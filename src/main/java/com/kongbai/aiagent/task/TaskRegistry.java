package com.kongbai.aiagent.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kongbai.aiagent.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务仓库（进程内单例）：负责任务的增删改查与持久化。
 *
 * <p><b>生命周期（与 {@code ProfileManager} 同一套约定）</b>：
 * <ol>
 *   <li>{@link #attach(Path)} —— 世界加载时调用</li>
 *   <li>{@link #detach()} —— 世界卸载/服务器关闭时调用，落盘并<b>清空内存表</b></li>
 * </ol>
 * 不调 {@code detach()} 的后果：换世界后旧任务残留，且内存持续增长。
 *
 * <p><b>任务的 key 是小写名称</b>：{@code MyTask} 与 {@code mytask} 视为同一任务，
 * 避免玩家被大小写坑。
 */
public final class TaskRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");
    private static final String FILE_NAME = "aiagent_tasks.json";

    private static final TaskRegistry INSTANCE = new TaskRegistry();

    /** 小写任务名 -&gt; 任务。 */
    private final Map<String, RecordedTask> tasks = new ConcurrentHashMap<>();

    @Nullable
    private volatile Path saveDir;

    private TaskRegistry() {
    }

    @NotNull
    public static TaskRegistry getInstance() {
        return INSTANCE;
    }

    // ---------- 生命周期 ----------

    public void attach(@Nullable Path dir) {
        if (dir == null) {
            LOGGER.warn("[ai-agent] TaskRegistry.attach 收到 null 目录，已忽略");
            return;
        }
        detach();
        this.saveDir = dir;
        load();
    }

    public void detach() {
        save();
        tasks.clear();
        saveDir = null;
    }

    public boolean isAttached() {
        return saveDir != null;
    }

    // ---------- 增删改查 ----------

    /**
     * 保存任务。同名（忽略大小写）会覆盖。
     *
     * @return 成功返回 {@code true}；未 attach 时返回 {@code false}
     */
    public boolean put(@Nullable RecordedTask task) {
        if (task == null) {
            return false;
        }
        tasks.put(task.key(), task);
        return save();
    }

    @Nullable
    public RecordedTask get(@Nullable String name) {
        if (name == null) {
            return null;
        }
        return tasks.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 删除任务。
     *
     * @return 被删除的任务；原本不存在返回 {@code null}
     */
    @Nullable
    public RecordedTask remove(@Nullable String name) {
        if (name == null) {
            return null;
        }
        RecordedTask removed = tasks.remove(name.trim().toLowerCase(Locale.ROOT));
        if (removed != null) {
            save();
        }
        return removed;
    }

    public boolean exists(@Nullable String name) {
        return get(name) != null;
    }

    /** 不可修改的任务快照。 */
    @NotNull
    public Collection<RecordedTask> all() {
        return Collections.unmodifiableCollection(tasks.values());
    }

    /** 任务名列表（原始大小写，已排序）。 */
    @NotNull
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (RecordedTask task : tasks.values()) {
            out.add(task.name());
        }
        Collections.sort(out);
        return out;
    }

    public int size() {
        return tasks.size();
    }

    // ---------- 持久化 ----------

    @NotNull
    private Path configFile() {
        Path dir = saveDir;
        if (dir == null) {
            throw new IllegalStateException("TaskRegistry 尚未 attach");
        }
        return dir.resolve("aiagent").resolve(FILE_NAME);
    }

    public boolean save() {
        if (saveDir == null) {
            return false;
        }
        try {
            JsonArray array = new JsonArray();
            for (RecordedTask task : tasks.values()) {
                array.add(task.toJson());
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("tasks", array);
            return JsonUtil.writeTree(configFile(), root);
        } catch (IllegalStateException e) {
            return false;
        } catch (RuntimeException e) {
            LOGGER.error("[ai-agent] 序列化任务失败: {}", e.getMessage());
            return false;
        }
    }

    public void load() {
        if (saveDir == null) {
            return;
        }
        JsonElement root = JsonUtil.readTree(configFile());
        if (root == null || !root.isJsonObject()) {
            return;
        }
        JsonElement arrayElement = root.getAsJsonObject().get("tasks");
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
            RecordedTask task = RecordedTask.fromJson(element.getAsJsonObject());
            if (task == null) {
                skipped++;
                continue;
            }
            tasks.put(task.key(), task);
            loaded++;
        }
        LOGGER.info("[ai-agent] 已加载 {} 个任务{}", loaded, skipped > 0 ? "（跳过 " + skipped + " 个损坏任务）" : "");
    }
}
