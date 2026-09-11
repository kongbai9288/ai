# M3–M5 代码审查报告

**模块**：AI 对话引擎 / 长期任务调度 / 机器管理
**审查日期**：2026-09-11
**规模**：24 个 Java 文件，5755 行（M3–M5 新增 10 个）

---

## 一、构建失败修复（本轮最重要）

CI 连续失败 5 次，因为沙盒无法下载 Actions 日志，
先改造了 workflow —— **把构建日志自动推送到 `ci-logs` 分支**，
才拿到真实报错。两个根因都不是代码逻辑问题，而是构建配置：

### 🔴 P0-A `Failed to find official mojang mappings for 26.2`

**原因**：26.1 起 Minecraft **不再混淆**（1.21.11 是最后一个混淆版本）。
Loom 会照常去下载官方映射，但 26.2 根本没有映射文件，于是失败。

**修复**（依据 Fabric 官方《移植到 26.1》文档）：
- Loom 插件 id：`fabric-loom` → `net.fabricmc.fabric-loom`
- **删除 `mappings` 行** —— 未混淆版本不需要反混淆
- `modImplementation` / `modCompileOnly` → `implementation` / `compileOnly`
- 加 `noIntermediateMappings()`，移除 `withSourcesJar()`
  （26.1 起无 intermediary 命名空间，源码 jar 无法重映射）

### 🔴 P0-B `Could not find carpet:fabric-carpet:26.2-26.2+v260621`

**原因**：抄了 carpet-extra 的坐标写法 `${minecraft_version}-${carpet_core_version}`。
但该坐标在 masa.dy.fi 上的版本命名并不稳定（carpet-extra 用的是 fork 分支），
CI 上解析不到。

**修复**：改用 Modrinth Maven：
```gradle
compileOnly "maven.modrinth:carpet:26.2"
```
Modrinth 是 Carpet 官方发布渠道，版本号就是发布版本号（`26.2`），确定可解析。

### 附带改进：推送脚本

`ghpush.py` 原来对每个文件都建 blob，文件一多就超时。
现在会**比对本地与远端的 git blob sha1，跳过未变更文件**，
并对新建 blob 用 6 线程并发。本次推送从「超时失败」变成「复用 33 个、仅新建 2 个」。

---

## 二、M3–M5 新增文件

| 文件 | 职责 |
|---|---|
| `ai/AiProvider` | AI 接口抽象（异步、失败语义统一） |
| `ai/OpenAiProvider` | OpenAI 兼容协议实现（JDK 内置 HttpClient） |
| `ai/AiPrompts` | 系统提示词（安全边界的一部分） |
| `ai/AiPlan` | 解析 AI 返回的 JSON 计划 |
| `ai/AgentService` | 对话引擎：异步请求 → 主线程执行 |
| `ai/ChatTrigger` | 触发入口（聊天 + 命令共用） |
| `mixin/ChatMixin` | 聊天「你好，ai」触发 |
| `task/Scheduler` | 长期任务调度（deadline 到点停止） |
| `machine/Machine` | 机器定义（不可变） |
| `machine/MachineRegistry` | 机器注册表 + 持久化 |

---

## 三、设计要点

### AI 输出的 JSON 契约

```json
{
  "longRunning": true,
  "deadline": -1,
  "fakePlayer": "bot1",
  "commands": ["player bot1 use continuous"],
  "reply": "已开启刷石机"
}
```

对应需求里的四个字段：**是否长期执行 / 执行终止时间（-1 一直）/ 执行命令 / 假人名**。

**额外做了中文键兼容**：同时接受 `是否长期执行` / `执行终止时间` / `假人名` / `执行命令`。
实战中 AI 常会照着提示词里的中文输出键名，多接受一套能显著提高成功率。

