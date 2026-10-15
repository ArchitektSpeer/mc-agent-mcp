package com.mcagent.mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;

/**
 * Bounded log of received chat and system messages, so an agent can read what other players
 * and the server say. Each entry gets a monotonically increasing {@code seq}; readers pass
 * the last seq they saw to get only newer lines. Thread-safe.
 */
public final class ChatLog {
	public static final int CAPACITY = 256;

	private final ArrayDeque<JsonObject> entries = new ArrayDeque<>();
	private long nextSeq = 1;

	public synchronized void add(String kind, String sender, String text) {
		JsonObject e = new JsonObject();
		e.addProperty("seq", nextSeq++);
		e.addProperty("kind", kind);
		if (sender != null) e.addProperty("sender", sender);
		e.addProperty("text", text);
		e.addProperty("timeMs", System.currentTimeMillis());
		entries.addLast(e);
		while (entries.size() > CAPACITY) {
			entries.removeFirst();
		}
	}

	/** Entries with {@code seq > afterSeq}, oldest first, at most {@code max} (the newest ones). */
	public synchronized JsonObject since(long afterSeq, int max) {
		JsonArray out = new JsonArray();
		int skipped = 0;
		for (JsonObject e : entries) {
			if (e.get("seq").getAsLong() > afterSeq) out.add(e);
		}
		while (out.size() > max) {
			out.remove(0);
			skipped++;
		}
		JsonObject o = new JsonObject();
		o.add("messages", out);
		o.addProperty("lastSeq", nextSeq - 1);
		if (skipped > 0) o.addProperty("omitted", skipped);
		return o;
	}
}
