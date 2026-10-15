package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/** get_entities: nearby mobs, players, dropped items etc., nearest first. */
public final class GetEntitiesTool implements McpTool {
	private final GameBridge bridge;

	public GetEntitiesTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "get_entities";
	}

	@Override
	public String description() {
		return "List entities near the player, nearest first: id (use with attack/interact), type, pos, "
				+ "distance, health for living entities, hostile=true for monsters, item for dropped items. "
				+ "Optional 'type' filter, e.g. 'minecraft:zombie' or 'zombie'.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.integer("radius", "Search radius in blocks (default 32, max 128).")
				.integer("max", "Max entities returned (default 32, max 256).")
				.string("type", "Only this entity type id.")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int radius = Args.getInt(args, "radius", 32, 1, 128);
		int max = Args.getInt(args, "max", 32, 1, 256);
		String type = Args.getString(args, "type", null);
		String typeId = type == null ? null : type.contains(":") ? type : "minecraft:" + type;

		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			AABB box = p.getBoundingBox().inflate(radius);
			List<Entity> found = Minecraft.getInstance().level.getEntities(p, box, e ->
					e.isAlive() && e.distanceToSqr(p) <= (double) radius * radius
							&& (typeId == null || typeId.equals(typeIdOf(e))));
			found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));

			JsonArray arr = new JsonArray();
			for (Entity e : found) {
				if (arr.size() >= max) break;
				JsonObject o = new JsonObject();
				o.addProperty("id", e.getId());
				o.addProperty("type", typeIdOf(e));
				if (e.hasCustomName() || e instanceof net.minecraft.world.entity.player.Player) {
					o.addProperty("name", e.getName().getString());
				}
				o.add("pos", Json.vec(e.position()));
				o.addProperty("distance", Json.round2(Math.sqrt(e.distanceToSqr(p))));
				if (e instanceof LivingEntity le) {
					o.addProperty("health", le.getHealth());
				}
				if (e instanceof Enemy) {
					o.addProperty("hostile", true);
				}
				if (e instanceof ItemEntity ie) {
					o.add("item", Json.item(ie.getItem()));
				}
				arr.add(o);
			}
			JsonObject out = new JsonObject();
			out.add("entities", arr);
			out.addProperty("total", found.size());
			return out;
		});
	}

	private static String typeIdOf(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}
}
