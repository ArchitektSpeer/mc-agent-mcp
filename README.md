# mc-agent-mcp

**English** | [简体中文](README.zh-CN.md)

A **Minecraft Fabric mod** that runs a **Model Context Protocol (MCP) server** inside the game
client, so an AI agent (Claude, DeepSeek, or any MCP client) can see the world and play
survival on its own: walk, mine, build, fight, eat, craft, smelt, use chests and chat.

**Demo video** (Chinese, 4 min, [download](https://github.com/ArchitektSpeer/mc-agent-mcp/releases/download/v0.2.1/mc-agent-mcp-demo-zh.mp4)): DeepSeek V4 Flash goes from an empty
inventory to a stone sword, builds a shelter before night, and fights mobs with survival reflexes.

| | |
|---|---|
| Game | Minecraft `1.21.11` (Java 21) |
| Loader | Fabric Loader `0.19.5` + Fabric API `0.141.6` |
| Transport | MCP *Streamable HTTP* at `http://127.0.0.1:27890/mcp`, Bearer token |
| Protocol | MCP `2025-06-18` (tools, server instructions, SSE notifications) |
| Side | client-only: it drives *your* player, in singleplayer or on a server |

## Features

- **29 tools** covering perception, movement, interaction, inventory, crafting and chat
  ([reference](#tools)).
- **Survival reflexes for slow models**: the mod defends, evades creepers, eats, equips armor
  and swims up every tick (50 ms), so an agent that needs seconds per turn is not killed while
  it thinks ([details](#survival-reflexes)).
- **Built for LLMs**: readable ASCII block maps, nearest-first entity lists, recipe lookup that
  says whether a crafting table is needed, errors that tell the agent what to do next
  ("out of reach; move closer").
- **Survival-correct actions**: mining takes real tool-speed time, crafting goes through the
  recipe book like a player, reach is enforced, all actions are normal client packets.
- **Fast perception**: section-level block scanning, compact `rle` encoding, exact block-change
  tracking pushed over SSE.
- **Owner controls**: loopback-only, Bearer auth, `Origin` checks, per-category permissions,
  per-tool disabling and rate limits.

## Quick start

1. Install [Fabric Loader](https://fabricmc.net/use/) for 1.21.11 and
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download `mc-agent-mcp-<version>.jar` from [Releases](https://github.com/ArchitektSpeer/mc-agent-mcp/releases/latest) (or build it) and drop it into
   `.minecraft/mods/`:
   ```bash
   ./gradlew build        # -> build/libs/mc-agent-mcp-<version>.jar
   ```
3. Start the game once. The mod writes `config/mcagent.json` with a random `authToken` and logs
   the endpoint.
4. Connect your MCP client:
   ```
   URL:     http://127.0.0.1:27890/mcp
   Header:  Authorization: Bearer <authToken from config/mcagent.json>
   ```

**Claude Code**

```bash
TOKEN=$(grep authToken ~/.minecraft/config/mcagent.json | cut -d'"' -f4)
claude mcp add --transport http minecraft http://127.0.0.1:27890/mcp \
  --header "Authorization: Bearer $TOKEN"
```

Join a world and ask, for example: *"Collect some oak logs, craft a wooden pickaxe and mine 10
cobblestone."* The `initialize` response includes a short playbook (`instructions`) that
clients put in the model's context: the observe → act loop, yaw directions, reach limits and
how crafting works.

Other clients (Claude Desktop via `mcp-remote`, Cursor, custom agents, …) use the same URL and
header.

**DeepSeek Harness (DSH)**

The [`dsh-plugin-minecraft`](dsh-plugin/README.md) bundle connects DSH to the mod with no
configuration (it finds the port and token itself, including HMCL/PCL version folders):

```bash
dsh plugin --profile web add dsh-plugin-minecraft
```

Tested end to end: DeepSeek V4 Flash in `dsh headless`, starting from an empty inventory next to
two oak trees, collected logs and crafted a crafting table and a wooden pickaxe in 69 s (17 steps).

**Example agent (DeepSeek or any OpenAI-compatible API)**

[`examples/deepseek_agent.py`](examples/deepseek_agent.py) is a standalone agent loop (Python
standard library only). It injects a fresh `observe` into every turn, keeps the context short and
lets the model call several tools per turn, which suits fast, inexpensive models:

```bash
export DEEPSEEK_API_KEY=sk-...
python3 examples/deepseek_agent.py --model <model id from your provider> \
  --goal "Gather wood, craft a wooden pickaxe, then mine 10 cobblestone."
# --base-url for other OpenAI-compatible APIs, --config if .minecraft is elsewhere
```

**Smoke test with curl**

```bash
TOKEN=$(grep authToken ~/.minecraft/config/mcagent.json | cut -d'"' -f4)
call() { curl -s -X POST http://127.0.0.1:27890/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "$1"; echo; }

call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"observe","arguments":{}}}'
call '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_blocks","arguments":{"format":"layers","radius":9}}}'
```

## Configuration

`config/mcagent.json` (in the game directory) is rewritten on every start with all keys, so new
options show up automatically. Edit it while the game is closed.

```json
{
  "enabled": true,
  "port": 27890,
  "bindAddress": "127.0.0.1",
  "authToken": "<generated>",
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

| Key | Meaning |
|---|---|
| `enabled` | start the MCP server at all |
| `port`, `bindAddress` | listen address; keep it on loopback unless you know why |
| `authToken` | Bearer token, generated on first run; delete it to rotate |
| `allowCommands` | let `chat` send `/commands` (off: an agent could otherwise `/op`, `/kill`, …) |
| `disablePauseOnLostFocus` | keep the game running unfocused, so the agent can play in the background |
| `permissions.<category>` | `false` hides every tool of that category (see the table below) |
| `disabledTools` | tool names to hide, e.g. `["attack_entity", "chat"]` |
| `reflexes.*` | per-tick survival reflexes, see [Survival reflexes](#survival-reflexes) |
| `rateLimits.global` | token bucket shared by all non-control calls |
| `rateLimits.<category>` | extra bucket per category; `perSecond: 0` = unlimited |

Hidden tools are removed from `tools/list`, so the model never tries them; calling one anyway
returns an error that names the config key. A rate-limited call returns `isError` with
`retryAfterMs`. `stop` and `ping` are **control** tools: never hidden, never limited, so an
agent can always halt. The chat default (one message per 2 s, burst 3) stays well under
vanilla's spam kick.

Typical setups:

- **Watch-only** (the agent can look but not act): set `movement`, `interaction`, `inventory`
  and `chat` to `false`.
- **Peaceful helper on a shared server**: `"disabledTools": ["attack_entity"]`, `chat: false`.

## Tools

Categories drive permissions, rate limits and the MCP `annotations` (`readOnlyHint` for
perception, `destructiveHint` for interaction and inventory).

| Tool | Category | Purpose |
|---|---|---|
| `observe` | perception | position, rotation, health, food, armor, air, held item, attack cooldown, open screen, crosshair target, `dead`, time, weather |
| `get_blocks` | perception | read a box of blocks as `layers` (ASCII), `rle` or `json` |
| `scan_blocks` | perception | find specific blocks (ores…) within a radius |
| `subscribe_blocks` / `get_block_changes` | perception | track block changes around the player (SSE push or polling) |
| `get_entities` | perception | nearby mobs/players/items, nearest first, with ids, health, `hostile` |
| `get_inventory` | perception | non-empty slots (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand) |
| `get_chat` | perception | received chat/system messages, incremental via `afterSeq` |
| `get_container` | perception | open menu (chest, furnace, crafting table, own inventory) by slot and section |
| `get_recipes` | perception | unlocked recipes: ingredients, station, craftable right now |
| `move` | movement | hold W/A/S/D/jump/sneak/sprint for N ticks, optionally face a yaw first |
| `go_to` | movement | walk to a position or entity: sprints, jumps steps, stops before lava/cliffs and when hurt |
| `look` | movement | set yaw/pitch, or face a block or entity |
| `break_block` | interaction | mine a block until it breaks |
| `place_block` | interaction | place the held block into an empty cell (support face chosen automatically); your own feet cell pillars up; refusals are explained |
| `interact_block` | interaction | right-click a block: doors, chests, crafting tables, beds, buttons |
| `attack_entity` / `interact_entity` | interaction | left / right click an entity |
| `use_item` | interaction | right-click in the air; `ticks` holds it (eat, drink, bow, shield) |
| `respawn` | interaction | respawn after death |
| `select_slot` | inventory | choose the hotbar slot, optionally drop one/all |
| `click_slot` | inventory | one raw container click (left/right/shift/swap/drop/collect) |
| `transfer_items` | inventory | shift-click stacks between container and inventory |
| `craft` | inventory | craft N items via the recipe book (2x2 grid, or an opened crafting table) |
| `chat` | chat | send chat; `/commands` only with `allowCommands` |
| `stop` | control | release all keys, cancel mining/crafting/walking, optionally close the GUI |
| `batch` | control | run up to 8 tool calls in order in one request (each step is permission/rate checked) |
| `set_reflexes` | control | read or toggle the survival reflexes at runtime |
| `ping` | control | liveness check |

### Survival reflexes

An LLM needs seconds per decision; a zombie hits every second and a creeper explodes 1.5 s after
it starts hissing. So the mod reacts on its own, every tick, between the agent's turns:

| Reflex | What it does |
|---|---|
| `autoDefend` | hits hostile mobs in melee reach with the best hotbar sword/axe when the cooldown is full, then switches back |
| `evadeCreepers` | hits a close creeper (knockback), turns away and sprints until it is out of blast range |
| `chargeRanged` | rushes skeletons/strays/pillagers/witches in line of sight while health ≥ 8 (archers win at range) |
| `autoEat` | eats the best safe hotbar food when hungry and no enemy is within 6 blocks (never rotten flesh; golden food only in emergencies) |
| `autoArmor` | moves armor from the inventory into empty armor slots, best material first |
| `escapeFluids` | swims up when air runs low, jumps out of lava |
| `interruptOnDamage` | a running `move`/`go_to`/`break_block` returns at once with `interrupted: took damage from …` |

`observe` reports `threats` (hostiles within 16 blocks, nearest first, `hissing` for creepers)
and `events` (damage taken, what the reflexes did, kills) since the previous `observe`; movement
results carry `threats` too. Each reflex only runs if the owner allows the tool it imitates, so
`permissions.interaction: false` or `"disabledTools": ["attack_entity"]` also disables
auto-defence. The agent can switch reflexes off with `set_reflexes` to fight by hand.

Measured on a Normal-difficulty server at night with an **idle** agent (stone sword and bread in
the hotbar, no armor), mobs spawned 5 blocks away:

| Scenario | Reflexes off | Reflexes on |
|---|---|---|
| 1 creeper | 1 HP left | no damage |
| 3 creepers | – | no damage |
| 3 zombies | dead in 9 s | all killed, lowest 14 HP |
| 6 zombies | – | all killed, lowest 15 HP |
| 2 skeletons | dead in 15 s | usually both killed, lowest 3–10 HP |
| 4 husks | – | all killed, lowest 4.7 HP |

### Reading blocks

`get_blocks` takes `center` `[x,y,z]` (default: the player), `size` `[sx,sy,sz]` (default
`[32,32,32]`) or `radius`, and `format`. At most 96³ cells per call.

- **`layers`**: one ASCII map per Y level (`layers[0]` is the top) plus a `legend`. An LLM can
  read it directly.
- **`rle`** (default): smallest. `palette` maps index → block id; `data` is base64 of int32 runs
  in y-major, then z, then x order, each run `(runLength << 12) | paletteIndex`.
- **`json`**: explicit `{x,y,z,b}` list with air skipped. Largest, but self-describing.

`scan_blocks` takes `blocks` (e.g. `["diamond_ore"]`), `radius` (default 16, max 32) and `max`
positions per block type.

### Block-change tracking

```
subscribe_blocks {action:"start", radius:16}   -> baseline is built
get_block_changes {}                           -> {changed, full, resync, changes:[{pos,from,to}]}
```

Or open `GET /mcp` with the same token for Server-Sent Events:

```
event: message
data: {"jsonrpc":"2.0","method":"notifications/mcagent/block_diff","params":{"changed":1,"changes":[{"pos":[12,64,-3],"from":"-","to":"minecraft:stone"}]}}
```

- The first result after `start` is `full: true` (baseline only). `resync: true` means more
  than 4096 changes piled up; re-read with `get_blocks`.
- The cube is anchored to 16-block boundaries and re-anchors when the player crosses a chunk
  border (also reported as `full`). Chunks loading in are re-baselined silently.
- Use **either** SSE **or** polling per session: while a stream is open, diffs go to the stream.
  A client that disconnects without `DELETE /mcp` is only noticed at the next heartbeat (15 s).

### Crafting and containers

```
get_recipes {item:"wooden_pickaxe"}        -> station "crafting_table", ingredients
craft {item:"oak_planks", count:12}        -> 2x2 grid, no table needed
place_block {pos} + interact_block {pos}   -> open the crafting table
craft {item:"wooden_pickaxe"}              -> made in the 3x3 grid
interact_block {furnace}
transfer_items {to:"container", item:"raw_iron"}   -> goes to the input slot
transfer_items {to:"container", item:"coal"}       -> goes to the fuel slot
transfer_items {to:"player", item:"iron_ingot"}    -> take the output
```

`craft` places the recipe (the server fills the grid), waits for the result and shift-clicks it,
repeating until `count` is reached. Only unlocked recipes can be placed, as in vanilla
survival; anything else can be done by hand with `click_slot`.

### How actions run

Timed actions (`move`, `break_block`, `use_item` with `ticks`, `craft`) advance on game ticks
and the call returns when they finish (`move` also has `wait:false`). Starting an action of the
same kind replaces the running one; `stop` cancels everything.

- Movement is injected right after vanilla reads the keyboard (`KeyboardInputMixin`), so it works
  while the window is unfocused.
- While mining, vanilla's per-tick `continueAttack` is skipped (`MinecraftMixin`): with the
  attack key up it would reset break progress every tick.
- Reach is checked up front (~4.5 blocks for blocks, ~3 for entities) and reported as an
  actionable error.

## Security

- Binds to `127.0.0.1` by default; every request needs the Bearer token (constant-time compare).
- Requests with a non-loopback `Origin` header get **403**, which blocks DNS-rebinding attacks
  from web pages.
- POST bodies over 1 MiB get **413**.
- Chat `/commands` are off by default; permissions and rate limits are described above.

## Design notes

- **Threads**: HTTP workers only validate and route. All world access runs on the client thread
  (`GameBridge`); multi-tick actions are driven from `END_CLIENT_TICK` and the HTTP thread waits on
  a future. SSE streams get their own threads.
- **Scanning** walks `LevelChunkSection`s directly and skips empty sections instead of calling
  `getBlockState` per cell.
- **Change detection** hooks both `ClientLevel.setBlock` (client predictions) and
  `setServerVerifiedBlockState` (server updates, which bypass `setBlock`), marks the chunk dirty,
  and re-scans only that chunk. Snapshots are sparse; block states are interned, so diffs are
  pointer compares.

Encoding a 48³ terrain-like volume (110,592 cells) off-thread (`checks/CodecBench.java`):

| format | payload | per cell | encode |
|---|---|---|---|
| `rle` | 46 KB | 0.42 B | 3.7 ms |
| `layers` | 116 KB | 1.05 B | 8.4 ms |
| `json` | 2.3 MB | 20.9 B | 25 ms |

## Development

### Layout

```
src/main/java/com/mcagent/mcp/          (no Minecraft client classes)
  config/McAgentConfig.java   config file, defaults, -> ToolPolicy
  server/                     JSON-RPC, MCP protocol, Streamable HTTP transport, SSE
  tools/                      McpTool SPI, registry, ToolCategory, ToolPolicy, RateLimiter,
                              Args (validation), Schema (builder)
src/client/java/com/mcagent/mcp/client/
  McAgentClient.java          entrypoint: wiring, lifecycle, agent playbook
  GameBridge.java             client-thread dispatch
  ChatLog.java                bounded chat log
  control/AgentControl.java   timed actions: held keys, use key, mining, menu scripts, go_to
  reflex/                     per-tick survival reflexes + pure weapon/food/armor choice
  blocks/                     scanner, codecs, change tracker
  inventory/                  menu reader, recipe book, crafting task, pure transfer logic
  mixin/                      ClientLevel, KeyboardInput, Minecraft hooks
  tools/                      one class per tool
checks/                       offline test suite
```

### Offline checks

```bash
./checks/run.sh
```

| Check | Covers |
|---|---|
| `ProtocolTest` | initialize/session/instructions, tools/list (hidden tools, annotations), tools/call, `isError`, JSON-RPC errors, batches, the `batch` tool (order, stop on denied step, no nesting), 401, 403 `Origin`, 413, permission denial, rate limiting, 202, 405, DELETE |
| `SseSelfTest` | SSE push end to end, concurrent POST while streaming, DELETE closing streams |
| `CodecTest` | `rle` round-trip, `layers` geometry/legend, `json` air skipping |
| `DiffLogicTest` | baseline, appeared/removed, chunk unload, overflow → resync, sparse air |
| `ArgsTest` | argument validation, chat log paging |
| `InventoryMathTest` | transfer stack selection, 2x2 vs 3x3 grid fit, item id normalisation |
| `PolicyTest` | token bucket timing (fake clock), permissions, control exemption, config parse/round-trip, reflex settings |
| `ReflexMathTest` | weapon DPS ranking, food choice (gap filling, unsafe/precious food), armor tiers |
| `ToolSchemaTest` | all 29 tools: unique names, category set, well-formed JSON Schema |

### In-game testing

The offline suite cannot cover mixins at runtime or real gameplay. Run a local offline-mode
server and the dev client:

```bash
# 1. server (Loom has already cached the jar), flat world, peaceful
mkdir -p /tmp/mcsrv && cd /tmp/mcsrv
cp <repo>/.gradle-home/caches/fabric-loom/1.21.11/minecraft-server.jar .   # or ~/.gradle/caches/...
echo eula=true > eula.txt
printf 'online-mode=false\nserver-port=25599\nlevel-type=minecraft\\:flat\ndifficulty=peaceful\nspawn-protection=0\n' > server.properties
java -jar minecraft-server.jar --nogui     # console: /give, /setblock, /summon to stage scenarios

# 2. dev client (set onboardAccessibility:false in run/options.txt first, or quick play stalls)
./gradlew runClient --args="--quickPlayMultiplayer 127.0.0.1:25599"
```

Then call tools as in the curl smoke test, using the token in `run/config/mcagent.json`. Every
tool has been exercised this way in survival.

## Roadmap

- **M0–M3** ✅ skeleton, HTTP/JSON-RPC transport, block engine with change tracking and SSE
- **M4** ✅ perception: entities, inventory, chat
- **M5** ✅ actuation: move, look, mine, place, interact, attack, use, chat, respawn, stop
- **M6** ✅ containers and recipe-book crafting
- **M7** ✅ hardening: permissions, rate limits, request limits, tool annotations, bilingual docs
- **M8** ✅ playable by fast/cheap models: survival reflexes, interrupt on damage, `go_to`, `batch`,
  threats/events in `observe`, example DeepSeek agent
- **M9** ✅ DeepSeek Harness plugin; `layers` labelled with `layerY`; `go_to` stops next to solid
  targets and names what blocks it
- **Next**: optional `pathfind_to` via Baritone (LGPL, kept as an
  external dependency)

## License

MIT. Fabric Loader/Loom are Apache-2.0, the MCP specification and SDKs are MIT. Baritone (LGPL)
would only ever be an optional external dependency.
