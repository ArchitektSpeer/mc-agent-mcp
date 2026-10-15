# dsh-plugin-minecraft

[English](README.md) | **简体中文**

让 [DeepSeek Harness（DSH）](https://www.npmjs.com/package/@deepseek-ai/dsh) 玩 Minecraft。

本插件把 DSH 连接到 [mc-agent-mcp](https://github.com/ArchitektSpeer/mc-agent-mcp)：一个在你的
Minecraft 客户端里运行 MCP 服务器的 Fabric 模组。连上后，DSH 的模型会获得 29 个 Minecraft 工具
（`mcp__minecraft__observe`、`…__go_to`、`…__break_block`、`…__craft` 等），模组的玩法说明也会进入
系统提示词。模组每 tick 运行的生存反射会在模型思考时保住玩家，所以 DeepSeek V4 Flash 这类快速模型也能玩。

无需任何配置：插件会自己找到模组的端口和认证令牌。

## 安装

1. 在 Minecraft 1.21.11（Fabric）里安装模组：从 [Releases](https://github.com/ArchitektSpeer/mc-agent-mcp/releases)
   下载 jar，和 Fabric API 一起放进 `.minecraft/mods/`，然后启动一次游戏，让模组生成配置。
2. 把插件加到 DSH 的 profile（需要 pnpm）：
   ```bash
   dsh plugin --profile web add dsh-plugin-minecraft
   ```
3. 重启 profile（`dsh web`），进入世界，然后对它说：
   *“砍附近的树，做一把木镐。”*

启动顺序无所谓：可以先开 DSH 再开 Minecraft（或者中途重启游戏），DSH 会在后台持续重试，游戏一启动就连上。

升级或卸载：

```bash
dsh plugin --profile web update dsh-plugin-minecraft
dsh plugin --profile web remove dsh-plugin-minecraft
```

## 插件如何找到游戏

插件会添加一个 DSH 内置 MCP 客户端条目（id 为 `minecraft`）。启动时按以下顺序确定 URL 和 Bearer 令牌，
先找到的为准：

1. 环境变量 `MC_AGENT_URL` 和 `MC_AGENT_TOKEN`；
2. `MC_AGENT_CONFIG` 指定的模组配置文件；
3. `<游戏目录>/config/mcagent.json`，或最近写入的 `<游戏目录>/versions/*/config/mcagent.json`
   （HMCL、PCL 等开启版本隔离的启动器会把配置放在这里）。

`<游戏目录>` 为 `MC_AGENT_GAME_DIR`（如已设置），否则为 `~/.minecraft`（Linux）、
`%APPDATA%\.minecraft`（Windows）或 `~/Library/Application Support/minecraft`（macOS）。

这些值在 profile 启动时读取。模组第一次生成配置后（或你更换了令牌），请重启 DSH profile。

## 自定义

在 `$DSH_HOME/profiles/<profile>/cordis.patch.yml` 中按 profile 覆盖字段：

```yaml
- id: minecraft
  config:
    url: http://192.168.1.20:27890/mcp       # 游戏在另一台机器上（那边需设置 bindAddress）
    headers:
      Authorization: Bearer <令牌>
    toolCallTimeoutMs: 120000
```

其余设置都在模组的 `config/mcagent.json` 里：权限、速率限制和生存反射（见[模组 README](https://github.com/ArchitektSpeer/mc-agent-mcp/blob/main/README.zh-CN.md#配置)）。

## 兼容性

| | |
|---|---|
| DSH | `>=0.1.7-rc.1 <0.1.8`（以 peer 范围声明；DSH 在安装和启动时会检查） |
| mc-agent-mcp | 建议 0.2.0 及以上（生存反射、`go_to`、`batch`） |
| Node | 22.3+（DSH 本身也需要较新的 Node） |

## 排查问题

- **没有 `mcp__minecraft__*` 工具。** DSH 启动时游戏没在运行且至今仍未运行，或令牌不对。确认
  `config/mcagent.json` 存在，然后重启 profile。
  `curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:27890/mcp` 应输出 `401`（服务器在运行，需要令牌）。
- **工具调用超时。** 游戏处于暂停状态（打开暂停菜单会停止 tick）。模组默认关闭失焦暂停，请检查其配置中的
  `disablePauseOnLostFocus`。
- **装了多个 Minecraft。** 用 `MC_AGENT_CONFIG` 指向正确的 `mcagent.json`。

## 开发

本包只有一个 DSH 组合层 [`cordis.patch.yml`](cordis.patch.yml)，不需要构建。`npm test` 会在临时 home
目录里对其中的 URL 和令牌表达式求值测试。

## 许可证

MIT
