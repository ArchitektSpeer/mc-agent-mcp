package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.inventory.InventoryMath;
import com.mcagent.mcp.client.inventory.Recipes;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/** get_recipes: unlocked recipes from the recipe book, with ingredients and craftability. */
public final class GetRecipesTool implements McpTool {
	private final GameBridge bridge;

	public GetRecipesTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "get_recipes";
	}

	@Override
	public String description() {
		return "Look up recipes the player has unlocked (recipes unlock as you collect ingredients). Filter by "
				+ "result 'item' and/or craftable=true (enough ingredients in inventory now). Each entry gives result, "
				+ "count, ingredients [{anyOf, count}] and 'station': 'inventory' (2x2, no table needed), "
				+ "'crafting_table', or e.g. 'minecraft:furnace'.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.string("item", "Result item id, e.g. 'stick' or 'minecraft:crafting_table'.")
				.bool("craftable", "Only recipes craftable right now (default false).")
				.integer("max", "Max entries (default 20, max 200).")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		String itemId = InventoryMath.itemId(Args.getString(args, "item", null));
		boolean craftableOnly = Args.getBool(args, "craftable", false);
		int max = Args.getInt(args, "max", 20, 1, 200);

		return bridge.callOnGameThread(() -> {
			Recipes recipes = new Recipes(GameBridge.requirePlayer());
			JsonArray out = new JsonArray();
			int matched = 0;
			for (RecipeDisplayEntry e : recipes.all()) {
				if (itemId != null && !itemId.equals(recipes.resultId(e))) continue;
				if (craftableOnly && !recipes.canCraft(e)) continue;
				matched++;
				if (out.size() < max) out.add(recipes.describe(e));
			}
			JsonObject o = new JsonObject();
			o.add("recipes", out);
			o.addProperty("matched", matched);
			if (matched == 0 && itemId != null) {
				o.addProperty("hint", "no unlocked recipe makes " + itemId
						+ "; collect one of its ingredients to unlock it, or it may need a furnace/smithing");
			}
			return o;
		});
	}
}
