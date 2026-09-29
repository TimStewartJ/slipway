package dev.timstewart.slipway.math;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Where a vessel is: the world position of its local origin (the minimum corner of the anchor block) and its
 * orientation as a unit quaternion. Local coordinates are plot coordinates minus the anchor, so
 * {@code world = position + rotation * local}. Immutable; all arithmetic is in doubles because plots sit
 * 24 million blocks from the origin.
 *
 * <p>Quarter turns follow the right-hand rule about +Y: one quarter turn maps +X (east) to -Z (north), which is
 * Minecraft's {@link Rotation#COUNTERCLOCKWISE_90}.
 */
public record VesselPose(double x, double y, double z, double qx, double qy, double qz, double qw) {
	public static final VesselPose ORIGIN = new VesselPose(0, 0, 0, 0, 0, 0, 1);

	public static final Codec<VesselPose> CODEC = RecordCodecBuilder.create(i -> i.group(
		Vec3.CODEC.fieldOf("position").forGetter(VesselPose::position),
		Codec.DOUBLE.listOf(4, 4).fieldOf("rotation").forGetter(p -> List.of(p.qx, p.qy, p.qz, p.qw))
	).apply(i, (pos, q) -> new VesselPose(pos.x, pos.y, pos.z, q.get(0), q.get(1), q.get(2), q.get(3)).normalized()));

	public VesselPose {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
			|| !Double.isFinite(qx) || !Double.isFinite(qy) || !Double.isFinite(qz) || !Double.isFinite(qw)) {
			throw new IllegalArgumentException("non-finite pose " + x + "," + y + "," + z + " q=" + qx + "," + qy + "," + qz + "," + qw);
		}
	}

	public static VesselPose of(double x, double y, double z, Quaterniond q) {
		return new VesselPose(x, y, z, q.x, q.y, q.z, q.w).normalized();
	}

	public static VesselPose at(double x, double y, double z) {
		return new VesselPose(x, y, z, 0, 0, 0, 1);
	}

	/** Pose from Euler angles in degrees, applied yaw (about Y) then pitch (about X) then roll (about Z) in the body frame. */
	public static VesselPose fromYawPitchRoll(double x, double y, double z, double yawDeg, double pitchDeg, double rollDeg) {
		Quaterniond q = new Quaterniond()
			.rotateY(Math.toRadians(yawDeg))
			.rotateX(Math.toRadians(pitchDeg))
			.rotateZ(Math.toRadians(rollDeg));
		return of(x, y, z, q);
	}

	public VesselPose normalized() {
		double n = Math.sqrt(this.qx * this.qx + this.qy * this.qy + this.qz * this.qz + this.qw * this.qw);
		if (n < 1.0e-9) {
			return new VesselPose(this.x, this.y, this.z, 0, 0, 0, 1);
		}
		if (Math.abs(n - 1.0) < 1.0e-12) {
			return this;
		}
		return new VesselPose(this.x, this.y, this.z, this.qx / n, this.qy / n, this.qz / n, this.qw / n);
	}

	public Vec3 position() {
		return new Vec3(this.x, this.y, this.z);
	}

	public Quaterniond rotation() {
		return new Quaterniond(this.qx, this.qy, this.qz, this.qw);
	}

	public VesselPose withPosition(double nx, double ny, double nz) {
		return new VesselPose(nx, ny, nz, this.qx, this.qy, this.qz, this.qw);
	}

	public VesselPose withRotation(Quaterniond q) {
		return of(this.x, this.y, this.z, q);
	}

	/** Rotates a direction from local into world space. */
	public Vector3d rotate(double lx, double ly, double lz, Vector3d out) {
		return this.rotation().transform(lx, ly, lz, out);
	}

	/** Rotates a direction from world into local space. */
	public Vector3d inverseRotate(double wx, double wy, double wz, Vector3d out) {
		return this.rotation().transformInverse(wx, wy, wz, out);
	}

	public Vector3d localToWorld(double lx, double ly, double lz, Vector3d out) {
		this.rotate(lx, ly, lz, out);
		return out.add(this.x, this.y, this.z);
	}

	public Vec3 localToWorld(Vec3 local) {
		Vector3d v = this.localToWorld(local.x, local.y, local.z, new Vector3d());
		return new Vec3(v.x, v.y, v.z);
	}

	public Vector3d worldToLocal(double wx, double wy, double wz, Vector3d out) {
		return this.inverseRotate(wx - this.x, wy - this.y, wz - this.z, out);
	}

	public Vec3 worldToLocal(Vec3 world) {
		Vector3d v = this.worldToLocal(world.x, world.y, world.z, new Vector3d());
		return new Vec3(v.x, v.y, v.z);
	}

	/** Position lerp and spherical rotation interpolation; {@code t} is clamped to [0, 1]. */
	public VesselPose interpolate(VesselPose to, double t) {
		double s = Math.max(0.0, Math.min(1.0, t));
		Quaterniond q = this.rotation().slerp(to.rotation(), s);
		return of(this.x + (to.x - this.x) * s, this.y + (to.y - this.y) * s, this.z + (to.z - this.z) * s, q);
	}

	/** Angle in degrees between the vessel's local up axis and world up. */
	public double tiltDegrees() {
		Vector3d up = this.rotate(0, 1, 0, new Vector3d());
		return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, up.y))));
	}

	/** Heading of the local +X axis about world +Y in degrees, right-hand rule, in (-180, 180]. */
	public double headingDegrees() {
		Vector3d ax = this.rotate(1, 0, 0, new Vector3d());
		return Math.toDegrees(Math.atan2(-ax.z, ax.x));
	}

	/** The nearest whole number of quarter turns about +Y (0..3). */
	public int nearestQuarterTurns() {
		return Math.floorMod((int)Math.round(this.headingDegrees() / 90.0), 4);
	}

	/** Pitch, yaw and roll in degrees for the HUD: yaw is the heading of local +Z, pitch its elevation, roll the bank. */
	public double[] attitudeDegrees() {
		Vector3d forward = this.rotate(0, 0, 1, new Vector3d());
		Vector3d up = this.rotate(0, 1, 0, new Vector3d());
		double yaw = Math.toDegrees(Math.atan2(-forward.x, forward.z));
		double pitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, forward.y))));
		// Roll: angle of local up around the forward axis, measured from the vertical plane that contains forward.
		Vector3d right = new Vector3d(forward).cross(0, 1, 0);
		double roll;
		if (right.lengthSquared() < 1.0e-9) {
			roll = 0.0;
		} else {
			right.normalize();
			Vector3d level = new Vector3d(right).cross(forward).normalize();
			roll = Math.toDegrees(Math.atan2(up.dot(right), up.dot(level)));
		}
		return new double[] {pitch, yaw, roll};
	}

	/** Minecraft's rotation for a number of quarter turns about +Y as defined above. */
	public static Rotation minecraftRotation(int quarterTurns) {
		return switch (Math.floorMod(quarterTurns, 4)) {
			case 1 -> Rotation.COUNTERCLOCKWISE_90;
			case 2 -> Rotation.CLOCKWISE_180;
			case 3 -> Rotation.CLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/** Rotates an integer local offset by quarter turns about +Y. */
	public static int[] rotateQuarterTurns(int lx, int lz, int quarterTurns) {
		return switch (Math.floorMod(quarterTurns, 4)) {
			case 1 -> new int[] {lz, -lx};
			case 2 -> new int[] {-lx, -lz};
			case 3 -> new int[] {-lz, lx};
			default -> new int[] {lx, lz};
		};
	}
}
