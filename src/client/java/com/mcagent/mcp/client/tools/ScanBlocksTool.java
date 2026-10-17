package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * scan_blocks: locate specific blocks (e.g. ores) near the player.
 * Much cheaper than get_blocks when you only need matches.
 */
public final class ScanBlocksTool implements McpTool {
	public static final int MAX_RADIUS = 32;
	public static final int MAX_PER_NAME = 256;

	private final GameBridge bridge;
	private final BlockScanner scanner;

	public ScanBlocksTool(GameBridge bridge, BlockScanner scanner) {
		this.bridge = bridge;
		this.scanner = scanner;
	}

	@Override
	public String name() {
		return "scan_blocks";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.PERCEPTION;
	}

	@Override
	public String description() {
		return "Find blocks of given types within a radius of a position (default: the player). "
				+ "Returns up to max positions per block type. Cheaper than get_blocks when you only need matches. "
				+ "Block names are ids like 'minecraft:diamond_ore' (accepts 'diamond_ore' shorthand).";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		JsonObject items = new JsonObject();
		items.addProperty("type", "integer");

		JsonObject center = new JsonObject();
		center.addProperty("type", "array");
		center.addProperty("description", "Center [x,y,z]; omit to use the player position.");
		center.add("items", items);
		center.addProperty("minItems", 3);
		center.addProperty("maxItems", 3);
		props.add("center", center);

		JsonObject radius = new JsonObject();
		radius.addProperty("type", "integer");
		radius.addProperty("description", "Cube radius; default 16, max " + MAX_RADIUS + ".");
		props.add("radius", radius);

		JsonObject blocks = new JsonObject();
		blocks.addProperty("type", "array");
		blocks.addProperty("description", "Block ids to find, e.g. ['minecraft:diamond_ore','minecraft:iron_ore'].");
		JsonObject strItems = new JsonObject();
		strItems.addProperty("type", "string");
		blocks.add("items", strItems);
		props.add("blocks", blocks);

		JsonObject max = new JsonObject();
		max.addProperty("type", "integer");
		max.addProperty("description", "Max positions per block type; default 64, max " + MAX_PER_NAME + ".");
		props.add("max", max);

		schema.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("blocks");
		schema.add("required", required);
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		if (!args.has("blocks") || !args.get("blocks").isJsonArray() || args.getAsJsonArray("blocks").isEmpty()) {
			throw new ToolException("missing required parameter: blocks (non-empty array of block ids)");
		}
		int radius = args.has("radius") ? Math.min(args.get("radius").getAsInt(), MAX_RADIUS) : 16;
		int max = args.has("max") ? Math.min(args.get("max").getAsInt(), MAX_PER_NAME) : 64;

		Set<String> wanted = new HashSet<>();
		for (var el : args.getAsJsonArray("blocks")) {
			String raw = el.getAsString().trim();
			if (raw.isEmpty()) continue;
			String name = raw.contains(":") ? raw : "minecraft:" + raw;
			if (ResourceLocation.tryParse(name) == null) {
				throw new ToolException("invalid block id: " + raw);
			}
			wanted.add(name);
		}
		if (wanted.isEmpty()) {
			throw new ToolException("no valid block ids in 'blocks'");
		}

		return bridge.callOnGameThread(() -> {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			if (level == null) throw new ToolException("no world loaded");

			BlockPos center;
			if (args.has("center") && args.get("center").isJsonArray()) {
				JsonArray c = args.getAsJsonArray("center");
				if (c.size() != 3) throw new ToolException("center must be [x,y,z]");
				center = new BlockPos(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt());
			} else if (mc.player != null) {
				center = mc.player.blockPosition();
			} else {
				throw new ToolException("no center given and player is not in a world");
			}

			List<BlockScanner.MatchGroup> groups = scanner.scanFor(level, center, radius, wanted, max);

			JsonObject out = new JsonObject();
			out.addProperty("centerRadius", radius);
			JsonArray matches = new JsonArray();
			for (BlockScanner.MatchGroup g : groups) {
				JsonObject m = new JsonObject();
				m.addProperty("name", g.name);
				m.addProperty("count", g.positions.size());
				m.addProperty("truncated", g.truncated);
				JsonArray positions = new JsonArray();
				for (int[] p : g.positions) {
					JsonArray pos = new JsonArray();
					pos.add(p[0]); pos.add(p[1]); pos.add(p[2]);
					positions.add(pos);
				}
				m.add("positions", positions);
				matches.add(m);
			}
			out.add("matches", matches);
			return out;
		});
	}
}
