package com.kongbai.aiagent.machine;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 机器的运行状态。
 *
 * <p><b>为什么需要状态</b>：录制的「开启任务 / 关闭任务」本质是<b>动作</b>，
 * 不是<b>状态设置</b>。对按钮、拉杆这类 toggle 型机器，
 * 重复执行一次关闭动作等于又切了一次 —— 会把已关闭的机器重新打开。
 * 因此必须记住当前状态，才能在「已经关了」时拒绝执行。
 *
 * <p><b>UNKNOWN 的意义</b>：模组无法感知玩家在游戏里的手动操作
 * （直接右键拉杆、用命令操作假人等）。一旦发生过无法追踪的操作，
 * 或机器刚创建还没执行过任何开关，状态就是 {@link #UNKNOWN}。
 * UNKNOWN 时不假设任何状态，按调用方的安全策略处理。
 */
public enum MachineState {

    /** 已知为开启。 */
    ON,
    /** 已知为关闭。 */
    OFF,
    /**
     * 状态未知（未执行过开关、或发生过模组无法追踪的手动操作）。
     *
     * <p>这是最需要谨慎对待的状态：既不能假设它开着（否则该关的没关），
     * 也不能假设它关着（否则重复关闭会把机器打开）。
     */
    UNKNOWN;

    @NotNull
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 显示用中文名。 */
    @NotNull
    public String display() {
        return switch (this) {
            case ON -> "开启";
            case OFF -> "关闭";
            case UNKNOWN -> "未知";
        };
    }

    /** 显示用颜色码。 */
    @NotNull
    public String color() {
        return switch (this) {
            case ON -> "§a";
            case OFF -> "§7";
            case UNKNOWN -> "§6";
        };
    }

    /**
     * 从持久化 id 还原。
     *
     * @return 无法识别时返回 {@link #UNKNOWN}（不返回 null，避免调用方漏判空）
     */
    @NotNull
    public static MachineState fromId(@Nullable String id) {
        if (id == null) {
            return UNKNOWN;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        // 兼容中文，玩家手改存档时可能写成中文
        return switch (normalized) {
            case "on", "开", "开启" -> ON;
            case "off", "关", "关闭" -> OFF;
            default -> UNKNOWN;
        };
    }
}
