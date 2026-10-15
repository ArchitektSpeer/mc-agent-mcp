# mc-agent-mcp

[English](README.md) | **简体中文**

一个 **Minecraft Fabric 模组**：在游戏客户端里运行 **MCP（Model Context Protocol）服务器**，
让 AI 智能体（Claude、DeepSeek 或任何 MCP 客户端）能看到世界并自己玩生存模式：走路、挖矿、
建造、战斗、进食、合成、熔炼、使用箱子和聊天。

| | |
|---|---|
| 游戏 | Minecraft `1.21.11`（Java 21） |
| 加载器 | Fabric Loader `0.19.5` + Fabric API `0.141.6` |
| 传输 | MCP *Streamable HTTP*，地址 `http://127.0.0.1:27890/mcp`，Bearer 令牌认证 |
| 协议 | MCP `2025-06-18`（工具、服务器 instructions、SSE 通知） |
| 运行端 | 仅客户端：操控的是**你自己的**玩家，单人和多人服务器都可用 |

## 功能

- **29 个工具**，覆盖感知、移动、交互、物品栏、合成和聊天（[工具列表](#工具)）。
- **为慢模型准备的生存反射**：模组每 tick（50 毫秒）自动防御、躲避苦力怕、进食、穿护甲、上浮换气，
  每轮要思考几秒的智能体不会在思考时被打死（[详情](#生存反射)）。
- **为大模型设计**：可直接阅读的 ASCII 方块地图、按距离排序的实体列表、能告诉你是否需要工作台的
  配方查询，以及会告诉智能体下一步该做什么的错误信息（例如“超出距离，请靠近”）。
- **符合生存规则**：挖掘耗时与工具速度一致，合成像玩家一样走配方书，强制检查交互距离，
  所有动作都是普通的客户端数据包。
- **高速感知**：按区块段扫描方块、紧凑的 `rle` 编码、精确的方块变化追踪并通过 SSE 推送。
- **服主可控**：只监听本机、Bearer 认证、`Origin` 校验、按类别开关权限、可单独禁用工具、速率限制。

## 快速开始

1. 安装 1.21.11 的 [Fabric Loader](https://fabricmc.net/use/) 和
   [Fabric API](https://modrinth.com/mod/fabric-api)。
2. 构建模组，把 jar 放进 `.minecraft/mods/`：
   ```bash
   ./gradlew build        # -> build/libs/mc-agent-mcp-<version>.jar
   ```
3. 启动一次游戏。模组会生成带随机 `authToken` 的 `config/mcagent.json`，并在日志里打印地址。
4. 连接 MCP 客户端：
   ```
   URL:     http://127.0.0.1:27890/mcp
   Header:  Authorization: Bearer <config/mcagent.json 里的 authToken>
   ```

**Claude Code**

```bash
TOKEN=$(grep authToken ~/.minecraft/config/mcagent.json | cut -d'"' -f4)
claude mcp add --transport http minecraft http://127.0.0.1:27890/mcp \
  --header "Authorization: Bearer $TOKEN"
```

进入世界后直接提要求，例如：*“砍一些橡木，做一把木镐，再挖 10 个圆石。”*
`initialize` 的响应里带有一段简短的玩法说明（`instructions`），客户端会把它放进模型上下文，
内容包括“观察 → 行动”循环、朝向角度、交互距离和合成方法。

其他客户端（通过 `mcp-remote` 的 Claude Desktop、Cursor、自建智能体等）使用相同的 URL 和请求头。

**DeepSeek Harness（DSH）**

[`dsh-plugin-minecraft`](dsh-plugin/README.zh-CN.md) 组合包让 DSH 免配置连上模组（自动找到端口和令牌，
包括 HMCL/PCL 的版本隔离目录）：

```bash
dsh plugin --profile web add dsh-plugin-minecraft
```

端到端实测：在 `dsh headless` 中运行的 DeepSeek V4 Flash，从空背包出发、身边有两棵橡树，
69 秒（17 步）内砍到原木并合成了工作台和木镐。

**示例智能体（DeepSeek 或任何 OpenAI 兼容 API）**

[`examples/deepseek_agent.py`](examples/deepseek_agent.py) 是一个独立的智能体循环（只用 Python 标准库）。
它每轮自动注入最新的 `observe`，控制上下文长度，并允许模型每轮调用多个工具，适合速度快、成本低的模型：

```bash
export DEEPSEEK_API_KEY=sk-...
python3 examples/deepseek_agent.py --model <服务商提供的模型 id> \
  --goal "砍木头，做一把木镐，然后挖 10 个圆石。"
# 其他 OpenAI 兼容 API 用 --base-url；.minecraft 不在默认位置时用 --config
```

**用 curl 冒烟测试**

```bash
TOKEN=$(grep authToken ~/.minecraft/config/mcagent.json | cut -d'"' -f4)
call() { curl -s -X POST http://127.0.0.1:27890/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$1"; echo; }

call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"observe","arguments":{}}}'
call '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_blocks","arguments":{"format":"layers","radius":9}}}'
```

## 配置

`config/mcagent.json`（位于游戏目录）每次启动都会带着全部选项重写一遍，新版本加的选项会自动出现。
请在游戏关闭时修改。

```json
{
  "enabled": true,
  "port": 27890,
  "bindAddress": "127.0.0.1",
  "authToken": "<自动生成>",
  "allowCommands": false,
  "disablePauseOnLostFocus": true,
  "permissions": { "perception": true, "movement": true, "interaction": true, "inventory": true, "chat": true },
  "disabledTools": [],
  "reflexes": { "autoDefend": true, "evadeCreepers": true, "chargeRanged": true, "autoEat": true,
                "autoArmor": true, "escapeFluids": true, "interruptOnDamage": true, "eatBelowFood": 14 },
  "rateLimits": {
    "global":      { "perSecond": 20,  "burst": 40 },
    "perception":  { "perSecond": 0,   "burst": 0 },
    "movement":    { "perSecond": 10,  "burst": 20 },
    "interaction": { "perSecond": 10,  "burst": 20 },
    "inventory":   { "perSecond": 20,  "burst": 40 },
    "chat":        { "perSecond": 0.5, "burst": 3 }
  }
}
```

| 键 | 含义 |
|---|---|
| `enabled` | 是否启动 MCP 服务器 |
| `port`、`bindAddress` | 监听地址；除非清楚后果，否则保持本机地址 |
| `authToken` | Bearer 令牌，首次运行时生成；删掉后重启即可更换 |
| `allowCommands` | 允许 `chat` 发送 `/指令`（默认关闭，否则智能体可以 `/op`、`/kill` 等） |
| `disablePauseOnLostFocus` | 窗口失焦时游戏不暂停，方便智能体在后台玩 |
| `permissions.<类别>` | 设为 `false` 会隐藏该类别下的所有工具（见下表） |
| `disabledTools` | 要隐藏的工具名，例如 `["attack_entity", "chat"]` |
| `reflexes.*` | 每 tick 运行的生存反射，见[生存反射](#生存反射) |
| `rateLimits.global` | 所有非控制类调用共享的令牌桶 |
| `rateLimits.<类别>` | 每个类别额外的令牌桶；`perSecond: 0` 表示不限 |

被隐藏的工具不会出现在 `tools/list` 里，模型根本不会尝试；如果仍然调用，会返回指明配置项的错误。
被限流的调用返回 `isError` 和 `retryAfterMs`。`stop` 和 `ping` 属于**控制类**工具：
不会被隐藏也不受限流，保证智能体随时能停下来。聊天默认每 2 秒一条、最多连发 3 条，
远低于原版的刷屏踢出阈值。

常见配置：

- **只看不动**（智能体只能观察）：把 `movement`、`interaction`、`inventory` 和 `chat` 设为 `false`。
- **多人服务器上的和平助手**：`"disabledTools": ["attack_entity"]`，`chat: false`。

## 工具

类别决定权限、速率限制和 MCP `annotations`（感知类带 `readOnlyHint`，交互类和物品栏类带
`destructiveHint`）。

| 工具 | 类别 | 作用 |
|---|---|---|
| `observe` | perception 感知 | 位置、朝向、生命、饥饿、护甲、氧气、手持物品、攻击冷却、打开的界面、准星目标、`dead`、时间、天气 |
| `get_blocks` | perception 感知 | 读取一个区域的方块，格式为 `layers`（ASCII）、`rle` 或 `json` |
| `scan_blocks` | perception 感知 | 在半径内查找指定方块（矿石等） |
| `subscribe_blocks` / `get_block_changes` | perception 感知 | 追踪玩家周围的方块变化（SSE 推送或轮询） |
| `get_entities` | perception 感知 | 附近的生物、玩家、掉落物，按距离排序，带 id、生命值、`hostile` |
| `get_inventory` | perception 感知 | 非空格子（0-8 快捷栏，9-35 背包，36-39 护甲，40 副手） |
| `get_chat` | perception 感知 | 收到的聊天和系统消息，可用 `afterSeq` 增量读取 |
| `get_container` | perception 感知 | 当前打开的界面（箱子、熔炉、工作台、自己的背包），按格子和分区列出 |
| `get_recipes` | perception 感知 | 已解锁的配方：原料、合成站、当前能否合成 |
| `move` | movement 移动 | 按住 W/A/S/D/跳跃/潜行/疾跑 N 个 tick，可先转向指定 yaw |
| `go_to` | movement 移动 | 走到某个位置或实体：疾跑、自动跳台阶，遇到岩浆/悬崖或受伤时停下 |
| `look` | movement 移动 | 设置 yaw/pitch，或看向某个方块或实体 |
| `break_block` | interaction 交互 | 挖掉一个方块 |
| `place_block` | interaction 交互 | 把手持方块放进空格子（自动选择依附面）；目标是自己脚下的格子时会跳起垫高；放不下时说明原因 |
| `interact_block` | interaction 交互 | 右键方块：门、箱子、工作台、床、按钮 |
| `attack_entity` / `interact_entity` | interaction 交互 | 左键 / 右键实体 |
| `use_item` | interaction 交互 | 对空气右键；`ticks` 表示按住（进食、喝药、拉弓、举盾） |
| `respawn` | interaction 交互 | 死亡后重生 |
| `select_slot` | inventory 物品栏 | 选择快捷栏格子，可丢出一个或全部 |
| `click_slot` | inventory 物品栏 | 一次原始的容器点击（左键/右键/shift/交换/丢出/收集） |
| `transfer_items` | inventory 物品栏 | 在容器和背包之间 shift 点击整组物品 |
| `craft` | inventory 物品栏 | 通过配方书合成 N 个物品（2x2 网格，或打开的工作台） |
| `chat` | chat 聊天 | 发送聊天；只有开启 `allowCommands` 才能发 `/指令` |
| `stop` | control 控制 | 松开所有按键、取消挖掘、合成和行走，可选关闭界面 |
| `batch` | control 控制 | 一次请求按顺序执行最多 8 个工具调用（每一步都单独检查权限和限流） |
| `set_reflexes` | control 控制 | 运行时查看或开关生存反射 |
| `ping` | control 控制 | 存活检查 |

### 生存反射

大模型每做一次决定要几秒，而僵尸每秒打一下，苦力怕开始嘶嘶响后 1.5 秒就会爆炸。所以模组会在智能体两轮之间
每 tick 自己做出反应：

| 反射 | 作用 |
|---|---|
| `autoDefend` | 攻击冷却满时，用快捷栏里最好的剑/斧攻击近战范围内的敌对生物，打完切回原来的格子 |
| `evadeCreepers` | 击退靠近的苦力怕，然后转身疾跑，直到离开爆炸范围 |
| `chargeRanged` | 生命值 ≥ 8 时冲向视线内的骷髅/流浪者/掠夺者/女巫（远程怪站着打必输） |
| `autoEat` | 饥饿且 6 格内没有敌人时，吃快捷栏里最合适的安全食物（不吃腐肉；金苹果只在紧急时吃） |
| `autoArmor` | 把背包里的护甲穿到空的护甲槽，优先好材质 |
| `escapeFluids` | 氧气不足时上浮，掉进岩浆时跳出来 |
| `interruptOnDamage` | 受伤时，正在执行的 `move`/`go_to`/`break_block` 立即返回 `interrupted: took damage from …` |

`observe` 会返回 `threats`（16 格内的敌对生物，按距离排序，苦力怕带 `hissing`）和 `events`（自上次 `observe`
以来受到的伤害、反射做了什么、击杀）；移动类工具的结果里也带 `threats`。每个反射只有在服主允许它所模仿的工具时
才会运行，所以 `permissions.interaction: false` 或 `"disabledTools": ["attack_entity"]` 也会关闭自动防御。
智能体可以用 `set_reflexes` 关掉反射，自己手动战斗。

在普通难度服务器的夜晚实测，智能体**完全不操作**（快捷栏有石剑和面包，无护甲），怪物生成在 5 格外：

| 场景 | 关闭反射 | 开启反射 |
|---|---|---|
| 1 只苦力怕 | 剩 1 点血 | 无伤 |
| 3 只苦力怕 | – | 无伤 |
| 3 只僵尸 | 9 秒内死亡 | 全部击杀，最低 14 点血 |
| 6 只僵尸 | – | 全部击杀，最低 15 点血 |
| 2 只骷髅 | 15 秒内死亡 | 通常两只都击杀，最低 3–10 点血 |
| 4 只尸壳 | – | 全部击杀，最低 4.7 点血 |

### 读取方块

`get_blocks` 参数：`center` `[x,y,z]`（默认玩家位置）、`size` `[sx,sy,sz]`（默认 `[32,32,32]`）
或 `radius`，以及 `format`。每次最多 96³ 个格子。

- **`layers`**：每个 Y 层一张 ASCII 地图（`layers[0]` 是最上层），附带 `legend`，大模型可以直接读。
- **`rle`**（默认）：体积最小。`palette` 是索引到方块 id 的映射；`data` 是 int32 游程的 base64，
  顺序为 y 优先、然后 z、最后 x，每个游程为 `(runLength << 12) | paletteIndex`。
- **`json`**：显式的 `{x,y,z,b}` 列表，跳过空气。体积最大，但自带说明。

`scan_blocks` 参数：`blocks`（例如 `["diamond_ore"]`）、`radius`（默认 16，最大 32）、
每种方块最多返回的位置数 `max`。

### 方块变化追踪

```
subscribe_blocks {action:"start", radius:16}   -> 建立基线
get_block_changes {}                           -> {changed, full, resync, changes:[{pos,from,to}]}
```

也可以用同一个令牌打开 `GET /mcp` 接收 Server-Sent Events：

```
event: message
data: {"jsonrpc":"2.0","method":"notifications/mcagent/block_diff","params":{"changed":1,"changes":[{"pos":[12,64,-3],"from":"-","to":"minecraft:stone"}]}}
```

- `start` 之后的第一次结果是 `full: true`（只有基线）。`resync: true` 表示积压超过 4096 处变化，
  请用 `get_blocks` 重新读取。
- 追踪区域按 16 格对齐，玩家跨区块时会重新定位（同样报告为 `full`）。新加载的区块会静默重建基线。
- 每个会话**只用 SSE 或轮询其中一种**：打开推送流时，变化会发往推送流。客户端如果没有调用
  `DELETE /mcp` 就断开，要到下一次心跳（15 秒）才会被发现。

### 合成与容器

```
get_recipes {item:"wooden_pickaxe"}        -> station "crafting_table"，原料列表
craft {item:"oak_planks", count:12}        -> 2x2 网格，不需要工作台
place_block {pos} + interact_block {pos}   -> 打开工作台
craft {item:"wooden_pickaxe"}              -> 在 3x3 网格中合成
interact_block {熔炉}
transfer_items {to:"container", item:"raw_iron"}   -> 自动进入原料槽
transfer_items {to:"container", item:"coal"}       -> 自动进入燃料槽
transfer_items {to:"player", item:"iron_ingot"}    -> 取出产物
```

`craft` 的流程是：放置配方（由服务器填充网格）→ 等待结果槽出现 → shift 点击取出，重复直到达到
`count`。和原版生存一样，只能放置已解锁的配方；其他情况可以用 `click_slot` 手动摆放。

### 动作如何执行

需要持续时间的动作（`move`、`break_block`、带 `ticks` 的 `use_item`、`craft`）按游戏 tick 推进，
完成后调用才返回（`move` 也支持 `wait:false`）。启动同类动作会替换正在进行的那个；
`stop` 取消一切。

- 移动输入在原版读取键盘之后注入（`KeyboardInputMixin`），所以窗口失焦时也能动。
- 挖掘时会跳过原版每 tick 调用的 `continueAttack`（`MinecraftMixin`）：没按左键时它会每 tick
  重置挖掘进度。
- 交互距离会提前检查（方块约 4.5 格，实体约 3 格），超出时返回可操作的错误信息。

## 安全

- 默认只绑定 `127.0.0.1`；每个请求都需要 Bearer 令牌（常量时间比较）。
- 带有非本机 `Origin` 头的请求返回 **403**，防止网页发起 DNS rebinding 攻击。
- 超过 1 MiB 的 POST 请求体返回 **413**。
- 聊天 `/指令` 默认关闭；权限和速率限制见上文。

## 设计说明

- **线程**：HTTP 线程只做校验和路由。所有世界访问都在客户端主线程执行（`GameBridge`）；
  多 tick 动作由 `END_CLIENT_TICK` 驱动，HTTP 线程等待一个 future。SSE 流使用独立线程。
- **扫描**直接遍历 `LevelChunkSection` 并跳过空段，而不是对每个格子调用 `getBlockState`。
- **变化检测**同时挂钩 `ClientLevel.setBlock`（客户端预测）和 `setServerVerifiedBlockState`
  （服务器更新，它会绕过 `setBlock`），标记区块为脏，只重扫该区块。快照是稀疏的；方块状态是
  享元对象，比较变化只需比较指针。

在后台线程编码一个 48³ 的类地形区域（110,592 格，`checks/CodecBench.java`）：

| 格式 | 大小 | 每格 | 编码耗时 |
|---|---|---|---|
| `rle` | 46 KB | 0.42 B | 3.7 ms |
| `layers` | 116 KB | 1.05 B | 8.4 ms |
| `json` | 2.3 MB | 20.9 B | 25 ms |

## 开发

### 目录结构

```
src/main/java/com/mcagent/mcp/          （不依赖 Minecraft 客户端类）
  config/McAgentConfig.java   配置文件、默认值、生成 ToolPolicy
  server/                     JSON-RPC、MCP 协议、Streamable HTTP 传输、SSE
  tools/                      McpTool 接口、注册表、ToolCategory、ToolPolicy、RateLimiter、
                              Args（参数校验）、Schema（构建器）
src/client/java/com/mcagent/mcp/client/
  McAgentClient.java          入口：组装、生命周期、给智能体的玩法说明
  GameBridge.java             切换到客户端主线程
  ChatLog.java                有界聊天记录
  control/AgentControl.java   持续动作：按住的按键、使用键、挖掘、界面脚本、go_to
  reflex/                     每 tick 的生存反射 + 纯逻辑的武器/食物/护甲选择
  blocks/                     扫描器、编码器、变化追踪
  inventory/                  界面读取、配方书、合成任务、纯逻辑的物品转移规划
  mixin/                      ClientLevel、KeyboardInput、Minecraft 的挂钩
  tools/                      每个工具一个类
checks/                       离线测试套件
```

### 离线检查

```bash
./checks/run.sh
```

| 检查 | 覆盖内容 |
|---|---|
| `ProtocolTest` | initialize/会话/instructions、tools/list（隐藏工具、annotations）、tools/call、`isError`、JSON-RPC 错误、批量请求、`batch` 工具（顺序、遇到被拒步骤即停、禁止嵌套）、401、403 `Origin`、413、权限拒绝、限流、202、405、DELETE |
| `SseSelfTest` | 端到端 SSE 推送、推送期间并发 POST、DELETE 关闭推送流 |
| `CodecTest` | `rle` 往返、`layers` 几何与图例、`json` 跳过空气 |
| `DiffLogicTest` | 基线、出现/消失、区块卸载、溢出 → resync、稀疏空气 |
| `ArgsTest` | 参数校验、聊天记录分页 |
| `InventoryMathTest` | 转移时的整组选择、2x2 与 3x3 网格适配、物品 id 规范化 |
| `PolicyTest` | 令牌桶计时（假时钟）、权限、控制类豁免、配置解析与往返、反射设置 |
| `ReflexMathTest` | 武器 DPS 排序、食物选择（按缺口、不安全/珍贵食物）、护甲等级 |
| `ToolSchemaTest` | 全部 29 个工具：名称唯一、类别已设置、JSON Schema 格式正确 |

### 实机测试

离线测试覆盖不到运行时的 Mixin 和真实玩法。可以启动本地离线服务器加开发客户端：

```bash
# 1. 服务器（Loom 已缓存 jar），超平坦，和平难度
mkdir -p /tmp/mcsrv && cd /tmp/mcsrv
cp <仓库目录>/.gradle-home/caches/fabric-loom/1.21.11/minecraft-server.jar .   # 或 ~/.gradle/caches/...
echo eula=true > eula.txt
printf 'online-mode=false\nserver-port=25599\nlevel-type=minecraft\\:flat\ndifficulty=peaceful\nspawn-protection=0\n' > server.properties
java -jar minecraft-server.jar --nogui     # 控制台可用 /give、/setblock、/summon 布置场景

# 2. 开发客户端（先在 run/options.txt 里设 onboardAccessibility:false，否则快速加入会卡住）
./gradlew runClient --args="--quickPlayMultiplayer 127.0.0.1:25599"
```

然后按 curl 冒烟测试的方式调用工具，令牌在 `run/config/mcagent.json` 里。所有工具都已在生存模式下
用这种方式实测过。

## 路线图

- **M0–M3** ✅ 项目骨架、HTTP/JSON-RPC 传输、带变化追踪和 SSE 的方块引擎
- **M4** ✅ 感知：实体、物品栏、聊天
- **M5** ✅ 行动：移动、转向、挖掘、放置、交互、攻击、使用、聊天、重生、停止
- **M6** ✅ 容器和配方书合成
- **M7** ✅ 加固：权限、速率限制、请求限制、工具 annotations、双语文档
- **M8** ✅ 让快速/低成本模型也能玩：生存反射、受伤中断、`go_to`、`batch`、`observe` 中的威胁和事件、
  DeepSeek 示例智能体
- **M9** ✅ DeepSeek Harness 插件；`layers` 增加 `layerY` 标注；`go_to` 在实心目标旁停下并指出挡路的方块
- **下一步**：演示视频；可选的 `pathfind_to`（通过 Baritone，LGPL，仅作为外部依赖）

## 许可证

MIT。Fabric Loader/Loom 为 Apache-2.0，MCP 规范和 SDK 为 MIT。Baritone（LGPL）只会作为可选的外部依赖。
