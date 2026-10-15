package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.client.inventory.CraftTask;
import com.mcagent.mcp.client.inventory.InventoryMath;
import com.mcagent.mcp.client.inventory.Recipes;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

import java.util.concurrent.CompletableFuture;

/** craft: make an item through the recipe book in the 2x2 inventory grid or an open crafting table. */
public final class CraftTool implements McpTool {
	private final GameBridge bridge;
	private final AgentControl control;

	public CraftTool(GameBridge bridge, AgentControl control) {
		this.bridge = bridge;
		this.control = control;
	}

	@Override
	public String name() {
		return "craft";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.INVENTORY;
	}

	@Override
	public String description() {
		return "Craft 'item' until at least 'count' were made (default: one craft). Uses the 2x2 inventory grid "
				+ "when no screen is open, or the 3x3 grid of a crafting table opened with interact_block. Only "
				+ "unlocked recipes work (see get_recipes). Waits and returns {crafted, success, reason?}. "
				+ "For smelting, open a furnace and use transfer_items to='container' with the ore and the fuel.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.string("item", "Item to make, e.g. 'oak_planks', 'stick', 'minecraft:crafting_table'.")
				.integer("count", "Minimum number of items to end up with (default 1, max 576).")
				.required("item")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		String itemId = InventoryMath.itemId(Args.requireString(args, "item"));
		int count = Args.getInt(args, "count", 1, 1, 576);

		CompletableFuture<JsonObject> done = bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			if (!(p.containerMenu instanceof AbstractCraftingMenu)) {
				throw new ToolException("the open screen is not a crafting grid; close it (stop closeScreen=true) "
						+ "or open a crafting table");
			}
			if (!p.containerMenu.getCarried().isEmpty()) {
				throw new ToolException("an item is on the cursor; put it down with click_slot first");
			}
			Recipes recipes = new Recipes(p);
			RecipeDisplayEntry best = null;
			boolean known = false, tooBig = false;
			for (RecipeDisplayEntry e : recipes.all()) {
				if (!Recipes.isCrafting(e.display()) || !itemId.equals(recipes.resultId(e))) continue;
				known = true;
				if (!Recipes.fits(e.display(), p.containerMenu)) {
					tooBig = true;
					continue;
				}
				if (recipes.canCraft(e)) {
					best = e;
					break;
				}
			}
			if (best == null) {
				if (!known) throw new ToolException("no unlocked crafting recipe for " + itemId + " (see get_recipes)");
				if (tooBig && p.containerMenu instanceof InventoryMenu) {
					throw new ToolException(itemId + " needs a crafting table: place one and interact_block it");
				}
				throw new ToolException("missing ingredients for " + itemId + " (get_recipes item=" + itemId + ")");
			}
			return control.startMenuTask(new CraftTask(p, best, itemId, count));
		});
		// Each craft takes ~3 ticks of server round trips.
		return GameBridge.await(done, Math.min(count, 64) * 400L + 10000);
	}
}
