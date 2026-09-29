package dev.timstewart.slipway.physics;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Turns helm input into a force and a torque for one physics step. Forces scale with the vessel's mass and
 * torques with its inertia tensor, so a skiff and a thousand-block galleon accelerate and turn alike.
 *
 * <p>Linear: full input asks for {@code thrustAcceleration} along the vessel's own forward, right and up axes,
 * with drag {@code thrustAcceleration / maxSpeed} per second limiting speed. In hover mode gravity is cancelled
 * and every local axis without input is braked to rest, so a released vessel holds its position.
 *
 * <p>Angular: input asks for turn rates about the vessel's own right (pitch), up (yaw) and forward (roll) axes up
 * to {@code maxTurnRate}; a proportional controller on angular velocity, limited to {@code angularAcceleration},
 * produces the torque {@code I * alpha} with the world-space inertia tensor. Without pitch and roll input, level
 * mode adds a rate that rotates the vessel's up axis back towards world up (keeping its heading); with level off
 * the vessel holds whatever attitude it has. All rotation is quaternion based, so there is no gimbal lock.
 *
 * <p>This is our own design using textbook rigid-body relations (F = m a, tau = I alpha, R I R^T for the world
 * inertia) and a proportional rate controller.
 */
public final class VesselController {
	/** Per-second gain of hover braking on idle axes. */
	public static final double BRAKE_GAIN = 1.5;
	public static final double GRAVITY = 9.81;

	private VesselController() {
	}

	public record Params(double thrustAcceleration, double maxSpeed, double angularAcceleration, double maxTurnRate, double levelStrength) {
	}

	public record Command(Vector3d force, Vector3d torque) {
		public boolean isFinite() {
			return this.force.isFinite() && this.torque.isFinite();
		}
	}

	/**
	 * @param forwardLocal unit vector of the vessel's forward direction in local coordinates (horizontal)
	 * @param inertiaLocal row-major inertia tensor about the centre of mass in local axes
	 */
	public static Command compute(Params p, double forward, double strafe, double vertical, double pitch, double yaw, double roll,
		boolean hover, boolean level, Quaterniond rotation, Vector3d velocity, Vector3d angularVelocity,
		double mass, double[] inertiaLocal, Vector3d forwardLocal) {
		Vector3d upLocal = new Vector3d(0, 1, 0);
		Vector3d rightLocal = new Vector3d(forwardLocal).cross(upLocal).normalize();
		Vector3d f = rotation.transform(new Vector3d(forwardLocal));
		Vector3d r = rotation.transform(new Vector3d(rightLocal));
		Vector3d u = rotation.transform(new Vector3d(upLocal));

		// ---- linear ----
		double drag = p.thrustAcceleration() / Math.max(0.1, p.maxSpeed());
		double[] inputs = {forward, strafe, vertical};
		Vector3d[] axes = {f, r, u};
		Vector3d accel = new Vector3d();
		boolean idle = forward == 0 && strafe == 0 && vertical == 0;
		for (int i = 0; i < 3; i++) {
			double along = velocity.dot(axes[i]);
			double a;
			if (inputs[i] != 0) {
				a = p.thrustAcceleration() * inputs[i] - drag * along;
			} else if (hover) {
				a = -BRAKE_GAIN * along;
			} else {
				a = -drag * along;
			}
			accel.fma(a, axes[i]);
		}
		if (hover) {
			accel.y += GRAVITY;
		}
		double maxAccel = 3.0 * p.thrustAcceleration() + GRAVITY;
		if (accel.length() > maxAccel) {
			accel.normalize(maxAccel);
		}
		Vector3d force = accel.mul(mass);

		// ---- angular ----
		Vector3d target = new Vector3d();
		target.fma(p.maxTurnRate() * pitch, r);
		target.fma(-p.maxTurnRate() * yaw, u);
		target.fma(p.maxTurnRate() * roll, f);
		if (level && pitch == 0 && roll == 0) {
			// Rotate u towards world up about the axis u x up; the magnitude sin(angle) fades near level.
			Vector3d correction = new Vector3d(u).cross(0, 1, 0);
			if (u.y < 0 && correction.lengthSquared() < 1.0e-6) {
				// Upside down exactly: pick the vessel's forward axis to roll around.
				correction.set(f);
			}
			target.fma(p.levelStrength(), correction);
		}
		double rateGain = p.angularAcceleration() / Math.max(0.05, p.maxTurnRate());
		Vector3d alpha = new Vector3d(target).sub(angularVelocity).mul(rateGain);
		double maxAlpha = 2.0 * p.angularAcceleration() + p.levelStrength();
		if (alpha.length() > maxAlpha) {
			alpha.normalize(maxAlpha);
		}
		Matrix3d rot = new Matrix3d().set(rotation);
		Matrix3d inertia = new Matrix3d(
			inertiaLocal[0], inertiaLocal[3], inertiaLocal[6],
			inertiaLocal[1], inertiaLocal[4], inertiaLocal[7],
			inertiaLocal[2], inertiaLocal[5], inertiaLocal[8]
		);
		Matrix3d worldInertia = new Matrix3d(rot).mul(inertia).mul(new Matrix3d(rot).transpose());
		Vector3d torque = worldInertia.transform(alpha);
		return new Command(force, torque);
	}
}
