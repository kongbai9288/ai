# 假人智能 AI Agent

Minecraft **26.2** + Fabric + Carpet 的附属模组。让 AI 通过 Carpet 命令驱动假人执行任务。

> **当前阶段**：M1–M5 全部完成（AI 配置 / 任务录制回放 / AI 对话引擎 / 长期任务 / 机器管理）。
> **运行环境**：客户端（单机世界内的集成服务端）。后续会迁移到独立服务端，代码结构已按此预留。

---

## 版本基线

| 组件 | 版本 | 说明 |
|---|---|---|
| Minecraft | **26.2** | Chaos Cubed，2026-06-16 发布 |
| Java | 25 | MC 26.1+ 强制要求，不可降级 |
| Fabric Loader | **0.19.3**（下限）/ 0.19.5（推荐） | 需与 gradle.properties 的 `loader_version` 一致 |
| Fabric Loom | 1.17-SNAPSHOT | 26.1+ 插件 id 为 `net.fabricmc.fabric-loom` |
| Fabric API | 0.160.0+26.2 | 本模组**未直接调用**，仅编译期对齐，运行时非必需 |
| Carpet | 26.2 | Modrinth Maven 坐标 `maven.modrinth:carpet:26.2` |

> ⚠️ **版本基线唯一真相是 `gradle.properties`**。README 只是说明，一旦两处冲突以 gradle.properties 为准。
>
> ⚠️ **`fabric.mod.json` 的 `minecraft` 不要写 `~26.2`** —— `~` 在 Fabric semver 中等价于 `>=26.2.0 <26.3.0`，
> 只要版本字符串带 hotfix 后缀或格式与预期不同，就会误判成"旧版本"而拒绝加载。已改为 `>=26.2`。

**版本号不要自行改动** —— 26.2 是 2026 年的编号体系（YY.D 格式），不是 1.21.x 的延续。

**版本号不要自行改动** —— 26.2 是 2026 年的编号体系（YY.D 格式），不是 1.21.x 的延续。

---

## 安全模型（必读）

本模组会执行来自**不可信来源**的命令 —— AI 模型的回复，以及玩家录制后可能被他人回放的任务。
因此防提权是核心设计，共四道防线：

| 防线 | 位置 | 作用 |
|---|---|---|
| 永久黑名单 | `PermissionGuard.FORBIDDEN_ROOTS` | `op`/`ban`/`stop`/`fill` 等永不放行 |
| 命令白名单 | `PermissionGuard.ALLOWED_ROOTS` | 默认只允许 `player` 等假人/只读命令 |
| **权限降权** | `PermissionGuard.clamp` | 把命令源压到**任务开启者**的等级再派发 |
| 操作审计 | `Auditor` | 记录每条命令的处置结果，可事后追溯 |

> ⚠️ **降权这道防线以前是失效的，而且坏了两处**：
>
> 1. `PermissionGuard.clamp()` 曾因「26.1 后找不到构造权限集的 API」
>    而直接 `return source`（原样返回 OP 命令源）。26.2 的正确写法是
>    `source.withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level)))`。
> 2. **`ChatTrigger`（AI 对话路径）根本没调用 `clamp()`** —— 它收下了 `level`
>    参数却直接 `server.createCommandSourceStack()`，即以等级 4 执行。
>    这是最危险的一处：AI 回复属于完全不可信输入。
>
> 两处均已修复。若权限 API 再变动，`clamp()` 会退回 `NO_PERMISSIONS`
> （宁可命令因权限不足失败，也绝不静默放行）。

### 另一条提权路径：回放借录制者的权限

回放执行的命令**原先用 `task.permLevel()`（录制者的权限等级），而不是执行者的**。
单人场景无感，但多人服务器上是直接的提权路径：

> OP 录过某个任务 → 任何普通玩家 `/carpet ai run <那个任务>` → 命令以等级 4 执行。

`give` / `tp` / `gamemode` 等都在白名单内，因此这条路径是真实可利用的。
机器开关同理：低权限玩家 `machine add` 引用高权限者录制的任务即可借权。

**修复**：实际生效等级改为 `min(录制者, 执行者)` —— 执行者无法借此抬高自己的权限。
（`TaskRunner.start` 改为接收执行者的 `CommandSourceStack`，从中断出权限与名字，
所有命令入口均已传入。）

