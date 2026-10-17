package com.mcagent.mcp.client.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.FurnaceFuelSlot;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Describes the open container menu (or the player's own inventory menu) for the agent. Game thread only. */
public final class MenuReader {
	private MenuReader() {
	}

	/** Which part of the menu a slot belongs to. "player" slots also carry the get_inventory slot number. */
	public static String section(Slot slot) {
		if (slot instanceof ResultSlot || slot instanceof FurnaceResultSlot) return "result";
		if (slot instanceof FurnaceFuelSlot) return "fuel";
		if (slot.container instanceof Inventory) return "player";
		if (slot.container instanceof CraftingContainer) return "grid";
		return "container";
	}

	public static boolean isPlayerSlot(Slot slot) {
		return slot.container instanceof Inventory;
	}

	/** Menu type id; the player's own inventory menu has no registered type. */
	public static String typeId(AbstractContainerMenu menu) {
		if (menu instanceof InventoryMenu) return "inventory";
		try {
			var key = BuiltInRegistries.MENU.getKey(menu.getType());
			return key == null ? menu.getClass().getSimpleName() : key.toString();
		} catch (UnsupportedOperationException e) {
			return menu.getClass().getSimpleName();
		}
	}

	public static String itemId(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	public static JsonObject describe(Minecraft mc, AbstractContainerMenu menu) {
		JsonObject o = new JsonObject();
		o.addProperty("containerId", menu.containerId);
		o.addProperty("type", typeId(menu));
		o.addProperty("open", !(menu instanceof InventoryMenu));
		if (mc.screen != null) o.addProperty("title", mc.screen.getTitle().getString());

		JsonArray slots = new JsonArray();
		JsonArray emptyContainerSlots = new JsonArray();
		for (Slot slot : menu.slots) {
			String section = section(slot);
			ItemStack s = slot.getItem();
			if (s.isEmpty()) {
				if (!"player".equals(section)) emptyContainerSlots.add(slot.index);
				continue;
			}
			JsonObject e = new JsonObject();
			e.addProperty("slot", slot.index);
			e.addProperty("section", section);
			if ("player".equals(section)) e.addProperty("invSlot", slot.getContainerSlot());
			e.addProperty("id", itemId(s));
			e.addProperty("count", s.getCount());
			slots.add(e);
		}
		o.add("slots", slots);
		o.add("emptyContainerSlots", emptyContainerSlots);
		ItemStack carried = menu.getCarried();
		if (!carried.isEmpty()) {
			JsonObject c = new JsonObject();
			c.addProperty("id", itemId(carried));
			c.addProperty("count", carried.getCount());
			o.add("carried", c);
		}
		if (menu instanceof AbstractFurnaceMenu f) {
			JsonObject furnace = new JsonObject();
			furnace.addProperty("lit", f.isLit());
			furnace.addProperty("progress", Math.round(f.getBurnProgress() * 100) / 100.0);
			furnace.addProperty("fuelLeft", Math.round(f.getLitProgress() * 100) / 100.0);
			o.add("furnace", furnace);
		}
		return o;
	}

	/** Occupied slots on one side of the menu, for {@link InventoryMath#pickStacks}. */
	public static List<InventoryMath.Stack> stacks(AbstractContainerMenu menu, boolean playerSide) {
		List<InventoryMath.Stack> out = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (isPlayerSlot(slot) != playerSide || !slot.hasItem()) continue;
			// Never pull a crafting result implicitly: taking it consumes the grid's ingredients.
			// Furnace output is just a finished product and is the main thing to take.
			if (!playerSide && Recipes.isCraftingGrid(menu) && "result".equals(section(slot))) continue;
			out.add(new InventoryMath.Stack(slot.index, itemId(slot.getItem()), slot.getItem().getCount()));
		}
		return out;
	}

	/** Total count of an item in the player's inventory (not the open container). */
	public static int countInInventory(Inventory inv, String itemId) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && itemId(s).equals(itemId)) n += s.getCount();
		}
		return n;
	}
}
