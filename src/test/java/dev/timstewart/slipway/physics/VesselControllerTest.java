package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class VesselControllerTest {
	private static final VesselController.Params PARAMS = new VesselController.Params(12.0, 24.0, 1.6, 0.9, 1.5);
	private static final double MASS = 50_000;
	private static final double[] INERTIA = {4.0e5, 0, 0, 0, 6.0e5, 0, 0, 0, 3.0e5};
	/** Forward is north (-Z). */
	private static final Vector3d NORTH = new Vector3d(0, 0, -1);
	/** How the point mass and the ball below are stepped: sixty times a second, velocity first. */
	private static final VesselController.Step SIXTIETH = new VesselController.Step(1.0 / 60.0, 1);

	private static VesselController.Command run(double fwd, double strafe, double vert, double pitch, double yaw, double roll, boolean hover, boolean level,
		Quaterniond rotation, Vector3d v, Vector3d w) {
		return VesselController.compute(PARAMS, fwd, strafe, vert, pitch, yaw, roll, hover, level, rotation, v, w, MASS, INERTIA, NORTH);
	}

	@Test
	void hoverCancelsGravityAtRest() {
		VesselController.Command c = run(0, 0, 0, 0, 0, 0, true, true, new Quaterniond(), new Vector3d(), new Vector3d());
		assertEquals(MASS * VesselController.GRAVITY, c.force().y, 1e-6);
		assertEquals(0.0, c.force().x, 1e-9);
		assertEquals(0.0, c.torque().length(), 1e-9);
	}

	@Test
	void hoverBrakesDriftOnIdleAxes() {
		VesselController.Command c = run(0, 0, 0, 0, 0, 0, true, false, new Quaterniond(), new Vector3d(3, 0, 0), new Vector3d());
		assertTrue(c.force().x < 0, "drift east is braked");
	}

	@Test
	void forwardThrustScalesWithMassAndFollowsTheHull() {
		VesselController.Command level = run(1, 0, 0, 0, 0, 0, false, false, new Quaterniond(), new Vector3d(), new Vector3d());
		assertEquals(-MASS * PARAMS.thrustAcceleration(), level.force().z, 1e-6);
		// Turned 90 degrees about +Y (north becomes west): thrust points west.
		Quaterniond west = new Quaterniond().rotateY(Math.toRadians(90));
		VesselController.Command turned = run(1, 0, 0, 0, 0, 0, false, false, west, new Vector3d(), new Vector3d());
		assertEquals(-MASS * PARAMS.thrustAcceleration(), turned.force().x, 1e-6);
		assertEquals(0.0, turned.force().z, 1e-6);
	}

	@Test
	void dragLimitsSpeed() {
		VesselController.Command c = run(1, 0, 0, 0, 0, 0, false, false, new Quaterniond(), new Vector3d(0, 0, -PARAMS.maxSpeed()), new Vector3d());
		assertEquals(0.0, c.force().z, 1e-6);
	}

	@Test
	void yawInputTurnsAboutTheVesselsUpAxis() {
		VesselController.Command right = run(0, 0, 0, 0, 1, 0, false, false, new Quaterniond(), new Vector3d(), new Vector3d());
		assertTrue(right.torque().y < 0, "turning right is a negative rotation about +Y");
		assertEquals(0.0, right.torque().x, 1e-6);
		assertEquals(0.0, right.torque().z, 1e-6);
	}

	@Test
	void pitchAndRollInputUseTheVesselsOwnAxesUpsideDown() {
		Quaterniond inverted = new Quaterniond().rotateZ(Math.PI);
		VesselController.Command pitchUp = run(0, 0, 0, 1, 0, 0, false, false, inverted, new Vector3d(), new Vector3d());
		// Upside down, the vessel's right axis points west, so nose-up pitch is a negative rotation about +X.
		assertTrue(pitchUp.torque().x < 0);
		VesselController.Command rollRight = run(0, 0, 0, 0, 0, 1, false, false, new Quaterniond(), new Vector3d(), new Vector3d());
		assertTrue(rollRight.torque().z < 0, "rolling right about a north-facing hull is negative about +Z");
	}

	@Test
	void levelModeRightsARolledVessel() {
		Quaterniond rolled = new Quaterniond().rotateZ(Math.toRadians(30));
		VesselController.Command c = run(0, 0, 0, 0, 0, 0, true, true, rolled, new Vector3d(), new Vector3d());
		assertTrue(c.torque().z < 0, "rolled +30 about Z is pushed back");
		VesselController.Command off = run(0, 0, 0, 0, 0, 0, true, false, rolled, new Vector3d(), new Vector3d());
		assertEquals(0.0, off.torque().length(), 1e-6, "level off holds the attitude");
	}

	@Test
	void angularVelocityIsDampedWithoutInput() {
		VesselController.Command c = run(0, 0, 0, 0, 0, 0, false, false, new Quaterniond(), new Vector3d(), new Vector3d(0, 0.5, 0));
		assertTrue(c.torque().y < 0);
	}

	@Test
	void levelModeSettlesQuicklyWithLittleOvershoot() {
		// Integrate the rotation under the controller's torque alone (rigid body, world-space inertia, 60 Hz), starting
		// rolled 30 degrees and pitched 10, and check the vessel is level within three seconds without swinging past.
		Quaterniond q = new Quaterniond().rotateZ(Math.toRadians(30)).rotateX(Math.toRadians(10));
		Vector3d w = new Vector3d();
		double dt = 1.0 / 60.0;
		double worstOvershoot = 0.0;
		double tiltAt3s = Double.NaN;
		for (int step = 0; step < 6 * 60; step++) {
			VesselController.Command c = run(0, 0, 0, 0, 0, 0, true, true, q, new Vector3d(), w);
			org.joml.Matrix3d rot = new org.joml.Matrix3d().set(q);
			org.joml.Matrix3d inertia = new org.joml.Matrix3d(INERTIA[0], INERTIA[3], INERTIA[6], INERTIA[1], INERTIA[4], INERTIA[7], INERTIA[2], INERTIA[5], INERTIA[8]);
			org.joml.Matrix3d worldInertia = new org.joml.Matrix3d(rot).mul(inertia).mul(new org.joml.Matrix3d(rot).transpose());
			Vector3d alpha = new org.joml.Matrix3d(worldInertia).invert().transform(new Vector3d(c.torque()));
			w.fma(dt, alpha);
			Vector3d axis = w.lengthSquared() < 1e-18 ? new Vector3d(0, 1, 0) : new Vector3d(w).normalize();
			Quaterniond spin = new Quaterniond().rotationAxis(w.length() * dt, axis.x, axis.y, axis.z);
			q = spin.mul(q).normalize();
			Vector3d up = q.transform(new Vector3d(0, 1, 0));
			double tilt = Math.toDegrees(Math.acos(Math.min(1.0, up.y)));
			// Overshoot: the up axis crossing to the other side of vertical from where it started.
			Vector3d right = q.transform(new Vector3d(1, 0, 0));
			if (step > 30 && up.x > 0) {
				worstOvershoot = Math.max(worstOvershoot, tilt);
			}
			if (step == 3 * 60) {
				tiltAt3s = tilt;
			}
			assertTrue(Double.isFinite(right.x));
		}
		assertTrue(tiltAt3s < 2.0, "still tilted " + tiltAt3s + " degrees after 3 s");
		assertTrue(worstOvershoot < 3.0, "swung " + worstOvershoot + " degrees past level");
	}

	/** A point mass under the controller's force, gravity and an extra downward load (as a fraction of its weight). */
	private static Vector3d[] hoverFor(double seconds, Vector3d position, Vector3d velocity, VesselController.Hold hold, double forward, double load) {
		double dt = 1.0 / 60.0;
		Vector3d p = new Vector3d(position);
		Vector3d v = new Vector3d(velocity);
		for (int step = 0; step < Math.round(seconds * 60); step++) {
			VesselController.Command c = VesselController.compute(PARAMS, forward, 0, 0, 0, 0, 0, true, true, new Quaterniond(), p, v, new Vector3d(), MASS, INERTIA,
				NORTH, hold, SIXTIETH);
			Vector3d a = new Vector3d(c.force()).div(MASS).add(0, -VesselController.GRAVITY * (1.0 + load), 0);
			v.fma(dt, a);
			p.fma(dt, v);
		}
		return new Vector3d[] {p, v};
	}

	@Test
	void aReleasedHoveringVesselStopsWhereThePlainBrakeStopsIt() {
		Vector3d start = new Vector3d(100, 80, -40);
		Vector3d velocity = new Vector3d(6, -2, 9);
		Vector3d[] braked = hoverFor(6, start, velocity, null, 0, 0);
		// The plain brake a = -w v ends at start + v / w, less one integration step of travel (|v| / 60 = 0.18).
		Vector3d expected = new Vector3d(start).fma(SIXTIETH.stoppingTime(VesselController.BRAKE_GAIN), velocity);
		assertEquals(0.0, braked[0].distance(expected), 0.002, "the plain brake's stopping point");
		// Nothing pushes this vessel: with the hold it is where the plain brake has it, at every moment of the stop.
		for (double seconds : new double[] {0.05, 0.5, 2, 6}) {
			Vector3d[] held = hoverFor(seconds, start, velocity, new VesselController.Hold(), 0, 0);
			Vector3d[] plain = hoverFor(seconds, start, velocity, null, 0, 0);
			assertEquals(0.0, held[0].distance(plain[0]), 1.0e-9, "holding changed where a released vessel is after " + seconds + " s");
			assertEquals(0.0, held[1].distance(plain[1]), 1.0e-9, "holding changed how fast a released vessel is after " + seconds + " s");
		}
	}

	@Test
	void aHoveringVesselHoldsItsPositionUnderALoad() {
		Vector3d start = new Vector3d(100, 80, -40);
		double load = 0.3;
		VesselController.Hold hold = new VesselController.Hold();
		Vector3d[] held = hoverFor(10, start, new Vector3d(), hold, 0, load);
		// The hold is a spring of BRAKE_GAIN^2 per block: under 30% of its weight the vessel sags 0.3 g / 2.25 = 1.31 blocks.
		double sag = load * VesselController.GRAVITY / (VesselController.BRAKE_GAIN * VesselController.BRAKE_GAIN);
		assertEquals(80 - sag, held[0].y, 0.01, "height under the load");
		assertEquals(0.0, held[1].length(), 0.001, "the loaded vessel still sinks");
		assertTrue(hold.isValid() && Math.abs(hold.target().y - 80) < 1.0e-9, "the hold point moved: " + hold.target());
		// Without the hold the brake alone only limits the sink rate to load * g / w = 1.96 m/s.
		Vector3d[] braked = hoverFor(10, start, new Vector3d(), null, 0, load);
		assertEquals(-load * VesselController.GRAVITY / VesselController.BRAKE_GAIN, braked[1].y, 0.01, "sink rate with the brake alone");
		assertTrue(braked[0].y < 62, "the unheld vessel sank to " + braked[0].y);
	}

	@Test
	void anOverloadedHoldGivesWayInsteadOfWindingUp() {
		// HOLD_SLACK blocks of spring carry 46% of the vessel's weight; 80% sinks it at a steady rate.
		double load = 0.8;
		VesselController.Hold hold = new VesselController.Hold();
		Vector3d[] early = hoverFor(10, new Vector3d(0, 200, 0), new Vector3d(), hold, 0, load);
		Vector3d[] late = hoverFor(5, early[0], early[1], hold, 0, load);
		double w = VesselController.BRAKE_GAIN;
		// At the limit the hold pulls with w^2 (HOLD_SLACK + 2 t rate), t the brake's stopping time, and the brake with w rate.
		double expectedRate = (load * VesselController.GRAVITY - w * w * VesselController.HOLD_SLACK) / (w + 2 * w * w * SIXTIETH.stoppingTime(w));
		assertEquals(-expectedRate, late[1].y, 0.02, "steady sink rate of an overloaded vessel");
		assertEquals(early[1].y, late[1].y, 0.01, "the sink rate still changes");
		// The hold point followed: when the load goes, the vessel settles within the slack and its braking distance.
		Vector3d[] freed = hoverFor(8, late[0], late[1], hold, 0, 0);
		assertEquals(0.0, freed[1].length(), 0.01, "the freed vessel still moves");
		assertTrue(Math.abs(freed[0].y - late[0].y) < VesselController.HOLD_SLACK + 0.5, "the freed vessel sprang back " + (freed[0].y - late[0].y) + " blocks");
	}

	@Test
	void thrustTakesTheHoldPointAlong() {
		VesselController.Hold hold = new VesselController.Hold();
		Vector3d start = new Vector3d(0, 100, 0);
		Vector3d[] flying = hoverFor(4, start, new Vector3d(), hold, 1.0, 0);
		assertTrue(flying[0].z < -40, "four seconds of full thrust north only reached z " + flying[0].z);
		Vector3d[] released = hoverFor(6, flying[0], flying[1], hold, 0, 0);
		// After release the vessel coasts on as far as the brake takes to stop it and stays there: it is not pulled back to the start.
		assertEquals(flying[0].z + flying[1].z * SIXTIETH.stoppingTime(VesselController.BRAKE_GAIN), released[0].z, 0.01, "stopping point after releasing the thrust");
		assertEquals(0.0, released[1].length(), 0.01, "still moving six seconds after releasing the thrust");
		assertEquals(100.0, released[0].y, 0.01, "height drifted while thrusting north");
		assertEquals(0.0, released[0].x, 0.01, "drifted sideways while thrusting north");
	}

	@Test
	void hoverOffForgetsTheHoldPoint() {
		VesselController.Hold hold = new VesselController.Hold();
		hoverFor(1, new Vector3d(0, 100, 0), new Vector3d(), hold, 0, 0);
		assertTrue(hold.isValid());
		VesselController.compute(PARAMS, 0, 0, 0, 0, 0, 0, false, true, new Quaterniond(), new Vector3d(0, 50, 0), new Vector3d(), new Vector3d(), MASS, INERTIA, NORTH, hold,
			SIXTIETH);
		assertFalse(hold.isValid(), "hover off must drop the hold point, or the vessel would spring back to it when hover returns");
	}

	/** A rigid body turning under the controller's torque and an extra torque (as angular acceleration about world +Z). */
	private static Object[] turnFor(double seconds, Quaterniond rotation, Vector3d rate, VesselController.Hold hold, boolean level, double roll, double pushed) {
		double dt = 1.0 / 60.0;
		Quaterniond q = new Quaterniond(rotation);
		Vector3d w = new Vector3d(rate);
		// A ball of inertia, so the torque maps to the same acceleration at any attitude.
		double[] inertia = {4.0e5, 0, 0, 0, 4.0e5, 0, 0, 0, 4.0e5};
		for (int step = 0; step < Math.round(seconds * 60); step++) {
			VesselController.Command c = VesselController.compute(PARAMS, 0, 0, 0, 0, 0, roll, true, level, q, new Vector3d(), new Vector3d(), w, MASS, inertia, NORTH, hold,
				SIXTIETH);
			Vector3d alpha = new Vector3d(c.torque()).div(4.0e5).add(0, 0, pushed);
			w.fma(dt, alpha);
			if (w.length() > 1.0e-12) {
				Vector3d axis = new Vector3d(w).normalize();
				q = new Quaterniond().rotationAxis(w.length() * dt, axis.x, axis.y, axis.z).mul(q).normalize();
			}
		}
		return new Object[] {q, w};
	}

	private static double rollDegrees(Quaterniond q) {
		Vector3d up = q.transform(new Vector3d(0, 1, 0));
		return Math.toDegrees(Math.atan2(-up.x, up.y));
	}

	@Test
	void levelOffHoldsTheAttitudeAgainstATorque() {
		// Cargo lying off centre turns the vessel with 0.2 rad/s^2. The rate brake alone only limits the turn rate to
		// 0.2 / 3 rad/s: the vessel keeps rolling. The hold stops it 0.2 / 9 rad = 1.3 degrees from where it was.
		Quaterniond start = new Quaterniond().rotateZ(Math.toRadians(25));
		Object[] braked = turnFor(10, start, new Vector3d(), null, false, 0, 0.2);
		assertEquals(25 + Math.toDegrees(0.2 / 3 * 10), rollDegrees((Quaterniond)braked[0]), 2.0, "roll after ten seconds with the rate brake alone");
		VesselController.Hold hold = new VesselController.Hold();
		Object[] held = turnFor(10, start, new Vector3d(), hold, false, 0, 0.2);
		assertEquals(25 + Math.toDegrees(0.2 / 9), rollDegrees((Quaterniond)held[0]), 0.05, "roll held against the torque");
		assertEquals(0.0, ((Vector3d)held[1]).length(), 1.0e-4, "the held vessel still turns");
		// With level on nothing is held here: levelling rights the vessel against the torque (0.2 / 4.5 rad from level).
		Object[] levelled = turnFor(10, start, new Vector3d(), hold, true, 0, 0.2);
		assertEquals(Math.toDegrees(Math.asin(0.2 / 4.5)), rollDegrees((Quaterniond)levelled[0]), 0.2, "roll with level on under the torque");
	}

	@Test
	void aReleasedTurnStopsWhereTheRateBrakeStopsIt() {
		Vector3d rate = new Vector3d(0.3, -0.5, 0.6);
		Quaterniond start = new Quaterniond().rotateXYZ(0.3, 1.1, -0.4);
		Object[] braked = turnFor(5, start, rate, null, false, 0, 0);
		Object[] held = turnFor(5, start, rate, new VesselController.Hold(), false, 0, 0);
		Quaterniond difference = new Quaterniond((Quaterniond)held[0]).mul(new Quaterniond((Quaterniond)braked[0]).conjugate());
		// The vessel turns on by about rate / 3 = 16 degrees, to the same attitude with the hold as without.
		assertEquals(0.0, Math.toDegrees(2 * Math.asin(Math.min(1.0, Math.sqrt(difference.x * difference.x + difference.y * difference.y + difference.z * difference.z)))),
			1.0e-6, "holding changed the attitude a released vessel stops in (degrees)");
		assertEquals(0.0, ((Vector3d)held[1]).length(), 1.0e-3);
	}

	@Test
	void rollInputTakesTheHeldAttitudeAlong() {
		VesselController.Hold hold = new VesselController.Hold();
		Object[] rolling = turnFor(3, new Quaterniond(), new Vector3d(), hold, false, 1.0, 0);
		Object[] released = turnFor(4, (Quaterniond)rolling[0], (Vector3d)rolling[1], hold, false, 0, 0);
		double rolled = rollDegrees((Quaterniond)released[0]);
		// Three seconds at up to 0.9 rad/s and the coast after release: well past 100 degrees, and it stays there.
		assertTrue(Math.abs(rolled) > 100, "rolled only " + rolled + " degrees");
		Object[] later = turnFor(3, (Quaterniond)released[0], (Vector3d)released[1], hold, false, 0, 0);
		assertEquals(rolled, rollDegrees((Quaterniond)later[0]), 0.05, "the vessel turned back after the roll input ended");
	}

	/** A body that records what is applied to it. */
	private static final class RecordingEngine implements PhysicsEngine {
		final PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
		int applied;
		Vector3d force = new Vector3d();
		Vector3d torque = new Vector3d();

		@Override
		public void applyForceAndTorque(long vesselId, Vector3d force, Vector3d torque) {
			this.applied++;
			this.force = new Vector3d(force);
			this.torque = new Vector3d(torque);
		}

		@Override
		public boolean readVessel(long vesselId, BodyState out) {
			out.copyFrom(this.state);
			return true;
		}

		@Override
		public void setVesselShape(long vesselId, BoxList boxes, dev.timstewart.slipway.math.VesselPose pose, Vector3d linearVelocity, Vector3d angularVelocity) {
		}

		@Override
		public void removeVessel(long vesselId) {
		}

		@Override
		public boolean hasVessel(long vesselId) {
			return true;
		}

		@Override
		public void teleportVessel(long vesselId, dev.timstewart.slipway.math.VesselPose pose) {
		}

		@Override
		public void setVesselLoose(long vesselId, boolean loose) {
		}

		@Override
		public void setStaticSection(long sectionKey, BoxList boxes, int originX, int originY, int originZ) {
		}

		@Override
		public void removeStaticSection(long sectionKey) {
		}

		@Override
		public boolean hasStaticSection(long sectionKey) {
			return false;
		}

		@Override
		public void step(float seconds, int substeps) {
		}

		@Override
		public int vesselCount() {
			return 1;
		}

		@Override
		public int staticSectionCount() {
			return 0;
		}

		@Override
		public void close() {
		}
	}

	private static VesselController.Drive drive(boolean loose, boolean resetHold, VesselController.Hold hold) {
		return new VesselController.Drive(7L, new VesselController.Axes(1, 1, 1, 1, 1, 1), true, true, loose, resetHold,
			new BoxList.MassProperties(MASS, 0, 0, 0, INERTIA), NORTH, hold);
	}

	@Test
	void aLooseVesselGetsNoForceAndNoTorque() {
		RecordingEngine engine = new RecordingEngine();
		// Falling, drifting, tumbling and tilted, with every helm axis held and hover and level on.
		engine.state.vx = 5;
		engine.state.vy = -20;
		engine.state.wx = 2;
		engine.state.wz = -1;
		engine.state.qz = Math.sin(0.4);
		engine.state.qw = Math.cos(0.4);
		VesselController.Hold hold = new VesselController.Hold();
		assertTrue(VesselController.drive(engine, PARAMS, PhysicsWorld.STEP, drive(false, false, hold), new PhysicsEngine.BodyState()), "a controlled vessel is driven");
		assertEquals(1, engine.applied);
		assertTrue(engine.force.length() > MASS && engine.torque.length() > 1000, "the controlled vessel got a force and a torque");
		assertTrue(hold.isValid());

		assertFalse(VesselController.drive(engine, PARAMS, PhysicsWorld.STEP, drive(true, false, hold), new PhysicsEngine.BodyState()), "a loose vessel was driven");
		assertEquals(1, engine.applied, "something was applied to a loose vessel");
		assertFalse(hold.isValid(), "a loose vessel keeps no hold point: hover must take a new one when loose ends");
	}

	@Test
	void aTeleportedVesselTakesANewHoldPoint() {
		RecordingEngine engine = new RecordingEngine();
		VesselController.Hold hold = new VesselController.Hold();
		VesselController.Drive idle = new VesselController.Drive(7L, VesselController.Axes.IDLE, true, true, false, false,
			new BoxList.MassProperties(MASS, 0, 0, 0, INERTIA), NORTH, hold);
		engine.state.y = 100;
		VesselController.drive(engine, PARAMS, PhysicsWorld.STEP, idle, new PhysicsEngine.BodyState());
		assertEquals(100.0, hold.target().y, 1.0e-9);
		// Moved 50 blocks up at once: without a reset the hold would pull it back as far as its slack allows.
		engine.state.y = 150;
		VesselController.drive(engine, PARAMS, PhysicsWorld.STEP, new VesselController.Drive(7L, VesselController.Axes.IDLE, true, true, false, true,
			new BoxList.MassProperties(MASS, 0, 0, 0, INERTIA), NORTH, hold), new PhysicsEngine.BodyState());
		assertEquals(150.0, hold.target().y, 1.0e-9, "the hold point after a teleport");
		assertEquals(MASS * VesselController.GRAVITY, engine.force.y, 1.0e-6, "a teleported vessel at rest only needs its weight carried");
	}

	@Test
	void outputsAreFiniteAndBounded() {
		VesselController.Command c = run(1, 1, 1, 1, 1, 1, true, true, new Quaterniond().rotateXYZ(1, 2, 3), new Vector3d(500, -500, 500),
			new Vector3d(40, -40, 40));
		assertTrue(c.isFinite());
		assertTrue(c.force().length() <= MASS * (3 * PARAMS.thrustAcceleration() + VesselController.GRAVITY) + 1e-6);
	}
}
