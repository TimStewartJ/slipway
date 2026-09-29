package dev.timstewart.slipway.vessel;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * Bounded flood fill over face-connected blocks, independent of any level so it can be unit tested. The search
 * accepts at most {@code maxBlocks} positions and stops as soon as a limit is exceeded, so its cost is bounded no
 * matter how large the connected structure is.
 */
public final class StructureScan {
	private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	private StructureScan() {
	}

	public enum Cell {
		/** A block that belongs in the vessel. */
		ACCEPT,
		/** Air, a denied block, or outside the level's height: not part of the vessel. */
		REJECT,
		/** In an unloaded chunk: the structure cannot be known, so the scan fails. */
		UNLOADED
	}

	/** What the scan asks of the world. */
	@FunctionalInterface
	public interface Grid {
		Cell classify(int x, int y, int z);
	}

	public enum Failure {
		NONE,
		/** More connected blocks than the cap allows. */
		TOO_MANY_BLOCKS,
		/** The connected blocks span more than the allowed extent on some axis. */
		TOO_LARGE,
		/** The structure touches an unloaded chunk. */
		UNLOADED,
		/** The start position itself is not a vessel block. */
		NOTHING
	}

	public record Result(Failure failure, long[] positions, BlockPos min, BlockPos max) {
		public boolean ok() {
			return this.failure == Failure.NONE;
		}

		public int size() {
			return this.positions.length;
		}
	}

	public static Result scan(BlockPos start, Grid grid, int maxBlocks, int maxSpan) {
		Cell first = grid.classify(start.getX(), start.getY(), start.getZ());
		if (first != Cell.ACCEPT) {
			return new Result(first == Cell.UNLOADED ? Failure.UNLOADED : Failure.NOTHING, new long[0], start, start);
		}
		LongOpenHashSet seen = new LongOpenHashSet();
		LongArrayList accepted = new LongArrayList();
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		long startKey = start.asLong();
		seen.add(startKey);
		queue.enqueue(startKey);
		int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
		int maxX = minX, maxY = minY, maxZ = minZ;
		while (!queue.isEmpty()) {
			long key = queue.dequeueLong();
			accepted.add(key);
			if (accepted.size() > maxBlocks) {
				return new Result(Failure.TOO_MANY_BLOCKS, new long[0], new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
			}
			int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
			minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
			if (maxX - minX >= maxSpan || maxY - minY >= maxSpan || maxZ - minZ >= maxSpan) {
				return new Result(Failure.TOO_LARGE, new long[0], new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
			}
			for (int[] d : NEIGHBOURS) {
				int nx = x + d[0], ny = y + d[1], nz = z + d[2];
				long nKey = BlockPos.asLong(nx, ny, nz);
				if (!seen.add(nKey)) {
					continue;
				}
				switch (grid.classify(nx, ny, nz)) {
					case ACCEPT -> queue.enqueue(nKey);
					case UNLOADED -> {
						return new Result(Failure.UNLOADED, new long[0], new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
					}
					case REJECT -> {
					}
				}
			}
		}
		return new Result(Failure.NONE, accepted.toLongArray(), new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}
}
