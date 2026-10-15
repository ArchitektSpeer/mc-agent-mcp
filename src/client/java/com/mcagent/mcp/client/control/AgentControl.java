package com.mcagent.mcp.client.control;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.blocks.BlockScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * Timed actuation of the local player: held movement keys, a held "use" key, and the
 * multi-tick block-breaking task.
 *
 * All state is owned by the game thread. Tools start actions through the
 * {@link com.mcagent.mcp.client.GameBridge} and then wait on the returned future from their
 * HTTP thread; {@link #tick} (END_CLIENT_TICK) advances and completes them. Starting an
 * action of a kind that is already running replaces it (the old future completes with
 * {@code false} / {@code interrupted}).
 */
public final class AgentControl {
	/** Static handle for the input/attack mixins, like {@code BlockDiffTracker.instance}. */
	public static volatile AgentControl instance;

	/** Upper bound for any timed action: 30 s of game time. */
	public static final int MAX_TICKS = 600;

	private Input heldInput;
	private int moveTicksLeft;
	private CompletableFuture<Boolean> moveDone;

	private int useTicksLeft;
	private CompletableFuture<Boolean> useDone;

	private BreakTask breakTask;

	private TickTask menuTask;
	private CompletableFuture<JsonObject> menuDone;

	/** A multi-tick script driven by {@link #tick}; returns its result when finished, null while running. */
	public interface TickTask {
		JsonObject tick(Minecraft mc);

		/** Result reported when the task is cancelled before finishing. */
		JsonObject cancelled(String reason);
	}

	// ---- movement --------------------------------------------------------------------

	/** Hold the given movement keys for {@code ticks} ticks. Game thread only. */
	public CompletableFuture<Boolean> holdMovement(Input input, int ticks) {
		finishMove(false);
		heldInput = input;
		moveTicksLeft = ticks;
		moveDone = new CompletableFuture<>();
		return moveDone;
	}

	/** Called from the KeyboardInput mixin: the input to apply this tick, or null for the real keyboard. */
	public static Input inputOverride() {
		AgentControl c = instance;
		return c != null && c.moveTicksLeft > 0 ? c.heldInput : null;
	}

	// ---- use key ---------------------------------------------------------------------

	/**
	 * Hold the "use" key for {@code ticks} ticks, letting vanilla drive start/continue/release
	 * (eating, drinking, drawing a bow, blocking with a shield).
	 */
	public CompletableFuture<Boolean> holdUse(Minecraft mc, int ticks) {
		finishUse(mc, false);
		mc.options.keyUse.setDown(true);
		useTicksLeft = ticks;
		useDone = new CompletableFuture<>();
		return useDone;
	}

	// ---- block breaking --------------------------------------------------------------

	/** Start mining {@code pos}; the future yields a result object when the block is gone or the attempt ends. */
	public CompletableFuture<JsonObject> startBreak(Minecraft mc, BlockPos pos, Direction face, int maxTicks) {
		cancelBreak(mc, "interrupted by a new break_block");
		breakTask = new BreakTask(pos, face, maxTicks, BlockScanner.idOf(mc.level.getBlockState(pos)));
		return breakTask.done;
	}

	/** True while a break task owns the attack key; the Minecraft mixin then skips vanilla continueAttack. */
	public static boolean isBreaking() {
		AgentControl c = instance;
		return c != null && c.breakTask != null;
	}

	// ---- menu scripts (crafting) -------------------------------------------------------

	/** Run a menu script (e.g. crafting) one step per tick; replaces any running one. */
	public CompletableFuture<JsonObject> startMenuTask(TickTask task) {
		cancelMenuTask("interrupted by a new crafting request");
		menuTask = task;
		menuDone = new CompletableFuture<>();
		return menuDone;
	}

	private void cancelMenuTask(String reason) {
		if (menuTask == null) return;
		JsonObject r = menuTask.cancelled(reason);
		menuTask = null;
		menuDone.complete(r);
		menuDone = null;
	}

	// ---- lifecycle -------------------------------------------------------------------

	/** Advance timed actions by one tick. END_CLIENT_TICK, game thread. */
	public void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			stopAll(mc);
			return;
		}
		if (moveTicksLeft > 0 && --moveTicksLeft == 0) {
			finishMove(true);
		}
		if (useTicksLeft > 0 && --useTicksLeft == 0) {
			finishUse(mc, true);
		}
		if (breakTask != null) {
			tickBreak(mc);
		}
		if (menuTask != null) {
			JsonObject r = menuTask.tick(mc);
			if (r != null) {
				menuTask = null;
				menuDone.complete(r);
				menuDone = null;
			}
		}
	}

	/** Release every key and cancel every task. Returns a summary of what was stopped. */
	public JsonObject stopAll(Minecraft mc) {
		JsonObject o = new JsonObject();
		o.addProperty("movement", moveTicksLeft > 0);
		o.addProperty("use", useTicksLeft > 0);
		o.addProperty("breaking", breakTask != null);
		o.addProperty("crafting", menuTask != null);
		finishMove(false);
		finishUse(mc, false);
		cancelBreak(mc, "stopped");
		cancelMenuTask("stopped");
		return o;
	}

	public JsonObject status() {
		JsonObject o = new JsonObject();
		o.addProperty("moveTicksLeft", moveTicksLeft);
		o.addProperty("useTicksLeft", useTicksLeft);
		if (breakTask != null) {
			o.addProperty("breaking", breakTask.pos.getX() + "," + breakTask.pos.getY() + "," + breakTask.pos.getZ());
		}
		if (menuTask != null) o.addProperty("crafting", true);
		return o;
	}

	/** Turn the player to look at a world point (eye-anchored); the rotation is sent with the next movement packet. */
	public static void lookAt(LocalPlayer player, Vec3 target) {
		player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
	}

	/** The face of {@code pos} that points toward the player's eyes. */
	public static Direction faceToward(LocalPlayer player, BlockPos pos) {
		Vec3 d = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(d.x, d.y, d.z);
	}

	private void finishMove(boolean completed) {
		moveTicksLeft = 0;
		heldInput = null;
		if (moveDone != null) {
			moveDone.complete(completed);
			moveDone = null;
		}
	}

	private void finishUse(Minecraft mc, boolean completed) {
		if (useDone != null || useTicksLeft > 0) {
			mc.options.keyUse.setDown(false);
		}
		useTicksLeft = 0;
		if (useDone != null) {
			useDone.complete(completed);
			useDone = null;
		}
	}

	private void cancelBreak(Minecraft mc, String reason) {
		if (breakTask == null) return;
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		finishBreak(false, reason);
	}

	private void tickBreak(Minecraft mc) {
		BreakTask t = breakTask;
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		BlockState state = level.getBlockState(t.pos);
		if (state.isAir()) {
			finishBreak(true, null);
			return;
		}
		if (t.ticks++ >= t.maxTicks) {
			cancelBreak(mc, "timed out after " + t.maxTicks + " ticks (block still " + BlockScanner.idOf(state) + ")");
			return;
		}
		if (!p.isWithinBlockInteractionRange(t.pos, 0.0)) {
			cancelBreak(mc, "out of reach");
			return;
		}
		lookAt(p, Vec3.atCenterOf(t.pos));
		if (t.started) {
			mc.gameMode.continueDestroyBlock(t.pos, t.face);
		} else {
			mc.gameMode.startDestroyBlock(t.pos, t.face);
			t.started = true;
		}
		p.swing(InteractionHand.MAIN_HAND);
	}

	private void finishBreak(boolean broken, String reason) {
		BreakTask t = breakTask;
		breakTask = null;
		JsonObject o = new JsonObject();
		o.addProperty("broken", broken);
		o.addProperty("block", t.blockId);
		o.addProperty("ticks", t.ticks);
		if (reason != null) o.addProperty("reason", reason);
		t.done.complete(o);
	}

	private static final class BreakTask {
		final BlockPos pos;
		final Direction face;
		final int maxTicks;
		final String blockId;
		final CompletableFuture<JsonObject> done = new CompletableFuture<>();
		int ticks;
		boolean started;

		BreakTask(BlockPos pos, Direction face, int maxTicks, String blockId) {
			this.pos = pos;
			this.face = face;
			this.maxTicks = maxTicks;
			this.blockId = blockId;
		}
	}
}
