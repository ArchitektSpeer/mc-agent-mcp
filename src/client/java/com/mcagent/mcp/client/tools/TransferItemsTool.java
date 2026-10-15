package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.inventory.InventoryMath;
import com.mcagent.mcp.client.inventory.MenuReader;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.List;

/** transfer_items: shift-click whole stacks between the open container and the player inventory. */
public final class TransferItemsTool implements McpTool {
	private final GameBridge bridge;

	public TransferItemsTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "transfer_items";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.INVENTORY;
	}

	@Override
	public String description() {
		return "Move items between the open container and your inventory by shift-clicking whole stacks: "
				+ "to='player' takes from a chest/furnace output etc., to='container' stores into it (into a "
				+ "furnace this fills the fuel or input slot automatically). Optional 'item' filter and 'count' "
				+ "(moves whole stacks until at least count moved). Returns how many actually moved.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.enumeration("to", "Destination.", "player", "container")
				.string("item", "Only this item id, e.g. 'cobblestone'. Omit for everything.")
				.integer("count", "Stop once at least this many moved (default: all matching).")
				.required("to")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		if (!Args.has(args, "to")) throw new ToolException("missing required argument 'to'");
		boolean toPlayer = "player".equals(Args.getEnum(args, "to", null, "player", "container"));
		String itemId = InventoryMath.itemId(Args.getString(args, "item", null));
		int count = Args.getInt(args, "count", 0, 1, 64 * 64);

		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			AbstractContainerMenu menu = p.containerMenu;
			if (menu instanceof InventoryMenu) {
				throw new ToolException("no container is open; use interact_block on a chest/furnace first");
			}
			// Source side is the opposite of the destination.
			List<InventoryMath.Stack> source = MenuReader.stacks(menu, !toPlayer);
			int before = total(source, itemId);
			List<Integer> clicks = InventoryMath.pickStacks(source, itemId, count);
			for (int slot : clicks) {
				Minecraft.getInstance().gameMode.handleInventoryMouseClick(
						menu.containerId, slot, 0, ClickType.QUICK_MOVE, p);
			}
			int moved = before - total(MenuReader.stacks(menu, !toPlayer), itemId);
			JsonObject o = new JsonObject();
			o.addProperty("moved", moved);
			o.addProperty("stacksClicked", clicks.size());
			if (clicks.isEmpty()) {
				o.addProperty("reason", "nothing matching on the source side");
			} else if (moved == 0) {
				o.addProperty("reason", "destination full or does not accept this item");
			}
			return o;
		});
	}

	private static int total(List<InventoryMath.Stack> stacks, String itemId) {
		int n = 0;
		for (InventoryMath.Stack s : stacks) {
			if (itemId == null || itemId.equals(s.itemId())) n += s.count();
		}
		return n;
	}
}
