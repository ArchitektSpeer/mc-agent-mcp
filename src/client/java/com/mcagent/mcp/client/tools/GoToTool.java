package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/** go_to: walk straight to a position or an entity, jumping up steps, until close enough. */
public final class GoToTool implements McpTool {
	/** Default stop distance when the target block itself is solid: close enough to mine it. */
	private static final double SOLID_TARGET_STOP = 2.5;

	private final GameBridge bridge;
	private final AgentControl control;

	public GoToTool(GameBridge bridge, AgentControl control) {
		this.bridge = bridge;
		this.control = control;
	}

	@Override
	public String name() {
		return "go_to";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.MOVEMENT;
	}

	@Override
	public String description() {
		return "Walk to 'pos' [x,y,z] or to entity 'entityId' (e.g. to reach a mob, item or player). Faces the "
				+ "target, sprints when far, jumps up 1-block steps, swims. Stops within 'stopDistance' (default 1.5, or "
				+ "2.5 when 'pos' is a solid block such as a tree or ore, so you can mine it; use ~2.5 to get in attack "
				+ "reach), when stuck (naming the blocking block), or before lava / a 4+ block drop. Straight line only: "
				+ "for walls or caves give intermediate waypoints. Stops early if the player takes damage. "
				+ "Returns {arrived, reason?, position}.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.pos("pos", "Destination block.")
				.integer("entityId", "Entity to walk to (from get_entities / observe threats).")
				.number("stopDistance", "Stop this close, horizontally (default 1.5).")
				.integer("maxTicks", "Give up after this many ticks (default 400 = 20 s, max " + AgentControl.MAX_TICKS + ").")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int[] pos = Args.getPos(args, "pos");
		boolean hasEntity = Args.has(args, "entityId");
		if ((pos == null) == !hasEntity) throw new ToolException("give exactly one of 'pos' or 'entityId'");
		int entityId = Args.getInt(args, "entityId", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
		boolean stopGiven = Args.has(args, "stopDistance");
		double stop = Args.getDouble(args, "stopDistance", 1.5);
		if (stop < 0.3 || stop > 32) throw new ToolException("'stopDistance' must be between 0.3 and 32");
		int maxTicks = Args.getInt(args, "maxTicks", 400, 1, AgentControl.MAX_TICKS);

		CompletableFuture<JsonObject> done = bridge.callOnGameThread(() -> {
			GameBridge.requirePlayer();
			if (hasEntity) {
				Entity e = Minecraft.getInstance().level.getEntity(entityId);
				if (e == null) throw new ToolException("no entity with id " + entityId);
				return control.startGoTo(null, entityId, stop, maxTicks);
			}
			// Walking "to" a tree trunk or an ore means walking next to it: you cannot stand inside a
			// solid block, and a strict 1.5 would end in "stuck".
			BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
			var level = Minecraft.getInstance().level;
			boolean solid = !level.getBlockState(bp).getCollisionShape(level, bp).isEmpty();
			double effective = solid && !stopGiven ? SOLID_TARGET_STOP : stop;
			return control.startGoTo(new Vec3(pos[0] + 0.5, pos[1], pos[2] + 0.5), 0, effective, maxTicks);
		});
		JsonObject o = GameBridge.await(done, maxTicks * 50L + 5000);
		o.add("player", bridge.callOnGameThread(() -> Json.playerState(GameBridge.requirePlayer())));
		return o;
	}
}
