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
 * 是否需要召唤由调用方直接 {@code /player <name> spawn} ——
 * Carpet 对已存在的假人会自己复用，无需先查列表。
 */
public final class FakePlayerNaming {

    /**
     * 假人专属前缀。
     *
     * <p>Carpet 假人名允许的字符有限（不能含空格、特殊符号），
     * 下划线安全。
     *
     * <p><b>注意：Carpet 的 {@code /player} 不支持名字通配符</b> ——
     * 旧注释提到的 {@code /player ai_* kill} 实际不存在，别照着写。
     * 前缀的作用是「一眼看出这是本模组的假人」，批量清理需自行遍历名单。
     */
    /** 默认前缀。服主可通过 {@code /carpet ai botprefix} 修改。 */
    public static final String PREFIX = "ai_";

    /**
     * 当前生效的前缀（可配置，默认 {@link #PREFIX}）。
     *
     * <p><b>为什么前缀要可配置</b>：有些服务端 / 其他模组会给假人名<b>强制叠加自己的前缀</b>。
     * 例如本模组生成 {@code ai_01}，服务端统一加 {@code bot_}，最终实体名叫 {@code bot_ai_01} ——
     * 此时若还按「必须以 {@code ai_} 开头」判定，归属检查会全部失败，功能直接瘫痪。
     *
     * <p>服主可把前缀改成实际生效的完整形式（如 {@code bot_ai_}）。
     */
    private static volatile String activePrefix = PREFIX;

    /** 假人名最大长度。Minecraft 玩家名上限 16，减去前缀留足余量。 */
    public static final int MAX_LENGTH = 16;

    /** 合法假人名：字母、数字、下划线，且以字母开头。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private FakePlayerNaming() {
    }

    /** 当前生效的前缀。 */
    @NotNull
    public static String prefix() {
        return activePrefix;
    }