> 顺带修掉一个同源的功能 bug：不指定假人时，移动/视角命令拼的是 `tp @s ...`。
> 但投递器用的是 `server.createCommandSourceStack()`（**没有实体**的服务器级 source），
> `@s` 永远无法解析 —— 「不填假人就驱动执行者自己」这条路其实从未真正工作过。
> 现在改为直接写执行者的名字。

### 永久黑名单曾被一条白名单命令完全绕过

`PermissionGuard` 只校验**命令根**，而 `/execute` 能把任意命令当参数吞掉：

```
op attacker                -> root=op      -> 黑名单拦截 ✅
execute run op attacker    -> root=execute -> 白名单放行 ❌
```

`execute` 当时被列在「只读探测」组里（和 `list`/`data`/`scoreboard`/`tag` 一起）。
只要它在白名单里，**整张永久黑名单形同虚设** ——
`execute run fill ...`、`execute run stop`、`execute run function ...` 全部放行，
嵌套（`execute run execute run op`）自然也拦不住。

**修复**：`check()` 在命令根放行后追加语义校验 ——
对 `/execute` 按 `run` 分段递归校验内部命令；对 `data`/`scoreboard` 收窄到只读子命令。

> 业界做法参考：fabric-command-hider 等权限模组是**遍历整棵 Brigadier 命令树**逐节点校验，
> 而不是只看根。本类校验发生在派发前、没有命令树上下文，因此用递归分段近似达到同样效果。
> 副作用是参数里恰好出现单词 `run` 时可能误拦（刻意选择「误拦」而非「漏拦」）。

### 命令投递器现在是最后一道兜底

闸门原先只在各调用点校验，新调用点一旦漏校验，不可信命令就直达派发。
现在 `AiAgentMod.createCommandSink` 内部也会过一次 `PermissionGuard`，任何路径都绕不开。

### 命令策略：默认拒绝，服主手动开启

**其他模组注册的命令，默认全部拒绝。** 本模组无法预判第三方命令的破坏力，
放行等于把其他模组的权限体系也一起暴露给 AI。实测默认拦截：

```
home / rtp / warp / money / back / sethome / tpa / nick / fly ...
→ 命令 /xxx 不在允许清单内（其他模组的命令默认拒绝）
```

服主确认安全后逐条开启（需要权限 3+，控制台权限 4 天然满足）：

```
/carpet ai policy                     查看当前策略
/carpet ai policy allow <命令根>       额外放行（如 home）
/carpet ai policy deny  <命令根>       额外禁用（可收紧内置白名单，如禁掉 give）
/carpet ai policy remove <命令根>      移除自定义规则，回到默认
/carpet ai policy reset               清空全部自定义规则
```

策略持久化在存档 `aiagent/aiagent_policy.json`。

> ⚠️ **永久黑名单不可被 `allow` 覆盖** ——
> `op` / `stop` / `fill` / `setblock` / `function` 等在 `FORBIDDEN_ROOTS` 里的命令，
> 即使服主显式 `allow` 也会被拒绝。这是整个安全模型的底线，避免手滑撕开缺口。
>
> ⚠️ **策略绝不能被 AI 修改** —— 它是「闸门之上的闸门」。
> `checkCarpet` 只放行 `carpet ai machine`，AI 调用 `carpet ai policy` 会被拦截；
> 命令本身也要求权限等级 3+。否则 AI 一句 `policy allow op` 就能撕掉全部防线。

### 与 Carpet 的对齐问题（本轮重点）

之前的实现有几处**根本没对上 Carpet 的 `/player` 语法**，功能看着在跑实则空转：

| 问题 | 说明 |
|---|---|
| **`player <名> tp` 不存在** | Carpet 的 `/player` 子命令只有 `spawn/kill/shadow/move/look/turn/use/attack/jump/drop/.../stop`，**没有 `tp`**。回放的移动动作 100% 失败。改用原版 `/tp <名> x y z`（假人是真实玩家实体，原版 tp 有效） |
| **回放前不召唤假人** | 直接发 `player X use`，假人不存在则全部失败。现在回放启动时会先发一次 `player X spawn`（Carpet 对已存在的假人会复用，不会新建存档数据） |
| **假人名前缀对不上** | 模组召唤时统一加 `ai_` 前缀，但 AI 提示词示例写的是 `player bot1 use` —— AI 照着学会去操作**别人的假人**。现在提示词改用 `ai_bot1`，并在闸门里**强制校验 `/player` 的目标必须带 `ai_` 前缀** |
| **`/player ai_* kill` 是假的** | Carpet **不支持名字通配符**，旧注释误导。前缀只用于标识归属，批量清理需自行遍历 |
| **`spawnCommandIfNeeded` 不存在** | 类注释引用了一个根本没实现的方法 |

