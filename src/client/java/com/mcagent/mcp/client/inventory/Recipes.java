package com.mcagent.mcp.client.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the client recipe book. Only recipes the player has unlocked are listed, which are
 * exactly the ones the server lets the recipe book place. Game thread only.
 *
 * <p>1.21.1 still syncs full recipes to the client: the book's collections hold every
 * recipe, and {@link ClientRecipeBook#contains} tells which ones are unlocked.
 */
public final class Recipes {
	private static final int MAX_ALTERNATIVES = 4;

	private final LocalPlayer player;
	private final RegistryAccess registries;
	private final StackedContents available;

	public Recipes(LocalPlayer player) {
		this.player = player;
		this.registries = player.level().registryAccess();
		this.available = new StackedContents();
		player.getInventory().fillStackedContents(available);
		if (player.containerMenu instanceof RecipeBookMenu<?, ?> m) {
			// Items already sitting in the crafting grid count as available too.
			m.fillCraftSlotsStackedContents(available);
		}
	}

	public List<RecipeHolder<?>> all() {
		ClientRecipeBook book = player.getRecipeBook();
		List<RecipeHolder<?>> out = new ArrayList<>();
		for (RecipeCollection c : book.getCollections()) {
			for (RecipeHolder<?> r : c.getRecipes()) {
				if (book.contains(r)) out.add(r);
			}
		}
		return out;
	}

	public String resultId(RecipeHolder<?> e) {
		ItemStack st = e.value().getResultItem(registries);
		return st.isEmpty() ? null : MenuReader.itemId(st);
	}

	public int resultCount(RecipeHolder<?> e) {
		return e.value().getResultItem(registries).getCount();
	}

	public boolean canCraft(RecipeHolder<?> e) {
		return available.canCraft(e.value(), null);
	}

	/** A crafting-grid recipe the recipe book can place (special recipes like dyeing are not). */
	public static boolean isCrafting(RecipeHolder<?> e) {
		return e.value() instanceof CraftingRecipe r && !r.isSpecial();
	}

	/** The 2x2 inventory grid and the 3x3 crafting table: the menus {@code craft} works in. */
	public static boolean isCraftingGrid(AbstractContainerMenu menu) {
		return menu instanceof CraftingMenu || menu instanceof InventoryMenu;
	}

	/** Whether a crafting recipe fits the grid of {@code menu} (2x2 inventory or 3x3 table). */
	public static boolean fits(RecipeHolder<?> e, AbstractContainerMenu menu) {
		if (!isCraftingGrid(menu) || !(menu instanceof RecipeBookMenu<?, ?> m)) return false;
		return e.value().canCraftInDimensions(m.getGridWidth(), m.getGridHeight());
	}

	/** Where the recipe can be made: "inventory" (fits 2x2), "crafting_table", or the station item id. */
	public String station(RecipeHolder<?> e) {
		Recipe<?> r = e.value();
		if (r instanceof CraftingRecipe) {
			return r.canCraftInDimensions(2, 2) ? "inventory" : "crafting_table";
		}
		ItemStack st = r.getToastSymbol();
		return st.isEmpty() ? "unknown" : MenuReader.itemId(st);
	}

	public JsonObject describe(RecipeHolder<?> e) {
		JsonObject o = new JsonObject();
		o.addProperty("result", resultId(e));
		o.addProperty("count", resultCount(e));
		o.addProperty("station", station(e));
		o.addProperty("craftable", canCraft(e));
		List<Ingredient> reqs = e.value().getIngredients().stream().filter(i -> !i.isEmpty()).toList();
		if (!reqs.isEmpty()) o.add("ingredients", ingredients(reqs));
		return o;
	}

	/** Ingredients grouped by identical alternatives: [{anyOf:[ids...], count}]. */
	private static JsonArray ingredients(List<Ingredient> reqs) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, List<String>> alts = new LinkedHashMap<>();
		for (Ingredient ing : reqs) {
			List<String> ids = new ArrayList<>();
			for (ItemStack st : ing.getItems()) ids.add(BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
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
}
