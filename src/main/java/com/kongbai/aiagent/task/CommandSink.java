package com.kongbai.aiagent.task;

import org.jetbrains.annotations.NotNull;

/**
 * 命令投递接口。
 *
 * <p><b>为什么需要它</b>：{@link TaskRunner} 需要在回放时执行命令，
 * 但直接依赖 {@code MinecraftServer} / {@code CommandDispatcher} 会带来两个问题：
 * <ol>
 *   <li>让回放器无法脱离游戏环境做单元测试</li>
 *   <li>容易不小心持有服务器长生命周期引用，造成内存泄漏</li>
 * </ol>
 * 因此抽象成接口，由 {@code AiAgentMod} 侧提供一个"按权限等级执行命令"的实现。
 *
 * <p><b>实现方的义务</b>：
 * <ul>
 *   <li>必须把命令源降权到 {@code permLevel}（见 {@code PermissionGuard.clamp}），不能直接用 OP 权限</li>
 *   <li>命令执行抛异常时应自行捕获，不要让异常冒到回放 tick 中打断游戏刻</li>
 * </ul>
 */
public interface CommandSink {

    /**
     * 以指定权限等级执行一条命令。
     *
     * @param command   命令原文（不含前导 {@code /}），不可为 {@code null}
     * @param permLevel 权限等级 0-4；实现方需将其作为上限，不得超出
     * @return 命令是否成功执行；失败不应抛异常
     */
    boolean execute(@NotNull String command, int permLevel);
}