**新增的 `/player` 目标校验**（实测）：

```
player bot1 use continuous      -> 拦截（裸名，可能打错目标）
player Steve attack continuous  -> 拦截（真实玩家！）
player Steve kill               -> 拦截
player ai_bot1 use continuous   -> 放行
```

这条不只是防误伤 —— `/player` 能作用在**真实在线玩家**身上，
不校验等于允许 AI 对服务器上任何人下发动作指令。

> ⚠️ **老任务可能受影响**：录制任务里若含裸名 `player bot1 ...`，回放时会被拦截。
> 这是有意为之，请重新录制或用 `setstate` 校正。

**其他对齐**：`bot spawn/stop/kill` 之前用 `server.createCommandSourceStack()`（等级 4）
直接派发，会绕过 Carpet 的 `commandPlayer` 规则（默认 `ops`）——
本没有 `/player` 权限的人也能召唤/杀掉假人。现在改为降权执行，且命令要求权限 2。
`machine` 节点同样补上权限要求。

### 假人生存保障（出生点 / 怪物 / 死亡）

假人是**真实玩家实体**，会死、会被推、会掉虚空。回放期间任一环节出问题，
后续命令都会「目标不存在」而**静默失败** —— 看着在跑实则空转。

| 风险 | 处理 |
|---|---|
| **出生点被破坏**（基岩挖穿/填岩浆/封死） | spawn 显式带录制坐标 `at x y z`，不落到世界出生点 |
| **Carpet spawn 是异步的** | spawn 后等 5 刻再发动作。官方 wiki：*"Profile resolution is async; name is marked as spawning during fetch"* |
| **假人被打死 / 掉虚空** | 每 100 刻保活重发一次 spawn —— 还在就复用，不在就复活 |
| **怪物干扰 / 摔落 / 饥饿** | spawn 后给抗性 255 + 饱和 + 防火 |
| **重生动量导致位置偏移** | 保护生效后再 `tp` 回精确坐标（Carpet #1058：击退会在重生时生效） |
| **服务端叠加前缀**（`ai_01` → `bot_ai_01`） | 见下节 |

> ⚠️ **已知不受本模组控制**：Carpet 在**未加载区块**重复 spawn 有死锁风险
> （gnembon/fabric-carpet#2087，低核心数 VM + 慢 I/O 才触发）。
> 表现为服务器 Watchdog 崩溃。若在受限环境跑，尽量在常加载区域使用假人。

### 服务端给假人名叠了别的前缀（bot_ai_01）

有些服务端 / 其他模组会给假人名**强制叠加自己的前缀**：本模组生成 `ai_01`，
最终实体却叫 `bot_ai_01`。此时按「必须以 `ai_` 开头」判定会全部失败。

双重兼容：

1. **自动兜底** —— `isOurs` 先 `startsWith(prefix)`，失败再用 `contains(prefix)`。
   实测 `bot_ai_01` 在默认配置下就能正确识别。
2. **显式配置** —— 服主可把前缀设成最终形式：
   ```
   /carpet ai policy botprefix bot_ai_
   ```
   持久化到 `aiagent/aiagent_policy.json`。

前缀会**被拼进命令**，因此校验为 `^[A-Za-z0-9_-]{1,12}$` ——
实测 `ai_;op x`、`ai_x;stop` 这类注入前缀全部被拒。
真实玩家（`Steve` / `Notch`）无论前缀怎么配都仍被拦截。

### 白名单命令的「目标」此前完全不受限（可作用于任意玩家）

白名单按**命令根**放行，但这些命令的**作用对象是谁**此前完全自由。
放行 `give` 本意是「给假人发工具」，实测（修复前）以下全部放行：

```
give Steve diamond 64                    -> 给任意玩家刷物品
give @a netherite_block 64               -> 给全服刷
gamemode creative Steve                  -> 把玩家变创造
tp Steve 0 -64 0                         -> 传玩家进虚空
effect give Steve minecraft:poison 9999  -> 毒杀玩家
```

