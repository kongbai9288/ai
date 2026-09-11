package com.kongbai.aiagent.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link BlockProbe} 的 Minecraft 实现：读真实世界的方块状态。
 *
 * <p><b>只采集红石相关方块</b>：全量采集（半径 3 就有 343 个方块）
 * 会混入大量无关噪音（比如旁边的台阶朝向、草的生长阶段），
 * 反而让对比失去意义。这里用「方块 ID 关键词 + 状态属性关键词」双重过滤，
 * 只留下真正能反映机器状态的方块。
 *
 * <p><b>不持有世界引用</b>：{@code level} 是构造参数，
 * 由调用方在 tick 内传入；本类不缓存、不静态持有。
 * 调用方用完即弃，避免世界卸载后无法回收。
 *
 * <p><b>异常隔离</b>：任何读取失败（区块未加载、坐标越界）都跳过该点，不抛异常。
 */
public final class LevelBlockProbe implements BlockProbe {

    private final ServerLevel level;

    public LevelBlockProbe(@NotNull ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean isAvailable() {
        return level != null;
    }

    @Override
    @NotNull
    public List<BlockSnapshot> collect(double originX, double originY, double originZ, int radius) {
        List<BlockSnapshot> out = new ArrayList<>();
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
                    BlockSnapshot snapshot = read(x, y, z, dx, dy, dz);
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
                                         double originX, double originY, double originZ,
                                         boolean useRelative) {
        List<BlockSnapshot> out = new ArrayList<>(templates.size());
        if (level == null || templates.isEmpty()) {
            return out;
        }
        int baseX = (int) Math.floor(originX);
        int baseY = (int) Math.floor(originY);
        int baseZ = (int) Math.floor(originZ);

        for (BlockSnapshot template : templates) {
            if (template == null) {
                out.add(null);
                continue;
            }
            int x, y, z;
            if (useRelative) {
                x = baseX + template.relX();
                y = baseY + template.relY();
                z = baseZ + template.relZ();
            } else {
                x = template.absX();
                y = template.absY();
                z = template.absZ();
            }
            // 读失败时放 null，调用方视为「不一致」
            out.add(read(x, y, z, x - baseX, y - baseY, z - baseZ));
        }
        return out;
    }

    /**
     * 读单个方块的快照。
     *
     * @return 非红石相关或读取失败时返回 {@code null}
     */
    @Nullable
    private BlockSnapshot read(int x, int y, int z, int relX, int relY, int relZ) {
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
            return BlockSnapshot.of(relX, relY, relZ, x, y, z, blockId, stateKey, signal);
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
        java.util.regex.Matcher matcher = PROPERTY_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String name = matcher.group(1);
            if (isRedstonePropertyName(name)) {
                parts.add(name + "=" + matcher.group(2));
            }
        }
        // 排序，保证同样的状态每次生成一致的字符串（Set/Map 遍历顺序不稳定）
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    /** 匹配 {@code key=value} 形式的属性。值限定为小写字母/数字/下划线。 */
    private static final java.util.regex.Pattern PROPERTY_PATTERN =
            java.util.regex.Pattern.compile("([a-z_]+)=([a-z0-9_]+)");

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
