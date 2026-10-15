import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.blocks.BlockCodec;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** BlockCodec round-trip and format checks (pure Java, no Minecraft runtime needed). */
public class CodecTest {
	static int failures = 0;

	public static void main(String[] args) {
		int sx = 8, sy = 4, sz = 8;
		// y-major layout, matching BlockScanner: index = ((y * sz) + z) * sx + x
		short[] cells = new short[sx * sy * sz];
		List<String> palette = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt");
		int expectedNonAir = 0;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					short id = 0;
					if (y < 2) id = 1;                          // two full stone layers
					else if (y == 2 && (x == 3 || z == 3)) id = 2; // dirt cross
					cells[(y * sz + z) * sx + x] = id;
					if (id != 0) expectedNonAir++;
				}
			}
		}
		BlockCodec.Volume v = new BlockCodec.Volume(
				100, 64, -32, sx, sy, sz, cells, palette, expectedNonAir, 3, 0);

		// --- RLE: palette + run pairs, base64 ---
		JsonObject rle = BlockCodec.toRle(v);
		check("rle reports the palette", rle.getAsJsonArray("palette").size() == 3);
		byte[] raw = Base64.getDecoder().decode(rle.get("data").getAsString());
		IntBuffer ib = ByteBuffer.wrap(raw).asIntBuffer();
		short[] decoded = new short[cells.length];
		int i = 0;
		while (ib.hasRemaining()) {
			int packed = ib.get();
			int id = packed & 0xFFF;          // low 12 bits: palette index
			int run = packed >>> 12;          // high 20 bits: run length
			for (int k = 0; k < run; k++) decoded[i++] = (short) id;
		}
		check("rle runs fill the whole volume", i == cells.length);
		check("rle round-trips exactly", Arrays.equals(cells, decoded));
		check("rle is one int32 per run", rle.get("runCount").getAsInt() == ib.capacity());
		check("rle decode consumed every int", i == cells.length);
		check("rle documents its run format", rle.has("runFormat")
				&& rle.get("encoding").getAsString().equals("int32-packed-runs"));
		check("rle uses at most 4 bytes per run", raw.length == rle.get("runCount").getAsInt() * 4);

		// --- layers: one ASCII map per Y, legend maps chars to blocks ---
		JsonObject layers = BlockCodec.toLayers(v);
		JsonArray arr = layers.getAsJsonArray("layers");
		check("layers emits one map per Y level", arr.size() == sy);
		JsonArray ys = layers.getAsJsonArray("layerY");
		check("layerY labels every slice, highest first", ys.size() == sy
				&& ys.get(0).getAsInt() == v.originY + sy - 1 && ys.get(sy - 1).getAsInt() == v.originY);
		check("howToRead explains the axes", layers.get("howToRead").getAsString().contains("highest first"));
		check("top layer (all air) is blank",
				arr.get(0).getAsString().chars().allMatch(c -> c == ' ' || c == '\n'));
		String dirtLayer = arr.get(1).getAsString();
		String[] rows = dirtLayer.split("\n");
		check("dirt cross spans the full width on row z=3",
				rows.length == sz && rows[3].chars().allMatch(c -> !Character.isWhitespace(c)));
		check("layers legend resolves dirt", layers.getAsJsonObject("legend").toString().contains("minecraft:dirt"));

		// --- json: explicit cell list, skipAir drops the empty cells ---
		JsonObject jsonSkip = BlockCodec.toJson(v, true);
		check("json skipAir cell count = non-air blocks",
				jsonSkip.get("cellCount").getAsInt() == expectedNonAir);
		JsonObject jsonAll = BlockCodec.toJson(v, false);
		check("json without skipAir covers every cell",
				jsonAll.get("cellCount").getAsInt() == sx * sy * sz);

		// --- stats ---
		JsonObject stats = rle.getAsJsonObject("stats");
		check("stats report the volume size", stats.get("cells").getAsInt() == cells.length);
		check("stats report non-air count", stats.get("nonAir").getAsInt() == expectedNonAir);

		System.out.println(failures == 0 ? "CODEC: PASS" : "CODEC: FAIL (" + failures + ")");
		if (failures != 0) System.exit(1);
	}

	static void check(String what, boolean ok) {
		System.out.println((ok ? "  ok   " : "  FAIL ") + what);
		if (!ok) failures++;
	}

	static List<String> list(Object... items) {
		List<String> l = new ArrayList<>();
		for (Object o : items) l.add((String) o);
		return l;
	}
}
