package com.mcagent.mcp.tools;

import com.google.gson.JsonObject;

/** Liveness probe: proves the full MCP request path works. */
public final class PingTool implements McpTool {
	@Override
	public String name() {
		return "ping";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.CONTROL;
	}

	@Override
	public String description() {
		return "Liveness check. Returns pong, the mod version and server uptime in milliseconds.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", new JsonObject());
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		JsonObject o = new JsonObject();
		o.addProperty("pong", true);
		o.addProperty("timeMs", System.currentTimeMillis());
		return o;
	}
}
