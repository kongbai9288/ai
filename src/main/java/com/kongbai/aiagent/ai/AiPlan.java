package com.kongbai.aiagent.ai;

import com.google.gson.JsonElement;
import com.kongbai.aiagent.util.JsonUtil;
import com.kongbai.aiagent.util.PermissionGuard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * AI 返回的动作计划。
 *
 * <p><b>JSON 契约</b>（用户指定的事件格式）：
 * <pre>
 * {
 *   "longRunning": true,        // 是否长期执行
 *   "deadline": -1,             // 执行终止时间（秒）；-1 = 一直
 *   "fakePlayer": "bot1",       // 假人名
 *   "commands": ["..."],        // 执行命令列表
 *   "reply": "已开启"            // 给玩家的话
 * }
 * </pre>
 *
 * <p><b>兼容中文键</b>：同时接受 {@code 是否长期执行} / {@code 执行终止时间} /
 * {@code 假人名} / {@code 执行命令}。AI 有时会照着提示词里的中文输出键名，
 * 多接受一套能显著提升成功率（这是实战中会遇到的真实情况）。
 *
 * <p><b>不可变</b>：解析完成后内容固定，跨线程安全。
 *
 * <p><b>空指针约束</b>：{@link #commands()} 永不为 {@code null}（空列表代替）；
 * {@link #fakePlayer()} 可能为 {@code null}（AI 未指定）。
 */
public final class AiPlan {

    /** 单次计划允许的毫秒级上限：2 小时。超过视为异常输入，截断。 */
    public static final long MAX_DEADLINE_SECONDS = 2 * 60 * 60;

    private final boolean longRunning;
    private final long deadlineSeconds;
    @Nullable
    private final String fakePlayer;
    private final List<String> commands;
    @Nullable
    private final String reply;

    private AiPlan(boolean longRunning, long deadlineSeconds, @Nullable String fakePlayer,
                   List<String> commands, @Nullable String reply) {
        this.longRunning = longRunning;
        this.deadlineSeconds = deadlineSeconds;
        this.fakePlayer = fakePlayer;
        this.commands = commands;
        this.reply = reply;
    }

    /** 只有一句话、没有命令的计划。用于 AI 反问或说明做不到的场景。 */
    @NotNull
    public static AiPlan replyOnly(@Nullable String reply) {
        return new AiPlan(false, 0, null, Collections.emptyList(), reply);
    }

    /**
     * 从 AI 的回复文本中解析计划。
     *
     * <p>处理顺序：
     * <ol>
     *   <li>整个回复就是 JSON → 直接解析</li>
     *   <li>回复中含 ```json 代码块 → 提取解析</li>
     *   <li>回复中含首个 {@code {...}} → 提取解析</li>
     *   <li>都失败 → 返回 {@code null}（调用方当作纯文本回复展示，不执行任何命令）</li>
     * </ol>
     *
     * @param raw AI 原始回复，允许为 {@code null}
     * @return 解析出的计划；无法解析返回 {@code null}
     */
    @Nullable
    public static AiPlan parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement element = JsonUtil.tryParse(raw.trim());
        if (element == null) {
            element = extractFromCodeBlock(raw);
        }
        if (element == null) {
            element = extractFirstObject(raw);
        }
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        return fromJsonObject(element.getAsJsonObject());
    }

    @Nullable
    private static JsonElement extractFromCodeBlock(@NotNull String text) {
        int start = text.indexOf("```");
        if (start < 0) {
            return null;
        }
        int contentStart = text.indexOf('\n', start);
        if (contentStart < 0) {
            return null;
        }
        int end = text.indexOf("```", contentStart);
        if (end < 0) {
            return null;
        }
        return JsonUtil.tryParse(text.substring(contentStart + 1, end).trim());
    }

    /** 提取第一个完整的 {...} 片段（简单的括号配平，不处理字符串内的花括号）。 */
    @Nullable
    private static JsonElement extractFirstObject(@NotNull String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return JsonUtil.tryParse(text.substring(start, i + 1));
                }
            }
        }
        return null;
    }

    @Nullable
    private static AiPlan fromJsonObject(@NotNull com.google.gson.JsonObject obj) {
        boolean longRunning = readBool(obj, "longRunning", "是否长期执行");
        long deadline = readLong(obj, "deadline", "执行终止时间");
        String fakePlayer = readString(obj, "fakePlayer", "假人名");
        String reply = readString(obj, "reply", "回复");

        List<String> commands = new ArrayList<>();
        var commandsElement = JsonUtil.path(obj, "commands");
        if (commandsElement == null) {
            commandsElement = JsonUtil.path(obj, "执行命令");
        }
        if (commandsElement != null && commandsElement.isJsonArray()) {
            for (var item : commandsElement.getAsJsonArray()) {
                if (item == null) {
                    continue;
                }
                String command = item.isJsonPrimitive() ? item.getAsString() : null;
                if (command != null && !command.isBlank()) {
                    if (commands.size() >= PermissionGuard.MAX_COMMANDS_PER_RESPONSE) {
                        break; // 超上限截断
                    }
                    commands.add(command.trim());
                }
            }
        }

        // 既没有命令也没有回复 → 视为无效计划，让调用方当作纯文本处理
        if (commands.isEmpty() && (reply == null || reply.isBlank())) {
            return null;
        }

        // 终止时间做范围收敛：负数统一为 -1（一直），过大的截断
        long normalizedDeadline;
        if (deadline < 0) {
            normalizedDeadline = -1L;
        } else if (deadline > MAX_DEADLINE_SECONDS) {
            normalizedDeadline = MAX_DEADLINE_SECONDS;
        } else {
            normalizedDeadline = deadline;
        }
        // deadline > 0 隐含长期执行
        boolean effectiveLongRunning = longRunning || normalizedDeadline > 0;

        return new AiPlan(effectiveLongRunning, normalizedDeadline,
                fakePlayer == null || fakePlayer.isBlank() ? null : fakePlayer.trim(),
                List.copyOf(commands),
                reply == null || reply.isBlank() ? null : reply.trim());
    }

    private static boolean readBool(@NotNull com.google.gson.JsonObject obj, String... keys) {
        for (String key : keys) {
            var element = JsonUtil.path(obj, key);
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            try {
                if (element.getAsJsonPrimitive().isBoolean()) {
                    return element.getAsBoolean();
                }
                // AI 可能输出 "true" 字符串或 1
                String text = element.getAsString().trim().toLowerCase(Locale.ROOT);
                return text.equals("true") || text.equals("1") || text.equals("yes");
            } catch (RuntimeException ignored) {
                // 类型不符，继续试下一个键
            }
        }
        return false;
    }

    private static long readLong(@NotNull com.google.gson.JsonObject obj, String... keys) {
        for (String key : keys) {
            var element = JsonUtil.path(obj, key);
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            try {
                return element.getAsLong();
            } catch (NumberFormatException | UnsupportedOperationException ignored) {
                // 继续试下一个键
            }
        }
        return 0L;
    }

    @Nullable
    private static String readString(@NotNull com.google.gson.JsonObject obj, String... keys) {
        for (String key : keys) {
            var element = JsonUtil.path(obj, key);
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            try {
                return element.getAsString();
            } catch (RuntimeException ignored) {
                // 继续试下一个键
            }
        }
        return null;
    }

    // ---------- 访问器 ----------

    public boolean isLongRunning() {
        return longRunning;
    }

    /**
     * 终止时间（秒）。-1 = 一直执行；&gt;0 = N 秒后停止；0 = 立即执行（非长期）。
     */
    public long deadlineSeconds() {
        return deadlineSeconds;
    }

    @Nullable
    public String fakePlayer() {
        return fakePlayer;
    }

    /** 命令列表，永不为 {@code null}。 */
    @NotNull
    public List<String> commands() {
        return commands;
    }

    @Nullable
    public String reply() {
        return reply;
    }

    public boolean hasCommands() {
        return !commands.isEmpty();
    }

    /**
     * 转成可展示的摘要。
     *
     * <p><b>命令内容会展示</b>：这是给玩家自己看的，且命令已经过白名单过滤，
     * 不含密钥等机密。
     */
    @NotNull
    public String describe() {
        StringBuilder builder = new StringBuilder();
        if (longRunning) {
            builder.append("长期任务");
            builder.append(deadlineSeconds < 0 ? "（一直执行）" : "（" + deadlineSeconds + " 秒后停止）");
        } else {
            builder.append("立即执行");
        }
        if (fakePlayer != null) {
            builder.append(" | 假人 ").append(fakePlayer);
        }
        if (!commands.isEmpty()) {
            builder.append(" | ").append(commands.size()).append(" 条命令");
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "AiPlan{" + describe() + "}";
    }
}
