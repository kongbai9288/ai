package com.kongbai.aiagent.task;

import com.kongbai.aiagent.util.PermissionGuard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单个玩家的录制会话（<b>可变</b>，但仅由录制线程/主线程访问）。
 *
 * <p>这是本模块唯一的"可控可变状态"：
 * 录制过程中需要不断追加动作，若每帧生成新的不可变 {@link RecordedTask}，
 * 会产生大量短命对象（20 次/秒 × N 玩家），得不偿失。
 * 因此录制期用可变容器，{@link #finish()} 时才生成不可变任务对象。
 *
 * <p><b>必须调用 {@link #finish()} 或 {@link #abort()}</b>：
 * 会话持有 {@code List<RecordedAction>}，玩家下线若不清理，内存持续增长。
 * {@link RecorderManager} 在玩家下线/服务器关闭时统一 abort。
 *
 * <p><b>采样策略（防止数据爆炸）</b>：
 * <ul>
 *   <li>位置：移动距离超过 {@link #MOVE_THRESHOLD} 才记录</li>
 *   <li>视角：角度变化超过 {@link #LOOK_THRESHOLD} 才记录</li>
 * </ul>
 * 静止站立时几乎不产生数据；跑动约每刻 1 条。
 * 上限 {@link RecordedTask#MAX_ACTIONS} 条，超过后 {@link #isFull()} 返回 true，
 * 由 {@link RecorderManager} 强制结束录制。
 *
 * <p><b>空指针约束</b>：所有 {@code @NotNull} 参数传入 {@code null} 会抛 NPE。
 */
public final class TaskRecorder {

    /** 位置采样阈值（格）。小于此距离的移动视为抖动，不记录。 */
    public static final double MOVE_THRESHOLD = 0.15;
    /** 视角采样阈值（度）。 */
    public static final float LOOK_THRESHOLD = 2.0f;
    /** 是否允许阈值平方比较，避免开方。位置比较用平方距离。 */
    private static final double MOVE_THRESHOLD_SQ = MOVE_THRESHOLD * MOVE_THRESHOLD;

    private final UUID ownerUuid;
    private final String ownerName;
    private final int permLevel;
    private final String taskName;
    private final long startTick;

    private final List<RecordedAction> actions = new ArrayList<>();

    // 上一次采样时的状态，用于阈值比较
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;
    private boolean hasSample;

    private boolean finished;

    TaskRecorder(@NotNull UUID ownerUuid, @Nullable String ownerName, int permLevel,
                 @NotNull String taskName, long startTick) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.permLevel = Math.max(0, Math.min(4, permLevel));
        this.taskName = taskName;
        this.startTick = startTick;
    }

    // ---------- 采样 ----------

    /**
     * 每刻调用一次，采样玩家位置与视角。
     *
     * <p><b>幂等性</b>：已结束的会话调用本方法直接返回，不抛异常、不记录。
     *
     * @param tick  当前游戏刻（绝对刻）
     * @param x,y,z 玩家坐标
     * @param yaw   水平视角
     * @param pitch 垂直视角
     */
    public void sample(long tick, double x, double y, double z, float yaw, float pitch) {
        if (finished) {
            return;
        }
        if (!isFinite(x) || !isFinite(y) || !isFinite(z) || !isFinite(yaw) || !isFinite(pitch)) {
            // 坐标异常（例如玩家在未加载区块/切维度瞬间）时跳过本次采样，
            // 不能让 NaN 进入任务，否则回放时 tp 到 NaN 会破坏实体
            return;
        }
        long offset = Math.max(0, tick - startTick);

        if (!hasSample) {
            // 第一个采样点：无条件记录，作为回放的起点
            actions.add(RecordedAction.move(offset, x, y, z));
            actions.add(RecordedAction.look(offset, yaw, pitch));
            lastX = x;
            lastY = y;
            lastZ = z;
            lastYaw = yaw;
            lastPitch = pitch;
            hasSample = true;
            return;
        }

        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        if (dx * dx + dy * dy + dz * dz >= MOVE_THRESHOLD_SQ) {
            actions.add(RecordedAction.move(offset, x, y, z));
            lastX = x;
            lastY = y;
            lastZ = z;
        }

        if (Math.abs(yaw - lastYaw) >= LOOK_THRESHOLD || Math.abs(pitch - lastPitch) >= LOOK_THRESHOLD) {
            actions.add(RecordedAction.look(offset, yaw, pitch));
            lastYaw = yaw;
            lastPitch = pitch;
        }
    }

    /**
     * 记录一条玩家执行的命令。
     *
     * <p><b>会过滤掉本模组的录制命令</b>（见 {@link #shouldIgnoreCommand}），
     * 否则玩家用 {@code /carpet ai rec stop} 结束录制时，这条命令会被录进去，
     * 回放时又触发一次录制停止 —— 形成自指循环。
     *
     * @return 已记录返回 {@code true}；被过滤/会话已满/命令非法返回 {@code false}
     */
    public boolean recordCommand(long tick, @Nullable String rawCommand) {
        if (finished || rawCommand == null) {
            return false;
        }
        String command = rawCommand.trim();
        if (command.isEmpty() || shouldIgnoreCommand(command)) {
            return false;
        }
        if (isFull()) {
            return false;
        }
        long offset = Math.max(0, tick - startTick);
        try {
            actions.add(RecordedAction.command(offset, command));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 判断命令是否应被忽略。
     *
     * <p>当前规则：以 {@code carpet ai} 开头的命令一概不录。
     * 这覆盖了 {@code /carpet ai rec ...}、{@code /carpet ai run ...} 等，
     * 避免录制与回放互相触发。
     *
     * <p>注意用 {@link PermissionGuard#rootOf} 无法判断二级节点，
     * 因此这里做轻量字符串判断（命令原文已去除前导 {@code /}）。
     */
    private static boolean shouldIgnoreCommand(@NotNull String command) {
        String text = command.startsWith("/") ? command.substring(1) : command;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        // 容忍 "carpet ai" / "carpet  ai"（多空格）两种写法
        return lower.startsWith("carpet ai") || lower.startsWith("carpet  ai");
    }

    // ---------- 状态 ----------

    public boolean isFinished() {
        return finished;
    }

    public boolean isFull() {
        return actions.size() >= RecordedTask.MAX_ACTIONS;
    }

    public int size() {
        return actions.size();
    }

    @NotNull
    public String taskName() {
        return taskName;
    }

    @NotNull
    public UUID ownerUuid() {
        return ownerUuid;
    }

    public long startTick() {
        return startTick;
    }

    /**
     * 结束录制并生成不可变任务。
     *
     * @return 任务对象；动作数为 0 时返回 {@code null}（空任务没有保存价值）
     */
    @Nullable
    public RecordedTask finish() {
        if (finished) {
            return null;
        }
        finished = true;
        if (actions.isEmpty()) {
            return null;
        }
        return RecordedTask.of(taskName, ownerUuid, ownerName, permLevel, actions);
    }

    /**
     * 丢弃录制（玩家下线、服务器关闭、同名覆盖等场景）。
     *
     * <p><b>必须调用</b>：清空内部列表，释放内存。
     * 与 {@link #finish()} 的区别是不生成任务对象。
     */
    public void abort() {
        finished = true;
        actions.clear();
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
