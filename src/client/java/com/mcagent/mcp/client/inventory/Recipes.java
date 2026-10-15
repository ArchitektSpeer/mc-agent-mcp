package com.mcagent.mcp.client.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads the client recipe book. It only holds recipes the player has unlocked, which are
 * exactly the ones the server lets the recipe book place. Game thread only.
 */
public final class Recipes {
	private static final int MAX_ALTERNATIVES = 4;

	private final LocalPlayer player;
	private final ContextMap ctx;
	private final StackedItemContents available;

	public Recipes(LocalPlayer player) {
		this.player = player;
		this.ctx = SlotDisplayContext.fromLevel(player.level());
		this.available = new StackedItemContents();
		player.getInventory().fillStackedContents(available);
		if (player.containerMenu instanceof RecipeBookMenu m) {
			// Items already sitting in the crafting grid count as available too.
			m.fillCraftSlotsStackedContents(available);
		}
	}

	public List<RecipeDisplayEntry> all() {
		ClientRecipeBook book = player.getRecipeBook();
		List<RecipeDisplayEntry> out = new ArrayList<>();
		for (RecipeCollection c : book.getCollections()) out.addAll(c.getRecipes());
		return out;
	}

	public String resultId(RecipeDisplayEntry e) {
		List<ItemStack> items = e.resultItems(ctx);
		return items.isEmpty() ? null : MenuReader.itemId(items.get(0));
	}

	public int resultCount(RecipeDisplayEntry e) {
		List<ItemStack> items = e.resultItems(ctx);
		return items.isEmpty() ? 0 : items.get(0).getCount();
	}

	public boolean canCraft(RecipeDisplayEntry e) {
		return e.canCraft(available);
	}

	public static boolean isCrafting(RecipeDisplay d) {
		return d instanceof ShapedCraftingRecipeDisplay || d instanceof ShapelessCraftingRecipeDisplay;
	}

	/** Whether a crafting recipe fits the grid of {@code menu} (2x2 inventory or 3x3 table). */
	public static boolean fits(RecipeDisplay d, AbstractContainerMenu menu) {
		if (!(menu instanceof AbstractCraftingMenu cm)) return false;
		int side = (int) Math.round(Math.sqrt(cm.getInputGridSlots().size()));
		if (d instanceof ShapedCraftingRecipeDisplay s) {
			return InventoryMath.fitsGrid(true, s.width(), s.height(), s.ingredients().size(), side);
		}
		if (d instanceof ShapelessCraftingRecipeDisplay s) {
			return InventoryMath.fitsGrid(false, 0, 0, s.ingredients().size(), side);
		}
		return false;
	}

	/** Where the recipe can be made: "inventory" (fits 2x2), "crafting_table", or the station item id. */
	public String station(RecipeDisplay d) {
		if (d instanceof ShapedCraftingRecipeDisplay s) {
			return InventoryMath.fitsGrid(true, s.width(), s.height(), 0, 2) ? "inventory" : "crafting_table";
		}
		if (d instanceof ShapelessCraftingRecipeDisplay s) {
			return InventoryMath.fitsGrid(false, 0, 0, s.ingredients().size(), 2) ? "inventory" : "crafting_table";
		}
		ItemStack st = d.craftingStation().resolveForFirstStack(ctx);
		return st.isEmpty() ? "unknown" : MenuReader.itemId(st);
	}

	public JsonObject describe(RecipeDisplayEntry e) {
		JsonObject o = new JsonObject();
		o.addProperty("result", resultId(e));
		o.addProperty("count", resultCount(e));
		o.addProperty("station", station(e.display()));
		o.addProperty("craftable", canCraft(e));
		e.craftingRequirements().ifPresent(reqs -> o.add("ingredients", ingredients(reqs)));
		return o;
	}

	/** Ingredients grouped by identical alternatives: [{anyOf:[ids...], count}]. */
	private static JsonArray ingredients(List<Ingredient> reqs) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, List<String>> alts = new LinkedHashMap<>();
		for (Ingredient ing : reqs) {
			List<String> ids = ing.items().map(Recipes::idOf).collect(Collectors.toList());
			String key = String.join("|", ids);
			counts.merge(key, 1, Integer::sum);
			alts.putIfAbsent(key, ids);
		}
		JsonArray out = new JsonArray();
		for (var entry : counts.entrySet()) {
			JsonObject i = new JsonObject();
			JsonArray any = new JsonArray();
			List<String> ids = alts.get(entry.getKey());
			for (int k = 0; k < Math.min(ids.size(), MAX_ALTERNATIVES); k++) any.add(ids.get(k));
			if (ids.size() > MAX_ALTERNATIVES) any.add("... +" + (ids.size() - MAX_ALTERNATIVES) + " more");
			i.add("anyOf", any);
			i.addProperty("count", entry.getValue());
			out.add(i);
		}
		return out;
	}

	private static String idOf(Holder<Item> h) {
		return BuiltInRegistries.ITEM.getKey(h.value()).toString();
	}
}
