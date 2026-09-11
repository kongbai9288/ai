# 假人智能 AI Agent

Minecraft **26.2** + Fabric + Carpet 的附属模组。让 AI 通过 Carpet 命令驱动假人执行任务。

> **当前阶段**：M1–M5 全部完成（AI 配置 / 任务录制回放 / AI 对话引擎 / 长期任务 / 机器管理）。
> **运行环境**：客户端（单机世界内的集成服务端）。后续会迁移到独立服务端，代码结构已按此预留。

---

## 版本基线

| 组件 | 版本 | 说明 |
|---|---|---|
| Minecraft | **26.2** | Chaos Cubed，2026-06-16 发布 |
| Java | 25 | MC 26.1+ 强制要求 |
| Fabric Loader | 0.19.5 | fabricmc.net/develop 推荐值 |
| Fabric Loom | 1.17-SNAPSHOT | 同上 |
| Fabric API | 0.160.0+26.2 | 同上 |
| Carpet | 26.2+v260621 | masa.dy.fi/maven |

**版本号不要自行改动** —— 26.2 是 2026 年的编号体系（YY.D 格式），不是 1.21.x 的延续。

---

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

AI 的回复属于**完全不可信输入**。三重防线：

1. **永久黑名单** — `op`/`deop`/`ban`/`stop`/`whitelist`/`datapack`/`function` 等永不放行
2. **自指阻断** — 禁止 AI 触及 `ai` 命令本身，防止它自己改 API 地址或密钥形成提权闭环
3. **降权执行** — 即使放行，也用「任务开启者」的权限等级构造命令源执行，而非 OP 权限

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
