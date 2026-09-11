package com.kongbai.aiagent.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个录制好的任务（不可变值对象）。
 *
 * <p><b>为什么不可变</b>：任务会被回放线程读取并跨 tick 执行，
 * 若中途被修改（例如玩家重录同名任务），回放中的索引会错位。
 * 不可变 + 整体替换（registry 里换引用）可以避免这类问题。
 *
 * <p><b>权限绑定</b>：任务记录创建者的 {@code UUID} 与<b>权限等级</b>。
 * 回放时用创建者的权限等级执行（见 {@code PermissionGuard.clamp}），
 * 这样即使创建者后来被降级/提权，也不会影响已录制任务的行为边界 ——
 * 用录制时的快照更可预测，也避免"录时普通玩家、放时变成 OP"的提权漏洞。
 *
 * <p><b>内存安全</b>：只存 {@code UUID}、{@code String}、{@code int}、{@code List<RecordedAction>}，
 * 不持有任何游戏对象。
 */
public final class RecordedTask {

    /** 单个任务的动作数上限。防止玩家录一小时把内存和存档撑爆。 */
    public static final int MAX_ACTIONS = 10_000;
    /** 任务名最大长度。 */
    public static final int MAX_NAME_LENGTH = 32;
    /** 任务名允许的字符：中文、字母、数字、下划线、连字符。 */
    private static final String NAME_PATTERN = "^[\\w\\u4e00-\\u9fa5-]{1," + MAX_NAME_LENGTH + "}$";

    private final String name;
    private final UUID ownerUuid;
    private final String ownerName;
    private final int permLevel;
    private final long createdAt;
    private final List<RecordedAction> actions;
    /** 是否允许长期执行（M4 调度器用）。M2 阶段恒为 false，字段先预留。 */
    private final boolean longRunning;

    private RecordedTask(String name, UUID ownerUuid, String ownerName, int permLevel,
                         long createdAt, List<RecordedAction> actions, boolean longRunning) {
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.permLevel = permLevel;
        this.createdAt = createdAt;
        this.actions = actions;
        this.longRunning = longRunning;
    }

    /**
     * 构造任务。
     *
     * @param name      任务名，需匹配 {@link #NAME_PATTERN}
     * @param ownerUuid 创建者 UUID，不可为 {@code null}
     * @param ownerName 创建者名字（可为空串）
     * @param permLevel 创建者权限等级，会被夹到 [0, 4]
     * @param actions   动作列表；为 {@code null} 视为空列表，超过 {@link #MAX_ACTIONS} 会被截断
     * @throws IllegalArgumentException 名称非法时抛出
     */
    @NotNull
    public static RecordedTask of(@NotNull String name, @NotNull UUID ownerUuid, @Nullable String ownerName,
                                  int permLevel, @Nullable List<RecordedAction> actions) {
        return of(name, ownerUuid, ownerName, permLevel, actions, false);
    }

    @NotNull
    public static RecordedTask of(@NotNull String name, @NotNull UUID ownerUuid, @Nullable String ownerName,
                                  int permLevel, @Nullable List<RecordedAction> actions, boolean longRunning) {
        String trimmed = name == null ? "" : name.trim();
        if (!trimmed.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "任务名只能包含中文/字母/数字/下划线/连字符，且不超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        List<RecordedAction> safe = new ArrayList<>();
        if (actions != null) {
            for (RecordedAction action : actions) {
                if (action == null) {
                    continue; // 跳过 null 条目，不让脏数据混进来
                }
                if (safe.size() >= MAX_ACTIONS) {
                    break; // 超限截断，宁可丢尾部也不撑爆内存
                }
                safe.add(action);
            }
        }
        return new RecordedTask(trimmed,
                Objects.requireNonNull(ownerUuid, "ownerUuid"),
                ownerName == null ? "" : ownerName,
                Math.max(0, Math.min(4, permLevel)),
                System.currentTimeMillis(),
                List.copyOf(safe),
                longRunning);
    }

    /** 任务名是否合法。命令层用它做前置校验，避免抛异常路径。 */
    public static boolean isValidName(@Nullable String name) {
        return name != null && name.trim().matches(NAME_PATTERN);
    }

    // ---------- 访问器 ----------

    @NotNull
    public String name() {
        return name;
    }

    /** 存储用的小写 key，保证同名任务不因大小写重复。 */
    @NotNull
    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }

    @NotNull
    public UUID ownerUuid() {
        return ownerUuid;
    }

    @NotNull
    public String ownerName() {
        return ownerName;
    }

