# M1 代码审查报告

**模块**：项目骨架 + AI 接入配置 + 权限闸门
**审查日期**：2026-09-11
**规模**：6 个 Java 文件，1492 行

---

## 一、审查中发现并已修复的缺陷

### 🔴 P0-1 启动即崩溃：`Set.of()` 重复元素

**位置**：`PermissionGuard.java` 的 `FORBIDDEN_ROOTS`

**问题**：`"reload"` 在集合中出现了两次（分别在「服务器生命周期」和「数据/脚本执行」两行）。
`Set.of()` 对重复元素会抛 `IllegalArgumentException`，而这是**静态字段初始化**，
意味着类加载时就会抛 `ExceptionInInitializerError` —— 模组在 Fabric 加载阶段直接崩溃，
玩家看到的是一片红字，排查成本极高。

**修复**：删除重复项，并加了静态检查脚本（见第三节）。

**教训**：`Set.of()` 与 `Set.of(...)` 的可变参数版本行为不同，手写长列表时重复项极难靠肉眼发现。

---

### 🟠 P1-2 NPE / 类型异常风险：JSON 数值读取

**位置**：`AiProfile.fromJson()`

**问题**：原写法 `JsonUtil.path(obj, "timeoutMs").getAsInt()` 虽然判了 null，
但配置由玩家手改时可能把 `timeoutMs` 写成 `"快一点"`，`getAsInt()` 会抛
`NumberFormatException`，导致**整份配置加载失败**（不只是这一条）。

**修复**：新增 `readIntOr` / `readFloatOr` 两个私有辅助方法：
- 类型不符 → 回落默认值
- 数值非法 → 回落默认值
- 浮点额外处理 `NaN` / `Infinity`（这两个值会让后续 HTTP 超时计算静默失效）

---

### 🟡 P2-3 静默产生脏数据：`withTuning()` 的兜底逻辑

**位置**：`AiProfile.withTuning()`

**问题**：原实现在 `baseUrl` 为空时塞入 `"https://example.invalid"` 占位符。
这会让「未配置」的对象伪装成「已配置」，绕过后续的 `isConfigured()` 检查。

**修复**：改为直接抛 `IllegalStateException`，并在 `AiCommand` 侧捕获转成玩家提示。
显式失败优于静默降级 —— 权限相关路径尤其如此。

---

## 二、边界情况定义（每个类的契约）

| 类 | 输入边界 | 失败行为 |
|---|---|---|
| `JsonUtil.readTree` | 文件不存在 / 空文件 / 格式错误 | 返回 `null`，由调用方决定回落。**不抛异常、不吞异常** |
| `JsonUtil.writeTree` | 父目录不存在 | 自动创建；写入走「临时文件 + 原子移动」，失败不破坏原文件 |
| `AiProfile.of` | URL 非 http/https、key 超 512 字符、超时越界 | 抛 `IllegalArgumentException`，由命令层转玩家提示 |
| `ProfileManager.get` | 未 attach / 玩家未配置 | 返回 `null`（这是合法的「未就绪」状态，不是错误） |
| `ProfileManager.attach` | 传入 `null` | 记日志并忽略，**不覆盖已有的 saveDir** |
| `PermissionGuard.check` | 空串 / 含 `;` `&&` / 超长 | 返回拒绝原因字符串；放行时返回 `null` |
| `AiCommand.playerUuid` | 控制台或命令方块执行 | 返回 `null`，命令提示「只能由玩家执行」 |

**关键的 null 语义约定**：权限校验路径上出现 `null` 视为严重缺陷，
宁可抛 NPE 也不静默放行 —— 静默放行等于把服务器权限交给外部 API。

---

## 三、内存安全

### 风险点排查

