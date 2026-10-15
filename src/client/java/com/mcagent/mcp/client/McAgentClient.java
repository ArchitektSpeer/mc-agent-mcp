package com.mcagent.mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.McAgentMod;
import com.mcagent.mcp.client.blocks.BlockDiffTracker;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.client.tools.AttackTool;
import com.mcagent.mcp.client.tools.BreakBlockTool;
import com.mcagent.mcp.client.tools.ChatTool;
import com.mcagent.mcp.client.tools.ClickSlotTool;
import com.mcagent.mcp.client.tools.CraftTool;
import com.mcagent.mcp.client.tools.GetContainerTool;
import com.mcagent.mcp.client.tools.GetRecipesTool;
import com.mcagent.mcp.client.tools.TransferItemsTool;
import com.mcagent.mcp.client.tools.GetBlockChangesTool;
import com.mcagent.mcp.client.tools.GetBlocksTool;
import com.mcagent.mcp.client.tools.GetChatTool;
import com.mcagent.mcp.client.tools.GetEntitiesTool;
import com.mcagent.mcp.client.tools.GetInventoryTool;
import com.mcagent.mcp.client.tools.LookTool;
import com.mcagent.mcp.client.tools.MoveTool;
import com.mcagent.mcp.client.tools.ObserveTool;
import com.mcagent.mcp.client.tools.PlaceBlockTool;
import com.mcagent.mcp.client.tools.RespawnTool;
import com.mcagent.mcp.client.tools.ScanBlocksTool;
import com.mcagent.mcp.client.tools.SelectSlotTool;
import com.mcagent.mcp.client.tools.StopTool;
import com.mcagent.mcp.client.tools.SubscribeBlocksTool;
import com.mcagent.mcp.client.tools.UseItemTool;
import com.mcagent.mcp.config.McAgentConfig;
import com.mcagent.mcp.server.McpHttpServer;
import com.mcagent.mcp.server.McpProtocol;
import com.mcagent.mcp.server.NotificationBus;
import com.mcagent.mcp.tools.PingTool;
import com.mcagent.mcp.tools.ToolRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * Client entrypoint: wires config, tool registry, protocol and HTTP transport,
 * and manages the MCP server lifecycle with the game.
 *
 * M3 adds: block engine (scanner/codec), diff tracker with Mixin hook,
 * SSE notification pushes, and 4 block tools.
 * M4/M5 add: entity/inventory/chat perception and player actuation ({@link AgentControl}).
 * M6 adds: container menus and recipe-book crafting.
 */
public class McAgentClient implements ClientModInitializer {
	/** Playbook sent in the initialize result, so any MCP client knows how to drive the game. */
	static final String INSTRUCTIONS = """
			You control a Minecraft player through these tools. Game time: 20 ticks = 1 second.
			Loop: observe -> decide -> act -> observe. observe reports position, health, food, held item,
			crosshair target, open screen and dead=true (then call respawn).
			Perception: get_blocks format='layers' gives readable ASCII maps; scan_blocks finds ores/blocks;
			get_entities lists mobs (hostile=true), players and dropped items with ids; get_inventory lists slots;
			get_chat reads messages (pass afterSeq=lastSeq).
			Movement: look (yaw/pitch, block or entity) then move {forward:true, ticks:N}; add jump:true to climb
			one-block steps, sneak:true near edges. Yaw: 0=south(+z), 90=west(-x), 180=north(-z), -90=east(+x).
			There is no pathfinder: walk in short segments and re-observe; get_blocks around you to plan a route.
			Interaction reach is ~4.5 blocks for blocks and ~3 for entities; move closer when a tool says out of reach.
			Mining: select_slot the right tool, then break_block {pos}; walk over drops to pick them up.
			Building: select_slot a block, place_block {pos} into an empty cell next to a solid block.
			Combat: attack_entity {entityId} when attackStrength is near 1.0. Eat with use_item {ticks:40}.
			Crafting: get_recipes {item} shows ingredients and whether a crafting table is needed; craft {item,count}
			works in the 2x2 inventory grid, or in a crafting table after interact_block on it.
			Chests/furnaces: interact_block opens them, get_container lists slots, transfer_items moves stacks
			(to='container' into a furnace fills fuel/input automatically), click_slot does single clicks.
			Close GUIs with stop {closeScreen:true}.
			stop cancels any running action.
			The server owner may disable tools or rate-limit them; on a rate-limit error wait retryAfterMs.""";

	private static McpHttpServer server;

