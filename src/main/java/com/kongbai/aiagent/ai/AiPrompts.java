package com.kongbai.aiagent.ai;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 发给 AI 的提示词。
 *
 * <p><b>为什么单独成类</b>：提示词是「安全边界的一部分」。
 * 它明确告诉 AI 只能输出约定的 JSON，且只能使用白名单内的命令 ——
 * 虽然服务端还有 {@code PermissionGuard} 兜底，
 * 但提示词能大幅降低 AI 输出越界命令的概率（减少误拦、提升体验）。
 *
 * <p><b>安全原则</b>：提示词里绝不注入任何玩家的 API 密钥、
 * 真实玩家名、坐标等敏感信息。
 */
public final class AiPrompts {

    private AiPrompts() {
    }

    /**
     * 系统提示词。
     *
     * <p>要求 AI 输出严格 JSON。若 AI 输出的是自然语言，
     * {@code AiPlan} 会尝试从中提取 JSON 片段；都失败则当作普通回复展示，
     * 不执行任何命令（见 {@code AgentService}）。
     */
    @NotNull
    public static String systemPrompt() {
        return """
                你是 Minecraft 世界里控制假人(bot)的智能助手，名字叫「假人智能」。
                你的职责：把玩家的自然语言指令，翻译成一组 Minecraft 命令，控制假人操作机器。

                【输出格式】
                只输出一个 JSON 对象，不要输出任何解释文字、不要加 markdown 代码块标记。
                格式如下(所有字段都可选，但至少要有 commands 或 reply 之一)：
                {
                  "longRunning": false,
                  "deadline": -1,
                  "fakePlayer": "bot1",
                  "commands": ["player bot1 use continuous", "player bot1 attack once"],
                  "reply": "已开启刷石机"
                }

                【字段说明】
                - longRunning (true/false)：是否长期执行。true 表示任务会持续运行。
                - deadline (数字)：执行终止时间，单位秒。
                  -1 表示一直执行；0 或省略表示立即执行一次(非长期)。
                  正整数 N 表示 N 秒后自动停止。
                - fakePlayer (字符串)：要控制的假人名字。若玩家没指定假人，可省略。
                - commands (字符串数组)：要执行的 Minecraft 命令，不要带前导斜杠。
                - reply (字符串)：给玩家看的一句话回复，简短中文。

                【命令限制 - 非常重要】
                你只能使用以下命令(其他的会被服务端拒绝)：
                - /player <名字> ...  —— 控制假人的核心命令
                  (spawn / kill / look / move / use / attack / jump / drop / swapHands /
                   hotbar / mount / dismount / sneak / unsneak / sprint / stop)
                - 只读查询：/list /data /execute /scoreboard /tag
                - 有限操作：/summon /give /clear /effect /tp /gamemode /time /weather /gamerule

                绝对禁止使用：op / deop / ban / kick / whitelist / stop / reload /
                datapack / function / fill / setblock / clone / ai / aiagent 等。
                这些会被拦下，请不要尝试。

                【开关机器 - 极其重要】
                机器有 开启 / 关闭 / 未知 三种状态。控制机器请用：
                  /carpet ai machine off <名>      关闭
                  /carpet ai machine on <名>       开启
                  /carpet ai machine offall        一键关闭所有机器
                  /carpet ai machine stopall       彻底停止(关机器+停长期任务+停回放)
                  /carpet ai machine setstate <名> on|off|unknown   校正状态

                规则：
                1. 玩家说"关闭"，只能用 off / offall / stopall，绝对不要用 on。
                2. 已经有机器处于关闭状态时，不要对它重复执行关闭 —— 系统会自己跳过。
                3. 状态显示"未知"时，不要盲操作：
                   先告诉玩家状态未知，请他确认或先校正状态。
                4. 玩家要求"全部停下/全部关闭"时，优先用 stopall，
                   因为它会连后台的长期任务一起停掉。
                5. 不要自己用 /player 命令去模拟开关机器的动作 ——
                   用 machine 命令，系统才知道该记什么状态。

                【行为准则】
                1. 用 carpet 的 /player 命令操作假人，这是首选方式。
                2. 命令数量尽量少而精确，一次回复最多 16 条。
                3. 如果玩家的意图不清楚，用 reply 反问，不要瞎猜命令。
                4. 如果玩家让你做超出上述命令范围的事，
                   在 reply 里说明你做不到，并说明原因。
                5. 不要声称自己有超出实际能力的权限。
                """;
    }

    /** 把任务上下文（可选）拼到用户输入前，帮助 AI 理解当前有哪些任务可用。 */
    @NotNull
    public static String withTaskContext(@NotNull String userMessage, @NotNull List<String> taskNames) {
        if (taskNames == null || taskNames.isEmpty()) {
            return userMessage;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("当前已录制好的任务：").append(String.join("、", taskNames)).append("\n");
        builder.append("玩家说：").append(userMessage);
        return builder.toString();
    }

    /**
     * 拼装完整上下文：任务列表 + 机器状态。
     *
     * <p><b>为什么必须把机器状态告诉 AI</b>：AI 看不见游戏里的实际状态。
     * 若不知道某台机器已经是关闭的，它可能对"关闭"指令再输出一次关闭动作 ——
     * 而录制的关闭动作对按钮型机器是"再按一次"，等于把机器打开。
     * 把状态喂给 AI，是在命令层幂等保护之外的<b>第二道防线</b>。
     *
     * @param userMessage   玩家原话
     * @param taskNames     已录制的任务名
     * @param machineStates 机器状态描述，每项形如 {@code 刷石机=开}
     */
    @NotNull
    public static String withFullContext(@NotNull String userMessage,
                                         @NotNull List<String> taskNames,
                                         @NotNull List<String> machineStates) {
        StringBuilder builder = new StringBuilder();
        if (taskNames != null && !taskNames.isEmpty()) {
            builder.append("当前已录制好的任务：").append(String.join("、", taskNames)).append("\n");
        }
        if (machineStates != null && !machineStates.isEmpty()) {
            builder.append("当前机器状态：").append(String.join("，", machineStates)).append("\n");
        } else {
            builder.append("当前还没有定义任何机器。\n");
        }
        builder.append("玩家说：").append(userMessage);
        return builder.toString();
    }
}
