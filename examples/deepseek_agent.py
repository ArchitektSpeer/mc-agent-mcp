#!/usr/bin/env python3
"""
Minimal autonomous Minecraft agent for mc-agent-mcp, using any OpenAI-compatible chat API
(DeepSeek by default). Standard library only.

    export DEEPSEEK_API_KEY=sk-...
    python3 examples/deepseek_agent.py --model <model id> \
        --goal "Gather wood, craft a wooden pickaxe, then mine 10 cobblestone."

Designed for fast, inexpensive models:
  * a fresh `observe` is injected into every turn, so the model never acts on stale state
    (threats, damage events and reflex actions included);
  * only the last few rounds are kept, and long tool results are truncated, to keep the
    context small and turns fast;
  * the model may call several tools per turn (or use `batch`).
The mod's per-tick reflexes (auto-defend, creeper evasion, eating...) keep the player alive
while the model thinks.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

MAX_RESULT_CHARS = 3000
KEEP_ROUNDS = 6


class Mcp:
    """Tiny MCP Streamable-HTTP client (JSON responses only, which is what mc-agent-mcp sends)."""

    def __init__(self, url, token):
        self.url, self.token, self.session, self.next_id = url, token, None, 0

    def rpc(self, method, params=None):
        self.next_id += 1
        body = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            body["params"] = params
        headers = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        req = urllib.request.Request(self.url, json.dumps(body).encode(), headers)
        with urllib.request.urlopen(req, timeout=120) as r:
            self.session = r.headers.get("Mcp-Session-Id") or self.session
            msg = json.load(r)
        if "error" in msg:
            raise RuntimeError(msg["error"].get("message"))
        return msg["result"]

    def call(self, name, args):
        r = self.rpc("tools/call", {"name": name, "arguments": args})
        return r.get("structuredContent", {}), bool(r.get("isError"))


def chat(base_url, api_key, model, messages, tools):
    body = {"model": model, "messages": messages, "tools": tools, "temperature": 0.3}
    req = urllib.request.Request(base_url.rstrip("/") + "/chat/completions", json.dumps(body).encode(),
                                 {"Authorization": "Bearer " + api_key, "Content-Type": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                return json.load(r)["choices"][0]["message"]
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503) and attempt < 3:
                time.sleep(2 ** attempt)
                continue
            raise RuntimeError("chat API error %s: %s" % (e.code, e.read()[:300]))


def brief(state):
    """observe output trimmed to what a model needs every turn."""
    keep = ["position", "health", "food", "dead", "armor", "air", "mainHand", "selectedSlot",
            "screen", "crosshair", "timeOfDay", "threats", "events", "agent"]
    s = {k: state[k] for k in keep if k in state}
    if "position" in s:
        s["position"] = [round(s["position"][a], 1) for a in ("x", "y", "z")]
    return s


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--goal", default="Survive. Gather wood, craft a wooden pickaxe and sword, "
                                      "then mine stone and build a small shelter before night.")
    ap.add_argument("--model", default=os.environ.get("DEEPSEEK_MODEL", "deepseek-chat"),
                    help="model id (default: $DEEPSEEK_MODEL or deepseek-chat)")
    ap.add_argument("--base-url", default=os.environ.get("OPENAI_BASE_URL", "https://api.deepseek.com"))
    ap.add_argument("--api-key-env", default="DEEPSEEK_API_KEY")
    ap.add_argument("--mcp-url", default="http://127.0.0.1:27890/mcp")
    ap.add_argument("--config", default=os.path.expanduser("~/.minecraft/config/mcagent.json"),
                    help="mod config, to read the auth token")
    ap.add_argument("--turns", type=int, default=200)
    a = ap.parse_args()

    api_key = os.environ.get(a.api_key_env)
    if not api_key:
        sys.exit("set %s to your API key" % a.api_key_env)
    token = json.load(open(a.config))["authToken"]

    mcp = Mcp(a.mcp_url, token)
    init = mcp.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                  "clientInfo": {"name": "deepseek-agent", "version": "0.1"}})
    tools = [{"type": "function", "function": {"name": t["name"], "description": t["description"],
                                               "parameters": t["inputSchema"]}}
             for t in mcp.rpc("tools/list")["tools"]]
    system = (init.get("instructions", "") + "\n\nYour goal: " + a.goal +
              "\nEach user message contains the current game state. Act with tool calls; keep text short.")
    print("connected: %d tools, model %s" % (len(tools), a.model))

    rounds = []  # each round: [assistant message, tool messages...]
    for turn in range(1, a.turns + 1):
        state, _ = mcp.call("observe", {})
        if not state.get("inWorld"):
            print("not in a world; waiting...")
            time.sleep(5)
            continue
        if state.get("dead"):
            mcp.call("respawn", {})
        user = {"role": "user", "content": "Turn %d. State: %s" % (turn, json.dumps(brief(state)))}
        messages = [{"role": "system", "content": system}] + [m for r in rounds for m in r] + [user]

        t0 = time.time()
        reply = chat(a.base_url, api_key, a.model, messages, tools)
        dt = time.time() - t0
        calls = reply.get("tool_calls") or []
        text = (reply.get("content") or "").strip()
        print("[%d] %.1fs hp=%s food=%s threats=%d %s" % (
            turn, dt, state.get("health"), state.get("food"), len(state.get("threats", [])), text[:120]))

        round_msgs = [user, {"role": "assistant", "content": reply.get("content") or "", "tool_calls": calls}]
        for c in calls:
            name = c["function"]["name"]
            try:
                args = json.loads(c["function"].get("arguments") or "{}")
                result, is_err = mcp.call(name, args)
            except Exception as e:  # bad JSON from the model, transport errors
                args, result, is_err = {}, {"error": str(e)}, True
            out = json.dumps(result)
            if len(out) > MAX_RESULT_CHARS:
                out = out[:MAX_RESULT_CHARS] + "...(truncated)"
            print("    %s %s -> %s%s" % (name, json.dumps(args)[:80], "ERROR " if is_err else "", out[:140]))
            round_msgs.append({"role": "tool", "tool_call_id": c["id"], "content": out})
        rounds.append(round_msgs)
        rounds = rounds[-KEEP_ROUNDS:]
        if not calls:
            time.sleep(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nstopped")
