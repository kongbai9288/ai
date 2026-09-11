package com.kongbai.aiagent.machine;

import com.google.gson.JsonObject;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 一台「机器」的定义（不可变值对象）。
 *
 * <p><b>什么是机器</b>：本模组不区分具体机型，只把它抽象成
 * 「一个名字 + 一个开启动作序列 + 一个关闭动作序列」。
 * 开启/关闭动作都是<b>已录制的任务名</b>——
 * 玩家先录一遍「怎么开」，再录一遍「怎么关」，就定义出一台机器。
 *
 * <p>这样设计的好处：不需要理解红石或具体方块，
 * 任何能用键盘操作完成的开关流程都能被录成机器。
 *
 * <p><b>为什么绑定 UUID 与权限</b>：机器由某个玩家定义，
 * 执行其开关任务时用<b>定义者的权限等级</b>降权执行，
 * 防止低权限玩家定义机器、高权限玩家执行导致提权。
 *
 * <p><b>内存安全</b>：只存 {@code UUID} 与 {@code String}，不持有游戏对象。
 */
public final class Machine {

    public static final int MAX_NAME_LENGTH = 32;

    private final String name;
    @Nullable
    private final String onTask;
    @Nullable
    private final String offTask;
    private final UUID ownerUuid;
    private final String ownerName;
    private final int permLevel;
    private final long createdAt;
    @NotNull
    private final MachineState state;
    /** 状态最后一次被明确设置的时间戳（毫秒）。用于判断状态是否可能已过期。 */
    private final long stateUpdatedAt;

    private Machine(String name, @Nullable String onTask, @Nullable String offTask,
                    UUID ownerUuid, String ownerName, int permLevel, long createdAt,
                    @NotNull MachineState state, long stateUpdatedAt) {
        this.name = name;
        this.onTask = onTask;
        this.offTask = offTask;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.permLevel = permLevel;
        this.createdAt = createdAt;
        this.state = state == null ? MachineState.UNKNOWN : state;
        this.stateUpdatedAt = stateUpdatedAt;
    }

