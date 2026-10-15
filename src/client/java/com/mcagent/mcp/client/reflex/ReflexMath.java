package com.mcagent.mcp.client.reflex;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure decisions behind the survival reflexes (no Minecraft types, unit-tested offline):
 * which weapon, which food, which armor piece.
 */
public final class ReflexMath {
	private ReflexMath() {
	}

	/** Base attack damage of a sword per material (vanilla values; attack speed 1.6/s). */
	private static final Map<String, Double> SWORD = Map.of(
			"wooden", 4.0, "golden", 4.0, "stone", 5.0, "copper", 5.0, "iron", 6.0, "diamond", 7.0, "netherite", 8.0);
	/** Axes hit harder but slower; {damage, attacks per second}. */
	private static final Map<String, double[]> AXE = Map.of(
			"wooden", new double[]{7, 0.8}, "golden", new double[]{7, 1.0}, "stone", new double[]{9, 0.8},
			"copper", new double[]{9, 0.8}, "iron", new double[]{9, 0.9}, "diamond", new double[]{9, 1.0},
			"netherite", new double[]{10, 1.0});

	/** Never eaten automatically: harmful, teleporting, or too valuable to spend on hunger. */
	private static final Set<String> UNSAFE_FOOD = Set.of(
			"minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:poisonous_potato", "minecraft:pufferfish",
			"minecraft:chorus_fruit", "minecraft:suspicious_stew", "minecraft:raw_chicken");
	private static final Set<String> PRECIOUS_FOOD = Set.of(
			"minecraft:golden_apple", "minecraft:enchanted_golden_apple", "minecraft:golden_carrot");

	private static final Map<String, Integer> ARMOR_TIER = Map.of(
			"leather", 1, "golden", 2, "copper", 2, "chainmail", 3, "turtle", 3, "iron", 4, "diamond", 5, "netherite", 6);

	/**
	 * Sustained melee damage per second of an item, assuming full-strength hits.
	 * 0 for anything that is not a sword or axe (fists are not worth switching to).
	 */
	public static double weaponDps(String itemId) {
		String path = path(itemId);
		int us = path.lastIndexOf('_');
		if (us < 0) return 0;
		String material = path.substring(0, us), type = path.substring(us + 1);
		if (type.equals("sword") && SWORD.containsKey(material)) return SWORD.get(material) * 1.6;
		if (type.equals("axe") && AXE.containsKey(material)) {
			double[] a = AXE.get(material);
			return a[0] * a[1];
		}
		if (path.equals("trident")) return 9 * 1.1;
		if (path.equals("mace")) return 6 * 0.6;
		return 0;
	}

	/** A hotbar slot holding an item. */
	public record Held(int slot, String itemId, int nutrition) {
	}

	/** Best weapon slot, or -1 when the hotbar has none. Ties keep the lower slot. */
	public static int bestWeapon(List<Held> hotbar) {
		int best = -1;
		double bestDps = 0;
		for (Held h : hotbar) {
			double d = weaponDps(h.itemId());
			if (d > bestDps) {
				bestDps = d;
				best = h.slot();
			}
		}
		return best;
	}

	/** Should the reflex eat now? Hungry, or hurt and not full (food regenerates health). */
	public static boolean wantsToEat(int food, float health, int eatBelowFood) {
		return food < eatBelowFood || (health < 10 && food < 18);
	}

	/**
	 * Food slot to eat, or -1. Prefers the item that fills the hunger gap best without waste;
	 * golden food only in an emergency (health below 6).
	 */
	public static int pickFood(List<Held> items, int food, float health) {
		int gap = 20 - food;
		int best = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Held h : items) {
			if (h.nutrition() <= 0 || UNSAFE_FOOD.contains(h.itemId())) continue;
			if (PRECIOUS_FOOD.contains(h.itemId()) && health >= 6) continue;
			// Fill as much of the gap as possible; overshoot costs half as much as it gains.
			int n = h.nutrition();
			double score = n <= gap ? n : gap - (n - gap) * 0.5;
			if (score > bestScore) {
				bestScore = score;
				best = h.slot();
			}
		}
		return best;
	}

	/** Armor quality for choosing between pieces for an empty slot; 0 when unknown. */
	public static int armorTier(String itemId) {
		String path = path(itemId);
		int us = path.indexOf('_');
		return us < 0 ? 0 : ARMOR_TIER.getOrDefault(path.substring(0, us), 0);
	}

	private static String path(String id) {
		int c = id.indexOf(':');
		return c < 0 ? id : id.substring(c + 1);
	}
}
