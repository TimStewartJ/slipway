package dev.timstewart.slipway.math;

import org.joml.Vector3d;

/**
 * The cut of a cube with a horizontal plane: the piece of the plane inside the cube, a polygon of three to six
 * corners, as one or two quads (a triangle repeats its last corner). The cube may be turned any way; it is given by
 * its centre and its three half edges. Plain arithmetic; one instance keeps the scratch space for one thread.
 */
public final class CubeCut {
	/** Floats one cut can add: two quads of four corners of three coordinates. */
	public static final int MAX_FLOATS = 24;
	/** The twelve edges of a cube whose corner k has the signs of bits 0, 1 and 2 of k along its three half edges. */
	private static final int[][] EDGES = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

	private final double[] px = new double[6], pz = new double[6], angle = new double[6];
	private final double[] cornerX = new double[8], cornerY = new double[8], cornerZ = new double[8];

	/**
	 * Appends the cut's quads to {@code out} from index {@code n} on, each corner as x, y, z with (ox, oy, oz) taken
	 * off, in order round the polygon. {@code out} needs room for {@link #MAX_FLOATS} more.
	 *
	 * @param centre the cube's centre
	 * @param ax the cube's half edges (from the centre to the middle of three faces that meet)
	 * @param height the plane's height
	 * @return the index after the last float written ({@code n} when the plane misses the cube)
	 */
	public int quads(Vector3d centre, Vector3d ax, Vector3d ay, Vector3d az, double height, double ox, double oy, double oz, float[] out, int n) {
		for (int k = 0; k < 8; k++) {
			double sx = (k & 1) == 0 ? -1 : 1, sy = (k & 2) == 0 ? -1 : 1, sz = (k & 4) == 0 ? -1 : 1;
			this.cornerX[k] = centre.x + sx * ax.x + sy * ay.x + sz * az.x;
			this.cornerY[k] = centre.y + sx * ax.y + sy * ay.y + sz * az.y - height;
			this.cornerZ[k] = centre.z + sx * ax.z + sy * ay.z + sz * az.z;
		}
		int points = 0;
		double mx = 0, mz = 0;
		for (int[] edge : EDGES) {
			double a = this.cornerY[edge[0]], b = this.cornerY[edge[1]];
			if (a <= 0 == b <= 0 || points == 6) {
				continue;
			}
			double t = a / (a - b);
			this.px[points] = this.cornerX[edge[0]] + t * (this.cornerX[edge[1]] - this.cornerX[edge[0]]);
			this.pz[points] = this.cornerZ[edge[0]] + t * (this.cornerZ[edge[1]] - this.cornerZ[edge[0]]);
			mx += this.px[points];
			mz += this.pz[points];
			points++;
		}
		if (points < 3) {
			return n;
		}
		mx /= points;
		mz /= points;
		for (int p = 0; p < points; p++) {
			this.angle[p] = Math.atan2(this.pz[p] - mz, this.px[p] - mx);
		}
		// The corners in order round the middle (the cut of a convex body is convex): an insertion sort of at most six.
		for (int p = 1; p < points; p++) {
			double ka = this.angle[p], kx = this.px[p], kz = this.pz[p];
			int q = p - 1;
			while (q >= 0 && this.angle[q] > ka) {
				this.angle[q + 1] = this.angle[q];
				this.px[q + 1] = this.px[q];
				this.pz[q + 1] = this.pz[q];
				q--;
			}
			this.angle[q + 1] = ka;
			this.px[q + 1] = kx;
			this.pz[q + 1] = kz;
		}
		// A fan of quads from the first corner: (0, 1, 2, 3) and, for five or six corners, (0, 3, 4, 5).
		float y = (float)(height - oy);
		for (int first = 1; first < points - 1; first += 2) {
			int last = Math.min(first + 2, points - 1);
			n = this.corner(out, n, 0, y, ox, oz);
			n = this.corner(out, n, first, y, ox, oz);
			n = this.corner(out, n, first + 1, y, ox, oz);
			n = this.corner(out, n, last, y, ox, oz);
		}
		return n;
	}

	private int corner(float[] out, int n, int p, float y, double ox, double oz) {
		out[n] = (float)(this.px[p] - ox);
		out[n + 1] = y;
		out[n + 2] = (float)(this.pz[p] - oz);
		return n + 3;
	}
}
