package dev.timstewart.slipway.physics;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Turns helm input into a force and a torque for one physics step. Forces scale with the vessel's mass and
 * torques with its inertia tensor, so a skiff and a thousand-block galleon accelerate and turn alike.
 *
 * <p>Linear: full input asks for {@code thrustAcceleration} along the vessel's own forward, right and up axes,
 * with drag {@code thrustAcceleration / maxSpeed} per second limiting speed. In hover mode gravity is cancelled
 * and every local axis without input is braked to rest and then held: a released vessel stops where the brake
 * takes it and stays there, also under a load such as loose cargo resting on its deck (see {@link Hold}).
 *
 * <p>Angular: input asks for turn rates about the vessel's own right (pitch), up (yaw) and forward (roll) axes up
 * to {@code maxTurnRate}; a proportional controller on angular velocity, limited to {@code angularAcceleration},
 * produces the torque {@code I * alpha} with the world-space inertia tensor. Without pitch and roll input, level
 * mode adds a rate that rotates the vessel's up axis back towards world up (keeping its heading); with level off
 * the vessel holds whatever attitude it has (while hovering also against a torque, such as cargo lying off its
 * centre; see {@link Hold}). All rotation is quaternion based, so there is no gimbal lock.
 *
 * <p>A loose vessel is not controlled at all: {@link #drive} applies nothing to it, so only gravity and contacts
 * move it.
 *
 * <p>This is our own design using textbook rigid-body relations (F = m a, tau = I alpha, R I R^T for the world
 * inertia), a proportional rate controller and a critically damped position hold.
 */
public final class VesselController {
	/** Per-second rate of hover braking on idle axes: a released vessel loses speed as exp(-BRAKE_GAIN t). */
	public static final double BRAKE_GAIN = 1.5;
	/**
	 * How far (blocks) a load can push a hovering vessel off its hold point before the hold point gives way. The hold
	 * pulls back with BRAKE_GAIN^2 per block, so this carries a load of up to 2.0 * 2.25 / 9.81 = 46% of the vessel's
	 * own weight; anything heavier sinks it slowly.
	 */
	public static final double HOLD_SLACK = 2.0;
	/**
	 * How far (radians, 20 degrees) a torque can turn a hovering vessel with level off away from the attitude it holds
	 * before that attitude gives way. The hold turns back with RATE_GAIN^2 per radian.
	 */
	public static final double ATTITUDE_SLACK = Math.toRadians(20.0);
	/**
	 * Per-second gain of the turn-rate controller. With the default level strength (1.5 per second) the levelling
	 * loop is a damped oscillator with natural frequency sqrt(3 * 1.5) = 2.1 rad/s and damping ratio 0.71: it
	 * settles within about three seconds with a few percent overshoot.
	 */
	public static final double RATE_GAIN = 3.0;
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

	/** The helm's six axes for one step, each in [-1, 1]. */
	public record Axes(double forward, double strafe, double vertical, double pitch, double yaw, double roll) {
		public static final Axes IDLE = new Axes(0, 0, 0, 0, 0, 0);
	}

	/**
	 * Where a hovering vessel holds its position: the point the brake would bring it to rest at. Along an axis with
	 * input the point moves with the vessel (it stays where the brake would stop the vessel if the input ended now);
	 * along an idle axis the vessel is pulled to it by a critically damped spring,
	 * {@code a = -2 w v - w^2 e} with {@code w = BRAKE_GAIN} and {@code e} the distance from the point. Started from
	 * {@code e = -v / w} that is exactly the plain brake {@code a = -w v}, so an unloaded vessel stops as it always
	 * did; a pushed or loaded one comes back instead of drifting or sinking for as long as the push lasts. The
	 * distance is limited to {@link #HOLD_SLACK} (plus the braking distance at the current speed): pushed further,
	 * the point gives way.
	 *
	 * <p>With level off a hovering vessel holds its attitude the same way: the attitude the turn-rate brake would
	 * leave it in, followed about an axis with input and held about an idle one by {@code alpha = -2 k w - k^2 e}
	 * with {@code k = RATE_GAIN}, which from {@code e = -w / k} is exactly the plain rate brake {@code alpha = -k w}.
	 * With level on, levelling holds pitch and roll, and nothing is held here.
	 *
	 * <p>One instance belongs to one vessel and is only touched by the thread that steps the physics.
	 */
	public static final class Hold {
		final Vector3d target = new Vector3d();
		boolean valid;
		final Quaterniond attitude = new Quaterniond();
		boolean attitudeValid;

		/** Forgets what is held; the next hovering step takes it anew (after a teleport, or when hover returns). */
		public void reset() {
			this.valid = false;
			this.attitudeValid = false;
		}

		public boolean isValid() {
			return this.valid;
		}

		public Vector3d target() {
			return new Vector3d(this.target);
		}
	}

	/** Everything needed to control one vessel for one step: built on the game thread, used by the physics thread. */
	public record Drive(long vesselId, Axes axes, boolean hover, boolean level, boolean loose, boolean resetHold, BoxList.MassProperties mass,
		Vector3d forwardLocal, Hold hold) {
	}

	/**
	 * Applies one step of control to a vessel's body. A loose vessel gets no force and no torque (no hover, no
	 * levelling, no drag, no spin brake, no thrust, whatever the helm says).
	 *
	 * @return true when a force and torque were applied
	 */
	public static boolean drive(PhysicsEngine engine, Params params, Drive drive, PhysicsEngine.BodyState scratch) {
		if (drive.resetHold() || drive.loose()) {
			drive.hold().reset();
		}
		if (drive.loose() || !engine.readVessel(drive.vesselId(), scratch) || !scratch.isFinite()) {
			return false;
		}
		Axes in = drive.axes();
		Command command = compute(params, in.forward(), in.strafe(), in.vertical(), in.pitch(), in.yaw(), in.roll(), drive.hover(), drive.level(),
			new Quaterniond(scratch.qx, scratch.qy, scratch.qz, scratch.qw), new Vector3d(scratch.x, scratch.y, scratch.z),
			new Vector3d(scratch.vx, scratch.vy, scratch.vz), new Vector3d(scratch.wx, scratch.wy, scratch.wz),
			drive.mass().mass(), drive.mass().inertia(), drive.forwardLocal(), drive.hold());
		if (!command.isFinite()) {
			drive.hold().reset();
			return false;
		}
		engine.applyForceAndTorque(drive.vesselId(), command.force(), command.torque());
		return true;
	}

	/** {@link #compute} without a position hold: idle axes of a hovering vessel are only braked. */
	public static Command compute(Params p, double forward, double strafe, double vertical, double pitch, double yaw, double roll,
		boolean hover, boolean level, Quaterniond rotation, Vector3d velocity, Vector3d angularVelocity,
		double mass, double[] inertiaLocal, Vector3d forwardLocal) {
		return compute(p, forward, strafe, vertical, pitch, yaw, roll, hover, level, rotation, new Vector3d(), velocity, angularVelocity, mass, inertiaLocal,
			forwardLocal, null);
	}

	/**
	 * @param position world position of the vessel (any fixed point of it), only used for the hold
	 * @param forwardLocal unit vector of the vessel's forward direction in local coordinates (horizontal)
	 * @param inertiaLocal row-major inertia tensor about the centre of mass in local axes
	 * @param hold the vessel's hold point, updated by this call; null for braking without holding
	 */
	public static Command compute(Params p, double forward, double strafe, double vertical, double pitch, double yaw, double roll,
		boolean hover, boolean level, Quaterniond rotation, Vector3d position, Vector3d velocity, Vector3d angularVelocity,
		double mass, double[] inertiaLocal, Vector3d forwardLocal, @Nullable Hold hold) {
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
		Vector3d error = null;
		if (hold != null) {
			if (!hover) {
				hold.valid = false;
			} else {
				if (!hold.valid) {
					hold.target.set(position).fma(1.0 / BRAKE_GAIN, velocity);
					hold.valid = true;
				}
				error = new Vector3d(position).sub(hold.target);
			}
		}
		for (int i = 0; i < 3; i++) {
			double along = velocity.dot(axes[i]);
			double a;
			if (inputs[i] != 0) {
				a = p.thrustAcceleration() * inputs[i] - drag * along;
				if (error != null) {
					hold.target.fma(error.dot(axes[i]) + along / BRAKE_GAIN, axes[i]);
				}
			} else if (error != null) {
				double e = error.dot(axes[i]);
				double limit = HOLD_SLACK + Math.abs(along) / BRAKE_GAIN;
				double held = Math.max(-limit, Math.min(limit, e));
				if (held != e) {
					hold.target.fma(e - held, axes[i]);
				}
				a = -2.0 * BRAKE_GAIN * along - BRAKE_GAIN * BRAKE_GAIN * held;
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
		Vector3d alpha = new Vector3d(target).sub(angularVelocity).mul(RATE_GAIN);
		if (hold != null) {
			if (!hover || level) {
				hold.attitudeValid = false;
			} else {
				holdAttitude(hold, rotation, angularVelocity, new Vector3d[] {r, u, f}, new double[] {pitch, yaw, roll}, alpha);
			}
		}
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

	/**
	 * The attitude hold of a hovering vessel with level off (see {@link Hold}): about every idle axis, replaces the
	 * rate brake's part of {@code alpha} by the hold's, and lets the held attitude follow about every axis with input.
	 */
	private static void holdAttitude(Hold hold, Quaterniond rotation, Vector3d angularVelocity, Vector3d[] axes, double[] inputs, Vector3d alpha) {
		if (!hold.attitudeValid) {
			double rate = angularVelocity.length();
			hold.attitude.set(rotation);
			if (rate > 1.0e-9) {
				hold.attitude.premul(new Quaterniond().rotationAxis(rate / RATE_GAIN, angularVelocity.x / rate, angularVelocity.y / rate, angularVelocity.z / rate));
			}
			hold.attitudeValid = true;
		}
		// The rotation from the held attitude to the current one, as a world-space rotation vector (axis times angle).
		Quaterniond off = new Quaterniond(rotation).mul(new Quaterniond(hold.attitude).conjugate());
		if (off.w < 0) {
			off.set(-off.x, -off.y, -off.z, -off.w);
		}
		double sine = Math.sqrt(off.x * off.x + off.y * off.y + off.z * off.z);
		Vector3d error = new Vector3d();
		if (sine > 1.0e-12) {
			error.set(off.x, off.y, off.z).mul(2.0 * Math.atan2(sine, off.w) / sine);
		}
		for (int i = 0; i < 3; i++) {
			double rate = angularVelocity.dot(axes[i]);
			double e = error.dot(axes[i]);
			double held;
			if (inputs[i] != 0) {
				held = -rate / RATE_GAIN;
			} else {
				double limit = ATTITUDE_SLACK + Math.abs(rate) / RATE_GAIN;
				held = Math.max(-limit, Math.min(limit, e));
				// in place of the rate brake -k w about this axis
				alpha.fma(-RATE_GAIN * rate - RATE_GAIN * RATE_GAIN * held, axes[i]);
			}
			if (held != e) {
				hold.attitude.premul(new Quaterniond().rotationAxis(e - held, axes[i].x, axes[i].y, axes[i].z));
			}
		}
		hold.attitude.normalize();
	}
}