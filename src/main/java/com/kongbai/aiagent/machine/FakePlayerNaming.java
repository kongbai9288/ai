package com.kongbai.aiagent.machine;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 假人命名与复用管理。
 *
 * <p><b>为什么要有专属前缀</b>：Carpet 的假人是服务器级共享资源。
 * 如果本模组直接用玩家给的名字（比如 {@code bot1}），
 * 很可能撞上服务器里别人已经在用的假人 ——
 * 轻则把人家的假人传送走，重则替他执行了破坏性操作。
 * 统一加前缀后，本模组操作的假人身份一目了然，且不会误碰他人假人。
 *
 * <p><b>为什么尽量复用已召唤的假人</b>：每个 Carpet 假人都会在存档的
 * {@code players/} 目录生成一份玩家数据（背包、位置、状态）。
 * 每次任务都新建假人，会让存档随任务次数无限膨胀。
 * 复用同名假人则始终只有固定几份数据。
 *
 * <p><b>本类无状态</b>：不缓存任何假人信息（那会与真实世界不同步）。
 * 是否需要召唤由调用方通过 {@link #spawnCommandIfNeeded} 生成命令后
 * 由 Carpet 自己判定，或者由调用方先 {@code /player list} 查询。
 */
public final class FakePlayerNaming {

    /**
     * 假人专属前缀。
     *
     * <p>Carpet 假人名允许的字符有限（不能含空格、特殊符号），
     * 下划线安全。前缀也便于用 {@code /player ai_* kill} 批量清理。
     */
    public static final String PREFIX = "ai_";

    /** 假人名最大长度。Minecraft 玩家名上限 16，减去前缀留足余量。 */
    public static final int MAX_LENGTH = 16;

    /** 合法假人名：字母、数字、下划线，且以字母开头。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private FakePlayerNaming() {
    }

    /**
     * 规范化假人名：加上前缀、清洗非法字符、截断长度。
     *
     * <p><b>加前缀是幂等的</b>：已经是 {@code ai_} 开头的不会重复加。
     * 这样玩家写 {@code ai_bot1} 或 {@code bot1} 得到同一个名字
     * —— 这正是「复用已召唤假人」的前提。
     *
     * @param raw 玩家输入的名字；为 {@code null}/空时返回 {@code null}
     *            （表示不驱动假人，驱动执行者自己）
     * @return 带前缀的合法假人名；输入为空时返回 {@code null}
     */
    @Nullable
    public static String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // 已经是带前缀的（大小写不敏感），不重复加
        if (trimmed.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            trimmed = trimmed.substring(PREFIX.length());
        }
        // 清洗：只保留字母数字下划线
        StringBuilder cleaned = new StringBuilder();
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                cleaned.append(c);
            } else {
                cleaned.append('_');
            }
        }
        String body = cleaned.toString();
        if (body.isEmpty()) {
            return null;
        }
        // 首字母必须是字母
        if (!Character.isLetter(body.charAt(0))) {
            body = "b" + body;
        }
        // 截断：总长不超过 MAX_LENGTH
        int maxBody = MAX_LENGTH - PREFIX.length();
        if (body.length() > maxBody) {
            body = body.substring(0, maxBody);
        }
        return PREFIX + body;
    }

    /**
     * 按用途生成一个稳定的假人名。
     *
     * <p><b>稳定</b>是关键：同一个用途（比如同一台机器）每次都得到同一个名字，
     * 才能命中「已召唤过」的假人从而实现复用。
     * 若用随机数或时间戳，每次都是新名字，复用就失效了。
     *
     * @param purpose 用途标识（如机器名）；为空时返回默认名
     */
    @NotNull
    public static String forPurpose(@Nullable String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return PREFIX + "default";
        }
        String normalized = normalize(purpose);
        return normalized == null ? PREFIX + "default" : normalized;
    }

    /**
     * 是否为本模组管理的假人（带专属前缀）。
     *
     * <p>用于在展示和批量操作时区分「我们的假人」与「服务器里别人的假人」。
     */
    public static boolean isOurs(@Nullable String name) {
        if (name == null) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /**
     * 去掉前缀的原始名（展示用）。
     *
     * @return 无前缀时原样返回
     */
    @NotNull
    public static String strip(@Nullable String name) {
        if (name == null) {
            return "";
        }
        if (name.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return name.substring(PREFIX.length());
        }
        return name;
    }

    /** 名字是否合法（用于拒绝玩家输入的怪名字）。 */
    public static boolean isValid(@Nullable String name) {
        if (name == null) {
            return false;
        }
        return VALID_NAME.matcher(name).matches() && name.length() <= MAX_LENGTH;
    }

    /**
     * 查询某假人是否存在的命令。
     *
     * <p>本模组不自己维护假人列表（会与真实状态不同步），
     * 而是每次用 Carpet 的 {@code /player <name> ...} 命令试探。
     * 这里返回查询命令，由调用方执行后看结果。
     */
    @NotNull
    public static String listCommand() {
        return "player list";
    }

    /**
     * 生成「召唤假人」的命令。
     *
     * <p><b>复用策略</b>：Carpet 的 {@code /player X spawn} 在 X 已存在时
     * 会直接复用该假人（不会重置其数据、不新建存档条目），
     * 因此无条件 spawn 也能实现复用 —— 这正是「减少存储压力」的关键。
     *
     * <p>只有真正不存在时才会新建。
     */
    @NotNull
    public static String spawnCommand(@NotNull String fakeName) {
        return "player " + fakeName + " spawn";
    }

    /** 生成「停止假人当前动作」的命令（用于任务结束清理）。 */
    @NotNull
    public static String stopCommand(@NotNull String fakeName) {
        return "player " + fakeName + " stop";
    }

    /** 生成「移除假人」的命令。慎用：会清掉该假人的背包数据。 */
    @NotNull
    public static String killCommand(@NotNull String fakeName) {
        return "player " + fakeName + " kill";
    }
}
