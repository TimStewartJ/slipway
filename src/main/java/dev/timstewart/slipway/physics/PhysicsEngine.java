package dev.timstewart.slipway.physics;

import dev.timstewart.slipway.math.VesselPose;
import org.joml.Vector3d;

/**
 * The narrow interface Slipway needs from a rigid-body engine. One instance simulates one level. Implementations
 * are single-threaded: every call comes from the thread that owns the instance ({@link PhysicsWorld} runs them on
 * its physics thread). Bodies are keyed by Slipway's own ids so no engine handle escapes the implementation.
 */
public interface PhysicsEngine extends AutoCloseable {
	/** Creates the dynamic body of a vessel, or replaces its shape when it exists (keeping its pose and velocity). */
	void setVesselShape(long vesselId, BoxList boxes, VesselPose pose, Vector3d linearVelocity, Vector3d angularVelocity);

	void removeVessel(long vesselId);

	boolean hasVessel(long vesselId);

	/** Moves a vessel instantly and stops it. */
	void teleportVessel(long vesselId, VesselPose pose);

	/** Applies a force at the centre of mass and a torque for the next step only. */
	void applyForceAndTorque(long vesselId, Vector3d force, Vector3d torque);

	/**
	 * Marks a vessel's body as loose (a plain rigid body nobody controls) or controlled. A loose body may fall asleep
	 * once it rests and then costs nothing until something touches it or the things around it change; a controlled
	 * body never sleeps, because its controller acts on it every step.
	 */
	void setVesselLoose(long vesselId, boolean loose);

	/**
	 * Wakes the sleeping loose vessels in and around a box of world coordinates: something that is not a body changed
	 * there (the water a vessel floats in was taken away or let in).
	 */
	default void wakeLooseVessels(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	}

	/** Creates, replaces or (with empty boxes) removes the static body of a terrain section. */
	void setStaticSection(long sectionKey, BoxList boxes, int originX, int originY, int originZ);

	void removeStaticSection(long sectionKey);

	boolean hasStaticSection(long sectionKey);

	void step(float seconds, int substeps);

	/** Current state of a vessel; returns false when it has no body. */
	boolean readVessel(long vesselId, BodyState out);

	int vesselCount();

	int staticSectionCount();

	@Override
	void close();

	/** Mutable holder for a vessel body's state, reused to avoid allocation in the hot path. */
	final class BodyState {
		public double x, y, z;
		public double qx, qy, qz, qw = 1;
		public double vx, vy, vz;
		public double wx, wy, wz;
		/** False while the body sleeps (a loose vessel at rest). */
		public boolean active;

		public boolean isFinite() {
			return Double.isFinite(this.x) && Double.isFinite(this.y) && Double.isFinite(this.z)
				&& Double.isFinite(this.qx) && Double.isFinite(this.qy) && Double.isFinite(this.qz) && Double.isFinite(this.qw)
				&& Double.isFinite(this.vx) && Double.isFinite(this.vy) && Double.isFinite(this.vz)
				&& Double.isFinite(this.wx) && Double.isFinite(this.wy) && Double.isFinite(this.wz);
		}

		public VesselPose pose() {
			return new VesselPose(this.x, this.y, this.z, this.qx, this.qy, this.qz, this.qw).normalized();
		}

		public void copyFrom(BodyState other) {
			this.x = other.x; this.y = other.y; this.z = other.z;
			this.qx = other.qx; this.qy = other.qy; this.qz = other.qz; this.qw = other.qw;
			this.vx = other.vx; this.vy = other.vy; this.vz = other.vz;
			this.wx = other.wx; this.wy = other.wy; this.wz = other.wz;
			this.active = other.active;
		}
	}
}
