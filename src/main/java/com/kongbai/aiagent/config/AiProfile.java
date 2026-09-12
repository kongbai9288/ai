package com.kongbai.aiagent.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个玩家的 AI 接入配置（不可变值对象）。
 *
 * <p><b>为什么不可变</b>：配置会被异步 AI 请求线程读取（见 M3 的 {@code AiProvider}），
 * 可变对象在「玩家改配置」与「请求线程读配置」之间会产生读写竞争。做成不可变后，
 * 读取方拿到的是一个稳定快照，天然线程安全，无需加锁。
 *
 * <p><b>内存安全</b>：只持有 {@link UUID} 与 {@link String}，
 * 绝不持有 {@code ServerPlayer} / {@code MinecraftServer} / {@code ServerLevel}，
 * 避免玩家下线后对象无法回收。
 *
 * <p><b>字段约束（构造时校验，非法即抛，不允许脏数据进入系统）</b>：
 * <ul>
 *   <li>{@code baseUrl} 必须是 http/https —— 防 SSRF 与 file:// 等协议被误用</li>
 *   <li>{@code apiKey} 长度上限 512 —— 防止恶意超长输入吃内存</li>
 *   <li>{@code timeoutMs} 落在 [1000, 120000] —— 0 或负数会让 HTTP 客户端无限等待</li>
 *   <li>{@code temperature} 落在 [0, 2] —— OpenAI 兼容接口的合法区间</li>
 * </ul>
 */
public final class AiProfile {

    public static final int MAX_API_KEY_LENGTH = 512;
    public static final int MAX_URL_LENGTH = 512;
    public static final int MAX_MODEL_LENGTH = 128;
    public static final int MIN_TIMEOUT_MS = 1_000;
    public static final int MAX_TIMEOUT_MS = 120_000;
    public static final float MIN_TEMPERATURE = 0.0f;
    public static final float MAX_TEMPERATURE = 2.0f;

    /** 缺省超时：20 秒。低于此值大模型长回复容易超时，高于此值玩家等待感差。 */
    public static final int DEFAULT_TIMEOUT_MS = 20_000;
    public static final float DEFAULT_TEMPERATURE = 0.3f;

    private final UUID ownerUuid;
    private final String ownerName;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final int timeoutMs;
    private final float temperature;

    private AiProfile(UUID ownerUuid, String ownerName, String baseUrl, String model,
                      String apiKey, int timeoutMs, float temperature) {
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.ownerName = ownerName == null ? "" : ownerName;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.temperature = temperature;
    }

    /**
     * 构造配置。任一参数非法都抛 {@link IllegalArgumentException}，由命令层转成玩家可见提示。
     *
     * @param ownerUuid 归属玩家，不可为 {@code null}
     * @param ownerName 归属玩家名（仅用于展示，允许 {@code null}/空）
     * @param baseUrl   API 根地址，如 {@code https://api.openai.com/v1}
     * @param model     模型名，如 {@code gpt-4o-mini}
     * @param apiKey    密钥，允许空串（部分本地服务如 Ollama 不需要）
     * @param timeoutMs 请求超时（毫秒）
     * @param temperature 采样温度
     */
    @NotNull
    public static AiProfile of(@NotNull UUID ownerUuid, @Nullable String ownerName,
                               @NotNull String baseUrl, @NotNull String model,
                               @Nullable String apiKey, int timeoutMs, float temperature) {
        return new AiProfile(
                ownerUuid,
                ownerName,
                sanitizeUrl(baseUrl),
                sanitizeModel(model),
                sanitizeApiKey(apiKey),
                clamp(timeoutMs, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, "timeoutMs"),
                clampFloat(temperature, MIN_TEMPERATURE, MAX_TEMPERATURE, "temperature")
        );
    }

    /** 便捷构造：使用默认超时与温度。 */
    @NotNull
    public static AiProfile of(@NotNull UUID ownerUuid, @Nullable String ownerName,
                               @NotNull String baseUrl, @NotNull String model, @Nullable String apiKey) {
        return of(ownerUuid, ownerName, baseUrl, model, apiKey, DEFAULT_TIMEOUT_MS, DEFAULT_TEMPERATURE);
    }

    /** 空配置（玩家尚未设置）。用于「未配置」时的占位，避免到处判 null。 */
    @NotNull
    public static AiProfile empty(@NotNull UUID ownerUuid, @Nullable String ownerName) {
        return new AiProfile(ownerUuid, ownerName, "", "", "", DEFAULT_TIMEOUT_MS, DEFAULT_TEMPERATURE);
    }

    // ---------- 校验 ----------

    @NotNull
    private static String sanitizeUrl(@NotNull String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("API 地址过长（上限 " + MAX_URL_LENGTH + " 字符）");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        // 只放行 http/https，挡掉 file://、jar://、ftp:// 等可能被滥用的协议
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("API 地址必须以 http:// 或 https:// 开头");
        }
        // 去掉结尾多余的 /，避免拼出 //chat/completions
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    @NotNull
    private static String sanitizeModel(@NotNull String raw) {
        String model = raw == null ? "" : raw.trim();
        if (model.isEmpty()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        if (model.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("模型名过长（上限 " + MAX_MODEL_LENGTH + " 字符）");
        }
        return model;
    }

    @NotNull
    private static String sanitizeApiKey(@Nullable String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("API Key 过长（上限 " + MAX_API_KEY_LENGTH + " 字符）");
        }
        return key;
    }

