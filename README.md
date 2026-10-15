# mc-agent-mcp

A **Minecraft Fabric mod** that exposes a **Model Context Protocol (MCP) server** inside the
game, so AI agents (Claude, DeepSeek, or any MCP client) can perceive the world at high speed
and play the game directly.

- **Game**: Minecraft `1.21.11` (Java 21)
- **Loader**: Fabric Loader `0.19.5` + Fabric API `0.141.6`
- **Transport**: MCP *Streamable HTTP* on `http://127.0.0.1:27890/mcp` (loopback only, Bearer auth)
- **Protocol**: MCP spec `2025-06-18`
- **Client-side mod**: it drives the local player; nothing is exposed beyond loopback

> Status: **M5 complete** — perception (blocks, entities, inventory, chat) and actuation
> (move, look, mine, place, attack, use, chat). An AI agent can now play the game end to end.
> See the roadmap at the end.

## Build

```bash
./gradlew build
# jar lands in build/libs/mc-agent-mcp-<version>.jar
```

Drop the jar into your Fabric `mods/` folder together with Fabric API.

## Connecting an AI agent

On first launch the mod writes `config/mcagent.json` and prints a generated Bearer token to the
game log. Point any MCP client that supports *Streamable HTTP* at:

```
URL:     http://127.0.0.1:27890/mcp
Header:  Authorization: Bearer <token from config/mcagent.json>
```

### Claude Code

```bash
TOKEN=$(grep authToken ~/.minecraft/config/mcagent.json | cut -d'"' -f4)
claude mcp add --transport http minecraft http://127.0.0.1:27890/mcp \
  --header "Authorization: Bearer $TOKEN"
```

Then join a world and ask Claude e.g. *"collect 10 oak logs and craft a crafting table"*. The
server's `initialize` result carries a short playbook (`instructions`) that MCP clients put in the
model's context, explaining the observe → act loop, yaw directions and reach limits.

Other clients (Claude Desktop via `mcp-remote`, Cursor, DeepSeek agents, …) use the same URL and
header.

### Config (`config/mcagent.json`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | start the MCP server |
| `port` / `bindAddress` | `27890` / `127.0.0.1` | listen address (keep it on loopback) |
| `authToken` | random | Bearer token, generated on first run |
| `allowCommands` | `false` | let the `chat` tool send `/commands` (an agent could otherwise `/op`, `/kill`, …) |
| `disablePauseOnLostFocus` | `true` | keep the game running while unfocused, so the agent can play in the background |

Manual smoke test:

```bash
TOKEN=$(grep authToken run/config/mcagent.json | cut -d'"' -f4)

curl -X POST http://127.0.0.1:27890/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

curl -X POST http://127.0.0.1:27890/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# Read a 33x33x33 box around the player as an ASCII map (do the GET first for push mode):
curl -X POST http://127.0.0.1:27890/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_blocks","arguments":{"format":"layers","radius":33}}}'
```

### Push mode (server-initiated events)

`GET /mcp` with the same Bearer token opens a Server-Sent Events stream. While a subscription is
active, every block change in the tracked cube is pushed as it happens:

```bash
curl -N -H "Authorization: Bearer $TOKEN" http://127.0.0.1:27890/mcp
```

```
event: message
data: {"jsonrpc":"2.0","method":"notifications/mcagent/block_diff","params":{"changed":1,"ms":0.4,"changes":[{"pos":[12,64,-3],"from":"-","to":"minecraft:stone"}]}}
```

`DELETE /mcp` closes the session and its streams. A client without an SSE connection can poll the
same data with `get_block_changes` instead — the two consumers *split* the event stream, so use
one of them per session.

## Tools

| Tool | Status | Purpose |
|---|---|---|
| `ping` | ✅ | liveness / handshake check |
| `observe` | ✅ | player pos, rot, health, food, dimension, game time |
| `get_blocks` | ✅ | bulk block read of a box, `rle` / `layers` / `json` encodings |
| `scan_blocks` | ✅ | locate specific blocks (ore hunting) inside a radius |
| `subscribe_blocks` | ✅ | start/stop/status of the block-change tracker |
| `get_block_changes` | ✅ | poll the tracked block diffs (SSE alternative) |
| `get_entities` | ✅ | nearby entities, nearest first: id, type, pos, health, `hostile`, dropped `item` |
| `get_inventory` | ✅ | non-empty slots (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand), selected slot |
| `get_chat` | ✅ | received chat/system messages, incremental via `afterSeq` |
| `move` | ✅ | hold W/A/S/D/jump/sneak/sprint for N ticks, optional `yaw` first |
| `look` | ✅ | set yaw/pitch, or face a block / entity |
| `break_block` | ✅ | mine a block until it breaks (multi-tick, survival-correct) |
| `place_block` | ✅ | place the held block into an empty cell (support face found automatically) |
| `interact_block` | ✅ | right-click a block: doors, chests, crafting tables, beds, buttons |
| `attack_entity` / `interact_entity` | ✅ | left / right click an entity by id |
| `use_item` | ✅ | right-click in the air; `ticks>0` holds it (eat, drink, bow, shield) |
| `select_slot` | ✅ | pick hotbar slot, optionally drop one/all |
| `chat` | ✅ | send chat; `/commands` only with `allowCommands` |
| `respawn` | ✅ | respawn after death |
| `stop` | ✅ | release all keys, cancel mining, optionally close the open GUI |
| `pathfind_to` | 🔜 later | optional Baritone integration (LGPL, kept as external dep) |

