package com.mcagent.mcp.client.blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure sparse per-chunk change detector (no Minecraft types, so it is unit-testable).
 *
 * <p>State is a map of chunk -> (packed position -> value), where absent positions mean
 * "air/empty" and are therefore not stored. Callers feed freshly scanned chunks in and get
 * back a flat list of changes.
 */
public final class SparseChunkDiff {
	/** Extracts the stable numeric id reported to clients for a value. */
	public interface IdExtractor {
		int id(Object value);
	}

	private final Map<Long, Map<Long, Object>> chunks = new HashMap<>();
	private final List<long[]> pending = new ArrayList<>();
	private final IdExtractor ids;
	private final int maxChanges;

	private boolean overflowed;

	public SparseChunkDiff(int maxChanges, IdExtractor ids) {
		this.maxChanges = maxChanges;
		this.ids = ids;
	}

	/** Replace a chunk's contents without emitting a diff (initial baseline). */
	public void baseline(long chunkKey, Map<Long, Object> fresh) {
		if (fresh == null) {
			chunks.remove(chunkKey);
		} else {
			chunks.put(chunkKey, fresh);
		}
	}

	/** Compare a chunk against its previous contents and record the difference. */
	public void diff(long chunkKey, Map<Long, Object> fresh) {
		if (fresh == null) {
			// Not loaded any more: forget it, so a later load is a fresh baseline.
			chunks.remove(chunkKey);
			return;
		}
		Map<Long, Object> old = chunks.put(chunkKey, fresh);
		if (old == null) return;

		for (Map.Entry<Long, Object> e : old.entrySet()) {
			if (fresh.containsKey(e.getKey())) continue;
			if (!add(e.getKey(), 0, ids.id(e.getValue()))) return;
		}
		for (Map.Entry<Long, Object> e : fresh.entrySet()) {
			Object prev = old.get(e.getKey());
			if (prev == e.getValue()) continue;
			if (!add(e.getKey(), 1, ids.id(e.getValue()))) return;
		}
	}

	private boolean add(long packedPos, int appeared, int blockId) {
		if (pending.size() >= maxChanges) {
			overflowed = true;
			return false;
		}
		// Packed position layout must match BlockDiffTracker.pack/unpack.
		pending.add(new long[]{
				(int) (packedPos >> 38),
				(int) (packedPos << 52 >> 52),
				(int) (packedPos << 26 >> 38),
				appeared, blockId});
		return true;
	}

	/** Changes recorded so far (never null). */
	public List<long[]> peek() {
		return pending;
	}

	public boolean hasChanges() {
		return !pending.isEmpty() || overflowed;
	}

	public boolean overflowed() {
		return overflowed;
	}

	/**
	 * Take everything recorded so far and reset.
	 * A non-null overflow marker means the caller must rescan from scratch.
	 */
	public List<long[]> drain() {
		List<long[]> out = new ArrayList<>(pending);
		pending.clear();
		overflowed = false;
		return out;
	}

	public void clear() {
		chunks.clear();
		pending.clear();
		overflowed = false;
	}

	public int trackedChunks() {
		return chunks.size();
	}

	/** Total stored (non-air) positions; used in status output. */
	public int trackedPositions() {
		int n = 0;
		for (Map<Long, Object> m : chunks.values()) {
			n += m.size();
		}
		return n;
	}
}