    private static int clamp(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 " + min + " ~ " + max + " 之间");
        }
        return value;
    }

    private static float clampFloat(float value, float min, float max, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 " + min + " ~ " + max + " 之间");
        }
        return value;
    }

    // ---------- 访问器 ----------

    @NotNull
    public UUID ownerUuid() {
        return ownerUuid;
    }

    /** 归属玩家名；未记录时返回空串，永不为 {@code null}。 */
    @NotNull
    public String ownerName() {
        return ownerName;
    }

    @NotNull
    public String baseUrl() {
        return baseUrl;
    }

    @NotNull
    public String model() {
        return model;
    }

    @NotNull
    public String apiKey() {
        return apiKey;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public float temperature() {
        return temperature;
    }

    /**
     * 是否已配置到可用状态。
     * baseUrl 与 model 是发起请求的必要条件；apiKey 可空（本地模型常见）。
     */
    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }

    /**
     * 脱敏后的 key，用于聊天栏展示，避免密钥被截图外泄。
     * 8 字符及以下全打码；长 key 保留前 4 后 4。
     */
    @NotNull
    public String maskedApiKey() {
        if (apiKey.isEmpty()) {
            return "(未设置)";
        }
        int len = apiKey.length();
        if (len <= 8) {
            return "*".repeat(len);
        }
        return apiKey.substring(0, 4) + "*".repeat(Math.min(len - 8, 16)) + apiKey.substring(len - 4);
    }

    /** 补全后的 chat 接口地址。调用方无需关心用户填的地址是否带 /v1。 */
    @NotNull
    public String chatEndpoint() {
        String base = baseUrl;
        // 用户常填成 ".../v1/"（带尾斜杠），直接拼接会得到 ".../v1//chat/completions"。
        // 虽然多数服务端能容忍，但严格来说不是同一个路径，这里统一去掉。
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    // ---------- 序列化 ----------

    @NotNull
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("ownerUuid", ownerUuid.toString());
        obj.addProperty("ownerName", ownerName);
        obj.addProperty("baseUrl", baseUrl);
        obj.addProperty("model", model);
        obj.addProperty("apiKey", apiKey);
        obj.addProperty("timeoutMs", timeoutMs);
        obj.addProperty("temperature", temperature);
        return obj;
    }

    /**
     * 从 JSON 反序列化。
     *
     * @param obj JSON 对象；为 {@code null} 或缺少 ownerUuid 时返回 {@code null}
     * @return 配置实例；解析失败返回 {@code null}（不抛异常，单条配置损坏不应带崩整个加载流程）
     */
    @Nullable
    public static AiProfile fromJson(@Nullable JsonObject obj) {
        if (obj == null) {
            return null;
        }
        String uuidText = JsonUtil.stringOr(obj, "", "ownerUuid");
        if (uuidText.isEmpty()) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String baseUrl = JsonUtil.stringOr(obj, "", "baseUrl");
        String model = JsonUtil.stringOr(obj, "", "model");
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return null;
        }
        String apiKey = JsonUtil.stringOr(obj, "", "apiKey");
        int timeoutMs = readIntOr(obj, "timeoutMs", DEFAULT_TIMEOUT_MS);
        float temperature = readFloatOr(obj, "temperature", DEFAULT_TEMPERATURE);

        try {
            return AiProfile.of(uuid,
                    JsonUtil.stringOr(obj, "", "ownerName"),
                    baseUrl, model, apiKey, timeoutMs, temperature);
        } catch (IllegalArgumentException e) {
            // 磁盘上的历史数据可能不合法：跳过这一条，不要污染内存中的配置表
            return null;
        }
    }

    /**
     * 安全读取整数字段。
     *
     * <p>磁盘上的配置可能被玩家手改成任意内容（例如把 timeoutMs 写成 "快一点"），
     * 直接 {@code getAsInt()} 会抛 {@link NumberFormatException}，
     * 进而让整份配置加载失败。这里统一回落默认值。
     */
    private static int readIntOr(@Nullable JsonObject obj, @NotNull String key, int fallback) {
        JsonElement element = JsonUtil.path(obj, key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    /** 安全读取浮点字段，同 {@link #readIntOr}。NaN/Infinity 也回落。 */
    private static float readFloatOr(@Nullable JsonObject obj, @NotNull String key, float fallback) {
        JsonElement element = JsonUtil.path(obj, key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            float value = element.getAsFloat();
            return (Float.isNaN(value) || Float.isInfinite(value)) ? fallback : value;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    /**
     * 生成副本，仅替换超时与温度。
     * 用于 {@code /carpet api timeout} 这类局部调整。
     *
     * @throws IllegalStateException 尚未配置（baseUrl/model 为空）时抛出。
     *         调用方 {@code AiCommand} 已先用 {@link #isConfigured()} 拦过，
     *         这里再抛一次是为了防止后续模块误用在「空配置」上而静默产生脏数据。
     */
    @NotNull
    public AiProfile withTuning(int newTimeoutMs, float newTemperature) {
        if (!isConfigured()) {
            throw new IllegalStateException("未配置的 AiProfile 无法调整参数");
        }
        return AiProfile.of(ownerUuid, ownerName, baseUrl, model, apiKey, newTimeoutMs, newTemperature);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AiProfile other)) {
            return false;
        }
        return timeoutMs == other.timeoutMs
                && Float.compare(temperature, other.temperature) == 0
                && ownerUuid.equals(other.ownerUuid)
                && baseUrl.equals(other.baseUrl)
                && model.equals(other.model)
                && apiKey.equals(other.apiKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerUuid, baseUrl, model, apiKey, timeoutMs, temperature);
    }

    @Override
    public String toString() {
        // 注意：不输出 apiKey 明文，防止被日志泄露
        return "AiProfile{owner=" + ownerUuid + ", name='" + ownerName + "', url=" + baseUrl
                + ", model=" + model + ", key=" + maskedApiKey() + "}";
    }
}
