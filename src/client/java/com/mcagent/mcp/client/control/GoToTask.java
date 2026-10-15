package com.mcagent.mcp.client.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * Walks in a straight line to a point or an entity, one input per tick: face the target, hold
 * forward (sprinting when far), jump into one-block steps and while swimming. Stops on arrival,
 * when no progress is made, or before walking into lava or off a 4+ block drop. Not a
 * pathfinder: around walls the agent must pick waypoints.
 */
final class GoToTask {
	private static final int STUCK_TICKS = 50;
	private static final int MAX_SAFE_DROP = 3;

	private final Vec3 point;
	private final int entityId;
	private final double stopDistance;
	private final int maxTicks;
	final CompletableFuture<JsonObject> done = new CompletableFuture<>();

	private int ticks;
	private double best = Double.MAX_VALUE;
	private int lastProgress;
	private Input input;

	GoToTask(Vec3 point, int entityId, double stopDistance, int maxTicks) {
		this.point = point;
		this.entityId = entityId;
		this.stopDistance = stopDistance;
		this.maxTicks = maxTicks;
	}

	/** Input for the next tick, or null when finished. */
	Input input() {
		return input;
	}

	/** One step; returns the result when finished, null while walking. */
	JsonObject tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		Vec3 target;
		if (point != null) {
			target = point;
		} else {
			Entity e = level.getEntity(entityId);
			if (e == null || !e.isAlive()) return finish(p, false, "target entity is gone");
			target = e.position();
		}
		double dx = target.x - p.getX(), dz = target.z - p.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist <= stopDistance) return finish(p, true, null);
		if (ticks++ >= maxTicks) return finish(p, false, "timed out " + round(dist) + " blocks away");
		if (dist < best - 0.3) {
			best = dist;
			lastProgress = ticks;
		} else if (ticks - lastProgress > STUCK_TICKS) {
			return finish(p, false, "stuck " + round(dist) + " blocks away: " + blockerAhead(level, p, dx / dist, dz / dist));
		}

		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		p.setYRot(yaw);
		p.setYHeadRot(yaw);

		String hazard = hazardAhead(level, p, dx / dist, dz / dist);
		if (hazard != null) return finish(p, false, hazard);

		boolean jump = (p.horizontalCollision && p.onGround()) || p.isInWater();
		boolean sprint = dist > 4 && p.getFoodData().getFoodLevel() > 6;
		input = new Input(true, false, false, false, jump, false, sprint);
		return null;
	}

	JsonObject cancelled(LocalPlayer p, String reason) {
		return finish(p, false, reason);
	}

	/** What stops the player: the first solid block ahead at feet or head height, with a hint. */
	private static String blockerAhead(ClientLevel level, LocalPlayer p, double ux, double uz) {
		BlockPos feet = BlockPos.containing(p.getX() + ux * 0.8, p.getY(), p.getZ() + uz * 0.8);
		String[] where = {"feet", "head", "above head (blocks jumping)"};
		BlockPos[] cells = {feet, feet.above(), p.blockPosition().above(2)};
		for (int i = 0; i < cells.length; i++) {
			var state = level.getBlockState(cells[i]);
			if (!state.getCollisionShape(level, cells[i]).isEmpty()) {
				return "blocked by " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + cells[i].getX()
						+ "," + cells[i].getY() + "," + cells[i].getZ() + " (" + where[i] + "); break it or go around";
			}
		}
		return "no block directly ahead (a gap, a slab edge or an entity?); try a waypoint to the side";
	}

	/** Lava, or a drop deeper than {@link #MAX_SAFE_DROP}, one block ahead. */
	private static String hazardAhead(ClientLevel level, LocalPlayer p, double ux, double uz) {
		BlockPos ahead = BlockPos.containing(p.getX() + ux * 0.9, p.getY(), p.getZ() + uz * 0.9);
		for (int dy = 0; dy >= -1; dy--) {
			if (level.getFluidState(ahead.offset(0, dy, 0)).is(FluidTags.LAVA)) return "lava ahead, stopped";
		}
		if (!p.onGround() || p.isInWater()) return null;
		for (int down = 1; down <= MAX_SAFE_DROP + 1; down++) {
			BlockPos below = ahead.below(down);
			if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()
					|| !level.getFluidState(below).isEmpty()) {
				return null;
			}
		}
		return "drop of more than " + MAX_SAFE_DROP + " blocks ahead, stopped";
	}

	private JsonObject finish(LocalPlayer p, boolean arrived, String reason) {
		input = null;
		JsonObject o = new JsonObject();
		o.addProperty("arrived", arrived);
		if (reason != null) o.addProperty("reason", reason);
		o.addProperty("ticks", ticks);
		if (p != null) {
			JsonArray pos = new JsonArray();
			pos.add(round(p.getX()));
			pos.add(round(p.getY()));
			pos.add(round(p.getZ()));
			o.add("position", pos);
		}
		done.complete(o);
		return o;
	}

	private static double round(double d) {
		return Math.round(d * 100) / 100.0;
	}
}
