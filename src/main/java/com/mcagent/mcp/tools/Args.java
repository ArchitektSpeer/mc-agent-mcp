package com.mcagent.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcagent.mcp.tools.McpTool.ToolException;

/**
 * Typed, validating accessors for tool arguments. Every malformed value becomes a
 * {@link ToolException} (MCP {@code isError=true}) instead of a Gson exception, so the
 * agent gets a message it can act on.
 */
public final class Args {
	private Args() {
	}

	public static boolean has(JsonObject args, String key) {
		return args.has(key) && !args.get(key).isJsonNull();
	}

	public static int getInt(JsonObject args, String key, int def, int min, int max) {
		if (!has(args, key)) return def;
		int v = asInt(args.get(key), key);
		if (v < min || v > max) {
			throw new ToolException("'" + key + "' must be between " + min + " and " + max + ", got " + v);
		}
		return v;
	}

	public static double getDouble(JsonObject args, String key, double def) {
		if (!has(args, key)) return def;
		JsonElement e = args.get(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new ToolException("'" + key + "' must be a number");
		}
		double v = e.getAsDouble();
		if (!Double.isFinite(v)) throw new ToolException("'" + key + "' must be finite");
		return v;
	}

	public static boolean getBool(JsonObject args, String key, boolean def) {
		if (!has(args, key)) return def;
		JsonElement e = args.get(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new ToolException("'" + key + "' must be a boolean");
		}
		return e.getAsBoolean();
	}

	public static String getString(JsonObject args, String key, String def) {
		if (!has(args, key)) return def;
		JsonElement e = args.get(key);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw new ToolException("'" + key + "' must be a string");
		}
		return e.getAsString();
	}

	public static String requireString(JsonObject args, String key) {
		String s = getString(args, key, null);
		if (s == null) throw new ToolException("missing required argument '" + key + "'");
		return s;
	}

	/** One of {@code allowed} (case-insensitive), lower-cased. */
	public static String getEnum(JsonObject args, String key, String def, String... allowed) {
		String s = getString(args, key, def);
		if (s == null) return null;
		for (String a : allowed) {
			if (a.equalsIgnoreCase(s)) return a;
		}
		throw new ToolException("'" + key + "' must be one of " + String.join(", ", allowed) + ", got '" + s + "'");
	}

	/** Integer block position {@code [x,y,z]}, or null when absent. */
	public static int[] getPos(JsonObject args, String key) {
		if (!has(args, key)) return null;
		JsonElement e = args.get(key);
		if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
			throw new ToolException("'" + key + "' must be [x,y,z]");
		}
		JsonArray a = e.getAsJsonArray();
		return new int[]{asInt(a.get(0), key), asInt(a.get(1), key), asInt(a.get(2), key)};
	}

	public static int[] requirePos(JsonObject args, String key) {
		int[] p = getPos(args, key);
		if (p == null) throw new ToolException("missing required argument '" + key + "' ([x,y,z])");
		return p;
	}

	/** Exact integers only: 1.5 is rejected rather than silently truncated. */
	private static int asInt(JsonElement e, String key) {
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new ToolException("'" + key + "' must be an integer");
		}
		double d = e.getAsDouble();
		if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
			throw new ToolException("'" + key + "' must be an integer");
		}
		return (int) d;
	}
}
