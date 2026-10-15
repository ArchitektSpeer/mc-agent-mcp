import com.mcagent.mcp.client.blocks.SparseChunkDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exercises the real change-detection logic (SparseChunkDiff, the engine behind
 * BlockDiffTracker) with plain values instead of BlockStates.
 */
public class DiffLogicTest {
	static int failures = 0;

	public static void main(String[] args) {
		baselineProducesNoChanges();
		appearedAndRemovedAreReported();
		replacementReportsNewValue();
		unloadedChunkIsForgottenNotEmptied();
		overflowSignalsResync();
		sparseAirIsImplicit();
		System.out.println(failures == 0 ? "DIFF-LOGIC: PASS" : "DIFF-LOGIC: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	/** A chunk key in the same layout BlockDiffTracker uses: (cx << 32) | cz. */
	static long ck(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	/** Same bit layout as BlockDiffTracker.pack. */
	static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	static Map<Long, Object> chunk(Object... xyzValue) {
		Map<Long, Object> m = new HashMap<>();
		for (int i = 0; i < xyzValue.length; i += 4) {
			m.put(pack((int) xyzValue[i], (int) xyzValue[i + 1], (int) xyzValue[i + 2]), xyzValue[i + 3]);
		}
		return m;
	}

	static SparseChunkDiff newDiff(int max) {
		return new SparseChunkDiff(max, v -> ((String) v).hashCode() & 0xFF);
	}

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}

	static void baselineProducesNoChanges() {
		System.out.println("baseline:");
		SparseChunkDiff d = newDiff(100);
		d.baseline(ck(0, 0), chunk(1, 1, 1, "stone", 2, 1, 1, "dirt"));
		check("baseline alone emits nothing", !d.hasChanges());
		check("baseline is tracked", d.trackedChunks() == 1 && d.trackedPositions() == 2);
	}

	static void appearedAndRemovedAreReported() {
		System.out.println("appeared/removed:");
		SparseChunkDiff d = newDiff(100);
		d.baseline(ck(0, 0), chunk(1, 1, 1, "stone", 2, 1, 1, "dirt"));
		// stone stays, dirt replaced by air (removed), gold appears.
		d.diff(ck(0, 0), chunk(1, 1, 1, "stone", 3, 1, 1, "gold"));
		List<long[]> c = d.drain();
		check("two changes detected (1 removed, 1 appeared)", c.size() == 2);
		List<long[]> removed = new ArrayList<>(), appeared = new ArrayList<>();
		for (long[] e : c) (e[3] == 0 ? removed : appeared).add(e);
		check("removed is (2,1,1) with the OLD block id", removed.size() == 1
				&& removed.get(0)[0] == 2 && removed.get(0)[1] == 1 && removed.get(0)[2] == 1
				&& removed.get(0)[4] == ("dirt".hashCode() & 0xFF));
		check("appeared is (3,1,1) with the NEW block id", appeared.size() == 1
				&& appeared.get(0)[0] == 3 && appeared.get(0)[4] == ("gold".hashCode() & 0xFF));
		check("drain resets pending", !d.hasChanges() && d.drain().isEmpty());
		check("unchanged block was not reported", c.stream().noneMatch(e -> e[0] == 1));
	}

	static void replacementReportsNewValue() {
		System.out.println("replacement:");
		SparseChunkDiff d = newDiff(100);
		d.baseline(ck(0, 0), chunk(5, 5, 5, "stone"));
		d.diff(ck(0, 0), chunk(5, 5, 5, "gold"));
		List<long[]> c = d.drain();
		check("same position changed value -> one 'appeared' entry", c.size() == 1
				&& c.get(0)[0] == 5 && c.get(0)[3] == 1
				&& c.get(0)[4] == ("gold".hashCode() & 0xFF));
	}

	static void unloadedChunkIsForgottenNotEmptied() {
		System.out.println("chunk unload:");
		SparseChunkDiff d = newDiff(100);
		d.baseline(ck(0, 0), chunk(1, 1, 1, "stone", 2, 1, 1, "dirt"));
		d.diff(ck(0, 0), null); // chunk unloaded
		check("unload emits no 'removed' changes", !d.hasChanges());
		check("unload drops the snapshot", d.trackedChunks() == 0);
		// Reload: must be a baseline again, not a flood of changes.
		d.baseline(ck(0, 0), chunk(1, 1, 1, "stone"));
		check("reload as baseline emits nothing", !d.hasChanges());
	}

	static void overflowSignalsResync() {
		System.out.println("overflow:");
		SparseChunkDiff d = newDiff(3);
		d.baseline(ck(0, 0), chunk());
		Map<Long, Object> many = new HashMap<>();
		for (int x = 0; x < 10; x++) many.put(pack(x, 0, 0), "stone");
		d.diff(ck(0, 0), many);
		check("overflow is flagged", d.overflowed());
		check("pending is capped at maxChanges", d.peek().size() == 3);
		d.drain();
		check("drain clears overflow", !d.overflowed() && !d.hasChanges());
	}

	static void sparseAirIsImplicit() {
		System.out.println("sparse air:");
		SparseChunkDiff d = newDiff(100);
		d.baseline(ck(0, 0), chunk());
		check("empty chunk stores nothing", d.trackedPositions() == 0);
		d.diff(ck(0, 0), chunk(0, 0, 0, "bedrock"));
		List<long[]> c = d.drain();
		check("one block in an empty chunk = one change", c.size() == 1 && c.get(0)[4] == ("bedrock".hashCode() & 0xFF));
		// Block removed again -> reported as removed, and nothing is stored.
		d.diff(ck(0, 0), chunk());
		List<long[]> c2 = d.drain();
		check("removing it reports a removal", c2.size() == 1 && c2.get(0)[3] == 0);
		check("chunk is empty again", d.trackedPositions() == 0);
	}
}
