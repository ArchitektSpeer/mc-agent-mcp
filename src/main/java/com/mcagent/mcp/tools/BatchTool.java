package com.mcagent.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.function.BiFunction;

/**
 * batch: run several tool calls in one request, in order. Cuts round trips for agents whose
 * every turn costs seconds (e.g. select a sword, walk to the zombie, attack, observe).
 * Each step goes through the normal tools/call path, so permissions and rate limits apply
 * per step.
 */
public final class BatchTool implements McpTool {
	public static final int MAX_STEPS = 8;

	/** (tool name, arguments) -> MCP tools/call result ({content, structuredContent, isError?}). */
	private final BiFunction<String, JsonObject, JsonObject> invoker;

	public BatchTool(BiFunction<String, JsonObject, JsonObject> invoker) {
		this.invoker = invoker;
	}

	@Override
	public String name() {
		return "batch";
	}

	@Override
	public ToolCategory category() {
		// Only a dispatcher: every step is checked against the policy on its own.
		return ToolCategory.CONTROL;
	}

	@Override
	public String description() {
		return "Run up to " + MAX_STEPS + " tool calls in order in one request, e.g. [{tool:'select_slot',args:"
				+ "{slot:0}},{tool:'go_to',args:{entityId:12,stopDistance:2.5}},{tool:'attack_entity',args:{entityId:12}}]. "
				+ "Stops at the first failing step unless continueOnError=true. Returns each step's result.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject step = new JsonObject();
		step.addProperty("type", "object");
		JsonObject sp = new JsonObject();
		JsonObject toolProp = new JsonObject();
		toolProp.addProperty("type", "string");
		toolProp.addProperty("description", "Tool name.");
		sp.add("tool", toolProp);
		JsonObject argsProp = new JsonObject();
		argsProp.addProperty("type", "object");
		argsProp.addProperty("description", "Arguments for that tool.");
		sp.add("args", argsProp);
		step.add("properties", sp);
		JsonArray req = new JsonArray();
		req.add("tool");
		step.add("required", req);

		JsonObject steps = new JsonObject();
		steps.addProperty("type", "array");
		steps.add("items", step);
		steps.addProperty("maxItems", MAX_STEPS);
		steps.addProperty("description", "Calls to run in order.");

		JsonObject schema = Schema.object()
				.bool("continueOnError", "Keep going after a failed step (default false).")
				.required("steps")
				.build();
		schema.getAsJsonObject("properties").add("steps", steps);
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		if (!Args.has(args, "steps") || !args.get("steps").isJsonArray()) {
			throw new ToolException("'steps' must be an array of {tool, args}");
		}
		JsonArray steps = args.getAsJsonArray("steps");
		if (steps.isEmpty() || steps.size() > MAX_STEPS) {
			throw new ToolException("'steps' must have 1-" + MAX_STEPS + " entries");
		}
		boolean continueOnError = Args.getBool(args, "continueOnError", false);

		JsonArray results = new JsonArray();
		int failed = 0;
		for (int i = 0; i < steps.size(); i++) {
			JsonElement el = steps.get(i);
			JsonObject r = new JsonObject();
			if (!el.isJsonObject() || !el.getAsJsonObject().has("tool")) {
				r.addProperty("ok", false);
				r.addProperty("error", "step " + i + " needs {tool, args}");
			} else {
				JsonObject st = el.getAsJsonObject();
				String tool = st.get("tool").getAsString();
				r.addProperty("tool", tool);
				if (name().equals(tool)) {
					r.addProperty("ok", false);
					r.addProperty("error", "batch cannot be nested");
				} else {
					JsonObject a = st.has("args") && st.get("args").isJsonObject()
							? st.getAsJsonObject("args") : new JsonObject();
					JsonObject res = invoker.apply(tool, a);
					boolean ok = !(res.has("isError") && res.get("isError").getAsBoolean());
					r.addProperty("ok", ok);
					r.add("result", res.get("structuredContent"));
				}
			}
			results.add(r);
			if (!r.get("ok").getAsBoolean()) {
				failed++;
				if (!continueOnError) break;
			}
		}
		JsonObject o = new JsonObject();
		o.add("results", results);
		o.addProperty("completedSteps", results.size());
		o.addProperty("failedSteps", failed);
		return o;
	}
}
