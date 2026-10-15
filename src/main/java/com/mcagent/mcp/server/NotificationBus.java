package com.mcagent.mcp.server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out bus for server-initiated MCP notifications over SSE (GET /mcp).
 *
 * A GET /mcp handler registers its exchange here and then blocks in
 * {@link #pump(SseSubscriber)}; producers (game thread, tools) call
 * {@link #broadcast(JsonObject)} from any thread.
 */
public final class NotificationBus {
	/** Heartbeat interval; keeps intermediaries from dropping an idle stream. */
	public static final long HEARTBEAT_MILLIS = 15000L;

	private final List<SseSubscriber> subscribers = new CopyOnWriteArrayList<>();

	/** Register an already-authorized GET /mcp exchange as an SSE stream. */
	public SseSubscriber openSse(HttpExchange exchange, String sessionId) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");
		exchange.getResponseHeaders().set("Connection", "keep-alive");
		if (sessionId != null) {
			exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
		}
		exchange.sendResponseHeaders(200, 0); // 0 = chunked streaming
		SseSubscriber sub = new SseSubscriber(exchange);
		subscribers.add(sub);
		return sub;
	}

	/**
	 * Serve one SSE connection until it ends. Must be called on the handler thread
	 * that owns the exchange, because the JDK server closes the response when the
	 * handler returns.
	 */
	public void pump(SseSubscriber subscriber) {
		try {
			subscriber.pump(HEARTBEAT_MILLIS);
		} finally {
			subscribers.remove(subscriber);
		}
	}

	public int subscriberCount() {
		return subscribers.size();
	}

	/** Broadcast a JSON-RPC notification; returns the number of live connections it reached. */
	public int broadcast(JsonObject notification) {
		int reached = 0;
		for (SseSubscriber s : subscribers) {
			if (s.isDead()) {
				subscribers.remove(s);
			} else {
				s.enqueue(notification);
				reached++;
			}
		}
		return reached;
	}

	public void closeAll() {
		for (SseSubscriber s : subscribers) {
			s.closeQuietly();
		}
		subscribers.clear();
	}
}
