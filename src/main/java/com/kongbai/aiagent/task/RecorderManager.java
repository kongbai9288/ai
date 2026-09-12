package com.kongbai.aiagent.task;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 录制会话管理器（进程内单例）。
 *
 * <p><b>唯一职责</b>：管理"谁正在录制"。任务存储归 {@link TaskRegistry}，
 * 两者分开是为了让生命周期各自独立 —— 会话是临时的，任务是持久的。
 *
 * <p><b>内存安全（本类的核心风险点）</b>：
 * 每个会话持有一个 {@code List<RecordedAction>}。
 * 玩家录制中途直接退出游戏、或服务器关闭时，若不清理，
 * 这些列表会一直挂在 Map 里无法回收。
 * 因此：
 * <ul>
 *   <li>{@link #stop(UUID)} —— 玩家主动结束/下线时调用</li>
 *   <li>{@link #abortAll()} —— 服务器关闭时调用，清空整个 Map</li>
 * </ul>
 * {@code AiAgentMod} 的 {@code onServerClosed} 里已调用 {@link #abortAll()}。
 *
 * <p><b>并发</b>：用 {@link ConcurrentHashMap}。
 * 玩家命令在主线程执行，采样在服务端 tick 线程执行，
 * 两者可能同时访问同一个会话。
 */
public final class RecorderManager {

    private static final RecorderManager INSTANCE = new RecorderManager();

    /** 玩家 UUID -&gt; 录制会话。只存 UUID，绝不存 ServerPlayer。 */
    private final Map<UUID, TaskRecorder> sessions = new ConcurrentHashMap<>();

    private RecorderManager() {
    }

    @NotNull
    public static RecorderManager getInstance() {
        return INSTANCE;
    }

    // ---------- 生命周期 ----------

    /**
     * 开始录制。
     *
     * @param ownerUuid 玩家 UUID，不可为 {@code null}
     * @param ownerName 玩家名（可空）
     * @param permLevel 权限等级，会被夹到 [0, 4]
     * @param taskName  任务名，需已通过 {@link RecordedTask#isValidName} 校验
     * @param startTick 起始游戏刻
     * @return 新建的会话；该玩家已在录制时返回 {@code null}（不覆盖，避免丢失进行中的录制）
     */
    @Nullable
    public TaskRecorder start(@NotNull UUID ownerUuid, @Nullable String ownerName,
                              int permLevel, @NotNull String taskName, long startTick) {
        if (ownerUuid == null || taskName == null) {
            return null;
        }
        TaskRecorder recorder = new TaskRecorder(ownerUuid, ownerName, permLevel, taskName, startTick);
        TaskRecorder previous = sessions.putIfAbsent(ownerUuid, recorder);
        // putIfAbsent 返回非 null 说明已有会话，保持原样，避免录制数据丢失
        return previous == null ? recorder : null;
    }

    /**
     * 结束录制并生成任务。
     *
     * @return 任务对象；没有进行中的录制、或录制为空时返回 {@code null}
     */
    @Nullable
    public RecordedTask stop(@NotNull UUID ownerUuid) {
        if (ownerUuid == null) {
            return null;
        }
        TaskRecorder recorder = sessions.remove(ownerUuid);
        if (recorder == null) {
            return null;
        }
        return recorder.finish();
    }

    /** 丢弃某玩家的录制（不生成任务）。返回是否确实存在会话。 */
    public boolean cancel(@Nullable UUID ownerUuid) {
        if (ownerUuid == null) {
            return false;
        }
        TaskRecorder recorder = sessions.remove(ownerUuid);
        if (recorder == null) {
            return false;
        }
        recorder.abort();
        return true;
    }

    /** 取某玩家的会话；未在录制时返回 {@code null}。 */
    @Nullable
    public TaskRecorder get(@Nullable UUID ownerUuid) {
        if (ownerUuid == null) {
            return null;
        }
        return sessions.get(ownerUuid);
    }

    @NotNull
    public Optional<TaskRecorder> find(@Nullable UUID ownerUuid) {
        return Optional.ofNullable(get(ownerUuid));
    }

    public boolean isRecording(@Nullable UUID ownerUuid) {
        return get(ownerUuid) != null;
    }

    /**
     * 每刻对所有进行中的会话采样。
     *
     * <p><b>本方法已废弃且为空实现</b>：采样需要玩家坐标，而坐标只能从
     * {@code ServerPlayer} 读取。本类刻意不依赖 Minecraft 类（便于脱离游戏环境测试），
     * 因此采样逻辑实际由 {@code AiAgentMod.AiAgentExtension#sampleRecorders} 承担 ——
     * 它遍历 {@link #activeRecordings()}、取出玩家、再调用
     * {@code TaskRecorder#sample}。
     *
     * <p>保留此方法仅为兼容旧调用点；请不要在这里加逻辑，也不要依赖它做任何事。
     *
     * @deprecated 采样在 {@code AiAgentMod} 的 tick 钩子中完成，此方法不做任何事。
     */
    @Deprecated
    public void tick(long currentTick) {
        // 有意留空，详见方法注释
    }

    /**
     * 清空所有会话（服务器关闭 / 世界卸载时调用）。
     *
     * <p><b>必须调用</b>：这是防止会话列表泄漏的唯一出口。
     */
    public void abortAll() {
        for (TaskRecorder recorder : sessions.values()) {
            if (recorder != null) {
                recorder.abort();
            }
        }
        sessions.clear();
    }

    /** 当前正在录制的玩家 UUID 快照。 */
    @NotNull
    public Set<UUID> activeRecordings() {
        return Set.copyOf(sessions.keySet());
    }

    /** 正在录制的会话数。 */
    public int activeCount() {
        return sessions.size();
    }

    /** 不可修改的会话视图，供列表展示。 */
    @NotNull
    public Collection<TaskRecorder> allSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }
}
