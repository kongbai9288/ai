package com.kongbai.aiagent.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个方块的红石/状态快照（不可变值对象）。
 *
 * <p><b>为什么需要它</b>：录制的开关任务是<b>动作</b>（按一下按钮），不是<b>状态设置</b>。
 * 单看动作无法知道机器当前是开是关。快照把「执行命令那一刻周围方块长什么样」记下来，
 * 回放时就能通过对比判断「这活儿是不是已经干过了」。
 *
 * <p><b>只存基本类型与 String</b>：绝不持有 {@code BlockState} / {@code BlockPos} /
 * {@code Level} 等游戏对象，否则会造成严重的内存泄漏（一次录制可能产生上千个快照）。
 *
 * <p><b>坐标为什么存两份</b>：
 * <ul>
 *   <li>{@code relX/relY/relZ} —— 相对执行者的偏移。假人模式下用这个，
 *       因为假人会被 {@code MOVE} 动作传送到与录制时相同的位置</li>
 *   <li>{@code absX/absY/absZ} —— 绝对坐标。驱动玩家自己时用这个，
 *       因为玩家不一定站在录制时的位置</li>
 * </ul>
 */
public final class BlockSnapshot {

    /** 维度缺失时的兜底值（主世界）。 */
    public static final String DEFAULT_DIMENSION = "minecraft:overworld";

    /** 相对执行者的偏移。 */
    private final int relX;
    private final int relY;
    private final int relZ;
    /** 绝对坐标（录制时）。 */
    private final int absX;
    private final int absY;
    private final int absZ;
    /** 方块 ID，如 {@code minecraft:stone_button}。 */
    @NotNull
    private final String blockId;
    /**
     * 关键状态摘要，如 {@code powered=true,facing=north}。
     * 只保留红石相关属性，避免噪音（比如台阶的朝向变化不该影响判断）。
     */
    @NotNull
    private final String stateKey;
    /** 该位置的红石信号强度（0-15）。 */
    private final int signal;
    /**
     * 所属维度 ID，如 {@code minecraft:overworld}。
     *
     * <p><b>为什么必须存</b>：机器可能跨维度（主世界按钮控制下界农场）。
     * 只存坐标不存维度，回放时会到主世界去找下界的方块，
     * 读到的全是空气 → 判定为「不一致」→ 该执行的被跳过。
     */
    @NotNull
    private final String dimension;

    private BlockSnapshot(int relX, int relY, int relZ, int absX, int absY, int absZ,
                          @NotNull String blockId, @NotNull String stateKey, int signal,
                          @NotNull String dimension) {
        this.relX = relX;
        this.relY = relY;
        this.relZ = relZ;
        this.absX = absX;
        this.absY = absY;
        this.absZ = absZ;
        this.blockId = blockId;
        this.stateKey = stateKey;
        this.signal = Math.max(0, Math.min(15, signal));
        this.dimension = dimension == null || dimension.isEmpty()
                ? DEFAULT_DIMENSION : dimension;
    }

    @NotNull
    public static BlockSnapshot of(int relX, int relY, int relZ, int absX, int absY, int absZ,
                                   @Nullable String blockId, @Nullable String stateKey,
                                   int signal, @Nullable String dimension) {
        return new BlockSnapshot(relX, relY, relZ, absX, absY, absZ,
                blockId == null ? "" : blockId,
                stateKey == null ? "" : stateKey,
                signal, dimension);
    }

    // ---------- 访问器 ----------

    public int relX() {
        return relX;
    }

    public int relY() {
        return relY;
    }

    public int relZ() {
        return relZ;
    }

    public int absX() {
        return absX;
    }

    public int absY() {
        return absY;
    }

    public int absZ() {
        return absZ;
    }

    @NotNull
    public String blockId() {
        return blockId;
    }

    @NotNull
    public String stateKey() {
        return stateKey;
    }

    public int signal() {
        return signal;
    }

    /** 维度 ID，永不为 {@code null}（缺失时返回 {@link #DEFAULT_DIMENSION}）。 */
    @NotNull
    public String dimension() {
        return dimension;
    }