    /** 创建时的权限等级快照，已夹在 [0, 4]。 */
    public int permLevel() {
        return permLevel;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean isLongRunning() {
        return longRunning;
    }

    /** 不可修改的动作列表。 */
    @NotNull
    public List<RecordedAction> actions() {
        return actions;
    }

    public int size() {
        return actions.size();
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    /** 任务总时长（游戏刻）。空任务返回 0。 */
    public long durationTicks() {
        if (actions.isEmpty()) {
            return 0;
        }
        return actions.get(actions.size() - 1).tickOffset();
    }

    /**
     * 生成副本，追加一批动作。
     * 用于 M3 的"继续录制"。当前模块暂未使用，但先提供以免后续改不可变结构。
     */
    @NotNull
    public RecordedTask withAppendedActions(@Nullable List<RecordedAction> extra) {
        List<RecordedAction> merged = new ArrayList<>(actions);
        if (extra != null) {
            merged.addAll(extra);
        }
        return RecordedTask.of(name, ownerUuid, ownerName, permLevel, merged, longRunning);
    }

    // ---------- 序列化 ----------

    @NotNull
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("ownerUuid", ownerUuid.toString());
        obj.addProperty("ownerName", ownerName);
        obj.addProperty("permLevel", permLevel);
        obj.addProperty("createdAt", createdAt);
        obj.addProperty("longRunning", longRunning);
        JsonArray array = new JsonArray();
        for (RecordedAction action : actions) {
            array.add(action.toJson());
        }
        obj.add("actions", array);
        return obj;
    }

    /**
     * 从 JSON 还原。
     *
     * @return 成功返回任务；名称/UUID 非法时返回 {@code null}（动作损坏只跳过该条，不影响整体）
     */
    @Nullable
    public static RecordedTask fromJson(@Nullable JsonObject obj) {
        if (obj == null) {
            return null;
        }
        String name = JsonUtil.stringOr(obj, "", "name");
        if (!isValidName(name)) {
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

        int permLevel = 0;
        var permElement = JsonUtil.path(obj, "permLevel");
        if (permElement != null && permElement.isJsonPrimitive()) {
            try {
                permLevel = permElement.getAsInt();
            } catch (NumberFormatException | UnsupportedOperationException e) {
                permLevel = 0;
            }
        }
        long createdAt = 0;
        var timeElement = JsonUtil.path(obj, "createdAt");
        if (timeElement != null && timeElement.isJsonPrimitive()) {
            try {
                createdAt = timeElement.getAsLong();
            } catch (NumberFormatException | UnsupportedOperationException e) {
                createdAt = System.currentTimeMillis();
            }
        }
        boolean longRunning = JsonUtil.path(obj, "longRunning") != null
                && JsonUtil.path(obj, "longRunning").isJsonPrimitive()
                && JsonUtil.path(obj, "longRunning").getAsBoolean();

        List<RecordedAction> actions = new ArrayList<>();
        var arrayElement = JsonUtil.path(obj, "actions");
        if (arrayElement != null && arrayElement.isJsonArray()) {
            int skipped = 0;
            for (var element : arrayElement.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    skipped++;
                    continue;
                }
                RecordedAction action = RecordedAction.fromJson(element.getAsJsonObject());
                if (action == null) {
                    skipped++;
                } else {
                    actions.add(action);
                }
            }
            if (skipped > 0) {
                // 记录但不中断：少量损坏动作不应让整个任务不可用
                org.slf4j.LoggerFactory.getLogger("ai-agent")
                        .warn("[ai-agent] 任务 {} 加载时跳过 {} 条损坏动作", name, skipped);
            }
        }

        try {
            return RecordedTask.of(name, uuid,
                    JsonUtil.stringOr(obj, "", "ownerName"),
                    permLevel, actions, longRunning);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecordedTask other)) {
            return false;
        }
        return permLevel == other.permLevel
                && createdAt == other.createdAt
                && longRunning == other.longRunning
                && name.equals(other.name)
                && ownerUuid.equals(other.ownerUuid)
                && actions.equals(other.actions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, ownerUuid, permLevel, createdAt, longRunning, actions);
    }

    @Override
    public String toString() {
        return "RecordedTask{name='" + name + "', owner=" + ownerUuid
                + ", actions=" + actions.size() + ", perm=" + permLevel + "}";
    }

    /** 供命令层展示的不可修改视图工具（避免外部拿到本类的可变引用）。 */
    @NotNull
    public static List<RecordedAction> emptyActions() {
        return Collections.emptyList();
    }
}
