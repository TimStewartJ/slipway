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
	void outputsAreFiniteAndBounded() {
		VesselController.Command c = run(1, 1, 1, 1, 1, 1, true, true, new Quaterniond().rotateXYZ(1, 2, 3), new Vector3d(500, -500, 500),
			new Vector3d(40, -40, 40));
		assertTrue(c.isFinite());
		assertTrue(c.force().length() <= MASS * (3 * PARAMS.thrustAcceleration() + VesselController.GRAVITY) + 1e-6);
	}
}
