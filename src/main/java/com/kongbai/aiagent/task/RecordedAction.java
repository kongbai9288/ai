package com.kongbai.aiagent.task;

import com.google.gson.JsonObject;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * 录制下来的单个动作（不可变值对象）。
 *
 * <p><b>为什么不可变</b>：任务会被持久化和跨 tick 调度，
 * 可变对象在「录制线程写 / 回放线程读」之间会产生竞争。不可变后天然线程安全。
 *
 * <p><b>内存安全</b>：只存基本类型与 String，绝不持有任何游戏对象
 * （{@code ServerPlayer} / {@code Vec3} / {@code Level} 一律不存）。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@link Type#MOVE} —— {@code x/y/z} 有效，{@code command} 为 null</li>
 *   <li>{@link Type#LOOK} —— {@code yaw/pitch} 有效，{@code command} 为 null</li>
 *   <li>{@link Type#COMMAND} —— {@code command} 有效，坐标字段为 0</li>
 * </ul>
 * 未使用的字段保持 0 / null，避免"看起来有值"的误解。
 */
public final class RecordedAction {

    /** 动作类型。 */
    public enum Type {
        /** 位置变化。 */
        MOVE,
        /** 视角变化。 */
        LOOK,
        /** 执行了一条命令。 */
        COMMAND;

        @NotNull
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 从持久化 id 还原；无法识别时返回 {@code null}（不抛异常，跳过损坏数据）。 */
        @Nullable
        public static Type fromId(@Nullable String id) {
            if (id == null) {
                return null;
            }
            for (Type type : values()) {
                if (type.id().equals(id.toLowerCase(Locale.ROOT))) {
                    return type;
                }
            }
            return null;
        }
    }

    private final Type type;
    /** 相对录制开始的游戏刻。用于回放时还原节奏。 */
    private final long tickOffset;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    @Nullable
    private final String command;

    private RecordedAction(Type type, long tickOffset, double x, double y, double z,
                           float yaw, float pitch, @Nullable String command) {
        this.type = type;
        this.tickOffset = tickOffset;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.command = command;
    }

    // ---------- 工厂 ----------

    /** 构造位置动作。坐标为 {@code NaN}/{@code Infinity} 时抛异常 —— 脏数据不应进入任务。 */
    @NotNull
    public static RecordedAction move(long tickOffset, double x, double y, double z) {
        validateFinite(x, "x");
        validateFinite(y, "y");
        validateFinite(z, "z");
        return new RecordedAction(Type.MOVE, tickOffset, x, y, z, 0f, 0f, null);
    }

    /** 构造视角动作。 */
    @NotNull
    public static RecordedAction look(long tickOffset, float yaw, float pitch) {
        validateFinite(yaw, "yaw");
        validateFinite(pitch, "pitch");
        return new RecordedAction(Type.LOOK, tickOffset, 0, 0, 0, yaw, pitch, null);
    }

    /**
     * 构造命令动作。
     *
     * @param tickOffset 相对刻
     * @param command    命令原文（不含前导 {@code /}）；为 {@code null} 或空白时抛异常
     */
    @NotNull
    public static RecordedAction command(long tickOffset, @NotNull String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        return new RecordedAction(Type.COMMAND, tickOffset, 0, 0, 0, 0f, 0f, trimmed);
    }

    private static void validateFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限数值");
        }
    }

    // ---------- 访问器 ----------

    @NotNull
    public Type type() {
        return type;
    }

    public long tickOffset() {
        return tickOffset;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /**
     * 命令原文。
     *
     * @return 仅 {@link Type#COMMAND} 有值，其余类型返回 {@code null}
     */
    @Nullable
    public String command() {
        return command;
    }

    /** 人类可读摘要，用于列表展示与日志。命令内容不脱敏（任务本身不是机密）。 */
    @NotNull
    public String describe() {
        return switch (type) {
            case MOVE -> String.format(Locale.ROOT, "移动 (%.2f, %.2f, %.2f)", x, y, z);
            case LOOK -> String.format(Locale.ROOT, "视角 (%.1f, %.1f)", yaw, pitch);
            case COMMAND -> "执行 /" + (command == null ? "" : command);
        };
    }

    // ---------- 序列化 ----------

    @NotNull
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type.id());
        obj.addProperty("tick", tickOffset);
        switch (type) {
            case MOVE -> {
                obj.addProperty("x", x);
                obj.addProperty("y", y);
                obj.addProperty("z", z);
            }
            case LOOK -> {
                obj.addProperty("yaw", yaw);
                obj.addProperty("pitch", pitch);
            }
            case COMMAND -> obj.addProperty("command", command);
            // 语句形式的 switch 必须留 default：若将来枚举新增值，
            // 没有 default 会静默写出一个不含任何数据字段的 JSON，
            // 反序列化时变成"类型对但值为 0"的脏动作。显式抛错优于静默。
            default -> throw new IllegalStateException("未处理的动作类型: " + type);
        }
        return obj;
    }

    /**
     * 从 JSON 还原。
     *
     * @return 成功返回动作；类型未知、字段缺失、数值非法时返回 {@code null}
     *         （单条损坏不应导致整个任务加载失败）
     */
    @Nullable
    public static RecordedAction fromJson(@Nullable JsonObject obj) {
        if (obj == null) {
            return null;
        }
        Type type = Type.fromId(JsonUtil.stringOr(obj, "", "type"));
        if (type == null) {
            return null;
        }
        long tick = readLongOr(obj, "tick", 0L);
        try {
            return switch (type) {
                case MOVE -> RecordedAction.move(tick,
                        JsonUtil.path(obj, "x") != null ? JsonUtil.path(obj, "x").getAsDouble() : 0,
                        JsonUtil.path(obj, "y") != null ? JsonUtil.path(obj, "y").getAsDouble() : 0,
                        JsonUtil.path(obj, "z") != null ? JsonUtil.path(obj, "z").getAsDouble() : 0);
                case LOOK -> RecordedAction.look(tick,
                        JsonUtil.path(obj, "yaw") != null ? JsonUtil.path(obj, "yaw").getAsFloat() : 0f,
                        JsonUtil.path(obj, "pitch") != null ? JsonUtil.path(obj, "pitch").getAsFloat() : 0f);
                case COMMAND -> {
                    String cmd = JsonUtil.stringOr(obj, "", "command");
                    yield cmd.isEmpty() ? null : RecordedAction.command(tick, cmd);
                }
            };
        } catch (IllegalArgumentException | NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * 安全读取 long 字段。
     *
     * <p>存档文件可能被手改（例如把 tick 写成 "abc"），
     * 直接 {@code getAsLong()} 会抛 {@link NumberFormatException}，
     * 导致整个任务加载失败。这里统一回落默认值。
     */
    private static long readLongOr(@NotNull JsonObject obj, @NotNull String key, long fallback) {
        var element = JsonUtil.path(obj, key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecordedAction other)) {
            return false;
        }
        return tickOffset == other.tickOffset
                && type == other.type
                && Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0
                && Float.compare(yaw, other.yaw) == 0
                && Float.compare(pitch, other.pitch) == 0
                && Objects.equals(command, other.command);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, tickOffset, x, y, z, yaw, pitch, command);
    }

    @Override
    public String toString() {
        return "RecordedAction{tick=" + tickOffset + ", " + describe() + "}";
    }
}