**解析容错三级降级**：
1. 整个回复就是 JSON → 直接解析
2. 含 ` ```json ` 代码块 → 提取解析
3. 含首个 `{...}` → 括号配平提取
4. 都不是 → **当作纯文本回复展示，绝不执行命令**

### 长期任务的执行模型

启动执行一次「开始命令」，到期自动执行「停止命令」，**中间不重复执行**。

原因：carpet 的 `/player X use continuous` 本身就会持续，
重复执行反而会打断动作。这样行为可预测，也省 tick 开销。

### 机器的抽象

机器 = **名字 + 开启任务名 + 关闭任务名**。

玩家先录一遍「怎么开」、再录一遍「怎么关」，就定义出一台机器。
不需要理解红石或具体方块 —— 任何能用键盘完成的开关流程都能录成机器。

`/carpet ai machine offall` 即一键关闭所有机器（需求的核心场景）。

---

## 四、防提权（贯穿 M3–M5）

AI 回复属于**完全不可信输入**，四道防线：

1. **提示词约束** —— 明确告知 AI 只能用白名单命令（降低越界输出概率，减少误拦）
2. **解析层限流** —— 命令条数受 `MAX_COMMANDS_PER_RESPONSE`(16) 限制
3. **逐条校验** —— 每条命令过 `PermissionGuard.check()`；
   被拒的**单独提示**，不影响其他命令执行
4. **降权执行** —— 用**触发玩家**的权限等级构造命令源，不是 OP

**为什么用触发者权限而非任务定义者**：
录制任务用定义者权限快照（防「借他人之手提权」），
而 AI 对话用触发者当前权限 —— 因为 AI 是实时响应，
用当前权限最符合直觉，也不会让权限快照长期滞留成为漏洞。

---

## 五、边界情况

| 位置 | 情况 | 行为 |
|---|---|---|
| `OpenAiProvider` | 响应体 > 8MB | 丢弃并报错（防异常服务吃满内存） |
| `OpenAiProvider` | 用户输入 > 2000 字符 | 截断 |
| `OpenAiProvider` | HTTP 非 2xx | 返回精简错误（**不含密钥**，响应体截断到 200 字符） |
| `OpenAiProvider` | 地址非法 | `IllegalArgumentException` → `AiException`（不崩溃） |
| `AiPlan` | deadline 为负 | 统一为 -1（一直） |
| `AiPlan` | deadline > 2 小时 | 截断到 2 小时 |
| `AiPlan` | 既无命令又无回复 | 视为无效计划 → 返回 null → 当纯文本展示 |
| `AgentService` | AI 未配置 | 提示用法，不发起请求 |
| `AgentService` | AI 返回非 JSON | 当纯文本展示，**不执行任何命令** |
| `ChatMixin` | `handleChat` 方法名不匹配 | mixin 被跳过（`require=0`），聊天不触发 |
| `ChatMixin` | 反射找不到 player 字段 | 静默返回，聊天不触发 |
| `Scheduler` | sink 为 null | 停止全部长期任务（不能让它们永远卡住） |
| `Machine.add` | 引用的任务不存在 | 拒绝创建（避免建出指向空任务的机器） |

**降级链**：聊天触发失效 → 玩家仍可用 `/carpet ai ask <话>`；
两者都失效 → 录制/回放/机器等不依赖 AI 的功能完全可用。

---

## 六、内存安全

| 风险点 | 处理 |
|---|---|
| `AgentService` 持有 server | ✅ 不持有。通过 `MainThreadRunner`（`server::execute`）与 `CommandSink` 交互 |
| `ChatTrigger` 持有 player | ✅ 不保存，仅本次调用使用；lambda 回调完即释放 |
| `Scheduler` 持有 sink | ✅ 不保存，每刻临时传入（同 M2 的教训） |
| `OpenAiProvider` 线程池 | ✅ 守护线程 + `shutdown()` 在 `onServerClosed` 调用 |
| 机器/任务/配置注册表 | ✅ 三个都在 `onServerClosed` 里 `detach()` |
| 静态可变集合 | ✅ 无 |

**关键约定（新增两条）**：
- 任何持有 `CommandSink` 的地方都**不得长期保存**
- `AgentService.shutdown()` 必须调用，否则 HTTP 线程池

---

## 七、已知风险与未决项

### ✅ 编译已通过

`402e37b7` 起 CI 全绿（Build with Gradle / Upload artifact 均 success），
jar 产物由 Actions 上传。沙盒 JDK 11 无法本地验证，全部靠 CI 迭代修正。

**编译问题累计 3 轮、72 → 7 → 0 个错误**，全部源于 26.1 的 API 重构：

| 变更 | 处理 |
|---|---|
| `carpet.api.CarpetExtension` | → `carpet.CarpetExtension`（包名错误） |
| `hasPermission(int)` / `getPermissionLevel()` / `withPermission(int)` | 26.1 权限系统重构后全部移除 |
| `MinecraftServer.getSavePath(LevelResource)` | 反射依次尝试 `getSavePath`/`getWorldPath`，失败则不持久化 |
| `ServerPlayer.getServer()` | ChatMixin 改反射取 `MinecraftServer` 字段 |
| `GameProfile.getName()` | → `source.getTextName()` |
| `AiException extends Exception` | → `RuntimeException`（lambda 不能抛受检异常） |
| `catch (IllegalArgumentException \| NumberFormatException)` | 子类不能与父类并列 |

以下 API 假设已在 CI 中确认（M3–M5 新增部分）：

1. `ServerboundChatPacket.message()` ✅
2. `ServerGamePacketListenerImpl.handleChat` ✅（编译通过；`require=0` 保留运行时降级）
3. `ServerPlayer.createCommandSourceStack()` ✅
4. `MinecraftServer.getCommands().performPrefixedCommand(...)` ✅
5. `ServerPlayer.sendSystemMessage(Component)` ✅
6. `Commands.hasPermission(Commands.LEVEL_*)` ✅（26.1 新的权限探测方式）

**注意**：编译通过 ≠ 运行正确。`handleChat` 需进游戏实测（见下条）。

### ⚠️ 聊天触发可能不生效

`handleChat` 的方法名在 26.2 可能已改（1.21.9+ 聊天相关重构频繁）。
若 mixin 未命中，功能降级到 `/carpet ai ask`。

**验证方法**：进游戏聊天输入「你好，ai 你好」，
若无反应但 `/carpet ai ask 你好` 有反应，说明 mixin 未命中，
需要查 26.2 的实际方法名。

### ⚠️ 回放时 `@s` 选择器失效

录制命令原样执行，但回放的命令源无玩家上下文。
`/give @s stone` 这类命令回放时无法解析。**建议录制时避免 `@s`**。

### ⚠️ API 密钥明文存储

存在 `存档目录/aiagent/aiagent_profiles.json`。
单机场景可接受；迁移到多人服务端时应考虑加密或改为服务端统一配置。

---

## 八、给 AI 的交接说明

> 本段供后续对话中的 AI 实例阅读。**新开对话时必须重新确认**：

1. MC 版本 **26.2**，Java 25，**26.1 起 MC 未混淆**（不要加 `mappings` 行！）
2. Loom 插件 id 是 `net.fabricmc.fabric-loom`，依赖用 `implementation`/`compileOnly`
3. Carpet 坐标是 `maven.modrinth:carpet:26.2`
4. `AiPlan` / `Machine` / `RecordedTask` 均不可变，不要加 setter
5. `CommandSink` 绝不长期保存（会间接持有 MinecraftServer）
6. 五个管理器（`ProfileManager` / `TaskRegistry` / `MachineRegistry` /
   `RecorderManager` / `TaskRunner` / `Scheduler`）都要在 `onServerClosed` 清理
7. AI 命令必须逐条过 `PermissionGuard.check()`

**已完成**：M1 骨架配置 / M2 录制回放 / M3 AI 引擎 / M4 长期任务 / M5 机器管理
**下一步**：服务端迁移（当前是客户端集成服务端模式）

**不要做的事**：
- 不要改 `gradle.properties` 里的版本号
- 不要给 26.1+ 加 `mappings` 行
- 不要让 AI 生成的命令绕过 `PermissionGuard`
- 不要在值对象里持有 `ServerPlayer` / `MinecraftServer`

---

# 九、补充审查：机器状态与「反向操作」风险

**触发**：外部审查提出「机器已关闭时说关闭，会不会反而打开」。
**结论：该风险真实存在，已修复。** 本节记录问题与方案。

## 9.1 问题定性

之前的 `Machine` 类**没有任何状态字段**，只有 `onTask` / `offTask` 两个动作名。

关键点在于：**录制的开关任务是「动作」，不是「状态设置」**。

对按钮、拉杆这类 toggle 型机器：

| 实际状态 | 执行 offTask | 结果 |
|---|---|---|
| ON | 按一下 | → OFF ✅ |
| OFF | 按一下 | → **ON** ❌ |

所以「机器已关 → 说关闭 → 系统再执行一次关闭动作 → 机器被打开」是**真实缺陷**，
不是纯语义问题。之前的代码确实没有防护。

## 9.2 修复：三态 + 幂等

新增 `MachineState` 枚举：`ON` / `OFF` / `UNKNOWN`。

**幂等保护**（命令层第一道防线）：
- `machine off X` 且 `state == OFF` → **跳过**，提示已是关闭状态
- `machine on X` 且 `state == ON` → **跳过**
- `offall` 只遍历 `state != OFF` 的机器
- 需要强制时用 `machine off X force`

**UNKNOWN 的处理**（最难的部分）：
- 不猜。既不能假设开着（该关的没关），也不能假设关着（重复关会打开）
- 单个 `off` 遇到 UNKNOWN → **执行 + 明确警告**（安全方向是关）
- `offall` 遇到 UNKNOWN → 执行，但把机器名列出来让玩家核对
- AI 侧遇到 UNKNOWN → 提示词要求**先问玩家，不要盲操作**

**状态喂给 AI**（第二道防线）：
`AiPrompts.withFullContext()` 现在会把所有机器状态拼进上下文
（`刷石机=开，熔炉组=关闭`）。AI 看不见游戏内的实际状态，
不喂给它就会盲操作。

## 9.3 手动开关机：无解，只能校正

玩家在游戏里直接右键拉杆、手动操作假人，模组**无法感知** ——
没有任何可靠事件能捕获"这个方块状态变了"。

因此提供显式校正入口：

```
/carpet ai machine setstate <名> on|off|unknown
```

`unknown` 也是有效值：用于「我也不知道现在什么状态」，
让系统下次操作走保守路径（提示而非盲执行）。

`machine list` 会统计并提示有多少台状态未知。

## 9.4 纠正一个事实性错误

外部审查称「`offall` 设计上会停止所有长期任务」—— **这是错的**。

查 `machineOffAll` 实现：它只调用 `TaskRunner.start()` 启动回放，
**完全没有触碰 `Scheduler`**。一个 `longRunning` 的 AI 长期任务
在 `offall` 之后仍会在后台继续。

已新增 `/carpet ai machine stopall`：
执行关闭任务 + **停所有长期任务** + **停所有回放**。
这才是语义完整的「全部停下」。

提示词里也明确了：玩家说"全部停下"时优先用 `stopall`。

## 9.5 修改后的命令

```
/carpet ai machine on|off <名> [force]              幂等开关
/carpet ai machine offall                           只关"不是关闭态"的
/carpet ai machine onall                            只开"不是开启态"的
/carpet ai machine stopall                          关机器 + 停长期任务 + 停回放
/carpet ai machine setstate <名> on|off|unknown      状态校正
/carpet ai machine list                             显示状态 + 统计未知数量
```

## 9.6 残留风险

1. **状态仍可能失真** —— 手动操作后忘记 `setstate`，保护就失效。
   缓解：`list` 会提示未知数量；UNKNOWN 操作会警告。
2. **并发回放可能干扰** —— 同一台机器短时间内被多次 `off`，
   幂等检查与状态更新之间有窗口。当前单机场景影响有限，
   迁移服务端时应加机器级锁。
3. **录制任务本身可能是多步骤复合动作** —— 若"关闭任务"录的是
   "先开再关"这类流程，单次执行是对的，但状态跟踪会记成 OFF。
   属于使用方式问题，无通用解。

---

# 十、方块状态检测（红石状态对比）

**需求**：假人到位后，检测现场方块的红石状态，与录制时记录对比，
不同则跳过命令 —— 避免「机器已关，说关闭反而打开」。
录制/回放时向玩家发送提示。

## 10.1 核心设计：快照的语义

关键决策是**快照记的是「执行命令前」的状态**。

mixn 在 `performCommand` 的 `HEAD` 注入，此时命令尚未执行，
因此采集到的是「该干活之前的现场」。

回放时的判定：

| 当前状态 vs 快照 | 含义 | 行为 |
|---|---|---|
| 一致 | 这活儿还没干过 | **执行** |
| 不一致 | 现场已变（多半已干过） | **跳过** |

举例（录制"关闭刷石机"）：
- 录制时执行前，按钮 `powered=true`（机器开着）→ 快照记 `true`
- 回放时读到 `true` → 一致 → 机器还是开的 → 执行关闭 ✅
- 回放时读到 `false` → 不一致 → 已经关了 → 跳过 ✅

## 10.2 组件

| 文件 | 职责 |
|---|---|
| `task/BlockSnapshot` | 方块状态快照（不可变），只存基本类型与 String |
| `task/BlockProbe` | 采集器接口（隔离 MC 依赖） |
| `task/LevelBlockProbe` | MC 实现，读真实世界 |
| `task/RecordedAction` | COMMAND 动作携带快照列表 |
| `task/TaskRunner` | 回放时 recollect + 对比 + 决策 |

**快照内容**：方块 ID + 红石相关属性摘要（`powered=true,facing=north`）+ 红石信号强度 + 相对/绝对坐标。

## 10.3 只采集红石相关方块

半径 3 有 343 个方块。全量采集会混入大量噪音
（草的生长阶段、台阶朝向等），让对比失去意义。

双重过滤：
1. 方块 ID 含关键词（button / lever / pressure_plate / redstone / repeater /
   comparator / piston / lamp / torch / observer / dispenser / dropper /
   hopper / door / trapdoor / rail / furnace / chest ...）
2. 或状态属性含 powered / lit / open / triggered / inverted / signal / attached / face

单条动作快照上限 96 个，防止存档膨胀。

## 10.4 降级链

检测是**增强**，不能因为检测失败就让功能不可用：

| 情况 | 行为 |
|---|---|
| 没有快照（录制时附近无红石方块） | 照常执行 |
| 探测器不可用（世界未加载） | 照常执行 |
| recollect 抛异常 | 照常执行 |
| `force` 参数 | 跳过检测，无条件执行 |
| 快照数量对不上 | 照常执行 |

## 10.5 提示（"告诉玩家等一下"）

- **录制开始**：说明会记录周围方块红石状态，及回放时会自动核对
- **录制结束**：报告「其中 N 个动作带状态检测」；
  若一个都没有则警告「可能附近没有可检测方块」
- **回放开始**：`正在检测机器状态，请稍候...`
- **检测到差异**：广播跳过原因、一致率（`3/5 处一致`）、
  差异示例（哪个方块、什么状态）、以及 `force` 用法

## 10.6 命令变化

```
/carpet ai run <任务名> [假人名] [force]
/carpet ai run all [假人名] [force]
```
`force` 跳过全部状态检测。

## 10.7 已知限制

1. **只检测主世界** —— `createProbe` 固定用 `server.overworld()`。
   下界/末地的机器会读到错误维度，判定为「不一致」而跳过。
   由于跳过是安全方向（不会反向操作），可接受。
   后续可在录制时记录维度 ID 来支持多维度。
2. **依赖 `BlockState.toString()` 格式** ——
   26.1 把 `getValues()` 的返回类型从 `Map` 改成了 `Stream<Value<?>>`，
   直接调用编译不过。改用解析 `toString()`，不依赖具体 API 形态。
   若 Mojang 改了格式，最坏结果是提取不到属性，退化为只看方块 ID 关键词。
3. **坐标用绝对坐标定位** —— 机器位置固定，不随执行者移动。
   相对坐标保留但暂未启用（未来若要做「同一任务在不同位置执行」会用到）。
4. **部分一致仍需人工判断** —— 只要有一处不一致就整体跳过，
   可能因无关方块变动导致该执行的没执行。此时提示会给出 `force` 用法。

---

# 十一、全维度检测 + 假人命名规范

## 11.1 全维度状态检测（去掉"只测主世界"限制）

### 改动

| 位置 | 改动 |
|---|---|
| `BlockSnapshot` | 新增 `dimension` 字段；序列化时**仅非主世界才写**，保持存档精简 |
| `BlockProbe` | `collect` / `recollect` 都带维度参数；新增 `isDimensionLoaded` |
| `LevelBlockProbe` | 持有 `MinecraftServer`（不再是单个 level），按维度 ID 解析世界 |
| `TaskRecorder` | `sample()` 带维度参数 + `setDimension()`；快照记录所属维度 |
| `AiAgentMod` | `createProbe` 直接传 server，不再固定 overworld |

### 为什么用遍历而不是 `server.getLevel(key)`

构造 `ResourceKey` 需要 `Registries.DIMENSION` 与 `ResourceLocation`，
这两个类在 26.x 的包名/方法名仍有变数，直接调用有编译风险。

改为遍历 `server.getAllLevels()`，比对 `level.dimension().identifier().toString()`，
只依赖 `MinecraftServer` 与 `ServerLevel` 两个稳定 API。
维度数量通常只有个位数，遍历开销可忽略。

### 内存安全

探测器持有 `MinecraftServer`，但**每次读取都现取 level，绝不缓存 ServerLevel**。
世界会因切换/卸载变化，缓存会读到已卸载的世界并阻止其回收。

### 维度未加载的单独提示

维度不存在导致的跳过，和"机器状态已变"导致的跳过，对玩家意义完全不同。
现在会分别提示：

```
§6[状态检测] 跳过：维度 the_nether 当前未加载，无法核对现场
§8  加载该维度后重试，或用 /carpet ai run <名> force 强制执行
```

## 11.2 假人专属前缀与复用

### 问题

Carpet 假人是**服务器级共享资源**。若直接用玩家给的名字（如 `bot1`），
可能撞上他人已有的假人 —— 轻则把人家的假人传送走，重则替他执行破坏性操作。

另外每个假人都会在存档 `players/` 目录生成一份数据（背包、位置、状态）。
每次任务新建假人会让存档随任务次数无限膨胀。

### 方案：FakePlayerNaming

**前缀 `ai_`** —— 所有假人名自动加前缀，一眼可辨，且可用
`/player ai_* kill` 批量清理。

**normalize 幂等** —— `bot1` 与 `ai_bot1` 得到同一个名字 `ai_bot1`。
这是复用的前提：同一输入永远得到同一名字，才能命中已召唤的假人。

**清洗与截断** —— 只保留字母数字下划线；首字母必须是字母；
总长 ≤ 16（MC 玩家名上限）。

**复用机制** —— Carpet 的 `/player X spawn` 在 X 已存在时**直接复用**，
不重置数据、不新建存档条目。因此无条件 spawn 就能实现复用，
无需额外维护"已召唤列表"（那还会与真实状态不同步）。

### 新增命令

```
/carpet ai bot info                查看命名规则
/carpet ai bot list                说明
/carpet ai bot spawn <名>          召唤（已存在则复用）
/carpet ai bot stop <名>           停止当前动作
/carpet ai bot kill <名>           移除（会清背包数据）
```

## 11.3 残留风险

1. **跨维度录制时假人需手动传送** —— 假人不会自动跨维度。
   若任务跨维度，需要在任务里显式包含传送步骤，否则假人留在原维度。
2. **维度 ID 依赖数据包** —— 自定义维度的 ID 由数据包决定，
   若数据包被移除，该维度的快照会判定为"维度未加载"而跳过（安全方向）。
3. **假人前缀可能与他人冲突** —— 若服务器上已有别人用 `ai_` 开头的假人，
   仍可能撞名。可通过修改 `FakePlayerNaming.PREFIX` 常量解决。
