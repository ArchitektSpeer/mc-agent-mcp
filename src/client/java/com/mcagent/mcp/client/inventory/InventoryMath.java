package com.mcagent.mcp.client.inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decisions behind the inventory tools (no Minecraft types, unit-tested offline):
 * which stacks to shift-click for a transfer, and whether a recipe fits a crafting grid.
 */
public final class InventoryMath {
	private InventoryMath() {
	}

	/** One occupied menu slot as seen by the planner. */
	public record Stack(int slot, String itemId, int count) {
	}

	/**
	 * Stacks to shift-click so that at least {@code wanted} items of {@code itemId} move
	 * (shift-click moves whole stacks, so the total may overshoot by less than one stack).
	 * Largest stacks first, to move the amount in as few clicks as possible.
	 *
	 * @param itemId null for any item
	 * @param wanted {@code <= 0} for everything that matches
	 */
	public static List<Integer> pickStacks(List<Stack> stacks, String itemId, int wanted) {
		List<Stack> matching = new ArrayList<>();
		for (Stack s : stacks) {
			if (s.count() > 0 && (itemId == null || itemId.equals(s.itemId()))) matching.add(s);
		}
		matching.sort((a, b) -> b.count() != a.count()
				? Integer.compare(b.count(), a.count()) : Integer.compare(a.slot(), b.slot()));
		List<Integer> out = new ArrayList<>();
		int total = 0;
		for (Stack s : matching) {
			if (wanted > 0 && total >= wanted) break;
			out.add(s.slot());
			total += s.count();
		}
		return out;
	}

	/**
	 * Whether a crafting recipe fits a square grid of {@code gridSide} (2 = inventory, 3 = table).
	 * Shaped recipes need their width and height to fit; shapeless ones only enough cells.
	 */
	public static boolean fitsGrid(boolean shaped, int width, int height, int ingredientCount, int gridSide) {
		if (shaped) return width <= gridSide && height <= gridSide;
		return ingredientCount <= gridSide * gridSide;
	}

	/** Normalise "oak_log" to "minecraft:oak_log"; null stays null. */
	public static String itemId(String name) {
		if (name == null) return null;
		String n = name.trim().toLowerCase(java.util.Locale.ROOT);
		return n.contains(":") ? n : "minecraft:" + n;
	}
}
