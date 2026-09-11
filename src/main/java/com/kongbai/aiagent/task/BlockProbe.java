package com.kongbai.aiagent.task;

import org.jetbrains.annotations.NotNull;

/**
 * 方块状态采集器。
 *
 * <p><b>为什么抽象成接口</b>：采集方块状态必须访问 {@code ServerLevel}，
 * 而 {@link TaskRecorder} / {@link TaskRunner} 刻意不依赖 Minecraft 类
 * （否则无法隔离、也容易持有世界引用造成泄漏）。
 * 因此用接口把「读取世界」这件事外包出去，由 {@code AiAgentMod} 提供实现。
 *
 * <p><b>实现方义务</b>：
 * <ul>
 *   <li>只采集<b>红石相关</b>方块（按钮、拉杆、红石线、中继器、比较器、
 *       活塞、红石灯、红石火把、压力板、侦测器、投掷器、发射器、漏斗等），
 *       以及状态属性中含 powered / lit / open 等关键字的方块。
 *       全量采集会产生海量噪音，反而让对比失去意义</li>
 *   <li>坐标越界、区块未加载时跳过该点，<b>不要抛异常</b></li>
 *   <li>返回的快照必须只含基本类型，绝不返回游戏对象</li>
 * </ul>
 */
public interface BlockProbe {

    /** 默认采集半径（格）。3 格足以覆盖绝大多数机器的控制面板。 */
    int DEFAULT_RADIUS = 3;
    /** 采集半径上限，防止有人填个 100 把服务器卡死。 */
    int MAX_RADIUS = 8;

    /**
     * 采集原点周围半径内的红石相关方块状态。
     *
     * @param originX 原点 X（通常是执行者位置）
     * @param originY 原点 Y
     * @param originZ 原点 Z
     * @param radius  半径，会被夹到 [0, {@link #MAX_RADIUS}]
     * @return 快照列表；未找到红石方块时返回<b>空列表</b>（不是 null）
     */
    @NotNull
    java.util.List<BlockSnapshot> collect(double originX, double originY, double originZ, int radius);

    /**
     * 按模板中记录的位置重新采集当前状态。
     *
     * <p>用于回放时对比：模板里存了录制时各个方块的位置，
     * 这里按同样的位置把<b>当前</b>状态读出来，供 {@link BlockSnapshot#matches} 比较。
     *
     * @param templates   录制时的快照（提供坐标）
     * @param originX     执行者当前 X
     * @param originY     执行者当前 Y
     * @param originZ     执行者当前 Z
     * @param useRelative 为 true 时用「相对坐标 + 原点」定位（假人模式）；
     *                    false 时用模板里的绝对坐标（驱动玩家自己时）
     * @return 与模板<b>顺序一致</b>的当前快照；某点读取失败时对应位置为 {@code null}
     */
    @NotNull
    java.util.List<BlockSnapshot> recollect(@NotNull java.util.List<BlockSnapshot> templates,
                                            double originX, double originY, double originZ,
                                            boolean useRelative);

    /** 该探测器是否可用（世界已加载等）。不可用时调用方应跳过检测，按默认行为处理。 */
    default boolean isAvailable() {
        return true;
    }
}