    /**
     * 修改前缀。
     *
     * <p><b>只允许包含安全字符且不能为空</b> —— 前缀会被拼进命令，
     * 含空格或分号会破坏命令结构（甚至造成命令注入）。
     *
     * @return 成功返回 {@code null}；否则返回失败原因
     */
    @Nullable
    public static String setPrefix(@Nullable String prefix) {
        if (prefix == null) {
            return "前缀不能为空";
        }
        String trimmed = prefix.trim();
        // 前缀会被直接拼进 /player <名> ... 命令，必须限制字符集
        if (trimmed.isEmpty() || trimmed.length() > 12
                || !trimmed.matches("^[A-Za-z0-9_-]+$")) {
            return "前缀不合法（仅允许字母/数字/下划线/连字符，1-12 字符）";
        }
        activePrefix = trimmed;
        return null;
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
        String prefix = activePrefix;
        if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            trimmed = trimmed.substring(prefix.length());
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
        int maxBody = MAX_LENGTH - prefix.length();
        if (body.length() > maxBody) {
            body = body.substring(0, maxBody);
        }
        return prefix + body;
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
            return prefix() + "default";
        }
        String normalized = normalize(purpose);
        return normalized == null ? prefix() + "default" : normalized;
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
        String lower = name.toLowerCase(Locale.ROOT);
        String prefix = activePrefix.toLowerCase(Locale.ROOT);
        // 先按「以我们的前缀开头」判断（正常情况）
        if (lower.startsWith(prefix)) {
            return true;
        }
        // 兜底：服务端/其他模组可能在我们的名字前又叠了一层前缀，
        // 例如 ai_01 -> bot_ai_01。此时 startsWith 会失败但名字仍是我们的。
        // 用 contains 兜底，避免这种情况下功能整体瘫痪。
        // （代价：形如 notai_bot 的名字会被误判为本模组的，实际中极罕见）
        return lower.contains(prefix);
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
        String prefix = activePrefix;
        if (name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return name.substring(prefix.length());
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
     *
     * <p><b>注意 spawn 的位置</b>：不带 {@code at} 时假人生成在<b>执行者所在位置</b>。
     * 本模组的命令投递器用的是服务器级 source（没有实体），
     * 生成点会是世界出生点而非玩家身边。需要定位时用
     * {@link #spawnAtCommand(String, double, double, double)}。
     */
    @NotNull
    public static String spawnCommand(@NotNull String fakeName) {
        return "player " + fakeName + " spawn";
    }

    /**
     * 生成「在指定坐标召唤假人」的命令。
     *
     * <p>Carpet 语法（已核对）：
     * {@code /player <name> spawn at <X> <Y> <Z> [facing <yaw> <pitch>] [in <dim>] [in <gamemode>]}
     *
     * <p>回放开始时用它把假人直接放到第一个移动点上，
     * 避免「先在世界出生点冒出来、再被传送」的闪烁。
     */
    @NotNull
    public static String spawnAtCommand(@NotNull String fakeName, double x, double y, double z) {
        return String.format(java.util.Locale.ROOT,
                "player %s spawn at %.3f %.3f %.3f", fakeName, x, y, z);
    }

    /**
     * 生成「在指定坐标 + 指定维度召唤假人」的命令。
     *
     * <p>Carpet 语法（已核对）：
     * {@code /player <name> spawn at <X> <Y> <Z> facing <yaw> <pitch> in <dimension> in <gamemode>}
     *
     * <p><b>为什么必须带坐标</b>：不带 {@code at} 时假人生成在<b>执行者</b>位置。
     * 本模组的投递器是服务器级 source（没有实体），落点会变成<b>世界出生点</b> ——
     * 一旦出生点被改造过（基岩被挖穿、填了岩浆、封在方块里），
     * 假人会直接掉进虚空/被烧死/卡死，后续命令全部落空。
     * 显式指定录制时的坐标可完全避开这个问题。
     */
    @NotNull
    public static String spawnAtCommand(@NotNull String fakeName,
                                        double x, double y, double z,
                                        @Nullable String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return spawnAtCommand(fakeName, x, y, z);
        }
        return String.format(java.util.Locale.ROOT,
                "player %s spawn at %.3f %.3f %.3f in %s", fakeName, x, y, z, dimension.trim());
    }

    /**
     * 生成「给假人加保护」的命令序列（抗怪物干扰）。
     *
     * <p><b>为什么需要</b>：假人是真实的玩家实体，会被怪物攻击、被推动、
     * 会摔伤、会饿。回放一个几十秒的任务期间被僵尸推歪几格，
     * 后续所有「对着某个方块 use」就全部打偏了。
     *
     * <p>这里给高等级抗性提升 + 饱和（回血防饿死），
     * 既不改变世界，又能让假人在回放期间稳定存活。
     * 抗性 255 级基本等于无敌，饱和解决饥饿掉血。
     *
     * <p><b>刻意不设为创造模式</b>：部分机械（如刷石机、农作物交互）
     * 在创造模式下的行为与生存不同，会改变录制时的效果。
     */
    @NotNull
    public static java.util.List<String> protectionCommands(@NotNull String fakeName) {
        return java.util.List.of(
                "effect give " + fakeName + " minecraft:resistance 1728000 255 true",
                "effect give " + fakeName + " minecraft:saturation 1728000 255 true",
                "effect give " + fakeName + " minecraft:fire_resistance 1728000 255 true"
        );
    }

    /**
     * 生成「传送已存在假人」的命令。
     *
     * <p><b>为什么不是 {@code /player X tp}</b>：
     * Carpet 的 {@code /player} <b>没有 {@code tp} 子命令</b>（全部子命令为
     * spawn / kill / shadow / move / look / turn / use / attack / jump / drop /
     * dropStack / swapHands / hotbar / mount / dismount / sneak / sprint / stop）。
     * 传送假人要用原版 {@code /tp} —— 假人是真实注册的玩家实体，原版 tp 对其有效。
     */
    @NotNull
    public static String teleportCommand(@NotNull String fakeName, double x, double y, double z) {
        return String.format(java.util.Locale.ROOT, "tp %s %.3f %.3f %.3f", fakeName, x, y, z);
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
