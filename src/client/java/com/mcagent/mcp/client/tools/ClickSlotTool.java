package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

/** click_slot: one raw inventory click, exactly as a player would do it with the mouse. */
public final class ClickSlotTool implements McpTool {
	/** Vanilla's slot id for clicking outside the window (drops the carried stack). */
	public static final int OUTSIDE = -999;

	private final GameBridge bridge;

	public ClickSlotTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "click_slot";
	}

	@Override
	public String description() {
		return "Click a slot of the open menu (indices from get_container). Actions: 'left' picks up / puts "
				+ "down / swaps the whole stack with the cursor, 'right' picks up half or puts down one, 'shift' "
				+ "moves the stack to the other side (fuel/ores go into furnace slots automatically), 'swap' "
				+ "exchanges with hotbar slot 'hotbar' (0-8, 40 = offhand), 'drop'/'drop_all' throw one/all, "
				+ "'collect' gathers matching items onto the cursor. slot=-999 with left/right drops the cursor stack. "
				+ "Returns the clicked slot and cursor afterwards.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.integer("slot", "Menu slot index, or -999 for outside the window.")
				.enumeration("action", "Click kind (default left).",
						"left", "right", "shift", "swap", "drop", "drop_all", "collect")
				.integer("hotbar", "For 'swap': hotbar slot 0-8 or 40 for offhand.")
				.required("slot")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		if (!Args.has(args, "slot")) throw new ToolException("missing required argument 'slot'");
		int slot = Args.getInt(args, "slot", 0, OUTSIDE, 1000);
		String action = Args.getEnum(args, "action", "left",
				"left", "right", "shift", "swap", "drop", "drop_all", "collect");
		int hotbar = Args.getInt(args, "hotbar", -1, 0, 40);
		if ("swap".equals(action) && !(hotbar >= 0 && hotbar <= 8 || hotbar == 40)) {
			throw new ToolException("'swap' needs 'hotbar' 0-8 or 40");
		}
		ClickType type;
		int button;
		switch (action) {
			case "right" -> { type = ClickType.PICKUP; button = 1; }
			case "shift" -> { type = ClickType.QUICK_MOVE; button = 0; }
			case "swap" -> { type = ClickType.SWAP; button = hotbar; }
			case "drop" -> { type = ClickType.THROW; button = 0; }
			case "drop_all" -> { type = ClickType.THROW; button = 1; }
			case "collect" -> { type = ClickType.PICKUP_ALL; button = 0; }
			default -> { type = ClickType.PICKUP; button = 0; }
		}

		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			AbstractContainerMenu menu = p.containerMenu;
			boolean outside = slot == OUTSIDE;
			if (outside && type != ClickType.PICKUP) throw new ToolException("slot -999 only supports left/right");
			if (!outside && (slot < 0 || !menu.isValidSlotIndex(slot))) {
				throw new ToolException("no slot " + slot + " in this menu (" + menu.slots.size() + " slots)");
			}
			Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, p);

			JsonObject o = new JsonObject();
			if (!outside) {
				var stack = menu.getSlot(slot).getItem();
				o.add("slot", stack.isEmpty() ? null : Json.item(stack));
			}
			// Client-side prediction; the server confirms (or corrects) within a tick.
			o.add("carried", Json.item(menu.getCarried()));
			return o;
		});
	}
}
