package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.ChatLog;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;

/** get_chat: read received chat/system messages since a sequence number. */
public final class GetChatTool implements McpTool {
	private final ChatLog log;

	public GetChatTool(ChatLog log) {
		this.log = log;
	}

	@Override
	public String name() {
		return "get_chat";
	}

	@Override
	public String description() {
		return "Read chat and system messages the player received (kind 'chat' with a sender, or 'game' for "
				+ "server/system lines like deaths and command output). Pass 'afterSeq' = the previous 'lastSeq' "
				+ "to get only new messages. Keeps the last " + ChatLog.CAPACITY + ".";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.integer("afterSeq", "Only messages with seq greater than this (default 0 = all kept).")
				.integer("max", "Max messages, newest kept (default 50, max " + ChatLog.CAPACITY + ").")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int after = Args.getInt(args, "afterSeq", 0, 0, Integer.MAX_VALUE);
		int max = Args.getInt(args, "max", 50, 1, ChatLog.CAPACITY);
		return log.since(after, max);
	}
}
