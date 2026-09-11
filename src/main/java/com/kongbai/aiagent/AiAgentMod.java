package com.kongbai.aiagent;

import carpet.CarpetServer;
import carpet.api.CarpetExtension;
import com.kongbai.aiagent.command.AiCommand;
import com.kongbai.aiagent.config.ProfileManager;
import com.kongbai.aiagent.ai.AgentService;
import com.kongbai.aiagent.machine.MachineRegistry;
import com.kongbai.aiagent.task.CommandSink;
import com.kongbai.aiagent.task.RecordedTask;
import com.kongbai.aiagent.task.RecorderManager;
import com.kongbai.aiagent.task.Scheduler;
import com.kongbai.aiagent.task.TaskRecorder;
import com.kongbai.aiagent.task.TaskRegistry;
import com.kongbai.aiagent.task.TaskRunner;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 模组入口：既是 Fabric 的 {@link ModInitializer}，也负责向 Carpet 注册扩展。
 *
 * <p><b>为什么要拆出内部类</b>：Carpet 是 {@code modCompileOnly} 依赖。
 * 若主类直接 {@code implements CarpetExtension}，一旦玩家没装 Carpet，
 * 主类在链接阶段就会失败，Fabric 连 {@code onInitialize} 都跑不到。
 * 拆成内部类后，失败被限制在 {@link #loadExtension()} 的 try 块内，
 * 能给出「请先安装 Carpet」这样明确的提示。
 *
 * <p><b>内存安全（本类最重要的约束）</b>：
 * <ul>
 *   <li><b>不缓存</b> {@link MinecraftServer} —— 服务器实例只在回调参数里出现，用完即走</li>
 *   <li>每刻的 {@link CommandSink} 是<b>临时对象</b>，不被 {@link TaskRunner} 保存
 *       （详见 {@code TaskRunner.start} 的注释）</li>
 *   <li>服务器关闭时统一调用所有管理器的 {@code detach()}/{@code abortAll()}</li>
 * </ul>
 *
 * <p><b>空指针约束</b>：所有生命周期回调的参数都按「可能为 null」处理。
 */
public class AiAgentMod implements ModInitializer {

    public static final String MOD_ID = "ai-agent";

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");

    @Nullable
    private static volatile AiAgentMod instance;

    public AiAgentMod() {
        instance = this;
    }

    /** 取当前实例；Fabric 尚未构造时返回 {@code null}。 */
    @Nullable
    public static AiAgentMod getInstance() {
        return instance;
    }

    @Override
    public void onInitialize() {
        loadExtension();
        LOGGER.info("[假人智能] 初始化完成 (MC 26.2 / Carpet 扩展模式)");
    }

    /** 向 Carpet 注册扩展。失败只记录日志，不阻断游戏启动。 */
    public void loadExtension() {
        try {
            CarpetServer.manageExtension(new AiAgentExtension());
        } catch (Throwable t) {
            LOGGER.error("[假人智能] 注册 Carpet 扩展失败，请确认已安装 Carpet 且版本为 26.2。原因: {}", t.getMessage());
        }
    }

    /**
     * Carpet 扩展实现。
     *
     * <p>只 override 确定存在的方法 —— Carpet 接口方法均有默认实现，
     * 少写不会出错，写错签名会直接编译失败。
     */
    public static final class AiAgentExtension implements CarpetExtension {

        @Override
        public void onGameStarted() {
            LOGGER.info("[假人智能] 游戏已启动，等待世界加载");
        }

        @Override
        public void onServerLoadedWorlds(@Nullable MinecraftServer server) {
            if (server == null) {
                LOGGER.warn("[假人智能] onServerLoadedWorlds 收到 null server，跳过挂载");
                return;
            }
            Path saveDir = resolveSaveDir(server);
            if (saveDir == null) {
                LOGGER.warn("[假人智能] 无法确定存档目录，配置与任务系统未挂载");
                return;
            }
            ProfileManager.getInstance().attach(saveDir);
            TaskRegistry.getInstance().attach(saveDir);
            MachineRegistry.getInstance().attach(saveDir);
            LOGGER.info("[假人智能] 配置/任务/机器系统已挂载: {}", saveDir);
        }

        @Override
        public void onServerClosed(@Nullable MinecraftServer server) {
            // 关键：落盘 + 清空内存表。
            // 缺了这步，换世界会残留上一个世界的数据，且内存随玩家/任务数只增不减
            ProfileManager.getInstance().detach();
            TaskRegistry.getInstance().detach();
            MachineRegistry.getInstance().detach();
            RecorderManager.getInstance().abortAll();
            TaskRunner.getInstance().stopAll();
            Scheduler.getInstance().stopAll();
            AgentService.getInstance().shutdown();
            LOGGER.info("[假人智能] 已卸载并保存全部数据");
        }

        /**
         * 每刻驱动。
         *
         * <p>做两件事：采样录制中的玩家、推进回放队列。
         * 两者都在服务器 tick 线程上执行，因此可以安全访问玩家列表。
         *
         * <p><b>性能</b>：没有录制也没有回放时立刻返回，
         * 避免每刻都构造 CommandSink 或遍历集合。
         */
        @Override
        public void onTick(@Nullable MinecraftServer server) {
            if (server == null) {
                return;
            }
            RecorderManager recorders = RecorderManager.getInstance();
            TaskRunner runner = TaskRunner.getInstance();
            Scheduler scheduler = Scheduler.getInstance();
            boolean needTick = recorders.activeCount() > 0 || runner.hasActive() || scheduler.hasActive();
            if (!needTick) {
                return;
            }
            long tick = server.getTickCount();

            if (recorders.activeCount() > 0) {
                sampleRecorders(server, recorders, tick);
            }
            if (runner.hasActive()) {
                List<String> messages = runner.tick(tick, createSink(server));
                broadcast(server, messages);
            }
            if (scheduler.hasActive()) {
                List<String> messages = scheduler.tick(tick, createSink(server));
                broadcast(server, messages);
            }
        }

        @Override
        public void registerCommands(@NotNull CommandDispatcher<CommandSourceStack> dispatcher,
                                     @NotNull CommandBuildContext context) {
            if (dispatcher == null) {
                LOGGER.error("[假人智能] registerCommands 收到 null dispatcher，跳过");
                return;
            }
            AiCommand.register(dispatcher);
        }

        @Override
        @NotNull
        public String version() {
            return MOD_ID;
        }

        // ---------- 内部逻辑 ----------

        /**
         * 采样所有进行中的录制。
         *
         * <p>顺带处理两种边界：
         * <ul>
         *   <li><b>玩家下线</b> —— {@code getPlayer(uuid)} 返回 null。
         *       此时立即结束录制并<b>保存</b>（而不是丢弃），避免玩家辛苦录的内容丢掉</li>
         *   <li><b>动作数达上限</b> —— 自动结束并保存，防止无限增长</li>
         * </ul>
         */
        private void sampleRecorders(@NotNull MinecraftServer server,
                                     @NotNull RecorderManager recorders, long tick) {
            for (UUID uuid : recorders.activeRecordings()) {
                if (uuid == null) {
                    continue;
                }
                TaskRecorder recorder = recorders.get(uuid);
                if (recorder == null) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player == null) {
                    // 玩家下线：结束并保存，不丢弃
                    RecordedTask task = recorders.stop(uuid);
                    if (task != null) {
                        TaskRegistry.getInstance().put(task);
                        LOGGER.info("[假人智能] 玩家下线，自动保存任务「{}」({} 个动作)",
                                task.name(), task.size());
                    } else {
                        recorders.cancel(uuid);
                    }
                    continue;
                }
                try {
                    recorder.sample(tick, player.getX(), player.getY(), player.getZ(),
                            player.getYRot(), player.getXRot());
                } catch (RuntimeException e) {
                    LOGGER.warn("[假人智能] 采样异常，已终止该录制: {}", e.getMessage());
                    recorders.cancel(uuid);
                    continue;
                }
                if (recorder.isFull()) {
                    RecordedTask task = recorders.stop(uuid);
                    if (task != null) {
                        TaskRegistry.getInstance().put(task);
                        LOGGER.info("[假人智能] 任务「{}」已达 {} 动作上限，自动保存",
                                task.name(), RecordedTask.MAX_ACTIONS);
                    }
                }
            }
        }

        /**
         * 构造命令投递器。
         *
         * <p><b>注意</b>：这个对象只在本刻内使用，不会被 {@link TaskRunner} 保存。
         * 它捕获了 {@code server} 参数，若被长期持有会导致服务器无法回收。
         *
         * <p>权限处理：用 {@code withPermission(permLevel)} 把命令源降权到任务创建者等级
         * 后再派发。即使 {@code PermissionGuard} 的白名单有疏漏，
         * Minecraft 自身的权限校验仍会兜住。
         */
        @NotNull
        private CommandSink createSink(@NotNull MinecraftServer server) {
            return (command, permLevel) -> {
                try {
                    CommandSourceStack source = server.createCommandSourceStack()
                            .withPermission(Math.max(0, Math.min(4, permLevel)));
                    server.getCommands().performPrefixedCommand(source, command);
                    return true;
                } catch (Throwable t) {
                    LOGGER.warn("[假人智能] 命令执行失败 /{} : {}", command, t.getMessage());
                    return false;
                }
            };
        }

        /** 广播回放产生的反馈（例如命令被拦截、任务完成）。 */
        private void broadcast(@NotNull MinecraftServer server, @NotNull List<String> messages) {
            if (messages == null || messages.isEmpty()) {
                return;
            }
            try {
                for (String text : messages) {
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal(text), false);
                }
            } catch (Throwable t) {
                LOGGER.warn("[假人智能] 广播消息失败: {}", t.getMessage());
            }
        }
    }

    /** 解析存档根目录；解析失败返回 {@code null}。 */
    @Nullable
    private static Path resolveSaveDir(@NotNull MinecraftServer server) {
        try {
            return server.getSavePath(LevelResource.ROOT);
        } catch (Throwable t) {
            LOGGER.warn("[假人智能] 获取存档目录失败: {}", t.getMessage());
            return null;
        }
    }
}