    /**
     * 构造机器。
     *
     * @param name     机器名，非空且不超过 {@link #MAX_NAME_LENGTH}
     * @param onTask   开启任务名；为 {@code null}/空表示未定义
     * @param offTask  关闭任务名；为 {@code null}/空表示未定义
     * @param ownerUuid 定义者 UUID，不可为 {@code null}
     * @throws IllegalArgumentException 名称为空时抛出
     */
    @NotNull
    public static Machine of(@NotNull String name, @Nullable String onTask, @Nullable String offTask,
                             @NotNull UUID ownerUuid, @Nullable String ownerName, int permLevel) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("机器名不能为空");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("机器名过长（上限 " + MAX_NAME_LENGTH + " 字符）");
        }
        long now = System.currentTimeMillis();
        return new Machine(trimmed,
                normalize(onTask), normalize(offTask),
                Objects.requireNonNull(ownerUuid, "ownerUuid"),
                ownerName == null ? "" : ownerName,
                Math.max(0, Math.min(4, permLevel)),
                now, MachineState.UNKNOWN, now);
    }

    /**
     * 生成一个副本，把状态设为 {@code newState}。
     *
     * <p><b>为什么不可变 + 整体替换</b>：状态变更需要落盘，
     * 若用 setter 就地修改，会丢失「什么时候改的」这一信息，
     * 也让并发读取（回放线程 / 命令线程）看到半更新的对象。
     *
     * @param newState 新状态；为 {@code null} 时视为 {@link MachineState#UNKNOWN}
     */
    @NotNull
    public Machine withState(@Nullable MachineState newState) {
        MachineState target = newState == null ? MachineState.UNKNOWN : newState;
        if (target == this.state) {
            return this; // 状态未变，不产生新对象
        }
        return new Machine(name, onTask, offTask, ownerUuid, ownerName, permLevel,
                createdAt, target, System.currentTimeMillis());
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @NotNull
    public String name() {
        return name;
    }

    /** 存储用的小写 key。 */
    @NotNull
    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }

    @Nullable
    public String onTask() {
        return onTask;
    }

    @Nullable
    public String offTask() {
        return offTask;
    }

    @NotNull
    public UUID ownerUuid() {
        return ownerUuid;
    }

    @NotNull
    public String ownerName() {
        return ownerName;
    }

    public int permLevel() {
        return permLevel;
    }

    public long createdAt() {
        return createdAt;
    }

    /** 当前状态，永不为 {@code null}。 */
    @NotNull
    public MachineState state() {
        return state;
    }

    /**
     * 状态最后更新时间。
     *
     * <p>用于提示玩家「这个状态是 3 天前记的，可能已经不准了」。
     */
    public long stateUpdatedAt() {
        return stateUpdatedAt;
    }

    /** 状态是否已知（非 UNKNOWN）。 */
    public boolean hasKnownState() {
        return state != MachineState.UNKNOWN;
    }

    public boolean hasOn() {
        return onTask != null;
    }

    public boolean hasOff() {
        return offTask != null;
    }

    @NotNull
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append(name).append(" ");
        builder.append(state.color()).append('[').append(state.display()).append("]§r [");
        builder.append("开: ").append(onTask == null ? "未定义" : onTask).append(" | ");
        builder.append("关: ").append(offTask == null ? "未定义" : offTask).append("]");
        return builder.toString();
    }

    @NotNull
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        if (onTask != null) {
            obj.addProperty("onTask", onTask);
        }
        if (offTask != null) {
            obj.addProperty("offTask", offTask);
        }
        obj.addProperty("ownerUuid", ownerUuid.toString());
        obj.addProperty("ownerName", ownerName);
        obj.addProperty("permLevel", permLevel);
        obj.addProperty("createdAt", createdAt);
        obj.addProperty("state", state.id());
        obj.addProperty("stateUpdatedAt", stateUpdatedAt);
        return obj;
    }

    /** 从 JSON 还原；名称或 UUID 非法时返回 {@code null}。 */
    @Nullable
    public static Machine fromJson(@Nullable JsonObject obj) {
        if (obj == null) {
            return null;
        }
        String name = JsonUtil.stringOr(obj, "", "name");
        if (name.isEmpty()) {
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
            } catch (RuntimeException ignored) {
                permLevel = 0;
            }
        }
        long createdAt = System.currentTimeMillis();
        var timeElement = JsonUtil.path(obj, "createdAt");
        if (timeElement != null && timeElement.isJsonPrimitive()) {
            try {
                createdAt = timeElement.getAsLong();
            } catch (RuntimeException ignored) {
                createdAt = System.currentTimeMillis();
            }
        }
        var onElement = JsonUtil.path(obj, "onTask");
        var offElement = JsonUtil.path(obj, "offTask");
        // 状态：读不到就当作 UNKNOWN（绝不猜成 ON 或 OFF）
        MachineState state = MachineState.fromId(JsonUtil.stringOr(obj, "", "state"));
        try {
            Machine machine = Machine.of(name,
                    onElement != null ? onElement.getAsString() : null,
                    offElement != null ? offElement.getAsString() : null,
                    uuid, JsonUtil.stringOr(obj, "", "ownerName"), permLevel);
            // of() 造出来的是 UNKNOWN，这里换成存档里记录的状态
            return machine.withState(state);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Machine other)) {
            return false;
        }
        return permLevel == other.permLevel
                && name.equals(other.name)
                && ownerUuid.equals(other.ownerUuid)
                && Objects.equals(onTask, other.onTask)
                && Objects.equals(offTask, other.offTask);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, ownerUuid, onTask, offTask, permLevel);
    }

    @Override
    public String toString() {
        return "Machine{" + describe() + "}";
    }
}
