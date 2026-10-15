package com.mcagent.mcp.client;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * All world access must happen on the Minecraft main (client) thread.
 * The MCP HTTP threads call {@link #callOnGameThread} and block briefly
 * for the result; heavy work (block scanning, M3) additionally moves
 * encoding off-thread again.
 */
public final class GameBridge {
	private static final long DEFAULT_TIMEOUT_MS = 3000;

	private final Minecraft mc = Minecraft.getInstance();

	/**
	 * Run a read/action on the game thread and wait for the result.
	 * Unchecked exceptions thrown by the task propagate; the runtime is
	 * expected to map them to MCP {@code isError=true}.
	 */
	public <T> T callOnGameThread(Supplier<T> task) {
		if (mc.isSameThread()) {
			return task.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				future.complete(task.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			// Unwrap the game's original exception to avoid pointless wrapping.
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) throw re;
			throw new RuntimeException(cause);
		} catch (Exception e) {
			throw new RuntimeException("game-thread call failed: " + e.getMessage(), e);
		}
	}

	public boolean isInWorld() {
		return mc.level != null && mc.player != null;
	}
}
