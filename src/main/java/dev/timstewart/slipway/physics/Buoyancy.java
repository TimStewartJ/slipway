package dev.timstewart.slipway.physics;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * What water (or lava) does to a vessel's body in one physics step: lift and resistance.
 *
 * <p>Lift is Archimedes' principle taken element by element of the vessel's {@link Hull}: each element under the
 * surface is pushed up by the weight of the fluid it displaces, {@code density g volume}, at its centre. An element
 * of sheltered air displaces only while its pour point is above water. Summed,
 * that is a force and a torque about the centre of mass: a hull floats at the draught where it displaces its own
 * weight, is righted when it heels (the low side displaces more), sinks when it is too heavy, and goes down when
 * the water runs over its rim.
 *
 * <p>Resistance is a drag on the centre of mass and on the turn rate, scaled by how much of its own weight the
 * vessel displaces ({@code ratio}; 1 for anything that floats): {@code a = -ratio (c v + q |v| v)} along each of the
 * vessel's own axes, with little resistance along its length and much across and up and down, like a keel, and
 * {@code alpha = -ratio c w} for turning. It is what stops a floating vessel's bobbing and rocking, lets a boat run
 * straight and turn instead of sliding, and gives something that sinks its terminal speed.
 *
 * <p>The numbers are chosen, not measured: with the default helm thrust (12 m/s^2 against a drag of 0.5 per second
 * in the air) a boat reaches 12 / (0.5 + 0.7) = 10 m/s, and a hull with a draught of one to two blocks bobs two or
 * three times after a drop.
 *
 * <p>A hovering vessel is left alone: hover cancels gravity, and without weight nothing floats. It keeps its place
 * in the water as it does in the air, which is what makes it a submarine.
 */
public final class Buoyancy {
	/** Linear resistance per second along the vessel's forward, right and up axes. */
	public static final double DRAG_FORWARD = 0.7;
	public static final double DRAG_SIDE = 2.5;
	public static final double DRAG_UP = 2.5;
	/** Resistance growing with the square of the speed, per block, along the same axes. */
	public static final double DRAG_SQUARE_FORWARD = 0.02;
	public static final double DRAG_SQUARE_SIDE = 0.15;
	public static final double DRAG_SQUARE_UP = 0.15;
	/** Resistance to turning, per second. */
	public static final double DRAG_TURN = 1.5;
	/** The ratio of displaced weight to own weight the resistance stops growing at (a block of wool under water has 5). */
	public static final double MAX_RATIO = 3.0;
	/** The most of a speed or turn rate the resistance takes away in one step: it never reverses a motion. */
	private static final double MAX_STEP_LOSS = 0.8;
	/** Places at the waterline reported per step, for splashes. */
	public static final int WATERLINE_SAMPLES = 8;
	/** How far outside an outside element's face its place on the waterline lies, in blocks. */
	public static final double WATERLINE_OUT = 0.3;

	private Buoyancy() {
	}

	/**
	 * @param enabled whether fluids act on vessels at all
	 * @param drag scale of the resistance (1 is the default)
	 */
	public record Params(boolean enabled, double drag) {
		public static final Params DEFAULT = new Params(true, 1.0);
	}

	/**
	 * What one vessel's last step in a fluid looked like. One instance belongs to one vessel; the thread that steps the
	 * physics writes it, anything else reads it only while no step runs.
	 */
	public static final class State {
		/** Volume of fluid displaced, m^3. */
		public double displacedVolume;
		/** Mass of that fluid, kg. */
		public double displacedMass;
		/** Sheltered air below the surface that the fluid has run into, m^3. */
		public double floodedVolume;
		/** {@link FluidField#WATER}, {@link FluidField#LAVA} or {@link FluidField#NONE}. */
		public int fluid;
		/** Whether lift and resistance were applied (not to a hovering vessel). */
		public boolean applied;
		/** World x, surface y and z of up to {@link #WATERLINE_SAMPLES} of the vessel's outside blocks that the surface cuts. */
		public final float[] waterline = new float[3 * WATERLINE_SAMPLES];
		public int waterlineCount;

		public void clear() {
			this.displacedVolume = 0;
			this.displacedMass = 0;
			this.floodedVolume = 0;
			this.fluid = FluidField.NONE;
			this.applied = false;
			this.waterlineCount = 0;
		}

