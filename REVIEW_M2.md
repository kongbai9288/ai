# M2 代码审查报告

**模块**：任务录制 / 回放
**审查日期**：2026-09-11
**规模**：14 个 Java 文件，3554 行（M2 新增 8 个）

---

## 一、本模块新增文件

| 文件 | 职责 |
|---|---|
| `task/RecordedAction` | 单个动作（不可变），含 MOVE/LOOK/COMMAND 三型 |
| `task/RecordedTask` | 任务（不可变），绑定创建者与权限等级快照 |
| `task/TaskRecorder` | 单个录制会话（唯一的可变状态） |
| `task/RecorderManager` | 录制会话管理 |
| `task/TaskRegistry` | 任务存储 + 持久化 |
| `task/TaskRunner` | 回放调度器 |
| `task/CommandSink` | 命令投递接口（隔离服务器引用） |
| `mixin/CommandsMixin` | 拦截命令执行用于录制 |

---

## 二、审查中发现并已修复的缺陷

### 🔴 P0-1 内存泄漏：TaskRunner 长期持有 CommandSink → MinecraftServer

**位置**：`TaskRunner.start()` 最初签名

**问题**：最初设计把 `CommandSink` 存进 `Playback` 实例。
而 sink 的实现需要 `server.getCommands()` 才能派发命令，
等于让**长生命周期的回放对象间接持有 MinecraftServer** ——
服务器关闭后整条引用链（服务器 → 所有世界数据）都无法被 GC 回收。

**修复**：改为 `tick(long currentTick, CommandSink sink)` 时临时传入。
`Playback` 现在只持有不可变的 `RecordedTask`、字符串和基本类型。

**这是本模块最严重的问题** —— 它不会立刻报错，只在长时间运行、反复开关世界后才显现，最难排查。

---

### 🟠 P1-2 静默产生脏数据：序列化 switch 缺 default

**位置**：`RecordedAction.toJson()`

**问题**：`switch(type)` 是**语句形式**（无 `yield`），覆盖三个枚举值。
若将来给 `Type` 新增枚举值，这里会**静默跳过**，
写出一个只有 `type` 字段、没有任何数据的 JSON。
反序列化时变成"类型正确但坐标全 0"的动作，
回放时把假人 tp 到 `(0,0,0)` —— 破坏性极强且难以定位。

**修复**：加 `default -> throw new IllegalStateException(...)`。
显式崩溃优于静默产出脏数据。

（另两处 `switch` 是**表达式形式**，Java 编译器强制要求穷尽，
新增枚举值时会在编译期报错，因此不需要 default。）

---

### 🟡 P2-3 设计冗余：为读一个 long 定义嵌套 record

**位置**：`RecordedAction.fromJson()` 原实现

**问题**：为了安全读取 `tick` 字段，定义了一个嵌套 `record JsonElementHolder(long)`。
一个内部传输用的包装类型，增加了阅读负担，也没带来任何类型安全收益。

**修复**：改为 `readLongOr(obj, key, fallback)` 直接返回 `long`，失败回落默认值。

---

### 🟡 P2-4 三个未使用的 import

`CommandSink` 的 `Nullable`、`TaskRegistry` 的 `UUID`、`PermissionGuard` 的 `CommandSyntaxException`。
已清理。虽然不影响编译，但在严格 CI 下会触发警告。

---

## 三、边界情况定义

| 类 / 方法 | 输入边界 | 行为 |
|---|---|---|
| `TaskRecorder.sample` | 坐标为 NaN/Infinity | **跳过本次采样**。NaN 若进入任务，回放时 tp 到 NaN 会破坏实体 |
| `TaskRecorder.recordCommand` | 命令为 null / 空白 / 本模组命令 | 返回 false，不记录 |
| `TaskRecorder.finish` | 动作为空 | 返回 `null`（空任务无保存价值） |
| `RecordedTask.of` | 名称非法 | 抛 `IllegalArgumentException`，命令层已前置校验 |
| `RecordedTask.of` | 动作数超 `MAX_ACTIONS`(10000) | 截断尾部，宁可丢数据也不撑爆内存 |
| `RecordedAction.fromJson` | 字段缺失/类型错误 | 返回 `null`，**只跳过该条**，不影响整个任务 |
| `TaskRunner.start` | 任务为空 / 并发达上限(32) | 返回 `null` |
| `TaskRunner.tick` | sink 为 null | 停止全部回放（不能让它们永远卡在列表里） |
| `TaskRunner.step` | 单个回放抛异常 | 捕获、记消息、移除该回放，**不影响其他回放与游戏刻** |
| `CommandsMixin` | 任何异常 | 全部吞掉。录制逻辑绝不能阻断原版命令执行 |

### 采样策略（防止数据爆炸）

