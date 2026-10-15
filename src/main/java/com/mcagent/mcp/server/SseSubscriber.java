package com.mcagent.mcp.server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One Server-Sent Events connection (an open GET /mcp response).
 *
 * The JDK's HttpServer finalizes a response as soon as the handler returns, so the
 * GET handler thread blocks inside {@link #pump(long)} for the connection's lifetime.
 * Other threads only {@link #enqueue(JsonObject)} frames; the pump thread is the single
 * writer, which also emits periodic keep-alive comments.
 */
public final class SseSubscriber {
	private static final Logger LOGGER = LoggerFactory.getLogger("mc-agent-mcp");

	private final HttpExchange exchange;
	private final OutputStream out;
	private final BlockingQueue<JsonObject> queue = new LinkedBlockingQueue<>();
	private final AtomicBoolean dead = new AtomicBoolean();
	private volatile Thread pumpThread;

	public SseSubscriber(HttpExchange exchange) throws IOException {
		this.exchange = exchange;
		this.out = exchange.getResponseBody();
	}

	/** Queue a notification for delivery. Safe to call from any thread. */
	public void enqueue(JsonObject message) {
		if (!dead.get()) {
			queue.offer(message);
		}
	}

	/**
	 * Write frames until the client goes away or {@link #closeQuietly()} is called.
	 * Runs on the GET handler thread; returns only when the connection is finished.
	 */
	public void pump(long heartbeatMillis) {
		pumpThread = Thread.currentThread();
		try {
			while (!dead.get()) {
				JsonObject message = queue.poll(heartbeatMillis, TimeUnit.MILLISECONDS);
				if (message == null) {
					write(": keep-alive\n\n");
				} else {
					write("event: message\ndata: " + message + "\n\n");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			LOGGER.debug("SSE stream closed by peer", e);
		} finally {
			closeQuietly();
		}
	}

	private void write(String frame) throws IOException {
		out.write(frame.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	public boolean isDead() {
		return dead.get();
	}

	/** Idempotent; unblocks the pump thread and closes the underlying exchange. */
	public void closeQuietly() {
		if (!dead.compareAndSet(false, true)) {
			return;
		}
		Thread t = pumpThread;
		if (t != null && t != Thread.currentThread()) {
			t.interrupt();
		}
		try {
			exchange.close();
		} catch (Exception e) {
			LOGGER.debug("SSE close failed", e);
		}
	}
}
