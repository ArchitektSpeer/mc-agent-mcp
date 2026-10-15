package com.mcagent.mcp.tools;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Server-owner policy applied to every tool: which tools are exposed at all (by category
 * and by name), and how fast they may be called (a global bucket plus one per category).
 * {@link ToolCategory#CONTROL} tools are exempt from both, so an agent can always stop.
 */
public final class ToolPolicy {
	/** Result of {@link #check}: either allowed, or a user-facing reason (+ retry hint for limits). */
	public record Decision(boolean allowed, String reason, long retryAfterMs) {
		static final Decision OK = new Decision(true, null, 0);
	}

	private final Map<ToolCategory, Boolean> enabled = new EnumMap<>(ToolCategory.class);
	private final Set<String> disabledTools = new HashSet<>();
	private final Map<ToolCategory, RateLimiter> limits = new EnumMap<>(ToolCategory.class);
	private RateLimiter global;
	private final LongSupplier clock;

	public ToolPolicy(LongSupplier nanoClock) {
		this.clock = nanoClock;
	}

	public ToolPolicy() {
		this(System::nanoTime);
	}

	/** Everything allowed, nothing limited. */
	public static ToolPolicy allowAll() {
		return new ToolPolicy();
	}

	public ToolPolicy setEnabled(ToolCategory category, boolean on) {
		enabled.put(category, on);
		return this;
	}

	public ToolPolicy disableTool(String name) {
		disabledTools.add(name);
		return this;
	}

	/** {@code perSecond <= 0} removes the limit. */
	public ToolPolicy limit(ToolCategory category, double perSecond, double burst) {
		if (perSecond <= 0) limits.remove(category);
		else limits.put(category, new RateLimiter(perSecond, Math.max(1, burst), clock));
		return this;
	}

	/** {@code perSecond <= 0} removes the global limit. */
	public ToolPolicy limitGlobal(double perSecond, double burst) {
		global = perSecond <= 0 ? null : new RateLimiter(perSecond, Math.max(1, burst), clock);
		return this;
	}

	/** Whether the tool is exposed in tools/list and callable at all. */
	public boolean isExposed(McpTool tool) {
		if (tool.category() == ToolCategory.CONTROL) return true;
		return enabled.getOrDefault(tool.category(), true) && !disabledTools.contains(tool.name());
	}

	/** Permission + rate check for one call; consumes rate tokens only when the call is allowed. */
	public Decision check(McpTool tool) {
		ToolCategory cat = tool.category();
		if (cat == ToolCategory.CONTROL) return Decision.OK;
		if (!isExposed(tool)) {
			String why = disabledTools.contains(tool.name())
					? "it is listed in disabledTools"
					: "permissions." + cat.key() + " is false";
			return new Decision(false, "tool '" + tool.name() + "' is disabled by the server owner ("
					+ why + " in config/mcagent.json)", 0);
		}
		if (global != null) {
			long wait = global.tryAcquire();
			if (wait > 0) return limited("all tools", wait);
		}
		RateLimiter perCat = limits.get(cat);
		if (perCat != null) {
			long wait = perCat.tryAcquire();
			if (wait > 0) {
				if (global != null) global.refund();
				return limited(cat.key() + " tools", wait);
			}
		}
		return Decision.OK;
	}

	private static Decision limited(String scope, long waitMs) {
		return new Decision(false, "rate limit for " + scope + " exceeded; retry in " + waitMs + " ms", waitMs);
	}
}
