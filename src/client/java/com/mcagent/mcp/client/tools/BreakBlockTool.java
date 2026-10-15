package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.CompletableFuture;

/** break_block: mine a block with the held item, as a player holding left click would. */
public final class BreakBlockTool implements McpTool {
	private final GameBridge bridge;
	private final AgentControl control;

	public BreakBlockTool(GameBridge bridge, AgentControl control) {
		this.bridge = bridge;
		this.control = control;
	}

	@Override
	public String name() {
		return "break_block";
	}

	@Override
	public String description() {
		return "Mine the block at 'pos' with the held item (select a tool first with select_slot). The player "
				+ "turns toward it and keeps mining until it breaks, goes out of reach (~4.5 blocks from the eyes), "
				+ "or 'maxTicks' pass. Waits and returns {broken, block, ticks, reason?}. Drops must be picked up "
				+ "by walking over them.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.pos("pos", "Block to mine.")
				.integer("maxTicks", "Give up after this many ticks (default 200, max " + AgentControl.MAX_TICKS + ").")
				.required("pos")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int[] at = Args.requirePos(args, "pos");
		int maxTicks = Args.getInt(args, "maxTicks", 200, 1, AgentControl.MAX_TICKS);

		CompletableFuture<JsonObject> done = bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			Minecraft mc = Minecraft.getInstance();
			BlockPos pos = new BlockPos(at[0], at[1], at[2]);
			BlockState state = mc.level.getBlockState(pos);
			if (state.isAir()) throw new ToolException("there is no block at " + pos.toShortString());
			if (state.getDestroySpeed(mc.level, pos) < 0) throw new ToolException("block is unbreakable");
			if (!p.isWithinBlockInteractionRange(pos, 0.0)) {
				throw new ToolException("block is out of reach; move closer (within ~4.5 blocks of the eyes)");
			}
			return control.startBreak(mc, pos, AgentControl.faceToward(p, pos), maxTicks);
		});
		return GameBridge.await(done, maxTicks * 50L + 5000);
	}
}
