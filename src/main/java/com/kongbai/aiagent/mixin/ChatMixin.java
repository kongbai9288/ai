package com.kongbai.aiagent.mixin;

import com.kongbai.aiagent.ai.ChatTrigger;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 拦截玩家聊天，实现「你好，ai + 话」触发。
 *
 * <p><b>为什么用 mixin 而不是 Fabric API 的聊天事件</b>：
 * Fabric API 的 {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE} 第三个参数类型
 * 在 26.2 的官方命名下不确定，写错会直接编译失败。
 * 而 mixin 用 {@code require = 0}：方法签名对不上时被静默跳过，<b>不影响编译</b>。
 *
 * <p><b>切入点已用 javap 核对</b>（MC 26.2 官方命名）：
 * {@code ServerGamePacketListenerImpl.handleChat(ServerboundChatPacket)} 存在，
 * 且 {@code ServerboundChatPacket.message()} 可用。
 *
 * <p><b>关于反射</b>：只剩一处 —— 从监听器实例取 {@code ServerPlayer}。
 * 该字段在 26.2 中确为 {@code public ServerPlayer player}（已核对），
 * 但历史上叫法不一（{@code c} / {@code this$0} 等），因此仍走反射 + 缓存，
 * 找不到就降级。而<b>服务器实例不再用反射</b> —— 见 {@link #resolveServer}。
 *
 * <p><b>降级链</b>（每一层都保证「AI 功能变弱，但游戏不受影响」）：
 * <ol>
 *   <li>{@code handleChat} 方法名对不上 → mixin 被跳过，聊天不触发</li>
 *   <li>反射拿不到 player 字段 → 聊天不触发</li>
 *   <li>以上都失败 → 玩家仍可用 {@code /carpet ai ask <话>} 与 AI 对话</li>
 * </ol>
 *
 * <p><b>不取消原消息</b>：聊天内容照常广播，玩家能回看自己说了什么。
 *
 * <p><b>异常隔离</b>：整个方法体包 try-catch，聊天路径的异常绝不能阻断玩家发言。
 *
 * <p><b>不持有引用</b>：无实例字段；只缓存一个反射 {@link Field}（静态，生命周期与类相同），
 * 不引用任何玩家或服务器对象。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ChatMixin {

    /** 触发前缀，取最先命中的那个。兼容中英文逗号与无逗号写法。 */
    private static final String[] TRIGGERS = {"你好，ai", "你好,ai", "你好ai", "hi,ai", "hi ai"};

    /**
     * 缓存的 player 字段；{@code null} 表示「找不到或取不到」。
     *
     * <p>配合 {@link #playerFieldResolved} 区分「尚未查找」与「已确认找不到」，
     * 避免每次聊天都做一次全字段反射扫描。
     *
     * <p><b>不用哨兵 Field</b>：先前版本拿本类的 {@code playerField} 字段自己当
     * 「未找到」哨兵，一旦有人重命名/删除该字段，静态初始化就会抛
     * {@code ExceptionInInitializerError}，导致整个 mixin 类加载失败。
     * 用一个 boolean 标记语义清晰且无此风险。
     */
    private static volatile Field playerField;
    private static volatile boolean playerFieldResolved = false;

    @Inject(method = "handleChat", at = @At("HEAD"), require = 0)
    private void aiagent$onChat(ServerboundChatPacket packet, CallbackInfo ci) {
        try {
            if (packet == null) {
                return;
            }
            String raw = packet.message();
            if (raw == null || raw.isBlank()) {
                return;
            }
            if (raw.startsWith("/")) {
                return; // 命令在 26.2 走 handleChatCommand，这里兜个底
            }

            int matchIndex = -1;
            int matchedLength = 0;
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            for (String trigger : TRIGGERS) {
                int index = lower.indexOf(trigger);
                if (index >= 0 && (matchIndex < 0 || index < matchIndex)) {
                    matchIndex = index;
                    matchedLength = trigger.length();
                }
            }
            if (matchIndex < 0) {
                return;
            }
            String question = raw.substring(matchIndex + matchedLength).trim();

            ServerPlayer player = resolvePlayer(this);
            if (player == null) {
                return;
            }
            MinecraftServer server = resolveServer(player);
            if (server == null) {
                return;
            }
            ChatTrigger.dispatch(server, player, question);
        } catch (Throwable t) {
            // 吞掉一切异常：聊天触发绝不能阻断玩家发言
        }
    }

    /**
     * 通过反射从监听器实例中取玩家（结果缓存）。
     *
     * @return 玩家实例；字段不存在或类型不符时返回 {@code null}
     */
    @Nullable
    private static ServerPlayer resolvePlayer(@NotNull Object listener) {
        Field field = playerField;
        if (field == null && !playerFieldResolved) {
            field = lookupPlayerField();
            playerField = field;
            playerFieldResolved = true;
        }
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(listener);
            return value instanceof ServerPlayer player ? player : null;
        } catch (Throwable t) {
            // 取不到就永久放弃，避免每条聊天都重试
            playerField = null;
            playerFieldResolved = true;
            return null;
        }
    }

    /**
     * 取服务器实例。
     *
     * <p><b>不再用反射</b>：先前版本会遍历 {@code ServerGamePacketListenerImpl}
     * 及其父类的全部字段找一个 {@code MinecraftServer} 类型成员，
     * 而该字段实际位于父类 {@code ServerCommonPacketListenerImpl} 且是
     * {@code protected}。这条路径有两个问题：
     * <ul>
     *   <li><b>慢</b> —— 每次玩家发言都要扫一遍几十个字段，且与 player 字段的缓存策略不一致</li>
     *   <li><b>脆</b> —— 依赖字段类型精确匹配，字段名/类型一变就静默失效
     *       （而 {@code require = 0} 恰好会吞掉这个失败，玩家只会感觉「AI 不理我」）</li>
     * </ul>
     * 既然已经拿到了 {@code ServerPlayer}，直接用公开 API
     * {@code player.level().getServer()} 即可，稳定且无需反射。
     */
    @Nullable
    private static MinecraftServer resolveServer(@NotNull ServerPlayer player) {
        try {
            return player.level().getServer();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在本类及其父类中查找类型为 ServerPlayer 的第一个字段。 */
    @Nullable
    private static Field lookupPlayerField() {
        Class<?> current = ServerGamePacketListenerImpl.class;
        while (current != null && current != Object.class) {
            for (Field declared : current.getDeclaredFields()) {
                if (declared.getType().equals(ServerPlayer.class)) {
                    try {
                        declared.setAccessible(true);
                        return declared;
                    } catch (Throwable ignored) {
                        // 无法访问（如模块限制），继续找下一个
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
