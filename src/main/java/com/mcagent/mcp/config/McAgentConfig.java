package com.mcagent.mcp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagent.mcp.McAgentMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Minimal JSON config: {@code <game>/config/mcagent.json}.
 * The auth token is generated once on first run and persisted, so the user can
 * copy it into their MCP client configuration.
 */
public final class McAgentConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean enabled = true;
	public int port = 27890;
	public String bindAddress = "127.0.0.1";
	public String authToken = "";

	private McAgentConfig() {
	}

	public static McAgentConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mcagent.json");
		McAgentConfig cfg = new McAgentConfig();
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
				if (o.has("enabled")) cfg.enabled = o.get("enabled").getAsBoolean();
				if (o.has("port")) cfg.port = o.get("port").getAsInt();
				if (o.has("bindAddress")) cfg.bindAddress = o.get("bindAddress").getAsString();
				if (o.has("authToken")) cfg.authToken = o.get("authToken").getAsString();
			} catch (Exception e) {
				McAgentMod.LOGGER.warn("Failed to read config {}, using defaults", file, e);
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

	private void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("enabled", enabled);
			o.addProperty("port", port);
			o.addProperty("bindAddress", bindAddress);
			o.addProperty("authToken", authToken);
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(o, w);
			}
		} catch (IOException e) {
			McAgentMod.LOGGER.warn("Failed to write config {}", file, e);
		}
	}
}
