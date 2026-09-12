package com.kongbai.aiagent.ai;

import com.kongbai.aiagent.config.AiProfile;
import com.kongbai.aiagent.config.ProfileManager;
import com.kongbai.aiagent.machine.Machine;
import com.kongbai.aiagent.machine.MachineRegistry;
import com.kongbai.aiagent.task.CommandSink;
import com.kongbai.aiagent.task.Scheduler;
import com.kongbai.aiagent.task.TaskRegistry;
import com.kongbai.aiagent.util.Auditor;
import com.kongbai.aiagent.util.PermissionGuard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 对话引擎：把「玩家说的话」变成「实际执行的命令」。
 *
 * <p><b>执行流程</b>：
 * <pre>
 * 玩家说话 / 命令
 *   → 取该玩家的 AI 配置
 *   → 异步请求 AI（不阻塞主线程）
 *   → 回到主线程：解析 JSON 计划
 *   → 逐条过 PermissionGuard
 *   → 降权执行 / 注册长期任务
 *   → 把结果反馈给玩家
 * </pre>
 *
 * <p><b>线程安全（关键）</b>：
 * AI 请求在 HTTP 线程池完成，但<b>执行命令必须切回游戏主线程</b>。
 * 因此本类的执行方法要求调用方传入一个 {@code mainThreadRunner}，
 * 由调用方决定如何切线程（通常是 {@code server.execute(...) }）。
 * 这样本类不依赖 Minecraft 类，也不持有服务器引用。
 *
 * <p><b>防提权</b>：
 * <ol>
 *   <li>AI 输出先由 {@link AiPlan} 解析，命令条数受 {@code MAX_COMMANDS_PER_RESPONSE} 限制</li>
 *   <li><b>每条命令</b>过 {@link PermissionGuard#check(String)}，被拒的单独提示，不影响其他命令</li>
 *   <li>执行时用「发起请求的玩家」的权限等级降权，而非 OP</li>
 * </ol>
 *
 * <p><b>内存安全</b>：不持有 {@code MinecraftServer} / {@code ServerPlayer}。
 * 所有游戏交互通过 {@link CommandSink} 与回调接口完成，用完即走。
 */
public final class AgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");

    private static final AgentService INSTANCE = new AgentService();

    @NotNull
    private volatile AiProvider provider = new OpenAiProvider();

    private AgentService() {
    }

    @NotNull
    public static AgentService getInstance() {
        return INSTANCE;
    }

    /** 替换 AI 实现（预留给将来接其他协议）。 */
    public void setProvider(@NotNull AiProvider newProvider) {
        if (newProvider != null) {
            this.provider = newProvider;
        }
    }

    @NotNull
    public AiProvider provider() {
        return provider;
    }

    /** 服务器关闭时调用：关闭 HTTP 线程池。 */
    public void shutdown() {
        provider.shutdown();
    }

    /**
     * 处理一次玩家输入。
     *
     * @param uuid       玩家 UUID，不可为 {@code null}
     * @param message    玩家说的话
     * @param permLevel  玩家权限等级（执行时用，防提权）
     * @param startTick  当前游戏刻
     * @param sink       命令投递器（用于立即执行的命令）
     * @param mainThread 把任务切回主线程的适配器
     * @param feedback   结果反馈回调（在主线程调用）
     */
    public void handle(@Nullable UUID uuid, @Nullable String message, int permLevel, long startTick,
                       @Nullable CommandSink sink, @NotNull MainThreadRunner mainThread,
                       @NotNull Feedback feedback) {
        if (uuid == null) {
            mainThread.run(() -> feedback.onFailure("无法确定你的身份"));
            return;
        }
        if (message == null || message.isBlank()) {
            mainThread.run(() -> feedback.onFailure("你想让我做什么？"));
            return;
        }

        AiProfile profile = ProfileManager.getInstance().get(uuid);
        if (profile == null || !profile.isConfigured()) {
            mainThread.run(() -> feedback.onFailure("你还没有配置 AI。用法：/carpet ai api set <地址> <模型> [密钥]"));
            return;
        }

        // 带上任务名与机器状态，让 AI 知道现状再决定怎么操作
        List<String> taskNames = TaskRegistry.getInstance().names();
        List<String> machineStates = new ArrayList<>();
        for (Machine machine : MachineRegistry.getInstance().all()) {
            if (machine == null) {
                continue;
            }
            machineStates.add(machine.name() + "=" + machine.state().display());
        }
        String prompt = AiPrompts.withFullContext(message.trim(), taskNames, machineStates);

        provider.chat(profile, prompt).whenComplete((raw, throwable) -> {
            // 注意：这里仍在 HTTP 线程，必须切回主线程才能动游戏
            if (throwable != null) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                String text = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                String finalText = text;
                mainThread.run(() -> feedback.onFailure("AI 调用失败：" + finalText));
                return;
            }
            mainThread.run(() -> applyPlan(raw, uuid, permLevel, startTick, sink, feedback));
        });
    }

    /**
     * 应用 AI 计划（<b>必须在主线程调用</b>）。
     */
    private void applyPlan(@Nullable String raw, @NotNull UUID uuid, int permLevel, long startTick,
                           @Nullable CommandSink sink, @NotNull Feedback feedback) {
        AiPlan plan = AiPlan.parse(raw);
        if (plan == null) {
            // 不是 JSON：当作纯文本回复展示，绝不执行命令
            String text = raw == null ? "" : raw.trim();
            if (text.isEmpty()) {
                feedback.onFailure("AI 没有返回内容");
            } else {
                // 截断超长文本，避免刷屏
                if (text.length() > 400) {
                    text = text.substring(0, 400) + "...";
                }
                feedback.onReplyOnly(text);
            }
            return;
        }

        if (!plan.hasCommands()) {
            String reply = plan.reply();
            feedback.onReplyOnly(reply == null ? "（AI 没有给出具体操作）" : reply);
            return;
        }

        int executed = 0;
        int blocked = 0;
        StringBuilder blockReason = new StringBuilder();
        for (String command : plan.commands()) {
            if (command == null) {
                continue;
            }
            String reason = PermissionGuard.check(command);
            if (reason != null) {
                blocked++;
                if (blockReason.length() == 0) {
                    blockReason.append(reason);
                }
                LOGGER.warn("[ai-agent] 已拦截 AI 命令 /{} : {}", PermissionGuard.rootOf(command), reason);
                Auditor.getInstance().record(startTick, null, permLevel, command,
                        Auditor.Result.BLOCKED, Auditor.Source.AI, reason);
                continue;
            }
            if (sink == null) {
                feedback.onFailure("执行器未就绪");
                return;
            }
            if (sink.execute(command, permLevel)) {
                executed++;
            }
        }

        // 长期任务：注册到调度器，到点自动停止
        Long scheduleId = null;
        if (plan.isLongRunning() && executed > 0) {
            Scheduler scheduler = Scheduler.getInstance();
            scheduleId = scheduler.schedule(plan, uuid, permLevel, startTick, null);
        }

        String reply = plan.reply();
        feedback.onExecuted(plan, executed, blocked,
                blockReason.length() == 0 ? null : blockReason.toString(),
                scheduleId, reply);
    }

    /**
     * 把任务切回游戏主线程的适配器。
     *
     * <p>由 {@code AiAgentMod} 用 {@code server.execute(...)} 实现。
     * 抽象成接口是为了让本类不依赖 Minecraft 类。
     */
    public interface MainThreadRunner {
        void run(@NotNull Runnable task);
    }

    /**
     * 结果反馈（在主线程调用）。
     */
    public interface Feedback {
        /** AI 调用失败。 */
        void onFailure(@NotNull String reason);

        /** AI 只是说话，没有可执行命令。 */
        void onReplyOnly(@NotNull String text);

        /** 已执行命令，汇报结果。 */
        void onExecuted(@NotNull AiPlan plan, int executed, int blocked,
                        @Nullable String blockReason, @Nullable Long scheduleId,
                        @Nullable String reply);
    }
}
