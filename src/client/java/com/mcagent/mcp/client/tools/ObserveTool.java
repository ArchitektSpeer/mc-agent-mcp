package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.McpTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

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
		return "Observe the local player and world: position, rotation, health, hunger, armor, air, held item, "
				+ "attack cooldown, open screen, crosshair target, dead flag, dimension, time of day, weather. "
				+ "Call this first each turn. "
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
			// Everything below is what an agent needs to decide its next action in one call.
			o.addProperty("dead", p.isDeadOrDying());
			o.addProperty("armor", p.getArmorValue());
			o.addProperty("air", p.getAirSupply());
			o.addProperty("inWater", p.isInWater());
			o.addProperty("onFire", p.isOnFire());
			o.addProperty("xpLevel", p.experienceLevel);
			o.addProperty("timeOfDay", mc.level.getDayTime() % 24000L);
			o.addProperty("raining", mc.level.isRaining());
			o.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
			JsonObject held = Json.item(p.getMainHandItem());
			if (held != null) o.add("mainHand", held);
			o.addProperty("attackStrength", Json.round2(p.getAttackStrengthScale(0.5F)));
			if (mc.screen != null) o.addProperty("screen", mc.screen.getClass().getSimpleName());
			o.add("crosshair", crosshair(mc));
			if (AgentControl.instance != null) o.add("agent", AgentControl.instance.status());
			return o;
		});
	}

	/** What the crosshair points at: {type:"block",pos,block,face} | {type:"entity",id,entity} | {type:"miss"}. */
	private static JsonObject crosshair(Minecraft mc) {
		JsonObject o = new JsonObject();
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			o.addProperty("type", "block");
			o.add("pos", Json.pos(bh.getBlockPos()));
			o.addProperty("block", BlockScanner.idOf(mc.level.getBlockState(bh.getBlockPos())));
			o.addProperty("face", bh.getDirection().getName());
		} else if (hit instanceof EntityHitResult eh) {
			o.addProperty("type", "entity");
			o.addProperty("id", eh.getEntity().getId());
			o.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(eh.getEntity().getType()).toString());
		} else {
			o.addProperty("type", "miss");
		}
		return o;
	}
}
