package com.mcagent.mcp.tools;

import com.google.gson.JsonObject;

/** One MCP tool. Implementations must be thread-safe; game access goes through the GameBridge. */
public interface McpTool {
	String name();

	String description();

	/** What the tool can do; drives permissions and rate limits ({@link ToolPolicy}). */
	ToolCategory category();

	/** JSON Schema (draft 2020-12 style object) describing the arguments. */
	JsonObject inputSchema();

	/**
	 * Execute the tool.
	 *
	 * @param args arguments object (never null; empty object when the client sent none)
	 * @return arbitrary JSON payload; wrapped into an MCP tools/call result by the protocol layer
	 * @throws ToolException for user-facing errors (mapped to isError=true, not a protocol error)
	 */
	JsonObject call(JsonObject args);

	/** User-facing tool failure -> MCP result with isError=true. Unchecked so tools can throw from game-thread lambdas. */
	final class ToolException extends RuntimeException {
		public ToolException(String message) {
			super(message);
		}
	}
}
