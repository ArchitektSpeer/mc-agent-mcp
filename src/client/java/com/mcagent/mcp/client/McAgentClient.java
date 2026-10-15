package com.mcagent.mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.McAgentMod;
import com.mcagent.mcp.client.blocks.BlockDiffTracker;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.client.tools.GetBlockChangesTool;
import com.mcagent.mcp.client.tools.GetBlocksTool;
import com.mcagent.mcp.client.tools.ObserveTool;
import com.mcagent.mcp.client.tools.ScanBlocksTool;
import com.mcagent.mcp.client.tools.SubscribeBlocksTool;
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
 */
public class McAgentClient implements ClientModInitializer {
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

		ToolRegistry registry = new ToolRegistry();
		registry.register(new PingTool());
		registry.register(new ObserveTool(bridge));
		registry.register(new GetBlocksTool(bridge, scanner));
		registry.register(new ScanBlocksTool(bridge, scanner));
		registry.register(new SubscribeBlocksTool(bridge, tracker));
		registry.register(new GetBlockChangesTool(bridge, tracker));

		McpProtocol protocol = new McpProtocol(registry, "mc-agent-mcp", version);
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