这就是「有人使坏」最直接的入口 —— 只需诱导 AI 输出这类命令。

**修复**：对带目标参数的命令强制「目标必须是本模组的假人」，且**拒绝一切选择器**：

| 命令 | 目标位置 |
|---|---|
| `give <target> <item>` | 第 1 段 |
| `tp / teleport <target> <pos>` | 第 1 段 |
| `clear [target]` | 第 1 段 |
| `gamemode <mode> [target]` | 第 2 段（可缺省） |
| `effect give <target> <effect>` | 第 3 段 |

> 选择器（`@a` / `@e` / `@p` / `@s`）**一律拒绝** ——
> `give @a ...` 等于给全服刷物品。必须写明具体的 `ai_` 假人名，范围可控且可审计。

**B. 让假人干破坏性动作**（`player ai_bot1 attack continuous`）是**设计内的** ——
假人持续攻击/使用本身就是录制回放的核心能力。风险由**权限等级**兜底：
Carpet 的 `commandPlayer` 默认 `ops`，降权后普通玩家触发的这类命令会执行失败。

### 幻翼（假人永不睡觉 → 必然招幻翼）

幻翼生成条件：**玩家 3 游戏日（72000 刻）未上床睡觉**。**假人永远不会睡觉**，
所以长期挂机必然招来幻翼，且它会**持续**生成（幻翼生成时**无视敌对生物上限**）。

抗性 255 挡得住伤害，但挡不住：持续骚扰、被击退位移（后续 `use` 全部打偏）、占用服务器资源。

**推荐做法** —— 从源头关闭（一键）：

```
/carpet ai bot phantom          查看状态
/carpet ai bot phantom off      关闭幻翼生成
/carpet ai bot phantom on       恢复
```

等价于 `/gamerule spawn_phantoms false`（Java 26.2 新名，原 `doInsomnia`）。
**注意这是全服规则**，关掉后所有玩家都不会因失眠刷幻翼。

> **为什么不做「被打死后自动复活重置」**：死亡确实能重置 insomnia 计时，
> 但 Carpet 假人死亡 = 掉线 + 掉落物品，代价太大。
>
> **为什么不每刻 `tp` 回出生点**：那会把假人钉死，
> 直接摧毁回放轨迹（我实现过一次，发现后已撤回）。

### 其他模组的 carpet 扩展命令

Carpet 扩展（TIS Carpet Addition、GCA、Carpet-Org-Addition 等）注册的命令
（`manipulate` / `removeentity` / `playerManager` / `playerAction` 等）
**默认全部拒绝**，需服主 `policy allow`。

`policy allow` 时会区分提示：
- 内置高危命令（`give`/`tp`/`summon`…）→ 提示会改变世界
- **内置清单之外**的命令 → 额外提示「破坏力无法预判，放行等于把那个模组的权限也交给 AI」

Carpet 规则修改（3 段式，如 `/carpet commandScript true`）**一律拦截** ——
`commandScript` / `commandScriptACE` 能开启 Scarpet 任意代码执行，
放行了等于把服务器完全交出去。实测已拦截。

### HTTP 客户端

| 风险 | 处理 |
|---|---|
| 响应体过大 | 改为 `ofInputStream` + 边读边限流（8MB）。原先的 `ofString()` 会先把整个响应读进内存，**之后再判断大小已经晚了** |
| 协议 | 只放行 `http` / `https` |
| 内网地址 | **故意不禁** —— 大量玩家用局域网 Ollama / vLLM（192.168.x.x），一刀切会让本地部署不可用。风险由服主自行判断 |
| 密钥 | 密钥明文存在存档 JSON 里；展示一律走 `maskedApiKey()`，日志与异常绝不输出密钥 |

### 密钥与审计的边界

- 存档文件 `aiagent/aiagent_profiles.json` 里**明文保存 API 密钥**。分享/上传存档前请先 `api clear`。
- 审计日志对 `ai` / `aiagent` 开头的命令只记录长度不记录原文 ——
  因为 `/carpet ai api set ... <密钥>` 会把密钥写进日志。

