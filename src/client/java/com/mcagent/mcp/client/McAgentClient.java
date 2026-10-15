package com.mcagent.mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.McAgentMod;
import com.mcagent.mcp.client.blocks.BlockDiffTracker;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.client.reflex.Reflexes;
import com.mcagent.mcp.client.tools.AttackTool;
import com.mcagent.mcp.client.tools.BreakBlockTool;
import com.mcagent.mcp.client.tools.ChatTool;
import com.mcagent.mcp.client.tools.ClickSlotTool;
import com.mcagent.mcp.client.tools.CraftTool;
import com.mcagent.mcp.client.tools.GetContainerTool;
import com.mcagent.mcp.client.tools.GetRecipesTool;
import com.mcagent.mcp.client.tools.GoToTool;
import com.mcagent.mcp.client.tools.SetReflexesTool;
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
import com.mcagent.mcp.tools.BatchTool;
import com.mcagent.mcp.tools.PingTool;
import com.mcagent.mcp.tools.ToolPolicy;
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
			You control a Minecraft survival player. 20 ticks = 1 second. Your turns are slow, so the mod runs
			reflexes every tick for you: it hits hostile mobs in reach with your best hotbar sword/axe, hits and
			backs away from close creepers, eats safe hotbar food when hungry, equips armor, swims up for air.
			Keep a weapon and food in the hotbar (slots 0-8) so the reflexes can use them.

			Every turn: observe first. Read 'threats' (hostile mobs, nearest first) and 'events' (damage taken,
			what the reflexes did). If health < 8 or several threats are near, retreat or block up before anything
			else. If dead=true, call respawn.
			Do more per turn: batch runs up to 8 calls in order; go_to {pos} or {entityId} walks there (jumps
			steps, stops before lava/cliffs, stops when you are hurt). Actions return early with 'interrupted'
			when you take damage: re-observe and react.

			Moving: go_to for destinations; move {forward,ticks} for small steps. Yaw 0=south(+z), 90=west(-x),
			180=north(-z), -90=east(+x). go_to walks in a straight line: give waypoints around walls.
			Reach: ~4.5 blocks for blocks, ~3 for entities.
			Mining: select_slot a pickaxe/axe/shovel, break_block {pos}; walk over drops to collect them.
			Building: select_slot a block, place_block {pos} into an empty cell next to a solid block.
			Fighting yourself: go_to {entityId, stopDistance:2.5}, then attack_entity when attackStrength ~1.0.
			Crafting: get_recipes {item}, then craft {item,count} (2x2 in inventory, 3x3 after interact_block on a
			crafting table). Furnace/chest: interact_block, transfer_items, get_container, stop {closeScreen:true}.
			Night is dangerous: before dark, have a sword, food and a shelter (dig in and place a block above you),
			or sleep in a bed with interact_block.
			stop cancels everything. If a tool is rate-limited, wait retryAfterMs.""";


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
		ChatLog events = new ChatLog(); // damage, reflex actions, deaths; returned by observe
		ToolPolicy policy = config.toPolicy();
		Reflexes reflexes = new Reflexes(config.reflexes, control, policy, events);

		ToolRegistry registry = new ToolRegistry();
		registry.register(new PingTool());
		registry.register(new ObserveTool(bridge, events));
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
		registry.register(new GoToTool(bridge, control));
		registry.register(new LookTool(bridge));
		registry.register(new BreakBlockTool(bridge, control));
		registry.register(PlaceBlockTool.place(bridge, control));
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
		// survival reflexes + throughput
		registry.register(new SetReflexesTool(config.reflexes));

		McpProtocol protocol = new McpProtocol(registry, "mc-agent-mcp", version, INSTRUCTIONS, policy);
		registry.register(new BatchTool(protocol::invokeTool));
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
			reflexes.tick(mc);
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
