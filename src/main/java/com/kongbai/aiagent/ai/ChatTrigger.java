package com.kongbai.aiagent.ai;

import com.kongbai.aiagent.task.CommandSink;
import com.kongbai.aiagent.util.PermissionGuard;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private ChatTrigger() {
    }

    /**
     * 分发一次对话。
     *
     * @param player   触发的玩家，不可为 {@code null}
     * @param question 玩家的问题；为空时会提示用法
     */
    public static void dispatch(@NotNull ServerPlayer player, @NotNull String question) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            send(player, "§7想让我做什么？例如：§f你好，ai 让 bot1 开始挖矿");
            return;
        }

        int permLevel = PermissionGuard.levelOf(player.createCommandSourceStack());
        long startTick = server.getTickCount();

        // 命令投递器：本次调用内有效，不被长期保存
        CommandSink sink = (command, level) -> {
            try {
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withPermission(Math.max(0, Math.min(4, level))),
                        command);
                return true;
            } catch (Throwable t) {
                LOGGER.warn("[假人智能] 命令执行失败 /{} : {}", command, t.getMessage());
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
