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
