package com.mcagent.mcp.client.inventory;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.control.AgentControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Crafts with the recipe book, one craft per round, until enough items were made:
 * place recipe (server fills the grid) -> wait for the result slot -> shift-click it.
 * The server answers asynchronously, so each step waits for the client to see its effect.
 */
public final class CraftTask implements AgentControl.TickTask {
	/** Ticks to wait for the server to fill the grid before giving up. */
	private static final int RESULT_TIMEOUT_TICKS = 40;
	private static final int MAX_ROUNDS = 64;

	private enum Phase { PLACE, AWAIT_RESULT, AWAIT_TAKE }

	private final RecipeHolder<?> recipe;
	private final String itemId;
	private final int wanted;
	private final int containerId;
	private final int startCount;
	private Phase phase = Phase.PLACE;
	private int waited;
	private int rounds;

	public CraftTask(LocalPlayer player, RecipeHolder<?> recipe, String itemId, int wanted) {
		this.recipe = recipe;
		this.itemId = itemId;
		this.wanted = wanted;
		this.containerId = player.containerMenu.containerId;
		this.startCount = MenuReader.countInInventory(player.getInventory(), itemId);
	}

	@Override
	public JsonObject tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		AbstractContainerMenu menu = p.containerMenu;
		if (menu.containerId != containerId || !Recipes.isCraftingGrid(menu) || !(menu instanceof RecipeBookMenu<?, ?> cm)) {
			return result(p, "crafting menu was closed");
		}
		int resultSlot = cm.getResultSlotIndex();
		switch (phase) {
			case PLACE -> {
				if (gained(p) >= wanted) return result(p, null);
				if (rounds >= MAX_ROUNDS) return result(p, "stopped after " + MAX_ROUNDS + " crafts");
				if (!new Recipes(p).canCraft(recipe)) return result(p, "ran out of ingredients");
				mc.gameMode.handlePlaceRecipe(containerId, recipe, false);
				rounds++;
				waited = 0;
				phase = Phase.AWAIT_RESULT;
			}
			case AWAIT_RESULT -> {
				if (menu.getSlot(resultSlot).hasItem()) {
					mc.gameMode.handleInventoryMouseClick(containerId, resultSlot, 0, ClickType.QUICK_MOVE, p);
					phase = Phase.AWAIT_TAKE;
				} else if (++waited > RESULT_TIMEOUT_TICKS) {
					return result(p, "server did not fill the crafting grid (recipe not unlocked or inventory full?)");
				}
			}
			// One tick for the server to apply the take before placing the next round.
			case AWAIT_TAKE -> phase = Phase.PLACE;
		}
		return null;
	}

	@Override
	public JsonObject cancelled(String reason) {
		LocalPlayer p = Minecraft.getInstance().player;
		return p == null ? summary(0, reason) : result(p, reason);
	}

	private int gained(LocalPlayer p) {
		return MenuReader.countInInventory(p.getInventory(), itemId) - startCount;
	}

	private JsonObject result(LocalPlayer p, String reason) {
		return summary(Math.max(0, gained(p)), reason);
	}

	private JsonObject summary(int crafted, String reason) {
		JsonObject o = new JsonObject();
		o.addProperty("item", itemId);
		o.addProperty("crafted", crafted);
		o.addProperty("requested", wanted);
		o.addProperty("success", crafted >= wanted);
		if (reason != null) o.addProperty("reason", reason);
		return o;
	}
}
