package dev.timstewart.slipway.vessel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class StructureScanTest {
	/** A sparse grid: listed positions are vessel blocks; y outside [0, 100) is outside the level. */
	private static StructureScan.Grid grid(Set<Long> blocks, Set<Long> unloaded) {
		return (x, y, z) -> {
			long key = BlockPos.asLong(x, y, z);
			if (unloaded.contains(key)) {
				return StructureScan.Cell.UNLOADED;
			}
			if (y < 0 || y >= 100) {
				return StructureScan.Cell.REJECT;
			}
			return blocks.contains(key) ? StructureScan.Cell.ACCEPT : StructureScan.Cell.REJECT;
		};
	}

	private static Set<Long> box(int x0, int y0, int z0, int x1, int y1, int z1) {
		Set<Long> set = new HashSet<>();
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					set.add(BlockPos.asLong(x, y, z));
				}
			}
		}
		return set;
	}

	@Test
	void collectsExactlyTheFaceConnectedStructure() {
		Set<Long> blocks = box(0, 10, 0, 4, 10, 2);
		blocks.add(BlockPos.asLong(2, 11, 1));
		// Touching only by an edge: not connected.
		blocks.add(BlockPos.asLong(5, 11, 0));
		// Separate structure.
		blocks.addAll(box(20, 10, 20, 21, 10, 21));
		StructureScan.Result result = StructureScan.scan(new BlockPos(0, 10, 0), grid(blocks, Set.of()), 4096, 512);
		assertTrue(result.ok());
		assertEquals(16, result.size());
		assertEquals(new BlockPos(0, 10, 0), result.min());
		assertEquals(new BlockPos(4, 11, 2), result.max());
	}

	@Test
	void refusesMoreBlocksThanTheCap() {
		Set<Long> blocks = box(0, 0, 0, 9, 9, 9);
		StructureScan.Result result = StructureScan.scan(new BlockPos(0, 0, 0), grid(blocks, Set.of()), 999, 512);
		assertEquals(StructureScan.Failure.TOO_MANY_BLOCKS, result.failure());
		assertEquals(0, result.size());
		assertTrue(StructureScan.scan(new BlockPos(0, 0, 0), grid(blocks, Set.of()), 1000, 512).ok());
	}

	@Test
	void refusesStructuresWiderThanTheSpan() {
		Set<Long> line = box(0, 5, 0, 99, 5, 0);
		assertEquals(StructureScan.Failure.TOO_LARGE, StructureScan.scan(new BlockPos(0, 5, 0), grid(line, Set.of()), 4096, 64).failure());
		assertTrue(StructureScan.scan(new BlockPos(0, 5, 0), grid(line, Set.of()), 4096, 100).ok());
	}

	@Test
	void refusesStructuresTouchingUnloadedChunks() {
		Set<Long> blocks = box(0, 5, 0, 3, 5, 0);
		StructureScan.Result result = StructureScan.scan(new BlockPos(0, 5, 0), grid(blocks, Set.of(BlockPos.asLong(4, 5, 0))), 4096, 512);
		assertEquals(StructureScan.Failure.UNLOADED, result.failure());
	}

	@Test
	void nothingToAssembleWhenTheStartIsNotAVesselBlock() {
		assertEquals(StructureScan.Failure.NOTHING, StructureScan.scan(new BlockPos(0, 5, 0), grid(Set.of(), Set.of()), 4096, 512).failure());
	}

	@Test
	void theLevelHeightBoundsTheFill() {
		Set<Long> column = box(0, 95, 0, 0, 105, 0);
		StructureScan.Result result = StructureScan.scan(new BlockPos(0, 95, 0), grid(column, Set.of()), 4096, 512);
		assertTrue(result.ok());
		assertEquals(5, result.size());
	}
}
