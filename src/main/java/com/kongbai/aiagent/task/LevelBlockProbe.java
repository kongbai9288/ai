package com.kongbai.aiagent.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link BlockProbe} 的 Minecraft 实现：读真实世界的方块状态，<b>支持多维度</b>。
 *
 * <p><b>只采集红石相关方块</b>：全量采集（半径 3 就有 343 个方块）
 * 会混入大量无关噪音（比如旁边的台阶朝向、草的生长阶段），
 * 反而让对比失去意义。这里用「方块 ID 关键词 + 状态属性关键词」双重过滤，
 * 只留下真正能反映机器状态的方块。
 *
 * <p><b>不长期持有世界引用</b>：持有 {@link MinecraftServer}，
 * 但调用方只在 tick 内创建本对象，用完即弃。
 * {@code MinecraftServer} 本身在服务器生命周期内是稳定存在的，
 * 而 {@code ServerLevel} 会随世界切换/卸载变化 ——
 * 所以<b>每次读取都现取 level</b>，绝不缓存，避免卸载后无法回收。
 *
 * <p><b>异常隔离</b>：任何读取失败（区块未加载、坐标越界、维度不存在）
 * 都跳过该点，不抛异常。
 */
public final class LevelBlockProbe implements BlockProbe {

    private final MinecraftServer server;

