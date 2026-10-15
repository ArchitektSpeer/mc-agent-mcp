package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;

import java.util.concurrent.CompletableFuture;

/** move: hold movement keys for a number of ticks (20 ticks = 1 second). */
public final class MoveTool implements McpTool {
	private final GameBridge bridge;
	private final AgentControl control;

	public MoveTool(GameBridge bridge, AgentControl control) {
		this.bridge = bridge;
		this.control = control;
	}

	@Override
	public String name() {
		return "move";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.MOVEMENT;
	}

	@Override
	public String description() {
		return "Hold movement keys for 'ticks' game ticks (20 ticks = 1 s; walking covers ~4.3 blocks/s, "
				+ "sprinting ~5.6). Directions are relative to where the player faces; set 'yaw' to turn first "
				+ "(0=south/+z, 90=west/-x, 180=north/-z, -90=east/+x). By default waits until done and returns "
				+ "the new position; wait=false returns immediately (use stop to cancel).";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.bool("forward", "W").bool("back", "S").bool("left", "A").bool("right", "D")
				.bool("jump", "Hold jump (jumps repeatedly while on ground).")
				.bool("sneak", "Hold sneak (prevents walking off edges).")
				.bool("sprint", "Hold sprint.")
				.number("yaw", "Face this yaw before moving.")
				.integer("ticks", "Duration in ticks (default 20, max " + AgentControl.MAX_TICKS + ").")
				.bool("wait", "Wait for completion (default true).")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		Input input = new Input(
				Args.getBool(args, "forward", false), Args.getBool(args, "back", false),
				Args.getBool(args, "left", false), Args.getBool(args, "right", false),
				Args.getBool(args, "jump", false), Args.getBool(args, "sneak", false),
				Args.getBool(args, "sprint", false));
		int ticks = Args.getInt(args, "ticks", 20, 1, AgentControl.MAX_TICKS);
		boolean wait = Args.getBool(args, "wait", true);
		boolean hasYaw = Args.has(args, "yaw");
		float yaw = (float) Args.getDouble(args, "yaw", 0);

		CompletableFuture<Boolean> done = bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			if (hasYaw) {
				p.setYRot(yaw);
				p.setYHeadRot(yaw);
			}
			return control.holdMovement(input, ticks);
		});
		JsonObject o = new JsonObject();
		if (wait) {
			o.addProperty("completed", GameBridge.await(done, ticks * 50L + 5000));
		} else {
			o.addProperty("started", true);
		}
		o.add("player", bridge.callOnGameThread(() -> Json.playerState(GameBridge.requirePlayer())));
		return o;
	}
}
