package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

/** get_inventory: non-empty inventory slots plus the selected hotbar slot. */
public final class GetInventoryTool implements McpTool {
	private final GameBridge bridge;

	public GetInventoryTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "get_inventory";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.PERCEPTION;
	}

	@Override
	public String description() {
		return "Read the player's inventory. Slots 0-8 are the hotbar, 9-35 main inventory, 36-39 armor "
				+ "(feet..head), 40 offhand. Only non-empty slots are listed. 'selectedSlot' is the active hotbar slot. "
				+ "With detail=true each item also has 'name', 'tooltip' (lines as shown on hover) and 'components' "
				+ "(non-default item data, e.g. mod data such as a seed genome or quality).";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.bool("detail", "Include name, tooltip lines and data components per item (default false).")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		boolean detail = Args.getBool(args, "detail", false);
		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			Inventory inv = p.getInventory();
			JsonArray slots = new JsonArray();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				JsonObject item = detail ? Json.itemDetail(inv.getItem(i), p) : Json.item(inv.getItem(i));
				if (item != null) {
					item.addProperty("slot", i);
					slots.add(item);
				}
			}
			JsonObject o = new JsonObject();
			o.addProperty("selectedSlot", inv.selected);
			JsonObject held = detail ? Json.itemDetail(inv.getSelected(), p) : Json.item(inv.getSelected());
			if (held != null) o.add("mainHand", held);
			o.add("slots", slots);
			return o;
		});
	}
}
