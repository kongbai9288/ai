package com.kongbai.aiagent.task;

import com.kongbai.aiagent.ai.AiPlan;
import com.kongbai.aiagent.util.PermissionGuard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 长期任务调度器（进程内单例）。
 *
 * <p><b>职责</b>：管理 AI（或玩家）发起的「长期任务」——
 * 例如「让假人一直挖矿，2 小时后停」。这类任务不是一次性的动作序列，
 * 而是「持续保持某个状态，到点自动停止」。
 *
 * <p><b>与 {@link TaskRunner} 的区别</b>：
 * <ul>
 *   <li>{@code TaskRunner} 回放<b>录制好的动作序列</b>（有固定时间轴，会自然结束）</li>
 *   <li>{@code Scheduler} 维持<b>持续状态</b>（无时间轴，靠 deadline 或手动停止）</li>
 * </ul>
 *
 * <p><b>终止时间语义</b>：
 * <ul>
 *   <li>{@code deadlineTick < 0} —— 一直执行，直到手动停或服务器关闭</li>
 *   <li>{@code deadlineTick > 0} —— 到该游戏刻自动停止</li>
 * </ul>
 *
 * <p><b>内存安全</b>：
 * <ul>
 *   <li>只持有 {@code UUID}、字符串、基本类型与不可变列表</li>
 *   <li><b>不持有</b> {@code CommandSink}（每刻由 tick 参数传入）</li>
 *   <li>{@link #stopAll()} 是唯一全局清空出口，服务器关闭时必须调用</li>
 * </ul>
 *
 * <p><b>执行方式</b>：启动时执行一次「开始命令」，到期时执行一次「停止命令」。
 * 中间不重复执行 —— 因为 carpet 的 {@code /player X use continuous} 本身就会持续，
 * 重复执行反而会打断。这是刻意的简化，也让行为可预测。
 */
public final class Scheduler {

    /** 同时进行的长期任务上限。 */
    public static final int MAX_CONCURRENT = 64;

    private static final Scheduler INSTANCE = new Scheduler();

    private final Map<Long, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private Scheduler() {
    }

    @NotNull
    public static Scheduler getInstance() {
        return INSTANCE;
    }

    /**
     * 注册一个长期任务。
     *
     * @param plan      AI 计划，不可为 {@code null}
     * @param ownerUuid 发起者 UUID
     * @param permLevel 权限等级（用于降权执行）
     * @param startTick 起始游戏刻
     * @param stopCommands 到期时要执行的「停止」命令；为空时用 {@code /player <假人> stop}
     * @return 任务 ID；达到上限或参数非法返回 {@code null}
     */
    @Nullable
    public Long schedule(@Nullable AiPlan plan, @Nullable UUID ownerUuid, int permLevel,
                         long startTick, @Nullable List<String> stopCommands) {
        if (plan == null) {
            return null;
        }
        if (tasks.size() >= MAX_CONCURRENT) {
            return null;
        }
        long id = nextId.getAndIncrement();
        long deadlineTick;
        if (plan.deadlineSeconds() < 0) {
            deadlineTick = -1L; // 一直
        } else {
            // 秒 -> 刻（20 刻 = 1 秒）
            deadlineTick = startTick + plan.deadlineSeconds() * 20L;
        }
        tasks.put(id, new ScheduledTask(id, plan, ownerUuid,
                Math.max(0, Math.min(4, permLevel)), startTick, deadlineTick,
                sanitize(stopCommands)));
        return id;
    }

    @NotNull
    private static List<String> sanitize(@Nullable List<String> commands) {
        if (commands == null) {
            return List.of();
        }
        return commands.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .limit(PermissionGuard.MAX_COMMANDS_PER_RESPONSE)
                .toList();
    }

    /**
     * 每刻驱动：检查到期任务并执行停止命令。
     *
     * @param currentTick 当前游戏刻
     * @param sink        命令投递器（临时传入，不保存）
     * @return 本刻产生的反馈文本
     */
    @NotNull
    public List<String> tick(long currentTick, @Nullable CommandSink sink) {
        List<String> messages = new java.util.ArrayList<>();
        if (tasks.isEmpty()) {
            return messages;
        }
        if (sink == null) {
            messages.add("§c调度系统未就绪，已停止所有长期任务");
            stopAll();
            return messages;
        }
        java.util.List<Long> toRemove = new java.util.ArrayList<>();
        for (ScheduledTask task : tasks.values()) {
            if (task == null) {
                continue;
            }
            if (task.deadlineTick < 0) {
                continue; // 一直执行，等手动停
            }
            if (currentTick < task.deadlineTick) {
                continue;
            }
            try {
                task.runStop(sink, messages);
                messages.add("§a长期任务 " + task.id + " 已到时自动停止");
            } catch (RuntimeException e) {
                messages.add("§c长期任务 " + task.id + " 停止时异常: " + e.getMessage());
            }
            toRemove.add(task.id);
        }
        for (Long id : toRemove) {
            tasks.remove(id);
        }
        return messages;
    }

    /** 停止指定长期任务（会执行其停止命令）。 */
    public boolean stop(@Nullable Long id, @Nullable CommandSink sink) {
        if (id == null) {
            return false;
        }
        ScheduledTask task = tasks.remove(id);
        if (task == null) {
            return false;
        }
        if (sink != null) {
            try {
                task.runStop(sink, new java.util.ArrayList<>());
            } catch (RuntimeException ignored) {
                // 停止失败也要把任务从表里移除，否则会永远卡住
            }
        }
        return true;
    }

    /** 停止全部（不执行停止命令，用于服务器关闭）。 */
    public void stopAll() {
        tasks.clear();
    }

    /**
     * 停止全部并<b>执行各自的停止命令</b>。
     *
     * <p><b>为什么需要这个重载</b>：{@link #stopAll()} 只是清空内存表，
     * 一条停止命令都不发。服务器关闭时这是对的（世界马上就没了），
     * 但玩家执行 {@code /carpet ai sched stop} 时期望的是「假人真的停下来」——
     * 用 {@code stopAll()} 会显示「已停止 N 个」，而假人还在原地继续挖矿。
     *
     * @param sink 命令投递器；为 {@code null} 时退化为 {@link #stopAll()}
     * @return 被停止的任务数
     */
    public int stopAll(@Nullable CommandSink sink) {
        int count = tasks.size();
        if (count == 0) {
            return 0;
        }
        if (sink == null) {
            stopAll();
            return count;
        }
        // 逐个 stop 而不是直接 clear —— 每个任务都要发出自己的停止命令，
        // 否则 carpet 假人会一直保持 use/attack/move 的持续动作。
        for (Long id : new java.util.ArrayList<>(tasks.keySet())) {
            stop(id, sink);
        }
        tasks.clear(); // 兜底：个别任务 stop 失败也要清空
        return count;
    }

    /** 停止某玩家发起的全部长期任务。 */
    public int stopByOwner(@Nullable UUID uuid, @Nullable CommandSink sink) {
        if (uuid == null) {
            return 0;
        }
        int count = 0;
        for (ScheduledTask task : tasks.values()) {
            if (task != null && uuid.equals(task.ownerUuid)) {
                if (stop(task.id, sink)) {
                    count++;
                }
            }
        }
        return count;
    }

    public int activeCount() {
        return tasks.size();
    }

    public boolean hasActive() {
        return !tasks.isEmpty();
    }

    /**
     * 展示用：进行中的长期任务描述。
     *
     * @param currentTick 当前游戏刻，用于计算<b>真实剩余时间</b>。
     *                    缺了它只能算出「总时长」，显示的倒计时会永远停在初始值不递减。
     */
    @NotNull
    public List<String> activeNames(long currentTick) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ScheduledTask task : tasks.values()) {
            if (task == null) {
                continue;
            }
            StringBuilder builder = new StringBuilder();
            builder.append('#').append(task.id).append(' ').append(task.plan.describe());
            if (task.deadlineTick >= 0) {
                long remainTicks = Math.max(0, task.deadlineTick - currentTick);
                builder.append(" §8| 剩余 ").append(remainTicks / 20).append("s");
            }
            out.add(builder.toString());
        }
        return out;
    }

    @Nullable
    public ScheduledTask get(@Nullable Long id) {
        return id == null ? null : tasks.get(id);
    }

    /** 单个长期任务（不可变）。 */
    public static final class ScheduledTask {
        private final long id;
        @NotNull
        private final AiPlan plan;
        @Nullable
        private final UUID ownerUuid;
        private final int permLevel;
        private final long startTick;
        private final long deadlineTick;
        @NotNull
        private final List<String> stopCommands;

        ScheduledTask(long id, @NotNull AiPlan plan, @Nullable UUID ownerUuid, int permLevel,
                      long startTick, long deadlineTick, @NotNull List<String> stopCommands) {
            this.id = id;
            this.plan = plan;
            this.ownerUuid = ownerUuid;
            this.permLevel = permLevel;
            this.startTick = startTick;
            this.deadlineTick = deadlineTick;
            this.stopCommands = stopCommands;
        }

        public long id() {
            return id;
        }

        @Nullable
        public UUID ownerUuid() {
            return ownerUuid;
        }

        /**
         * 执行停止逻辑。
         *
         * <p>优先执行显式指定的停止命令；没有则用 {@code /player <假人> stop}
         * 让 carpet 假人停下当前动作。都没有就什么都不做。
         */
        void runStop(@NotNull CommandSink sink, @NotNull List<String> messages) {
            if (!stopCommands.isEmpty()) {
                for (String command : stopCommands) {
                    String reason = PermissionGuard.check(command);
                    if (reason != null) {
                        messages.add("§c停止命令被拦截: " + reason);
                        continue;
                    }
                    sink.execute(command, permLevel);
                }
                return;
            }
            String fake = plan.fakePlayer();
            if (fake != null && !fake.isBlank()) {
                String command = "player " + fake + " stop";
                String reason = PermissionGuard.check(command);
                if (reason == null) {
                    sink.execute(command, permLevel);
                }
            }
        }
    }
}