### Actuation model

Timed actions (`move`, `break_block`, `use_item` with `ticks`) run on game ticks and the tool call
returns when they finish (or use `wait:false` on `move`). Starting a new action of the same kind
replaces the old one; `stop` cancels everything. Movement is injected after vanilla reads the
keyboard (`KeyboardInputMixin`), so it works while the window is unfocused. While mining, vanilla's
per-tick `continueAttack` is skipped (`MinecraftMixin`) — with the attack key up it would reset the
break progress every tick. Reach is checked up front, so the agent gets an actionable
"out of reach; move closer" instead of a silent no-op.

### `get_blocks`

Three encodings, chosen by what the agent needs:

- **`rle`** (default) — smallest wire format. `palette` maps index → block id; `data` is base64 of
  int32 runs in y-major, then z, then x order, each run `(runLength << 12) | paletteIndex`.
- **`layers`** — one ASCII map per Y level (`layers[0]` is the top), with a `legend`. This is the
  format an LLM can read without decoding anything.
- **`json`** — explicit `{x,y,z,b}` list; air skipped by default. Largest, but self-describing.

Arguments: `center` `[x,y,z]` (default: player), `size` `[sx,sy,sz]` (default `[32,32,32]`) or
`radius`, `format`, `skipAir`. Max 96³ cells per call.

### `scan_blocks`

`blocks` (required, e.g. `["minecraft:diamond_ore"]`, the `minecraft:` prefix is optional),
`radius` (default 16, max 32), `max` (default 64, max 256 positions per block type). Returns
positions per block type plus a `truncated` flag.

### Block-change tracking

```
subscribe_blocks {action:"start", radius:16}   -> baseline is built
get_block_changes {}                            -> {changed, full, resync, changes:[{pos,from,to}]}
```

- The first poll after `start` returns `full: true`: the baseline exists, but there is no
  incremental data yet.
- `resync: true` means the batch overflowed (4096 changes); re-read with `get_blocks`.
- The tracked cube is anchored to 16-block boundaries on all axes and re-anchors when the player
  crosses a chunk border (which also reports `full: true`).
- Chunk load/unload re-baselines silently, so terrain streaming in is never reported as thousands
  of "appeared" blocks.

## Design (why it's fast)

- **Thread model**: HTTP workers only validate/route; every world access is dispatched to the
  Minecraft client thread (`GameBridge`). SSE connections get their own threads, so a long-lived
  stream never blocks tool calls.
- **Section-level scanning**: `get_blocks` walks `LevelChunkSection`s directly, skipping empty
  sections (`Palette`-free `hasOnlyAir()` check) instead of calling `level.getBlockState` per cell.
- **Exact change detection**: a Mixin on `ClientLevel.setBlock` records the chunk that changed
  (Fabric has no client-side block-change event), so each tick only re-scans that chunk.
  Snapshots are sparse (non-air only) and stored per chunk.
- **Cheap diffs**: block states are flyweight-interned, so change detection is a pointer compare;
  the pending change list is bounded and reports `resync` on overflow instead of drifting silently.

Measured on a 48³ (110,592 cells) terrain-like volume, encoded off-thread (`checks/CodecBench.java`):

| format | payload | per cell | encode | vs. raw 16-bit |
|---|---|---|---|---|
| `rle` | 46 KB | 0.42 B | 3.7 ms | 4.8× smaller |
| `layers` | 116 KB | 1.05 B | 8.4 ms | 1.9× smaller |
| `json` | 2.3 MB | 20.9 B | 25 ms | 10× larger |

- **Security**: loopback bind + random Bearer token + `Origin` validation (browser requests from
  non-loopback origins get 403, blocking DNS rebinding); chat commands are off by default.

## Checks

