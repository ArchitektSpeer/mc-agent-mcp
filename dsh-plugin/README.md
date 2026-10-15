# dsh-plugin-minecraft

**English** | [简体中文](README.zh-CN.md)

Let [DeepSeek Harness (DSH)](https://www.npmjs.com/package/@deepseek-ai/dsh) play Minecraft.

This plugin connects DSH to [mc-agent-mcp](https://github.com/ArchitektSpeer/mc-agent-mcp), a
Fabric mod that runs an MCP server inside your Minecraft client. DSH's model then gets 29
Minecraft tools (`mcp__minecraft__observe`, `…__go_to`, `…__break_block`, `…__craft`, …) and the
mod's playbook in its system prompt. The mod's per-tick survival reflexes keep the player alive
while the model thinks, so fast models such as DeepSeek V4 Flash can play.

There is nothing to configure: the plugin finds the mod's port and auth token by itself.

## Install

1. Install the mod in Minecraft 1.21.11 (Fabric): download the jar from the
   [releases](https://github.com/ArchitektSpeer/mc-agent-mcp/releases) into `.minecraft/mods/`
   together with Fabric API, and start the game once so it writes its config.
2. Add the plugin to a DSH profile (needs pnpm):
   ```bash
   dsh plugin --profile web add dsh-plugin-minecraft
   ```
3. Restart the profile (`dsh web`), join a world, and ask:
   *"Get wood from the trees nearby and craft a wooden pickaxe."*

Order does not matter: start DSH first and Minecraft later (or restart the game); DSH keeps
retrying in the background and connects as soon as the game is up.

Update or remove:

```bash
dsh plugin --profile web update dsh-plugin-minecraft
dsh plugin --profile web remove dsh-plugin-minecraft
```

## How it finds the game

The plugin adds one entry (id `minecraft`) of DSH's built-in MCP client. At startup it resolves
the URL and Bearer token, first match wins:

1. `MC_AGENT_URL` and `MC_AGENT_TOKEN` environment variables;
2. the mod config file named by `MC_AGENT_CONFIG`;
3. `<game dir>/config/mcagent.json`, or the most recently written
   `<game dir>/versions/*/config/mcagent.json` (launchers with version isolation such as HMCL
   and PCL keep it there).

`<game dir>` is `MC_AGENT_GAME_DIR` if set, otherwise `~/.minecraft` (Linux),
`%APPDATA%\.minecraft` (Windows) or `~/Library/Application Support/minecraft` (macOS).

Values are read when the profile starts. After the mod writes its config for the first time
(or you change its token), restart the DSH profile.

## Customise

Override fields per profile in `$DSH_HOME/profiles/<profile>/cordis.patch.yml`:

```yaml
- id: minecraft
  config:
    url: http://192.168.1.20:27890/mcp       # game on another machine (set bindAddress there)
    headers:
      Authorization: Bearer <token>
    toolCallTimeoutMs: 120000
```

Everything else is configured in the mod's `config/mcagent.json`: permissions, rate limits and
the survival reflexes (see the [mod README](https://github.com/ArchitektSpeer/mc-agent-mcp#configuration)).

## Compatibility

| | |
|---|---|
| DSH | `>=0.1.7-rc.1 <0.1.8` (declared as a peer range; DSH checks it on install and start) |
| mc-agent-mcp | 0.2.0 or newer recommended (survival reflexes, `go_to`, `batch`) |
| Node | 22.3+ (DSH itself requires a modern Node) |

## Troubleshooting

- **No `mcp__minecraft__*` tools.** The game was not running when DSH started and is still not
  running, or the token is wrong. Check that `config/mcagent.json` exists, then restart the profile.
  `curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:27890/mcp` should print `401`
  (server up, token needed).
- **Tool calls time out.** The game is paused (an open pause menu stops ticks); the mod disables
  pause-on-focus-loss by default, so check `disablePauseOnLostFocus` in its config.
- **Several Minecraft installs.** Point `MC_AGENT_CONFIG` at the right `mcagent.json`.

## Development

The package is a single DSH bundle layer, [`cordis.patch.yml`](cordis.patch.yml); there is no
build step. `npm test` evaluates its URL and token expressions against a throwaway home directory.

## License

MIT
