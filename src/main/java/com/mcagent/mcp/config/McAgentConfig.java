package com.mcagent.mcp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagent.mcp.tools.ToolCategory;
import com.mcagent.mcp.tools.ToolPolicy;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * JSON config: {@code <game>/config/mcagent.json}.
 * The auth token is generated once on first run and persisted, so the user can
 * copy it into their MCP client configuration. Missing keys get defaults, and the
 * file is rewritten with every key so new options are discoverable.
 */
public final class McAgentConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// Not LOGGER: loading McAgentMod needs Fabric on the classpath, which the offline checks avoid.
	private static final Logger LOGGER = LoggerFactory.getLogger("mc-agent-mcp");

	/** Rate limit for one bucket; {@code perSecond <= 0} means unlimited. */
	public record Limit(double perSecond, double burst) {
	}

	public boolean enabled = true;
	public int port = 27890;
	public String bindAddress = "127.0.0.1";
	public String authToken = "";
	/** Let the agent run /commands through the chat tool (off: an agent could e.g. /op or /kill). */
	public boolean allowCommands = false;
	/** Keep the game running while the window is unfocused, so the agent can play in the background. */
	public boolean disablePauseOnLostFocus = true;
	/** Per-category switch; a disabled category's tools disappear from tools/list. */
	public final Map<ToolCategory, Boolean> permissions = new EnumMap<>(ToolCategory.class);
	/** Individual tools to hide, by name (e.g. "attack_entity"). */
	public final List<String> disabledTools = new ArrayList<>();
	/** Applies to every non-control tool call together. */
	public Limit globalLimit = new Limit(20, 40);
	public final Map<ToolCategory, Limit> limits = new EnumMap<>(ToolCategory.class);
	/** Survival reflexes that act between the agent's turns. */
	public final ReflexSettings reflexes = new ReflexSettings();

	public McAgentConfig() {
		for (ToolCategory c : ToolCategory.values()) {
			if (c != ToolCategory.CONTROL) permissions.put(c, true);
		}
		limits.put(ToolCategory.PERCEPTION, new Limit(0, 0));
		limits.put(ToolCategory.MOVEMENT, new Limit(10, 20));
		limits.put(ToolCategory.INTERACTION, new Limit(10, 20));
		limits.put(ToolCategory.INVENTORY, new Limit(20, 40));
		// Servers kick chat spammers; one message every two seconds with a short burst is safe.
		limits.put(ToolCategory.CHAT, new Limit(0.5, 3));
	}

	public static McAgentConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mcagent.json");
		McAgentConfig cfg = new McAgentConfig();
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				cfg = fromJson(JsonParser.parseReader(r).getAsJsonObject());
			} catch (Exception e) {
				LOGGER.warn("Failed to read config {}, using defaults", file, e);
			}
		}
		if (cfg.authToken == null || cfg.authToken.isBlank()) {
			byte[] b = new byte[24];
			new SecureRandom().nextBytes(b);
			cfg.authToken = HexFormat.of().formatHex(b);
		}
		cfg.save(file);
		return cfg;
	}

	/** Parse a config object; absent or malformed keys keep their defaults. */
	public static McAgentConfig fromJson(JsonObject o) {
		McAgentConfig cfg = new McAgentConfig();
		if (o.has("enabled")) cfg.enabled = o.get("enabled").getAsBoolean();
		if (o.has("port")) cfg.port = o.get("port").getAsInt();
		if (o.has("bindAddress")) cfg.bindAddress = o.get("bindAddress").getAsString();
		if (o.has("authToken")) cfg.authToken = o.get("authToken").getAsString();
		if (o.has("allowCommands")) cfg.allowCommands = o.get("allowCommands").getAsBoolean();
		if (o.has("disablePauseOnLostFocus")) {
			cfg.disablePauseOnLostFocus = o.get("disablePauseOnLostFocus").getAsBoolean();
		}
		if (o.has("permissions") && o.get("permissions").isJsonObject()) {
			for (var e : o.getAsJsonObject("permissions").entrySet()) {
				ToolCategory c = ToolCategory.byKey(e.getKey());
				if (c == null || c == ToolCategory.CONTROL) {
					LOGGER.warn("[mcagent] unknown permission '{}' in config ignored", e.getKey());
				} else {
					cfg.permissions.put(c, e.getValue().getAsBoolean());
				}
			}
		}
		if (o.has("disabledTools") && o.get("disabledTools").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("disabledTools")) cfg.disabledTools.add(e.getAsString());
		}
		if (o.has("reflexes") && o.get("reflexes").isJsonObject()) {
			cfg.reflexes.apply(o.getAsJsonObject("reflexes"));
		}
		if (o.has("rateLimits") && o.get("rateLimits").isJsonObject()) {
			for (var e : o.getAsJsonObject("rateLimits").entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				Limit l = parseLimit(e.getValue().getAsJsonObject());
				if ("global".equalsIgnoreCase(e.getKey())) {
					cfg.globalLimit = l;
					continue;
				}
				ToolCategory c = ToolCategory.byKey(e.getKey());
				if (c == null || c == ToolCategory.CONTROL) {
					LOGGER.warn("[mcagent] unknown rate limit '{}' in config ignored", e.getKey());
				} else {
					cfg.limits.put(c, l);
				}
			}
		}
		return cfg;
	}

	private static Limit parseLimit(JsonObject o) {
		double perSecond = o.has("perSecond") ? o.get("perSecond").getAsDouble() : 0;
		double burst = o.has("burst") ? o.get("burst").getAsDouble() : Math.max(1, perSecond);
		return new Limit(perSecond, burst);
	}

	/** The permissions and rate limits as enforced by the MCP protocol layer. */
	public ToolPolicy toPolicy() {
		ToolPolicy p = new ToolPolicy();
		permissions.forEach(p::setEnabled);
		disabledTools.forEach(p::disableTool);
		p.limitGlobal(globalLimit.perSecond(), globalLimit.burst());
		limits.forEach((c, l) -> p.limit(c, l.perSecond(), l.burst()));
		return p;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("enabled", enabled);
		o.addProperty("port", port);
		o.addProperty("bindAddress", bindAddress);
		o.addProperty("authToken", authToken);
		o.addProperty("allowCommands", allowCommands);
		o.addProperty("disablePauseOnLostFocus", disablePauseOnLostFocus);
		JsonObject perms = new JsonObject();
		permissions.forEach((c, on) -> perms.addProperty(c.key(), on));
		o.add("permissions", perms);
		JsonArray disabled = new JsonArray();
		disabledTools.forEach(disabled::add);
		o.add("disabledTools", disabled);
		JsonObject rl = new JsonObject();
		rl.add("global", limitJson(globalLimit));
		limits.forEach((c, l) -> rl.add(c.key(), limitJson(l)));
		o.add("rateLimits", rl);
		o.add("reflexes", reflexes.toJson());
		return o;
	}

	private static JsonObject limitJson(Limit l) {
		JsonObject o = new JsonObject();
		o.addProperty("perSecond", l.perSecond());
		o.addProperty("burst", l.burst());
		return o;
	}

	private void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(toJson(), w);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to write config {}", file, e);
		}
	}
}
