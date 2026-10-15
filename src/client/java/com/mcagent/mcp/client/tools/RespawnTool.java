package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** respawn: press "Respawn" after death. */
public final class RespawnTool implements McpTool {
	private final GameBridge bridge;

	public RespawnTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "respawn";
	}

	@Override
	public String description() {
		return "Respawn after dying (observe reports dead=true). Inventory is usually dropped where you died.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object().build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		return bridge.callOnGameThread(() -> {
			LocalPlayer p = GameBridge.requirePlayer();
			if (!p.isDeadOrDying()) throw new ToolException("player is alive");
			p.respawn();
			Minecraft.getInstance().setScreen(null);
			JsonObject o = new JsonObject();
			o.addProperty("respawnRequested", true);
			return o;
		});
	}
}
