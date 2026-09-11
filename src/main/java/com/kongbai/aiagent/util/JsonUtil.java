package com.kongbai.aiagent.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 读写工具。
 *
 * <p><b>职责边界</b>：只负责「文件 &lt;-&gt; JSON 树」的双向转换，不持有任何游戏对象。
 *
 * <p><b>失败语义约定（调用方必须知道）</b>：
 * <ul>
 *   <li>读：文件不存在 / 内容非法 -&gt; 返回 {@code null}，由调用方决定回落策略。不抛异常、不吞异常。</li>
 *   <li>写：采用「写临时文件 -&gt; 原子移动」策略。写入失败不影响已有文件内容（不会写成半截）。</li>
 * </ul>
 *
 * <p><b>空指针约束</b>：{@code path} 与 {@code gson} 均不可为 {@code null}；
 * 传入 {@code null} 的 {@code path} 会直接抛 NPE —— 这是有意为之，属于调用方 bug，不应静默。
 */
public final class JsonUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");

    /** 带缩进的 GSON，用于写文件（人可读，方便玩家手改配置）。 */
    public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    /** 紧凑 GSON，用于网络传输 / 日志。 */
    public static final Gson COMPACT = new GsonBuilder().create();

    private JsonUtil() {
    }

    /**
     * 从文件读取 JSON 树。
     *
     * @param path 目标文件，不可为 {@code null}
     * @return 解析成功的 JSON 树；文件不存在、内容为空、解析失败时统一返回 {@code null}
     */
    @Nullable
    public static JsonElement readTree(@NotNull Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || element.isJsonNull()) {
                return null;
            }
            return element;
        } catch (JsonParseException e) {
            LOGGER.warn("[ai-agent] JSON 格式错误，已忽略该文件: {} ({})", path, e.getMessage());
            return null;
        } catch (IOException e) {
            LOGGER.warn("[ai-agent] 读取文件失败: {} ({})", path, e.getMessage());
            return null;
        }
    }

    /**
     * 将 JSON 树原子写入文件。
     *
     * <p>先写 {@code <target>.tmp} 再 {@code ATOMIC_MOVE}；若文件系统不支持原子移动，
     * 退化为 {@code REPLACE_EXISTING} 的普通移动。任何一步失败都会尝试清理临时文件，
     * 且不会破坏原有文件。
     *
     * @param path 目标文件，不可为 {@code null}
     * @param tree 待写入的 JSON 树，不可为 {@code null}
     * @return 成功返回 {@code true}；失败返回 {@code false}（已记日志，调用方可选择是否提示玩家）
     */
    public static boolean writeTree(@NotNull Path path, @NotNull JsonElement tree) {
        Path parent = path.getParent();
        if (parent == null) {
            LOGGER.error("[ai-agent] 无法写入，路径没有父目录: {}", path);
            return false;
        }
        Path tmp = parent.resolve(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                PRETTY.toJson(tree, writer);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("[ai-agent] 写入文件失败: {} ({})", path, e.getMessage());
            cleanupQuietly(tmp);
            return false;
        }
    }

    /**
     * 尝试解析 JSON 字符串；失败返回 {@code null} 而不是抛异常。
     *
     * @param json 待解析文本，允许为 {@code null}（直接返回 {@code null}）
     */
    @Nullable
    public static JsonElement tryParse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(json);
        } catch (JsonParseException e) {
            return null;
        }
    }

    /**
     * 从 JSON 树中取成员；任一环节缺失或类型不符都返回 {@code null}。
     * 避免层层 {@code has()/isJsonObject()} 导致的样板代码与漏判。
     */
    @Nullable
    public static JsonElement path(@Nullable JsonElement root, @NotNull String... keys) {
        JsonElement current = root;
        for (String key : keys) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(key);
        }
        return current;
    }

    /** 取字符串成员，缺失或非字符串时返回 {@code fallback}。 */
    @NotNull
    public static String stringOr(@Nullable JsonElement root, @NotNull String fallback, @NotNull String... keys) {
        JsonElement element = path(root, keys);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return element.getAsString();
    }

    /** 删除临时文件，失败静默（临时文件残留不影响正确性，下次写入会被覆盖）。 */
    private static void cleanupQuietly(@Nullable Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // 无意为之：临时文件清理失败不视为错误
        }
    }

    /**
     * 按分隔符切分并去除首尾空白，过滤空串。
     * 用于解析玩家输入（例如 baseUrl 后的多余空格）。
     */
    @NotNull
    public static List<String> splitNonEmpty(@Nullable String input, char delimiter) {
        List<String> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        for (String part : input.split(String.valueOf(delimiter))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
