package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.config.ReflexSettings;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;

/** set_reflexes: read or change the built-in survival reflexes at runtime. */
public final class SetReflexesTool implements McpTool {
	private final ReflexSettings settings;

	public SetReflexesTool(ReflexSettings settings) {
		this.settings = settings;
	}

	@Override
	public String name() {
		return "set_reflexes";
	}

	@Override
	public ToolCategory category() {
		// Reflexes still obey the owner's permissions for the tools they imitate.
		return ToolCategory.CONTROL;
	}

	@Override
	public String description() {
		return "Show or change the built-in reflexes that act every tick between your turns: autoDefend (hit "
				+ "hostiles in reach with the best hotbar weapon), evadeCreepers, chargeRanged (rush skeletons in sight "
				+ "while health >= 8), autoEat (safe hotbar food), autoArmor, "
				+ "escapeFluids (swim up / leave lava), interruptOnDamage (your move/go_to/break stops when hurt), "
				+ "eatBelowFood. All on by default; turn autoDefend off to fight manually. Call with no "
				+ "arguments to read the current settings.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.bool("autoDefend", "Melee hostile mobs in reach.")
				.bool("evadeCreepers", "Hit and back away from close creepers.")
				.bool("chargeRanged", "Rush visible skeletons/pillagers/witches.")
				.bool("autoEat", "Eat when hungry and safe.")
				.bool("autoArmor", "Equip armor into empty slots.")
				.bool("escapeFluids", "Swim up when out of air, jump out of lava.")
				.bool("interruptOnDamage", "Stop running actions when hurt.")
				.integer("eatBelowFood", "autoEat threshold, 1-20 (default 14).")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		// Validate types first so a bad value changes nothing.
		for (String k : new String[]{"autoDefend", "evadeCreepers", "chargeRanged", "autoEat", "autoArmor", "escapeFluids",
				"interruptOnDamage"}) {
			Args.getBool(args, k, false);
		}
		Args.getInt(args, "eatBelowFood", 14, 1, 20);
		settings.apply(args);
		return settings.toJson();
	}
}
