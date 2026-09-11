package com.kongbai.aiagent.ai;

import com.kongbai.aiagent.config.AiProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * AI 接口抽象。
 *
 * <p><b>为什么抽象成接口</b>：让「对话引擎」与「具体厂商协议」解耦。
 * 目前只实现 OpenAI 兼容格式（覆盖 OpenAI / DeepSeek / 通义 / 本地 Ollama），
 * 将来接其他协议时只需新增实现类，不用动执行逻辑。
 *
 * <p><b>线程约定（重要）</b>：
 * {@link #chat} 立即返回 {@link CompletableFuture}，请求在线程池中异步执行，
 * <b>绝不阻塞游戏主线程</b>。调用方拿到 future 后，
 * 必须把「改世界 / 执行命令 / 发消息」的操作切回主线程（{@code server.execute}）。
 *
 * <p><b>失败语义</b>：任何失败（网络错误、HTTP 非 2xx、返回体无法解析）
 * 都通过 {@link AiException} 以异常形式从 future 抛出，
 * 由调用方转成玩家可见的提示。<b>不返回 null</b>。
 */
public interface AiProvider {

    /**
     * 发起一次对话。
     *
     * @param profile 使用的配置（含地址、模型、密钥、超时），不可为 {@code null}
     * @param userMessage 用户输入，不可为 {@code null}；为空串时由实现方自行处理
     * @return 异步结果；失败时以 {@link AiException} 完成
     */
    @NotNull
    CompletableFuture<String> chat(@NotNull AiProfile profile, @NotNull String userMessage);

    /** 该实现支持的系统提示词注入方式标记。当前仅用于日志区分。 */
    @NotNull
    default String name() {
        return "openai-compatible";
    }

    /**
     * 关闭底层资源（线程池、连接池）。
     *
     * <p>服务器关闭时调用。不调用不会导致功能错误，
     * 但可能让 JVM 在退出时等待非守护线程。
     */
    default void shutdown() {
    }

    /**
     * AI 调用失败的统一异常。
     *
     * <p><b>为什么是 RuntimeException（非受检）</b>：
     * 这类异常主要在 {@link java.util.concurrent.CompletableFuture}
     * 的 lambda（{@code thenApply} / {@code whenComplete}）中抛出，
     * 而 lambda 的方法签名不允许抛受检异常。
     * 改成非受检后可以直接在 lambda 里抛，由 {@code whenComplete} 统一捕获。
     *
     * <p><b>消息必须可展示给玩家</b> —— 因此会抹去密钥等敏感信息，
     * 且不允许把原始响应体（可能含密钥回显）直接放进消息。
     */
    final class AiException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AiException(@Nullable String message) {
            super(message == null ? "AI 调用失败" : message);
        }

        public AiException(@NotNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
