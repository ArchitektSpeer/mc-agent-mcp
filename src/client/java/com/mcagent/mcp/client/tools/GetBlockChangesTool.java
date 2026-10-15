package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.blocks.BlockDiffTracker;
import com.mcagent.mcp.tools.McpTool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * get_block_changes: poll the pending block diff. The first poll after start returns
 * the full snapshot state (full=true); later polls return only changes. The result is
 * also broadcast on the SSE stream when subscribers are connected.
 */
public final class GetBlockChangesTool implements McpTool {
	private final GameBridge bridge;
	private final BlockDiffTracker tracker;

	public GetBlockChangesTool(GameBridge bridge, BlockDiffTracker tracker) {
		this.bridge = bridge;
		this.tracker = tracker;
	}

	@Override
	public String name() {
		return "get_block_changes";
	}

	@Override
	public String description() {
		return "Poll block changes since the last poll inside the subscribed cube (see subscribe_blocks). "
				+ "Returns {changed: n, resync: bool, changes: [{pos, from, to}]}. "
				+ "from/to are block ids ('-' = unknown/removed side). "
				+ "First poll after start returns the full baseline (resync=false, changed=0, full=true).";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		schema.add("properties", props);
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		return bridge.callOnGameThread(() -> {
			if (!tracker.isTracking()) {
				throw new ToolException("not tracking: call subscribe_blocks with action='start' first");
			}
			BlockDiffTracker.DiffResult diff = tracker.takeDiff();

			JsonObject o = new JsonObject();
			o.addProperty("radius", tracker.getRadius());
			o.addProperty("epoch", tracker.getEpoch());

			if (diff == null) {
				o.addProperty("changed", 0);
				o.addProperty("full", false);
				o.addProperty("resync", false);
				return o;
			}

			o.addProperty("full", diff.full);
			o.addProperty("resync", diff.resync);
			o.addProperty("ms", diff.snapNanos / 1_000_000.0);

			if (diff.full || diff.resync) {
				// Full baseline / resync: client should re-pull with get_blocks.
				o.addProperty("changed", 0);
				o.addProperty("hint", diff.full
						? "initial baseline established; no incremental data yet"
						: "change overflow; rescan with get_blocks");
				return o;
			}

			List<long[]> changes = diff.changes;
			JsonArray arr = new JsonArray();
			for (long[] c : changes) {
				JsonObject ch = new JsonObject();
				JsonArray pos = new JsonArray();
				pos.add((int) c[0]);
				pos.add((int) c[1]);
				pos.add((int) c[2]);
				ch.add("pos", pos);
				if (c[3] == 0) {
					ch.addProperty("from", nameOfId((int) c[4]));
					ch.addProperty("to", "-");
				} else {
					ch.addProperty("from", "-");
					ch.addProperty("to", nameOfId((int) c[4]));
				}
				arr.add(ch);
			}
			o.add("changes", arr);
			o.addProperty("changed", arr.size());
			return o;
		});
	}

	private static String nameOfId(int globalId) {
		Block b = BuiltInRegistries.BLOCK.byId(globalId);
		if (b == null) return "unknown";
		var id = BuiltInRegistries.BLOCK.getKey(b);
		return id == null ? "unknown" : id.toString();
	}
}
