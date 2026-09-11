package com.kongbai.aiagent.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家 AI 配置管理器（进程内单例）。
 *
 * <p><b>生命周期（关键，防止跨世界串数据与内存泄露）</b>：
 * <ol>
 *   <li>{@link #attach(Path)} —— 世界加载时调用：记录存档目录并加载该世界的配置</li>
 *   <li>{@link #detach()} —— 世界卸载/服务器关闭时调用：落盘并<b>清空内存表</b></li>
 * </ol>
 * 若不调用 {@code detach()}，换世界后旧世界的配置会残留在内存里，
 * 且随玩家数量增长持续占用堆空间。
 *
 * <p><b>线程安全</b>：底层使用 {@link ConcurrentHashMap}，
 * 配置对象 {@link AiProfile} 不可变，因此读取端无需加锁。
 *
 * <p><b>空指针约束</b>：{@code attach} 前调用 {@link #get(UUID)} 返回 {@code null}；
 * 这是「尚未就绪」的合法状态，调用方需自行判断（命令层会提示玩家稍后再试）。
 */
public final class ProfileManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");
    private static final String FILE_NAME = "aiagent_profiles.json";

    private static final ProfileManager INSTANCE = new ProfileManager();

    /** 玩家 UUID -&gt; 配置。只存 UUID，绝不存 ServerPlayer 引用。 */
    private final Map<UUID, AiProfile> profiles = new ConcurrentHashMap<>();

    /**
     * 当前存档目录。{@code null} 表示尚未 attach（未加载世界）。
     * 使用 volatile 保证「主线程 attach、异步线程读取」之间的可见性。
     */
    @Nullable
    private volatile Path saveDir;

    private ProfileManager() {
    }

    @NotNull
    public static ProfileManager getInstance() {
        return INSTANCE;
    }

    // ---------- 生命周期 ----------

    /**
     * 绑定到某个存档目录并加载配置。重复调用会先 {@link #detach()} 再加载，
     * 因此切换世界时直接调用本方法即可，不会残留上一个世界的数据。
     *
     * @param dir 存档根目录；为 {@code null} 时不执行任何操作（防御）
     */
    public void attach(@Nullable Path dir) {
        if (dir == null) {
            LOGGER.warn("[ai-agent] attach 收到 null 存档目录，已忽略");
            return;
        }
        detach();
        this.saveDir = dir;
        load();
    }

    /**
     * 落盘并清空内存表。
     *
     * <p>这是唯一会清空 {@link #profiles} 的入口 —— 必须在世界卸载时调用，
     * 否则玩家配置对象会随玩家数持续增长而无法回收。
     */
    public void detach() {
        save();
        profiles.clear();
        saveDir = null;
    }

    /** 当前是否已绑定存档目录。 */
    public boolean isAttached() {
        return saveDir != null;
    }

    // ---------- 读写 ----------

    /**
     * 取某玩家配置。
     *
     * @param uuid 玩家 UUID，允许为 {@code null}（直接返回 {@code null}）
     * @return 已配置的实例；未配置或管理器未就绪时返回 {@code null}
     */
    @Nullable
    public AiProfile get(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return profiles.get(uuid);
    }

    @NotNull
    public Optional<AiProfile> find(@Nullable UUID uuid) {
        return Optional.ofNullable(get(uuid));
    }

    /**
     * 保存某玩家配置。
     *
     * @param profile 配置，不可为 {@code null}
     * @return 写入成功返回 {@code true}；管理器未就绪时返回 {@code false}
     */
    public boolean put(@NotNull AiProfile profile) {
        if (profile == null) {
            return false;
        }
        profiles.put(profile.ownerUuid(), profile);
        return save();
    }

    /**
     * 删除某玩家配置。
     *
     * @return 被删除的配置；原本就没有则返回 {@code null}
     */
    @Nullable
    public AiProfile remove(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }
        AiProfile removed = profiles.remove(uuid);
        if (removed != null) {
            save();
        }
        return removed;
    }

    /** 返回不可修改的快照，供列表展示。调用方无法借此修改内部表。 */
    @NotNull
    public Collection<AiProfile> all() {
        return Collections.unmodifiableCollection(profiles.values());
    }

    public int size() {
        return profiles.size();
    }

    // ---------- 持久化 ----------

    @NotNull
    private Path configFile() {
        Path dir = saveDir;
        if (dir == null) {
            // 不应发生：所有调用点都已判空。抛出以便尽早暴露调用顺序 bug。
            throw new IllegalStateException("ProfileManager 尚未 attach");
        }
        return dir.resolve("aiagent").resolve(FILE_NAME);
    }

    /** 落盘。未 attach 或写入失败时静默返回 false（已记日志）。 */
    public boolean save() {
        if (saveDir == null) {
            return false;
        }
        try {
            JsonArray array = new JsonArray();
            for (AiProfile profile : profiles.values()) {
                array.add(profile.toJson());
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("profiles", array);
            return JsonUtil.writeTree(configFile(), root);
        } catch (IllegalStateException e) {
            return false;
        } catch (RuntimeException e) {
            LOGGER.error("[ai-agent] 序列化配置失败: {}", e.getMessage());
            return false;
        }
    }

    /** 从磁盘加载。文件不存在属正常首次启动，不算错误。 */
    public void load() {
        if (saveDir == null) {
            return;
        }
        JsonElement root = JsonUtil.readTree(configFile());
        if (root == null || !root.isJsonObject()) {
            return;
        }
        JsonElement arrayElement = root.getAsJsonObject().get("profiles");
        if (arrayElement == null || !arrayElement.isJsonArray()) {
            return;
        }

        int loaded = 0;
        int skipped = 0;
        for (JsonElement element : arrayElement.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                skipped++;
                continue;
            }
            AiProfile profile = AiProfile.fromJson(element.getAsJsonObject());
            if (profile == null) {
                skipped++;
                continue;
            }
            profiles.put(profile.ownerUuid(), profile);
            loaded++;
        }
        LOGGER.info("[ai-agent] 已加载 {} 条 AI 配置{}", loaded, skipped > 0 ? "（跳过 " + skipped + " 条损坏数据）" : "");
    }
}
