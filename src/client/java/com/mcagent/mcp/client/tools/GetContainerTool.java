package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.inventory.MenuReader;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.client.Minecraft;

/** get_container: the open container (chest, furnace, crafting table...) or the player's own inventory menu. */
public final class GetContainerTool implements McpTool {
	private final GameBridge bridge;

	public GetContainerTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "get_container";
	}

	@Override
	public String description() {
		return "Read the open container menu (open one with interact_block). Lists non-empty slots by menu "
				+ "'slot' index (what click_slot takes) with a 'section': container, player (plus its get_inventory "
				+ "'invSlot'), grid, result, fuel. Also 'emptyContainerSlots', the item on the cursor ('carried') and "
				+ "furnace progress. With nothing open it describes the inventory menu (type 'inventory': slot 0 "
				+ "result, 1-4 the 2x2 grid, 5-8 armor, 9-35 main, 36-44 hotbar, 45 offhand).";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object().build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		return bridge.callOnGameThread(() ->
				MenuReader.describe(Minecraft.getInstance(), GameBridge.requirePlayer().containerMenu));
	}
}
