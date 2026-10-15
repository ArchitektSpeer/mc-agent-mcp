package com.mcagent.mcp.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** JSON-RPC 2.0 response builders (MCP is JSON-RPC based). */
public final class JsonRpc {
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	private JsonRpc() {
	}

	public static JsonObject result(JsonElement id, JsonElement result) {
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id);
		o.add("result", result);
		return o;
	}

	public static JsonObject error(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id); // may be JsonNull for parse errors
		o.add("error", err);
		return o;
	}
}
