package com.mcagent.mcp.client.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encode a scanned block volume for an AI agent.
 *
 * Formats:
 *  - {@code rle}: palette + packed run-length int32 array (base64). Compact wire format.
 *  - {@code layers}: ASCII top-down map per Y level with a legend. Best for LLM reading.
 *  - {@code json}: explicit coordinate list, air skipped. Slowest but self-describing.
 *
 * Coordinates are world-space; indices in the cell arrays are y-major, then z, then x
 * (same iteration order as the scanner). Each rle run is one int32:
 * {@code (runLength << 12) | paletteIndex}.
 */
public final class BlockCodec {

	/** Bits reserved for the palette index inside a packed rle run. */
	public static final int RLE_COUNT_SHIFT = 12;
	/** Largest palette index representable in a packed run. */
	public static final int RLE_MAX_PALETTE = 1 << RLE_COUNT_SHIFT;

	/** A scanned volume: dims + origin + per-cell palette indices + palette. */
	public static final class Volume {
		public final int originX, originY, originZ;
		public final int sizeX, sizeY, sizeZ;
		public final short[] cells;             // sizeX*sizeY*sizeZ, y-major
		public final List<String> palette;      // index -> block id
		public final int nonAir;
		public final int emptySectionsSkipped;
		public final int unloadedChunksTouched;
		public long scanNanos;

		public Volume(int ox, int oy, int oz, int sx, int sy, int sz,
					  short[] cells, List<String> palette, int nonAir,
					  int emptySections, int unloadedChunks) {
			this.originX = ox; this.originY = oy; this.originZ = oz;
			this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz;
			this.cells = cells; this.palette = palette; this.nonAir = nonAir;
			this.emptySectionsSkipped = emptySections;
			this.unloadedChunksTouched = unloadedChunks;
		}

		public int cellCount() {
			return sizeX * sizeY * sizeZ;
		}
	}

	public static JsonObject toRle(Volume v) {
		JsonObject o = meta(v);
		o.addProperty("format", "rle");
		JsonArray pal = new JsonArray();
		for (String p : v.palette) pal.add(p);
		o.add("palette", pal);

		// One int32 per run: (runLength << 12) | paletteIndex. Halves the wire size
		// compared to emitting [index, length] pairs, and stays trivial to decode.
		// paletteIndex fits in 12 bits, so a volume may use up to 4096 distinct blocks.
		List<Integer> runs = new ArrayList<>();
		int i = 0;
		int n = v.cells.length;
		while (i < n) {
			int id = v.cells[i] & 0xFFFF;
			if (id >= RLE_MAX_PALETTE) {
				throw new IllegalArgumentException("palette index " + id
						+ " exceeds the rle limit of " + RLE_MAX_PALETTE + " distinct blocks");
			}
			int j = i + 1;
			while (j < n && (v.cells[j] & 0xFFFF) == id) j++;
			runs.add(((j - i) << RLE_COUNT_SHIFT) | id);
			i = j;
		}
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(runs.size() * 4);
		for (int r : runs) buf.putInt(r);
		o.addProperty("encoding", "int32-packed-runs");
		o.addProperty("runFormat", "(runLength << " + RLE_COUNT_SHIFT + ") | paletteIndex");
		o.addProperty("data", Base64.getEncoder().encodeToString(buf.array()));
		o.addProperty("runCount", runs.size());
		return o;
	}

	public static JsonObject toJson(Volume v, boolean skipAir) {
		JsonObject o = meta(v);
		o.addProperty("format", "json");
		JsonArray cells = new JsonArray();
		int air = airIndex(v);
		for (int y = 0; y < v.sizeY; y++) {
			for (int z = 0; z < v.sizeZ; z++) {
				for (int x = 0; x < v.sizeX; x++) {
					short idx = v.cells[(y * v.sizeZ + z) * v.sizeX + x];
					if (skipAir && (idx & 0xFFFF) == air) continue;
					JsonObject c = new JsonObject();
					c.addProperty("x", v.originX + x);
					c.addProperty("y", v.originY + y);
					c.addProperty("z", v.originZ + z);
					c.addProperty("b", v.palette.get(idx & 0xFFFF));
					cells.add(c);
				}
			}
		}
		o.add("cells", cells);
		o.addProperty("cellCount", cells.size());
		return o;
	}

	private static final String LAYER_CHARS = " #@%&*+=~^!?"; // ' ' == air

	public static JsonObject toLayers(Volume v) {
		JsonObject o = meta(v);
		o.addProperty("format", "layers");
		JsonObject legend = new JsonObject();
		int air = airIndex(v);
		for (int i = 0; i < v.palette.size(); i++) {
			String ch = (i == air) ? " " : String.valueOf(LAYER_CHARS.charAt(1 + (i % (LAYER_CHARS.length() - 1))));
			legend.addProperty(ch, v.palette.get(i));
		}
		o.add("legend", legend);
		JsonArray layers = new JsonArray();
		for (int y = v.sizeY - 1; y >= 0; y--) {
			StringBuilder sb = new StringBuilder();
			for (int z = 0; z < v.sizeZ; z++) {
				for (int x = 0; x < v.sizeX; x++) {
					short idx = v.cells[(y * v.sizeZ + z) * v.sizeX + x];
					int i = idx & 0xFFFF;
					sb.append(i == air ? ' ' : LAYER_CHARS.charAt(1 + (i % (LAYER_CHARS.length() - 1))));
				}
				if (z < v.sizeZ - 1) sb.append('\n');
			}
			layers.add(sb.toString());
		}
		o.add("layers", layers); // index 0 = top Y layer, rows = z, cols = x
		return o;
	}

	private static JsonObject meta(Volume v) {
		JsonObject o = new JsonObject();
		JsonArray origin = new JsonArray();
		origin.add(v.originX); origin.add(v.originY); origin.add(v.originZ);
		JsonArray size = new JsonArray();
		size.add(v.sizeX); size.add(v.sizeY); size.add(v.sizeZ);
		o.add("origin", origin);
		o.add("size", size);
		o.addProperty("air", v.palette.isEmpty() ? -1 : airIndex(v));
		JsonObject stats = new JsonObject();
		stats.addProperty("cells", v.cellCount());
		stats.addProperty("nonAir", v.nonAir);
		stats.addProperty("ms", v.scanNanos / 1_000_000.0);
		stats.addProperty("emptySectionsSkipped", v.emptySectionsSkipped);
		stats.addProperty("unloadedChunks", v.unloadedChunksTouched);
		o.add("stats", stats);
		return o;
	}

	private static int airIndex(Volume v) {
		for (int i = 0; i < v.palette.size(); i++) {
			if (v.palette.get(i).equals("minecraft:air")) return i;
		}
		return -1;
	}

	/** Fast id <-> index map used by scan filters. */
	public static final class Palette {
		private final Map<String, Short> ids = new HashMap<>();
		private final List<String> names = new ArrayList<>();

		public short idFor(String name) {
			Short s = ids.get(name);
			if (s != null) return s;
			short id = (short) names.size();
			names.add(name);
			ids.put(name, id);
			return id;
		}

		public List<String> names() {
			return names;
		}
	}
}
