package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * inspect_block: one block in full detail: its id, every block state property (crop age,
 * modded flags...) and the block entity data the client knows about.
 */
public final class InspectBlockTool implements McpTool {
	private final GameBridge bridge;

	public InspectBlockTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "inspect_block";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.PERCEPTION;
	}

	@Override
	public String description() {
		return "Read one block in detail: {block, properties: {name: value}, blockEntity?}. 'properties' are the "
				+ "block state values (e.g. age, facing, waterlogged and mod-specific ones); 'blockEntity' is the "
				+ "data the server synced to the client (may be partial). Works at any distance in loaded chunks.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.pos("pos", "Block to read.")
				.required("pos")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int[] at = Args.requirePos(args, "pos");
		BlockPos pos = new BlockPos(at[0], at[1], at[2]);
		return bridge.callOnGameThread(() -> {
			GameBridge.requirePlayer();
			ClientLevel level = Minecraft.getInstance().level;
			if (!level.isLoaded(pos)) {
				throw new ToolException("chunk at " + pos.toShortString() + " is not loaded");
			}
			BlockState state = level.getBlockState(pos);
			JsonObject o = new JsonObject();
			o.add("pos", Json.pos(pos));
			o.addProperty("block", BlockScanner.idOf(state));
			JsonObject props = new JsonObject();
			for (Property<?> prop : state.getProperties()) {
				props.addProperty(prop.getName(), valueName(state, prop));
			}
			o.add("properties", props);
			BlockEntity be = level.getBlockEntity(pos);
			if (be != null) {
				var tag = be.saveWithoutMetadata(level.registryAccess());
				o.add("blockEntity", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag));
			}
			return o;
		});
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> prop) {
		return prop.getName(state.getValue(prop));
	}
}