**限流**：聊天触发没有权限和消耗门槛，玩家刷屏「你好ai」即可持续打爆自己的 API 配额
并在服务端堆积并发 HTTP 请求。已加 3 秒/人的最小请求间隔（记真实时间而非游戏刻 ——
服务器卡顿时游戏刻推进很慢，用刻限流形同虚设）。

**审计日志**记录：游戏刻、命令（脱敏）、权限等级、来源（AI/回放/调度）、处置结果。
`ai` 开头的命令只记录长度不记录原文 —— 因为 `/carpet ai api set ...` 会携带 API 密钥。

## 已实现的命令

```
/carpet ai                                     帮助
/carpet ai api                                 查看我的 AI 配置
/carpet ai api set <地址> <模型> [密钥]          配置 AI 接口
/carpet ai api timeout <毫秒>                   设置超时（1000-120000）
/carpet ai api temp <0-2>                      设置温度
/carpet ai api clear                           清除我的配置
/carpet ai api list                            列出所有已配置玩家（需权限 2）
/carpet ai perm                                查看 AI 可执行命令范围
/carpet ai audit [条数]                        查看命令审计日志（需权限 2）
/carpet ai policy allow|deny <命令根>          开关命令，默认拒绝其他模组命令（需权限 3）
```

### 任务录制与回放

```
/carpet ai rec <任务名>            开始录制（记录移动 / 视角 / 你执行的命令）
/carpet ai rec stop                结束并保存
/carpet ai rec cancel              放弃录制
/carpet ai rec status              查看录制状态

/carpet ai task list               列出所有任务
/carpet ai task info <任务名>       查看任务详情
/carpet ai task remove <任务名>     删除任务

/carpet ai run <任务名> [假人名]     回放任务（不填假人则驱动执行者自己）
/carpet ai run all [假人名]         回放所有任务
/carpet ai run list                查看进行中的回放
/carpet ai run stop                停止所有回放
```

**录制会记录**：移动（超过 0.15 格才记）、视角（超过 2 度才记）、你执行的命令。
**不会记录**：本模组的 `/carpet ai ...` 命令（否则回放会再次触发录制/回放，形成自指循环）。

单个任务上限 10000 个动作；玩家中途下线会自动保存已录内容。

### 配置示例

```
/carpet ai api set https://api.openai.com/v1 gpt-4o-mini sk-xxxxxx
/carpet ai api set http://localhost:11434/v1 qwen2.5          # 本地 Ollama，无需密钥
/carpet ai api set https://api.deepseek.com/v1 deepseek-chat sk-xxxxxx
```

每个玩家的配置相互隔离，各用各的 —— 谁发起任务就用谁的 API。

### 与 AI 对话

```
你好，ai 让 bot1 开始挖矿        ← 聊天直接触发
/carpet ai ask <话>              ← 命令方式（聊天触发失效时的兜底）
```

AI 会返回约定的 JSON 并执行，格式：

```json
{
  "longRunning": true,
  "deadline": -1,
  "fakePlayer": "bot1",
  "commands": ["player bot1 use continuous"],
  "reply": "已开启"
}
```

`deadline` 为 `-1` 表示一直执行，正整数表示 N 秒后自动停止。
字段也接受中文键名（`是否长期执行` / `执行终止时间` / `假人名` / `执行命令`）。

### 机器管理

先录两个任务（怎么开 / 怎么关），再定义成一台机器：

```
/carpet ai rec 刷石机开    ... /carpet ai rec stop
/carpet ai rec 刷石机关    ... /carpet ai rec stop
/carpet ai machine add 刷石机 刷石机开 刷石机关

/carpet ai machine offall        ← 一键关闭所有机器
/carpet ai machine stopall       ← 彻底停止(关机器+停长期任务+停回放)
```

### ⚠️ 机器状态与手动操作

录制的开关任务是**动作**（按一下按钮），不是**状态设置**（设成关）。
对按钮/拉杆型机器，在已关闭状态下再执行一次"关闭动作"等于**又按一次 = 打开**。

因此机器有三态：`开启 / 关闭 / 未知`。

- 已经是目标状态时，`on` / `off` 会**跳过**并提示，不会重复切换
- `offall` 只处理"状态不是关闭"的机器
- **手动开关机后模组无法感知**，需要用 `setstate` 校正：

