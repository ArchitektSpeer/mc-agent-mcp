package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.blocks.BlockCodec;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** get_blocks: palette/RLE, ASCII layers, or explicit JSON dump of a block volume. */
public final class GetBlocksTool implements McpTool {
	private final GameBridge bridge;
	private final BlockScanner scanner;

	public GetBlocksTool(GameBridge bridge, BlockScanner scanner) {
		this.bridge = bridge;
		this.scanner = scanner;
	}

	@Override
	public String name() {
		return "get_blocks";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.PERCEPTION;
	}

	@Override
	public String description() {
		return "Scan a box of the world around a position and return every block. "
				+ "Formats: 'rle' (compact, recommended for large volumes: base64 of int32 runs, "
				+ "each run = (runLength << 12) | paletteIndex, y-major/z/x order), "
				+ "'layers' (ASCII top-down map per Y level with legend, best for an LLM to read directly), "
				+ "'json' (explicit [{x,y,z,b}] list, air skipped). "
				+ "Empty chunk sections are skipped server-side; 'stats.ms' reports scan time. Max 96^3 cells.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();

		JsonObject center = new JsonObject();
		center.addProperty("type", "array");
		center.addProperty("description", "Center [x,y,z]; omit to use the player position.");
		JsonObject items = new JsonObject();
		items.addProperty("type", "integer");
		center.add("items", items);
		center.addProperty("minItems", 3);
		center.addProperty("maxItems", 3);
		props.add("center", center);

		JsonObject size = new JsonObject();
		size.addProperty("type", "array");
		size.addProperty("description", "Box size [sx,sy,sz]; default [32,32,32].");
		size.add("items", items);
		size.addProperty("minItems", 3);
		size.addProperty("maxItems", 3);
		props.add("size", size);

		JsonObject radius = new JsonObject();
		radius.addProperty("type", "integer");
		radius.addProperty("description", "Alternative to 'size': cube side length centered on 'center'.");
		props.add("radius", radius);

		JsonObject format = new JsonObject();
		format.addProperty("type", "string");
		JsonArray formats = new JsonArray();
		formats.add("rle");
		formats.add("layers");
		formats.add("json");
		format.add("enum", formats);
		format.addProperty("description", "Output format; default 'rle'.");
		props.add("format", format);

		JsonObject skipAir = new JsonObject();
		skipAir.addProperty("type", "boolean");
		skipAir.addProperty("description", "For 'json' format only: omit air cells (default true).");
		props.add("skipAir", skipAir);

		schema.add("properties", props);
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		String format = args.has("format") && args.get("format").isJsonPrimitive()
				? args.get("format").getAsString() : "rle";
		boolean skipAir = !args.has("skipAir") || !args.get("skipAir").isJsonPrimitive()
				|| args.get("skipAir").getAsBoolean();

		return bridge.callOnGameThread(() -> {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			if (level == null) {
				throw new ToolException("no world loaded");
			}

			int cx, cy, cz;
			if (args.has("center") && args.get("center").isJsonArray()) {
				JsonArray c = args.getAsJsonArray("center");
				if (c.size() != 3) throw new ToolException("center must be [x,y,z]");
				cx = c.get(0).getAsInt();
				cy = c.get(1).getAsInt();
				cz = c.get(2).getAsInt();
			} else if (mc.player != null) {
				cx = mc.player.blockPosition().getX();
				cy = mc.player.blockPosition().getY();
				cz = mc.player.blockPosition().getZ();
			} else {
				throw new ToolException("no center given and player is not in a world");
			}

			int sx = 32, sy = 32, sz = 32;
			if (args.has("size") && args.get("size").isJsonArray()) {
				JsonArray s = args.getAsJsonArray("size");
				if (s.size() != 3) throw new ToolException("size must be [sx,sy,sz]");
				sx = s.get(0).getAsInt();
				sy = s.get(1).getAsInt();
				sz = s.get(2).getAsInt();
			} else if (args.has("radius") && args.get("radius").isJsonPrimitive()) {
				sx = sy = sz = args.get("radius").getAsInt();
			}

			int minX = cx - sx / 2, minY = cy - sy / 2, minZ = cz - sz / 2;
			int maxX = minX + sx, maxY = minY + sy, maxZ = minZ + sz;

			try {
				BlockCodec.Volume volume = scanner.scan(level, minX, minY, minZ, maxX, maxY, maxZ);
				return switch (format) {
					case "layers" -> BlockCodec.toLayers(volume);
					case "json" -> BlockCodec.toJson(volume, skipAir);
					case "rle" -> BlockCodec.toRle(volume);
					default -> throw new ToolException("unknown format: " + format);
				};
			} catch (IllegalArgumentException e) {
				throw new ToolException(e.getMessage());
			}
		});
	}
}
