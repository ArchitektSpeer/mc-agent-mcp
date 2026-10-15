package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Shared JSON shapes for the perception/actuation tools. */
final class Json {
	private Json() {
	}

	static JsonArray pos(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}

	/** Position rounded to centimetres: plenty for an agent, and keeps payloads short. */
	static JsonArray vec(Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(round2(v.x));
		a.add(round2(v.y));
		a.add(round2(v.z));
		return a;
	}

	static double round2(double d) {
		return Math.round(d * 100.0) / 100.0;
	}

	/** {id, count[, name][, damage, maxDamage]}; null for an empty stack. */
	static JsonObject item(ItemStack s) {
		if (s.isEmpty()) return null;
		JsonObject o = new JsonObject();
		o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
		o.addProperty("count", s.getCount());
		if (s.getMaxDamage() > 0) {
			o.addProperty("damage", s.getDamageValue());
			o.addProperty("maxDamage", s.getMaxDamage());
		}
		return o;
	}

	/** Compact player state returned after actions, so the agent rarely needs a separate observe. */
	static JsonObject playerState(LocalPlayer p) {
		JsonObject o = new JsonObject();
		o.add("position", vec(p.position()));
		o.addProperty("yaw", round2(p.getYRot()));
		o.addProperty("pitch", round2(p.getXRot()));
		o.addProperty("onGround", p.onGround());
		o.addProperty("health", p.getHealth());
		return o;
	}
}
