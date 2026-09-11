package com.kongbai.aiagent.command;

import com.kongbai.aiagent.config.AiProfile;
import com.kongbai.aiagent.config.ProfileManager;
import com.kongbai.aiagent.machine.Machine;
import com.kongbai.aiagent.machine.MachineRegistry;
import com.kongbai.aiagent.task.RecordedTask;
import com.kongbai.aiagent.task.RecorderManager;
import com.kongbai.aiagent.task.Scheduler;
import com.kongbai.aiagent.task.TaskRecorder;
import com.kongbai.aiagent.task.TaskRegistry;
import com.kongbai.aiagent.task.TaskRunner;
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
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
                
                .executes(ctx -> showHelp(ctx.getSource()))
                .then(buildApiNode())
                .then(buildRecNode())
                .then(buildTaskNode())
                .then(buildRunNode())
                .then(buildAskNode())
                .then(buildMachineNode())
                .then(buildSchedNode())
                .then(buildEndNode())
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
                        
                        .executes(ctx -> listProfiles(ctx.getSource())));
    }

    // ---------- 命令树：录制 / 任务 / 回放 ----------

    /**
     * {@code /carpet ai rec ...} 录制控制。
     *
     * <p>录制期间会记录玩家的位置、视角与执行的命令。
     * 本模组自己的 {@code /carpet ai ...} 命令会被自动排除（见 {@code TaskRecorder}），
     * 否则"结束录制"这条命令会被录进任务，回放时又触发一次结束 —— 自指循环。
     */
    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildRecNode() {
        return Commands.literal("rec")
                .executes(ctx -> recStatus(ctx.getSource()))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> recStart(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"))))
                .then(Commands.literal("stop")
                        .executes(ctx -> recStop(ctx.getSource())))
                .then(Commands.literal("cancel")
                        .executes(ctx -> recCancel(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> recStatus(ctx.getSource())));
    }

    /** {@code /carpet ai task ...} 任务管理。 */
    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildTaskNode() {
        return Commands.literal("task")
                .executes(ctx -> taskList(ctx.getSource()))
                .then(Commands.literal("list")
                        .executes(ctx -> taskList(ctx.getSource())))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> taskInfo(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> taskRemove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))));
    }

    /**
     * {@code /carpet ai run ...} 回放。
     *
     * <p>{@code run all} 对应需求里的"执行所有任务"：
     * 依次启动所有已保存任务，互不等待（各自按自己的时间轴推进）。
     */
    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildRunNode() {
        return Commands.literal("run")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> runTask(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), null))
                        .then(Commands.argument("fake", StringArgumentType.word())
                                .executes(ctx -> runTask(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "fake")))))
                .then(Commands.literal("all")
                        .executes(ctx -> runAll(ctx.getSource(), null))
                        .then(Commands.argument("fake", StringArgumentType.word())
                                .executes(ctx -> runAll(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "fake")))))
                .then(Commands.literal("stop")
                        .executes(ctx -> runStop(ctx.getSource())))
                .then(Commands.literal("list")
                        .executes(ctx -> runList(ctx.getSource())));
    }

    /**
     * {@code /carpet ai ask <话>} 直接问 AI。
     *
     * <p>这是聊天触发（「你好，ai ...」）的兜底入口 ——
     * 当 mixin 未生效时，玩家仍可用命令与 AI 对话。
     */
    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildAskNode() {
        return Commands.literal("ask")
                .executes(ctx -> {
                    sendError(ctx.getSource(), "用法: /carpet ai ask <你想让 AI 做的事>");
                    return 0;
                })
                .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> askAi(ctx.getSource(),
                                StringArgumentType.getString(ctx, "question"))));
    }

    /** {@code /carpet ai machine ...} 机器管理。 */
    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildMachineNode() {
        return Commands.literal("machine")
                .executes(ctx -> machineList(ctx.getSource()))
                .then(Commands.literal("list")
                        .executes(ctx -> machineList(ctx.getSource())))
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("onTask", StringArgumentType.word())
                                        .executes(ctx -> machineAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "onTask"),
                                                null))
                                        .then(Commands.argument("offTask", StringArgumentType.word())
                                                .executes(ctx -> machineAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "onTask"),
                                                        StringArgumentType.getString(ctx, "offTask")))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> machineRemove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("on")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> machineSwitch(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), true))))
                .then(Commands.literal("off")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> machineSwitch(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), false))))
                .then(Commands.literal("offall")
                        .executes(ctx -> machineOffAll(ctx.getSource())))
                .then(Commands.literal("onall")
                        .executes(ctx -> machineOnAll(ctx.getSource())));
    }

    /** {@code /carpet ai sched ...} 长期任务。 */
    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildSchedNode() {
        return Commands.literal("sched")
                .executes(ctx -> schedList(ctx.getSource()))
                .then(Commands.literal("list")
                        .executes(ctx -> schedList(ctx.getSource())))
                .then(Commands.literal("stop")
                        .executes(ctx -> schedStop(ctx.getSource())));
    }

    /**
     * {@code /carpet ai end ...} 需求里「end / confirm」的入口。
     *
     * <p>{@code end confirm} 等价于 {@code run all}：执行所有已保存任务。
     */
    @NotNull
    private static LiteralArgumentBuilder<CommandSourceStack> buildEndNode() {
        return Commands.literal("end")
                .then(Commands.literal("confirm")
                        .executes(ctx -> runAll(ctx.getSource(), null))
                        .then(Commands.argument("fake", StringArgumentType.word())
                                .executes(ctx -> runAll(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "fake")))))
                .then(Commands.literal("cancel")
                        .executes(ctx -> runStop(ctx.getSource())));
    }

    // ---------- 具体实现 ----------

    private static int showHelp(@NotNull CommandSourceStack source) {
        send(source, "§6=== 假人智能 AI Agent ===");
        send(source, "§8— AI 配置 —");
        send(source, "§7/carpet ai api §f查看我的 AI 配置");
        send(source, "§7/carpet ai api set <地址> <模型> [密钥] §f配置 AI 接口");
        send(source, "§7/carpet ai api timeout <毫秒> §f设置超时");
        send(source, "§7/carpet ai api temp <0-2> §f设置温度");
        send(source, "§7/carpet ai api clear §f清除我的配置");
        send(source, "§8— 任务录制 —");
        send(source, "§7/carpet ai rec <任务名> §f开始录制（记录移动/视角/命令）");
        send(source, "§7/carpet ai rec stop §f结束并保存");
        send(source, "§7/carpet ai rec cancel §f放弃录制");
        send(source, "§7/carpet ai rec status §f查看录制状态");
        send(source, "§8— 任务管理 —");
        send(source, "§7/carpet ai task list §f列出所有任务");
        send(source, "§7/carpet ai task info <任务名> §f查看任务详情");
        send(source, "§7/carpet ai task remove <任务名> §f删除任务");
        send(source, "§8— 回放 —");
        send(source, "§7/carpet ai run <任务名> [假人名] §f回放任务");
        send(source, "§7/carpet ai run all [假人名] §f回放所有任务");
        send(source, "§7/carpet ai run list §f查看进行中的回放");
        send(source, "§7/carpet ai run stop §f停止所有回放");
        send(source, "§8— AI 对话 —");
        send(source, "§7你好，ai <话> §f聊天里直接触发（也支持 你好ai / hi,ai）");
        send(source, "§7/carpet ai ask <话> §f命令方式问 AI（聊天触发失效时的兜底）");
        send(source, "§8— 机器 —");
        send(source, "§7/carpet ai machine add <名> <开任务> [关任务] §f定义机器");
        send(source, "§7/carpet ai machine on|off <名> §f开关单台");
        send(source, "§7/carpet ai machine offall §f一键关闭所有机器");
        send(source, "§7/carpet ai machine list|remove §f查看/删除");
        send(source, "§8— 长期任务 —");
        send(source, "§7/carpet ai sched list|stop §f查看/停止长期任务");
        send(source, "§8— 其他 —");
        send(source, "§7/carpet ai end confirm §f执行所有任务（等价 run all）");
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

    // ---------- 录制 ----------

    private static int recStart(@NotNull CommandSourceStack source, String rawName) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        String name = rawName == null ? "" : rawName.trim();
        if (!RecordedTask.isValidName(name)) {
            sendError(source, "任务名非法：只能包含中文/字母/数字/下划线/连字符，且不超过 "
                    + RecordedTask.MAX_NAME_LENGTH + " 个字符");
            return 0;
        }
        RecorderManager recorders = RecorderManager.getInstance();
        if (recorders.isRecording(uuid)) {
            sendError(source, "你正在录制中，请先 /carpet ai rec stop 或 cancel");
            return 0;
        }
        long tick = currentTick(source);
        TaskRecorder recorder = recorders.start(uuid, playerName(source),
                PermissionGuard.levelOf(source), name, tick);
        if (recorder == null) {
            sendError(source, "无法开始录制（可能已有进行中的会话）");
            return 0;
        }
        send(source, "§a开始录制任务「" + name + "」");
        send(source, "§7会记录：移动、视角、你执行的命令（本模组命令除外）");
        if (TaskRegistry.getInstance().exists(name)) {
            send(source, "§6注意：同名任务已存在，结束录制时会被覆盖");
        }
        send(source, "§7结束录制：§f/carpet ai rec stop");
        return 1;
    }

    private static int recStop(@NotNull CommandSourceStack source) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        RecordedTask task = RecorderManager.getInstance().stop(uuid);
        if (task == null) {
            sendError(source, "你没有进行中的录制，或录制内容为空");
            return 0;
        }
        TaskRegistry registry = TaskRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "任务系统尚未就绪，保存失败");
            return 0;
        }
        boolean existed = registry.exists(task.name());
        if (!registry.put(task)) {
            sendError(source, "保存失败，请查看服务端日志");
            return 0;
        }
        send(source, "§a已" + (existed ? "覆盖" : "保存") + "任务「" + task.name() + "」："
                + task.size() + " 个动作，约 " + String.format("%.1f", task.durationTicks() / 20.0) + " 秒");
        send(source, "§7回放：§f/carpet ai run " + task.name() + " [假人名]");
        return 1;
    }

    private static int recCancel(@NotNull CommandSourceStack source) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        if (RecorderManager.getInstance().cancel(uuid)) {
            send(source, "§a已取消录制（内容已丢弃）");
            return 1;
        }
        send(source, "§7你没有进行中的录制");
        return 1;
    }

    private static int recStatus(@NotNull CommandSourceStack source) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        TaskRecorder recorder = RecorderManager.getInstance().get(uuid);
        if (recorder == null) {
            send(source, "§7当前没有进行中的录制");
            send(source, "§7开始录制：§f/carpet ai rec <任务名>");
            return 1;
        }
        send(source, "§6正在录制「" + recorder.taskName() + "」");
        send(source, "§7已记录 §f" + recorder.size() + " §7个动作（上限 " + RecordedTask.MAX_ACTIONS + "）");
        if (recorder.isFull()) {
            send(source, "§c已达上限，将自动保存");
        }
        return 1;
    }

    // ---------- 任务管理 ----------

    private static int taskList(@NotNull CommandSourceStack source) {
        TaskRegistry registry = TaskRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "任务系统尚未就绪（世界未加载）");
            return 0;
        }
        if (registry.size() == 0) {
            send(source, "§7还没有任何任务。用 §f/carpet ai rec <任务名> §7录制一个");
            return 1;
        }
        send(source, "§6任务列表（共 " + registry.size() + "）");
        for (RecordedTask task : registry.all()) {
            send(source, String.format("§7- §f%-16s §8| %3d 动作 §8| §7%.1fs §8| §7%s",
                    task.name(), task.size(), task.durationTicks() / 20.0,
                    task.ownerName().isEmpty() ? "未知创建者" : task.ownerName()));
        }
        return 1;
    }

    private static int taskInfo(@NotNull CommandSourceStack source, String rawName) {
        RecordedTask task = TaskRegistry.getInstance().get(rawName);
        if (task == null) {
            sendError(source, "任务不存在: " + rawName);
            return 0;
        }
        send(source, "§6任务「" + task.name() + "」");
        send(source, "§7创建者: §f" + (task.ownerName().isEmpty() ? task.ownerUuid().toString() : task.ownerName()));
        send(source, "§7权限等级: §f" + task.permLevel() + " §8（回放命令以此等级执行）");
        send(source, "§7动作数: §f" + task.size());
        send(source, "§7时长: §f" + String.format("%.1f", task.durationTicks() / 20.0) + " 秒");
        int shown = Math.min(task.size(), 8);
        send(source, "§7前 " + shown + " 个动作:");
        for (int i = 0; i < shown; i++) {
            send(source, "§8  " + task.actions().get(i).describe());
        }
        if (task.size() > shown) {
            send(source, "§8  ... 还有 " + (task.size() - shown) + " 个");
        }
        return 1;
    }

    private static int taskRemove(@NotNull CommandSourceStack source, String rawName) {
        RecordedTask task = TaskRegistry.getInstance().get(rawName);
        if (task == null) {
            sendError(source, "任务不存在: " + rawName);
            return 0;
        }
        // 只允许创建者本人或权限等级更高者删除
        UUID uuid = playerUuid(source);
        boolean isOwner = uuid != null && uuid.equals(task.ownerUuid());
        boolean isAdmin = PermissionGuard.levelOf(source) > task.permLevel();
        if (!isOwner && !isAdmin) {
            sendError(source, "只有任务创建者本人可以删除该任务");
            return 0;
        }
        TaskRegistry.getInstance().remove(rawName);
        send(source, "§a已删除任务「" + task.name() + "」");
        return 1;
    }

    // ---------- 回放 ----------

    private static int runTask(@NotNull CommandSourceStack source, String rawName, String fakeName) {
        TaskRegistry registry = TaskRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "任务系统尚未就绪（世界未加载）");
            return 0;
        }
        RecordedTask task = registry.get(rawName);
        if (task == null) {
            sendError(source, "任务不存在: " + rawName);
            return 0;
        }
        long tick = currentTick(source);
        Long id = TaskRunner.getInstance().start(task, fakeName, tick);
        if (id == null) {
            sendError(source, "无法启动回放：可能任务为空，或回放数量已达上限 "
                    + TaskRunner.MAX_CONCURRENT);
            return 0;
        }
        send(source, "§a开始回放任务「" + task.name() + "」"
                + (fakeName == null || fakeName.isBlank() ? "" : " → 假人 " + fakeName));
        send(source, "§7共 " + task.size() + " 个动作，预计 "
                + String.format("%.1f", task.durationTicks() / 20.0) + " 秒");
        return 1;
    }

    private static int runAll(@NotNull CommandSourceStack source, String fakeName) {
        TaskRegistry registry = TaskRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "任务系统尚未就绪（世界未加载）");
            return 0;
        }
        if (registry.size() == 0) {
            send(source, "§7没有可回放的任务");
            return 1;
        }
        long tick = currentTick(source);
        TaskRunner runner = TaskRunner.getInstance();
        int started = 0;
        int failed = 0;
        for (RecordedTask task : registry.all()) {
            if (task == null || task.isEmpty()) {
                continue;
            }
            if (runner.start(task, fakeName, tick) != null) {
                started++;
            } else {
                failed++;
            }
        }
        send(source, "§a已启动 " + started + " 个任务回放"
                + (fakeName == null || fakeName.isBlank() ? "" : "（假人 " + fakeName + "）"));
        if (failed > 0) {
            send(source, "§6" + failed + " 个任务未能启动（可能已达并发上限 " + TaskRunner.MAX_CONCURRENT + "）");
        }
        return 1;
    }

    private static int runStop(@NotNull CommandSourceStack source) {
        TaskRunner runner = TaskRunner.getInstance();
        int count = runner.activeCount();
        if (count == 0) {
            send(source, "§7当前没有进行中的回放");
            return 1;
        }
        runner.stopAll();
        send(source, "§a已停止 " + count + " 个回放");
        return 1;
    }

    private static int runList(@NotNull CommandSourceStack source) {
        TaskRunner runner = TaskRunner.getInstance();
        if (!runner.hasActive()) {
            send(source, "§7当前没有进行中的回放");
            return 1;
        }
        send(source, "§6进行中的回放（共 " + runner.activeCount() + "）");
        for (String line : runner.activeNames()) {
            send(source, "§7- §f" + line);
        }
        return 1;
    }

    // ---------- AI 对话 ----------

    private static int askAi(@NotNull CommandSourceStack source, String question) {
        var player = source.getPlayer();
        if (player == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            sendError(source, "用法: /carpet ai ask <你想让 AI 做的事>");
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            sendError(source, "服务器未就绪");
            return 0;
        }
        try {
            com.kongbai.aiagent.ai.ChatTrigger.dispatch(server, player, text);
            return 1;
        } catch (RuntimeException e) {
            sendError(source, "AI 调用失败: " + e.getMessage());
            return 0;
        }
    }

    // ---------- 机器管理 ----------

    private static int machineAdd(@NotNull CommandSourceStack source, String name, String onTask, String offTask) {
        UUID uuid = playerUuid(source);
        if (uuid == null) {
            sendError(source, "该命令只能由玩家执行");
            return 0;
        }
        MachineRegistry registry = MachineRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "机器系统尚未就绪（世界未加载）");
            return 0;
        }
        TaskRegistry tasks = TaskRegistry.getInstance();
        // 校验引用的任务确实存在，避免建出指向空任务的机器
        if (onTask != null && !onTask.isBlank() && tasks.get(onTask) == null) {
            sendError(source, "开启任务不存在: " + onTask);
            return 0;
        }
        if (offTask != null && !offTask.isBlank() && tasks.get(offTask) == null) {
            sendError(source, "关闭任务不存在: " + offTask);
            return 0;
        }
        Machine machine;
        try {
            machine = Machine.of(name, onTask, offTask, uuid, playerName(source),
                    PermissionGuard.levelOf(source));
        } catch (IllegalArgumentException e) {
            sendError(source, "参数无效: " + e.getMessage());
            return 0;
        }
        boolean existed = registry.exists(name);
        if (!registry.put(machine)) {
            sendError(source, "保存失败，请查看服务端日志");
            return 0;
        }
        send(source, "§a已" + (existed ? "更新" : "添加") + "机器「" + machine.name() + "」");
        send(source, "§7" + machine.describe());
        return 1;
    }

    private static int machineRemove(@NotNull CommandSourceStack source, String name) {
        Machine machine = MachineRegistry.getInstance().get(name);
        if (machine == null) {
            sendError(source, "机器不存在: " + name);
            return 0;
        }
        UUID uuid = playerUuid(source);
        boolean isOwner = uuid != null && uuid.equals(machine.ownerUuid());
        boolean isAdmin = PermissionGuard.levelOf(source) > machine.permLevel();
        if (!isOwner && !isAdmin) {
            sendError(source, "只有创建者本人可以删除该机器");
            return 0;
        }
        MachineRegistry.getInstance().remove(name);
        send(source, "§a已删除机器「" + machine.name() + "」");
        return 1;
    }

    private static int machineList(@NotNull CommandSourceStack source) {
        MachineRegistry registry = MachineRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "机器系统尚未就绪（世界未加载）");
            return 0;
        }
        if (registry.size() == 0) {
            send(source, "§7还没有定义任何机器");
            send(source, "§7先录两个任务（开 / 关），再 §f/carpet ai machine add <名> <开任务> <关任务>");
            return 1;
        }
        send(source, "§6机器列表（共 " + registry.size() + "）");
        for (Machine machine : registry.all()) {
            send(source, "§7- §f" + machine.describe());
        }
        send(source, "§7一键关闭所有：§f/carpet ai machine offall");
        return 1;
    }

    /**
     * 开关单台机器。
     *
     * <p>本质是回放对应的录制任务，因此会保留录制时的时间顺序。
     */
    private static int machineSwitch(@NotNull CommandSourceStack source, String rawName, boolean on) {
        Machine machine = MachineRegistry.getInstance().get(rawName);
        if (machine == null) {
            sendError(source, "机器不存在: " + rawName);
            return 0;
        }
        String taskName = on ? machine.onTask() : machine.offTask();
        if (taskName == null) {
            sendError(source, "机器「" + machine.name() + "」未定义"
                    + (on ? "开启" : "关闭") + "任务");
            return 0;
        }
        RecordedTask task = TaskRegistry.getInstance().get(taskName);
        if (task == null) {
            sendError(source, "关联的任务已不存在: " + taskName);
            return 0;
        }
        Long id = TaskRunner.getInstance().start(task, null, currentTick(source));
        if (id == null) {
            sendError(source, "启动失败：任务为空或回放已达上限");
            return 0;
        }
        send(source, "§a已" + (on ? "开启" : "关闭") + "机器「" + machine.name() + "」（任务 " + taskName + "）");
        return 1;
    }

    /** 一键关闭所有机器 —— 需求里的核心场景。 */
    private static int machineOffAll(@NotNull CommandSourceStack source) {
        MachineRegistry registry = MachineRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "机器系统尚未就绪");
            return 0;
        }
        List<Machine> withOff = registry.allWithOff();
        if (withOff.isEmpty()) {
            send(source, "§7没有定义了关闭任务的机器");
            return 1;
        }
        long tick = currentTick(source);
        TaskRunner runner = TaskRunner.getInstance();
        int started = 0;
        for (Machine machine : withOff) {
            RecordedTask task = TaskRegistry.getInstance().get(machine.offTask());
            if (task == null || task.isEmpty()) {
                continue;
            }
            if (runner.start(task, null, tick) != null) {
                started++;
            }
        }
        send(source, "§a已触发 " + started + " 台机器的关闭流程（共 " + withOff.size() + " 台有关闭任务）");
        if (started < withOff.size()) {
            send(source, "§6部分机器未能启动（可能已达并发上限 " + TaskRunner.MAX_CONCURRENT + "）");
        }
        return 1;
    }

    private static int machineOnAll(@NotNull CommandSourceStack source) {
        MachineRegistry registry = MachineRegistry.getInstance();
        if (!registry.isAttached()) {
            sendError(source, "机器系统尚未就绪");
            return 0;
        }
        long tick = currentTick(source);
        TaskRunner runner = TaskRunner.getInstance();
        int started = 0;
        for (Machine machine : registry.all()) {
            if (machine == null || !machine.hasOn()) {
                continue;
            }
            RecordedTask task = TaskRegistry.getInstance().get(machine.onTask());
            if (task == null || task.isEmpty()) {
                continue;
            }
            if (runner.start(task, null, tick) != null) {
                started++;
            }
        }
        send(source, "§a已开启 " + started + " 台机器");
        return 1;
    }

    // ---------- 长期任务 ----------

    private static int schedList(@NotNull CommandSourceStack source) {
        Scheduler scheduler = Scheduler.getInstance();
        if (!scheduler.hasActive()) {
            send(source, "§7当前没有进行中的长期任务");
            return 1;
        }
        send(source, "§6进行中的长期任务（共 " + scheduler.activeCount() + "）");
        for (String line : scheduler.activeNames()) {
            send(source, "§7- §f" + line);
        }
        send(source, "§7停止全部：§f/carpet ai sched stop");
        return 1;
    }

    private static int schedStop(@NotNull CommandSourceStack source) {
        Scheduler scheduler = Scheduler.getInstance();
        int count = scheduler.activeCount();
        if (count == 0) {
            send(source, "§7当前没有进行中的长期任务");
            return 1;
        }
        scheduler.stopAll();
        send(source, "§a已停止 " + count + " 个长期任务");
        return 1;
    }

    /**
     * 取当前游戏刻。
     *
     * <p>取不到时返回 0 —— 这会让回放"立刻执行所有动作"，
     * 而不是崩溃。属于可接受的降级。
     */
    private static long currentTick(@NotNull CommandSourceStack source) {
        try {
            var server = source.getServer();
            return server == null ? 0L : server.getTickCount();
        } catch (Throwable t) {
            return 0L;
        }
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
            return source.getTextName();
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
