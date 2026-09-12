package com.kongbai.aiagent.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 命令审计日志 —— 回答「AI 到底干了什么」。
 *
 * <p><b>为什么需要它</b>：本模组的命令来自两个<b>不可信来源</b> ——
 * 外部 AI 模型的回复，以及玩家录制后可能被他人回放的任务。
 * {@link PermissionGuard} 负责「拦不拦」，但它不回答事后追责问题：
 * 服务器被搞坏之后，管理员需要一个可回溯的清单。
 *
 * <p><b>设计约束</b>：
 * <ul>
 *   <li><b>有界内存</b>：用环形缓冲固定容量 {@value #CAPACITY}，
 *       超出自动丢弃最旧的 —— 审计不能自己变成内存泄漏</li>
 *   <li><b>绝不记录密钥</b>：命令根为 {@code ai}/{@code aiagent} 时只记根名与长度，
 *       因为 {@code /carpet ai api set ... <密钥>} 会把密钥写进日志</li>
 *   <li><b>不写磁盘</b>：仅在内存，重启即清。需要长期留存的由管理员自行落盘，
 *       避免本模组在存档目录里堆积不可控文件</li>
 * </ul>
 */
public final class Auditor {

    /** 保留的最大条目数。 */
    public static final int CAPACITY = 500;

    /** 命令来源。 */
    public enum Source {
        /** AI 对话产生。 */
        AI("AI"),
        /** 任务回放。 */
        PLAYBACK("回放"),
        /** 长期任务到期停止。 */
        SCHEDULER("调度"),
        /** 玩家直接触发。 */
        PLAYER("玩家");

        private final String label;

        Source(@NotNull String label) {
            this.label = label;
        }

        @NotNull
        public String label() {
            return label;
        }
    }

    /** 单条命令的处置结果。 */
    public enum Result {
        /** 通过闸门并已派发。 */
        EXECUTED("已执行", "§a"),
        /** 被闸门拦截。 */
        BLOCKED("已拦截", "§c"),
        /** 通过闸门但执行抛异常。 */
        FAILED("失败", "§c");

        private final String label;
        private final String color;

        Result(@NotNull String label, @NotNull String color) {
            this.label = label;
            this.color = color;
        }

        @NotNull
        public String label() {
            return label;
        }

        @NotNull
        public String color() {
            return color;
        }
    }

    /**
     * 一条审计记录（不可变）。
     *
     * @param wallMillis 真实时间戳（{@code System.currentTimeMillis()}）
     * @param tick       游戏刻
     * @param actor      发起者名字；未知时为 {@code null}
     * @param permLevel  执行时使用的权限等级
     * @param command    命令原文（已脱敏）
     * @param result     处置结果
     * @param source     来源
     * @param detail     补充说明（拦截原因、异常信息等），可为 {@code null}
     */
    public record Entry(
            long wallMillis,
            long tick,
            @Nullable String actor,
            int permLevel,
            @NotNull String command,
            @NotNull Result result,
            @NotNull Source source,
            @Nullable String detail
    ) {}

    private static final Auditor INSTANCE = new Auditor();

    private final Deque<Entry> entries = new ArrayDeque<>();

    private Auditor() {
    }

    @NotNull
    public static Auditor getInstance() {
        return INSTANCE;
    }

    /**
     * 记录一条审计。
     *
     * @param command 命令原文；为 {@code null}/空白时忽略
     */
    public void record(long tick, @Nullable String actor, int permLevel,
                       @Nullable String command, @NotNull Result result,
                       @NotNull Source source, @Nullable String detail) {
        if (command == null || command.isBlank()) {
            return;
        }
        String safe = redact(command);
        Entry entry = new Entry(System.currentTimeMillis(), tick, actor,
                Math.max(0, Math.min(4, permLevel)), safe, result, source, detail);
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
        }
    }

    /**
     * 脱敏：可能携带密钥的命令只保留根名与长度。
     *
     * <p>命中条件用 {@link PermissionGuard#rootOf(String)} 判定，
     * 与闸门用的是同一套解析逻辑，不会出现「闸门拦了但日志漏了」的缝隙。
     */
    @NotNull
    public static String redact(@NotNull String command) {
        String root = PermissionGuard.rootOf(command);
        if (root.equals("ai") || root.equals("aiagent")) {
            return "/" + root + " <内容已脱敏, " + command.trim().length() + " 字符>";
        }
        String text = command.trim();
        if (text.length() > 120) {
            return text.substring(0, 120) + "...";
        }
        return text;
    }

    /** 最近 {@code n} 条（最新在后）。 */
    @NotNull
    public List<Entry> recent(int n) {
        int count = Math.max(0, Math.min(n, CAPACITY));
        synchronized (entries) {
            List<Entry> all = new ArrayList<>(entries);
            if (all.size() <= count) {
                return List.copyOf(all);
            }
            return List.copyOf(all.subList(all.size() - count, all.size()));
        }
    }

    /** 清空（服务器关闭时调用，保证不跨世界残留）。 */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** 统计各结果的条数，供概览用。 */
    @NotNull
    public String summary() {
        int executed = 0;
        int blocked = 0;
        int failed = 0;
        synchronized (entries) {
            for (Entry e : entries) {
                switch (e.result()) {
                    case EXECUTED -> executed++;
                    case BLOCKED -> blocked++;
                    case FAILED -> failed++;
                    default -> { }
                }
            }
        }
        return String.format(Locale.ROOT, "共 %d 条（执行 %d / 拦截 %d / 失败 %d）",
                executed + blocked + failed, executed, blocked, failed);
    }

    /** 格式化为聊天栏文本（已带颜色码）。 */
    @NotNull
    public static String format(@NotNull Entry entry) {
        StringBuilder b = new StringBuilder();
        b.append("§8[").append(entry.tick()).append("刻] ");
        b.append(entry.result().color()).append(entry.result().label());
        b.append(" §7").append(entry.source().label());
        if (entry.actor() != null && !entry.actor().isBlank()) {
            b.append(" §8by §f").append(entry.actor());
        }
        b.append(" §8L").append(entry.permLevel());
        b.append("\n§7   ").append(entry.command());
        if (entry.detail() != null && !entry.detail().isBlank()) {
            b.append("\n§8   ↳ ").append(entry.detail());
        }
        return b.toString();
    }
}
