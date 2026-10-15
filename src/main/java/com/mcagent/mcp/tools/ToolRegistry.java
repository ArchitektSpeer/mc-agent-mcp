package com.mcagent.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered registry of tools exposed over MCP. */
public final class ToolRegistry {
	private final Map<String, McpTool> tools = new LinkedHashMap<>();

	public void register(McpTool tool) {
		tools.put(tool.name(), tool);
	}

	public McpTool get(String name) {
		return tools.get(name);
	}

	/** MCP tools/list payload: {"tools": [...]}. */
	public JsonObject listPayload() {
		JsonArray arr = new JsonArray();
		for (McpTool t : tools.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.inputSchema());
			arr.add(o);
		}
		JsonObject payload = new JsonObject();
		payload.add("tools", arr);
		return payload;
	}
}