    public LevelBlockProbe(@NotNull MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isAvailable() {
        return server != null;
    }

    @Override
    public boolean isDimensionLoaded(@NotNull String dimension) {
        return resolveLevel(dimension) != null;
    }

    /**
     * 按维度 ID 取对应的 {@code ServerLevel}。
     *
     * <p><b>为什么用遍历而不是 {@code server.getLevel(key)}</b>：
     * 构造 {@code ResourceKey} 需要 {@code Registries.DIMENSION} 与
     * {@code ResourceLocation}，这两个类在 26.x 的包名/方法名仍有变数。
     * 遍历 {@code getAllLevels()} 只依赖 {@code MinecraftServer} 与
     * {@code ServerLevel} 两个稳定 API，编译风险低得多。
     * 维度数量通常只有个位数，遍历开销可忽略。
     *
     * <p><b>每次调用都重新取</b>，不缓存。维度可能因数据包变化、
     * 世界重载等原因失效，缓存会读到已卸载的世界。
     *
     * @return 维度不存在或未加载时返回 {@code null}
     */
    @Nullable
    private ServerLevel resolveLevel(@NotNull String dimension) {
        if (server == null || dimension == null || dimension.isEmpty()) {
            return null;
        }
        try {
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                // dimension().identifier().toString() 形如 minecraft:the_nether
                String id = level.dimension().identifier().toString();
                if (dimension.equals(id)) {
                    return level;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取当前维度的 ID 字符串。
     *
     * <p>供录制时记录「这条命令是在哪个维度执行的」。
     */
    @NotNull
    public static String dimensionIdOf(@NotNull ServerLevel level) {
        try {
            return level.dimension().identifier().toString();
        } catch (Throwable t) {
            return BlockSnapshot.DEFAULT_DIMENSION;
        }
    }

    @Override
    @NotNull
    public List<BlockSnapshot> collect(double originX, double originY, double originZ,
                                      int radius, @NotNull String dimension) {
        List<BlockSnapshot> out = new ArrayList<>();
        ServerLevel level = resolveLevel(dimension);
        if (level == null) {
            return out;
        }
        int r = Math.max(0, Math.min(MAX_RADIUS, radius));
        int baseX = (int) Math.floor(originX);
        int baseY = (int) Math.floor(originY);
        int baseZ = (int) Math.floor(originZ);

        // 上限保护：即使半径填满，也最多记录 96 个，避免存档膨胀
        final int limit = 96;

        for (int dy = -r; dy <= r && out.size() < limit; dy++) {
            for (int dx = -r; dx <= r && out.size() < limit; dx++) {
                for (int dz = -r; dz <= r && out.size() < limit; dz++) {
                    int x = baseX + dx;
                    int y = baseY + dy;
                    int z = baseZ + dz;
                    BlockSnapshot snapshot = read(level, x, y, z, dx, dy, dz, dimension);
                    if (snapshot != null) {
                        out.add(snapshot);
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    @Override
    @NotNull
    public List<BlockSnapshot> recollect(@NotNull List<BlockSnapshot> templates,
                                         boolean useRelative) {
        List<BlockSnapshot> out = new ArrayList<>(templates.size());
        if (server == null || templates.isEmpty()) {
            return out;
        }
        // 按维度分组取 level，避免同一批快照里每个都解析一次维度
        Map<String, ServerLevel> levelCache = new HashMap<>();

        for (BlockSnapshot template : templates) {
            if (template == null) {
                out.add(null);
                continue;
            }
            String dim = template.dimension();
            ServerLevel level = levelCache.computeIfAbsent(dim, this::resolveLevel);
            if (level == null) {
                // 维度不存在：读不到就放 null，调用方视为「不一致」并给出准确提示
                out.add(null);
                continue;
            }
            int x, y, z;
            if (useRelative) {
                // 相对模式需要原点；当前调用方一律用绝对坐标，
                // 这里保留分支以便将来支持「在不同位置重放同一任务」
                x = template.absX() + template.relX();
                y = template.absY() + template.relY();
                z = template.absZ() + template.relZ();
            } else {
                x = template.absX();
                y = template.absY();
                z = template.absZ();
            }
            out.add(read(level, x, y, z, x, y, z, dim));
        }
        return out;
    }

    /**
     * 读单个方块的快照。
     *
     * @return 非红石相关或读取失败时返回 {@code null}
     */
    @Nullable
    private BlockSnapshot read(@NotNull ServerLevel level, int x, int y, int z,
                               int relX, int relY, int relZ, @NotNull String dimension) {
        try {
            // 世界高度越界直接跳过，避免无谓的区块加载
            if (y < level.getMinY() || y >= level.getMaxY()) {
                return null;
            }
            BlockPos pos = new BlockPos(x, y, z);
            // 区块未加载时不要强制加载（会拖慢服务器），直接跳过
            if (!level.isLoaded(pos)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (state == null || state.isAir()) {
                return null;
            }
            String blockId;
            try {
                blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            } catch (Throwable t) {
                return null;
            }
            String stateKey = extractStateKey(state);
            if (!isRedstoneRelated(blockId, stateKey)) {
                return null;
            }
            int signal = 0;
            try {
                signal = level.getBestNeighborSignal(pos);
            } catch (Throwable ignored) {
                signal = 0;
            }
            return BlockSnapshot.of(relX, relY, relZ, x, y, z, blockId, stateKey, signal, dimension);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 提取状态摘要。
     *
     * <p>只保留红石相关属性，格式 {@code name=value,name=value}（已排序，保证稳定）。
     * 其余属性（朝向、含水等）一律丢弃，避免无关变化干扰判断。
     *
     * <p><b>为什么解析 toString 而不是调 getValues()</b>：
     * {@code BlockState.getValues()} 的返回类型在 26.1 从
     * {@code Map<Property<?>, Comparable<?>>} 变成了 {@code Stream<Value<?>>}，
     * 直接调用在 26.2 上编译不过。而 {@code toString()} 的输出格式
     * （{@code Block{minecraft:stone_button}[powered=true,facing=north]}）
     * 长期稳定，且完全不依赖具体 API 形态 ——
     * 这样同一个实现能同时兼容新旧版本，不会被下一次 API 变动打挂。
     *
     * <p>代价是依赖字符串格式。若 Mojang 哪天改了 toString 格式，
     * 最坏结果是提取不到属性（返回空串），此时 {@link #isRedstoneRelated}
     * 会退化成只看方块 ID 关键词 —— 功能变弱但不会出错。
     */
    @NotNull
    private static String extractStateKey(@NotNull BlockState state) {
        String text;
        try {
            text = state.toString();
        } catch (Throwable t) {
            return "";
        }
        if (text == null || text.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Matcher matcher = PROPERTY_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String name = matcher.group(1);
            if (isRedstonePropertyName(name)) {
                parts.add(name + "=" + matcher.group(2));
            }
        }
        // 排序，保证同样的状态每次生成一致的字符串（遍历顺序不稳定会影响对比）
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    /** 匹配 {@code key=value} 形式的属性。值限定为小写字母/数字/下划线。 */
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("([a-z_]+)=([a-z0-9_]+)");

    /** 状态属性名是否与红石/开关相关。 */
    private static boolean isRedstonePropertyName(@NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("power")
                || lower.contains("lit")
                || lower.equals("open")
                || lower.contains("trigger")
                || lower.contains("invert")
                || lower.contains("mode")
                || lower.contains("signal")
                || lower.contains("attach")
                || lower.contains("face");
    }

    /**
     * 判断方块是否红石相关。
     *
     * <p>双重判定：ID 含关键词，或状态里带红石属性。
     * 后者能覆盖一些自定义模组方块（它们也可能有 powered 属性）。
     */
    private static boolean isRedstoneRelated(@NotNull String blockId, @NotNull String stateKey) {
        if (!stateKey.isEmpty()) {
            return true; // 已经提取到红石属性，必然相关
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        for (String keyword : REDSTONE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 红石相关方块的 ID 关键词。 */
    private static final String[] REDSTONE_KEYWORDS = {
            "button", "lever", "pressure_plate", "redstone", "repeater", "comparator",
            "piston", "lamp", "torch", "observer", "dispenser", "dropper", "hopper",
            "target", "daylight_detector", "note_block", "door", "trapdoor", "gate",
            "rail", "lectern", "bell", "campfire", "furnace", "barrel", "chest",
            "shulker_box", "command_block", "structure_block", "jukebox", "beacon"
    };
}
