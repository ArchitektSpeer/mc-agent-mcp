package com.mcagent.mcp.tools;

import java.util.Locale;

/**
 * What a tool can do to the game, the unit of permissions and rate limits.
 * The lower-case name is the key used in {@code config/mcagent.json}.
 */
public enum ToolCategory {
	/** Read-only: observe, blocks, entities, inventory, chat log, recipes. */
	PERCEPTION,
	/** Moves or turns the player. */
	MOVEMENT,
	/** Changes the world or entities: mine, place, attack, use, right-click, respawn. */
	INTERACTION,
	/** Moves items: hotbar selection, container clicks, transfers, crafting. */
	INVENTORY,
	/** Speaks for the player in chat (visible to other people). */
	CHAT,
	/** Safety and liveness ({@code stop}, {@code ping}): never disabled, never rate limited. */
	CONTROL;

	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Parse a config key; null when unknown. */
	public static ToolCategory byKey(String key) {
		for (ToolCategory c : values()) {
			if (c.key().equalsIgnoreCase(key)) return c;
		}
		return null;
	}
}
