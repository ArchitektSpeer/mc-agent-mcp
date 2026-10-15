import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.blocks.BlockDiffTracker;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.client.ChatLog;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.client.tools.AttackTool;
import com.mcagent.mcp.client.tools.BreakBlockTool;
import com.mcagent.mcp.client.tools.ChatTool;
import com.mcagent.mcp.client.tools.GetBlockChangesTool;
import com.mcagent.mcp.client.tools.GetChatTool;
import com.mcagent.mcp.client.tools.GetEntitiesTool;
import com.mcagent.mcp.client.tools.GetInventoryTool;
import com.mcagent.mcp.client.tools.LookTool;
import com.mcagent.mcp.client.tools.MoveTool;
import com.mcagent.mcp.client.tools.PlaceBlockTool;
import com.mcagent.mcp.client.tools.RespawnTool;
import com.mcagent.mcp.client.tools.SelectSlotTool;
import com.mcagent.mcp.client.tools.StopTool;
import com.mcagent.mcp.client.tools.UseItemTool;
import com.mcagent.mcp.client.tools.GetBlocksTool;
import com.mcagent.mcp.client.tools.ObserveTool;
import com.mcagent.mcp.client.tools.ScanBlocksTool;
import com.mcagent.mcp.client.tools.SubscribeBlocksTool;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.PingTool;
import com.mcagent.mcp.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates the JSON Schema every tool advertises in tools/list.
 *
 * Schemas are what an AI agent actually sees, so they must be well-formed JSON Schema:
 * the classic failure is emitting {"enum": "['a','b']"} (a string) instead of an array,
 * which strict clients reject or silently ignore.
 *
 * The tools are constructed with a null GameBridge: only inputSchema() is exercised, and
 * no game state is touched.
 */
public class ToolSchemaTest {
	static int failures = 0;

	public static void main(String[] args) {
		BlockScanner scanner = new BlockScanner();
		BlockDiffTracker tracker = new BlockDiffTracker();

		List<McpTool> tools = new ArrayList<>();
		tools.add(new PingTool());
		tools.add(new ObserveTool(null));
		tools.add(new GetBlocksTool(null, scanner));
		tools.add(new ScanBlocksTool(null, scanner));
		tools.add(new SubscribeBlocksTool(null, tracker));
		tools.add(new GetBlockChangesTool(null, tracker));
		AgentControl control = new AgentControl();
		tools.add(new GetEntitiesTool(null));
		tools.add(new GetInventoryTool(null));
		tools.add(new GetChatTool(new ChatLog()));
		tools.add(new MoveTool(null, control));
		tools.add(new LookTool(null));
		tools.add(new BreakBlockTool(null, control));
		tools.add(PlaceBlockTool.place(null));
		tools.add(PlaceBlockTool.interact(null));
		tools.add(AttackTool.attack(null));
		tools.add(AttackTool.interact(null));
		tools.add(new UseItemTool(null, control));
		tools.add(new SelectSlotTool(null));
		tools.add(new ChatTool(null, false));
		tools.add(new RespawnTool(null));
		tools.add(new StopTool(null, control));

		// The registry is what tools/list is built from; make sure it accepts them all.
		ToolRegistry registry = new ToolRegistry();
		for (McpTool t : tools) {
			registry.register(t);
		}
		java.util.Set<String> names = new java.util.HashSet<>();
		for (McpTool t : tools) {
			check("unique tool name " + t.name(), names.add(t.name()));
		}
		check("registry exposes every tool",
				registry.listPayload().getAsJsonArray("tools").size() == tools.size());

		for (McpTool t : tools) {
			System.out.println("-- " + t.name());
			check("  name is set", t.name() != null && !t.name().isBlank());
			check("  description tells the agent what it does",
					t.description() != null && t.description().length() > 20);
			JsonObject schema = t.inputSchema();
			check("  schema is an object", schema != null
					&& "object".equals(schema.get("type").getAsString()));
			check("  schema has properties", schema.has("properties")
					&& schema.get("properties").isJsonObject());
			check("  schema is small enough to send on every turn",
					schema.toString().length() < 4096);
			validate(schema, t.name());
		}

		System.out.println(failures == 0 ? "TOOL-SCHEMA: PASS" : "TOOL-SCHEMA: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	/** Recursively validate the schema shape (types, enums, required, items). */
	static void validate(JsonObject schema, String tool) {
		if (schema.has("enum")) {
			check("  " + tool + ": enum is an array", schema.get("enum").isJsonArray());
		}
		if (schema.has("required")) {
			check("  " + tool + ": required is an array", schema.get("required").isJsonArray());
			JsonObject props = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
			for (JsonElement r : schema.getAsJsonArray("required")) {
				check("  " + tool + ": required '" + r.getAsString() + "' is declared in properties",
						props.has(r.getAsString()));
			}
		}
		if (schema.has("type")) {
			JsonElement type = schema.get("type");
			check("  " + tool + ": type is a string", type.isJsonPrimitive());
			String t = type.getAsString();
			if ("array".equals(t)) {
				check("  " + tool + ": array schema declares items", schema.has("items"));
			}
			if (schema.has("items")) {
				check("  " + tool + ": items is an object schema",
						schema.get("items").isJsonObject()
								&& schema.getAsJsonObject("items").has("type"));
			}
		}
		if (schema.has("properties")) {
			for (Map.Entry<String, JsonElement> e : schema.getAsJsonObject("properties").entrySet()) {
				check("  " + tool + ": property '" + e.getKey() + "' is an object",
						e.getValue().isJsonObject());
				JsonObject p = e.getValue().getAsJsonObject();
				check("  " + tool + ": property '" + e.getKey() + "' declares a type",
						p.has("type") || p.has("enum"));
				validate(p, tool);
			}
		}
	}

	static void check(String what, boolean ok) {
		if (!ok) {
			System.out.println("  FAIL " + what);
			failures++;
		}
	}
}
