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

	/**
	 * Wait (on the calling HTTP thread, never the game thread) for a multi-tick action
	 * started on the game thread to complete.
	 */
	public static <T> T await(CompletableFuture<T> future, long timeoutMs) {
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			throw new com.mcagent.mcp.tools.McpTool.ToolException(
					"action still running after " + timeoutMs + " ms (is the game paused or frozen?)");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted while waiting for the game", e);
		} catch (java.util.concurrent.ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) throw re;
			throw new RuntimeException(cause);
		}
	}

	/** The local player, or a tool error when no world is loaded. Game thread only. */
	public static net.minecraft.client.player.LocalPlayer requirePlayer() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.gameMode == null) {
			throw new com.mcagent.mcp.tools.McpTool.ToolException("not in a world");
		}
		return mc.player;
	}

	public boolean isInWorld() {
		return mc.level != null && mc.player != null;
	}
}
