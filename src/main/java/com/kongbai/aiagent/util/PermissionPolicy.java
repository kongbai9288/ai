package com.kongbai.aiagent.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 服主可配置的命令策略（进程内单例）。
 *
 * <p><b>核心原则：默认拒绝</b>。
 * {@link PermissionGuard} 只放行内置白名单里的命令根，
 * <b>其他模组注册的命令（home / back / rtp / money / warp ...）一律默认拒绝</b> ——
 * 因为本模组无法预判它们的破坏力，放行等于把其他模组的权限体系也暴露给 AI。
 *
 * <p>服主确认某条命令安全后，用 {@code /carpet ai policy allow <命令根>} 显式开启；
 * 也可以反过来用 {@code deny} 收紧内置白名单里已放行的命令（例如禁掉 {@code give}）。
 *
 * <p><b>为什么策略不能被 AI 修改（关键）</b>：
 * 本类是「闸门之上的闸门」。若 AI 能调用 {@code policy allow op}，
 * 那么 {@link PermissionGuard} 的永久黑名单就成了一张可以被 AI 自己撕掉的纸 ——
 * 这是最彻底的提权闭环。因此：
 * <ul>
 *   <li>{@code /carpet ai policy ...} 需要权限等级 3+（管理级）</li>
 *   <li>{@link PermissionGuard#checkCarpet} 只放行 {@code carpet ai machine}，
 *       AI 调用 {@code carpet ai policy} 会被拒</li>
 *   <li>永久黑名单（{@code FORBIDDEN_ROOTS}）<b>不可被 allow 覆盖</b>，
 *       即使服主显式要求也会被 {@link #allow} 拒绝，避免误操作撕开底线</li>
 * </ul>
 *
 * <p><b>生命周期</b>：与 {@code ProfileManager} 同一套约定 ——
 * {@link #attach(Path)} 加载、{@link #detach()} 落盘并清空，
 * 在世界加载/卸载时由 {@code AiAgentMod} 调用，防止跨世界串数据。
 */
public final class PermissionPolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");
    private static final String FILE_NAME = "aiagent_policy.json";

    /** 自定义 allow / deny 各自的最大条数，防止配置无限膨胀。 */
    public static final int MAX_ENTRIES = 200;

    /**
     * 合法命令根：小写字母、数字、下划线、连字符。
     *
     * <p>刻意不含空格与 {@code :} —— 命令根是单个 token，
     * 允许空格会让「一条命令根」变成「半条命令」，从而绕过按根匹配的校验。
     */
    private static final Pattern VALID_ROOT = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private static final PermissionPolicy INSTANCE = new PermissionPolicy();

    /** 服主额外放行的命令根。 */
    private final Set<String> extraAllowed = ConcurrentHashMap.newKeySet();
    /** 服主额外禁用的命令根（优先级高于内置白名单）。 */
    private final Set<String> extraDenied = ConcurrentHashMap.newKeySet();

    /**
     * 假人名前缀（持久化）。
     *
     * <p>服主可用 {@code /carpet ai botprefix <前缀>} 修改，
     * 用于适配「服务端/其他模组会给假人名强制叠加前缀」的环境
     *（例如实体最终叫 {@code bot_ai_01} 而非 {@code ai_01}）。
     */
    @NotNull
    private volatile String botPrefix = com.kongbai.aiagent.machine.FakePlayerNaming.PREFIX;

    /**
     * 幻翼防护（默认开）。
     *
     * <p><b>为什么需要</b>：幻翼的生成条件是「玩家 3 游戏日（72000 刻）未上床睡觉」。
     * <b>假人永远不会睡觉</b>，所以长期挂机必然招来幻翼，且它会持续不断地生成 ——
     * 幻翼生成时<b>无视敌对生物上限</b>，抗性也挡不住持续骚扰与被击退。
     *
     * <p>开启后：保活检查时把假人 {@code tp} 回精确坐标，抵消被幻翼击退的位移。
     */
    private volatile boolean phantomGuard = true;

    @Nullable
    private volatile Path saveDir;

    private PermissionPolicy() {
    }

    @NotNull
    public static PermissionPolicy getInstance() {
        return INSTANCE;
    }

    // ---------- 生命周期 ----------

    public void attach(@Nullable Path dir) {
        if (dir == null) {
            return;
        }
        detach();
        this.saveDir = dir;
        load();
    }

    public void detach() {
        save();
        extraAllowed.clear();
        extraDenied.clear();
        saveDir = null;
    }

    public boolean isAttached() {
        return saveDir != null;
    }

    // ---------- 查询 ----------

    /** 该命令根是否被服主额外放行。 */
    public boolean isAllowed(@Nullable String root) {
        return root != null && extraAllowed.contains(normalize(root));
    }

    /** 该命令根是否被服主额外禁用。 */
    public boolean isDenied(@Nullable String root) {
        return root != null && extraDenied.contains(normalize(root));
    }

    @NotNull
    public Collection<String> extraAllowed() {
        return List.copyOf(extraAllowed);
    }

    @NotNull
    public Collection<String> extraDenied() {
        return List.copyOf(extraDenied);
    }

    public int size() {
        return extraAllowed.size() + extraDenied.size();
    }

    /**
     * 是否开启幻翼防护。
     *
     * <p>注意：本开关<b>只做「位移回正」</b>，不能阻止幻翼生成。
     * 彻底关闭请用 {@code /carpet ai bot phantom off}（等价于
     * {@code /gamerule spawn_phantoms false}）。
     */
    public boolean phantomGuard() {
        return phantomGuard;
    }

    /**
     * 设置幻翼防护。
     *
     * @param enabled 是否开启
     * @param silent  为 true 时不写审计（用于从存档载入时）
     */
    public void setPhantomGuard(boolean enabled, boolean silent) {
        this.phantomGuard = enabled;
        if (!silent) {
            Auditor.getInstance().record(0L, null, 0,
                    "phantomGuard " + (enabled ? "on" : "off"),
                    Auditor.Result.EXECUTED, Auditor.Source.POLICY, null);
            save();
        }
    }

    @NotNull
    public String botPrefix() {
        return botPrefix;
    }

    /**
     * 修改假人名前缀。校验交给 {@code FakePlayerNaming#setPrefix}（限制字符集，防命令注入）。
     *
     * @return 成功返回 {@code null}；否则返回失败原因
     */
    @Nullable
    public String setBotPrefix(@Nullable String prefix) {
        String error = com.kongbai.aiagent.machine.FakePlayerNaming.setPrefix(prefix);
        if (error != null) {
            return error;
        }
        botPrefix = com.kongbai.aiagent.machine.FakePlayerNaming.prefix();
        Auditor.getInstance().record(0L, null, 0, "botprefix " + botPrefix,
                Auditor.Result.EXECUTED, Auditor.Source.POLICY, null);
        save();
        return null;
    }

    // ---------- 修改 ----------

    /**
     * 额外放行一条命令根。
     *
     * @return {@code null} 表示成功；否则返回给人看的失败原因
     */
    @Nullable
    public String allow(@Nullable String root) {
        String normalized = validate(root);
        if (normalized == null) {
            return "命令根不合法（只允许小写字母/数字/下划线/连字符，1-32 字符）";
        }
        // 永久黑名单不可被覆盖：这是底线，服主也不行
        if (PermissionGuard.isPermanentlyForbidden(normalized)) {
            return "命令 /" + normalized + " 在永久黑名单中，不允许通过策略开启";
        }
        if (extraDenied.contains(normalized)) {
            return "命令 /" + normalized + " 当前被显式禁用，请先 remove 再 allow";
        }
        if (extraAllowed.size() >= MAX_ENTRIES) {
            return "额外放行已达上限 " + MAX_ENTRIES;
        }
        if (!extraAllowed.add(normalized)) {
            return "命令 /" + normalized + " 已经在放行列表中";
        }
        Auditor.getInstance().record(0L, null, 0, "policy allow " + normalized,
                Auditor.Result.EXECUTED, Auditor.Source.POLICY, null);
        save();
        return null;
    }

    /**
     * 额外禁用一条命令根（可覆盖内置白名单，用于收紧）。
     *
     * @return {@code null} 表示成功；否则返回失败原因
     */
    @Nullable
    public String deny(@Nullable String root) {
        String normalized = validate(root);
        if (normalized == null) {
            return "命令根不合法（只允许小写字母/数字/下划线/连字符，1-32 字符）";
        }
        if (extraAllowed.contains(normalized)) {
            return "命令 /" + normalized + " 当前被显式放行，请先 remove 再 deny";
        }
        if (extraDenied.size() >= MAX_ENTRIES) {
            return "额外禁用已达上限 " + MAX_ENTRIES;
        }
        if (!extraDenied.add(normalized)) {
            return "命令 /" + normalized + " 已经在禁用列表中";
        }
        Auditor.getInstance().record(0L, null, 0, "policy deny " + normalized,
                Auditor.Result.EXECUTED, Auditor.Source.POLICY, null);
        save();
        return null;
    }

    /** 移除某条自定义规则（allow 或 deny 都算）。 */
    public boolean remove(@Nullable String root) {
        String normalized = normalize(root);
        boolean removed = extraAllowed.remove(normalized) | extraDenied.remove(normalized);
        if (removed) {
            Auditor.getInstance().record(0L, null, 0, "policy remove " + normalized,
                    Auditor.Result.EXECUTED, Auditor.Source.POLICY, null);
            save();
        }
        return removed;
    }

    /** 清空全部自定义规则，回到「仅内置白名单」的出厂状态。 */
    public int reset() {
        int count = size();
        extraAllowed.clear();
        extraDenied.clear();
        if (count > 0) {
            Auditor.getInstance().record(0L, null, 0, "policy reset (" + count + " 条)",
                    Auditor.Result.EXECUTED, Auditor.Source.POLICY, null);
            save();
        }
        return count;
    }

    @Nullable
    private static String normalize(@Nullable String root) {
        if (root == null) {
            return null;
        }
        String text = root.trim().toLowerCase(Locale.ROOT);
        // 容忍玩家带命名空间输入：minecraft:home -> home
        int colon = text.indexOf(':');
        if (colon >= 0) {
            text = text.substring(colon + 1);
        }
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        return text;
    }

    @Nullable
    private static String validate(@Nullable String root) {
        String normalized = normalize(root);
        if (normalized == null || !VALID_ROOT.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    // ---------- 持久化 ----------

    @NotNull
    private Path policyFile() {
        Path dir = saveDir;
        if (dir == null) {
            throw new IllegalStateException("PermissionPolicy 尚未 attach");
        }
        return dir.resolve("aiagent").resolve(FILE_NAME);
    }

    public boolean save() {
        if (saveDir == null) {
            return false;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("allow", toArray(extraAllowed));
            root.add("deny", toArray(extraDenied));
            root.addProperty("botPrefix", botPrefix);
            root.addProperty("phantomGuard", phantomGuard);
            return JsonUtil.writeTree(policyFile(), root);
        } catch (IllegalStateException e) {
            return false;
        } catch (RuntimeException e) {
            LOGGER.error("[ai-agent] 保存命令策略失败: {}", e.getMessage());
            return false;
        }
    }

    public void load() {
        if (saveDir == null) {
            return;
        }
        JsonElement root = JsonUtil.readTree(policyFile());
        if (root == null || !root.isJsonObject()) {
            return;
        }
        int allowed = readInto(root, "allow", extraAllowed);
        int denied = readInto(root, "deny", extraDenied);
        if (allowed + denied > 0) {
            LOGGER.info("[ai-agent] 已加载命令策略：额外放行 {} 条、额外禁用 {} 条", allowed, denied);
        }
        // 恢复幻翼防护（默认 true；存档里明确写了 false 才关）
        JsonElement guardElement = JsonUtil.path(root, "phantomGuard");
        if (guardElement != null && guardElement.isJsonPrimitive()) {
            try {
                phantomGuard = guardElement.getAsBoolean();
            } catch (RuntimeException ignored) {
                phantomGuard = true;
            }
        }

        // 恢复假人名前缀（并同步到 FakePlayerNaming）
        String saved = JsonUtil.stringOr(root, "", "botPrefix");
        if (!saved.isEmpty()) {
            String error = com.kongbai.aiagent.machine.FakePlayerNaming.setPrefix(saved);
            if (error == null) {
                botPrefix = com.kongbai.aiagent.machine.FakePlayerNaming.prefix();
                LOGGER.info("[ai-agent] 已恢复假人名前缀: {}", botPrefix);
            } else {
                LOGGER.warn("[ai-agent] 存档中的假人名前缀非法，已忽略: {}", saved);
            }
        }
    }

    private static int readInto(@NotNull JsonElement root, @NotNull String key,
                                @NotNull Set<String> target) {
        JsonElement element = JsonUtil.path(root, key);
        if (element == null || !element.isJsonArray()) {
            return 0;
        }
        int count = 0;
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || !item.isJsonPrimitive()) {
                continue;
            }
            String value = validate(item.getAsString());
            // 载入时同样过滤永久黑名单，避免手改存档把底线撕开
            if (value != null && !PermissionGuard.isPermanentlyForbidden(value)) {
                if (target.add(value)) {
                    count++;
                }
            }
        }
        return count;
    }

    @NotNull
    private static JsonArray toArray(@NotNull Collection<String> values) {
        JsonArray array = new JsonArray();
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        for (String value : sorted) {
            array.add(value);
        }
        return array;
    }
}