		public void copyFrom(State other) {
			this.displacedVolume = other.displacedVolume;
			this.displacedMass = other.displacedMass;
			this.floodedVolume = other.floodedVolume;
			this.fluid = other.fluid;
			this.applied = other.applied;
			this.waterlineCount = other.waterlineCount;
			System.arraycopy(other.waterline, 0, this.waterline, 0, other.waterlineCount * 3);
		}
	}

	/** Reused between calls on the physics thread. */
	public static final class Scratch {
		final PhysicsEngine.BodyState body = new PhysicsEngine.BodyState();
		double[] flood = new double[16];
		long seed = 0x9E3779B97F4A7C15L;
	}

	/**
	 * Applies one step of lift and resistance to a vessel's body and records what it found.
	 *
	 * @param forces false to only record (a hovering vessel)
	 * @param forwardLocal unit vector of the vessel's forward direction in its own frame (horizontal)
	 * @return true when the vessel displaces any fluid
	 */
	public static boolean apply(PhysicsEngine engine, FluidField field, Params params, VesselController.Step step, long vesselId, Hull hull,
		BoxList.MassProperties mass, Vector3d forwardLocal, boolean forces, State out, Scratch scratch) {
		PhysicsEngine.BodyState body = scratch.body;
		if (!params.enabled() || hull.elementCount() == 0 || field.isEmpty() || mass.mass() <= 0 || !engine.readVessel(vesselId, body) || !body.isFinite()) {
			out.clear();
			return false;
		}
		if (!body.active) {
			// Asleep: it rests as the last step left it, floating or sunk; what was recorded then still holds.
			return out.displacedVolume > 0;
		}
		double qx = body.qx, qy = body.qy, qz = body.qz, qw = body.qw;
		double m00 = 1 - 2 * (qy * qy + qz * qz), m01 = 2 * (qx * qy - qz * qw), m02 = 2 * (qx * qz + qy * qw);
		double m10 = 2 * (qx * qy + qz * qw), m11 = 1 - 2 * (qx * qx + qz * qz), m12 = 2 * (qy * qz - qx * qw);
		double m20 = 2 * (qx * qz - qy * qw), m21 = 2 * (qy * qz + qx * qw), m22 = 1 - 2 * (qx * qx + qy * qy);

		// Nothing to do far from any fluid: the box around the hull's bounds, and a block more.
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			double lx = hull.minX() + ((i & 1) == 0 ? 0 : hull.sizeX());
			double ly = hull.minY() + ((i & 2) == 0 ? 0 : hull.sizeY());
			double lz = hull.minZ() + ((i & 4) == 0 ? 0 : hull.sizeZ());
			double wx = body.x + m00 * lx + m01 * ly + m02 * lz;
			double wy = body.y + m10 * lx + m11 * ly + m12 * lz;
			double wz = body.z + m20 * lx + m21 * ly + m22 * lz;
			minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
			maxX = Math.max(maxX, wx); maxY = Math.max(maxY, wy); maxZ = Math.max(maxZ, wz);
		}
		out.clear();
		if (!field.anyIn(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1)) {
			return false;
		}

		int pours = hull.pourCount();
		if (scratch.flood.length < pours) {
			scratch.flood = new double[Math.max(pours, scratch.flood.length * 2)];
		}
		double[] flood = scratch.flood;
		java.util.Arrays.fill(flood, 0, pours, Double.NaN);

