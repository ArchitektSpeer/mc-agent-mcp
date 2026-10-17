package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import com.mcagent.mcp.client.reflex.Reflexes;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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

	/**
	 * {@link #item} plus everything a mod tester needs: the display name, the tooltip lines as the
	 * player would see them, and the non-default data components (e.g. a seed's genome).
	 */
	static JsonObject itemDetail(ItemStack s, LocalPlayer p) {
		JsonObject o = item(s);
		if (o == null) return null;
		o.addProperty("name", s.getHoverName().getString());
		List<Component> lines = s.getTooltipLines(Item.TooltipContext.of(p.level()), p, TooltipFlag.Default.NORMAL);
		JsonArray tooltip = new JsonArray();
		for (int i = 1; i < lines.size(); i++) tooltip.add(lines.get(i).getString()); // line 0 is the name
		o.add("tooltip", tooltip);
		DataComponentPatch patch = s.getComponentsPatch();
		if (!patch.isEmpty()) {
			DataComponentPatch.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), patch)
					.result().ifPresent(json -> o.add("components", json));
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
		o.addProperty("food", p.getFoodData().getFoodLevel());
		JsonArray threats = Reflexes.describeThreats(net.minecraft.client.Minecraft.getInstance(), p, 3);
		if (!threats.isEmpty()) o.add("threats", threats);
		return o;
	}
}
