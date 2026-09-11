package com.kongbai.aiagent.command;

import com.kongbai.aiagent.config.AiProfile;
import com.kongbai.aiagent.config.ProfileManager;
import com.kongbai.aiagent.util.PermissionGuard;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * {@code /carpet ai ...} 命令注册与实现。
 *
 * <p><b>为什么挂在 carpet 节点下而不是独立注册</b>：
 * Carpet 已注册了 {@code /carpet} 根节点。若直接用 {@code dispatcher.register(literal("carpet")...)}
 * 会<b>覆盖</b> Carpet 自身的节点，导致 {@code /carpet setDefault} 等原生功能全部失效。
 * 因此这里先取出已有节点，再 {@code addChild} 追加子节点 —— 只加不改。
 *
 * <p><b>M1 范围</b>：仅实现 {@code /carpet ai api}（个人 AI 接入配置）与 {@code /carpet ai perm}（权限自省）。
 * 任务录制、对话执行等属于后续模块，不在此文件。
 *
 * <p><b>空指针约束</b>：
 * <ul>
 *   <li>{@link #register(CommandDispatcher)} 中若 {@code /carpet} 节点不存在则跳过注册并记日志，
 *       不抛异常 —— 缺少 Carpet 时本模组本就无法工作，静默降级优于崩溃</li>
 *   <li>所有命令回调里 {@code source.getPlayer()} 可能为 {@code null}（命令方块/控制台执行），
 *       均走 {@link #requirePlayer} 统一拦截</li>
 * </ul>
 */
public final class AiCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-agent");

    /** 查看他人配置所需的最低权限等级（2 = 可被 /op 的普通管理）。 */
    private static final int PERM_VIEW_OTHERS = 2;

    private AiCommand() {
    }

    /**
     * 把 {@code ai} 子命令树追加到 {@code /carpet} 下。
     *
     * @param dispatcher Carpet 传入的命令分发器，不可为 {@code null}
     */
    public static void register(@NotNull CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher == null) {
            LOGGER.error("[ai-agent] register 收到 null dispatcher，跳过命令注册");
            return;
        }

        LiteralArgumentBuilder<CommandSourceStack> aiNode = Commands.literal("ai")
                .requires(source -> source.hasPermission(0))
                .executes(ctx -> showHelp(ctx.getSource()))
                .then(buildApiNode())
                .then(Commands.literal("perm")
                        .executes(ctx -> showPermissions(ctx.getSource())));

        CommandNode<CommandSourceStack> carpetNode = dispatcher.getRoot().getChild("carpet");
        if (carpetNode == null) {
            LOGGER.error("[ai-agent] 未找到 /carpet 节点，命令注册失败（Carpet 是否已安装？）");
            return;
        }
        // 幂等：重复注册（例如 /reload）时先移除旧节点，避免 addChild 覆盖后丢失分支
        CommandNode<CommandSourceStack> existing = carpetNode.getChild("ai");
        if (existing != null) {
            carpetNode.getChildren().removeIf(node -> node.getName().equals("ai"));
        }
        carpetNode.addChild(aiNode.build());
        LOGGER.info("[ai-agent] 已注册 /carpet ai 命令");
    }

    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildApiNode() {
        return Commands.literal("api")
                // 无参数：查看自己的配置
                .executes(ctx -> showOwn(ctx.getSource()))
                .then(Commands.literal("set")
                        .then(Commands.argument("baseUrl", StringArgumentType.word())
                                .then(Commands.argument("model", StringArgumentType.word())
                                        // key 放最后用 greedyString：允许 key 内含特殊字符
                                        .executes(ctx -> setProfile(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "baseUrl"),
                                                StringArgumentType.getString(ctx, "model"),
                                                ""))
                                        .then(Commands.argument("apiKey", StringArgumentType.greedyString())
                                                .executes(ctx -> setProfile(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "baseUrl"),
                                                        StringArgumentType.getString(ctx, "model"),
                                                        StringArgumentType.getString(ctx, "apiKey")))))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clearProfile(ctx.getSource())))
                .then(Commands.literal("timeout")
                        .then(Commands.argument("ms", IntegerArgumentType.integer(
                                        AiProfile.MIN_TIMEOUT_MS, AiProfile.MAX_TIMEOUT_MS))
                                .executes(ctx -> setTimeout(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "ms")))))
                .then(Commands.literal("temp")
                        .then(Commands.argument("value", FloatArgumentType.floatArg(
                                        AiProfile.MIN_TEMPERATURE, AiProfile.MAX_TEMPERATURE))
                                .executes(ctx -> setTemperature(ctx.getSource(),
                                        FloatArgumentType.getFloat(ctx, "value")))))
                .then(Commands.literal("list")
                        .requires(source -> source.hasPermission(PERM_VIEW_OTHERS))
                        .executes(ctx -> listProfiles(ctx.getSource())));
    }

    // ---------- 具体实现 ----------

    private static int showHelp(@NotNull CommandSourceStack source) {
        send(source, "§6=== 假人智能 AI Agent ===");
        send(source, "§7/carpet ai api §f查看我的 AI 配置");
        send(source, "§7/carpet ai api set <地址> <模型> [密钥] §f配置 AI 接口");
        send(source, "§7/carpet ai api timeout <毫秒> §f设置超时");
        send(source, "§7/carpet ai api temp <0-2> §f设置温度");
        send(source, "§7/carpet ai api clear §f清除我的配置");
        send(source, "§7/carpet ai perm §f查看 AI 可执行命令范围");
        return 1;
    }

    private static int showOwn(@NotNull CommandSourceStack source) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        if (!ProfileManager.getInstance().isAttached()) {
            sendError(source, "配置系统尚未就绪（世界未加载）");
            return 0;
        }
        AiProfile profile = ProfileManager.getInstance().get(uuid);
        if (profile == null || !profile.isConfigured()) {
            send(source, "§7你还没有配置 AI。用法：§f/carpet ai api set <地址> <模型> [密钥]");
            return 1;
        }
        send(source, "§6我的 AI 配置");
        send(source, "§7地址: §f" + profile.baseUrl());
        send(source, "§7模型: §f" + profile.model());
        send(source, "§7密钥: §f" + profile.maskedApiKey());
        send(source, "§7超时: §f" + profile.timeoutMs() + " ms");
        send(source, "§7温度: §f" + profile.temperature());
        return 1;
    }

    private static int setProfile(@NotNull CommandSourceStack source, String baseUrl, String model, String apiKey) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        String name = playerName(source);

        AiProfile profile;
        try {
            profile = AiProfile.of(uuid, name, baseUrl, model, apiKey);
        } catch (IllegalArgumentException e) {
            // 校验失败信息直接给玩家看，这里的信息都是「格式不对」级别，不含敏感内容
            sendError(source, "配置无效: " + e.getMessage());
            return 0;
        }

        ProfileManager manager = ProfileManager.getInstance();
        if (!manager.isAttached()) {
            sendError(source, "配置系统尚未就绪（世界未加载）");
            return 0;
        }
        boolean ok = manager.put(profile);
        if (!ok) {
            sendError(source, "保存失败，请查看服务端日志");
            return 0;
        }
        send(source, "§a已保存你的 AI 配置：§f" + profile.model() + " §7@ §f" + profile.baseUrl());
        send(source, "§7密钥: " + profile.maskedApiKey() + " §8（已脱敏显示）");
        return 1;
    }

    private static int clearProfile(@NotNull CommandSourceStack source) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        AiProfile removed = ProfileManager.getInstance().remove(uuid);
        if (removed == null) {
            send(source, "§7你本来就没有配置");
            return 1;
        }
        send(source, "§a已清除你的 AI 配置");
        return 1;
    }

    private static int setTimeout(@NotNull CommandSourceStack source, int ms) {
        AiProfile current = requireOwnProfile(source);
        if (current == null) {
            return 0;
        }
        AiProfile updated;
        try {
            updated = current.withTuning(ms, current.temperature());
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendError(source, "参数无效: " + e.getMessage());
            return 0;
        }
        if (!ProfileManager.getInstance().put(updated)) {
            sendError(source, "保存失败，请查看服务端日志");
            return 0;
        }
        send(source, "§a超时已设为 §f" + ms + " ms");
        return 1;
    }

    private static int setTemperature(@NotNull CommandSourceStack source, float value) {
        AiProfile current = requireOwnProfile(source);
        if (current == null) {
            return 0;
        }
        AiProfile updated;
        try {
            updated = current.withTuning(current.timeoutMs(), value);
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendError(source, "参数无效: " + e.getMessage());
            return 0;
        }
        if (!ProfileManager.getInstance().put(updated)) {
            sendError(source, "保存失败，请查看服务端日志");
            return 0;
        }
        send(source, "§a温度已设为 §f" + value);
        return 1;
    }

    private static int listProfiles(@NotNull CommandSourceStack source) {
        ProfileManager manager = ProfileManager.getInstance();
        if (!manager.isAttached()) {
            sendError(source, "配置系统尚未就绪");
            return 0;
        }
        var all = manager.all();
        if (all.isEmpty()) {
            send(source, "§7当前没有玩家配置 AI");
            return 1;
        }
        send(source, "§6已配置的 AI（共 " + all.size() + "）");
        for (AiProfile profile : all) {
            String name = profile.ownerName();
            String label = name.isEmpty() ? profile.ownerUuid().toString().substring(0, 8) : name;
            send(source, "§7- §f" + label + " §8| §7" + profile.model() + " §8| §7" + profile.baseUrl());
        }
        return 1;
    }

    private static int showPermissions(@NotNull CommandSourceStack source) {
        send(source, "§6AI 可执行命令范围");
        send(source, "§a允许: §f" + String.join(", ", PermissionGuard.allowedRoots()));
        send(source, "§c永久禁止: §f" + String.join(", ", PermissionGuard.forbiddenRoots()));
        send(source, "§7单次最多 §f" + PermissionGuard.MAX_COMMANDS_PER_RESPONSE + " §7条命令，每条上限 §f"
                + PermissionGuard.MAX_COMMAND_LENGTH + " §7字符");
        send(source, "§7所有命令均以「任务开启者」的权限等级执行，无法提权");
        return 1;
    }

    // ---------- 辅助 ----------

    /**
     * 取命令执行者的 UUID。
     *
     * @return 玩家 UUID；由控制台/命令方块执行时返回 {@code null}
     */
    @Nullable
    private static UUID playerUuid(@NotNull CommandSourceStack source) {
        try {
            if (source.getPlayer() == null) {
                return null;
            }
            return source.getPlayer().getUUID();
        } catch (Exception e) {
            // getPlayer() 在非玩家 source 上会抛 CommandSyntaxException
            return null;
        }
    }

    /** 取玩家名；取不到时返回空串而非 null，避免后续字符串拼接出现 "null"。 */
    @NotNull
    private static String playerName(@NotNull CommandSourceStack source) {
        try {
            if (source.getPlayer() == null) {
                return "";
            }
            return source.getPlayer().getGameProfile().getName();
        } catch (Exception e) {
            return "";
        }
    }

    /** 取自己的已配置 profile；未配置/非玩家时返回 null 并已给出提示。 */
    @Nullable
    private static AiProfile requireOwnProfile(@NotNull CommandSourceStack source) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return null;
        }
        AiProfile profile = ProfileManager.getInstance().get(uuid);
        if (profile == null || !profile.isConfigured()) {
            sendError(source, "请先配置 AI：/carpet ai api set <地址> <模型> [密钥]");
            return null;
        }
        return profile;
    }

    private static void send(@NotNull CommandSourceStack source, String text) {
        try {
            source.sendSuccess(() -> Component.literal(text), false);
        } catch (Exception e) {
            LOGGER.warn("[ai-agent] 发送消息失败: {}", e.getMessage());
        }
    }

    private static void sendError(@NotNull CommandSourceStack source, String text) {
        try {
            source.sendFailure(Component.literal("§c" + text));
        } catch (Exception e) {
            LOGGER.warn("[ai-agent] 发送错误消息失败: {}", e.getMessage());
        }
    }
}
