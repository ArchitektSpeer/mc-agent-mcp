package com.mcagent.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

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
		return listPayload(t -> true);
	}

	/** tools/list restricted to the tools the policy exposes, so an agent never sees what it may not use. */
	public JsonObject listPayload(Predicate<McpTool> exposed) {
		JsonArray arr = new JsonArray();
		for (McpTool t : tools.values()) {
			if (!exposed.test(t)) continue;
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.inputSchema());
			o.add("annotations", annotations(t.category()));
			arr.add(o);
		}
		JsonObject payload = new JsonObject();
		payload.add("tools", arr);
		return payload;
	}

	/** MCP tool annotations: lets clients auto-approve read-only tools and warn before destructive ones. */
	private static JsonObject annotations(ToolCategory c) {
		JsonObject a = new JsonObject();
		a.addProperty("readOnlyHint", c == ToolCategory.PERCEPTION);
		a.addProperty("destructiveHint", c == ToolCategory.INTERACTION || c == ToolCategory.INVENTORY);
		a.addProperty("openWorldHint", c == ToolCategory.CHAT);
		return a;
	}
}
