package com.kongbai.aiagent.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
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
            // 只读探测：仅放行只读子命令（见 READ_ONLY_SUBCOMMANDS）
            "list", "data", "scoreboard", "tag",
            // 通用执行器：可以包裹任意命令，必须递归校验内部命令（见 checkExecuteNested）
            "execute",
            // 受限的实体/物品操作（仍需通过降权 source 校验权限等级）
            "summon", "give", "clear", "effect", "tp", "teleport",
            "gamemode", "weather", "time", "gamerule", "setworldspawn",
            // Carpet：仅允许查询规则 + 本模组的机器控制（见 checkCarpet）
            "carpet"
    );

    /**
     * 「只读探测」类命令允许的子命令。
     *
     * <p><b>为什么需要</b>：{@code data} / {@code scoreboard} / {@code tag} 曾被注释为
     * 「只读探测」，但它们都带写子命令 —— {@code /data modify} 能改任意 NBT，
     * {@code /scoreboard objectives add}、{@code /tag <目标> add} 都是写操作。
     * 只按命令根放行等于把「只读」的承诺让给了 AI 自己遵守。
     *
     * <p>key = 命令根；value = 该命令第 2 段允许的只读子命令。
     * 列表中未出现的根（如 {@code list}）本身即只读，无需约束。
     */
    private static final Map<String, Set<String>> READ_ONLY_SUBCOMMANDS = Map.of(
            "data", Set.of("get"),
            "scoreboard", Set.of("objectives", "players")
    );

    /**
     * {@code scoreboard} 第 3 段允许的只读子命令。
     *
     * <p>{@code objectives add/remove/modify}、{@code players set/add/reset/operation}
     * 等都是写操作，因此这里只放行 {@code list} 与 {@code get}。
     */
    private static final Set<String> SCOREBOARD_READ_ONLY = Set.of("list", "get");

    /**
     * {@code /carpet ai} 下允许 AI 调用的子命令。
     *
     * <p><b>为什么必须显式收窄</b>：{@code AiPrompts} 教 AI 用
     * {@code /carpet ai machine on|off|offall|stopall|setstate} 控制机器，
     * 这是本模组的核心能力之一。但整条 {@code carpet ai} 树里还有
     * {@code api set <地址> <模型> <密钥>} —— 放行它等于让 AI 把 API 指向
     * 攻击者的服务器并接管后续所有对话（这正是「自指阻断」要防的事）。
     *
     * <p>因此只放行机器控制与只读查询，其余（api / rec / run / ask / bot /
     * sched / end / audit / perm）一律拒绝。
     */
    private static final Set<String> CARPET_AI_ALLOWED = Set.of("machine");

    /** {@code /carpet ai machine} 下允许的子命令。 */
    private static final Set<String> CARPET_AI_MACHINE_ALLOWED =
            Set.of("on", "off", "offall", "stopall", "setstate", "list");

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
        // 命令根放行还不够 —— 还要看这条命令具体想干什么
        return checkSemantics(root, trimmed);
    }

    /**
     * 命令根放行后的语义校验。
     *
     * <p><b>为什么不能只看命令根</b>：白名单是按「命令根」匹配的，
     * 但 Minecraft 里有若干「通用执行器」命令，它们能把任意命令当参数吃掉。
     * 最典型的就是 {@code /execute}：
     * <pre>
     *   op attacker              -> root=op        -> 黑名单拦截 ✅
     *   execute run op attacker  -> root=execute   -> 白名单放行 ❌
     * </pre>
     * 只要 {@code execute} 在白名单里，整张永久黑名单就形同虚设。
     * 因此必须递归校验被包裹的内部命令。
     *
     * <p>参考业界做法：fabric-command-hider 等权限模组是<b>遍历整棵 Brigadier
     * 命令树</b>逐节点校验，而不是只看根。本类没有命令树上下文（校验发生在
     * 派发之前），因此改用「按 {@code run} 分段递归」近似达到同样效果。
     */
    @Nullable
    private static String checkSemantics(@NotNull String root, @NotNull String command) {
        switch (root) {
            case "execute" -> {
                return checkExecuteNested(command);
            }
            case "carpet" -> {
                return checkCarpet(command);
            }
            default -> {
                Set<String> allowedSubs = READ_ONLY_SUBCOMMANDS.get(root);
                if (allowedSubs == null) {
                    return null; // 无额外约束
                }
                return checkReadOnlySubcommand(root, command, allowedSubs);
            }
        }
    }

    /**
     * 递归校验 {@code /execute} 包裹的内部命令。
     *
     * <p>对每个单独成词的 {@code run} 之后的内容，当作一条独立命令递归校验。
     * 嵌套（{@code execute run execute run op}）也会被逐层拆开。
     *
     * <p><b>宁可错杀</b>：把参数里恰好出现单词 {@code run} 的情况也当成子命令，
     * 可能误拦合法命令（例如 {@code execute if data entity @s {CustomName:"run"}}）。
     * 这里刻意选择「误拦」而非「漏拦」—— 安全方向优先。
     */
    @Nullable
    private static String checkExecuteNested(@NotNull String command) {
        String[] parts = command.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].equals("run")) {
                continue;
            }
            if (i + 1 >= parts.length) {
                continue; // 语法不完整的 execute，交给 Brigadier 去报错
            }
            String nested = String.join(" ", java.util.Arrays.copyOfRange(parts, i + 1, parts.length));
            String reason = check(nested);
            if (reason != null) {
                return "/execute 包裹的命令 /" + rootOf(nested) + " 不被允许：" + reason;
            }
        }
        return null;
    }

    /** 只读类命令：第 2 段必须落在只读子命令集合内。 */
    @Nullable
    private static String checkReadOnlySubcommand(@NotNull String root, @NotNull String command,
                                                  @NotNull Set<String> allowedSubs) {
        String[] parts = command.trim().split("\\s+");
        // 去掉可能的前导 "/" —— rootOf 会处理，这里 parts[0] 可能是 "/data"
        String sub = parts.length >= 2 ? parts[1] : "";
        if (!allowedSubs.contains(sub.toLowerCase(Locale.ROOT))) {
            return "/" + root + " 仅允许只读子命令 " + allowedSubs + "，收到: " + sub;
        }
        if (root.equals("scoreboard") && parts.length >= 3) {
            String third = parts[2].toLowerCase(Locale.ROOT);
            if (!SCOREBOARD_READ_ONLY.contains(third)) {
                return "/scoreboard " + sub + " 仅允许 " + SCOREBOARD_READ_ONLY + "，收到: " + third;
            }
        }
        return null;
    }

    /**
     * 校验 {@code /carpet ...}。
     *
     * <p>两条规则：
     * <ol>
     *   <li>普通 Carpet 规则 —— 只允许查询，不允许修改（沿用 {@link #isCarpetRuleMutation}）</li>
     *   <li>{@code /carpet ai ...} —— 只放行机器控制，其余一律拒绝</li>
     * </ol>
     */
    @Nullable
    private static String checkCarpet(@NotNull String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length >= 2 && parts[1].equalsIgnoreCase("ai")) {
            // /carpet ai ... —— 本模组自己的命令树，收窄到机器控制
            if (parts.length < 4) {
                return "/carpet ai 参数不完整，且 AI 仅可调用 machine 子命令";
            }
            String sub = parts[2].toLowerCase(Locale.ROOT);
            if (!CARPET_AI_ALLOWED.contains(sub)) {
                return "AI 不允许调用 /carpet ai " + sub
                        + "（仅允许 machine，以防 AI 自行修改 API 配置形成提权闭环）";
            }
            String action = parts[3].toLowerCase(Locale.ROOT);
            if (!CARPET_AI_MACHINE_ALLOWED.contains(action)) {
                return "AI 不允许调用 /carpet ai machine " + action
                        + "（允许: " + CARPET_AI_MACHINE_ALLOWED + "）";
            }
            return null;
        }
        if (isCarpetRuleMutation(command)) {
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
            // 26.1 起：withPermission(int) 改为 withPermission(PermissionSet)。
            // 「按等级构造权限集」的工厂是 LevelBasedPermissionSet.forLevel(PermissionLevel)，
            // PermissionLevel.byId 接受 0-4（ALL / MODERATORS / GAMEMASTERS / ADMINS / OWNERS）。
            PermissionSet downgraded =
                    LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level));
            return source.withPermission(downgraded);
        } catch (Throwable t) {
            // 权限 API 若再变动，绝不静默放行：退回最低权限集，
            // 宁可命令因权限不足失败，也不能以 OP 身份执行不可信输入。
            return source.withPermission(PermissionSet.NO_PERMISSIONS);
        }
    }

    /**
     * 取某命令源的权限等级（0-4）。
     *
     * <p>26.1 起 {@code getPermissionLevel()} 已移除，改为通过
     * {@code Commands.hasPermission(PermissionCheck)} 返回的判定器逐个试探。
     * 从高到低探测，命中即返回对应等级。
     *
     * <p>注意：不要缓存此值用于长期判断 —— 玩家 OP 状态可能在任务执行期间变化，
     * 每次执行前都应重新读取。
     */
    public static int levelOf(@NotNull CommandSourceStack source) {
        try {
            PermissionSet set = source.permissions();
            if (set == null) {
                return 0;
            }
            // 最快路径：基于等级的权限集可直接读出等级
            if (set instanceof LevelBasedPermissionSet levelBased) {
                return levelBased.level().id();
            }
            // 其它实现：用 PermissionCheck 直接对权限集判定
            // （注意不是 Commands.hasPermission(...).test(source) ——
            //  CommandSourceStack 并不实现 PermissionSetSupplier，那样写编译不过）
            if (Commands.LEVEL_OWNERS.check(set)) {
                return 4;
            }
            if (Commands.LEVEL_ADMINS.check(set)) {
                return 3;
            }
            if (Commands.LEVEL_GAMEMASTERS.check(set)) {
                return 2;
            }
            if (Commands.LEVEL_MODERATORS.check(set)) {
                return 1;
            }
            return 0;
        } catch (Throwable t) {
            // 权限 API 再变动时不要崩，按最低权限处理（最保守）
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
