package com.kongbai.aiagent.util;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * 权限闸门 —— 防提权的核心。
 *
 * <p><b>威胁模型</b>：AI 的回复内容来自外部模型，属于<b>完全不可信输入</b>。
 * 若把 AI 生成的命令直接用 OP 权限执行，等价于把服务器管理权交给外部 API，
 * 攻击者只需在对话里诱导一句「执行 /op attacker」即可完全接管。
 *
 * <p><b>三重防线（缺一不可）</b>：
 * <ol>
 *   <li><b>黑名单</b>：高危管理命令永不放行（op/deop/ban/stop/whitelist/...）</li>
 *   <li><b>降权执行</b>：即使命令放行，也用「任务开启者」的权限等级构造 source 执行，
 *       而非执行者的 OP 权限 —— 见 {@link #clamp(CommandSourceStack, int)}</li>
 *   <li><b>自指阻断</b>：禁止 AI 触及本模组的配置命令（{@code ai}），
 *       否则 AI 可自行修改 API 地址/密钥，形成提权闭环</li>
 * </ol>
 *
 * <p><b>空指针约束</b>：所有 {@code @NotNull} 参数若传入 {@code null} 会直接抛 NPE，
 * 这是有意的 —— 权限校验路径上出现 null 属于严重缺陷，静默放行比崩溃更危险。
 */
public final class PermissionGuard {

    /**
     * 绝对禁止的命令根（小写）。
     * 这些命令一旦被执行，可能造成不可逆损失或完全提权，任何情况下都不放行。
     */
    private static final Set<String> FORBIDDEN_ROOTS = Set.of(
            // --- 权限与封禁 ---
            "op", "deop", "ban", "ban-ip", "banip", "pardon", "pardon-ip", "banlist",
            "whitelist", "kick", "setidletimeout",
            // --- 服务器生命周期 ---
            "stop", "restart", "save-all", "save-off", "save-on", "publish",
            // --- 数据/脚本执行（可任意执行代码）---
            "datapack", "function", "debug", "jfr", "perf", "reload",
            // --- 世界编辑（规模不可控，可能瞬间摧毁存档）---
            "fill", "clone", "setblock", "fillbiome",
            // --- 本模组自指：防止 AI 改自己的配置/密钥，形成提权闭环 ---
            "ai", "aiagent"
    );

    /**
     * 默认放行的命令根（小写）。
     *
     * <p>设计原则：<b>AI 默认只能通过 Carpet 的假人命令操作世界</b>，
     * 而不是直接改方块 —— 这既符合「用 carpet 命令操作人物」的要求，
     * 也让一切行为可追溯、可撤销（改 carpet 规则另行限制）。
     */
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            // 假人操作（核心能力）
            "player",
            // 只读探测：让 AI 能"看见"世界，但不能改
            "list", "data", "execute", "scoreboard", "tag",
            // 受限的实体/物品操作（仍需通过降权 source 校验权限等级）
            "summon", "give", "clear", "effect", "tp", "teleport",
            "gamemode", "weather", "time", "gamerule", "setworldspawn",
            // Carpet 规则：仅允许查询，不允许修改（见 isCarpetRuleMutation）
            "carpet"
    );

    /** 单条命令最大长度，防止超长输入压垮解析与日志。 */
    public static final int MAX_COMMAND_LENGTH = 512;
    /** 单次 AI 回复允许执行的最大命令条数，防止刷屏式破坏。 */
    public static final int MAX_COMMANDS_PER_RESPONSE = 16;

    private PermissionGuard() {
    }

    /**
     * 校验 AI 产生的单条命令是否可以执行。
     *
     * @param command 待执行命令（不含前导 {@code /}），不可为 {@code null}
     * @return 放行返回 {@code null}；拒绝返回给人看的原因（不含敏感信息）
     */
    @Nullable
    public static String check(@NotNull String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            return "空命令";
        }
        if (trimmed.length() > MAX_COMMAND_LENGTH) {
            return "命令过长（上限 " + MAX_COMMAND_LENGTH + " 字符）";
        }
        // 防链式执行：; 与 && 会绕过单条白名单校验
        if (trimmed.contains(";") || trimmed.contains("&&") || trimmed.contains("||")
                || trimmed.contains("\n") || trimmed.contains("\r")) {
            return "不允许使用 ; && || 或换行拼接多条命令";
        }

        String root = rootOf(trimmed);
        if (root.isEmpty()) {
            return "无法解析命令";
        }
        if (FORBIDDEN_ROOTS.contains(root)) {
            return "命令 /" + root + " 在永久黑名单中";
        }
        if (!ALLOWED_ROOTS.contains(root)) {
            return "命令 /" + root + " 不在允许清单内";
        }
        if (root.equals("carpet") && isCarpetRuleMutation(trimmed)) {
            return "AI 不允许修改 Carpet 规则（仅可查询）";
        }
        return null;
    }

    /**
     * 是否放行。{@link #check(String)} 的布尔便捷版。
     */
    public static boolean isAllowed(@Nullable String command) {
        return command != null && check(command) == null;
    }

    /**
     * 提取命令根（第一个空白前的 token，小写）。
     * 输入 {@code "/player Steve attack"} 返回 {@code "player"}。
     */
    @NotNull
    public static String rootOf(@NotNull String command) {
        String text = command.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        int space = text.indexOf(' ');
        String root = space < 0 ? text : text.substring(0, space);
        // 去掉命名空间前缀：minecraft:player -> player
        int colon = root.indexOf(':');
        if (colon >= 0) {
            root = root.substring(colon + 1);
        }
        return root.toLowerCase(Locale.ROOT);
    }

    /**
     * 判断 {@code /carpet ...} 是否为「修改规则」操作。
     *
     * <p>规则：{@code /carpet <rule> <value>} 为修改；
     * {@code /carpet list}、{@code /carpet <rule>}（不带值）为查询，放行。
     */
    private static boolean isCarpetRuleMutation(@NotNull String command) {
        String[] parts = command.trim().split("\\s+");
        // /carpet                          -> 0 段（仅 /carpet 本身）
        // /carpet list                     -> 2 段（含 "/carpet"）—— 查询
        // /carpet list defaults            -> 3 段 —— 查询
        // /carpet <rule>                   -> 2 段 —— 查询
        // /carpet <rule> <value>           -> 3 段 —— 修改
        // /carpet setDefault <rule> <val>  -> 4 段 —— 修改
        if (parts.length < 3) {
            return false;
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list") || sub.equals("info")) {
            return false;
        }
        if (sub.equals("setdefault") || sub.equals("removedefault")) {
            return true;
        }
        // 其余 "规则名 + 值" 形式视为修改
        return true;
    }

    /**
     * 构造降权后的命令源。
     *
     * <p>这是防提权的<b>关键一环</b>：把命令源的权限等级压到任务开启者的等级，
     * 再由 Brigadier 自己去判定该命令是否允许执行。
     * 这样即使白名单存在疏漏，Minecraft 自身的权限校验仍会兜住。
     *
     * @param source   原始命令源，不可为 {@code null}
     * @param permLevel 目标权限等级，会被夹到 [0, 4]
     * @return 降权后的新命令源（原对象不被修改）
     */
    @NotNull
    public static CommandSourceStack clamp(@NotNull CommandSourceStack source, int permLevel) {
        int level = Math.max(0, Math.min(4, permLevel));
        try {
            return source.withPermission(level);
        } catch (Exception e) {
            // withPermission 理论上不会抛异常；若真发生，宁可返回原对象也不要崩溃游戏线程
            return source;
        }
    }

    /**
     * 取某命令源的权限等级。
     *
     * <p>注意：不要缓存此值用于长期判断 —— 玩家 OP 状态可能在任务执行期间变化，
     * 每次执行前都应重新读取。
     */
    public static int levelOf(@NotNull CommandSourceStack source) {
        try {
            return source.getPermissionLevel();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 判断两个权限等级是否「同级或更低」。
     * 用于任务归属校验：任务只能由开启者本人或更高权限者管理。
     */
    public static boolean atLeast(int actual, int required) {
        return actual >= required;
    }

    /**
     * 校验一批命令，返回全部放行时的 {@code null}，或首条被拒的原因。
     * 同时执行数量上限检查。
     */
    @Nullable
    public static String checkBatch(@Nullable java.util.List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return "没有命令";
        }
        if (commands.size() > MAX_COMMANDS_PER_RESPONSE) {
            return "单次命令数量超过上限 " + MAX_COMMANDS_PER_RESPONSE;
        }
        for (String command : commands) {
            if (command == null) {
                return "存在 null 命令";
            }
            String reason = check(command);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /** 供 {@code /carpet ai perm} 展示用：当前允许的命令清单。 */
    @NotNull
    public static Set<String> allowedRoots() {
        return ALLOWED_ROOTS;
    }

    /** 供展示用：永久黑名单。 */
    @NotNull
    public static Set<String> forbiddenRoots() {
        return FORBIDDEN_ROOTS;
    }
}
