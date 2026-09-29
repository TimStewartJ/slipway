package dev.timstewart.slipway.vessel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class VesselRegionTest {
	@Test
	void plotsTileTheRegionAndMapBack() {
		for (int plot : new int[] {0, 1, 1023, 1024, 5000, VesselRegion.MAX_PLOTS - 1}) {
			int minX = VesselRegion.plotMinX(plot);
			int minZ = VesselRegion.plotMinZ(plot);
			assertEquals(plot, VesselRegion.plotAt(minX, minZ));
			assertEquals(plot, VesselRegion.plotAt(minX + VesselRegion.PLOT_SIZE - 1, minZ + VesselRegion.PLOT_SIZE - 1));
			BlockPos anchor = VesselRegion.anchor(plot, 70);
			assertEquals(plot, VesselRegion.plotAt(anchor.getX(), anchor.getZ()));
			assertEquals(70, anchor.getY());
			assertTrue(VesselRegion.isReserved(anchor));
		}
	}

	@Test
	void regionStaysInsideTheWorldBorderAndAwayFromEarth() {
		assertTrue(VesselRegion.REGION_MAX < 29_999_984, "inside the vanilla world border");
		assertTrue(VesselRegion.REGION_MIN > 20_037_509, "beyond a 1:1 Earth's x range");
		assertFalse(VesselRegion.isReserved(0, 0));
		assertFalse(VesselRegion.isReserved(VesselRegion.REGION_MIN - 1, VesselRegion.REGION_MIN));
		assertFalse(VesselRegion.isReserved(VesselRegion.REGION_MIN, VesselRegion.REGION_MIN - 1));
		assertTrue(VesselRegion.isReserved(VesselRegion.REGION_MIN, VesselRegion.REGION_MIN));
		assertFalse(VesselRegion.isReserved(VesselRegion.REGION_MAX, VesselRegion.REGION_MIN));
		assertEquals(-1, VesselRegion.plotAt(100, 100));
		assertTrue(VesselRegion.isReservedChunk(VesselRegion.CHUNK_MIN, VesselRegion.CHUNK_MIN));
		assertFalse(VesselRegion.isReservedChunk(VesselRegion.CHUNK_MIN - 1, VesselRegion.CHUNK_MIN));
	}

	@Test
	void plotMarginIsEnforced() {
		int half = VesselRegion.PLOT_SIZE / 2 - VesselRegion.PLOT_MARGIN;
		assertTrue(VesselRegion.fitsInPlot(half - 1, -half));
		assertFalse(VesselRegion.fitsInPlot(half, 0));
		assertFalse(VesselRegion.fitsInPlot(0, -half - 1));
	}

	@Test
	void invalidPlotsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> VesselRegion.plotMinX(-1));
		assertThrows(IllegalArgumentException.class, () -> VesselRegion.plotMinZ(VesselRegion.MAX_PLOTS));
	}
}