Everything that can be verified without a GPU or a Minecraft account:

```bash
./checks/run.sh
```

| Check | Covers |
|---|---|
| `ProtocolTest` | initialize/session id/instructions, `tools/list`, `tools/call`, tool errors → `isError`, JSON-RPC errors, batches, 401, 403 on foreign `Origin`, 202, 405, DELETE |
| `SseSelfTest` | SSE push end-to-end, concurrent POST while streaming, DELETE closing streams |
| `CodecTest` | `rle` round-trip, `layers` geometry/legend, `json` air skipping, stats |
| `DiffLogicTest` | baseline, appeared/removed with old/new ids, chunk unload, overflow → resync, sparse air |
| `ArgsTest` | argument validation (ranges, integer-only positions, enums → `ToolException`), chat log paging |
| `ToolSchemaTest` | all 22 tools: unique names, well-formed JSON Schema (types, `enum` arrays, `required`, `items`) |

`CodecBench` (`./.jdk21/bin/java -cp .harness/classes:build/classes/java/client:<gson> CodecBench`)
prints the numbers in the table above.

**Not covered offline** (needs a real game launch): mixin application at runtime, live chunk
scanning, SSE pushes from an actual world, and the effect of actuation tools on a real player.
`./gradlew runClient` + the curl commands above are the manual test path.

## Layout

```
src/main/java/com/mcagent/mcp/
  McAgentMod.java            common entrypoint (constants/logger)
  config/McAgentConfig.java  JSON config + token generation
  server/JsonRpc.java        JSON-RPC 2.0 helpers
  server/McpProtocol.java    MCP methods: initialize, tools/list, tools/call, notifications
  server/McpHttpServer.java  Streamable HTTP transport (JDK HttpServer, auth, session id)
  server/NotificationBus.java SSE fan-out to GET /mcp subscribers
  server/SseSubscriber.java  one SSE connection (queue + single writer)
  tools/McpTool.java         tool SPI
  tools/ToolRegistry.java    tool registry -> tools/list
  tools/PingTool.java        liveness tool
  tools/Args.java            validating argument accessors
  tools/Schema.java          input-schema builder
src/client/java/com/mcagent/mcp/client/
  McAgentClient.java         client entrypoint: wiring + lifecycle
  GameBridge.java            main-thread dispatch for all game access
  ChatLog.java               bounded log of received chat
  control/AgentControl.java  timed actions: held keys, use key, block breaking
  tools/ObserveTool.java     player/world perception
  tools/GetBlocksTool.java   get_blocks (bulk block read)
  tools/ScanBlocksTool.java  scan_blocks (find blocks)
  tools/SubscribeBlocksTool.java   subscribe_blocks (start/stop/status)
  tools/GetBlockChangesTool.java   get_block_changes (poll diffs)
  tools/GetEntitiesTool.java, GetInventoryTool.java, GetChatTool.java   M4 perception
  tools/MoveTool.java, LookTool.java, BreakBlockTool.java, PlaceBlockTool.java,
  tools/AttackTool.java, UseItemTool.java, SelectSlotTool.java, ChatTool.java,
  tools/RespawnTool.java, StopTool.java                                  M5 actuation
  blocks/BlockScanner.java   section-level scanning
  blocks/BlockCodec.java     rle / layers / json encoders
  blocks/BlockDiffTracker.java     change tracking + per-chunk snapshots
  blocks/SparseChunkDiff.java      pure diff core (unit-tested)
  mixin/ClientLevelMixin.java      setBlock hook for exact change detection
  mixin/KeyboardInputMixin.java    agent movement input
  mixin/MinecraftMixin.java        keeps agent mining progress alive
checks/                      offline check suite (see above)
```

## Roadmap

- **M0–M2** ✅ project skeleton, HTTP/JSON-RPC transport, `ping`/`observe`
- **M3** ✅ block engine: scanner, codecs, change tracking, SSE push
- **M4** ✅ perception: `get_entities`, `get_inventory`, `get_chat`
- **M5** ✅ actuation: `move`, `look`, `break_block`, `place_block`, `interact_*`, `attack_entity`,
  `use_item`, `select_slot`, `chat`, `respawn`, `stop`
- **M6** containers + crafting: read/click open container slots, recipe-book crafting, smelting
- **M7** hardening + docs: rate limits, per-tool permissions, example agent, demo video
- **Later** optional `pathfind_to` via Baritone as an *external* dependency

## License

MIT. Fabric Loader/Loom are Apache-2.0/CC0; the MCP spec and official SDKs are MIT — no
license conflicts. Baritone (LGPL) will remain an *optional external* dependency if integrated.
