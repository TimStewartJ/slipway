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
import net.minecraft.core.SectionPos;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Vessels in water in the real engine, with the real controller and the real hull: floating at the draught that
 * displaces their weight, sinking, righting, flooding over the rim, sealed air, resistance, a boat under thrust,
 * hover, sleeping. One step is one game tick, as {@link PhysicsWorld} runs it. The sea is 48 blocks deep with its
 * surface at {@link #SURFACE}, over a floor whose top is at {@link #FLOOR}.
 */
class BuoyancyTest {
	private static final VesselController.Params PARAMS = new VesselController.Params(12.0, 24.0, 1.6, 0.9, 1.5);
	private static final double SURFACE = 63 + 8.0 / 9.0;
	private static final double FLOOR = 16.0;
	private static final float WOOD = 700f;
	private static final float STONE = 2400f;

	@BeforeAll
	static void loadNatives() {
		JoltRuntime.ensureLoaded(Path.of(System.getProperty("slipway.test.nativeCache", "build/tmp/test-natives")));
	}

	/** A vessel's blocks as its body and as its hull. */
	private static final class Shape {
		final BoxList boxes = new BoxList();
		final Hull.Builder hull = new Hull.Builder();

		/** A box of full blocks from (x0, y0, z0) to (x1, y1, z1), both inclusive: one collision box, one hull cell per block. */
		Shape fill(int x0, int y0, int z0, int x1, int y1, int z1, float density) {
			this.boxes.add(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1, density);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						this.hull.block(x, y, z, 1f, true);
					}
				}
			}
			return this;
		}

		/** An open hull: sx by sz outside with a floor at y = 0 and walls up to y = sy - 1. */
		static Shape openHull(int sx, int sy, int sz, float density) {
			Shape s = new Shape().fill(0, 0, 0, sx - 1, 0, sz - 1, density);
			s.fill(0, 1, 0, sx - 1, sy - 1, 0, density);
			s.fill(0, 1, sz - 1, sx - 1, sy - 1, sz - 1, density);
			s.fill(0, 1, 1, 0, sy - 1, sz - 2, density);
			s.fill(sx - 1, 1, 1, sx - 1, sy - 1, sz - 2, density);
			return s;
		}
	}

	private static final class Vessel {
		final long id;
		Hull hull;
		BoxList.MassProperties mass;
		final VesselController.Hold hold = new VesselController.Hold();
		final PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
		final Buoyancy.State water = new Buoyancy.State();
		boolean hover;
		boolean level;
		boolean loose = true;
		VesselController.Axes axes = VesselController.Axes.IDLE;

		Vessel(long id, Shape shape) {
			this.id = id;
			this.hull = shape.hull.build();
			this.mass = shape.boxes.massProperties();
		}

		double speed() {
			return Math.sqrt(this.state.vx * this.state.vx + this.state.vy * this.state.vy + this.state.vz * this.state.vz);
		}

		double tilt() {
			return this.state.pose().tiltDegrees();
		}
	}

	private static final class Sea implements AutoCloseable {
		final JoltEngine engine = new JoltEngine(2);
		final FluidField water = new FluidField();
		final List<Vessel> vessels = new ArrayList<>();
		private final PhysicsEngine.BodyState scratch = new PhysicsEngine.BodyState();
		private final Buoyancy.Scratch buoyancyScratch = new Buoyancy.Scratch();
		Buoyancy.Params params = Buoyancy.Params.DEFAULT;
		private long nextId = 1;

		/** Water from the floor to the surface over the block columns x0..x1, z0..z1, and the floor under it. */
		Sea(int x0, int z0, int x1, int z1) {
			this(x0, z0, x1, z1, (int)FLOOR);
		}

		/** A shallower sea: its floor's top at {@code floorTop}. */
		Sea(int x0, int z0, int x1, int z1, int floorTop) {
			FluidFieldTest.fill(this.water, x0, floorTop, z0, x1, 63, z1, false);
			long key = 1;
			for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
				for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
					BoxList floor = new BoxList();
					floor.add(0, 0, 0, 16, 16, 16, STONE);
					this.engine.setStaticSection(key++, floor, sx << 4, floorTop - 16, sz << 4);
				}
			}
		}

		/** Gives a vessel other blocks where it is, as the game does when a block of it changes. */
		void reshape(Vessel v, Shape shape) {
			v.hull = shape.hull.build();
			v.mass = shape.boxes.massProperties();
			this.engine.setVesselShape(v.id, shape.boxes, v.state.pose(), new Vector3d(v.state.vx, v.state.vy, v.state.vz), new Vector3d(v.state.wx, v.state.wy, v.state.wz));
		}

		Vessel add(Shape shape, VesselPose pose) {
			Vessel v = new Vessel(this.nextId++, shape);
			this.engine.setVesselShape(v.id, shape.boxes, pose, new Vector3d(), new Vector3d());
			this.engine.readVessel(v.id, v.state);
			this.vessels.add(v);
			return v;
		}

		void step(int ticks) {
			for (int i = 0; i < ticks; i++) {
				for (Vessel v : this.vessels) {
					this.engine.setVesselLoose(v.id, v.loose);
					VesselController.drive(this.engine, PARAMS, PhysicsWorld.STEP, new VesselController.Drive(v.id, v.axes, v.hover, v.level, v.loose, false, v.mass,
						new Vector3d(0, 0, -1), v.hold), this.scratch);
					Buoyancy.apply(this.engine, this.water, this.params, PhysicsWorld.STEP, v.id, v.hull, v.mass, new Vector3d(0, 0, -1), v.loose || !v.hover, v.water,
						this.buoyancyScratch);
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

	@Test
	void aBlockOfWoodFloatsWithSevenTenthsOfItUnderWater() {
		try (Sea sea = new Sea(0, 0, 15, 15)) {
			Vessel plank = sea.add(new Shape().fill(0, 0, 0, 0, 0, 0, WOOD), VesselPose.at(8.0, SURFACE + 1.5, 8.0));
			double lowest = Double.MAX_VALUE;
			for (int i = 0; i < 80; i++) {
				sea.step(1);
				lowest = Math.min(lowest, plank.state.y);
			}
			sea.step(200);
			// 700 kg displace 0.7 m^3 of water: the bottom face lies 0.7 below the surface.
			assertEquals(SURFACE - 0.7, plank.state.y, 0.02);
			assertEquals(0.0, plank.speed(), 0.02);
			assertEquals(0.7, plank.water.displacedVolume, 0.02);
			assertEquals(700.0, plank.water.displacedMass, 20.0);
			assertEquals(FluidField.WATER, plank.water.fluid);
			assertTrue(plank.water.applied);
			assertEquals(1, plank.water.waterlineCount);
			assertEquals(SURFACE, plank.water.waterline[1], 0.02);
			// It went in from a fall of a block and a half and came up again: it was deeper than it floats, and did not hit the floor.
			assertTrue(lowest < SURFACE - 0.8 && lowest > SURFACE - 4.0, "lowest point of the plunge: " + (lowest - SURFACE));
		}
	}

	@Test
	void stoneSinksAtItsTerminalSpeedAndRestsOnTheBottom() {
		try (Sea sea = new Sea(0, 0, 15, 15)) {
			Vessel stone = sea.add(new Shape().fill(0, 0, 0, 0, 0, 0, STONE), VesselPose.at(8.0, SURFACE - 1.0, 8.0));
			double fastest = 0;
			for (int i = 0; i < 100; i++) {
				sea.step(1);
				fastest = Math.max(fastest, -stone.state.vy);
			}
			// Weight less lift is 5.72 m/s^2 down; the resistance (2.5 v + 0.15 v^2) / 2.4 balances it at 4.35 m/s.
			assertEquals(4.35, fastest, 0.25);
			sea.step(300);
			assertEquals(FLOOR, stone.state.y, 0.06);
			assertEquals(0.0, stone.speed(), 0.05);
			assertEquals(1.0, stone.water.displacedVolume, 1e-6);
		}
	}

	@Test
	void anOpenHullOfStoneFloatsOnTheAirItShelters() {
		try (Sea sea = new Sea(0, 0, 31, 31)) {
			// 16 x 16 outside and six high: 556 blocks of stone, 1334 t, around 980 m^3 of air.
			Shape shape = Shape.openHull(16, 6, 16, STONE);
			Vessel hull = sea.add(shape, VesselPose.at(8.0, SURFACE - 5.0, 8.0));
			assertEquals(556 * 2400.0, hull.mass.mass(), 1.0);
			assertEquals(14 * 14 * 5.0, hull.hull.shelteredVolume(), 1e-3);
			sea.step(400);
			// It displaces its weight with 16 x 16 blocks of bottom: a draught of 1334.4 / 256 = 5.21 blocks of its six.
			assertEquals(SURFACE - 556 * 2.4 / 256.0, hull.state.y, 0.05);
			assertEquals(0.0, hull.speed(), 0.03);
			assertTrue(hull.tilt() < 0.5, "it lists " + hull.tilt() + " degrees");
			assertEquals(0.0, hull.water.floodedVolume, 1e-6);
			assertEquals(556 * 2.4, hull.water.displacedVolume, 15.0);
			// Where spray rises: on the walls the surface cuts, not in the dry air of the hold, which it cuts as well.
			assertEquals(Buoyancy.WATERLINE_SAMPLES, hull.water.waterlineCount);
			for (int i = 0; i < hull.water.waterlineCount; i++) {
				Vector3d local = hull.state.pose().worldToLocal(hull.water.waterline[i * 3], hull.water.waterline[i * 3 + 1], hull.water.waterline[i * 3 + 2], new Vector3d());
				assertTrue(local.x < 1 || local.x > 15 || local.z < 1 || local.z > 15, "a waterline sample inside the hold: " + local);
				assertEquals(SURFACE, hull.water.waterline[i * 3 + 1], 0.02);
			}
		}
	}

	@Test
	void aRaftOfSlabsFloatsAsDeepAsSlabsDo() {
		try (Sea sea = new Sea(0, 0, 15, 15)) {
			// Three by three bottom slabs of wood: half a block high, so 0.35 of it under water (as a full block has 0.7).
			Shape bottom = new Shape();
			bottom.boxes.add(0, 0, 0, 3, 0.5f, 3, WOOD);
			Shape top = new Shape();
			top.boxes.add(0, 0.5f, 0, 3, 1, 3, WOOD);
			for (int x = 0; x < 3; x++) {
				for (int z = 0; z < 3; z++) {
					bottom.hull.block(x, 0, z, 0.5f, true, 0f, 0.5f);
					top.hull.block(x, 0, z, 0.5f, true, 0.5f, 1f);
				}
			}
			Vessel low = sea.add(bottom, VesselPose.at(2.0, SURFACE - 0.2, 2.0));
			Vessel high = sea.add(top, VesselPose.at(9.0, SURFACE - 0.7, 9.0));
			sea.step(300);
			assertEquals(SURFACE - 0.35, low.state.y, 0.02);
			// Top slabs: the same raft half a block up in its cells.
			assertEquals(SURFACE - 0.35 - 0.5, high.state.y, 0.02);
			assertEquals(9 * 0.35, low.water.displacedVolume, 0.05);
			assertEquals(9 * 0.35, high.water.displacedVolume, 0.05);
			assertEquals(0.0, low.speed() + high.speed(), 0.03);
			assertEquals(SURFACE, low.water.waterline[1], 0.02);
		}
	}

	@Test
	void aHullPushedUnderTakesInWaterOverItsRimAndSinks() {
		try (Sea sea = new Sea(0, 0, 31, 31)) {
			// The same hull that floats above, set down with its rim a block under water.
			Vessel hull = sea.add(Shape.openHull(16, 6, 16, STONE), VesselPose.at(8.0, SURFACE - 7.0, 8.0));
			sea.step(1);
			assertEquals(14 * 14 * 5.0, hull.water.floodedVolume, 1.0, "all the air it sheltered has flooded");
			assertEquals(556.0, hull.water.displacedVolume, 1.0, "what is left is the stone itself");
			sea.step(600);
			assertEquals(FLOOR, hull.state.y, 0.1);
			assertEquals(0.0, hull.speed(), 0.05);
		}
	}

	@Test
	void aWoodenHullSwampedOverItsRimComesBackUp() {
		try (Sea sea = new Sea(0, 0, 31, 31)) {
			Vessel hull = sea.add(Shape.openHull(5, 3, 5, WOOD), VesselPose.at(8.0, SURFACE - 6.0, 8.0));
			sea.step(1);
			assertEquals(18.0, hull.water.floodedVolume, 1e-3);
			sea.step(600);
			// Wood is lighter than water: full of water it rises until its rim is out, and then the water is out of it
			// too (nothing remembers the water that was in it), so it floats as it did before.
			assertEquals(SURFACE - 1.596, hull.state.y, 0.08);
			assertEquals(0.0, hull.water.floodedVolume, 1e-6);
			assertEquals(0.0, hull.speed(), 0.05);
		}
	}

	@Test
	void aHeeledHullRightsItself() {
		try (Sea sea = new Sea(0, 0, 31, 31)) {
			Shape shape = Shape.openHull(5, 3, 5, WOOD);
			// 57 planks, 39.9 t, on a bottom of 25 blocks: a draught of 1.6 of its three blocks.
			VesselPose heeled = VesselPose.fromYawPitchRoll(12.0, SURFACE - 1.6, 12.0, 0, 0, 12);
			Vessel hull = sea.add(shape, heeled);
			assertTrue(hull.tilt() > 11.0);
			sea.step(400);
			assertTrue(hull.tilt() < 1.0, "it still heels " + hull.tilt() + " degrees");
			assertEquals(0.0, hull.speed(), 0.03);
			assertEquals(39.9, hull.water.displacedVolume, 0.6);
			assertEquals(0.0, hull.water.floodedVolume, 1e-6);
			assertTrue(hull.water.waterlineCount > 0);
		}
	}

	@Test
	void aSealedCabinKeepsItsAirUnderWaterAndComesUp() {
		try (Sea sea = new Sea(0, 0, 31, 31)) {
			// A closed, flat box of planks, seven blocks square and three high: 122 planks, 85.4 t, 25 m^3 of air
			// nothing can reach.
			Shape shape = Shape.openHull(7, 2, 7, WOOD).fill(0, 2, 0, 6, 2, 6, WOOD);
			Vessel cabin = sea.add(shape, VesselPose.at(8.0, SURFACE - 20.0, 8.0));
			assertEquals(25.0, cabin.hull.sealedVolume(), 1e-6);
			assertEquals(122 * 700.0, cabin.mass.mass(), 1.0);
			sea.step(1);
			assertEquals(147.0, cabin.water.displacedVolume, 1e-3, "deep under water it displaces all of itself, air and all");
			sea.step(600);
			// 85.4 t on a bottom of 49 blocks: a draught of 1.743.
			assertEquals(SURFACE - 85.4 / 49.0, cabin.state.y, 0.06);
			assertEquals(0.0, cabin.speed(), 0.03);
		}
	}

	@Test
	void aHoveringVesselIsLeftAloneAndOneWithoutHoverFloats() {
		try (Sea sea = new Sea(0, 0, 31, 31)) {
			Vessel hovering = sea.add(Shape.openHull(5, 3, 5, WOOD), VesselPose.at(4.0, SURFACE - 2.5, 4.0));
			Vessel floating = sea.add(Shape.openHull(5, 3, 5, WOOD), VesselPose.at(20.0, SURFACE - 2.5, 20.0));
			hovering.loose = floating.loose = false;
			hovering.hover = true;
			hovering.level = floating.level = true;
			sea.step(200);
			assertEquals(SURFACE - 2.5, hovering.state.y, 0.02, "the hovering vessel was moved by the water");
			assertFalse(hovering.water.applied);
			assertTrue(hovering.water.displacedVolume > 60.0, "it is told that it lies in water all the same");
			assertEquals(SURFACE - 1.596, floating.state.y, 0.05, "without hover the helm's vessel floats like any other");
			assertTrue(floating.water.applied);
			// Hover switched off: it comes up to float; switched on again: it stays where it is then.
			hovering.hover = false;
			sea.step(300);
			assertEquals(SURFACE - 1.596, hovering.state.y, 0.05);
			hovering.hover = true;
			sea.step(100);
			assertEquals(SURFACE - 1.596, hovering.state.y, 0.08);
		}
	}

	@Test
	void aBoatUnderThrustRunsStraightAtBoatSpeedAndTurns() {
		try (Sea sea = new Sea(-64, -191, 95, 31)) {
			Vessel boat = sea.add(Shape.openHull(5, 3, 7, WOOD), VesselPose.at(13.0, SURFACE - 1.7, 8.0));
			boat.loose = false;
			boat.level = true;
			sea.step(100);
			double floatsAt = boat.state.y;
			double startX = boat.state.x;
			boat.axes = new VesselController.Axes(1, 0, 0, 0, 0, 0);
			sea.step(200);
			// Thrust 12 m/s^2 against 0.5 per second in the air and (0.7 + 0.02 v) per second in the water: 8.7 m/s.
			assertEquals(-8.73, boat.state.vz, 0.6);
			assertEquals(0.0, boat.state.vx, 0.1);
			assertEquals(startX, boat.state.x, 0.3);
			assertEquals(floatsAt, boat.state.y, 0.15);
			assertTrue(boat.tilt() < 2.0, "the boat pitches or rolls " + boat.tilt() + " degrees under thrust");
			// Hard over: it turns, and the water stops it sliding on along its old course.
			boat.axes = new VesselController.Axes(1, 0, 0, 0, 1, 0);
			double heading = boat.state.pose().attitudeDegrees()[1];
			sea.step(40);
			double turned = Math.abs(boat.state.pose().attitudeDegrees()[1] - heading);
			turned = Math.min(turned, 360.0 - turned);
			assertTrue(turned > 40.0, "turned " + turned + " degrees in two seconds");
			// Released: the water stops it within a few seconds.
			boat.axes = VesselController.Axes.IDLE;
			sea.step(160);
			assertEquals(0.0, boat.speed(), 0.25);
			assertEquals(floatsAt, boat.state.y, 0.1);
		}
	}

	@Test
	void aFloatingLooseVesselFallsAsleepAndWakesWhenTheWaterGoes() {
		try (Sea sea = new Sea(0, 0, 15, 15)) {
			Vessel plank = sea.add(new Shape().fill(0, 0, 0, 2, 0, 2, WOOD), VesselPose.at(6.0, SURFACE - 0.5, 6.0));
			sea.step(400);
			assertFalse(plank.state.active, "a raft at rest on the water is still simulated");
			assertEquals(SURFACE - 0.7, plank.state.y, 0.03);
			assertTrue(plank.water.displacedVolume > 6.0, "asleep, it still counts as afloat");
			// The pool is drained: the game tells the engine, the raft wakes and falls to the floor.
			for (int sy = 1; sy <= 3; sy++) {
				sea.water.remove(SectionPos.asLong(0, sy, 0));
			}
			sea.engine.wakeLooseVessels(0, 16, 0, 16, 64, 16);
			sea.step(200);
			assertEquals(FLOOR, plank.state.y, 0.06);
			assertEquals(0.0, plank.water.displacedVolume, 1e-9);
		}
	}

	@Test
	void lavaCarriesMoreThanWaterAndBuoyancyCanBeTurnedOff() {
		try (Sea sea = new Sea(0, 0, 15, 15)) {
			sea.water.clear();
			FluidFieldTest.fill(sea.water, 0, (int)FLOOR, 0, 15, 63, 15, true);
			// Stone floats on lava: 2400 kg displace 2400 / 3100 = 0.774 m^3.
			Vessel stone = sea.add(new Shape().fill(0, 0, 0, 0, 0, 0, STONE), VesselPose.at(8.0, SURFACE - 0.5, 8.0));
			sea.step(300);
			assertEquals(SURFACE - 2400.0 / 3100.0, stone.state.y, 0.03);
			assertEquals(FluidField.LAVA, stone.water.fluid);
			sea.params = new Buoyancy.Params(false, 1.0);
			sea.engine.wakeLooseVessels(0, 16, 0, 16, 80, 16);
			sea.step(200);
			assertEquals(FLOOR, stone.state.y, 0.06);
			assertFalse(stone.water.applied);
		}
	}

	/** The hull of the client test `afloat`: 5 x 5, a floor and three rows of wall, the helm in the middle and iron in the inner corners. */
	private static Shape ballastedHull(int moreIron) {
		Shape s = Shape.openHull(5, 4, 5, WOOD).fill(2, 1, 2, 2, 1, 2, WOOD);
		int[][] iron = {{1, 1}, {3, 1}, {1, 3}, {3, 3}, {1, 2}, {3, 2}, {2, 1}};
		for (int i = 0; i < 4 + moreIron; i++) {
			s.fill(iron[i][0], 1, iron[i][1], iron[i][0], 1, iron[i][1], 7800f);
		}
		return s;
	}

	@Test
	void theBallastedHullOfTheClientTestGoesInWithoutDippingItsRimSailsAndSinksWhenOverloaded() {
		// The pool of the client test: seven blocks deep.
		try (Sea sea = new Sea(-32, -32, 47, 47, 57)) {
			Vessel hull = sea.add(ballastedHull(0), VesselPose.at(0, SURFACE + 1.0 + 1.0 / 9.0, 0));
			hull.loose = false;
			hull.level = true;
			assertEquals(83000.0, hull.mass.mass(), 1.0);
			assertEquals(22.0, hull.hull.shelteredVolume(), 1e-6);
			assertEquals(100.0, hull.hull.capacity(), 1e-6);
			double deepest = 0;
			int turns = 0;
			double before = 0;
			for (int i = 0; i < 400; i++) {
				sea.step(1);
				deepest = Math.max(deepest, SURFACE - hull.state.y);
				if (Math.abs(before) > 0.03 && before * hull.state.vy < 0) {
					turns++;
				}
				before = hull.state.vy;
			}
			// 83 t on 25 blocks of bottom: a draught of 3.32 of the hull's four blocks.
			assertEquals(3.32, SURFACE - hull.state.y, 0.03);
			assertTrue(deepest < 3.8, "going in from a block above, the hull dipped to " + deepest + " (its rim is at 4)");
			assertTrue(turns >= 1 && turns <= 6, "the hull changed direction " + turns + " times before it lay still");
			assertEquals(0.0, hull.water.floodedVolume, 1e-9);
			assertEquals(0.0, hull.speed(), 0.02);
			// The push of the test: 0.4 of the thrust for twelve ticks.
			double z = hull.state.z;
			hull.axes = new VesselController.Axes(0.4, 0, 0, 0, 0, 0);
			sea.step(12);
			hull.axes = VesselController.Axes.IDLE;
			sea.step(160);
			double moved = Math.abs(hull.state.z - z);
			assertTrue(moved > 0.8 && moved < 3.5, "the push moved the boat " + moved + " blocks");
			assertEquals(0.0, hull.speed(), 0.05);
			assertEquals(3.32, SURFACE - hull.state.y, 0.03);
			// Three more blocks of iron: 106.4 t in a hull that displaces 100 m^3 at most.
			sea.reshape(hull, ballastedHull(3));
			assertTrue(hull.mass.mass() > 100000);
			sea.step(400);
			assertEquals(57.0, hull.state.y, 0.1);
			assertTrue(hull.water.floodedVolume > 18.0, "flooded: " + hull.water.floodedVolume);
			assertEquals(0.0, hull.speed(), 0.05);
		}
	}

	@Test
	void theHullOfTheServerTestCarriesTwoBlocksOfIronAndSinksUnderEightInAShallowPool() {
		// The pool of the server tests: four blocks of water, 3.89 deep.
		try (Sea sea = new Sea(0, 0, 31, 31, 60)) {
			Shape light = Shape.openHull(5, 3, 5, WOOD).fill(2, 1, 2, 2, 1, 2, WOOD).fill(1, 1, 2, 1, 1, 2, 7800f).fill(3, 1, 2, 3, 1, 2, 7800f);
			Vessel hull = sea.add(light, VesselPose.at(8, SURFACE + 1.0 / 9.0, 8));
			hull.loose = false;
			hull.level = true;
			sea.step(300);
			assertEquals(56200.0, hull.mass.mass(), 1.0);
			assertEquals(15.0, hull.hull.shelteredVolume(), 1e-6);
			assertEquals(56.2 / 25.0, SURFACE - hull.state.y, 0.04);
			assertEquals(0.0, hull.water.floodedVolume, 1e-9);
			assertTrue(hull.tilt() < 1.0);
			Shape heavy = Shape.openHull(5, 3, 5, WOOD).fill(2, 1, 2, 2, 1, 2, WOOD);
			for (int x = 1; x <= 3; x++) {
				for (int z = 1; z <= 3; z++) {
					if (x != 2 || z != 2) {
						heavy.fill(x, 1, z, x, 1, z, 7800f);
					}
				}
			}
			sea.reshape(hull, heavy);
			assertEquals(103000.0, hull.mass.mass(), 1.0);
			sea.step(400);
			assertEquals(60.0, hull.state.y, 0.1);
			assertEquals(0.0, hull.speed(), 0.05);
			// The nine cells of air left in it are under water and flooded: the rim is 0.89 under the surface.
			assertEquals(9.0, hull.water.floodedVolume, 0.01);
		}
	}
}
