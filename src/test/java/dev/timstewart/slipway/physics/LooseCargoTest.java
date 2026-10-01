package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.jolt.JoltEngine;
import dev.timstewart.slipway.physics.jolt.JoltRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Loose vessels (plain rigid bodies) in the real engine with the real controller: free fall, resting on a hovering
 * carrier, riding along, sliding off a rolled deck, piles, sleeping and waking. One step is one game tick (0.05 s,
 * three collision steps), as {@link PhysicsWorld} runs it.
 */
class LooseCargoTest {
	private static final double X = 1000.5;
	private static final double Z = -2000.5;
	private static final VesselController.Params PARAMS = new VesselController.Params(12.0, 24.0, 1.6, 0.9, 1.5);
	/** Friction between two vessels: both have 0.6 and Jolt combines by the geometric mean. */
	private static final double FRICTION = 0.6;

	@BeforeAll
	static void loadNatives() {
		JoltRuntime.ensureLoaded(Path.of(System.getProperty("slipway.test.nativeCache", "build/tmp/test-natives")));
	}

	/** One vessel in a test world: its body, modes and helm, and its state after the last step. */
	private static final class Vessel {
		final long id;
		final BoxList boxes;
		final BoxList.MassProperties mass;
		final VesselController.Hold hold = new VesselController.Hold();
		final PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
		boolean hover = true;
		boolean level = true;
		boolean loose;
		VesselController.Axes axes = VesselController.Axes.IDLE;

		Vessel(long id, BoxList boxes) {
			this.id = id;
			this.boxes = boxes;
			this.mass = boxes.massProperties();
		}

		VesselPose pose() {
			return this.state.pose();
		}

		double speed() {
			return Math.sqrt(this.state.vx * this.state.vx + this.state.vy * this.state.vy + this.state.vz * this.state.vz);
		}

		double spin() {
			return Math.sqrt(this.state.wx * this.state.wx + this.state.wy * this.state.wy + this.state.wz * this.state.wz);
		}

		/** This vessel's origin in another vessel's frame. */
		Vector3d in(Vessel other) {
			return other.pose().worldToLocal(this.state.x, this.state.y, this.state.z, new Vector3d());
		}
	}

	private static final class World implements AutoCloseable {
		final JoltEngine engine = new JoltEngine(2);
		final List<Vessel> vessels = new ArrayList<>();
		private final PhysicsEngine.BodyState scratch = new PhysicsEngine.BodyState();
		private long nextId = 1;

		Vessel add(BoxList boxes, VesselPose pose, boolean loose) {
			return this.add(boxes, pose, loose, new Vector3d(), new Vector3d());
		}

		Vessel add(BoxList boxes, VesselPose pose, boolean loose, Vector3d velocity, Vector3d angularVelocity) {
			Vessel v = new Vessel(this.nextId++, boxes);
			v.loose = loose;
			this.engine.setVesselShape(v.id, boxes, pose, velocity, angularVelocity);
			this.engine.setVesselLoose(v.id, loose);
			this.engine.readVessel(v.id, v.state);
			this.vessels.add(v);
			return v;
		}

		void remove(Vessel v) {
			this.engine.removeVessel(v.id);
			this.vessels.remove(v);
		}

		void step(int ticks) {
			for (int i = 0; i < ticks; i++) {
				for (Vessel v : this.vessels) {
					this.engine.setVesselLoose(v.id, v.loose);
					VesselController.drive(this.engine, PARAMS, new VesselController.Drive(v.id, v.axes, v.hover, v.level, v.loose, false, v.mass,
						new Vector3d(0, 0, -1), v.hold), this.scratch);
				}
				this.engine.step(PhysicsWorld.STEP_SECONDS, PhysicsWorld.SUBSTEPS);
				for (Vessel v : this.vessels) {
					assertTrue(this.engine.readVessel(v.id, v.state) && v.state.isFinite(), "vessel " + v.id + " has a non-finite state");
				}
			}
		}

		@Override
		public void close() {
			this.engine.close();
		}
	}

