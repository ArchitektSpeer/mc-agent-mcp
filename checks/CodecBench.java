import com.google.gson.JsonObject;
import com.mcagent.mcp.client.blocks.BlockCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reports real encoded sizes/timings for a terrain-like volume, so the README's
 * compression claims are measured rather than guessed. Informational only.
 */
public class CodecBench {
	public static void main(String[] args) {
		short[] cells = new short[side * side * side];
		List<String> palette = List.of(
				"minecraft:air", "minecraft:stone", "minecraft:dirt",
				"minecraft:grass_block", "minecraft:water", "minecraft:oak_log", "minecraft:oak_leaves");

		// Fake terrain: rolling ground, dirt layer, a few ores, some water pockets and trees.
		Random rnd = new Random(42);
		int nonAir = 0;
		for (int z = 0; z < side; z++) {
			for (int x = 0; x < side; x++) {
				int ground = 20 + (int) (6 * Math.sin(x / 7.0) + 5 * Math.cos(z / 9.0)) + rnd.nextInt(3);
				for (int y = 0; y < side; y++) {
					short id = 0;
					if (y < ground - 4) id = 1;                 // stone
					else if (y < ground) id = 2;                // dirt
					else if (y == ground) id = 3;               // grass
					if (y > ground && y <= 22 && rnd.nextInt(100) < 6) id = 4; // scattered water
					if (id == 1 && rnd.nextInt(2000) < 3) id = 5;             // sparse ore-ish logs
					if (x % 11 == 3 && z % 13 == 5 && y > ground && y < ground + 5) id = 5;   // trunk
					if (x % 11 == 3 && z % 13 == 5 && y >= ground + 5 && y < ground + 8) {
						for (int dx = -2; dx <= 2; dx++) {
							for (int dz = -2; dz <= 2; dz++) {
								int nx = x + dx, nz = z + dz;
								if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
								for (int dy = 0; dy < 2; dy++) {
									int ny = y + dy;
									if (ny >= side) continue;
									cells[(ny * side + nz) * side + nx] = 6;
								}
							}
						}
					}
					if (id != 0) cells[(y * side + z) * side + x] = id;
				}
			}
		}
		for (short c : cells) if (c != 0) nonAir++;

		BlockCodec.Volume v = new BlockCodec.Volume(0, 64, 0, side, side, side, cells,
				new ArrayList<>(palette), nonAir, 0, 0);

		int rawBytes = side * side * side * 2; // 16-bit palette index per cell
		bench("rle", rawBytes, () -> BlockCodec.toRle(v));
		bench("layers", rawBytes, () -> BlockCodec.toLayers(v));
		bench("json (skipAir)", rawBytes, () -> BlockCodec.toJson(v, true));

		JsonObject layers = BlockCodec.toLayers(v);
		System.out.println("legend chars: " + layers.get("legend"));
	}

	static int side = 48;

	static void bench(String name, int rawBytes, java.util.function.Supplier<JsonObject> fn) {
		// Warm up, then time the best of a few runs.
		for (int i = 0; i < 3; i++) fn.get();
		long best = Long.MAX_VALUE;
		JsonObject out = null;
		for (int i = 0; i < 5; i++) {
			long t0 = System.nanoTime();
			out = fn.get();
			best = Math.min(best, System.nanoTime() - t0);
		}
		int bytes = out.toString().length();
		System.out.printf("%-15s json=%8d B  raw=%8d B  ratio=%5.1fx  %.2f B/cell  encode=%6.2f ms%n",
				name, bytes, rawBytes, rawBytes / (double) bytes,
				bytes / (double) (side * side * side), best / 1e6);
	}
}
