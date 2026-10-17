package com.mcagent.mcp.client.blocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Section-level block scanning. Reads chunk sections directly (skipping empty
 * sections and short-circuiting per-section air), which is dramatically faster
 * than calling {@code level.getBlockState} per block. Must run on the game thread.
 */
public final class BlockScanner {

	public static final int MAX_CELLS = 96 * 96 * 96; // safety cap (~884k cells)

	/** Scan the axis-aligned box [minX..maxX) x [minY..maxY) x [minZ..maxZ). */
	public BlockCodec.Volume scan(ClientLevel level, int minX, int minY, int minZ,
								  int maxX, int maxY, int maxZ) {
		long start = System.nanoTime();
		int sx = maxX - minX, sy = maxY - minY, sz = maxZ - minZ;
		if (sx <= 0 || sy <= 0 || sz <= 0) {
			throw new IllegalArgumentException("empty volume");
		}
		if ((long) sx * sy * sz > MAX_CELLS) {
			throw new IllegalArgumentException("volume too large: " + sx + "x" + sy + "x" + sz
					+ " (" + (long) sx * sy * sz + " cells), cap is " + MAX_CELLS);
		}

		short[] cells = new short[sx * sy * sz];
		BlockCodec.Palette palette = new BlockCodec.Palette();
		short airId = palette.idFor("minecraft:air");

		int nonAir = 0;
		int emptySections = 0;
		int unloadedChunks = 0;

		int minYc = level.getMinBuildHeight();
		int maxYc = level.getMaxBuildHeight();

		for (int cz = minZ >> 4; cz <= (maxZ - 1) >> 4; cz++) {
			for (int cx = minX >> 4; cx <= (maxX - 1) >> 4; cx++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				int chunkBaseX = cx << 4;
				int chunkBaseZ = cz << 4;

				for (int syIdx = 0; syIdx < sections.length; syIdx++) {
					LevelChunkSection section = sections[syIdx];
					int secBaseY = level.getMinBuildHeight() + syIdx * 16;
					// Intersect section with requested box on Y.
					int y0 = Math.max(minY, secBaseY);
					int y1 = Math.min(maxY, secBaseY + 16);
					if (y0 >= y1) continue;
					// Intersect on X/Z with this chunk.
					int x0 = Math.max(minX, chunkBaseX);
					int x1 = Math.min(maxX, chunkBaseX + 16);
					int z0 = Math.max(minZ, chunkBaseZ);
					int z1 = Math.min(maxZ, chunkBaseZ + 16);
					if (x0 >= x1 || z0 >= z1) continue;

					if (section == null || section.hasOnlyAir()) {
						emptySections++;
						continue;
					}

					for (int y = y0; y < y1; y++) {
						int ly = y & 15;
						int rowBase = ((y - minY) * sz) * sx;
						for (int z = z0; z < z1; z++) {
							int idxBase = rowBase + (z - minZ) * sx;
							int lz = z & 15;
							for (int x = x0; x < x1; x++) {
								BlockState state = section.getBlockState(x & 15, ly, lz);
								String name = idOf(state);
								short id = palette.idFor(name);
								cells[idxBase + (x - minX)] = id;
								if (id != airId) nonAir++;
							}
						}
					}
				}
			}
		}

		BlockCodec.Volume volume = new BlockCodec.Volume(
				minX, minY, minZ, sx, sy, sz, cells, new ArrayList<>(palette.names()),
				nonAir, emptySections, unloadedChunks);
		volume.scanNanos = System.nanoTime() - start;
		return volume;
	}

	/**
	 * Find blocks whose id is in {@code wanted} within a cube centered at the player.
	 * Returns up to {@code maxPerName} positions per block name. Empty sections skipped.
	 */
	public List<MatchGroup> scanFor(ClientLevel level, BlockPos center, int radius,
									java.util.Set<String> wanted, int maxPerName) {
		long start = System.nanoTime();
		Map<String, MatchGroup> groups = new HashMap<>();
		int minX = center.getX() - radius, maxX = center.getX() + radius + 1;
		int minY = Math.max(center.getY() - radius, level.getMinBuildHeight());
		int maxY = Math.min(center.getY() + radius + 1, level.getMaxBuildHeight());
		int minZ = center.getZ() - radius, maxZ = center.getZ() + radius + 1;

		for (int cz = minZ >> 4; cz <= (maxZ - 1) >> 4; cz++) {
			for (int cx = minX >> 4; cx <= (maxX - 1) >> 4; cx++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				int chunkBaseX = cx << 4, chunkBaseZ = cz << 4;

				for (int syIdx = 0; syIdx < sections.length; syIdx++) {
					LevelChunkSection section = sections[syIdx];
					if (section == null || section.hasOnlyAir()) continue;
					int secBaseY = level.getMinBuildHeight() + syIdx * 16;
					int y0 = Math.max(minY, secBaseY), y1 = Math.min(maxY, secBaseY + 16);
					if (y0 >= y1) continue;
					int x0 = Math.max(minX, chunkBaseX), x1 = Math.min(maxX, chunkBaseX + 16);
					int z0 = Math.max(minZ, chunkBaseZ), z1 = Math.min(maxZ, chunkBaseZ + 16);
					if (x0 >= x1 || z0 >= z1) continue;

					for (int y = y0; y < y1; y++) {
						for (int z = z0; z < z1; z++) {
							for (int x = x0; x < x1; x++) {
								String name = idOf(section.getBlockState(x & 15, y & 15, z & 15));
								if (!wanted.contains(name)) continue;
								MatchGroup g = groups.computeIfAbsent(name, MatchGroup::new);
								if (g.positions.size() < maxPerName) {
									g.positions.add(new int[]{x, y, z});
								} else {
									g.truncated = true;
								}
							}
						}
					}
				}
			}
		}

		List<MatchGroup> out = new ArrayList<>(groups.values());
		out.forEach(g -> g.scanNanos = start);
		return out;
	}

	public static String idOf(BlockState state) {
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id == null ? "minecraft:air" : id.toString();
	}

	public static final class MatchGroup {
		public final String name;
		public final List<int[]> positions = new ArrayList<>();
		public boolean truncated;
		public long scanNanos;

		MatchGroup(String name) {
			this.name = name;
		}
	}
}
