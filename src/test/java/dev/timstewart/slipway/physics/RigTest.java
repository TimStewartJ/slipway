package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class RigTest {
	/** 40 kN from the helm, 20 kN a sail, 500 kg lifted by a cubic metre, 100 m^3 a burner, 30% ballast. */
	static final Rig.Rules RULES = new Rig.Rules(40_000, 20_000, 500 * VesselController.GRAVITY, 100, 0.3);
	static final VesselController.Params PARAMS = new VesselController.Params(12.0, 24.0, 1.6, 0.9, 1.5);
	static final double[] INERTIA = {1000, 0, 0, 0, 1000, 0, 0, 0, 1000};

	/** Feeds one block to a hull and to the rig read from the same blocks. */
	private static void block(Hull.Builder hull, Rig.Builder rig, int x, int y, int z) {
		hull.block(x, y, z, 1f, true);
		rig.block(x, y, z, 1f, true, 0f, 1f);
	}

	private static Rig build(Hull.Builder hull, Rig.Builder rig) {
		return rig.build(hull.build(), rig.hasBurners() ? rig.envelope() : null);
	}

	/** A deck of {@code size} by {@code size} blocks at y = 0. */
	private static void deck(Hull.Builder hull, Rig.Builder rig, int size) {
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				block(hull, rig, x, 0, z);
			}
		}
	}

	/** A canopy over the deck: a roof at {@code top} and walls down to {@code mouth}, open below. */
	private static void canopy(Hull.Builder hull, Rig.Builder rig, int from, int to, int mouth, int top) {
		for (int x = from; x <= to; x++) {
			for (int z = from; z <= to; z++) {
				block(hull, rig, x, top, z);
				if (x == from || x == to || z == from || z == to) {
					for (int y = mouth; y < top; y++) {
						block(hull, rig, x, y, z);
					}
				}
			}
		}
	}

	@Test
	void aSailIsWoolTheWindGetsThrough() {
		Hull.Builder hull = new Hull.Builder();
		Rig.Builder rig = new Rig.Builder();
		deck(hull, rig, 5);
		// A mast with a sail three wide and three high across the deck, standing on the deck's middle row.
		for (int y = 1; y <= 4; y++) {
			block(hull, rig, 2, y, 2);
		}
		for (int x = 1; x <= 3; x++) {
			for (int y = 2; y <= 4; y++) {
				if (x != 2) {
					block(hull, rig, x, y, 2);
				}
				rig.sail(x, y, 2);
			}
		}
		// Wool laid as deck: nothing gets through it.
		rig.sail(0, 0, 0);
		rig.sail(2, 0, 2);
		assertEquals(9, build(hull, rig).sails(), "the nine blocks of the sail, and none of the wool in the deck");
	}

	@Test
	void woolBelowTheRimOfAHullIsNoSail() {
		Hull.Builder hull = HullTest.openBox(5, 4, 5);
		Rig.Builder rig = new Rig.Builder();
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				rig.block(x, 0, z, 1f, true, 0f, 1f);
				if (x == 0 || z == 0 || x == 4 || z == 4) {
					for (int y = 1; y < 4; y++) {
						rig.block(x, y, z, 1f, true, 0f, 1f);
					}
				}
			}
		}
		// A pole of wool from the floor of the hold to three blocks above the gunwale.
		for (int y = 1; y <= 6; y++) {
			block(hull, rig, 2, y, 2);
			rig.sail(2, y, 2);
		}
		assertEquals(3, build(hull, rig).sails(), "only the wool above the rim stands in the wind");
	}

	@Test
	void aCanopyHoldsTheAirAboveItsMouthAndABurnerBelowHeatsIt() {
		Hull.Builder hull = new Hull.Builder();
		Rig.Builder rig = new Rig.Builder();
		deck(hull, rig, 7);
		// Posts from the deck to the canopy, as ropes.
		for (int y = 1; y <= 2; y++) {
			block(hull, rig, 0, y, 0);
			block(hull, rig, 6, y, 6);
		}
		// 7 x 7 outside, roof at y = 8, walls from y = 3: five by five by five cells of air held.
		canopy(hull, rig, 0, 6, 3, 8);
		rig.burner(3, 1, 3);
		Rig balloon = build(hull, rig);
		assertEquals(125.0, balloon.envelope(), 1e-6);
		assertEquals(1, balloon.burners());
		assertEquals(100.0, balloon.hotAir(RULES), 1e-6, "one burner heats a hundred of the hundred and twenty-five");
		// The skin of the canopy is wool with air on one side only.
		rig.sail(0, 5, 3);
		rig.sail(3, 8, 3);
		assertEquals(0, build(hull, rig).sails(), "the skin of a balloon is no sail");
	}

	@Test
	void aBurnerUnderAFloorOrUnderTheOpenSkyHeatsNothing() {
		Hull.Builder hull = new Hull.Builder();
		Rig.Builder rig = new Rig.Builder();
		deck(hull, rig, 7);
		canopy(hull, rig, 0, 6, 3, 8);
		for (int y = 1; y <= 2; y++) {
			block(hull, rig, 0, y, 0);
		}
		// A lid over the first burner; the second stands beside the deck, clear of the canopy.
		block(hull, rig, 3, 2, 3);
		rig.burner(3, 1, 3);
		block(hull, rig, 9, 0, 3);
		block(hull, rig, 8, 0, 3);
		block(hull, rig, 7, 0, 3);
		rig.burner(9, 1, 3);
		Rig rigged = build(hull, rig);
		assertEquals(0, rigged.burners());
		assertEquals(0.0, rigged.lift(RULES), 1e-9);
		assertTrue(rigged.envelope() > 100, "the air is held all the same");
	}

	@Test
	void withoutABurnerNoEnvelopeIsLookedFor() {
		Hull.Builder hull = new Hull.Builder();
		Rig.Builder rig = new Rig.Builder();
		deck(hull, rig, 7);
		canopy(hull, rig, 0, 6, 3, 8);
		Rig cold = build(hull, rig);
		assertEquals(0.0, cold.envelope(), 0.0);
		assertEquals(0.0, cold.liftRatio(RULES, 1000), 0.0);
	}

	@Test
	void thrustOverMassIsTheAccelerationAndSetsTheTopSpeed() {
		Rig oars = new Rig(0, 0, 0);
		Rig sails = new Rig(12, 0, 0);
		// 40 kN on 40 t is 1 m/s^2: a twelfth of full thrust, so a twelfth of the full 24 m/s.
		assertEquals(1.0, oars.rating(RULES, 40_000, 12.0, 0).thrust(), 1e-9);
		assertEquals(2.0, oars.topSpeed(RULES, 40_000, 12.0, 24.0), 1e-9);
		// Twelve sails add 240 kN: 7 m/s^2 and 14 m/s.
		assertEquals(7.0, sails.rating(RULES, 40_000, 12.0, 0).thrust(), 1e-9);
		assertEquals(14.0, sails.topSpeed(RULES, 40_000, 12.0, 24.0), 1e-9);
		// Never more than the full thrust every vessel had before the rules.
		assertEquals(12.0, sails.rating(RULES, 1_000, 12.0, 0).thrust(), 1e-9);
		assertEquals(24.0, sails.topSpeed(RULES, 1_000, 12.0, 24.0), 1e-9);
		// A heavier ship under the same rig turns more slowly, but always answers its helm.
		assertEquals(Math.sqrt(7.0 / 12.0), sails.rating(RULES, 40_000, 12.0, 0).turn(), 1e-9);
		assertEquals(Rig.LEAST_TURN, oars.rating(RULES, 4_000_000, 12.0, 0).turn(), 1e-9);
	}

	@Test
	void aVesselFliesWhenItsHotAirLiftsItsWeight() {
		// 150 m^3 held, two burners: 150 heated, lifting 75 t.
		Rig balloon = new Rig(0, 2, 150);
		assertEquals(150.0, balloon.hotAir(RULES), 1e-9);
		VesselController.Rating light = balloon.rating(RULES, 50_000, 12.0, 0);
		assertTrue(light.airworthy());
		assertEquals(1.5, balloon.liftRatio(RULES, 50_000), 1e-9);
		assertEquals(0.5 * VesselController.GRAVITY, light.climb(), 1e-9, "it climbs with the lift it has to spare");
		assertEquals(Rig.VENT, light.sink(), 1e-9);
		assertEquals(0.0, light.lift(), 0.0);
		VesselController.Rating heavy = balloon.rating(RULES, 100_000, 12.0, 0);
		assertFalse(heavy.airworthy());
		assertEquals(0.75 * VesselController.GRAVITY, heavy.lift(), 1e-9, "too heavy to fly, it is three quarters lighter");
		assertEquals(0.0, heavy.climb(), 0.0);
		// One burner heats a hundred of the hundred and fifty.
		assertEquals(100.0, new Rig(0, 1, 150).hotAir(RULES), 1e-9);
		// Exactly its weight in lift: it still gets off the ground.
		assertEquals(Rig.LEAST_CLIMB, balloon.rating(RULES, 75_000, 12.0, 0).climb(), 1e-9);
	}

	@Test
	void ballastWorksOnlyInWaterAndInProportion() {
		Rig rig = new Rig(0, 0, 0);
		assertEquals(0.0, rig.rating(RULES, 10_000, 12.0, 0).trim(), 0.0, "out of the water there is nothing to trim against");
		assertEquals(0.3 * VesselController.GRAVITY, rig.rating(RULES, 10_000, 12.0, 10_000).trim(), 1e-9, "afloat, it displaces its weight");
		assertEquals(0.15 * VesselController.GRAVITY, rig.rating(RULES, 10_000, 12.0, 5_000).trim(), 1e-9);
		assertEquals(0.3 * VesselController.GRAVITY, rig.rating(RULES, 10_000, 12.0, 30_000).trim(), 1e-9, "held under, no more than the full share");
	}

	private static VesselController.Command compute(double forward, double vertical, double yaw, boolean hover, Vector3d velocity, VesselController.Rating rating) {
		return VesselController.compute(PARAMS, forward, 0, vertical, 0, yaw, 0, hover, true, new Quaterniond(), new Vector3d(), velocity, new Vector3d(), 1000.0, INERTIA,
			new Vector3d(0, 0, -1), null, null, rating);
	}

	@Test
	void aRatedVesselIsDrivenByItsRating() {
		VesselController.Rating rating = new VesselController.Rating(3.0, 2.0, 5.0, 0.5, true, 0, 0);
		// Forward at rest: the rating's thrust, and hover carries the weight.
		VesselController.Command ahead = compute(1, 0, 0, true, new Vector3d(), rating);
		assertEquals(-3.0 * 1000, ahead.force().z, 1e-6);
		assertEquals(VesselController.GRAVITY * 1000, ahead.force().y, 1e-6);
		// The same drag as every vessel: at a quarter of the full speed a quarter of the full thrust is used up.
		assertEquals(0.0, compute(1, 0, 0, true, new Vector3d(0, 0, -6), rating).force().z, 1e-6);
		// Up with the climb, down with the sink.
		assertEquals((VesselController.GRAVITY + 2.0) * 1000, compute(0, 1, 0, true, new Vector3d(), rating).force().y, 1e-6);
		assertEquals((VesselController.GRAVITY - 5.0) * 1000, compute(0, -1, 0, true, new Vector3d(), rating).force().y, 1e-6);
		// Half the turn rate is asked for.
		double free = compute(0, 0, 1, true, new Vector3d(), null).torque().y;
		assertEquals(free * 0.5, compute(0, 0, 1, true, new Vector3d(), rating).torque().y, 1e-6);
	}

	@Test
	void withoutTheLiftForItsWeightHoverDoesNotHoldAVesselUp() {
		VesselController.Rating heavy = new VesselController.Rating(3.0, 0, 0, 1.0, false, 4.0, 0);
		// Hover asked for: no weight carried, only the lift it has, and the helm's up does nothing in the air.
		assertEquals(4.0 * 1000, compute(0, 0, 0, true, new Vector3d(), heavy).force().y, 1e-6);
		assertEquals(4.0 * 1000, compute(0, 1, 0, true, new Vector3d(), heavy).force().y, 1e-6);
		// Hover off: the burners are shut, there is no lift at all.
		assertEquals(0.0, compute(0, 0, 0, false, new Vector3d(), heavy).force().y, 1e-6);
		// Falling, it is slowed by drag like a vessel that is not hovering (0.5 per second), not braked like one that is.
		assertEquals((4.0 + 0.5 * 2.0) * 1000, compute(0, 0, 0, true, new Vector3d(0, -2, 0), heavy).force().y, 1e-6);
		// In water its ballast pushes it up or down.
		VesselController.Rating afloat = new VesselController.Rating(3.0, 0, 0, 1.0, false, 0, 2.5);
		assertEquals(2.5 * 1000, compute(0, 1, 0, false, new Vector3d(), afloat).force().y, 1e-6);
		assertEquals(-2.5 * 1000, compute(0, -1, 0, false, new Vector3d(), afloat).force().y, 1e-6);
	}

	@Test
	void aFreeVesselIsDrivenAsBefore() {
		VesselController.Command free = compute(1, 1, 1, true, new Vector3d(1, 2, 3), null);
		VesselController.Command before = VesselController.compute(PARAMS, 1, 0, 1, 0, 1, 0, true, true, new Quaterniond(), new Vector3d(), new Vector3d(1, 2, 3),
			new Vector3d(), 1000.0, INERTIA, new Vector3d(0, 0, -1), null, null);
		assertEquals(before.force(), free.force());
		assertEquals(before.torque(), free.torque());
	}
}
