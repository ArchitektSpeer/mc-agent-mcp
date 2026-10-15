package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.McpTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Snapshot of the local player: position, rotation, health, food, dimension, world time.
 * Read-only; runs on the game thread via {@link GameBridge}.
 */
public final class ObserveTool implements McpTool {
	private final GameBridge bridge;

	public ObserveTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "observe";
	}

	@Override
	public String description() {
		return "Observe the local player and world: position, rotation, health, hunger, dimension, game time. "
				+ "Returns inWorld=false when no world is loaded.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", new JsonObject());
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		return bridge.callOnGameThread(() -> {
			JsonObject o = new JsonObject();
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer p = mc.player;
			if (mc.level == null || p == null) {
				o.addProperty("inWorld", false);
				return o;
			}
			o.addProperty("inWorld", true);
			o.addProperty("name", p.getName().getString());
			JsonObject pos = new JsonObject();
			pos.addProperty("x", p.getX());
			pos.addProperty("y", p.getY());
			pos.addProperty("z", p.getZ());
			o.add("position", pos);
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());
			o.addProperty("onGround", p.onGround());
			o.addProperty("health", p.getHealth());
			o.addProperty("maxHealth", p.getMaxHealth());
			o.addProperty("food", p.getFoodData().getFoodLevel());
			o.addProperty("saturation", p.getFoodData().getSaturationLevel());
			o.addProperty("dimension", mc.level.dimension().identifier().toString());
			o.addProperty("gameTime", mc.level.getGameTime());
			return o;
		});
	}
}
