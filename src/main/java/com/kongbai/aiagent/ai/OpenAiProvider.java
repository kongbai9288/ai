package com.kongbai.aiagent.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kongbai.aiagent.config.AiProfile;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容格式的 AI 实现。
 *
 * <p>适用于 OpenAI、DeepSeek、通义千问、 moonshot、本地 Ollama / vLLM 等
 * 一切遵循 {@code POST /chat/completions} 协议的服务。
 *
 * <p><b>为什么用 JDK 内置 HttpClient</b>：不引入第三方 HTTP 库，
 * 避免与 Minecraft 自带的依赖冲突（MC 内部就有 Netty / Apache HttpClient，
 * 版本冲突是 Fabric 模组的常见崩溃原因）。
 *
 * <p><b>线程模型</b>：
 * <ul>
 *   <li>请求在独立线程池执行，<b>绝不阻塞游戏主线程</b></li>
 *   <li>线程池为守护线程，避免服务器关闭时 JVM 无法退出</li>
 *   <li>{@link #shutdown()} 会关闭线程池；服务器关闭时调用</li>
 * </ul>
 *
 * <p><b>内存安全</b>：{@link HttpClient} 长期持有（连接池复用），
 * 但<b>不持有任何游戏对象</b>；{@link AiProfile} 只是值对象。
 *
 * <p><b>密钥保护</b>：日志与异常消息中<b>绝不输出</b> apiKey 与完整请求体。
 */
public final class OpenAiProvider implements AiProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");

    /** 单次响应体大小上限（8MB）。防止异常服务返回巨量数据吃满内存。 */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    /** 用户输入最大长度，防止超长输入打爆 token 与费用。 */
    public static final int MAX_INPUT_LENGTH = 2000;

    private final ExecutorService pool;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public OpenAiProvider() {
        this.pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ai-agent-http");
            // 守护线程：即使忘记 shutdown，JVM 也能正常退出
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(pool)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    @NotNull
    public CompletableFuture<String> chat(@NotNull AiProfile profile, @NotNull String userMessage) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new AiException("AI 服务已关闭"));
        }
        if (profile == null || !profile.isConfigured()) {
            return CompletableFuture.failedFuture(new AiException("尚未配置 AI，请先 /carpet ai api set ..."));
        }
        String input = userMessage == null ? "" : userMessage;
        if (input.length() > MAX_INPUT_LENGTH) {
            input = input.substring(0, MAX_INPUT_LENGTH);
            LOGGER.warn("[ai-agent] 用户输入超过 {} 字符，已截断", MAX_INPUT_LENGTH);
        }

        String body = buildRequestBody(profile, input);
        URI uri;
        try {
            uri = URI.create(profile.chatEndpoint());
        } catch (IllegalArgumentException e) {
            // URI.create 对非法地址会抛 IllegalArgumentException
            return CompletableFuture.failedFuture(new AiException("API 地址非法: " + e.getMessage()));
        }
        String schemeError = checkScheme(uri);
        if (schemeError != null) {
            return CompletableFuture.failedFuture(new AiException(schemeError));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(profile.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        // 有密钥才带 Authorization：本地 Ollama 等不需要，空 Bearer 会被部分服务端拒
        if (!profile.apiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + profile.apiKey());
        }
        HttpRequest request;
        try {
            request = builder.build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new AiException("API 地址非法: " + e.getMessage()));
        }

        // 关键：用 ofInputStream 自己限量读取，而不是 ofString()。
        // ofString() 会把整个响应无上限地读入内存，之后再判断大小已经晚了 ——
        // 一个异常/恶意服务返回几 GB 数据就能先吃满堆。
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .orTimeout(profile.timeoutMs() + 5_000L, TimeUnit.MILLISECONDS)
                .thenApply(response -> parseResponse(response))
                // 把受检异常包装成 AiException，统一失败语义
                .exceptionally(throwable -> {
                    throw new java.util.concurrent.CompletionException(toAiException(throwable));
                });
    }

    /**
     * 只放行 http / https。
     *
     * <p><b>为什么需要</b>：地址完全由玩家在 {@code /carpet ai api set} 里自由填写，
     * 属于不可信输入。虽然 JDK 的 HttpClient 不支持 {@code file:} / {@code jar:}，
     * 但显式校验协议能挡住未来依赖变更带来的意外（也挡住 jar: 这类有已知绕过史的协议）。
     *
     * <p><b>不禁止内网地址</b>：这是有意的设计取舍 —— 大量玩家用局域网内的
     * Ollama / vLLM（{@code 192.168.x.x}），一刀切禁内网会让本地部署完全不可用。
     * 风险在 README 的「安全模型」章节中说明，由服主自行判断。
     */
    @Nullable
    private static String checkScheme(@NotNull URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return "API 地址缺少协议（需以 http:// 或 https:// 开头）";
        }
        String lower = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals("http") && !lower.equals("https")) {
            return "API 地址协议不允许: " + scheme + "（仅支持 http / https）";
        }
        return null;
    }

    /**
     * 限量读取响应体，最多 {@value #MAX_RESPONSE_BYTES} 字节。
     *
     * <p>边读边计数，超过上限立即停止并抛异常 —— 这样内存峰值就是上限值，
     * 而不是「先全部读进内存再判断」。
     */
    @NotNull
    private static String readBodyLimited(@NotNull java.io.InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0L;
        try (stream) {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new AiException("AI 响应过大（超过 " + (MAX_RESPONSE_BYTES / 1024 / 1024)
                            + " MB），已丢弃");
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @NotNull
    private static String buildRequestBody(@NotNull AiProfile profile, @NotNull String userMessage) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", userMessage);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", AiPrompts.systemPrompt());
        messages.add(system);
        messages.add(message);

        JsonObject root = new JsonObject();
        root.addProperty("model", profile.model());
        root.add("messages", messages);
        root.addProperty("temperature", profile.temperature());
        root.addProperty("stream", false);
        return root.toString();
    }

    /**
     * 解析响应。
     *
     * @throws AiException HTTP 非 2xx 或响应体结构不符预期
     */
    @NotNull
    private static String parseResponse(@NotNull HttpResponse<java.io.InputStream> response) {
        int status = response.statusCode();
        String body;
        try {
            body = readBodyLimited(response.body());
        } catch (AiException e) {
            throw e;
        } catch (IOException e) {
            throw new AiException("读取 AI 响应失败：" + e.getMessage());
        }
        if (status < 200 || status >= 300) {
            // 只透出状态码与精简的错误摘要，避免把密钥回显写进日志
            String brief = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            if (brief.length() > 200) {
                brief = brief.substring(0, 200) + "...";
            }
            throw new AiException("AI 服务返回 HTTP " + status + "：" + brief);
        }
        var parsed = JsonUtil.tryParse(body);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new AiException("AI 返回内容不是合法 JSON");
        }
        var choices = JsonUtil.path(parsed, "choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
            throw new AiException("AI 返回结果为空（choices 缺失）");
        }
        var content = JsonUtil.path(choices.getAsJsonArray().get(0).getAsJsonObject(), "message", "content");
        if (content == null) {
            throw new AiException("AI 返回内容缺少 message.content");
        }
        String text = content.isJsonPrimitive() ? content.getAsString() : content.toString();
        if (text == null || text.isBlank()) {
            throw new AiException("AI 返回内容为空");
        }
        return text;
    }

    @NotNull
    private static AiException toAiException(@NotNull Throwable throwable) {
        Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                && throwable.getCause() != null ? throwable.getCause() : throwable;
        if (cause instanceof AiException aiException) {
            return aiException;
        }
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return new AiException("AI 请求超时");
        }
        if (cause instanceof IOException) {
            return new AiException("无法连接 AI 服务：" + cause.getMessage());
        }
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt(); // 恢复中断标记
            return new AiException("AI 请求被中断");
        }
        return new AiException("AI 请求失败：" + cause.getMessage());
    }

    @Override
    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    @NotNull
    public String name() {
        return "openai-compatible";
    }
}
