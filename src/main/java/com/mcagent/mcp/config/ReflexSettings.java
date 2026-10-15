package com.mcagent.mcp.config;

import com.google.gson.JsonObject;

/**
 * Switches for the client's survival reflexes: fast, per-tick reactions that keep a slow
 * (seconds-per-turn) agent alive between its turns. Loaded from {@code reflexes} in the config
 * and changeable at runtime with the {@code set_reflexes} tool; fields are volatile because
 * the game thread reads them while HTTP threads write them.
 */
public final class ReflexSettings {
	/** Hit hostile mobs in melee reach with the best hotbar weapon. */
	public volatile boolean autoDefend = true;
	/** Hit a creeper that gets close (knockback) and back away from it. */
	public volatile boolean evadeCreepers = true;
	/** Sprint at skeletons/pillagers/witches in sight (they out-range melee) while healthy. */
	public volatile boolean chargeRanged = true;
	/** Eat safe hotbar food when hungry and no enemy is close. */
	public volatile boolean autoEat = true;
	/** Put armor from the inventory into empty armor slots. */
	public volatile boolean autoArmor = true;
	/** Swim up when out of air, jump out of lava. */
	public volatile boolean escapeFluids = true;
	/** Abort the agent's running move/go_to/break_block when the player takes damage. */
	public volatile boolean interruptOnDamage = true;
	/** autoEat eats when food is below this (20 = full). */
	public volatile int eatBelowFood = 14;

	public void apply(JsonObject o) {
		if (o.has("autoDefend")) autoDefend = o.get("autoDefend").getAsBoolean();
		if (o.has("evadeCreepers")) evadeCreepers = o.get("evadeCreepers").getAsBoolean();
		if (o.has("chargeRanged")) chargeRanged = o.get("chargeRanged").getAsBoolean();
		if (o.has("autoEat")) autoEat = o.get("autoEat").getAsBoolean();
		if (o.has("autoArmor")) autoArmor = o.get("autoArmor").getAsBoolean();
		if (o.has("escapeFluids")) escapeFluids = o.get("escapeFluids").getAsBoolean();
		if (o.has("interruptOnDamage")) interruptOnDamage = o.get("interruptOnDamage").getAsBoolean();
		if (o.has("eatBelowFood")) eatBelowFood = Math.max(1, Math.min(20, o.get("eatBelowFood").getAsInt()));
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("autoDefend", autoDefend);
		o.addProperty("evadeCreepers", evadeCreepers);
		o.addProperty("chargeRanged", chargeRanged);
		o.addProperty("autoEat", autoEat);
		o.addProperty("autoArmor", autoArmor);
		o.addProperty("escapeFluids", escapeFluids);
		o.addProperty("interruptOnDamage", interruptOnDamage);
		o.addProperty("eatBelowFood", eatBelowFood);
		return o;
	}
}
