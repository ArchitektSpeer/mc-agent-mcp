package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.player.LocalPlayer;

/** select_slot: choose the active hotbar slot, optionally dropping items from it. */
public final class SelectSlotTool implements McpTool {
	private final GameBridge bridge;

	public SelectSlotTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "select_slot";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.INVENTORY;
	}

	@Override
	public String description() {
		return "Select hotbar slot 0-8 (see get_inventory) so its item is held in the main hand. "
				+ "drop='one' or 'all' then throws the held item(s) on the ground.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.integer("slot", "Hotbar slot 0-8; omit to keep the current one.")
				.enumeration("drop", "Drop held items after selecting (default none).", "none", "one", "all")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		boolean hasSlot = Args.has(args, "slot");
		int slot = Args.getInt(args, "slot", 0, 0, 8);
		String drop = Args.getEnum(args, "drop", "none", "none", "one", "all");

		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			if (hasSlot) p.getInventory().setSelectedSlot(slot);
			JsonObject o = new JsonObject();
			if (!"none".equals(drop)) {
				o.addProperty("dropped", p.drop("all".equals(drop)));
			}
			o.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
			JsonObject held = Json.item(p.getMainHandItem());
			if (held != null) o.add("mainHand", held);
			return o;
		});
	}
}