		double comX = body.x + m00 * mass.comX() + m01 * mass.comY() + m02 * mass.comZ();
		double comZ = body.z + m20 * mass.comX() + m21 * mass.comY() + m22 * mass.comZ();
		double lift = 0, torqueX = 0, torqueZ = 0;
		double displaced = 0, displacedMass = 0, flooded = 0;
		int fluidSeen = FluidField.NONE;
		int cut = 0;
		long seed = scratch.seed;
		for (int i = 0, n = hull.elementCount(); i < n; i++) {
			double lx = hull.elementX(i), ly = hull.elementY(i), lz = hull.elementZ(i);
			double wx = body.x + m00 * lx + m01 * ly + m02 * lz;
			double wy = body.y + m10 * lx + m11 * ly + m12 * lz;
			double wz = body.z + m20 * lx + m21 * ly + m22 * lz;
			double edge = hull.elementEdge(i);
			double under = field.submerged(wx, wy, wz, edge);
			if (under <= 0) {
				continue;
			}
			int fluid = field.lastFluid();
			double volume = hull.elementVolume(i) * under;
			int p = hull.elementPour(i);
			if (p >= 0) {
				double f = flood[p];
				if (f != f) {
					double px = hull.pourX(p), py = hull.pourY(p), pz = hull.pourZ(p);
					f = Hull.flooding(field.submerged(body.x + m00 * px + m01 * py + m02 * pz, body.y + m10 * px + m11 * py + m12 * pz,
						body.z + m20 * px + m21 * py + m22 * pz, 1.0));
					flood[p] = f;
				}
				flooded += volume * f;
				volume *= 1 - f;
				if (volume <= 0) {
					continue;
				}
			}
			double density = FluidField.density(fluid);
			double force = density * VesselController.GRAVITY * volume;
			lift += force;
			// F = (0, force, 0) at r from the centre of mass: r x F = (-r.z force, 0, r.x force).
			torqueX -= (wz - comZ) * force;
			torqueZ += (wx - comX) * force;
			displaced += volume;
			displacedMass += density * volume;
			fluidSeen = Math.max(fluidSeen, fluid);
			if (under < 1 && hull.elementIsOutside(i)) {
				// An outside block the surface cuts: where the hull meets the water (the air it keeps dry is cut by the
				// surface too, but nothing splashes in there). Reservoir sampling: each is as likely to be among the few kept.
				int slot = cut < WATERLINE_SAMPLES ? cut : -1;
				if (slot < 0) {
					seed = seed * 6364136223846793005L + 1442695040888963407L;
					int r = (int)((seed >>> 33) % (cut + 1));
					slot = r < WATERLINE_SAMPLES ? r : -1;
				}
				if (slot >= 0) {
					// Just outside the block's face, where the water is: at its centre the spray would be in the wall, and
					// half of it would come out on the inside.
					double out2 = Math.max(1.0, edge) * 0.5 + WATERLINE_OUT;
					double ox = hull.elementOutX(i) * out2, oz = hull.elementOutZ(i) * out2;
					out.waterline[slot * 3] = (float)(wx + m00 * ox + m02 * oz);
					out.waterline[slot * 3 + 1] = (float)(wy - edge * 0.5 + under * edge);
					out.waterline[slot * 3 + 2] = (float)(wz + m20 * ox + m22 * oz);
				}
				cut++;
			}
		}
		scratch.seed = seed;
		out.displacedVolume = displaced;
		out.displacedMass = displacedMass;
		out.floodedVolume = flooded;
		out.fluid = fluidSeen;
		out.waterlineCount = Math.min(cut, WATERLINE_SAMPLES);
		if (displaced <= 0 || !forces) {
			return displaced > 0;
		}

		Vector3d force = new Vector3d(0, lift, 0);
		Vector3d torque = new Vector3d(torqueX, 0, torqueZ);
		double ratio = Math.min(MAX_RATIO, displacedMass / mass.mass()) * params.drag();
		if (ratio > 0) {
			Quaterniond rotation = new Quaterniond(qx, qy, qz, qw);
			Vector3d f = rotation.transform(new Vector3d(forwardLocal));
			Vector3d u = new Vector3d(m01, m11, m21);
			Vector3d r = new Vector3d(f).cross(u);
			Vector3d velocity = new Vector3d(body.vx, body.vy, body.vz);
			double most = MAX_STEP_LOSS / step.seconds();
			drag(force, f, velocity, ratio * DRAG_FORWARD, ratio * DRAG_SQUARE_FORWARD, most, mass.mass());
			drag(force, r, velocity, ratio * DRAG_SIDE, ratio * DRAG_SQUARE_SIDE, most, mass.mass());
			drag(force, u, velocity, ratio * DRAG_UP, ratio * DRAG_SQUARE_UP, most, mass.mass());
			Vector3d alpha = new Vector3d(body.wx, body.wy, body.wz).mul(-Math.min(most, ratio * DRAG_TURN));
			double[] inertia = mass.inertia();
			Matrix3d rot = new Matrix3d().set(rotation);
			Matrix3d local = new Matrix3d(inertia[0], inertia[3], inertia[6], inertia[1], inertia[4], inertia[7], inertia[2], inertia[5], inertia[8]);
			torque.add(new Matrix3d(rot).mul(local).mul(new Matrix3d(rot).transpose()).transform(alpha));
		}
		if (force.isFinite() && torque.isFinite()) {
			engine.applyForceAndTorque(vesselId, force, torque);
			out.applied = true;
		}
		return true;
	}

	private static void drag(Vector3d force, Vector3d axis, Vector3d velocity, double linear, double square, double most, double mass) {
		double along = velocity.dot(axis);
		double rate = Math.min(most, linear + square * Math.abs(along));
		force.fma(-rate * along * mass, axis);
	}
}
