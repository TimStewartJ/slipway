package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HullTest {
	/** An open box of full blocks: {@code sx} by {@code sz} outside, a floor at y = 0 and walls up to y = sy - 1. */
	static Hull.Builder openBox(int sx, int sy, int sz) {
		Hull.Builder b = new Hull.Builder();
		for (int x = 0; x < sx; x++) {
			for (int z = 0; z < sz; z++) {
				b.block(x, 0, z, 1f, true);
				if (x == 0 || z == 0 || x == sx - 1 || z == sz - 1) {
					for (int y = 1; y < sy; y++) {
						b.block(x, y, z, 1f, true);
					}
				}
			}
		}
		return b;
	}

	private static double elementVolume(Hull hull, boolean sheltered) {
		double sum = 0;
		for (int i = 0; i < hull.elementCount(); i++) {
			if (hull.elementPour(i) >= 0 == sheltered) {
				sum += hull.elementVolume(i);
			}
		}
		return sum;
	}

	@Test
	void aRaftDisplacesOnlyItsOwnBlocks() {
		Hull.Builder b = new Hull.Builder();
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) {
				b.block(x, 0, z, 1f, true);
			}
		}
		Hull hull = b.build();
		assertEquals(9, hull.elementCount());
		assertEquals(9.0, hull.blockVolume(), 1e-6);
		assertEquals(0.0, hull.shelteredVolume(), 1e-6);
		assertEquals(0.0, hull.sealedVolume(), 1e-6);
		assertEquals(9.0, hull.capacity(), 1e-6);
		assertEquals(0, hull.pourCount());
		assertEquals(Hull.WATERTIGHT, hull.cellClass(1, 0, 1));
		assertEquals(Hull.OPEN, hull.cellClass(1, 1, 1));
		assertTrue(hull.hasCavities());
	}

	@Test
	void theAirInAnOpenHullIsShelteredUpToItsRim() {
		// 5 x 5 outside, floor and two rows of wall: 3 x 3 x 2 cells of air inside.
		Hull hull = openBox(5, 3, 5).build();
		assertEquals(57.0, hull.blockVolume(), 1e-6);
		assertEquals(18.0, hull.shelteredVolume(), 1e-6);
		assertEquals(0.0, hull.sealedVolume(), 1e-6);
		assertEquals(75.0, hull.capacity(), 1e-6);
		assertEquals(57.0, elementVolume(hull, false), 1e-6);
		assertEquals(18.0, elementVolume(hull, true), 1e-6);
		// Every column of air pours in from the cell above it at the height of the rim: the first cell above the walls.
		assertEquals(9, hull.pourCount());
		for (int x = 1; x <= 3; x++) {
			for (int z = 1; z <= 3; z++) {
				for (int y = 1; y <= 2; y++) {
					int p = hull.cellClass(x, y, z);
					assertTrue(p >= 0, "the air at " + x + "," + y + "," + z + " is not sheltered: " + p);
					assertEquals(x + 0.5f, hull.pourX(p));
					assertEquals(3.5f, hull.pourY(p));
					assertEquals(z + 0.5f, hull.pourZ(p));
				}
			}
		}
		assertEquals(Hull.WATERTIGHT, hull.cellClass(0, 2, 2));
		assertEquals(Hull.OPEN, hull.cellClass(2, 3, 2));
		assertEquals(Hull.OPEN, hull.cellClass(-1, 1, 2));
	}

	@Test
	void aHoleLetsTheWaterInAtItsHeight() {
		// The same hull with a block missing in the upper row of its wall: the water runs in there, one block lower.
		Hull.Builder upper = new Hull.Builder();
		Hull.Builder lower = new Hull.Builder();
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				upper.block(x, 0, z, 1f, true);
				lower.block(x, 0, z, 1f, true);
				if (x == 0 || z == 0 || x == 4 || z == 4) {
					for (int y = 1; y < 3; y++) {
						boolean hole = x == 0 && z == 2;
						if (!(hole && y == 2)) {
							upper.block(x, y, z, 1f, true);
						}
						if (!(hole && y == 1)) {
							lower.block(x, y, z, 1f, true);
						}
					}
				}
			}
		}
		Hull holedAbove = upper.build();
		assertEquals(9.0, holedAbove.shelteredVolume(), 1e-6, "only the lower layer of air is below the hole");
		for (int p = 0; p < holedAbove.pourCount(); p++) {
			assertEquals(2.5f, holedAbove.pourY(p));
		}
		assertEquals(Hull.OPEN, holedAbove.cellClass(2, 2, 2));
		assertTrue(holedAbove.cellClass(2, 1, 2) >= 0);
		Hull holedBelow = lower.build();
		assertEquals(0.0, holedBelow.shelteredVolume(), 1e-6, "a hole at the floor shelters nothing");
		assertEquals(Hull.OPEN, holedBelow.cellClass(2, 1, 2));
	}

	@Test
	void aClosedCabinIsSealed() {
		Hull.Builder b = openBox(5, 4, 5);
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				b.block(x, 4, z, 1f, true);
			}
		}
		Hull hull = b.build();
		assertEquals(27.0, hull.sealedVolume(), 1e-6);
		assertEquals(0.0, hull.shelteredVolume(), 1e-6);
		assertEquals(125.0, hull.capacity(), 1e-6);
		assertEquals(Hull.SEALED, hull.cellClass(2, 2, 2));
		assertEquals(0, hull.pourCount());
		assertEquals(125.0, elementVolume(hull, false), 1e-6);
	}

	@Test
	void aRailingThatWaterPassesDoesNotRaiseTheRimAndAWallDoes() {
		Hull.Builder fenced = openBox(5, 3, 5);
		Hull.Builder walled = openBox(5, 3, 5);
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				if (x == 0 || z == 0 || x == 4 || z == 4) {
					fenced.block(x, 3, z, 0.14f, false);
					walled.block(x, 3, z, 0.5f, true);
				}
			}
		}
		Hull withFence = fenced.build();
		assertEquals(18.0, withFence.shelteredVolume(), 1e-6);
		assertEquals(57.0 + 16 * 0.14, withFence.blockVolume(), 1e-4);
		assertEquals(3.5f, withFence.pourY(0));
		assertEquals(Hull.OPEN, withFence.cellClass(0, 3, 0), "a fence post is open to the water");
		Hull withWall = walled.build();
		assertEquals(27.0, withWall.shelteredVolume(), 1e-6, "slabs on the walls keep the water out one block higher");
		assertEquals(4.5f, withWall.pourY(0));
		assertEquals(Hull.WATERTIGHT, withWall.cellClass(0, 3, 0));
	}

	@Test
	void aSmallBlockInsideTheHullLeavesTheRestOfItsCellDry() {
		Hull.Builder b = openBox(5, 3, 5);
		// A carpet on the floor inside, and a chest.
		b.block(2, 1, 2, 0.0625f, true);
		b.block(1, 1, 1, 0.67f, true);
		Hull hull = b.build();
		assertEquals(18.0 - 0.0625 - 0.67, hull.shelteredVolume(), 1e-4);
		assertEquals(75.0, hull.capacity(), 1e-4, "the hull displaces as much with furniture as without");
		assertTrue(hull.cellClass(2, 1, 2) >= 0, "the cell of the carpet is dry like the air around it");
		assertTrue(hull.shelterClass(2.5, 1.03, 2.5) >= 0);
		// Standing on the floor: the point is in the floor block or just above it.
		assertTrue(hull.shelterClass(3.5, 0.99, 3.5) >= 0);
		assertTrue(hull.shelterClass(3.5, 1.1, 3.5) >= 0);
		assertEquals(Hull.OPEN, hull.shelterClass(3.5, 3.1, 3.5));
		// On top of a wall: watertight below, open above.
		assertEquals(Hull.OPEN, hull.shelterClass(0.5, 2.99, 2.5));
	}

	@Test
	void aBottomOfSlabsCountsWithItsOwnVolumeOnly() {
		Hull.Builder b = new Hull.Builder();
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				b.block(x, 0, z, 0.5f, true);
				if (x == 0 || z == 0 || x == 4 || z == 4) {
					b.block(x, 1, z, 1f, true);
				}
			}
		}
		Hull hull = b.build();
		assertEquals(12.5 + 16, hull.blockVolume(), 1e-6);
		assertEquals(9.0, hull.shelteredVolume(), 1e-6);
		assertEquals(Hull.WATERTIGHT, hull.cellClass(2, 0, 2));
	}

	@Test
	void aBlockStandsWhereItsShapeIsAndAsHighAsItsShape() {
		Hull.Builder b = new Hull.Builder();
		b.block(0, 0, 0, 1f, true);
		b.block(1, 0, 0, 0.5f, true, 0f, 0.5f);
		b.block(2, 0, 0, 0.5f, true, 0.5f, 1f);
		b.block(3, 0, 0, 0.0625f, true, 0f, 0.0625f);
		b.block(4, 0, 0, 0.3f, true, 0f, 0f);
		Hull hull = b.build();
		assertEquals(5, hull.elementCount());
		float[][] expected = {{0.5f, 1f}, {0.25f, 0.5f}, {0.75f, 0.5f}, {0.03125f, 0.0625f}, {0.03125f, 0.0625f}};
		for (int i = 0; i < 5; i++) {
			assertEquals(i + 0.5f, hull.elementX(i), 1e-6f);
			assertEquals(expected[i][0], hull.elementY(i), 1e-6f, "centre height of block " + i);
			assertEquals(expected[i][1], hull.elementEdge(i), 1e-6f, "height of block " + i);
			assertEquals(Hull.SEALED, hull.elementPour(i));
		}
		assertEquals(0.5f, hull.elementVolume(1), 1e-6f);

		Hull.Builder bottom = new Hull.Builder(), top = new Hull.Builder();
		bottom.block(0, 0, 0, 0.5f, true, 0f, 0.5f);
		top.block(0, 0, 0, 0.5f, true, 0.5f, 1f);
		assertNotEquals(bottom.fingerprint(), top.fingerprint());
	}

	@Test
	void onlyBlocksWithOpenSpaceBesideThemAreTheOutside() {
		Hull.Builder b = openBox(5, 3, 5);
		// A chest on the floor inside.
		b.block(2, 1, 2, 0.67f, true);
		Hull hull = b.build();
		int outside = 0, inside = 0, air = 0;
		for (int i = 0; i < hull.elementCount(); i++) {
			if (hull.elementPour(i) >= 0) {
				air++;
				assertFalse(hull.elementIsOutside(i), "sheltered air is not where a waterline shows");
			} else if (hull.elementIsOutside(i)) {
				outside++;
			} else {
				inside++;
				assertEquals(2.5f, hull.elementX(i), 1e-6f);
				assertEquals(2.5f, hull.elementZ(i), 1e-6f);
			}
		}
		assertEquals(57, outside, "the floor and the walls");
		assertEquals(1, inside, "the chest");
		assertEquals(18, air);

		// A solid cube: its core is inside.
		Hull.Builder cube = new Hull.Builder();
		for (int x = 0; x < 3; x++) {
			for (int y = 0; y < 3; y++) {
				for (int z = 0; z < 3; z++) {
					cube.block(x, y, z, 1f, true);
				}
			}
		}
		Hull solid = cube.build();
		int core = 0;
		for (int i = 0; i < solid.elementCount(); i++) {
			core += solid.elementIsOutside(i) ? 0 : 1;
		}
		assertEquals(1, core);
		// A fence post stands in open water itself.
		Hull.Builder post = new Hull.Builder();
		post.block(0, 0, 0, 0.125f, false);
		assertTrue(post.build().elementIsOutside(0));
		// Without the flood nothing is known of an inside: every block counts as outside.
		Hull unknown = openBox(5, 3, 5).build(Hull.MAX_ELEMENTS, 100);
		for (int i = 0; i < unknown.elementCount(); i++) {
			assertTrue(unknown.elementIsOutside(i));
		}
	}

	@Test
	void floodingGrowsFromTheRimToHalfABlockAboveIt() {
		assertEquals(0.0, Hull.flooding(0.0));
		assertEquals(0.5, Hull.flooding(0.25), 1e-9);
		assertEquals(1.0, Hull.flooding(0.5));
		assertEquals(1.0, Hull.flooding(1.0));
	}

	@Test
	void manyElementsAreMergedIntoCoarserOnesThatDisplaceTheSame() {
		Hull.Builder b = new Hull.Builder();
		for (int x = 0; x < 20; x++) {
			for (int y = 0; y < 20; y++) {
				for (int z = 0; z < 20; z++) {
					b.block(x - 7, y + 3, z, 1f, true);
				}
			}
		}
		Hull fine = b.build();
		assertEquals(8000, fine.elementCount());
		Hull coarse = b.build(500, Hull.MAX_GRID_CELLS);
		assertTrue(coarse.elementCount() <= 500, "elements: " + coarse.elementCount());
		double volume = 0, cx = 0, cy = 0, cz = 0;
		for (int i = 0; i < coarse.elementCount(); i++) {
			volume += coarse.elementVolume(i);
			cx += coarse.elementVolume(i) * coarse.elementX(i);
			cy += coarse.elementVolume(i) * coarse.elementY(i);
			cz += coarse.elementVolume(i) * coarse.elementZ(i);
			assertTrue(coarse.elementEdge(i) > 1f);
		}
		assertEquals(8000.0, volume, 1e-3);
		assertEquals(3.0, cx / volume, 1e-3);
		assertEquals(13.0, cy / volume, 1e-3);
		assertEquals(10.0, cz / volume, 1e-3);
		// The cells are still known one by one.
		assertEquals(Hull.WATERTIGHT, coarse.cellClass(-7, 3, 0));
	}

	@Test
	void anOpenHullKeepsItsShelteredAirWhenItsElementsAreMerged() {
		Hull hull = openBox(24, 10, 24).build(400, Hull.MAX_GRID_CELLS);
		assertTrue(hull.elementCount() <= 400);
		assertEquals(22 * 22 * 9.0, hull.shelteredVolume(), 1e-3);
		assertEquals(22 * 22 * 9.0, elementVolume(hull, true), 1e-2);
	}

	@Test
	void aHullWhoseBoundsAreTooLargeForTheFloodHasNoShelteredAir() {
		Hull hull = openBox(5, 3, 5).build(Hull.MAX_ELEMENTS, 100);
		assertFalse(hull.hasCavities());
		assertEquals(57.0, hull.capacity(), 1e-6);
		assertEquals(Hull.OPEN, hull.cellClass(2, 1, 2));
		assertEquals(57, hull.elementCount());
	}

	@Test
	void theFingerprintTellsDifferentBlocksApart() {
		assertEquals(openBox(5, 3, 5).fingerprint(), openBox(5, 3, 5).fingerprint());
		assertNotEquals(openBox(5, 3, 5).fingerprint(), openBox(5, 4, 5).fingerprint());
		Hull.Builder b = openBox(5, 3, 5);
		b.block(2, 1, 2, 0.5f, true);
		assertNotEquals(openBox(5, 3, 5).fingerprint(), b.fingerprint());
		assertEquals(0, Hull.EMPTY.elementCount());
		assertEquals(Hull.EMPTY, new Hull.Builder().build());
	}
}
