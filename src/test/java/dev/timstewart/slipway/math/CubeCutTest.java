package dev.timstewart.slipway.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CubeCutTest {
	private static final Vector3d CENTRE = new Vector3d(10.5, 64.5, -3.5);

	/** The cut of a unit cube turned by {@code rotation} about {@link #CENTRE} with the plane at {@code height}. */
	private static float[] cut(Quaterniond rotation, double height) {
		float[] out = new float[CubeCut.MAX_FLOATS];
		int n = new CubeCut().quads(CENTRE, rotation.transform(new Vector3d(0.5, 0, 0)), rotation.transform(new Vector3d(0, 0.5, 0)),
			rotation.transform(new Vector3d(0, 0, 0.5)), height, 10, 60, -4, out, 0);
		return java.util.Arrays.copyOf(out, n);
	}

	/** Area of the quads (each a convex polygon in the plane, possibly with a repeated corner), by the shoelace formula. */
	private static double area(float[] quads) {
		double sum = 0;
		for (int q = 0; q < quads.length; q += 12) {
			double twice = 0;
			for (int v = 0; v < 4; v++) {
				int a = q + v * 3, b = q + (v + 1) % 4 * 3;
				twice += quads[a] * quads[b + 2] - quads[b] * quads[a + 2];
			}
			sum += Math.abs(twice) / 2;
		}
		return sum;
	}

	/** Area of the plane inside the cube, counted on a fine grid of points. */
	private static double areaByCounting(Quaterniond rotation, double height) {
		Quaterniond back = new Quaterniond(rotation).conjugate();
		int inside = 0;
		int n = 800;
		double span = 2.0;
		Vector3d p = new Vector3d();
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				p.set((i + 0.5) / n * span - span / 2, height - CENTRE.y, (j + 0.5) / n * span - span / 2);
				back.transform(p);
				if (Math.abs(p.x) <= 0.5 && Math.abs(p.y) <= 0.5 && Math.abs(p.z) <= 0.5) {
					inside++;
				}
			}
		}
		return inside * span * span / ((double)n * n);
	}

	private static void assertInsideTheCubeAtTheHeight(float[] quads, Quaterniond rotation, double height) {
		Quaterniond back = new Quaterniond(rotation).conjugate();
		for (int v = 0; v < quads.length; v += 3) {
			assertEquals(height - 60, quads[v + 1], 1e-5);
			Vector3d local = back.transform(new Vector3d(quads[v] + 10 - CENTRE.x, height - CENTRE.y, quads[v + 2] - 4 - CENTRE.z));
			assertTrue(Math.abs(local.x) <= 0.5001 && Math.abs(local.y) <= 0.5001 && Math.abs(local.z) <= 0.5001, "a corner lies outside the cube: " + local);
		}
	}

	@Test
	void aLevelCubeIsCutInItsOwnSquare() {
		float[] quads = cut(new Quaterniond(), 64.3);
		assertEquals(12, quads.length);
		assertEquals(1.0, area(quads), 1e-5);
		for (int v = 0; v < 12; v += 3) {
			// Relative to (10, 60, -4): the square from (0, 0) to (1, 1) at 4.3.
			assertTrue(Math.abs(quads[v]) < 1e-5 || Math.abs(quads[v] - 1) < 1e-5, "x of a corner: " + quads[v]);
			assertEquals(4.3f, quads[v + 1], 1e-5);
			assertTrue(Math.abs(quads[v + 2]) < 1e-5 || Math.abs(quads[v + 2] - 1) < 1e-5, "z of a corner: " + quads[v + 2]);
		}
	}

	@Test
	void theCornersGoRoundThePolygon() {
		// Round a convex polygon, every turn has the same sense.
		Quaterniond rotation = new Quaterniond().rotateXYZ(0.5, 0.3, 0.9);
		for (double height = 63.75; height <= 65.25; height += 0.125) {
			float[] quads = cut(rotation, height);
			for (int q = 0; q < quads.length; q += 12) {
				int sense = 0;
				for (int v = 0; v < 4; v++) {
					int a = q + v * 3, b = q + (v + 1) % 4 * 3, c = q + (v + 2) % 4 * 3;
					double cross = (quads[b] - quads[a]) * (double)(quads[c + 2] - quads[b + 2]) - (quads[b + 2] - quads[a + 2]) * (double)(quads[c] - quads[b]);
					if (Math.abs(cross) > 1e-7) {
						assertTrue(sense == 0 || sense == (int)Math.signum(cross), "the corners of a quad do not go round it at height " + height);
						sense = (int)Math.signum(cross);
					}
				}
			}
		}
	}

	@Test
	void aTurnedCubeIsCutInThePolygonThePlaneHasInIt() {
		Quaterniond[] rotations = {
			new Quaterniond().rotateY(Math.toRadians(37)),
			new Quaterniond().rotateX(Math.toRadians(30)),
			new Quaterniond().rotateZ(Math.toRadians(-12)).rotateY(1.1),
			new Quaterniond().rotateXYZ(0.5, 0.3, 0.9),
			// A body diagonal upright: the cut through the centre is a regular hexagon.
			new Quaterniond().rotationTo(new Vector3d(1, 1, 1).normalize(), new Vector3d(0, 1, 0)),
		};
		for (Quaterniond rotation : rotations) {
			for (double height = 63.7; height <= 65.3; height += 0.1) {
				float[] quads = cut(rotation, height);
				assertInsideTheCubeAtTheHeight(quads, rotation, height);
				assertEquals(areaByCounting(rotation, height), area(quads), 0.012, "area of the cut at height " + height + " of the cube turned by " + rotation);
			}
		}
		assertEquals(1.0 / Math.cos(Math.toRadians(30)), area(cut(rotations[1], 64.5)), 1e-4, "a cube pitched 30 degrees, cut through its centre");
		float[] hexagon = cut(rotations[4], 64.5);
		assertEquals(24, hexagon.length, "a hexagon takes two quads");
		assertEquals(3 * Math.sqrt(3) / 4, area(hexagon), 1e-4);
	}

	@Test
	void aPlaneThatMissesTheCubeCutsNothing() {
		assertEquals(0, cut(new Quaterniond(), 65.01).length);
		assertEquals(0, cut(new Quaterniond(), 63.99).length);
		assertEquals(0, cut(new Quaterniond().rotateXYZ(0.5, 0.3, 0.9), 65.4).length);
		// Quads are appended where the caller says.
		float[] out = new float[3 + CubeCut.MAX_FLOATS];
		assertEquals(15, new CubeCut().quads(CENTRE, new Vector3d(0.5, 0, 0), new Vector3d(0, 0.5, 0), new Vector3d(0, 0, 0.5), 64.5, 0, 0, 0, out, 3));
		assertEquals(0f, out[0]);
		assertEquals(64.5f, out[4]);
	}
}
