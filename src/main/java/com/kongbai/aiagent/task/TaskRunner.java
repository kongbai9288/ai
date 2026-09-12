package com.kongbai.aiagent.task;

import com.kongbai.aiagent.machine.FakePlayerNaming;
import com.kongbai.aiagent.util.Auditor;
import com.kongbai.aiagent.util.PermissionGuard;
import net.minecraft.commands.CommandSourceStack;
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
    /**
     * 召唤后等待的游戏刻数。
     *
     * <p><b>为什么必须等</b>：Carpet 的假人创建是<b>异步</b>的 ——
     * 它要先去 Mojang 解析 GameProfile（查皮肤/UUID），
     * 官方文档明确写了 "Profile resolution is async; name is marked as spawning during fetch"。
     * 在这段窗口内假人还不存在，任何 {@code /player X use} 都会失败。
     * 实测需要几刻才能就绪，这里留 5 刻余量。
     */
    public static final long SPAWN_WARMUP_TICKS = 5L;

    /**
     * 保活 / 复活检测间隔（刻）。
     *
     * <p><b>为什么需要</b>：Carpet 原生的假人死亡后会直接掉线（不是重生），
     * 此后的所有命令都会「目标不存在」而静默失败 —— 回放看起来在跑，实则空转。
     * 定期重发一次 spawn 即可：假人还在就复用（Carpet 行为），不在就重新召唤。
     */
    public static final long KEEPALIVE_CHECK_TICKS = 100L;

    /**
     * 幻翼周期：{@code time_since_rest} 达到 72000 刻（3 游戏日）才可能生成幻翼。
     *
     * <p>「重生洗白」的间隔取 60000 刻（< 72000），留出余量，
     * 确保假人的计时永远够不到阈值。
     */
    public static final long PHANTOM_PERIOD_TICKS = 60000L;


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
        return start(task, fakeName, startTick, false, Integer.MAX_VALUE);
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
        // 未指定执行者权限：退化为「与录制者同级」（旧行为），调用方应尽量用下面的重载
        return start(task, fakeName, startTick, force, Integer.MAX_VALUE);
    }

    /**
     * 启动一个回放（推荐：显式传入执行者权限等级）。
     *
     * <p><b>为什么需要执行者权限</b>：回放执行的命令原本一律用
     * {@code task.permLevel()}（<b>录制者</b>的权限）。这在单人场景没问题，
     * 但多人服务器上是直接的提权路径 ——
     * 只要 OP 录过某个任务，任何普通玩家 {@code /carpet ai run <那个任务>}
     * 就能以等级 4 执行其中的命令（{@code give} / {@code tp} / {@code gamemode} 等
     * 都在白名单内）。机器开关同理：低权限玩家定义机器引用高权限者的任务即可借权。
     *
     * <p>因此实际生效等级取 {@code min(录制者, 执行者)}：
     * 既保留「任务本身需要一定权限」的语义，又保证执行者无法借此抬高自己的权限。
     *
     * @param executorPermLevel 执行者的权限等级（0-4）；传负数或超大值会被夹到合法区间
     */
    @Nullable
    public Long start(@Nullable RecordedTask task, @Nullable String fakeName,
                      long startTick, boolean force, int executorPermLevel) {
        return start(task, fakeName, startTick, force, executorPermLevel, null);
    }

    /**
     * 启动一个回放（推荐入口：显式传入执行者的命令源）。
     *
     * @param source 执行者的命令源；为 {@code null} 时退化为「与录制者同级、不驱动具体玩家」
     */
    @Nullable
    public Long start(@Nullable RecordedTask task, @Nullable String fakeName,
                      long startTick, boolean force, @Nullable CommandSourceStack source) {
        if (source == null) {
            return start(task, fakeName, startTick, force, Integer.MAX_VALUE, null);
        }
        String executorName = resolveExecutorName(source);
        return start(task, fakeName, startTick, force,
                PermissionGuard.levelOf(source), executorName);
    }

    @Nullable
    private Long start(@Nullable RecordedTask task, @Nullable String fakeName,
                       long startTick, boolean force, int executorPermLevel,
                       @Nullable String executorName) {
        if (task == null) {
            return null;
        }
        if (task.isEmpty()) {
            return null;
        }
        if (playbacks.size() >= MAX_CONCURRENT) {
            return null;
        }
        int executor = Math.max(0, Math.min(4, executorPermLevel));
        int effective = Math.min(task.permLevel(), executor);
        long id = nextId.getAndIncrement();
        playbacks.put(id, new Playback(id, task, normalizeFakeName(fakeName),
                startTick, force, effective, executorName));
        return id;
    }

    /**
     * 取执行者名字，用于「不指定假人时驱动执行者自己」的移动/视角命令。
     *
     * <p><b>为什么不能继续用 {@code @s}</b>：命令投递器用的是
     * {@code server.createCommandSourceStack()}，那是个<b>没有实体</b>的服务器级 source。
     * {@code @s} 需要 source 上有实体才能解析，因此
     * {@code tp @s x y z} 在任何情况下都会失败 ——
     * 「不填假人就驱动执行者自己」这条路其实从未真正工作过。
     * 改为直接写执行者的名字，语义正确且可回退。
     */
    @Nullable
    private static String resolveExecutorName(@NotNull CommandSourceStack source) {
        try {
            if (source.getPlayer() != null) {
                return source.getPlayer().getGameProfile().name();
            }
            String text = source.getTextName();
            return text == null || text.isBlank() ? null : text;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 规范化假人名：加专属前缀 + 清洗非法字符。
     *
     * <p>走 {@link FakePlayerNaming#normalize}，确保：
     * <ul>
     *   <li>一定带 {@code ai_} 前缀，不会操作服务器里别人的假人</li>
     *   <li>幂等 —— 同一输入永远得到同一名字，从而命中已召唤的假人实现复用</li>
     * </ul>
     */
    @Nullable
    public static String normalizeFakeName(@Nullable String name) {
        return FakePlayerNaming.normalize(name);
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
        /**
         * 实际生效的权限等级 = min(录制者, 执行者)。
         *
         * <p>直接用 {@code task.permLevel()} 会让任何执行者都能借录制者的权限，
         * 见 {@code start(...)} 的说明。
         */
        private final int effectivePermLevel;
        /**
         * 执行者名字。未指定假人时用它做移动/视角的目标；
         * 为 {@code null} 时移动/视角命令无法定位目标，只能跳过。
         */
        @Nullable
        private final String executorName;
        private int cursor;
        /** 本回放发送过"检测中"提示，避免每个动作都刷屏。 */
        private boolean probeNotified;
        /** 是否已发出过召唤命令。 */
        private boolean spawned;
        /** spawn 就绪前的等待截止刻（见 {@link #SPAWN_WARMUP_TICKS}）。 */
        private long warmupUntilTick = -1L;
        /** 上次保活/复活检测的刻。 */
        private long lastKeepaliveTick = -1L;
        /** 是否在等待 spawn 就绪后再补发保护效果。 */
        private boolean pendingProtection;

        /** 假人就绪流程的当前阶段。 */
        private Phase phase = Phase.NEED_SPAWN;
        /**
         * 上次「重生洗白」（真死一次重置幻翼计时）的刻。
         * 为 -1 表示还没做过。
         */
        private long lastWashTick = -1L;
        /** 安全出生点（取自任务的第一个移动动作）。 */
        private final double spawnX;
        private final double spawnY;
        private final double spawnZ;
        private final boolean hasSpawnPos;

        Playback(long id, @NotNull RecordedTask task, @Nullable String fakeName,
                 long startTick, boolean force, int effectivePermLevel,
                 @Nullable String executorName) {
            this.id = id;
            this.task = task;
            this.fakeName = fakeName;
            this.startTick = startTick;
            this.force = force;
            this.effectivePermLevel = Math.max(0, Math.min(4, effectivePermLevel));
            this.executorName = executorName;
            double[] pos = firstMovePosition(task);
            if (pos != null) {
                this.spawnX = pos[0];
                this.spawnY = pos[1];
                this.spawnZ = pos[2];
                this.hasSpawnPos = true;
            } else {
                this.spawnX = this.spawnY = this.spawnZ = 0.0;
                this.hasSpawnPos = false;
            }
        }

        /**
         * 取任务里第一个移动动作的坐标，作为假人的安全出生点。
         *
         * <p><b>为什么用它而不是世界出生点</b>：见 {@code FakePlayerNaming#spawnAtCommand} ——
         * 出生点可能被改造过（基岩挖穿 / 填岩浆 / 封死），
         * 用录制时的实际坐标最贴近任务场景，也最安全。
         */
        @Nullable
        private static double[] firstMovePosition(@NotNull RecordedTask task) {
            for (RecordedAction action : task.actions()) {
                if (action != null && action.type() == RecordedAction.Type.MOVE) {
                    return new double[]{action.x(), action.y(), action.z()};
                }
            }
            return null;
        }

        /** 假人就绪流程的阶段。 */
        private enum Phase {
            /** 需要召唤。 */
            NEED_SPAWN,
            /** 已发 spawn，等待 Carpet 异步创建完成。 */
            WARMUP,
            /** 已就绪，需要「真死一次」重置幻翼计时。 */
            NEED_WASH,
            /** 已发 /kill，等待死亡处理完成。 */
            WASHING,
            /** 全部就绪，可以开始执行动作。 */
            READY
        }

        /**
         * 当前是否需要「重生洗白」（真死一次重置幻翼计时）。
         *
         * <p>只在 {@link com.kongbai.aiagent.util.PermissionPolicy.PhantomMode#RESPAWN}
         * 模式下生效。条件：从未洗过，或距上次洗白已超过一个幻翼周期
         * （{@link #PHANTOM_PERIOD_TICKS}，3 游戏日）。
         *
         * <p><b>为什么是 3 游戏日</b>：幻翼要求 {@code time_since_rest >= 72000} 刻才生成，
         * 所以只要在 72000 刻内洗一次，就永远不会达到阈值。
         */
        private boolean shouldWash(long currentTick) {
            if (!com.kongbai.aiagent.util.PermissionPolicy.getInstance()
                    .phantomMode().equals(
                            com.kongbai.aiagent.util.PermissionPolicy.PhantomMode.RESPAWN)) {
                return false;
            }
            if (lastWashTick < 0) {
                return true;
            }
            return currentTick - lastWashTick >= PHANTOM_PERIOD_TICKS;
        }

        /**
         * 生成召唤命令。
         *
         * <p>优先用安全坐标（避免落在被破坏的世界出生点）；
         * 任务里没有移动动作时才退回无坐标版本。
         */
        @NotNull
        private String spawnCommand() {
            if (hasSpawnPos) {
                return FakePlayerNaming.spawnAtCommand(fakeName, spawnX, spawnY, spawnZ);
            }
            return FakePlayerNaming.spawnCommand(fakeName);
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
            // ── 假人保障流程 ──
            // 目标：确保回放期间「假人一直存在、且站在正确的位置、且活着」。
            // 缺任何一环，后续命令都会静默失败，回放看着在跑实则空转。
            if (fakeName != null) {
                switch (phase) {
                    case NEED_SPAWN -> {
                        // 首次召唤：用安全坐标（避开可能被破坏的世界出生点）
                        spawned = true;
                        lastKeepaliveTick = currentTick;
                        sink.execute(spawnCommand(), effectivePermLevel);
                        warmupUntilTick = currentTick + SPAWN_WARMUP_TICKS;
                        phase = Phase.WARMUP;
                        return;
                    }
                    case WARMUP -> {
                        if (currentTick < warmupUntilTick) {
                            return; // Carpet 的假人创建是异步的，还没就绪
                        }
                        // 判断是否需要「重生洗白」重置幻翼计时
                        if (shouldWash(currentTick)) {
                            phase = Phase.NEED_WASH;
                        } else {
                            phase = Phase.READY;
                        }
                        return; // 下一刻再进入新阶段，保持每刻只推进一步
                    }
                    case NEED_WASH -> {
                        // 真死一次：重置 time_since_rest，让幻翼 3 天内不再针对它。
                        // 必须用原版 /kill —— /player X kill 是「登出」，不算死亡。
                        lastWashTick = currentTick;
                        sink.execute(FakePlayerNaming.killForResetCommand(fakeName),
                                effectivePermLevel);
                        warmupUntilTick = currentTick + SPAWN_WARMUP_TICKS;
                        phase = Phase.WASHING;
                        return;
                    }
                    case WASHING -> {
                        if (currentTick < warmupUntilTick) {
                            return;
                        }
                        // 假人死亡后 Carpet 会让它掉线，重新召唤复活
                        sink.execute(spawnCommand(), effectivePermLevel);
                        warmupUntilTick = currentTick + SPAWN_WARMUP_TICKS;
                        pendingProtection = true;
                        phase = Phase.WARMUP;
                        return;
                    }
                    case READY -> {
                        if (pendingProtection) {
                            // 补发保护（抗性/饱和/防火），防怪物与摔落干扰
                            pendingProtection = false;
                            for (String cmd : FakePlayerNaming.protectionCommands(fakeName)) {
                                sink.execute(cmd, effectivePermLevel);
                            }
                            // 保护生效后再精确放到出生点（重生动量可能让它偏移）
                            if (hasSpawnPos) {
                                sink.execute(FakePlayerNaming.teleportCommand(
                                        fakeName, spawnX, spawnY, spawnZ), effectivePermLevel);
                            }
                        }
                        if (currentTick - lastKeepaliveTick >= KEEPALIVE_CHECK_TICKS) {
                            // 定期保活：假人可能被怪物打死/掉虚空，Carpet 原生会直接掉线。
                            // 重发 spawn —— 还在就复用，不在就复活。
                            lastKeepaliveTick = currentTick;
                            sink.execute(spawnCommand(), effectivePermLevel);
                            warmupUntilTick = currentTick + SPAWN_WARMUP_TICKS;
                            pendingProtection = true;
                            phase = Phase.WARMUP;
                            return;
                        }
                        // 幻翼计时会随时间重新累积，到 3 天后再洗一次
                        if (shouldWash(currentTick)) {
                            phase = Phase.NEED_WASH;
                            return;
                        }
                    }
                }
                // 注意：这里刻意【不做】「每刻 tp 回出生点」来抗幻翼击退 ——
                // 那会把假人钉死在出生点，直接摧毁回放轨迹。
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
                execute(currentTick, action, sink, probe, messages);
                cursor++;
            }
        }

        private void execute(long currentTick, @NotNull RecordedAction action,
                             @NotNull CommandSink sink,
                             @Nullable BlockProbe probe, @NotNull List<String> messages) {
            switch (action.type()) {
                case MOVE -> {
                    String target = fakeName == null ? executorName : fakeName;
                    if (target == null) {
                        break; // 既无假人也无执行者名字，无法定位目标
                    }
                    // 原版 /tp 传送（Carpet 的 /player 没有 tp 子命令，见 FakePlayerNaming）
                    String cmd = String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f",
                            target, action.x(), action.y(), action.z());
                    dispatch(currentTick, cmd, "移动", sink, messages);
                }
                case LOOK -> {
                    String target = fakeName == null ? executorName : fakeName;
                    if (target == null) {
                        break;
                    }
                    String cmd = fakeName == null
                            ? String.format(Locale.ROOT, "tp %s %.1f %.1f", target,
                            action.yaw(), action.pitch())
                            : String.format(Locale.ROOT, "player %s look %.1f %.1f",
                            fakeName, action.yaw(), action.pitch());
                    dispatch(currentTick, cmd, "视角", sink, messages);
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
                        Auditor.getInstance().record(currentTick, null, effectivePermLevel, raw,
                                Auditor.Result.BLOCKED, Auditor.Source.PLAYBACK, reason);
                        return;
                    }
                    // 方块状态检测：判断这活儿是不是已经干过了
                    if (!shouldExecuteByState(action, probe, messages)) {
                        return;
                    }
                    dispatch(currentTick, raw, "命令", sink, messages);
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
                // 用绝对坐标定位：机器在世界里的位置是固定的，不随执行者移动。
                // 维度按每条快照自己记录的来，因此跨维度机器也能读对。
                current = probe.recollect(templates, false);
            } catch (RuntimeException e) {
                return true; // 读取异常不阻断，按原行为执行
            }

            // 维度未加载：这与"机器状态变了"是两回事，要分开提示
            for (BlockSnapshot template : templates) {
                if (template != null && !probe.isDimensionLoaded(template.dimension())) {
                    messages.add("§6[状态检测] 跳过：维度 " + template.shortDimension()
                            + " 当前未加载，无法核对现场");
                    messages.add("§8  加载该维度后重试，或用 §f/carpet ai run "
                            + task.name() + " force §8强制执行");
                    return false;
                }
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
        private void dispatch(long currentTick, @NotNull String command, @NotNull String label,
                              @NotNull CommandSink sink, @NotNull List<String> messages) {
            String reason = PermissionGuard.check(command);
            if (reason != null) {
                messages.add("§c" + label + "被拦截：" + reason);
                Auditor.getInstance().record(currentTick, null, effectivePermLevel, command,
                        Auditor.Result.BLOCKED, Auditor.Source.PLAYBACK, reason);
                return;
            }
            boolean ok = sink.execute(command, effectivePermLevel);
            if (!ok) {
                messages.add("§c" + label + "执行失败: /" + command);
            }
        }
    }
}
