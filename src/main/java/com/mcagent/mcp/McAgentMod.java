package com.mcagent.mcp;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. The MCP server itself is client-side for now
 * (see com.mcagent.mcp.client.McAgentClient); this class only holds
 * shared constants and the logger.
 */
public class McAgentMod implements ModInitializer {
	public static final String MOD_ID = "mcagent";
	public static final Logger LOGGER = LoggerFactory.getLogger("mc-agent-mcp");

	@Override
	public void onInitialize() {
		LOGGER.info("MC Agent MCP loaded (common).");
	}
}
