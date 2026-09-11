package com.kongbai.aiagent;

import carpet.CarpetServer;
import carpet.api.CarpetExtension;
import com.kongbai.aiagent.command.AiCommand;
import com.kongbai.aiagent.config.ProfileManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 模组入口：既是 Fabric 的 {@link ModInitializer}，也负责向 Carpet 注册扩展。
 *
 * <p><b>为什么要拆出内部类</b>：Carpet 是 {@code modCompileOnly} 依赖。
 * 若主类直接 {@code implements CarpetExtension}，一旦玩家没装 Carpet，
 * 主类在链接阶段就会失败，Fabric 连 {@code onInitialize} 都跑不到，报错信息也会很难懂。
 * 拆成内部类后，失败被限制在 {@link #loadExtension()} 的 try 块内，
 * 能给出「请先安装 Carpet」这样明确的提示。
 *
 * <p><b>内存安全</b>：本类<b>不缓存</b> {@link MinecraftServer}。
 * 服务器实例只在回调参数里出现，用完即走；若长期持有，
 * 服务器关闭后整个 MinecraftServer 对象图（含所有世界数据）都无法被 GC 回收。
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
     * <p>只 override 确定存在的方法，未知方法一律不碰 —— Carpet 接口方法均有默认实现，
     * 少写不会出错，写错签名会直接编译失败。
     */
    public static final class AiAgentExtension implements CarpetExtension {

        @Override
        public void onGameStarted() {
            LOGGER.info("[假人智能] 游戏已启动，等待世界加载");
        }

        /**
         * 世界加载完成后挂载配置系统。
         *
         * <p>选 {@code onServerLoadedWorlds} 而非 {@code onServerLoaded}：
         * 此时世界目录已确定，能拿到正确的存档路径。
         */
        @Override
        public void onServerLoadedWorlds(@Nullable MinecraftServer server) {
            if (server == null) {
                LOGGER.warn("[假人智能] onServerLoadedWorlds 收到 null server，跳过配置挂载");
                return;
            }
            Path saveDir = resolveSaveDir(server);
            if (saveDir == null) {
                LOGGER.warn("[假人智能] 无法确定存档目录，配置系统未挂载");
                return;
            }
            ProfileManager.getInstance().attach(saveDir);
            LOGGER.info("[假人智能] 配置系统已挂载: {}", saveDir);
        }

        @Override
        public void onServerClosed(@Nullable MinecraftServer server) {
            // 关键：落盘 + 清空内存表。缺了这步，换世界会残留上一个世界的配置，且内存持续增长
            ProfileManager.getInstance().detach();
            LOGGER.info("[假人智能] 配置系统已卸载并保存");
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
    }

    /**
     * 解析存档根目录。
     *
     * @return 存档路径；解析失败返回 {@code null}
     */
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
