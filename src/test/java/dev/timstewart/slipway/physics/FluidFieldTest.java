package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

class FluidFieldTest {
	/** Fills a box of blocks (inclusive) with source fluid. */
	static void fill(FluidField field, int x0, int y0, int z0, int x1, int y1, int z1, boolean lava) {
		for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
			for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
				for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
					byte[] cells = new byte[4096];
					boolean any = false;
					for (int y = Math.max(y0, sy << 4); y <= Math.min(y1, (sy << 4) + 15); y++) {
						for (int z = Math.max(z0, sz << 4); z <= Math.min(z1, (sz << 4) + 15); z++) {
							for (int x = Math.max(x0, sx << 4); x <= Math.min(x1, (sx << 4) + 15); x++) {
								cells[FluidField.index(x & 15, y & 15, z & 15)] = FluidField.encode(8, lava);
								any = true;
							}
						}
					}
					if (any) {
						field.put(SectionPos.asLong(sx, sy, sz), cells);
					}
				}
			}
		}
	}

	@Test
	void aCubeIsUnderWaterByAsMuchAsTheSurfaceStandsAboveItsBottom() {
		FluidField field = new FluidField();
		// Water in y = 60 to 63: the surface of a source block is 8/9 of a block up, at 63.889.
		fill(field, -20, 60, -20, 20, 63, 20, false);
		double surface = 63 + 8.0 / 9.0;
		assertEquals(1.0, field.submerged(0.5, 61.5, 0.5, 1.0), 1e-9);
		assertEquals(FluidField.WATER, field.lastFluid());
		assertEquals(surface - 63.0, field.submerged(0.5, 63.5, 0.5, 1.0), 1e-9);
		assertEquals(surface - 63.4, field.submerged(0.5, 63.9, 0.5, 1.0), 1e-9);
		// The centre in the air above, the bottom still in the water.
		assertEquals(surface - 63.6, field.submerged(0.5, 64.1, 0.5, 1.0), 1e-9);
		assertEquals(FluidField.WATER, field.lastFluid());
		assertEquals(0.0, field.submerged(0.5, 64.5, 0.5, 1.0), 1e-9);
		assertEquals(FluidField.NONE, field.lastFluid());
		// Beside the pool and below it.
		assertEquals(0.0, field.submerged(30.5, 61.5, 0.5, 1.0), 1e-9);
		assertEquals(0.0, field.submerged(0.5, 58.5, 0.5, 1.0), 1e-9);
	}

	@Test
	void theFractionChangesSmoothlyAsACubeRisesThroughTheSurfaceAndAcrossSections() {
		FluidField field = new FluidField();
		// The surface lies in the first block of a section, the water below it in the section underneath.
		fill(field, 0, 40, 0, 15, 64, 15, false);
		double before = field.submerged(8.5, 61.0, 8.5, 1.0);
		assertEquals(1.0, before, 1e-9);
		for (double y = 61.0; y < 66.5; y += 0.01) {
			double now = field.submerged(8.5, y, 8.5, 1.0);
			assertTrue(now <= before + 1e-9 && before - now < 0.0101, "jump from " + before + " to " + now + " at y = " + y);
			before = now;
		}
		assertEquals(0.0, before, 1e-9);
	}

	@Test
	void aLargerCubeTakesItsSizeIntoAccount() {
		FluidField field = new FluidField();
		fill(field, 0, 60, 0, 15, 63, 15, false);
		double surface = 63 + 8.0 / 9.0;
		// A cube of four blocks with its centre at the surface is half under.
		assertEquals(0.5, field.submerged(8.5, surface, 8.5, 4.0), 1e-9);
		assertEquals((surface - 63.0) / 4.0, field.submerged(8.5, 65.0, 8.5, 4.0), 1e-9);
		assertEquals(1.0, field.submerged(8.5, 61.5, 8.5, 2.0), 1e-9);
	}

	@Test
	void shallowAndFlowingFluidHasALowerSurfaceAndLavaIsTold() {
		FluidField field = new FluidField();
		byte[] cells = new byte[4096];
		cells[FluidField.index(1, 0, 1)] = FluidField.encode(4, false);
		cells[FluidField.index(2, 0, 1)] = FluidField.encode(8, true);
		// Lava under water: two fluids in one column do not make one deep fluid.
		cells[FluidField.index(3, 0, 1)] = FluidField.encode(8, true);
		cells[FluidField.index(3, 1, 1)] = FluidField.encode(8, false);
		field.put(SectionPos.asLong(0, 0, 0), cells);
		assertEquals(4.0 / 9.0, field.submerged(1.5, 0.5, 1.5, 1.0), 1e-9);
		assertEquals(8.0 / 9.0, field.submerged(2.5, 0.5, 1.5, 1.0), 1e-9);
		assertEquals(FluidField.LAVA, field.lastFluid());
		assertEquals(8.0 / 9.0, field.submerged(3.5, 0.5, 1.5, 1.0), 1e-9);
		assertEquals(FluidField.LAVA, field.lastFluid());
		assertEquals(3100.0, FluidField.density(FluidField.LAVA));
		assertEquals(1000.0, FluidField.density(FluidField.WATER));
	}

	@Test
	void sectionsAreKnownByWhereTheyAreAndForgottenWhenRemoved() {
		FluidField field = new FluidField();
		assertTrue(field.isEmpty());
		assertFalse(field.anyIn(-100, -100, -100, 100, 100, 100));
		fill(field, 32, 60, 32, 47, 63, 47, false);
		assertEquals(1, field.sectionCount());
		assertTrue(field.anyIn(40, 50, 40, 41, 51, 41));
		assertFalse(field.anyIn(0, 50, 0, 20, 70, 20));
		assertFalse(field.anyIn(40, 64, 40, 41, 70, 41));
		// A box of very many sections is answered from the few that hold fluid.
		assertTrue(field.anyIn(-5000, -64, -5000, 5000, 320, 5000));
		assertEquals(1.0, field.submerged(40.5, 61.5, 40.5, 1.0), 1e-9);
		assertTrue(field.put(SectionPos.asLong(2, 3, 2), null), "removing a section that held fluid is a change");
		assertFalse(field.put(SectionPos.asLong(2, 3, 2), null), "removing nothing is none");
		assertEquals(0.0, field.submerged(40.5, 61.5, 40.5, 1.0), 1e-9);
		assertTrue(field.isEmpty());
	}
}
