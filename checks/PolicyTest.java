import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagent.mcp.config.McAgentConfig;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.RateLimiter;
import com.mcagent.mcp.tools.ToolCategory;
import com.mcagent.mcp.tools.ToolPolicy;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Permissions and rate limits (M7). A fake nanosecond clock drives the token buckets,
 * so refill timing is exact and the test never sleeps.
 */
public class PolicyTest {
	static int failures = 0;
	static final AtomicLong now = new AtomicLong(1_000_000_000L);

	public static void main(String[] args) {
		// --- token bucket
		RateLimiter rl = new RateLimiter(2, 3, now::get); // 2/s, burst 3
		check("burst allows 3 at once", rl.tryAcquire() == 0 && rl.tryAcquire() == 0 && rl.tryAcquire() == 0);
		long wait = rl.tryAcquire();
		check("4th is limited with retry ~500 ms (" + wait + ")", wait == 500);
		advance(250);
		check("half a token after 250 ms -> retry 250 ms", rl.tryAcquire() == 250);
		advance(250);
		check("one token after 500 ms", rl.tryAcquire() == 0);
		advance(60_000);
		int n = 0;
		while (rl.tryAcquire() == 0) n++;
		check("refill caps at burst (" + n + ")", n == 3);
		rl.refund();
		check("refund returns a token", rl.tryAcquire() == 0);
		try {
			new RateLimiter(0, 1, now::get);
			check("perSecond 0 rejected", false);
		} catch (IllegalArgumentException e) {
			check("perSecond 0 rejected", true);
		}

		// --- policy
		McpTool look = tool("look", ToolCategory.MOVEMENT);
		McpTool attack = tool("attack_entity", ToolCategory.INTERACTION);
		McpTool chat = tool("chat", ToolCategory.CHAT);
		McpTool stop = tool("stop", ToolCategory.CONTROL);
		McpTool observe = tool("observe", ToolCategory.PERCEPTION);

		ToolPolicy p = new ToolPolicy(now::get).setEnabled(ToolCategory.CHAT, false).disableTool("attack_entity")
				.setEnabled(ToolCategory.CONTROL, false); // must have no effect
		check("disabled category hidden", !p.isExposed(chat));
		check("disabled tool hidden", !p.isExposed(attack));
		check("other tools exposed", p.isExposed(look) && p.isExposed(observe));
		check("control can never be disabled", p.isExposed(stop) && p.check(stop).allowed());
		ToolPolicy.Decision d = p.check(chat);
		check("denied call explains the config key", !d.allowed() && d.reason().contains("permissions.chat")
				&& d.retryAfterMs() == 0);
		check("denied tool names disabledTools", p.check(attack).reason().contains("disabledTools"));

		ToolPolicy lim = new ToolPolicy(now::get).limitGlobal(100, 2).limit(ToolCategory.MOVEMENT, 1, 1);
		check("first move allowed", lim.check(look).allowed());
		ToolPolicy.Decision d2 = lim.check(look);
		check("second move limited by category", !d2.allowed() && d2.reason().contains("movement")
				&& d2.retryAfterMs() == 1000);
		check("category rejection refunds the global token", lim.check(observe).allowed());
		check("global bucket now empty", !lim.check(observe).allowed());
		check("control exempt from limits", lim.check(stop).allowed() && lim.check(stop).allowed());
		ToolPolicy unl = new ToolPolicy(now::get).limit(ToolCategory.MOVEMENT, 0, 0);
		boolean all = true;
		for (int i = 0; i < 1000; i++) all &= unl.check(look).allowed();
		check("perSecond 0 = unlimited", all);

		// --- config
		McAgentConfig def = McAgentConfig.fromJson(new JsonObject());
		check("defaults: commands off", !def.allowCommands);
		check("defaults: every category on", def.permissions.values().stream().allMatch(b -> b));
		check("defaults: chat is rate limited", def.limits.get(ToolCategory.CHAT).perSecond() > 0);
		check("defaults: perception only under the global limit",
				def.limits.get(ToolCategory.PERCEPTION).perSecond() == 0 && def.globalLimit.perSecond() > 0);

		JsonObject cfgJson = JsonParser.parseString("{\"permissions\":{\"chat\":false,\"bogus\":true,"
				+ "\"control\":false},\"disabledTools\":[\"attack_entity\"],\"rateLimits\":{\"global\":"
				+ "{\"perSecond\":5,\"burst\":5},\"movement\":{\"perSecond\":1},\"nope\":{\"perSecond\":1}}}")
				.getAsJsonObject();
		McAgentConfig cfg = McAgentConfig.fromJson(cfgJson);
		check("permission parsed", !cfg.permissions.get(ToolCategory.CHAT));
		check("unknown/control permission ignored", !cfg.permissions.containsKey(ToolCategory.CONTROL));
		check("disabledTools parsed", cfg.disabledTools.contains("attack_entity"));
		check("global limit parsed", cfg.globalLimit.perSecond() == 5 && cfg.globalLimit.burst() == 5);
		check("burst defaults to perSecond (min 1)", cfg.limits.get(ToolCategory.MOVEMENT).burst() == 1);
		ToolPolicy fromCfg = cfg.toPolicy();
		check("config -> policy: chat hidden", !fromCfg.isExposed(chat));
		check("config -> policy: attack hidden", !fromCfg.isExposed(attack));

		McAgentConfig round = McAgentConfig.fromJson(cfg.toJson());
		check("toJson/fromJson round-trip", round.toJson().equals(cfg.toJson()));
		check("reflexes default on", def.reflexes.autoDefend && def.reflexes.autoEat && def.reflexes.interruptOnDamage);
		McAgentConfig rcfg = McAgentConfig.fromJson(JsonParser.parseString(
				"{\"reflexes\":{\"autoDefend\":false,\"eatBelowFood\":99}}").getAsJsonObject());
		check("reflex switch parsed", !rcfg.reflexes.autoDefend && rcfg.reflexes.autoEat);
		check("eatBelowFood clamped to 20", rcfg.reflexes.eatBelowFood == 20);
		check("allows() follows category + disabledTools", !p.allows("attack_entity", ToolCategory.INTERACTION)
				&& p.allows("break_block", ToolCategory.INTERACTION) && !p.allows("anything", ToolCategory.CHAT)
				&& p.allows("stop", ToolCategory.CONTROL));
		check("written config lists every option", cfg.toJson().has("rateLimits")
				&& cfg.toJson().getAsJsonObject("permissions").size() == ToolCategory.values().length - 1);

		System.out.println(failures == 0 ? "POLICY: PASS" : "POLICY: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	static void advance(long ms) {
		now.addAndGet(ms * 1_000_000L);
	}

	static McpTool tool(String name, ToolCategory cat) {
		return new McpTool() {
			@Override public String name() { return name; }
			@Override public ToolCategory category() { return cat; }
			@Override public String description() { return name; }
			@Override public JsonObject inputSchema() { return new JsonObject(); }
			@Override public JsonObject call(JsonObject args) { return new JsonObject(); }
		};
	}

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}
}
