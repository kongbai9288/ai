package com.kongbai.aiagent.mixin;

import com.kongbai.aiagent.task.RecorderManager;
import com.kongbai.aiagent.task.TaskRecorder;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 拦截玩家执行的命令，用于录制。
 *
 * <p><b>切入点</b>：{@code Commands.performCommand(ParseResults, String)} ——
 * 所有服务端命令（玩家输入、命令方块、函数）最终都会经过这里。
 * 选它而不是 {@code performPrefixedCommand}，是因为后者只覆盖带 {@code /} 前缀的输入，
 * 会漏掉命令方块与函数触发的命令。
 *
 * <p><b>为什么 require=0</b>：MC 26.2 的命令执行签名可能与我按 1.21.11 写的不同。
 * 设成 0 后，若方法匹配不上，mixin 会被静默跳过而不是让模组崩溃 ——
 * 代价是"指令录制"功能失效，但位置/视角录制与回放仍然可用。
 * 这是有意的降级设计：宁可少个功能，也不能让玩家进不去世界。
 *
 * <p><b>不持有引用</b>：本类无字段，不缓存任何对象。
 * 每次注入只读取当前上下文并立即上报，用完即走。
 *
 * <p><b>异常隔离</b>：整个方法体包在 try-catch 里。
 * 命令执行路径上的任何异常都会让玩家"输入命令没反应"，
 * 绝不能让录制逻辑的错误影响到原版命令执行。
 */
@Mixin(Commands.class)
public class CommandsMixin {

    @Inject(method = "performCommand", at = @At("HEAD"), require = 0)
    private void aiagent$onPerformCommand(ParseResults<CommandSourceStack> parseResults,
                                          String command, CallbackInfo ci) {
        try {
            if (parseResults == null || command == null) {
                return;
            }
            RecorderManager manager = RecorderManager.getInstance();
            if (manager.activeCount() == 0) {
                return; // 没有人在录制，快速返回，避免每帧做无用功
            }

            CommandSourceStack source = parseResults.getContext().getSource();
            if (source == null) {
                return;
            }
            // 只对真人玩家录制的会话生效；命令方块/函数执行者没有 UUID，直接跳过
            if (source.getPlayer() == null) {
                return;
            }
            UUID uuid = source.getPlayer().getUUID();
            TaskRecorder recorder = manager.get(uuid);
            if (recorder == null) {
                return;
            }

            MinecraftServer server = source.getServer();
            long tick = server == null ? 0L : server.getTickCount();
            // 去掉前导 "/"，保持与玩家输入一致的外观
            String normalized = command.startsWith("/") ? command.substring(1) : command;
            recorder.recordCommand(tick, normalized);
        } catch (Throwable t) {
            // 吞掉一切异常：录制逻辑绝不能阻断原版命令执行
        }
    }
}
