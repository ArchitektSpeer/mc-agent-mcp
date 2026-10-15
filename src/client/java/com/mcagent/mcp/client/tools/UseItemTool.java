package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import java.util.concurrent.CompletableFuture;

/** use_item: right-click with the held item in the air, optionally held down (eat, drink, bow, shield). */
public final class UseItemTool implements McpTool {
	private final GameBridge bridge;
	private final AgentControl control;

	public UseItemTool(GameBridge bridge, AgentControl control) {
		this.bridge = bridge;
		this.control = control;
	}

	@Override
	public String name() {
		return "use_item";
	}

	@Override
	public String description() {
		return "Use the held item without targeting a block. ticks=0 (default) is a single right click "
				+ "(throw snowball/ender pearl, drink-free items). ticks>0 holds right click: eating/drinking "
				+ "needs ~32 ticks, a full bow draw ~20 (releasing fires). Waits and returns the updated food/health.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.integer("ticks", "Hold duration in ticks (default 0 = single click, max " + AgentControl.MAX_TICKS + ").")
				.enumeration("hand", "Hand for a single click (default main).", "main", "off")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		int ticks = Args.getInt(args, "ticks", 0, 0, AgentControl.MAX_TICKS);
		InteractionHand hand = "off".equals(Args.getEnum(args, "hand", "main", "main", "off"))
				? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

		JsonObject o = new JsonObject();
		if (ticks == 0) {
			bridge.callOnGameThread(() -> {
				LocalPlayer p = GameBridge.requirePlayer();
				InteractionResult r = Minecraft.getInstance().gameMode.useItem(p, hand);
				if (r instanceof InteractionResult.Success) p.swing(hand);
				o.addProperty("success", r.consumesAction());
				o.addProperty("result", r.getClass().getSimpleName());
				return null;
			});
		} else {
			CompletableFuture<Boolean> done = bridge.callOnGameThread(() -> {
				GameBridge.requirePlayer();
				return control.holdUse(Minecraft.getInstance(), ticks);
			});
			o.addProperty("completed", GameBridge.await(done, ticks * 50L + 5000));
		}
		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			o.addProperty("food", p.getFoodData().getFoodLevel());
			o.addProperty("health", p.getHealth());
			JsonObject held = Json.item(p.getMainHandItem());
			if (held != null) o.add("mainHand", held);
			return o;
		});
	}
}
