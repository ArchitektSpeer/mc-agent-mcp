// Evaluates the two `!!js` expressions in cordis.patch.yml exactly as DSH's loader does
// (a plain JS expression), against a throwaway HOME, to check how the mod is found.
import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import yaml from 'js-yaml';

const JsTag = new yaml.Type('tag:yaml.org,2002:js', { kind: 'scalar', construct: (s) => ({ __jsExpr: s }) });
const patch = yaml.load(fs.readFileSync(new URL('../cordis.patch.yml', import.meta.url), 'utf8'),
  { schema: yaml.DEFAULT_SCHEMA.extend([JsTag]) });
const entry = patch[0].insert[0];
const evaluate = (node) => new Function(`return (${node.__jsExpr})`)();
const resolve = () => ({ url: evaluate(entry.config.url), auth: evaluate(entry.config.headers.Authorization) });

const ENV_KEYS = ['HOME', 'MC_AGENT_URL', 'MC_AGENT_TOKEN', 'MC_AGENT_CONFIG', 'MC_AGENT_GAME_DIR'];
let saved, home;

function writeConfig(rel, cfg, ageMs = 0) {
  const f = path.join(home, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, JSON.stringify(cfg));
  const t = new Date(Date.now() - ageMs);
  fs.utimesSync(f, t, t);
  return f;
}

beforeEach(() => {
  saved = Object.fromEntries(ENV_KEYS.map((k) => [k, process.env[k]]));
  for (const k of ENV_KEYS) delete process.env[k];
  home = fs.mkdtempSync(path.join(os.tmpdir(), 'mcagent-home-'));
  process.env.HOME = home;
});
afterEach(() => {
  for (const k of ENV_KEYS) saved[k] === undefined ? delete process.env[k] : (process.env[k] = saved[k]);
  fs.rmSync(home, { recursive: true, force: true });
});

test('entry shape: DSH MCP client over streamable HTTP with Minecraft-sized timeouts', () => {
  assert.equal(entry.id, 'minecraft');
  assert.equal(entry.name, '@deepseek-ai/dsh-mcp-client');
  assert.equal(entry.config.serverName, 'minecraft');
  assert.equal(entry.config.transport, 'streamable-http');
  assert.ok(entry.config.toolCallTimeoutMs >= 60000);
  assert.ok(entry.config.reconnect.maxAttempts >= 100);
});

test('mod not installed yet: default URL, empty token, no exception', () => {
  assert.deepEqual(resolve(), { url: 'http://127.0.0.1:27890/mcp', auth: 'Bearer ' });
});

test('reads ~/.minecraft/config/mcagent.json', () => {
  writeConfig('.minecraft/config/mcagent.json', { port: 27890, authToken: 'abc123', bindAddress: '127.0.0.1' });
  assert.deepEqual(resolve(), { url: 'http://127.0.0.1:27890/mcp', auth: 'Bearer abc123' });
});

test('HMCL version isolation: newest versions/*/config wins, URL and token agree', () => {
  writeConfig('.minecraft/config/mcagent.json', { port: 1, authToken: 'old' }, 60_000);
  writeConfig('.minecraft/versions/1.21.11-Fabric/config/mcagent.json', { port: 27891, authToken: 'newest' }, 0);
  writeConfig('.minecraft/versions/other/config/mcagent.json', { port: 2, authToken: 'older' }, 30_000);
  assert.deepEqual(resolve(), { url: 'http://127.0.0.1:27891/mcp', auth: 'Bearer newest' });
});

test('0.0.0.0 bind is reached via loopback', () => {
  writeConfig('.minecraft/config/mcagent.json', { port: 27890, authToken: 't', bindAddress: '0.0.0.0' });
  assert.equal(resolve().url, 'http://127.0.0.1:27890/mcp');
});

test('MC_AGENT_GAME_DIR points at a custom game directory', () => {
  writeConfig('games/mc/config/mcagent.json', { port: 30000, authToken: 'custom' });
  process.env.MC_AGENT_GAME_DIR = path.join(home, 'games/mc');
  assert.deepEqual(resolve(), { url: 'http://127.0.0.1:30000/mcp', auth: 'Bearer custom' });
});

test('MC_AGENT_CONFIG names the file explicitly (versions are not scanned)', () => {
  writeConfig('.minecraft/versions/v/config/mcagent.json', { authToken: 'ignored' });
  const f = writeConfig('elsewhere/mcagent.json', { port: 27999, authToken: 'explicit' }, 60_000);
  process.env.MC_AGENT_CONFIG = f;
  assert.deepEqual(resolve(), { url: 'http://127.0.0.1:27999/mcp', auth: 'Bearer explicit' });
});

test('MC_AGENT_URL / MC_AGENT_TOKEN override everything', () => {
  writeConfig('.minecraft/config/mcagent.json', { port: 1, authToken: 'file' });
  process.env.MC_AGENT_URL = 'http://192.168.1.5:27890/mcp';
  process.env.MC_AGENT_TOKEN = 'envtoken';
  assert.deepEqual(resolve(), { url: 'http://192.168.1.5:27890/mcp', auth: 'Bearer envtoken' });
});

test('corrupt config file falls back to defaults instead of breaking DSH startup', () => {
  const f = path.join(home, '.minecraft/config/mcagent.json');
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, '{ not json');
  assert.deepEqual(resolve(), { url: 'http://127.0.0.1:27890/mcp', auth: 'Bearer ' });
});