    /**
     * 与另一个快照比较是否"状态一致"。
     *
     * <p><b>只比状态，不比坐标</b>：坐标用于定位取哪个方块，
     * 状态才是判断依据。方块类型也参与比较 —— 若方块被换成了别的种类
     * （比如按钮被拆了换成石头），说明现场已经变了。
     *
     * @return 方块类型、状态摘要、信号强度三者都相同返回 {@code true}
     */
    public boolean matches(@Nullable BlockSnapshot other) {
        if (other == null) {
            return false;
        }
        return blockId.equals(other.blockId)
                && stateKey.equals(other.stateKey)
                && signal == other.signal;
    }

    @NotNull
    public String describe() {
        return String.format("%s @(%d,%d,%d) %s sig=%d [%s]",
                blockId.isEmpty() ? "?" : blockId, absX, absY, absZ,
                stateKey.isEmpty() ? "-" : stateKey, signal, shortDimension());
    }

    // ---------- 序列化 ----------

    @NotNull
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("rel", relX + "," + relY + "," + relZ);
        obj.addProperty("abs", absX + "," + absY + "," + absZ);
        obj.addProperty("block", blockId);
        obj.addProperty("state", stateKey);
        obj.addProperty("signal", signal);
        // 只在非主世界时写维度，保持存档精简（绝大多数机器在主世界）
        if (!DEFAULT_DIMENSION.equals(dimension)) {
            obj.addProperty("dim", dimension);
        }
        return obj;
    }

    /** 从 JSON 还原；数据损坏时返回 {@code null}（单条坏数据不影响整体）。 */
    @Nullable
    public static BlockSnapshot fromJson(@Nullable JsonObject obj) {
        if (obj == null) {
            return null;
        }
        String block = JsonUtil.stringOr(obj, "", "block");
        if (block.isEmpty()) {
            return null;
        }
        int[] rel = parseInts(JsonUtil.stringOr(obj, "", "rel"));
        int[] abs = parseInts(JsonUtil.stringOr(obj, "", "abs"));
        return new BlockSnapshot(
                rel[0], rel[1], rel[2],
                abs[0], abs[1], abs[2],
                block,
                JsonUtil.stringOr(obj, "", "state"),
                readInt(obj, "signal"),
                JsonUtil.stringOr(obj, DEFAULT_DIMENSION, "dim"));
    }

    /** 解析 "x,y,z" 形式，返回长度 3 的数组；失败返回全 0。 */
    @NotNull
    private static int[] parseInts(@NotNull String text) {
        int[] out = new int[3];
        if (text.isEmpty()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static int readInt(@NotNull JsonObject obj, @NotNull String key) {
        var element = JsonUtil.path(obj, key);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 批量序列化。 */
    @NotNull
    public static JsonArray toJsonArray(@NotNull List<BlockSnapshot> list) {
        JsonArray array = new JsonArray();
        for (BlockSnapshot snapshot : list) {
            if (snapshot != null) {
                array.add(snapshot.toJson());
            }
        }
        return array;
    }

    /** 批量反序列化；跳过损坏条目。 */
    @NotNull
    public static List<BlockSnapshot> fromJsonArray(@Nullable com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<BlockSnapshot> out = new ArrayList<>();
        for (var item : element.getAsJsonArray()) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            BlockSnapshot snapshot = fromJson(item.getAsJsonObject());
            if (snapshot != null) {
                out.add(snapshot);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockSnapshot other)) {
            return false;
        }
        return relX == other.relX && relY == other.relY && relZ == other.relZ
                && absX == other.absX && absY == other.absY && absZ == other.absZ
                && signal == other.signal
                && dimension.equals(other.dimension)
                && blockId.equals(other.blockId)
                && stateKey.equals(other.stateKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relX, relY, relZ, absX, absY, absZ, blockId, stateKey, signal, dimension);
    }

    @Override
    public String toString() {
        return "BlockSnapshot{" + describe() + "}";
    }

    /**
     * 维度短名（去掉 {@code minecraft:} 前缀），仅用于展示。
     */
    @NotNull
    public String shortDimension() {
        int idx = dimension.indexOf(':');
        return idx >= 0 && idx + 1 < dimension.length() ? dimension.substring(idx + 1) : dimension;
    }

    /** 空列表常量，避免重复分配。 */
    @NotNull
    public static List<BlockSnapshot> none() {
        return Collections.emptyList();
    }
}
