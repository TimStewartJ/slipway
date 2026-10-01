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
 * inertia), a proportional rate controller and a spring and damper for the hold, stepped as the engine steps.
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
	 * How the engine advances a body from one call of the controller to the next: in {@code substeps} equal parts of
	 * {@code seconds}, each adding the acceleration to the velocity first and the new velocity to the position after
	 * (and the same for turning), under the force and torque of that call throughout. The hold needs it to know where
	 * the plain brake alone takes the vessel in the step.
	 */
	public record Step(double seconds, int substeps) {
		public Step {
			if (!(seconds > 0) || substeps < 1) {
				throw new IllegalArgumentException("a step takes time and has at least one part");
			}
		}

		/** A constant acceleration {@code a} moves a body with velocity {@code v} by {@code v seconds + travel() a seconds^2}. */
		double travel() {
			return (this.substeps + 1) / (2.0 * this.substeps);
		}

		/**
		 * How far a body braked by {@code a = -gain v} still goes, per unit of its speed: {@code 1 / gain} in theory,
		 * less {@code travel() seconds} for the stepping.
		 */
		double stoppingTime(double gain) {
			return Math.max(0.0, 1.0 / gain - this.travel() * this.seconds);
		}
	}

	/**
	 * Where a hovering vessel holds its position: the point the brake would bring its centre of mass to rest at. (The
	 * centre of mass is the point the velocity belongs to and the one a turn leaves in place: held by any other
	 * point, a ship whose helm is not at its centre is pulled round that point when it turns.) Along an idle axis the
	 * point stays, and the vessel is pulled to it by a spring on top of the brake,
	 * {@code a = -w v - w^2 (e + t v)}, with {@code w = BRAKE_GAIN}, {@code e} the distance from the point and
	 * {@code t} the brake's stopping time ({@link Step#stoppingTime}). A vessel that nothing pushes has
	 * {@code e = -t v}, where that is the plain brake {@code a = -w v}; a pushed or loaded one comes back instead of
	 * drifting or sinking for as long as the push lasts. Along an axis with input nothing is held: the point is put
	 * where the brake would stop the vessel now. At the end of every call the point is moved by what the plain
	 * controller's own acceleration changes about where the vessel will stop ({@code seconds (v + a / w)}: nothing
	 * along an idle axis, the vessel's travel along one with input, also while the axes turn). So with nothing pushing
	 * it, a vessel flies, turns and stops step for step as under the plain brake, and the hold only ever answers a
	 * push. The distance is limited to {@link #HOLD_SLACK} (plus the braking distance at the current speed): pushed
	 * further, the point gives way.
	 *
	 * <p>With level off a hovering vessel holds its attitude the same way: the attitude the turn-rate brake would
	 * leave it in, held about an idle axis by {@code alpha = -k w - k^2 (e + t w)} with {@code k = RATE_GAIN}, let go
	 * about an axis with input, and turned along at the end of every call as the plain rate controller's torque turns
	 * the attitude the vessel will come to rest in. With level on, levelling holds pitch and roll, and nothing is held
	 * here.
	 *
	 * <p>One instance belongs to one vessel and is only touched by the thread that steps the physics.
	 */
	public static final class Hold {
		final Vector3d target = new Vector3d();
		boolean valid;
		/** The centre of mass in the vessel's frame when the hold was last used: it shifts when blocks change. */
		final Vector3d centreLocal = new Vector3d();
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

		boolean isFinite() {
			return this.target.isFinite() && this.attitude.isFinite();
		}

		public Vector3d target() {
			return new Vector3d(this.target);
		}
	}

	/**
	 * Everything needed to control one vessel for one step: built on the game thread, used by the physics thread.
	 * Without a hold ({@code null}) a hovering vessel is only braked, as before 0.1.2.
	 */
	public record Drive(long vesselId, Axes axes, boolean hover, boolean level, boolean loose, boolean resetHold, BoxList.MassProperties mass,
		Vector3d forwardLocal, @Nullable Hold hold) {
	}

	/**
	 * Applies one step of control to a vessel's body. A loose vessel gets no force and no torque (no hover, no
	 * levelling, no drag, no spin brake, no thrust, whatever the helm says).
	 *
	 * @return true when a force and torque were applied
	 */
	public static boolean drive(PhysicsEngine engine, Params params, Step step, Drive drive, PhysicsEngine.BodyState scratch) {
		Hold hold = drive.hold();
		if (hold != null && (drive.resetHold() || drive.loose())) {
			hold.reset();
		}
		if (drive.loose() || !engine.readVessel(drive.vesselId(), scratch) || !scratch.isFinite()) {
			return false;
		}
		Axes in = drive.axes();
		Quaterniond rotation = new Quaterniond(scratch.qx, scratch.qy, scratch.qz, scratch.qw);
		Vector3d centreLocal = new Vector3d(drive.mass().comX(), drive.mass().comY(), drive.mass().comZ());
		if (hold != null) {
			if (hold.valid && !centreLocal.equals(hold.centreLocal)) {
				// Blocks changed and the centre of mass with them. The vessel has not moved, so what is held moves along
				// with the centre. (Taking the hold anew instead would let go of a load: the vessel's present place
				// would become the point, and a loaded vessel would sink by its sag at every block change.)
				hold.target.add(rotation.transform(new Vector3d(centreLocal).sub(hold.centreLocal)));
			}
			hold.centreLocal.set(centreLocal);
		}
		Vector3d centre = rotation.transform(centreLocal).add(scratch.x, scratch.y, scratch.z);
		Command command = compute(params, in.forward(), in.strafe(), in.vertical(), in.pitch(), in.yaw(), in.roll(), drive.hover(), drive.level(),
			rotation, centre, new Vector3d(scratch.vx, scratch.vy, scratch.vz), new Vector3d(scratch.wx, scratch.wy, scratch.wz),
			drive.mass().mass(), drive.mass().inertia(), drive.forwardLocal(), hold, step);
		if (hold != null && !(command.isFinite() && hold.isFinite())) {
			hold.reset();
		}
		if (!command.isFinite()) {
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
			forwardLocal, null, null);
	}

	/**
	 * @param position world position of the vessel's centre of mass (the point {@code velocity} is the velocity of), only used for the hold
	 * @param forwardLocal unit vector of the vessel's forward direction in local coordinates (horizontal)
	 * @param inertiaLocal row-major inertia tensor about the centre of mass in local axes
	 * @param hold the vessel's hold point, updated by this call; null for braking without holding
	 * @param step how the engine will advance the vessel under the returned force and torque; only used for the hold
	 */
	public static Command compute(Params p, double forward, double strafe, double vertical, double pitch, double yaw, double roll,
		boolean hover, boolean level, Quaterniond rotation, Vector3d position, Vector3d velocity, Vector3d angularVelocity,
		double mass, double[] inertiaLocal, Vector3d forwardLocal, @Nullable Hold hold, @Nullable Step step) {
		if (hold != null && step == null) {
			throw new IllegalArgumentException("holding needs the step");
		}
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
		// What the plain controller asks for: the same, unless something has pushed the vessel off its way.
		Vector3d plain = new Vector3d();
		Vector3d error = null;
		double stopping = 0;
		if (hold != null) {
			if (!hover) {
				hold.valid = false;
			} else {
				stopping = step.stoppingTime(BRAKE_GAIN);
				if (!hold.valid) {
					hold.target.set(position).fma(stopping, velocity);
					hold.valid = true;
				}
				error = new Vector3d(position).sub(hold.target);
			}
		}
		for (int i = 0; i < 3; i++) {
			double along = velocity.dot(axes[i]);
			double a;
			double b;
			if (inputs[i] != 0) {
				b = p.thrustAcceleration() * inputs[i] - drag * along;
				a = b;
				if (error != null) {
					hold.target.fma(error.dot(axes[i]) + stopping * along, axes[i]);
				}
			} else if (error != null) {
				double e = error.dot(axes[i]);
				double limit = HOLD_SLACK + stopping * Math.abs(along);
				double held = Math.max(-limit, Math.min(limit, e));
				if (held != e) {
					hold.target.fma(e - held, axes[i]);
				}
				b = -BRAKE_GAIN * along;
				a = b - BRAKE_GAIN * BRAKE_GAIN * (held + stopping * along);
			} else {
				b = -(hover ? BRAKE_GAIN : drag) * along;
				a = b;
			}
			accel.fma(a, axes[i]);
			plain.fma(b, axes[i]);
		}
		double maxAccel = 3.0 * p.thrustAcceleration() + GRAVITY;
		if (hover) {
			accel.y += GRAVITY;
		}
		if (accel.length() > maxAccel) {
			accel.normalize(maxAccel);
		}
		if (error != null) {
			// Under the plain controller alone the place where the vessel will stop changes by this much: the point goes along.
			plain.y += GRAVITY;
			if (plain.length() > maxAccel) {
				plain.normalize(maxAccel);
			}
			plain.y -= GRAVITY;
			hold.target.fma(step.seconds(), velocity).fma(step.seconds() / BRAKE_GAIN, plain);
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
		Vector3d plainAlpha = null;
		if (hold != null) {
			if (!hover || level) {
				hold.attitudeValid = false;
			} else {
				plainAlpha = new Vector3d(alpha);
				holdAttitude(hold, rotation, angularVelocity, new Vector3d[] {r, u, f}, new double[] {pitch, yaw, roll}, alpha, step.stoppingTime(RATE_GAIN));
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
		if (plainAlpha != null) {
			if (plainAlpha.length() > maxAlpha) {
				plainAlpha.normalize(maxAlpha);
			}
			turnHeldAttitude(hold, rotation, angularVelocity, worldInertia.transform(plainAlpha), inertia, step);
		}
		return new Command(force, torque);
	}

	/**
	 * The attitude hold of a hovering vessel with level off (see {@link Hold}): about every idle axis, adds the hold's
	 * part to the rate brake's {@code alpha}, and lets go of the held attitude about every axis with input.
	 *
	 * @param stopping the rate brake's stopping time
	 */
	private static void holdAttitude(Hold hold, Quaterniond rotation, Vector3d angularVelocity, Vector3d[] axes, double[] inputs, Vector3d alpha,
		double stopping) {
		if (!hold.attitudeValid) {
			hold.attitude.set(restAttitude(rotation, angularVelocity, stopping));
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
				held = -stopping * rate;
			} else {
				double limit = ATTITUDE_SLACK + stopping * Math.abs(rate);
				held = Math.max(-limit, Math.min(limit, e));
				alpha.fma(-RATE_GAIN * RATE_GAIN * (held + stopping * rate), axes[i]);
			}
			if (held != e) {
				hold.attitude.premul(new Quaterniond().rotationAxis(e - held, axes[i].x, axes[i].y, axes[i].z));
			}
		}
		hold.attitude.normalize();
	}

	/** The attitude the rate brake leaves a vessel in that has this attitude and turn rate now. */
	private static Quaterniond restAttitude(Quaterniond rotation, Vector3d angularVelocity, double stopping) {
		Quaterniond rest = new Quaterniond(rotation);
		double rate = angularVelocity.length();
		if (rate > 1.0e-12) {
			rest.premul(new Quaterniond().rotationAxis(rate * stopping, angularVelocity.x / rate, angularVelocity.y / rate, angularVelocity.z / rate));
		}
		return rest;
	}

	/**
	 * Turns the held attitude as the plain rate controller's torque turns the attitude the vessel will come to rest
	 * in during this step, so that the hold takes none of that turning for a push. Steps as the engine does: the
	 * torque stays as it is in the world while the vessel, and its inertia with it, turns through the parts of the
	 * step.
	 */
	private static void turnHeldAttitude(Hold hold, Quaterniond rotation, Vector3d angularVelocity, Vector3d plainTorque, Matrix3d inertiaLocal, Step step) {
		if (!(inertiaLocal.determinant() > 0)) {
			return;
		}
		double stopping = step.stoppingTime(RATE_GAIN);
		Quaterniond before = restAttitude(rotation, angularVelocity, stopping);
		Matrix3d inverse = new Matrix3d(inertiaLocal).invert();
		Quaterniond q = new Quaterniond(rotation);
		Vector3d w = new Vector3d(angularVelocity);
		Vector3d a = new Vector3d();
		double part = step.seconds() / step.substeps();
		for (int s = 0; s < step.substeps(); s++) {
			q.transform(inverse.transform(q.transformInverse(plainTorque, a)));
			w.fma(part, a);
			double rate = w.length();
			if (rate * part > 1.0e-12) {
				q.premul(new Quaterniond().rotationAxis(rate * part, w.x / rate, w.y / rate, w.z / rate)).normalize();
			}
		}
		hold.attitude.premul(restAttitude(q, w, stopping).mul(before.conjugate())).normalize();
	}
}