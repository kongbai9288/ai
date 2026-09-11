package com.kongbai.aiagent.machine;

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
 * 机器注册表（进程内单例）。
 *
 * <p>生命周期与 {@code TaskRegistry} 完全一致：
 * {@link #attach(Path)} / {@link #detach()} 必须成对调用，
 * {@code detach()} 负责落盘并<b>清空内存表</b>。
 *
 * <p>机器名大小写不敏感（key 用小写），同名录入覆盖。
 */
public final class MachineRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");
    private static final String FILE_NAME = "aiagent_machines.json";

    private static final MachineRegistry INSTANCE = new MachineRegistry();

    private final Map<String, Machine> machines = new ConcurrentHashMap<>();

    @Nullable
    private volatile Path saveDir;

    private MachineRegistry() {
    }

    @NotNull
    public static MachineRegistry getInstance() {
        return INSTANCE;
    }

    public void attach(@Nullable Path dir) {
        if (dir == null) {
            LOGGER.warn("[ai-agent] MachineRegistry.attach 收到 null 目录，已忽略");
            return;
        }
        detach();
        this.saveDir = dir;
        load();
    }

    public void detach() {
        save();
        machines.clear();
        saveDir = null;
    }

    public boolean isAttached() {
        return saveDir != null;
    }

    public boolean put(@Nullable Machine machine) {
        if (machine == null) {
            return false;
        }
        machines.put(machine.key(), machine);
        return save();
    }

    @Nullable
    public Machine get(@Nullable String name) {
        if (name == null) {
            return null;
        }
        return machines.get(name.trim().toLowerCase(Locale.ROOT));
    }

    @Nullable
    public Machine remove(@Nullable String name) {
        if (name == null) {
            return null;
        }
        Machine removed = machines.remove(name.trim().toLowerCase(Locale.ROOT));
        if (removed != null) {
            save();
        }
        return removed;
    }

    public boolean exists(@Nullable String name) {
        return get(name) != null;
    }

    /** 不可修改的机器快照。 */
    @NotNull
    public Collection<Machine> all() {
        return Collections.unmodifiableCollection(machines.values());
    }

    /** 所有定义了「关闭任务」的机器 —— 一键关停只处理这些。 */
    @NotNull
    public List<Machine> allWithOff() {
        List<Machine> out = new ArrayList<>();
        for (Machine machine : machines.values()) {
            if (machine != null && machine.hasOff()) {
                out.add(machine);
            }
        }
        return out;
    }

    @NotNull
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Machine machine : machines.values()) {
            out.add(machine.name());
        }
        Collections.sort(out);
        return out;
    }

    public int size() {
        return machines.size();
    }

    @NotNull
    private Path configFile() {
        Path dir = saveDir;
        if (dir == null) {
            throw new IllegalStateException("MachineRegistry 尚未 attach");
        }
        return dir.resolve("aiagent").resolve(FILE_NAME);
    }

    public boolean save() {
        if (saveDir == null) {
            return false;
        }
        try {
            JsonArray array = new JsonArray();
            for (Machine machine : machines.values()) {
                array.add(machine.toJson());
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("machines", array);
            return JsonUtil.writeTree(configFile(), root);
        } catch (IllegalStateException e) {
            return false;
        } catch (RuntimeException e) {
            LOGGER.error("[ai-agent] 序列化机器失败: {}", e.getMessage());
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
        JsonElement arrayElement = root.getAsJsonObject().get("machines");
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
            Machine machine = Machine.fromJson(element.getAsJsonObject());
            if (machine == null) {
                skipped++;
                continue;
            }
            machines.put(machine.key(), machine);
            loaded++;
        }
        LOGGER.info("[ai-agent] 已加载 {} 台机器{}", loaded, skipped > 0 ? "（跳过 " + skipped + " 条损坏数据）" : "");
    }
}
