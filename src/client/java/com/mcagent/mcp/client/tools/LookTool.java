package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** look: set rotation directly, or turn toward a block or entity. */
public final class LookTool implements McpTool {
	private final GameBridge bridge;

	public LookTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "look";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.MOVEMENT;
	}

	@Override
	public String description() {
		return "Turn the player. Give exactly one of: 'yaw'/'pitch' in degrees (pitch -90 up .. 90 down; "
				+ "yaw 0=south, 90=west, 180=north, -90=east), 'at' a block position [x,y,z] (looks at its center), "
				+ "or 'entityId' from get_entities. Returns the new rotation.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.number("yaw", "Absolute yaw in degrees.")
				.number("pitch", "Absolute pitch in degrees.")
				.pos("at", "Block to look at.")
				.integer("entityId", "Entity to look at.")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int[] at = Args.getPos(args, "at");
		boolean hasEntity = Args.has(args, "entityId");
		int entityId = Args.getInt(args, "entityId", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
		boolean hasYaw = Args.has(args, "yaw"), hasPitch = Args.has(args, "pitch");
		double yaw = Args.getDouble(args, "yaw", 0), pitch = Args.getDouble(args, "pitch", 0);
		int modes = (at != null ? 1 : 0) + (hasEntity ? 1 : 0) + (hasYaw || hasPitch ? 1 : 0);
		if (modes != 1) {
			throw new ToolException("give exactly one of yaw/pitch, at, entityId");
		}

		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			if (at != null) {
				AgentControl.lookAt(p, Vec3.atCenterOf(new net.minecraft.core.BlockPos(at[0], at[1], at[2])));
			} else if (hasEntity) {
				Entity e = Minecraft.getInstance().level.getEntity(entityId);
				if (e == null) throw new ToolException("no entity with id " + entityId);
				AgentControl.lookAt(p, e.getBoundingBox().getCenter());
			} else {
				if (hasYaw) {
					p.setYRot((float) yaw);
					p.setYHeadRot((float) yaw);
				}
				if (hasPitch) p.setXRot(Mth.clamp((float) pitch, -90.0F, 90.0F));
			}
			// The crosshair target (mc.hitResult) is recomputed during rendering, so report
			// the rotation only; observe returns the crosshair target.
			JsonObject o = new JsonObject();
			o.addProperty("yaw", Json.round2(p.getYRot()));
			o.addProperty("pitch", Json.round2(p.getXRot()));
			return o;
		});
	}
}
