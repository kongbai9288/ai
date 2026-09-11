package com.kongbai.aiagent.mixin;

import com.kongbai.aiagent.ai.ChatTrigger;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
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
 * <p><b>为什么用反射取 player</b>：
 * {@code ServerGamePacketListenerImpl} 中保存玩家实例的字段名
 * 在不同版本叫法不一（{@code player} / {@code c} / {@code this$0} 等）。
 * 用 {@code @Shadow} 硬写字段名，一旦版本对不上会在运行时抛异常；
 * 改用反射 + try-catch，找不到就静默降级，不会崩。
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
     * 缓存的 player 字段。
     *
     * <p>{@code null} = 尚未查找；{@code NOT_FOUND} = 已确认找不到（避免每次聊天都反射）。
     */
    private static volatile Field playerField;
    private static final Field NOT_FOUND;

    static {
        try {
            NOT_FOUND = ChatMixin.class.getDeclaredField("playerField");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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
                return; // 命令不走聊天触发
            }

            int matchIndex = -1;
            int matchedLength = 0;
            for (String trigger : TRIGGERS) {
                int index = raw.indexOf(trigger);
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
            MinecraftServer server = resolveServer(this);
            if (server == null) {
                return;
            }
            ChatTrigger.dispatch(server, player, question);
        } catch (Throwable t) {
            // 吞掉一切异常：聊天触发绝不能阻断玩家发言
        }
    }

    /**
     * 通过反射从监听器实例中取玩家。
     *
     * @return 玩家实例；字段不存在或类型不符时返回 {@code null}
     */
    @org.jetbrains.annotations.Nullable
    private static ServerPlayer resolvePlayer(@org.jetbrains.annotations.NotNull Object listener) {
        Field field = playerField;
        if (field == NOT_FOUND) {
            return null;
        }
        if (field == null) {
            field = lookupPlayerField();
            playerField = field;
            if (field == NOT_FOUND) {
                return null;
            }
        }
        try {
            Object value = field.get(listener);
            return value instanceof ServerPlayer player ? player : null;
        } catch (Throwable t) {
            // 取不到就放弃，不要反复重试
            playerField = NOT_FOUND;
            return null;
        }
    }

    /**
     * 通过反射从监听器实例中取服务器实例。
     *
     * <p>与取 player 同理：字段名在不同版本叫法不一，用反射 + try-catch 兜底。
     */
    @org.jetbrains.annotations.Nullable
    private static MinecraftServer resolveServer(@org.jetbrains.annotations.NotNull Object listener) {
        try {
            Class<?> current = ServerGamePacketListenerImpl.class;
            while (current != null && current != Object.class) {
                for (Field declared : current.getDeclaredFields()) {
                    if (declared.getType().equals(MinecraftServer.class)) {
                        try {
                            declared.setAccessible(true);
                            Object value = declared.get(listener);
                            return value instanceof MinecraftServer ms ? ms : null;
                        } catch (Throwable ignored) {
                            // 继续找
                        }
                    }
                }
                current = current.getSuperclass();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在类及其父类中查找类型为 ServerPlayer 的第一个字段。 */
    @org.jetbrains.annotations.NotNull
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
        return NOT_FOUND;
    }
}
