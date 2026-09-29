package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stephengold.joltjni.Jolt;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.jolt.JoltEngine;
import dev.timstewart.slipway.physics.jolt.JoltRuntime;
import java.nio.file.Path;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JoltEngineTest {
	private static final double BASE_X = 24_002_048;
	private static final double BASE_Z = 24_002_048;

	@BeforeAll
	static void loadNatives() {
		JoltRuntime.ensureLoaded(Path.of(System.getProperty("slipway.test.nativeCache", "build/tmp/test-natives")));
	}

	/** A 5x1x3 plank deck with a 1x2x1 mast, in local block coordinates. */
	static BoxList deck() {
		BoxList boxes = new BoxList();
		boxes.add(-2, 0, -1, 3, 1, 2, 700f);
		boxes.add(0, 1, 0, 1, 3, 1, 700f);
		return boxes;
	}

	static BoxList floorSection() {
		BoxList floor = new BoxList();
		floor.add(0, 0, 0, 16, 4, 16, 2400f);
		return floor;
	}

	@Test
	void aVesselFallsOntoTerrainAndComesToRest() {
		try (JoltEngine engine = new JoltEngine(1)) {
			// Terrain section with a solid floor whose top is at y = 68.
			engine.setStaticSection(1L, floorSection(), (int)BASE_X - 8, 64, (int)BASE_Z - 8);
			engine.setVesselShape(7L, deck(), VesselPose.at(BASE_X, 80, BASE_Z), new Vector3d(), new Vector3d());
			PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
			for (int i = 0; i < 100; i++) {
				engine.step(0.05f, 3);
			}
			assertTrue(engine.readVessel(7L, state));
			assertTrue(state.isFinite());
			// The deck's bottom face (local y = 0) rests on y = 68.
			assertEquals(68.0, state.y, 0.06);
			assertEquals(BASE_X, state.x, 0.1);
			assertEquals(0.0, Math.hypot(state.vx, Math.hypot(state.vy, state.vz)), 0.05);
			assertEquals(1, engine.staticSectionCount());
			assertEquals(1, engine.vesselCount());
		}
	}

	@Test
	void replacingTheShapeKeepsTheReferencePose() {
		try (JoltEngine engine = new JoltEngine(1)) {
			VesselPose pose = VesselPose.fromYawPitchRoll(BASE_X, 200, BASE_Z, 30, 10, -20);
			engine.setVesselShape(3L, deck(), pose, new Vector3d(), new Vector3d());
			PhysicsEngine.BodyState before = new PhysicsEngine.BodyState();
			engine.readVessel(3L, before);
			BoxList bigger = deck();
			bigger.add(3, 0, -1, 9, 1, 2, 2400f);
			engine.setVesselShape(3L, bigger, pose, new Vector3d(), new Vector3d());
			PhysicsEngine.BodyState after = new PhysicsEngine.BodyState();
			engine.readVessel(3L, after);
			assertEquals(before.x, after.x, 1e-6);
			assertEquals(before.y, after.y, 1e-6);
			assertEquals(before.z, after.z, 1e-6);
			assertEquals(pose.x(), after.x, 1e-6);
		}
	}

	@Test
	void teleportMovesAndStops() {
		try (JoltEngine engine = new JoltEngine(1)) {
			engine.setVesselShape(1L, deck(), VesselPose.at(BASE_X, 100, BASE_Z), new Vector3d(0, 5, 0), new Vector3d(0, 1, 0));
			VesselPose target = VesselPose.fromYawPitchRoll(BASE_X + 50, 120, BASE_Z - 50, 180, 0, 90);
			engine.teleportVessel(1L, target);
			PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
			engine.readVessel(1L, state);
			assertEquals(target.x(), state.x, 1e-6);
			assertEquals(0.0, state.vy, 1e-9);
			assertEquals(Math.abs(target.qw()), Math.abs(state.qw), 1e-5);
		}
	}

	@Test
	void forceAndTorqueAccelerateTheBody() {
		try (JoltEngine engine = new JoltEngine(1)) {
			BoxList boxes = deck();
			engine.setVesselShape(2L, boxes, VesselPose.at(BASE_X, 300, BASE_Z), new Vector3d(), new Vector3d());
			double mass = boxes.massProperties().mass();
			// Cancel gravity and push north at 2 m/s^2 for one second.
			for (int i = 0; i < 20; i++) {
				engine.applyForceAndTorque(2L, new Vector3d(0, mass * 9.81, -mass * 2.0), new Vector3d(0, 0, 0));
				engine.step(0.05f, 3);
			}
			PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
			engine.readVessel(2L, state);
			assertEquals(-2.0, state.vz, 0.05);
			assertEquals(0.0, state.vy, 0.05);
		}
	}

	/** The deck-walk test ship: a 13x13 plank deck with a helm in the middle, a two-block mast and a lamp off-centre. */
	static BoxList asymmetricShip() {
		BoxList boxes = new BoxList();
		boxes.add(-6, -1, -6, 7, 0, 7, 600f);
		boxes.add(0, 0, 0, 1, 1, 1, 800f);
		boxes.add(0, 0, 5, 1, 2, 6, 700f);
		boxes.add(5, 0, 5, 6, 1, 6, 1200f);
		return boxes;
	}

	/** Flies a vessel with the real controller for some steps; returns its final state. */
	static PhysicsEngine.BodyState fly(JoltEngine engine, long id, BoxList boxes, int steps, double forward, double pitch, double yaw, double roll, boolean level) {
		VesselController.Params params = new VesselController.Params(12.0, 24.0, 1.6, 0.9, 1.5);
		BoxList.MassProperties mass = boxes.massProperties();
		PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
		for (int i = 0; i < steps; i++) {
			engine.readVessel(id, state);
			VesselController.Command command = VesselController.compute(params, forward, 0, 0, pitch, yaw, roll, true, level,
				new org.joml.Quaterniond(state.qx, state.qy, state.qz, state.qw), new Vector3d(state.vx, state.vy, state.vz),
				new Vector3d(state.wx, state.wy, state.wz), mass.mass(), mass.inertia(), new Vector3d(0, 0, -1));
			engine.applyForceAndTorque(id, command.force(), command.torque());
			engine.step(0.05f, 3);
		}
		engine.readVessel(id, state);
		return state;
	}

	private static double tiltDegrees(PhysicsEngine.BodyState state) {
		return VesselPose.of(0, 0, 0, new org.joml.Quaterniond(state.qx, state.qy, state.qz, state.qw)).tiltDegrees();
	}

	@Test
	void yawInputTurnsAnAsymmetricShipAboutTheVerticalWithLevelOff() {
		try (JoltEngine engine = new JoltEngine(1)) {
			BoxList boxes = asymmetricShip();
			engine.setVesselShape(11L, boxes, VesselPose.at(BASE_X, 300, BASE_Z), new Vector3d(), new Vector3d());
			PhysicsEngine.BodyState state = fly(engine, 11L, boxes, 60, 0.5, 0, 1, 0, false);
			double heading = VesselPose.of(0, 0, 0, new org.joml.Quaterniond(state.qx, state.qy, state.qz, state.qw)).headingDegrees();
			assertTrue(Math.abs(heading) > 20, "the ship turned: heading " + heading);
			assertTrue(tiltDegrees(state) < 2.0, "yaw alone must not bank or pitch the ship: tilt " + tiltDegrees(state));
		}
	}

	@Test
	void anAsymmetricShipHoldsItsBankWhileTurningWithLevelOff() {
		try (JoltEngine engine = new JoltEngine(1)) {
			BoxList boxes = asymmetricShip();
			engine.setVesselShape(12L, boxes, VesselPose.fromYawPitchRoll(BASE_X, 300, BASE_Z, 0, 0, 45), new Vector3d(), new Vector3d());
			PhysicsEngine.BodyState state = fly(engine, 12L, boxes, 100, 0.4, 0, 0.6, 0, false);
			assertEquals(45.0, tiltDegrees(state), 3.0, "the bank is held while turning");
		}
	}

	@Test
	void nonFiniteForcesAreIgnored() {
		try (JoltEngine engine = new JoltEngine(1)) {
			engine.setVesselShape(5L, deck(), VesselPose.at(BASE_X, 300, BASE_Z), new Vector3d(), new Vector3d());
			engine.applyForceAndTorque(5L, new Vector3d(Double.NaN, 0, 0), new Vector3d());
			engine.applyForceAndTorque(5L, new Vector3d(), new Vector3d(0, Double.POSITIVE_INFINITY, 0));
			engine.step(0.05f, 3);
			PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
			engine.readVessel(5L, state);
			assertTrue(state.isFinite());
		}
	}

	@Test
	void emptyShapesRemoveBodies() {
		try (JoltEngine engine = new JoltEngine(1)) {
			engine.setVesselShape(9L, deck(), VesselPose.at(BASE_X, 100, BASE_Z), new Vector3d(), new Vector3d());
			assertTrue(engine.hasVessel(9L));
			engine.setVesselShape(9L, new BoxList(), VesselPose.at(BASE_X, 100, BASE_Z), new Vector3d(), new Vector3d());
			assertFalse(engine.hasVessel(9L));
			engine.setStaticSection(4L, new BoxList(), 0, 0, 0);
			assertFalse(engine.hasStaticSection(4L));
		}
	}

	/**
	 * Leak test: with the Debug natives jolt-jni counts every native allocation its glue code makes. A full
	 * engine lifecycle (vessels and terrain added, simulated, reshaped, removed, engine closed), repeated, must
	 * free exactly what it allocated.
	 */
	@Test
	void anEngineLifecycleFreesEveryNativeObject() {
		org.junit.jupiter.api.Assumptions.assumeTrue("Debug".equals(Jolt.buildType()), "allocation counters need the Debug natives");
		// Warm up once so lazily created statics are not counted.
		lifecycle();
		int news = Jolt.countNews();
		int deletes = Jolt.countDeletes();
		for (int round = 0; round < 3; round++) {
			lifecycle();
		}
		int allocated = Jolt.countNews() - news;
		int freed = Jolt.countDeletes() - deletes;
		assertTrue(allocated > 0, "the counters work");
		assertEquals(allocated, freed, "native objects leaked: " + (allocated - freed));
	}

	private static void lifecycle() {
		try (JoltEngine engine = new JoltEngine(2)) {
			for (int s = 0; s < 4; s++) {
				engine.setStaticSection(s, floorSection(), (int)BASE_X - 32 + s * 16, 64, (int)BASE_Z - 8);
			}
			for (long v = 1; v <= 3; v++) {
				engine.setVesselShape(v, deck(), VesselPose.at(BASE_X + v * 6, 72 + v, BASE_Z), new Vector3d(), new Vector3d());
			}
			PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
			for (int i = 0; i < 30; i++) {
				engine.applyForceAndTorque(1L, new Vector3d(100, 0, 0), new Vector3d(0, 10, 0));
				engine.step(0.05f, 3);
				engine.readVessel(2L, state);
			}
			BoxList bigger = deck();
			bigger.add(3, 0, -1, 6, 1, 2, 700f);
			engine.setVesselShape(2L, bigger, VesselPose.at(BASE_X, 90, BASE_Z), new Vector3d(), new Vector3d());
			engine.teleportVessel(3L, VesselPose.fromYawPitchRoll(BASE_X, 100, BASE_Z, 45, 45, 45));
			engine.removeVessel(1L);
			engine.removeStaticSection(0L);
			engine.step(0.05f, 3);
		}
	}
}
