package com.kongbai.aiagent.task;

import com.kongbai.aiagent.util.PermissionGuard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务回放执行器（进程内单例）。
 *
 * <p><b>调度模型</b>：每个回放实例记录"开始刻"与"当前动作索引"。
 * 每刻调用 {@link #tick(long)}，把所有 {@code tickOffset <= 已过刻数} 的动作一次性执行完。
 * 这样回放节奏与录制节奏一致（录制时隔 10 刻做的事，回放时也隔 10 刻做）。
 *
 * <p><b>三种动作的执行方式</b>：
 * <ul>
 *   <li>{@code MOVE} —— 有假人名时 {@code /player <假人> tp x y z}；无假人时 tp 执行者自己</li>
 *   <li>{@code LOOK} —— 有假人名时 {@code /player <假人> look yaw pitch}；无假人时 tp 执行者自己</li>
 *   <li>{@code COMMAND} —— <b>原样执行</b>，但必须先过 {@link PermissionGuard#check} 并以
 *       任务创建者的权限等级降权执行</li>
 * </ul>
 *
 * <p><b>为什么命令要原样执行</b>：玩家录制的是"对机器的操作"（开关、放置、红石等），
 * 这些操作本身就是命令。回放时替换玩家名反而会改变语义（例如 {@code /give @s} 的 {@code @s}
 * 应该指向执行者）。因此不做替换，只做权限收敛。
 *
 * <p><b>内存安全</b>：
 * <ul>
 *   <li>回放实例只持有不可变的 {@link RecordedTask}、字符串和基本类型，
 *       <b>不持有</b> {@code ServerPlayer} / {@code CommandSourceStack}</li>
 *   <li>执行结果通过 {@link #tick(long)} 返回的文本列表回传，不保存回调对象</li>
 *   <li>{@link #stopAll()} 是唯一的全局清空出口，服务器关闭时必须调用</li>
 * </ul>
 */
public final class TaskRunner {

    /** 同时进行的回放实例上限，防止玩家一次性触发几百个回放拖垮 tick。 */
    public static final int MAX_CONCURRENT = 32;

    private static final TaskRunner INSTANCE = new TaskRunner();

    /** 回放 ID -&gt; 实例。 */
    private final Map<Long, Playback> playbacks = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private TaskRunner() {
    }

    @NotNull
    public static TaskRunner getInstance() {
        return INSTANCE;
    }

    // ---------- 启动回放 ----------

    /**
     * 启动一个回放。
     *
     * <p><b>为什么不在这里传 {@link CommandSink}</b>：
     * sink 的实现需要 {@code MinecraftServer} 才能派发命令。
     * 若把它存进回放实例，就等于让长生命周期对象间接持有服务器引用 ——
     * 服务器关闭后整条引用链无法回收。
     * 因此改为在 {@link #tick(long, CommandSink)} 时临时传入，
     * 回放实例本身只持有不可变的任务数据。
     *
     * @param task      任务，不可为 {@code null}
     * @param fakeName  驱动哪个假人；为 {@code null}/空时驱动执行者自己
     * @param startTick 起始游戏刻
     * @return 回放 ID；达到并发上限或参数为 null 时返回 {@code null}
     */
    @Nullable
    public Long start(@Nullable RecordedTask task, @Nullable String fakeName, long startTick) {
        return start(task, fakeName, startTick, false);
    }

    /**
     * 启动一个回放。
     *
     * @param force 为 true 时<b>跳过方块状态检测</b>，无条件执行所有命令。
     *              用于玩家确认「状态记录不准，但我就是要执行」的场景。
     */
    @Nullable
    public Long start(@Nullable RecordedTask task, @Nullable String fakeName,
                      long startTick, boolean force) {
        if (task == null) {
            return null;
        }
        if (task.isEmpty()) {
            return null;
        }
        if (playbacks.size() >= MAX_CONCURRENT) {
            return null;
        }
        long id = nextId.getAndIncrement();
        playbacks.put(id, new Playback(id, task, normalizeFakeName(fakeName), startTick, force));
        return id;
    }

    @Nullable
    private static String normalizeFakeName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---------- 每刻驱动 ----------

    /**
     * 驱动所有回放前进一刻。
     *
     * @param currentTick 当前游戏刻
     * @param sink        命令投递器（由调用方按当前服务器上下文提供，不被本类保存）
     * @return 本刻产生的反馈文本（执行失败原因等）；无内容时返回空列表
     */
    @NotNull
    public List<String> tick(long currentTick, @Nullable CommandSink sink,
                             @Nullable BlockProbe probe) {
        List<String> messages = new ArrayList<>();
        if (playbacks.isEmpty()) {
            return messages;
        }
        if (sink == null) {
            // 没有投递器时无法推进，直接停止全部回放，避免它们永远卡在列表里
            messages.add("§c回放系统未就绪，已停止所有回放");
            stopAll();
            return messages;
        }
        List<Long> finished = new ArrayList<>();
        for (Playback playback : playbacks.values()) {
            if (playback == null) {
                continue;
            }
            try {
                playback.step(currentTick, sink, probe, messages);
            } catch (RuntimeException e) {
                // 单个回放出错不能影响其他回放，更不能打断游戏刻
                messages.add("§c回放 " + playback.id + " 异常: " + e.getMessage());
                finished.add(playback.id);
                continue;
            }
            if (playback.isDone()) {
                finished.add(playback.id);
                messages.add("§a任务「" + playback.task.name() + "」回放完成");
            }
        }
        for (Long id : finished) {
            playbacks.remove(id);
        }
        return messages;
    }

    // ---------- 控制 ----------

    /** 停止指定回放。返回是否确实停止了。 */
    public boolean stop(@Nullable Long id) {
        if (id == null) {
            return false;
        }
        return playbacks.remove(id) != null;
    }

    /** 停止全部回放（服务器关闭、玩家执行 stopall 时调用）。 */
    public void stopAll() {
        playbacks.clear();
    }

    public int activeCount() {
        return playbacks.size();
    }

    public boolean hasActive() {
        return !playbacks.isEmpty();
    }

    /** 正在回放的任务名列表，供 {@code /carpet ai run list} 展示。 */
    @NotNull
    public List<String> activeNames() {
        List<String> out = new ArrayList<>();
        for (Playback playback : playbacks.values()) {
            if (playback != null) {
                out.add("#" + playback.id + " " + playback.task.name()
                        + (playback.fakeName == null ? "" : " → " + playback.fakeName));
            }
        }
        return out;
    }

    // ---------- 内部：单个回放实例 ----------

    private static final class Playback {
        private final long id;
        @NotNull
        private final RecordedTask task;
        @Nullable
        private final String fakeName;
        private final long startTick;
        /** 是否跳过状态检测强制执行。 */
        private final boolean force;
        private int cursor;
        /** 本回放发送过"检测中"提示，避免每个动作都刷屏。 */
        private boolean probeNotified;

        Playback(long id, @NotNull RecordedTask task, @Nullable String fakeName,
                 long startTick, boolean force) {
            this.id = id;
            this.task = task;
            this.fakeName = fakeName;
            this.startTick = startTick;
            this.force = force;
        }

        boolean isDone() {
            return cursor >= task.size();
        }

        /**
         * 推进到当前刻。
         *
         * <p><b>批量执行</b>：把到期的动作一次做完，而不是每刻只做一个。
         * 否则当录制时某一刻有多个动作时，回放会被拉长。
         */
        void step(long currentTick, @NotNull CommandSink sink, @Nullable BlockProbe probe,
                  @NotNull List<String> messages) {
            if (isDone()) {
                return;
            }
            long elapsed = Math.max(0, currentTick - startTick);
            List<RecordedAction> actions = task.actions();
            while (cursor < actions.size()) {
                RecordedAction action = actions.get(cursor);
                if (action == null) {
                    cursor++;
                    continue;
                }
                if (action.tickOffset() > elapsed) {
                    break; // 还没到时间
                }
                execute(action, sink, probe, messages);
                cursor++;
            }
        }

        private void execute(@NotNull RecordedAction action, @NotNull CommandSink sink,
                             @Nullable BlockProbe probe, @NotNull List<String> messages) {
            switch (action.type()) {
                case MOVE -> {
                    String cmd = fakeName == null
                            ? String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f", "@s",
                            action.x(), action.y(), action.z())
                            : String.format(Locale.ROOT, "player %s tp %.3f %.3f %.3f",
                            fakeName, action.x(), action.y(), action.z());
                    dispatch(cmd, "移动", sink, messages);
                }
                case LOOK -> {
                    String cmd = fakeName == null
                            ? String.format(Locale.ROOT, "tp %s %.1f %.1f", "@s", action.yaw(), action.pitch())
                            : String.format(Locale.ROOT, "player %s look %.1f %.1f",
                            fakeName, action.yaw(), action.pitch());
                    dispatch(cmd, "视角", sink, messages);
                }
                case COMMAND -> {
                    String raw = action.command();
                    if (raw == null || raw.isEmpty()) {
                        return;
                    }
                    // 关键：AI/录制内容属于不可信输入，必须过权限闸门
                    String reason = PermissionGuard.check(raw);
                    if (reason != null) {
                        messages.add("§c已拦截任务「" + task.name() + "」中的命令 /"
                                + PermissionGuard.rootOf(raw) + "：" + reason);
                        return;
                    }
                    // 方块状态检测：判断这活儿是不是已经干过了
                    if (!shouldExecuteByState(action, probe, messages)) {
                        return;
                    }
                    dispatch(raw, "命令", sink, messages);
                }
            }
        }

        /**
         * 根据方块状态判断这条命令是否应该执行。
         *
         * <p><b>判定逻辑</b>：快照记录的是「执行命令前现场长什么样」。
         * <ul>
         *   <li>当前状态与快照<b>一致</b> → 这活儿还没干过，<b>执行</b></li>
         *   <li>不一致 → 现场已经变了（多半是已经执行过，或被手动改过），<b>跳过</b></li>
         * </ul>
         * 这样就能避免「机器已经关了，再说关闭，结果又按一次按钮把它打开」。
         *
         * <p><b>降级</b>：没快照 / 探测器不可用 / force 模式 → 一律返回 true（照常执行）。
         * 检测是增强，不能因为检测失败就让功能不可用。
         *
         * @return 应该执行返回 {@code true}；应跳过返回 {@code false}
         */
        private boolean shouldExecuteByState(@NotNull RecordedAction action,
                                             @Nullable BlockProbe probe,
                                             @NotNull List<String> messages) {
            // 没有快照就没法判断，按原行为执行
            if (!action.hasSnapshots()) {
                return true;
            }
            // force 模式：玩家明确要求无条件执行
            if (force) {
                if (!probeNotified) {
                    probeNotified = true;
                    messages.add("§6任务「" + task.name() + "」为强制执行，已跳过状态检测");
                }
                return true;
            }
            if (probe == null || !probe.isAvailable()) {
                return true;
            }

            java.util.List<BlockSnapshot> templates = action.snapshots();
            java.util.List<BlockSnapshot> current;
            try {
                // 用绝对坐标定位：机器在世界里的位置是固定的，不随执行者移动
                current = probe.recollect(templates, 0, 0, 0, false);
            } catch (RuntimeException e) {
                return true; // 读取异常不阻断，按原行为执行
            }

            int total = templates.size();
            if (total == 0 || current.size() != total) {
                return true;
            }
            int same = 0;
            BlockSnapshot firstDiff = null;
            for (int i = 0; i < total; i++) {
                if (templates.get(i).matches(current.get(i))) {
                    same++;
                } else if (firstDiff == null) {
                    firstDiff = templates.get(i);
                }
            }

            if (same == total) {
                // 状态一致，确认需要执行
                if (!probeNotified) {
                    probeNotified = true;
                    messages.add("§7[状态检测] 现场与录制时一致（" + total + " 处），执行中...");
                }
                return true;
            }

            // 有差异 → 现场已变，跳过以免反向操作
            String raw = action.command();
            String brief = raw == null ? "" : raw;
            if (brief.length() > 24) {
                brief = brief.substring(0, 24) + "...";
            }
            messages.add("§6[状态检测] 跳过 /" + brief + "：现场与录制时不同"
                    + "（" + same + "/" + total + " 处一致）");
            if (firstDiff != null) {
                messages.add("§8  差异示例：" + firstDiff.describe());
            }
            messages.add("§8  若确认要执行，用 §f/carpet ai run " + task.name() + " force");
            return false;
        }

        /**
         * 执行命令。
         *
         * <p>位置/视角命令也走 {@link PermissionGuard#check}：
         * {@code tp} 与 {@code player} 都在白名单内，因此正常情况下会放行；
         * 这样统一了校验路径，避免以后有人给 {@code tp} 加限制时漏掉这里。
         */
        private void dispatch(@NotNull String command, @NotNull String label,
                              @NotNull CommandSink sink, @NotNull List<String> messages) {
            String reason = PermissionGuard.check(command);
            if (reason != null) {
                messages.add("§c" + label + "被拦截：" + reason);
                return;
            }
            boolean ok = sink.execute(command, task.permLevel());
            if (!ok) {
                messages.add("§c" + label + "执行失败: /" + command);
            }
        }
    }
}
