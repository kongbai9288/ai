package com.kongbai.aiagent.ai;

import com.kongbai.aiagent.task.CommandSink;
import com.kongbai.aiagent.util.Auditor;
import com.kongbai.aiagent.util.PermissionGuard;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话触发入口。
 *
 * <p>被两处调用：
 * <ul>
 *   <li>{@code ChatMixin} —— 玩家在聊天里说「你好，ai ...」</li>
 *   <li>{@code /carpet ai ask <话>} —— 命令方式（聊天触发失效时的兜底）</li>
 * </ul>
 *
 * <p><b>线程处理（关键）</b>：
 * AI 请求是异步的，回调在 HTTP 线程。而「执行命令 / 给玩家发消息」
 * <b>必须在游戏主线程</b>。这里通过 {@code server.execute(() -> ...)}
 * 把结果处理切回主线程。
 *
 * <p><b>内存安全</b>：{@link #dispatch} 接收 {@code ServerPlayer} 参数但<b>不保存</b>，
 * 只在本次调用中使用。lambda 捕获的 {@code server} 与 {@code player} 会在
 * 回调执行完毕后随 lambda 一起被回收，不会造成长生命周期持有。
 *
 * <p><b>防提权</b>：执行 AI 命令时用<b>触发玩家的权限等级</b>降权，
 * 不是 OP 权限。见 {@link PermissionGuard#clamp} 与 {@code CommandSink} 实现。
 */
public final class ChatTrigger {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");

    /**
     * 同一玩家两次 AI 请求的最小间隔（毫秒）。
     *
     * <p>聊天触发没有任何权限/消耗门槛，玩家刷屏「你好ai」就能持续打爆自己的 API 配额，
     * 并让服务端堆积大量并发 HTTP 请求。这里做最基础的速率限制。
     */
    private static final long COOLDOWN_MS = 3000L;

    /**
     * 玩家 UUID -&gt; 上次发起请求的真实时间戳。
     *
     * <p><b>为什么用时间戳而非游戏刻</b>：限流要挡的是「真实世界的请求频率」，
     * 服务器卡顿（TPS 低）时游戏刻推进很慢，用刻会让限流形同虚设。
     *
     * <p><b>内存</b>：每个条目只是一个 UUID + long。玩家下线后不主动清理
     * （几十字节，可忽略），由 {@link #clearCooldowns()} 在服务器关闭时统一清空。
     */
    private static final Map<UUID, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();

    private ChatTrigger() {
    }

    /** 清空限流表（服务器关闭时调用，避免跨世界残留）。 */
    public static void clearCooldowns() {
        LAST_REQUEST_MS.clear();
    }

    /**
     * 分发一次对话。
     *
     * @param player   触发的玩家，不可为 {@code null}
     * @param question 玩家的问题；为空时会提示用法
     */
    public static void dispatch(@NotNull MinecraftServer server, @NotNull ServerPlayer player,
                                @NotNull String question) {
        if (server == null || player == null) {
            return;
        }
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            send(player, "§7想让我做什么？例如：§f你好，ai 让 bot1 开始挖矿");
            return;
        }

        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST_MS.get(uuid);
        // 限流：聊天触发没有任何门槛，不限制的话刷屏就能打爆玩家自己的 API 配额，
        // 并让服务端堆积大量并发 HTTP 请求。限的是「发起请求」而非「说话」。
        if (last != null && now - last < COOLDOWN_MS) {
            long wait = (COOLDOWN_MS - (now - last)) / 1000 + 1;
            send(player, "§7[假人智能] 请求太频繁，请 " + wait + " 秒后再试");
            return;
        }
        LAST_REQUEST_MS.put(uuid, now);

        int permLevel = PermissionGuard.levelOf(player.createCommandSourceStack());
        long startTick = server.getTickCount();

        // 命令投递器：本次调用内有效，不被长期保存
        //
        // 【关键】必须用 clamp 降权：server.createCommandSourceStack() 给的是服务器自身
        // （等级 4）权限。AI 回复属于完全不可信输入，若以 OP 身份执行，
        // 白名单一旦有疏漏就是完整的服务器提权。这里压到触发玩家自己的等级，
        // 再由 Brigadier 兜住第二道。
        CommandSink sink = (command, level) -> {
            try {
                CommandSourceStack stack =
                        PermissionGuard.clamp(server.createCommandSourceStack(), level);
                server.getCommands().performPrefixedCommand(stack, command);
                Auditor.getInstance().record(server.getTickCount(), player.getGameProfile().name(),
                        level, command, Auditor.Result.EXECUTED, Auditor.Source.AI, null);
                return true;
            } catch (Throwable t) {
                LOGGER.warn("[假人智能] 命令执行失败 /{} : {}", command, t.getMessage());
                Auditor.getInstance().record(server.getTickCount(), player.getGameProfile().name(),
                        level, command, Auditor.Result.FAILED, Auditor.Source.AI, t.getMessage());
                return false;
            }
        };

        send(player, "§8[假人智能] 思考中...");

        AgentService.getInstance().handle(
                player.getUUID(), text, permLevel, startTick, sink,
                // 切回主线程
                server::execute,
                new AgentService.Feedback() {
                    @Override
                    public void onFailure(@NotNull String reason) {
                        send(player, "§c[假人智能] " + reason);
                    }

                    @Override
                    public void onReplyOnly(@NotNull String reply) {
                        send(player, "§b[假人智能] §f" + reply);
                    }

                    @Override
                    public void onExecuted(@NotNull AiPlan plan, int executed, int blocked,
                                           @org.jetbrains.annotations.Nullable String blockReason,
                                           @org.jetbrains.annotations.Nullable Long scheduleId,
                                           @org.jetbrains.annotations.Nullable String reply) {
                        send(player, "§b[假人智能] §f" + (reply == null ? "已完成" : reply));
                        StringBuilder detail = new StringBuilder();
                        detail.append("§8执行 ").append(executed).append(" 条");
                        if (blocked > 0) {
                            detail.append("，拦截 ").append(blocked).append(" 条");
                        }
                        if (blockReason != null) {
                            detail.append("（").append(blockReason).append("）");
                        }
                        if (scheduleId != null) {
                            detail.append(" | 长期任务 #").append(scheduleId)
                                    .append(plan.deadlineSeconds() < 0 ? "（一直执行）"
                                            : "（" + plan.deadlineSeconds() + "s 后停）");
                        }
                        send(player, detail.toString());
                    }
                });
    }

    private static void send(@NotNull ServerPlayer player, @NotNull String text) {
        try {
            player.sendSystemMessage(Component.literal(text));
        } catch (Throwable t) {
            LOGGER.warn("[假人智能] 发送消息失败: {}", t.getMessage());
        }
    }
}