```
/carpet ai machine setstate 刷石机 off     ← 告诉模组它现在是关的
/carpet ai machine setstate 刷石机 unknown ← 不确定就标未知，下次操作会提示确认
```

状态存进 `aiagent/aiagent_machines.json`，随存档持久化。

### 假人命名

所有假人名会自动加 `ai_` 前缀，避免误操作服务器里其他人已有的假人：

```
bot1  →  ai_bot1     输入 ai_bot1 也得到 ai_bot1（幂等）
```

同一名字会**复用已召唤的假人**，不会重复新建存档数据，减少存储占用。

```
/carpet ai bot info                查看命名规则
/carpet ai bot spawn|stop|kill <名>
```

### 红石状态检测

录制"关闭机器"这类任务时，会**同时记录执行命令前周围方块的红石状态**
（按钮/拉杆/红石线/活塞/灯等的 powered、lit、信号强度）。

回放时会自动核对现场：

- 状态与录制时**一致** → 说明还没执行过 → **执行**
- 状态**不一致** → 说明机器已经动了 → **跳过**（并提示差异原因）

这是防止"机器已关、说关闭反而打开"的最后一道防线（在状态跟踪之外的物理验证）。

```
/carpet ai run <任务名> [假人名] [force]
```
`force` 跳过状态检测，无条件执行。

**支持全维度**：录制时会记录命令所属维度（主世界/下界/末地/自定义维度），
回放时到对应世界核对，跨维度机器也能正确检测。

录制结束时会报告有多少个动作带状态检测；如果一个都没有，
说明录制位置附近没检测到红石元件，需要靠近机器的控制面板再录。

---

## 防提权设计

AI 的回复属于**完全不可信输入**。四重防线：

1. **永久黑名单** — `op`/`deop`/`ban`/`stop`/`whitelist`/`datapack`/`function` 等永不放行
2. **自指阻断** — 禁止 AI 触及 `ai` 命令本身，防止它自己改 API 地址或密钥形成提权闭环
3. **命令白名单** — 默认只允许 `player` 等假人/只读命令，不在清单内一律拒绝
4. **降权执行** — 即使放行，也把命令源压到**执行者**的权限等级再派发，而非 OP 权限

> ⚠️ 上一版文档写的是「用**任务开启者**的权限等级执行」，这正是本仓库曾经存在的
> 提权漏洞（回放时用录制者权限）。当前实现是 `min(录制者, 执行者)`，
> 详见上面的「安全模型（必读）」。

另外：禁止 `;` `&&` `||` 和换行拼接（防绕过单条校验），单条命令上限 512 字符，单次上限 16 条。

---

## 构建

```bash
./gradlew build          # 需要 Java 25
```

或直接用 GitHub Actions（push 到 main 自动构建，产物在 Actions 页下载）。

## 安装

1. 装 Fabric Loader 0.19.5+（MC 26.2）
2. 把 `fabric-carpet-26.2+v260616.jar` 和本模组的 jar 一起丢进 `mods/`
3. 进游戏，`/carpet ai` 看帮助

---

## 目录结构

```
src/main/java/com/kongbai/aiagent/
├── AiAgentMod.java          入口 + Carpet 扩展生命周期
├── command/
│   └── AiCommand.java       /carpet ai 命令树
├── config/
│   ├── AiProfile.java       单个 AI 配置（不可变值对象）
│   └── ProfileManager.java  配置表 + 持久化
└── util/
    ├── JsonUtil.java        JSON 读写（原子写、失败回落）
    └── PermissionGuard.java 权限闸门（防提权核心）
```

## 后续模块

| 模块 | 内容 | 状态 |
|---|---|---|
| M1 | 骨架 + AI 接入配置 + 权限闸门 | ✅ |
| M2 | 任务录制/回放（`/carpet ai rec` / `task` / `run`） | ✅ |
| M3 | AI 对话与执行引擎（`/carpet ai ask` + 聊天触发） | ✅ |
| M4 | 长期任务调度（JSON 事件格式，`/carpet ai sched`） | ✅ |
| M5 | 机器管理与一键关停（`/carpet ai machine`） | ✅ |
| M6 | 服务端迁移 | 待做 |

代码审查报告：[REVIEW_M1](REVIEW_M1.md) / [REVIEW_M2](REVIEW_M2.md) / [REVIEW_M3_M5](REVIEW_M3_M5.md)

## 许可

MIT
