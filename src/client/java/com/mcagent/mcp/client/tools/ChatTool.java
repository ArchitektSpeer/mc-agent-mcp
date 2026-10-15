package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;

/** chat: send a chat message, or a /command when the config allows it. */
public final class ChatTool implements McpTool {
	public static final int MAX_LENGTH = 256;

	private final GameBridge bridge;
	private final boolean allowCommands;

	public ChatTool(GameBridge bridge, boolean allowCommands) {
		this.bridge = bridge;
		this.allowCommands = allowCommands;
	}

	@Override
	public String name() {
		return "chat";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.CHAT;
	}

	@Override
	public String description() {
		return "Send a chat message as the player (max " + MAX_LENGTH + " chars). Messages starting with '/' "
				+ "are commands, which are " + (allowCommands ? "enabled" : "disabled in config/mcagent.json (allowCommands)")
				+ ". Read replies with get_chat.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object().string("message", "Text, or '/command args'.").required("message").build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		String message = Args.requireString(args, "message").strip();
		if (message.isEmpty()) throw new ToolException("message is empty");
		if (message.length() > MAX_LENGTH) throw new ToolException("message longer than " + MAX_LENGTH + " chars");
		for (int i = 0; i < message.length(); i++) {
			char c = message.charAt(i);
			if (c < ' ' || c == 127 || c == '§') {
				throw new ToolException("message contains a control or formatting character");
			}
		}
		boolean command = message.startsWith("/");
		if (command && !allowCommands) {
			throw new ToolException("commands are disabled; set \"allowCommands\": true in config/mcagent.json");
		}

		return bridge.callOnGameThread(() -> {
			var p = GameBridge.requirePlayer();
			if (command) {
				p.connection.sendCommand(message.substring(1));
			} else {
				p.connection.sendChat(message);
			}
			JsonObject o = new JsonObject();
			o.addProperty("sent", true);
			o.addProperty("command", command);
			return o;
		});
	}
}