- 位置：移动超过 **0.15 格**才记录（用平方距离比较，避免开方）
- 视角：变化超过 **2 度**才记录
- 静止站立时几乎不产生数据；跑动约每刻 1 条
- 上限 10000 条，达到后自动结束并保存

---

## 四、内存安全

| 风险点 | 处理 |
|---|---|
| `TaskRunner` 持有 sink → server | ✅ 已修复，改为 tick 时临时传入 |
| 录制会话泄漏 | ✅ `abortAll()` 在 `onServerClosed` 调用 |
| 玩家下线时会话残留 | ✅ tick 中检测 `getPlayer(uuid) == null` → 结束并**保存**（不丢弃） |
| 回放实例泄漏 | ✅ 完成后自动从 Map 移除；`stopAll()` 兜底 |
| 持有游戏对象 | ✅ 全部只存 UUID / String / 基本类型 |
| 静态可变集合 | ✅ 无 |

**关键约定**：`ProfileManager`、`TaskRegistry`、`RecorderManager`、`TaskRunner`
四个管理器都必须在 `onServerClosed` 里清理。新增任何 Map 缓存时照抄这套。

---

## 五、防提权在 M2 的落实

回放时执行的命令**全部**经过 `PermissionGuard.check()`：

1. **录制的命令** —— 原样执行前先过白名单。即使有人手工编辑存档 JSON 塞入恶意命令，也会被拦下
2. **位置/视角命令** —— `tp` 与 `player` 也在白名单内，但同样走 `check()`，
   统一校验路径，避免以后给 `tp` 加限制时漏掉这里
3. **降权执行** —— `CommandSink` 用 `withPermission(task.permLevel())` 构造命令源，
   用的是**录制时的权限快照**，不是执行者的当前权限

**为什么用录制时的快照**：如果录的时候是普通玩家、放的时候 OP 来操作，
用当前权限就等于把一个普通玩家录的任务提升到 OP 权限执行 —— 这正是提权漏洞。
用快照则行为可预测，也无法通过"让别人帮忙执行"来提权。

---

## 六、已知限制与风险

### ⚠️ 编译期仍未验证

沙盒 JDK 11，本项目需要 Java 25。以下依赖对 MC 26.2 API 的假设待 CI 确认：

1. `Commands.performCommand(ParseResults<CommandSourceStack>, String)` 的签名
   （按 1.21.11 的 mojmap 写的，26.2 可能变化）
2. `MinecraftServer.createCommandSourceStack()`
3. `Commands.performPrefixedCommand(CommandSourceStack, String)`
4. `ServerPlayer.getYRot()` / `getXRot()` 的方法名
5. `PlayerList.broadcastSystemMessage(Component, boolean)`

**mixin 已设 `require = 0`**：如果第 1 项签名对不上，mixin 会被静默跳过，
代价是"指令录制"失效，但位置/视角录制与回放仍可用。
这是有意的降级设计 —— 宁可少个功能，也不能让玩家进不去世界。

### ⚠️ 回放时 `@s` 等选择器的语义变化

录制的命令原样执行，但回放的命令源是**服务器级 source**（无玩家上下文）。
若录制了 `/give @s stone`，回放时 `@s` 无法解析。

**当前无解**（需要把执行者信息带入回放，会引入持有 Player 的风险）。
M3 会评估是否用"记录执行时解析后的结果"来规避。
在此之前，建议录制时避免依赖 `@s` 的命令。

### ⚠️ 任务名大小写

`MyTask` 与 `mytask` 视为同一任务（key 用小写）。
同名录制会**覆盖**旧任务（有提示）。若希望改成"拒绝覆盖"，需要加 confirm 参数。

---

## 七、给 AI 的交接说明

> 本段供后续对话中的 AI 实例阅读。**新开对话时必须重新确认**：

1. MC 版本 **26.2**，不是 1.21.x。改版本号前先问用户。
2. `RecordedAction` / `RecordedTask` 均**不可变**。要改请用 `withXxx()` 或重新 `of()`，不要加 setter。
3. `TaskRunner.tick()` **必须传 sink**，不要为了省事把 sink 存起来 —— 那会持有 MinecraftServer。
4. `TaskRecorder` 是本模块**唯一的可变状态**，用后必须 `finish()` 或 `abort()`。
5. 命令执行一律走 `PermissionGuard.check()` + `CommandSink`，不要绕过。
6. 四个管理器（`ProfileManager` / `TaskRegistry` / `RecorderManager` / `TaskRunner`）
   都要在 `onServerClosed` 清理。新增 Map 缓存时照抄。

**已完成**：M1（骨架+配置+权限）、M2（录制+回放）
**下一个**：M3 AI 对话与执行引擎

**不要做的事**：
- 不要改 `gradle.properties` 里的版本号
- 不要在 `RecordedAction`/`RecordedTask` 里持有游戏对象
- 不要让回放命令绕过 `PermissionGuard`
