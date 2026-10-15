package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.blocks.BlockDiffTracker;
import com.mcagent.mcp.tools.McpTool;

/**
 * subscribe_blocks: start/stop/status of the block-change subscription.
 * While subscribed, block diffs are broadcast as MCP notifications
 * ("notifications/mcagent/block_diff") on the SSE stream; clients without an
 * SSE connection can use get_block_changes to poll.
 */
public final class SubscribeBlocksTool implements McpTool {
	private final GameBridge bridge;
	private final BlockDiffTracker tracker;

	public SubscribeBlocksTool(GameBridge bridge, BlockDiffTracker tracker) {
		this.bridge = bridge;
		this.tracker = tracker;
	}

	@Override
	public String name() {
		return "subscribe_blocks";
	}

	@Override
	public String description() {
		return "Manage the block-change subscription. "
				+ "action='start' begins tracking a player-centered cube (default radius 16, max "
				+ BlockDiffTracker.MAX_RADIUS + "); diffs are pushed as SSE notifications "
				+ "'notifications/mcagent/block_diff' and can also be polled with get_block_changes. "
				+ "action='stop' ends tracking; action='status' reports state.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();

		JsonObject action = new JsonObject();
		action.addProperty("type", "string");
		com.google.gson.JsonArray actions = new com.google.gson.JsonArray();
		actions.add("start");
		actions.add("stop");
		actions.add("status");
		action.add("enum", actions);
		action.addProperty("description", "Default 'status'.");
		props.add("action", action);

		JsonObject radius = new JsonObject();
		radius.addProperty("type", "integer");
		radius.addProperty("description", "Cube radius for 'start'; default 16, max " + BlockDiffTracker.MAX_RADIUS + ".");
		props.add("radius", radius);

		schema.add("properties", props);
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		String action = args.has("action") ? args.get("action").getAsString() : "status";

		return bridge.callOnGameThread(() -> {
			JsonObject o = new JsonObject();
			switch (action) {
				case "start" -> {
					int radius = args.has("radius") ? args.get("radius").getAsInt() : 16;
					if (radius < 1) throw new ToolException("radius must be >= 1");
					if (radius > BlockDiffTracker.MAX_RADIUS) {
						throw new ToolException("radius too large (max " + BlockDiffTracker.MAX_RADIUS + ")");
					}
					boolean started = tracker.start(radius);
					o.addProperty("action", "start");
					o.addProperty("tracking", true);
					o.addProperty("radius", tracker.getRadius());
					o.addProperty("alreadyTracking", !started);
					o.addProperty("epoch", tracker.getEpoch());
					o.addProperty("hint", "poll with get_block_changes, or open GET /mcp for SSE pushes");
				}
				case "stop" -> {
					boolean stopped = tracker.stop();
					o.addProperty("action", "stop");
					o.addProperty("tracking", false);
					o.addProperty("wasTracking", stopped);
				}
				case "status" -> {
					o.addProperty("action", "status");
					o.addProperty("tracking", tracker.isTracking());
					o.addProperty("radius", tracker.getRadius());
					o.addProperty("epoch", tracker.getEpoch());
					o.addProperty("changesDropped", tracker.getChangesDropped());
				}
				default -> throw new ToolException("unknown action: " + action);
			}
			return o;
		});
	}
}
