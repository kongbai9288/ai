package com.kongbai.aiagent.task;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 方块状态采集器。
 *
 * <p><b>为什么抽象成接口</b>：采集方块状态必须访问 {@code ServerLevel}，
 * 而 {@link TaskRecorder} / {@link TaskRunner} 刻意不依赖 Minecraft 类
 * （否则无法隔离、也容易持有世界引用造成泄漏）。
 * 因此用接口把「读取世界」这件事外包出去，由 {@code AiAgentMod} 提供实现。
 *
 * <p><b>多维度</b>：所有方法都带 {@code dimension} 参数。
 * 机器可能跨维度（主世界按钮控制下界农场），
 * 实现方必须按维度取对应的 {@code ServerLevel}，不能用固定的主世界。
 *
 * <p><b>实现方义务</b>：
 * <ul>
 *   <li>只采集<b>红石相关</b>方块（按钮、拉杆、红石线、中继器、比较器、
 *       活塞、红石灯、红石火把、压力板、侦测器、投掷器、发射器、漏斗等），
 *       以及状态属性中含 powered / lit / open 等关键字的方块。
 *       全量采集会产生海量噪音，反而让对比失去意义</li>
 *   <li>坐标越界、区块未加载、维度不存在时跳过该点，<b>不要抛异常</b></li>
 *   <li>返回的快照必须只含基本类型，绝不返回游戏对象</li>
 * </ul>
 */
public interface BlockProbe {

    /** 默认采集半径（格）。3 格足以覆盖绝大多数机器的控制面板。 */
    int DEFAULT_RADIUS = 3;
    /** 采集半径上限，防止有人填个 100 把服务器卡死。 */
    int MAX_RADIUS = 8;

    /**
     * 采集指定维度内、原点周围半径内的红石相关方块状态。
     *
     * @param originX   原点 X（通常是执行者位置）
     * @param originY   原点 Y
     * @param originZ   原点 Z
     * @param radius    半径，会被夹到 [0, {@link #MAX_RADIUS}]
     * @param dimension 维度 ID，如 {@code minecraft:the_nether}
     * @return 快照列表；未找到红石方块或维度不存在时返回<b>空列表</b>（不是 null）
     */
    @NotNull
    List<BlockSnapshot> collect(double originX, double originY, double originZ,
                                int radius, @NotNull String dimension);

    /**
     * 按模板中记录的位置重新采集当前状态。
     *
     * <p>用于回放时对比：模板里存了录制时各个方块的位置与维度，
     * 这里按<b>每条快照各自的维度</b>把当前状态读出来，
     * 供 {@link BlockSnapshot#matches} 比较。
     *
     * <p><b>为什么按模板的维度而不是统一用一个</b>：
     * 一个任务的多个动作可能分布在不同维度
     * （比如「在主世界按按钮 → 到下界收菜」，虽然罕见但可能）。
     * 逐条取维度才能都读对。
     *
     * @param templates 录制时的快照（提供坐标与维度）
     * @param useRelative 为 true 时用「相对坐标 + 原点」定位（假人模式）；
     *                    false 时用模板里的绝对坐标（机器位置固定，推荐）
     * @return 与模板<b>顺序一致</b>的当前快照；某点读取失败时对应位置为 {@code null}
     */
    @NotNull
    List<BlockSnapshot> recollect(@NotNull List<BlockSnapshot> templates, boolean useRelative);

    /**
     * 判断某个维度当前是否可用（已加载）。
     *
     * <p>用于回放时给出准确提示：维度未加载导致的跳过，
     * 和「机器状态已变」导致的跳过，对玩家意义完全不同。
     */
    boolean isDimensionLoaded(@NotNull String dimension);

    /** 该探测器是否可用（服务器已就绪等）。不可用时调用方应跳过检测，按默认行为处理。 */
    default boolean isAvailable() {
        return true;
    }
}