| 风险类型 | 排查结果 |
|---|---|
| 静态可变集合缓存 | ✅ 无。仅有的静态集合是 `Set.of()` 不可变常量 |
| 持有 `MinecraftServer` 字段 | ✅ 无。服务器实例只在回调参数中出现，用完即走 |
| 持有 `ServerPlayer` 引用 | ✅ 无。`AiProfile` 只存 `UUID` 和 `String` |
| 文件流未关闭 | ✅ 无。全部使用 try-with-resources |
| 监听器未注销 | ✅ 无。本模块未注册事件监听器 |

### 最需要注意的一点：`ProfileManager.detach()` 必须被调用

`ProfileManager` 用 `ConcurrentHashMap<UUID, AiProfile>` 持有全部玩家配置。
若世界卸载时**不调用 `detach()`**：

1. 换世界后旧世界的配置残留在内存，串数据
2. 配置对象随玩家数持续增长，永不回收

当前已在 `AiAgentExtension.onServerClosed()` 中调用。**后续模块如果新增类似的
Map 缓存，必须同样在 `onServerClosed` 里清空** —— 这是本项目的硬性约定。

### 线程安全

- `AiProfile` 不可变 → 异步 AI 请求线程读取时无需加锁
- `ProfileManager` 用 `ConcurrentHashMap` + `volatile saveDir`
- 写盘在主线程同步执行（配置变更频率极低，不值得为它引入异步写盘复杂度）

---

## 四、已知风险与未决项

### ⚠️ 编译期未能验证

沙盒环境的 JDK 是 11，而本项目需要 Java 25，**无法在本地编译验证**。
以下三处依赖对 Carpet 26.2 API 的假设，需等 GitHub Actions 构建结果确认：

1. `carpet.api.CarpetExtension` 的包名（老版本在 `carpet.CarpetExtension`）
2. `CommandSourceStack.sendSuccess(Supplier<Component>, boolean)` 的签名
3. `net.minecraft.world.level.storage.LevelResource.ROOT` 的名称

**处理方式**：CI 构建失败会暴露具体错误，届时按编译器的实际提示修正，
不靠猜测硬撑。

### ⚠️ 命令挂载的覆盖风险

`/carpet` 节点由 Carpet 注册。若用 `dispatcher.register(literal("carpet")...)`
会**覆盖** Carpet 自身的节点，导致 `/carpet setDefault` 等原生功能全部失效。

当前实现是取出已有节点后 `addChild` 追加，并在重复注册时先 `removeIf` 再添加
（幂等，兼容 `/reload`）。

### 📌 设计决策待确认

- **白名单粒度**：当前 `ALLOWED_ROOTS` 允许 `give`/`tp`/`gamemode` 等。
  这些在「AI 自动控机器」场景下是否需要？如果不需要应当收紧，
  等 M3 实际接入后再根据真实用例调整。
- **配置存储位置**：当前存在 `存档目录/aiagent/aiagent_profiles.json`。
  API 密钥是**明文存储**的。单机场景可接受，迁移到多人服务端时应考虑加密或
  改为服务端统一配置。

---

## 五、给 AI 的交接说明

> 本段供后续对话中的 AI 实例阅读。

**新开对话时必须重新确认的事**（不要凭印象写代码）：

1. 本仓库的 Minecraft 版本是 **26.2**，不是 1.21.x。改任何版本号前先问用户。
2. `AiProfile` 是**不可变**的。要改参数请用 `withTuning()` 或重新 `of()`，不要加 setter。
3. `ProfileManager` 是单例，`attach`/`detach` 必须成对。新增 Map 缓存时照抄这套生命周期。
4. 权限校验一律走 `PermissionGuard.check()`，**不要自己写 `startsWith("op")` 之类的判断**。
5. `FORBIDDEN_ROOTS` 里必须保留 `"ai"` —— 少了它 AI 能改自己的密钥，形成提权闭环。

**当前已完成**：M1（骨架 + 配置 + 权限闸门）
**下一个要做**：M2 任务录制/回放

**不要做的事**：
- 不要擅自改 `gradle.properties` 里的版本号
- 不要在 `AiProfile` 里持有 `ServerPlayer`
- 不要让 AI 生成的命令绕过 `PermissionGuard`