	/** A deck sx by sz blocks and {@code thick} deep with its top face at local y = 0, centred on the local origin. */
	private static BoxList deck(int sx, int sz, int thick) {
		BoxList b = new BoxList();
		b.add(-sx / 2f, -thick, -sz / 2f, sx / 2f, 0, sz / 2f, 700f);
		return b;
	}

	/** The same deck with a wall two blocks high around it. */
	private static BoxList tray(int sx, int sz) {
		BoxList b = deck(sx, sz, 1);
		b.add(-sx / 2f, 0, -sz / 2f, sx / 2f, 2, -sz / 2f + 1, 700f);
		b.add(-sx / 2f, 0, sz / 2f - 1, sx / 2f, 2, sz / 2f, 700f);
		b.add(-sx / 2f, 0, -sz / 2f + 1, -sx / 2f + 1, 2, sz / 2f - 1, 700f);
		b.add(sx / 2f - 1, 0, -sz / 2f + 1, sx / 2f, 2, sz / 2f - 1, 700f);
		return b;
	}

	private static BoxList box(float sx, float sy, float sz, float density) {
		BoxList b = new BoxList();
		b.add(0, 0, 0, sx, sy, sz, density);
		return b;
	}

	/** A 16 by 16 floor one block thick, as one box or as 256 single-block boxes of two materials (all seams). */
	private static BoxList floor(boolean patchwork) {
		BoxList floor = new BoxList();
		if (!patchwork) {
			floor.add(0, 0, 0, 16, 1, 16, 2400f);
			return floor;
		}
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				floor.add(x, 0, z, x + 1, 1, z + 1, (x + z) % 2 == 0 ? 1600f : 2400f);
			}
		}
		return floor;
	}

	@Test
	void aLooseVesselInFreeFallKeepsItsSpinAndAControlledOneIsBraked() {
		try (World world = new World()) {
			Vector3d spin = new Vector3d(0, 2.0, 0);
			Vessel loose = world.add(deck(3, 3, 1), VesselPose.at(X, 300, Z), true, new Vector3d(), spin);
			Vessel controlled = world.add(deck(3, 3, 1), VesselPose.at(X + 20, 300, Z), false, new Vector3d(), spin);
			controlled.hover = false;
			controlled.level = false;
			world.step(20);
			// One second of free fall: nothing damps the loose vessel, the controller brakes the other's turn rate.
			assertEquals(2.0, loose.spin(), 0.02, "the loose vessel's spin");
			assertEquals(-9.81, loose.state.vy, 0.05, "the loose vessel's fall speed after one second");
			assertEquals(300 - 9.81 / 2, loose.state.y, 0.3, "the loose vessel's height after one second");
			assertTrue(controlled.spin() < 0.2, "the controlled vessel still spins at " + controlled.spin());
			assertTrue(controlled.state.vy > -9.5, "the controlled vessel fell without drag: " + controlled.state.vy);
		}
	}

	@Test
	void helmInputAndModesDoNotMoveALooseVessel() {
		try (World world = new World()) {
			Vessel loose = world.add(deck(3, 3, 1), VesselPose.at(X, 300, Z), true);
			Vessel reference = world.add(deck(3, 3, 1), VesselPose.at(X + 20, 300, Z), true);
			loose.hover = true;
			loose.level = true;
			loose.axes = new VesselController.Axes(1, -1, 1, 1, -1, 1);
			reference.hover = false;
			reference.level = false;
			world.step(20);
			assertEquals(reference.state.y, loose.state.y, 1.0e-6, "hover or vertical input lifted a loose vessel");
			assertEquals(reference.state.x - 20, loose.state.x, 1.0e-6, "strafe input moved a loose vessel");
			assertEquals(reference.state.z, loose.state.z, 1.0e-6, "thrust moved a loose vessel");
			assertEquals(0.0, loose.spin(), 1.0e-6, "turn input spun a loose vessel");
		}
	}

	@Test
	void turningLooseOffBringsHoverAndLevelBack() {
		try (World world = new World()) {
			Vessel vessel = world.add(deck(5, 3, 1), VesselPose.fromYawPitchRoll(X, 300, Z, 0, 0, 35), true);
			world.step(20);
			assertTrue(vessel.state.vy < -9.0, "the loose vessel did not fall");
			vessel.loose = false;
			world.step(120);
			assertTrue(vessel.speed() < 0.05, "hover did not stop the vessel after loose was turned off: " + vessel.speed());
			assertTrue(vessel.pose().tiltDegrees() < 2.0, "level did not right the vessel after loose was turned off: " + vessel.pose().tiltDegrees());
			double y = vessel.state.y;
			world.step(40);
			assertEquals(y, vessel.state.y, 0.02, "the vessel does not hold its height");
		}
	}

	@Test
	void looseCargoComesToRestOnAHoveringCarrierWhichKeepsItsHeight() {
		try (World world = new World()) {
			Vessel carrier = world.add(deck(21, 17, 3), VesselPose.at(X, 100, Z), false);
			// Dropped from three blocks: a two-block crate, and a heavier L of stone.
			Vessel crate = world.add(box(2, 1, 1, 700f), VesselPose.at(X + 2, 103, Z + 1), true);
			BoxList stone = box(3, 1, 1, 2400f);
			stone.add(0, 1, 0, 1, 3, 1, 2400f);
			Vessel heavy = world.add(stone, VesselPose.at(X - 5, 103, Z - 3), true);
			world.step(200);
			double load = (crate.mass.mass() + heavy.mass.mass()) / carrier.mass.mass();
			double sag = load * VesselController.GRAVITY / (VesselController.BRAKE_GAIN * VesselController.BRAKE_GAIN);
			for (Vessel cargo : List.of(crate, heavy)) {
				Vector3d local = cargo.in(carrier);
				// Jolt lets resting bodies overlap by its penetration slop (2 cm).
				assertEquals(0.0, local.y, 0.04, "cargo " + cargo.id + " does not rest on the deck (height above it)");
				assertTrue(Math.abs(cargo.state.vy - carrier.state.vy) < 0.02 && cargo.spin() < 0.02, "cargo " + cargo.id + " is not at rest");
				assertTrue(cargo.pose().tiltDegrees() < 1.0, "cargo " + cargo.id + " landed tilted " + cargo.pose().tiltDegrees());
			}
			assertEquals(100 - sag, carrier.state.y, 0.02, "the carrier under a load of " + load + " of its weight");
			assertTrue(carrier.speed() < 0.01, "the loaded carrier still moves at " + carrier.speed());
			double before = carrier.state.y;
			world.step(200);
			assertEquals(before, carrier.state.y, 0.005, "the loaded carrier keeps sinking");
			assertEquals(0.0, crate.in(carrier).y, 0.04, "the crate sank into the deck over time");
		}
	}

	@Test
	void looseCargoRidesAlongWhenTheCarrierFliesAndTurns() {
		try (World world = new World()) {
			Vessel carrier = world.add(deck(21, 17, 3), VesselPose.at(X, 100, Z), false);
			Vessel crate = world.add(box(2, 1, 1, 700f), VesselPose.at(X + 2, 100.5, Z + 1), true);
			world.step(60);
			Vector3d before = crate.in(carrier);
			// Gentle flying: at a third of full thrust and turn rate the deck never accelerates much harder than friction
			// holds (0.6 g = 5.9 m/s^2; the stop when the helm is released from 7 m/s starts at 10.8 and fades in 0.4 s).
			carrier.axes = new VesselController.Axes(0.3, 0, 0, 0, 0.3, 0);
			world.step(200);
			carrier.axes = VesselController.Axes.IDLE;
			world.step(100);
			double flown = Math.hypot(carrier.state.x - X, carrier.state.z - Z);
			assertTrue(flown > 20, "the carrier only flew " + flown + " blocks");
			assertTrue(Math.abs(carrier.pose().headingDegrees()) > 30, "the carrier did not turn: heading " + carrier.pose().headingDegrees());
			Vector3d after = crate.in(carrier);
			assertTrue(before.distance(after) < 0.5, "the crate moved " + before.distance(after) + " blocks on the deck during gentle flying");
			assertEquals(0.0, after.y, 0.04, "the crate left the deck");
			// Climbing and sinking gently takes it along too.
			carrier.axes = new VesselController.Axes(0, 0, 0.3, 0, 0, 0);
			world.step(60);
			carrier.axes = new VesselController.Axes(0, 0, -0.3, 0, 0, 0);
			world.step(60);
			carrier.axes = VesselController.Axes.IDLE;
			world.step(100);
			assertTrue(after.distance(crate.in(carrier)) < 0.2, "the crate moved " + after.distance(crate.in(carrier)) + " blocks on the deck during a gentle climb");
		}
	}

	@Test
	void aHardStopSlidesLooseCargoAlongTheDeck() {
		try (World world = new World()) {
			Vessel carrier = world.add(deck(41, 41, 3), VesselPose.at(X, 100, Z), false);
			Vessel crate = world.add(box(2, 1, 1, 700f), VesselPose.at(X, 100.1, Z + 10), true);
			world.step(40);
			// Brought up to speed slowly, then the helm is released: the carrier brakes at 1.5 times its speed per second
			// (18 m/s^2 from 12 m/s), three times what friction can pass on to the crate.
			carrier.axes = new VesselController.Axes(0.5, 0, 0, 0, 0, 0);
			world.step(200);
			Vector3d before = crate.in(carrier);
			assertTrue(carrier.speed() > 11, "the carrier only reached " + carrier.speed() + " blocks per second");
			carrier.axes = VesselController.Axes.IDLE;
			world.step(100);
			Vector3d after = crate.in(carrier);
			// The carrier stops within v / 1.5 = 8 blocks; the crate needs v^2 / (2 mu g) = 12: it slides on by the rest.
			assertTrue(before.z - after.z > 2.0, "the crate only slid " + (before.z - after.z) + " blocks forward in a hard stop");
			assertEquals(0.0, after.y, 0.04, "the sliding crate left the deck");
			assertTrue(crate.pose().tiltDegrees() < 2.0, "the crate tumbled on a seamless deck");
		}
	}

	@Test
	void looseCargoStaysOnAGentlyRolledDeckAndSlidesOffASteepOne() {
		try (World world = new World()) {
			Vessel carrier = world.add(deck(21, 17, 3), VesselPose.at(X, 100, Z), false);
			carrier.level = false;
			Vessel crate = world.add(box(2, 1, 1, 700f), VesselPose.at(X + 2, 100.2, Z + 1), true);
			world.step(60);
			Vector3d start = crate.in(carrier);
			double frictionAngle = Math.toDegrees(Math.atan(FRICTION));
			this.rollTo(world, carrier, frictionAngle - 11);
			world.step(100);
			assertTrue(carrier.pose().tiltDegrees() > frictionAngle - 13 && carrier.pose().tiltDegrees() < frictionAngle - 5,
				"the carrier is rolled " + carrier.pose().tiltDegrees());
			assertTrue(crate.in(carrier).distance(start) < 0.1, "the crate slid " + crate.in(carrier).distance(start) + " blocks on a deck rolled "
				+ carrier.pose().tiltDegrees() + " degrees (friction angle " + frictionAngle + ")");
			this.rollTo(world, carrier, frictionAngle + 14);
			world.step(100);
			Vector3d end = crate.in(carrier);
			assertTrue(end.distance(start) > 12 && end.y < -3, "the crate did not slide off a deck rolled " + carrier.pose().tiltDegrees() + " degrees: it is at "
				+ end + " in the carrier's frame");
		}
	}

	/** Rolls a carrier with its own roll control until it is tilted by the given angle, then lets it come to rest. */
	private void rollTo(World world, Vessel carrier, double degrees) {
		carrier.axes = new VesselController.Axes(0, 0, 0, 0, 0, 0.15);
		for (int i = 0; i < 600 && carrier.pose().tiltDegrees() < degrees - 1.5; i++) {
			world.step(1);
		}
		carrier.axes = VesselController.Axes.IDLE;
		world.step(20);
	}

	@Test
	void aPileOfLooseVesselsOnACarrierComesToRestWithoutSinkingIn() {
		try (World world = new World()) {
			Vessel carrier = world.add(tray(11, 9), VesselPose.at(X, 100, Z), false);
			List<Vessel> pieces = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				BoxList shape = box(1 + i % 3, 1, 1, i % 2 == 0 ? 700f : 2400f);
				if (i % 2 == 0) {
					shape.add(0, 1, 0, 1, 2 + i % 3, 1, 700f);
				}
				if (i % 3 == 0) {
					shape.add(0, 0, 1, 1, 1, 3, 700f);
				}
				// All above the same few blocks, turned every way: they must land on each other.
				pieces.add(world.add(shape, VesselPose.fromYawPitchRoll(X - 1.5 + (i % 3) * 1.2, 103 + i * 3.0, Z - 1 + (i % 2) * 1.1, i * 37, i * 11, i * 23), true));
			}
			double fastest = 0;
			for (int tick = 0; tick < 500; tick++) {
				world.step(1);
				for (Vessel piece : pieces) {
					fastest = Math.max(fastest, piece.speed());
					Vector3d local = piece.in(carrier);
					assertTrue(Math.abs(local.x) < 12 && Math.abs(local.z) < 12 && local.y > -3 && local.y < 40, "piece " + piece.id + " was thrown to " + local
						+ " at tick " + tick);
				}
			}
			// The highest piece falls 24 blocks: 22 m/s. Anything much faster was thrown by a contact.
			assertTrue(fastest < 26, "a piece reached " + fastest + " blocks per second");
			for (int tick = 0; tick < 100; tick++) {
				world.step(1);
				for (Vessel piece : pieces) {
					double relative = new Vector3d(piece.state.vx - carrier.state.vx, piece.state.vy - carrier.state.vy, piece.state.vz - carrier.state.vz).length();
					assertTrue(relative < 0.05 && piece.spin() < 0.05, "piece " + piece.id + " still moves at " + relative + " blocks per second, spin " + piece.spin());
				}
			}
			List<Vessel> all = new ArrayList<>(pieces);
			all.add(carrier);
			for (Vessel a : pieces) {
				for (Vessel b : all) {
					if (a != b) {
						double depth = deepestPointInside(a, b);
						assertTrue(depth < 0.1, "piece " + a.id + " rests " + depth + " blocks inside vessel " + b.id);
					}
				}
			}
			// The pile weighs a quarter of this small carrier and lies off its centre: it sags and leans, and stays so.
			double tilt = carrier.pose().tiltDegrees();
			double height = carrier.state.y;
			assertTrue(tilt < 8.0, "the pile tipped the carrier " + tilt + " degrees");
			assertEquals(100.0, height, 1.5, "the carrier's height under the pile");
			world.step(100);
			assertEquals(tilt, carrier.pose().tiltDegrees(), 0.1, "the carrier keeps tipping under the pile");
			assertEquals(height, carrier.state.y, 0.01, "the carrier keeps sinking under the pile");
		}
	}

	/**
	 * How deep the corners and the centre of a vessel's boxes lie inside the boxes of another vessel: the largest
	 * distance of such a point from the nearest face of a box of {@code b} that contains it (0 when none is inside).
	 */
	private static double deepestPointInside(Vessel a, Vessel b) {
		double deepest = 0;
		Vector3d world = new Vector3d();
		Vector3d local = new Vector3d();
		for (int i = 0; i < a.boxes.size(); i++) {
			double[] xs = {a.boxes.minX(i), (a.boxes.minX(i) + a.boxes.maxX(i)) / 2, a.boxes.maxX(i)};
			double[] ys = {a.boxes.minY(i), (a.boxes.minY(i) + a.boxes.maxY(i)) / 2, a.boxes.maxY(i)};
			double[] zs = {a.boxes.minZ(i), (a.boxes.minZ(i) + a.boxes.maxZ(i)) / 2, a.boxes.maxZ(i)};
			for (double x : xs) {
				for (double y : ys) {
					for (double z : zs) {
						a.pose().localToWorld(x, y, z, world);
						b.pose().worldToLocal(world.x, world.y, world.z, local);
						for (int j = 0; j < b.boxes.size(); j++) {
							double inside = Math.min(Math.min(Math.min(local.x - b.boxes.minX(j), b.boxes.maxX(j) - local.x),
								Math.min(local.y - b.boxes.minY(j), b.boxes.maxY(j) - local.y)), Math.min(local.z - b.boxes.minZ(j), b.boxes.maxZ(j) - local.z));
							deepest = Math.max(deepest, inside);
						}
					}
				}
			}
		}
		return deepest;
	}

	@Test
	void looseVesselsAtRestOnTerrainSleepAndWakeWhenTheirSupportGoes() {
		try (World world = new World()) {
			world.engine.setStaticSection(1L, floor(false), 1000, 64, -2000);
			Vessel lower = world.add(box(2, 1, 1, 700f), VesselPose.at(1004, 67, -1994), true);
			Vessel upper = world.add(box(1, 1, 1, 700f), VesselPose.at(1004.3, 70, -1994), true);
			world.step(100);
			assertEquals(65.0, lower.state.y, 0.03, "the lower vessel rests on the floor");
			assertEquals(66.0, upper.state.y, 0.05, "the upper vessel rests on the lower one");
			assertFalse(lower.state.active || upper.state.active, "the pile at rest on terrain is still simulated");
			world.step(100);
			assertEquals(66.0, upper.state.y, 0.05, "the sleeping pile moved");

			// Take the lower vessel away (disassembled or removed): the upper one must fall, not hang in the air.
			world.remove(lower);
			world.step(40);
			assertEquals(65.0, upper.state.y, 0.03, "the upper vessel did not fall when the vessel under it was removed");
			world.step(40);
			assertFalse(upper.state.active, "the vessel did not go back to sleep");

			// The terrain under it changes (a block broken): it must wake and fall.
			world.engine.removeStaticSection(1L);
			world.step(20);
			assertTrue(upper.state.active && upper.state.y < 63, "the vessel did not fall when the terrain under it went: y " + upper.state.y);
		}
	}

	@Test
	void aLooseVesselOnAHoveringCarrierStaysAwakeWithIt() {
		try (World world = new World()) {
			Vessel carrier = world.add(deck(9, 9, 1), VesselPose.at(X, 100, Z), false);
			Vessel crate = world.add(box(1, 1, 1, 700f), VesselPose.at(X, 100.1, Z), true);
			world.step(100);
			// A controlled carrier never sleeps, and what rests on it is in its island: it must be ready to ride along.
			assertTrue(carrier.state.active && crate.state.active, "cargo on a hovering carrier fell asleep");
			carrier.axes = new VesselController.Axes(0, 0, 0.3, 0, 0, 0);
			world.step(40);
			assertTrue(carrier.state.y > 101, "the carrier did not rise");
			assertEquals(0.0, crate.in(carrier).y, 0.05, "the crate did not rise with the carrier");
		}
	}

	@Test
	void vesselsSlideOverTerrainSeamsAsOverOneSlab() {
		double[] travelled = new double[2];
		for (int run = 0; run < 2; run++) {
			try (World world = new World()) {
				world.engine.setStaticSection(1L, floor(run == 1), 1000, 64, -2000);
				Vessel cube = world.add(box(1, 1, 1, 700f), VesselPose.at(1001.3, 65.0, -1992.3), true, new Vector3d(8, 0, 0.7), new Vector3d());
				double tilt = 0;
				for (int tick = 0; tick < 100; tick++) {
					world.step(1);
					tilt = Math.max(tilt, cube.pose().tiltDegrees());
				}
				travelled[run] = cube.state.x - 1001.3;
				assertTrue(tilt < 1.0, "the cube tipped " + tilt + " degrees sliding over " + (run == 1 ? "a floor of single-block boxes" : "one slab"));
				assertFalse(cube.state.active, "the cube did not come to rest");
			}
		}
		// Sliding from 8 m/s with friction sqrt(0.6 * 0.8) on terrain: v^2 / (2 mu g) = 4.7 blocks.
		assertEquals(4.7, travelled[0], 0.3, "slide distance on one slab");
		assertEquals(travelled[0], travelled[1], 0.05, "slide distance over seams");
	}
}