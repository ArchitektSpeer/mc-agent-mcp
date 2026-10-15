package com.mcagent.mcp.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP "Streamable HTTP" transport (spec 2025-06-18) on the JDK built-in HTTP server.
 *
 * Scope of this minimal implementation:
 *  - single endpoint {@code /mcp}
 *  - POST with JSON-RPC messages; answered synchronously as {@code application/json}
 *    (the spec allows a server to answer requests with a plain JSON object instead of SSE)
 *  - notifications/responses (no id) -> 202 Accepted
 *  - GET (standalone SSE stream) pushes server-initiated notifications
 *    (block-diff events); DELETE terminates the session and closes those streams
 *  - Bearer-token auth on every request; loopback bind only by default
 *  - Origin validation: browser requests from non-loopback origins are rejected (403),
 *    as the spec requires, to block DNS-rebinding attacks against the local server
 */
public final class McpHttpServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("mc-agent-mcp");
	/** Largest accepted POST body; real MCP messages are a few KB. */
	public static final int MAX_BODY_BYTES = 1 << 20;

	private final McpProtocol protocol;
	private final NotificationBus bus;
	private final String bindAddress;
	private final int port;
	private final String authToken;

	private HttpServer server;
	private volatile String sessionId;

	public McpHttpServer(McpProtocol protocol, NotificationBus bus, String bindAddress, int port, String authToken) {
		this.protocol = protocol;
		this.bus = bus;
		this.bindAddress = bindAddress;
		this.port = port;
		this.authToken = authToken;
	}

	public void start() throws IOException {
		HttpServer s = HttpServer.create(new InetSocketAddress(bindAddress, port), 16);
		s.createContext("/mcp", this::handle);
		ThreadFactory tf = new ThreadFactory() {
			private final AtomicInteger n = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "mcagent-mcp-http-" + n.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		};
		// Cached pool: SSE connections occupy a thread for their whole lifetime,
		// so a fixed-size pool would starve regular POST requests.
		s.setExecutor(Executors.newCachedThreadPool(tf));
		s.start();
		this.server = s;
		LOGGER.info("[mcagent] MCP server listening on http://{}:{}/mcp (auth token in config/mcagent.json)",
				bindAddress, port);
	}

	public void stop() {
		bus.closeAll();
		if (server != null) {
			server.stop(1);
			server = null;
			LOGGER.info("[mcagent] MCP server stopped.");
		}
	}

	public String endpoint() {
		return "http://" + bindAddress + ":" + port + "/mcp";
	}

	private void handle(HttpExchange ex) throws IOException {
		// Set when a GET /mcp upgrade hands the long-lived response to the SSE bus:
		// in that case the exchange must stay open, so the finally block skips close().
		boolean sseOwned = false;
		try {
			if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) {
				respond(ex, 403, "application/json", "{\"error\":\"forbidden origin\"}");
				return;
			}
			if (!authorized(ex)) {
				respond(ex, 401, "application/json",
						"{\"error\":\"unauthorized: send header 'Authorization: Bearer <token>'\"}");
				return;
			}
			switch (ex.getRequestMethod()) {
				case "POST" -> handlePost(ex);
				case "GET" -> {
					if (sessionId == null) {
						sessionId = UUID.randomUUID().toString();
					}
					// Blocks this handler thread until the stream ends: the JDK server
					// closes the response as soon as the handler returns.
					SseSubscriber sub = bus.openSse(ex, sessionId);
					sseOwned = true;
					bus.pump(sub);
				}
				case "DELETE" -> {
					sessionId = null;
					bus.closeAll();
					respond(ex, 200, "application/json", "{}");
				}
				default -> respond(ex, 405, "application/json", "{\"error\":\"method not allowed\"}");
			}
		} catch (Throwable t) {
			LOGGER.warn("[mcagent] error handling MCP request", t);
			try {
				respond(ex, 500, "application/json", "{\"error\":\"internal server error\"}");
			} catch (IOException ignored) {
			}
		} finally {
			if (!sseOwned) {
				ex.close();
			}
		}
	}

	/**
	 * Non-browser MCP clients send no Origin; browsers always do. Only loopback origins may
	 * talk to the server, so a web page cannot reach it through DNS rebinding.
	 */
	static boolean originAllowed(String origin) {
		if (origin == null) return true;
		try {
			String host = java.net.URI.create(origin.trim()).getHost();
			if (host == null) return false;
			return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private boolean authorized(HttpExchange ex) {
		String h = ex.getRequestHeaders().getFirst("Authorization");
		String expected = "Bearer " + authToken;
		return h != null && MessageDigest.isEqual(
				h.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
	}

	private void handlePost(HttpExchange ex) throws IOException {
		String body;
		try (InputStream in = ex.getRequestBody()) {
			byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
			if (bytes.length > MAX_BODY_BYTES) {
				respond(ex, 413, "application/json", "{\"error\":\"request body larger than 1 MiB\"}");
				return;
			}
			body = new String(bytes, StandardCharsets.UTF_8);
		}
		if (body.isBlank()) {
			respondJsonRpc(ex, JsonRpc.error(JsonNull.INSTANCE, JsonRpc.PARSE_ERROR, "empty body"));
			return;
		}

		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(body);
		} catch (Exception e) {
			respondJsonRpc(ex, JsonRpc.error(JsonNull.INSTANCE, JsonRpc.PARSE_ERROR, "invalid JSON"));
			return;
		}

		// Batches are rare in MCP; answer with the batch form for correctness.
		if (parsed.isJsonArray()) {
			var arr = parsed.getAsJsonArray();
			var out = new com.google.gson.JsonArray();
			boolean anyRequest = false;
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) continue;
				boolean isRequest = el.getAsJsonObject().has("id")
						&& !(el.getAsJsonObject().get("id") instanceof JsonNull);
				JsonObject resp = protocol.handle(el.getAsJsonObject());
				if (resp != null) {
					out.add(resp);
					anyRequest = anyRequest || isRequest;
				}
			}
			if (!anyRequest) {
				acceptNotifications(ex);
			} else {
				respondJsonRpc(ex, out);
			}
			return;
		}

		if (!parsed.isJsonObject()) {
			respondJsonRpc(ex, JsonRpc.error(JsonNull.INSTANCE, JsonRpc.INVALID_REQUEST, "expected JSON-RPC object"));
			return;
		}

		JsonObject msg = parsed.getAsJsonObject();
		String method = msg.has("method") && msg.get("method").isJsonPrimitive()
				? msg.get("method").getAsString() : "";
		boolean isInitialize = "initialize".equals(method);

		JsonObject response = protocol.handle(msg);
		if (response == null) {
			// Pure notification / client response -> 202, no body.
			acceptNotifications(ex);
			return;
		}
		if (isInitialize && sessionId == null) {
			sessionId = UUID.randomUUID().toString();
		}
		if (sessionId != null) {
			ex.getResponseHeaders().set("Mcp-Session-Id", sessionId);
		}
		respondJsonRpc(ex, response);
	}

	private void acceptNotifications(HttpExchange ex) throws IOException {
		if (sessionId != null) {
			ex.getResponseHeaders().set("Mcp-Session-Id", sessionId);
		}
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(202, -1);
	}

	private void respondJsonRpc(HttpExchange ex, JsonElement payload) throws IOException {
		respond(ex, 200, "application/json; charset=utf-8", payload.toString());
	}

	private void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}
}
