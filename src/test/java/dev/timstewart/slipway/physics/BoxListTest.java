package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxListTest {
	@Test
	void greedyMergeTurnsASolidCubeIntoOneBox() {
		boolean[] solid = new boolean[16 * 16 * 16];
		java.util.Arrays.fill(solid, true);
		int[] material = new int[solid.length];
		BoxList out = new BoxList();
		GreedyBoxes.merge(solid, material, BlockDensity.DENSITIES, 16, 16, 16, 0, 0, 0, out);
		assertEquals(1, out.size());
		assertEquals(16f, out.maxX(0));
		assertEquals(16f, out.maxY(0));
		assertEquals(16f, out.maxZ(0));
	}

	@Test
	void greedyMergeCoversEveryCellExactlyOnceAndSeparatesMaterials() {
		int sx = 7, sy = 5, sz = 6;
		boolean[] solid = new boolean[sx * sy * sz];
		int[] material = new int[solid.length];
		java.util.Random random = new java.util.Random(42);
		int cells = 0;
		for (int i = 0; i < solid.length; i++) {
			solid[i] = random.nextInt(3) > 0;
			material[i] = random.nextInt(2);
			cells += solid[i] ? 1 : 0;
		}
		boolean[] copy = solid.clone();
		BoxList out = new BoxList();
		GreedyBoxes.merge(solid, material, BlockDensity.DENSITIES, sx, sy, sz, 0, 0, 0, out);
		int[] coverage = new int[copy.length];
		double volume = 0;
		for (int b = 0; b < out.size(); b++) {
			int m = -1;
			for (int y = (int)out.minY(b); y < out.maxY(b); y++) {
				for (int z = (int)out.minZ(b); z < out.maxZ(b); z++) {
					for (int x = (int)out.minX(b); x < out.maxX(b); x++) {
						int i = (y * sz + z) * sx + x;
						coverage[i]++;
						assertTrue(copy[i], "box covers an empty cell");
						if (m < 0) {
							m = material[i];
						}
						assertEquals(m, material[i], "box mixes materials");
					}
				}
			}
			volume += (out.maxX(b) - out.minX(b)) * (out.maxY(b) - out.minY(b)) * (out.maxZ(b) - out.minZ(b));
		}
		for (int i = 0; i < copy.length; i++) {
			assertEquals(copy[i] ? 1 : 0, coverage[i], "cell " + i);
		}
		assertEquals(cells, volume, 1e-9);
		assertTrue(out.size() < cells, "merging reduced the box count");
	}

	@Test
	void massPropertiesOfASingleBoxMatchTheTextbook() {
		BoxList boxes = new BoxList();
		boxes.add(0, 0, 0, 2, 1, 4, 1000f);
		BoxList.MassProperties props = boxes.massProperties();
		double m = 2 * 1 * 4 * 1000.0;
		assertEquals(m, props.mass(), 1e-6);
		assertEquals(1.0, props.comX(), 1e-9);
		assertEquals(0.5, props.comY(), 1e-9);
		assertEquals(2.0, props.comZ(), 1e-9);
		assertArrayEquals(new double[] {m * (1 + 16) / 12, 0, 0, 0, m * (4 + 16) / 12, 0, 0, 0, m * (4 + 1) / 12}, props.inertia(), 1e-6);
	}

	@Test
	void twoBoxesUseTheParallelAxisTheorem() {
		BoxList boxes = new BoxList();
		boxes.add(-1, 0, 0, 0, 1, 1, 500f);
		boxes.add(0, 0, 0, 1, 1, 1, 500f);
		BoxList.MassProperties split = boxes.massProperties();
		BoxList whole = new BoxList();
		whole.add(-1, 0, 0, 1, 1, 1, 500f);
		BoxList.MassProperties single = whole.massProperties();
		assertEquals(single.mass(), split.mass(), 1e-9);
		assertEquals(single.comX(), split.comX(), 1e-9);
		assertArrayEquals(single.inertia(), split.inertia(), 1e-9);
	}

	@Test
	void degenerateAndInvalidBoxesAreIgnored() {
		BoxList boxes = new BoxList();
		boxes.add(0, 0, 0, 0, 1, 1, 1000f);
		boxes.add(0, 0, 0, 1, 1, 1, Float.NaN);
		boxes.add(0, 0, 0, 1, 1, 1, -5f);
		assertEquals(0, boxes.size());
		assertEquals(0.0, boxes.massProperties().mass());
	}
}
