package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

	@Test
	void outputsAreFiniteAndBounded() {
		VesselController.Command c = run(1, 1, 1, 1, 1, 1, true, true, new Quaterniond().rotateXYZ(1, 2, 3), new Vector3d(500, -500, 500),
			new Vector3d(40, -40, 40));
		assertTrue(c.isFinite());
		assertTrue(c.force().length() <= MASS * (3 * PARAMS.thrustAcceleration() + VesselController.GRAVITY) + 1e-6);
	}
}
