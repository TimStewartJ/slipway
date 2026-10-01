package dev.timstewart.slipway.vessel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The reserved region of every level where vessel blocks live. Each vessel owns one plot: a
 * {@value #PLOT_SIZE}x{@value #PLOT_SIZE} column of real chunks, far away from anything a player normally visits.
 * Plots start at x = z = {@value #REGION_MIN} (beyond a 1:1 Earth's latitude range) and are laid out
 * {@value #PLOTS_PER_ROW} to a row, so the region ends before 28.2 million, inside the vanilla world border.
 * A vessel's anchor is the centre column of its plot at the Y its helm had when it was assembled, so the
 * structure always fits the level's height.
 */
public final class VesselRegion {
	public static final int REGION_MIN = 24_000_000;
	public static final int PLOT_SIZE = 4096;
	public static final int PLOTS_PER_ROW = 1024;
	public static final int REGION_MAX = REGION_MIN + PLOTS_PER_ROW * PLOT_SIZE;
	public static final int MAX_PLOTS = PLOTS_PER_ROW * PLOTS_PER_ROW;
	/** Blocks kept free between a vessel and its plot's edge. */
	public static final int PLOT_MARGIN = 32;
	public static final int CHUNK_MIN = REGION_MIN >> 4;
	public static final int CHUNK_MAX = REGION_MAX >> 4;

	private VesselRegion() {
	}

	public static boolean isReserved(int blockX, int blockZ) {
		return blockX >= REGION_MIN && blockX < REGION_MAX && blockZ >= REGION_MIN && blockZ < REGION_MAX;
	}

	public static boolean isReserved(double x, double z) {
		return x >= REGION_MIN && x < REGION_MAX && z >= REGION_MIN && z < REGION_MAX;
	}

	public static boolean isReserved(BlockPos pos) {
		return isReserved(pos.getX(), pos.getZ());
	}

	public static boolean isReservedChunk(int chunkX, int chunkZ) {
		return chunkX >= CHUNK_MIN && chunkX < CHUNK_MAX && chunkZ >= CHUNK_MIN && chunkZ < CHUNK_MAX;
	}

	public static boolean isReservedChunk(ChunkPos pos) {
		return isReservedChunk(pos.x(), pos.z());
	}

	public static boolean isReservedChunk(long packedChunkPos) {
		return isReservedChunk(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos));
	}

	/** The plot containing a reserved block column, or -1. */
	public static int plotAt(int blockX, int blockZ) {
		if (!isReserved(blockX, blockZ)) {
			return -1;
		}
		int column = (blockX - REGION_MIN) / PLOT_SIZE;
		int row = (blockZ - REGION_MIN) / PLOT_SIZE;
		return row * PLOTS_PER_ROW + column;
	}

	public static int plotAtChunk(int chunkX, int chunkZ) {
		return plotAt(chunkX << 4, chunkZ << 4);
	}

	public static int plotMinX(int plot) {
		checkPlot(plot);
		return REGION_MIN + (plot % PLOTS_PER_ROW) * PLOT_SIZE;
	}

	public static int plotMinZ(int plot) {
		checkPlot(plot);
		return REGION_MIN + (plot / PLOTS_PER_ROW) * PLOT_SIZE;
	}

	/** The anchor column of a plot: its centre. */
	public static BlockPos anchor(int plot, int y) {
		return new BlockPos(plotMinX(plot) + PLOT_SIZE / 2, y, plotMinZ(plot) + PLOT_SIZE / 2);
	}

	/** Whether a local offset from the anchor stays inside the plot with its margin. */
	public static boolean fitsInPlot(int localX, int localZ) {
		int half = PLOT_SIZE / 2 - PLOT_MARGIN;
		return localX >= -half && localX < half && localZ >= -half && localZ < half;
	}

	/**
	 * Whether a block column lies in the usable part of a plot: inside the margin that keeps vessels of neighbouring
	 * plots apart. Blocks of a vessel must never leave it.
	 */
	public static boolean isUsable(int blockX, int blockZ) {
		int plot = plotAt(blockX, blockZ);
		return plot >= 0 && fitsInPlot(blockX - plotMinX(plot) - PLOT_SIZE / 2, blockZ - plotMinZ(plot) - PLOT_SIZE / 2);
	}

	public static boolean isUsable(BlockPos pos) {
		return isUsable(pos.getX(), pos.getZ());
	}

	private static void checkPlot(int plot) {
		if (plot < 0 || plot >= MAX_PLOTS) {
			throw new IllegalArgumentException("plot " + plot + " outside 0.." + (MAX_PLOTS - 1));
		}
	}
}
