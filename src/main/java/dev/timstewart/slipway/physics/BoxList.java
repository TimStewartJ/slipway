package dev.timstewart.slipway.physics;

import java.util.Arrays;

/**
 * A growable list of axis-aligned boxes with a density each, in a body's local frame, plus the rigid-body mass
 * properties of their union: total mass, centre of mass, and the inertia tensor about the centre of mass
 * (solid-box inertia moved with the parallel-axis theorem). Plain Java, no native state.
 */
public final class BoxList {
	private float[] data = new float[7 * 16];
	private int size;

	public int size() {
		return this.size;
	}

	public boolean isEmpty() {
		return this.size == 0;
	}

	public void clear() {
		this.size = 0;
	}

	/** Adds a box with the given bounds (min < max on every axis) and density in kg/m^3. */
	public void add(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float density) {
		if (!(maxX > minX && maxY > minY && maxZ > minZ) || !Float.isFinite(density) || density <= 0) {
			return;
		}
		if ((this.size + 1) * 7 > this.data.length) {
			this.data = Arrays.copyOf(this.data, this.data.length * 2);
		}
		int i = this.size * 7;
		this.data[i] = minX;
		this.data[i + 1] = minY;
		this.data[i + 2] = minZ;
		this.data[i + 3] = maxX;
		this.data[i + 4] = maxY;
		this.data[i + 5] = maxZ;
		this.data[i + 6] = density;
		this.size++;
	}

	/** Appends all boxes of another list, offset by (dx, dy, dz). */
	public void addAll(BoxList other, float dx, float dy, float dz) {
		for (int b = 0; b < other.size; b++) {
			int i = b * 7;
			this.add(other.data[i] + dx, other.data[i + 1] + dy, other.data[i + 2] + dz,
				other.data[i + 3] + dx, other.data[i + 4] + dy, other.data[i + 5] + dz, other.data[i + 6]);
		}
	}

	public float minX(int b) { return this.data[b * 7]; }
	public float minY(int b) { return this.data[b * 7 + 1]; }
	public float minZ(int b) { return this.data[b * 7 + 2]; }
	public float maxX(int b) { return this.data[b * 7 + 3]; }
	public float maxY(int b) { return this.data[b * 7 + 4]; }
	public float maxZ(int b) { return this.data[b * 7 + 5]; }
	public float density(int b) { return this.data[b * 7 + 6]; }

	/** Mass properties of the union of all boxes. */
	public MassProperties massProperties() {
		double mass = 0, cx = 0, cy = 0, cz = 0;
		for (int b = 0; b < this.size; b++) {
			double m = this.boxMass(b);
			mass += m;
			cx += m * (this.minX(b) + this.maxX(b)) * 0.5;
			cy += m * (this.minY(b) + this.maxY(b)) * 0.5;
			cz += m * (this.minZ(b) + this.maxZ(b)) * 0.5;
		}
		if (mass <= 0) {
			return MassProperties.EMPTY;
		}
		cx /= mass;
		cy /= mass;
		cz /= mass;
		double ixx = 0, iyy = 0, izz = 0, ixy = 0, ixz = 0, iyz = 0;
		for (int b = 0; b < this.size; b++) {
			double m = this.boxMass(b);
			double sx = this.maxX(b) - this.minX(b), sy = this.maxY(b) - this.minY(b), sz = this.maxZ(b) - this.minZ(b);
			double dx = (this.minX(b) + this.maxX(b)) * 0.5 - cx;
			double dy = (this.minY(b) + this.maxY(b)) * 0.5 - cy;
			double dz = (this.minZ(b) + this.maxZ(b)) * 0.5 - cz;
			// Solid box about its own centre, then the parallel-axis theorem: I += m (|d|^2 E - d d^T).
			ixx += m * (sy * sy + sz * sz) / 12.0 + m * (dy * dy + dz * dz);
			iyy += m * (sx * sx + sz * sz) / 12.0 + m * (dx * dx + dz * dz);
			izz += m * (sx * sx + sy * sy) / 12.0 + m * (dx * dx + dy * dy);
			ixy -= m * dx * dy;
			ixz -= m * dx * dz;
			iyz -= m * dy * dz;
		}
		return new MassProperties(mass, cx, cy, cz, new double[] {ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz});
	}

	private double boxMass(int b) {
		return (double)(this.maxX(b) - this.minX(b)) * (this.maxY(b) - this.minY(b)) * (this.maxZ(b) - this.minZ(b)) * this.density(b);
	}

	/**
	 * @param inertia row-major 3x3 inertia tensor about the centre of mass, local axes, kg m^2
	 */
	public record MassProperties(double mass, double comX, double comY, double comZ, double[] inertia) {
		public static final MassProperties EMPTY = new MassProperties(0, 0, 0, 0, new double[9]);
	}
}
