package com.mcagent.mcp.client.blocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks block changes around the player for {@code subscribe_blocks} / {@code get_block_changes}.
 *
 * <p>All state lives on the game thread: {@link #onBlockChanged} is invoked from a Mixin on
 * {@code ClientLevel.setBlock}, and {@link #tick()}/{@link #takeDiff()} are driven from the
 * client tick loop and {@code GameBridge.callOnGameThread}.
 *
 * <p>Why this stays cheap on a large cube:
 * <ul>
 *   <li>Change detection is exact-first: the Mixin records the chunk that changed, and each
 *       tick re-scans only those chunks (see {@link SparseChunkDiff}). A full re-scan happens
 *       only when the cube re-anchors or a chunk (re)loads.</li>
 *   <li>Snapshots are sparse and per chunk: an ordinary world stores a few thousand non-air
 *       entries instead of every cell of the cube.</li>
 *   <li>The cube origin is 16-aligned on all three axes. Otherwise stepping up one block would
 *       re-anchor the cube and silently discard the changes gathered so far.</li>
 * </ul>
 */
public final class BlockDiffTracker {
	public static final int MAX_RADIUS = 24;
	public static final int MAX_CHANGES_PER_BATCH = 4096;

	/** Static handle for the Mixin hook (client-only wiring). */
	public static volatile BlockDiffTracker instance;

	/** Placeholder reported when a block id cannot be resolved. */
	public static final int UNKNOWN_BLOCK_ID = -1;

	private final SparseChunkDiff diff =
			new SparseChunkDiff(MAX_CHANGES_PER_BATCH, v -> stateId((BlockState) v));

	private boolean tracking;
	private int radius;
	private long epoch;

	/** Anchored origin of the tracked cube; all three axes are multiples of 16. */
	private int ox, oy, oz;
	private int worldMinY = Integer.MIN_VALUE;
	private int worldMaxY = Integer.MAX_VALUE;

	/** Chunks whose contents changed and must be re-scanned (and diffed) on the next tick. */
	private final Set<Long> dirtyChunks = new HashSet<>();
	/** Chunks to re-scan without emitting a diff (load/unload: contents are not trustworthy). */
	private final Set<Long> rebaselineChunks = new HashSet<>();

	private boolean baselineReady;
	/** Set when a new baseline is built, so the next poll can tell the client to rescan. */
	private boolean announceBaseline;

	private long lastScanNanos;
	private long changesDropped;

	/** Mixin hook: {@code ClientLevel.setBlock} changed a block. Game thread only. */
	public static void onBlockChanged(BlockPos pos) {
		BlockDiffTracker t = instance;
		if (t != null) {
			t.markDirty(pos);
		}
	}

	/** A chunk finished loading: re-baseline it silently. */
	public synchronized void onChunkLoaded(int cx, int cz) {
		if (!tracking) return;
		long key = chunkKey(cx, cz);
		dirtyChunks.remove(key);
		rebaselineChunks.add(key);
	}

	/** A chunk unloaded: drop its snapshot so a later load counts as a fresh baseline. */
	public synchronized void onChunkUnloaded(int cx, int cz) {
		if (!tracking) return;
		long key = chunkKey(cx, cz);
		dirtyChunks.remove(key);
		rebaselineChunks.remove(key);
		diff.diff(key, null); // forget it, never report its blocks as removed
	}

	private synchronized void markDirty(BlockPos pos) {
		if (!tracking || !baselineReady) return;
		int y = pos.getY();
		if (y < oy || y >= oy + size() || y >= worldMaxY) return;
		int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
		if (!inCube(cx, cz)) return;
		long key = chunkKey(cx, cz);
		dirtyChunks.add(key);
		rebaselineChunks.remove(key);
	}

	public synchronized boolean start(int radius) {
		if (tracking) return false;
		this.radius = Math.min(Math.max(radius, 1), MAX_RADIUS);
		this.tracking = true;
		this.epoch++;
		reset();
		return true;
	}

	public synchronized boolean stop() {
		if (!tracking) return false;
		this.tracking = false;
		reset();
		return true;
	}

	private void reset() {
		diff.clear();
		dirtyChunks.clear();
		rebaselineChunks.clear();
		baselineReady = false;
		announceBaseline = false;
	}

	public synchronized boolean isTracking() {
		return tracking;
	}

	public synchronized int getRadius() {
		return radius;
	}

	public synchronized long getEpoch() {
		return epoch;
	}

	public synchronized long getChangesDropped() {
		return changesDropped;
	}

	/** Called from END_CLIENT_TICK (game thread): re-scan only what is known to be dirty. */
	public synchronized void tick() {
		if (!tracking) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) return;
		worldMinY = level.getMinBuildHeight();
		worldMaxY = level.getMaxBuildHeight();

		BlockPos p = mc.player.blockPosition();
		int nx = ((p.getX() - radius) >> 4) << 4;
		int ny = Math.max(((p.getY() - radius) >> 4) << 4, worldMinY);
		int nz = ((p.getZ() - radius) >> 4) << 4;

		if (!baselineReady || nx != ox || ny != oy || nz != oz) {
			ox = nx; oy = ny; oz = nz;
			rebaselineAll(level);
			return;
		}
		if (dirtyChunks.isEmpty() && rebaselineChunks.isEmpty()) return;

		long t0 = System.nanoTime();
		if (!dirtyChunks.isEmpty()) {
			List<Long> keys = new ArrayList<>(dirtyChunks);
			dirtyChunks.clear();
			for (long key : keys) {
				diff.diff(key, scanChunk(level, key));
			}
		}
		if (!rebaselineChunks.isEmpty()) {
			List<Long> keys = new ArrayList<>(rebaselineChunks);
			rebaselineChunks.clear();
			for (long key : keys) {
				diff.baseline(key, scanChunk(level, key));
			}
		}
		lastScanNanos = System.nanoTime() - t0;
	}

	/** Rebuild the whole cube without emitting a diff (new baseline after start/re-anchor). */
	private void rebaselineAll(ClientLevel level) {
		diff.clear();
		dirtyChunks.clear();
		rebaselineChunks.clear();
		long t0 = System.nanoTime();
		int side = size();
		for (int cz = oz; cz < oz + side; cz += 16) {
			for (int cx = ox; cx < ox + side; cx += 16) {
				long key = chunkKey(cx >> 4, cz >> 4);
				diff.baseline(key, scanChunk(level, key));
			}
		}
		lastScanNanos = System.nanoTime() - t0;
		baselineReady = true;
		announceBaseline = true;
	}

	/**
	 * Sparse scan of the part of a chunk that intersects the tracked cube.
	 * Returns null when the chunk is not loaded (callers then keep the previous snapshot).
	 */
	private Map<Long, Object> scanChunk(ClientLevel level, long chunkKey) {
		int cx = (int) (chunkKey >> 32);
		int cz = (int) chunkKey;
		LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) return null;

		Map<Long, Object> out = new HashMap<>();
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = cx << 4, baseZ = cz << 4;
		int x0 = Math.max(ox, baseX), x1 = Math.min(ox + size(), baseX + 16);
		int z0 = Math.max(oz, baseZ), z1 = Math.min(oz + size(), baseZ + 16);
		if (x0 >= x1 || z0 >= z1) return out;

		int yTop = Math.min(oy + size(), worldMaxY);
		for (int idx = 0; idx < sections.length; idx++) {
			LevelChunkSection sec = sections[idx];
			if (sec == null || sec.hasOnlyAir()) continue;
			int secBaseY = worldMinY + idx * 16;
			int y0 = Math.max(oy, secBaseY), y1 = Math.min(yTop, secBaseY + 16);
			if (y0 >= y1) continue;
			for (int y = y0; y < y1; y++) {
				for (int z = z0; z < z1; z++) {
					for (int x = x0; x < x1; x++) {
						BlockState state = sec.getBlockState(x & 15, y & 15, z & 15);
						if (state.isAir()) continue; // sparse: air is the implicit default
						out.put(pack(x, y, z), state);
					}
				}
			}
		}
		return out;
	}

	private boolean inCube(int cx, int cz) {
		return (cx << 4) < ox + size() && (cx << 4) + 16 > ox
				&& (cz << 4) < oz + size() && (cz << 4) + 16 > oz;
	}

	/** Cube side length: radius*2 rounded up to a whole number of chunks. */
	public int size() {
		return ((radius * 2 + 15) >> 4) << 4;
	}

	private static long chunkKey(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	private static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	private static int stateId(BlockState s) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(s.getBlock());
	}

	/**
	 * Drain the changes recorded since the last call. Cheap: scanning happened in {@link #tick()}.
	 * Returns null when nothing changed.
	 */
	public synchronized DiffResult takeDiff() {
		if (!tracking || !baselineReady) return null;

		if (announceBaseline) {
			announceBaseline = false;
			return DiffResult.full(lastScanNanos);
		}
		if (!diff.hasChanges()) return null;

		boolean overflow = diff.overflowed();
		List<long[]> out = diff.drain();
		if (overflow) {
			changesDropped += out.size();
			return DiffResult.resync(lastScanNanos);
		}
		return DiffResult.changes(out, lastScanNanos);
	}

	/** Result of one diff pass. */
	public static final class DiffResult {
		/** true when a new baseline was established, so no incremental data exists yet. */
		public final boolean full;
		/** true when the batch overflowed: the client must re-pull with get_blocks. */
		public final boolean resync;
		/** [x,y,z,appeared(1)/removed(0),blockGlobalId]; null for full/resync. */
		public final List<long[]> changes;
		public final long snapNanos;

		private DiffResult(boolean full, boolean resync, List<long[]> changes, long snapNanos) {
			this.full = full;
			this.resync = resync;
			this.changes = changes;
			this.snapNanos = snapNanos;
		}

		static DiffResult changes(List<long[]> c, long nanos) {
			return new DiffResult(false, false, c, nanos);
		}

		static DiffResult full(long nanos) {
			return new DiffResult(true, false, null, nanos);
		}

		static DiffResult resync(long nanos) {
			return new DiffResult(false, true, null, nanos);
		}
	}
}