	@Override
	public void onInitializeClient() {
		McAgentConfig config = McAgentConfig.load();
		if (!config.enabled) {
			McAgentMod.LOGGER.info("[mcagent] disabled via config/mcagent.json");
			return;
		}

		String version = FabricLoader.getInstance()
				.getModContainer(McAgentMod.MOD_ID)
				.map(ModContainer::getMetadata)
				.map(m -> m.getVersion().getFriendlyString())
				.orElse("dev");

		GameBridge bridge = new GameBridge();
		BlockScanner scanner = new BlockScanner();
		BlockDiffTracker tracker = new BlockDiffTracker();
		BlockDiffTracker.instance = tracker; // static handle for the Mixin hook
		NotificationBus bus = new NotificationBus();
		AgentControl control = new AgentControl();
		AgentControl.instance = control; // static handle for the input/attack Mixins
		ChatLog chatLog = new ChatLog();

		ToolRegistry registry = new ToolRegistry();
		registry.register(new PingTool());
		registry.register(new ObserveTool(bridge));
		registry.register(new GetBlocksTool(bridge, scanner));
		registry.register(new ScanBlocksTool(bridge, scanner));
		registry.register(new SubscribeBlocksTool(bridge, tracker));
		registry.register(new GetBlockChangesTool(bridge, tracker));
		// M4 perception
		registry.register(new GetEntitiesTool(bridge));
		registry.register(new GetInventoryTool(bridge));
		registry.register(new GetChatTool(chatLog));
		// M5 actuation
		registry.register(new MoveTool(bridge, control));
		registry.register(new LookTool(bridge));
		registry.register(new BreakBlockTool(bridge, control));
		registry.register(PlaceBlockTool.place(bridge));
		registry.register(PlaceBlockTool.interact(bridge));
		registry.register(AttackTool.attack(bridge));
		registry.register(AttackTool.interact(bridge));
		registry.register(new UseItemTool(bridge, control));
		registry.register(new SelectSlotTool(bridge));
		registry.register(new ChatTool(bridge, config.allowCommands));
		registry.register(new RespawnTool(bridge));
		registry.register(new StopTool(bridge, control));
		// M6 containers + crafting
		registry.register(new GetContainerTool(bridge));
		registry.register(new ClickSlotTool(bridge));
		registry.register(new TransferItemsTool(bridge));
		registry.register(new GetRecipesTool(bridge));
		registry.register(new CraftTool(bridge, control));

		McpProtocol protocol = new McpProtocol(registry, "mc-agent-mcp", version, INSTRUCTIONS, config.toPolicy());
		server = new McpHttpServer(protocol, bus, config.bindAddress, config.port, config.authToken);
		try {
			server.start();
			McAgentMod.LOGGER.info("[mcagent] connect your MCP client to {} with Bearer token: {}",
					server.endpoint(), config.authToken);
		} catch (Exception e) {
			McAgentMod.LOGGER.error("[mcagent] failed to start MCP server", e);
			server = null;
		}

		// Track world changes each client tick; push diffs to SSE subscribers.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			control.tick(mc);
			tracker.tick();
			if (tracker.isTracking() && bus.subscriberCount() > 0) {
				BlockDiffTracker.DiffResult diff = tracker.takeDiff();
				if (diff != null) {
					JsonObject notif = new JsonObject();
					notif.addProperty("jsonrpc", "2.0");
					notif.addProperty("method", "notifications/mcagent/block_diff");
					notif.add("params", diffParams(diff));
					bus.broadcast(notif);
				}
			}
		});
		// Chunk (un)load invalidates cached snapshots: re-baseline without emitting a diff,
		// so loading terrain never shows up as thousands of "appeared" blocks.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
				tracker.onChunkLoaded(chunk.getPos().x, chunk.getPos().z));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
				tracker.onChunkUnloaded(chunk.getPos().x, chunk.getPos().z));

		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, receivedAt) ->
				chatLog.add("chat", sender == null ? null : sender.name(), message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) chatLog.add("game", null, message.getString()); // skip action-bar spam
		});

		if (config.disablePauseOnLostFocus) {
			// An agent drives the game while the user works in another window; vanilla would
			// pause on focus loss and open the pause menu, freezing every timed action.
			ClientLifecycleEvents.CLIENT_STARTED.register(mc -> mc.options.pauseOnLostFocus = false);
		}

		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			if (server != null) {
				server.stop();
				server = null;
			}
		});
	}

	private static JsonObject diffParams(BlockDiffTracker.DiffResult diff) {
		JsonObject params = new JsonObject();
		params.addProperty("ms", diff.snapNanos / 1_000_000.0);
		params.addProperty("full", diff.full);
		params.addProperty("resync", diff.resync);
		JsonArray changes = new JsonArray();
		if (diff.changes != null) {
			for (long[] c : diff.changes) {
				JsonObject ch = new JsonObject();
				JsonArray pos = new JsonArray();
				pos.add((int) c[0]);
				pos.add((int) c[1]);
				pos.add((int) c[2]);
				ch.add("pos", pos);
				// c[4] is the block id: the new one when it appeared, the old one when removed.
				String name = c[4] == BlockDiffTracker.UNKNOWN_BLOCK_ID ? "-" : nameOfId((int) c[4]);
				if (c[3] == 0) {
					ch.addProperty("from", name);
					ch.addProperty("to", "-");
				} else {
					ch.addProperty("from", "-");
					ch.addProperty("to", name);
				}
				changes.add(ch);
			}
		}
		params.add("changes", changes);
		params.addProperty("changed", changes.size());
		return params;
	}

	private static String nameOfId(int globalId) {
		Block b = BuiltInRegistries.BLOCK.byId(globalId);
		if (b == null) return "unknown";
		var id = BuiltInRegistries.BLOCK.getKey(b);
		return id == null ? "unknown" : id.toString();
	}
}
