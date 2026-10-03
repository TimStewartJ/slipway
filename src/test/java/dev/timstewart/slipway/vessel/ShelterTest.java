package dev.timstewart.slipway.vessel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.FluidCells;
import dev.timstewart.slipway.physics.FluidField;
import dev.timstewart.slipway.physics.Hull;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Which places a hull keeps dry, with the sea as a function (no game needed): water in every block up to y = 63,
 * its surface at 63.889.
 */
class ShelterTest {
	private static final FluidCells SEA = (x, y, z) -> y <= 63 ? FluidField.encode(8, false) : 0;
	private static final double SURFACE = 63 + 8.0 / 9.0;

	/** An open box of full blocks: {@code sx} by {@code sz} outside, a floor at y = 0 and walls up to y = sy - 1. */
	private static Hull.Builder openBox(int sx, int sy, int sz) {
		Hull.Builder b = new Hull.Builder();
		for (int x = 0; x < sx; x++) {
			for (int z = 0; z < sz; z++) {
				b.block(x, 0, z, 1f, true);
				if (x == 0 || z == 0 || x == sx - 1 || z == sz - 1) {
					for (int y = 1; y < sy; y++) {
						b.block(x, y, z, 1f, true);
					}
				}
			}
		}
		return b;
	}

	private static List<VesselLookup.View> vessel(Hull hull, VesselPose pose) {
		return List.of(new VesselLookup.View() {
			@Override
			public long id() {
				return 1;
			}

			@Override
			public BlockPos anchor() {
				return BlockPos.ZERO;
			}

			@Override
			public VesselPose pose() {
				return pose;
			}

			@Override
			public VesselPose previousPose() {
				return pose;
			}

			@Override
			public AABB worldBounds() {
				return new AABB(-100, -100, -100, 100, 200, 100);
			}

			@Override
			public Vec3 velocity() {
				return Vec3.ZERO;
			}

			@Override
			public Hull hull() {
				return hull;
			}
		});
	}

	/** Whether the point of the vessel's frame is dry with the vessel at the pose. */
	private static boolean dry(Hull hull, VesselPose pose, double lx, double ly, double lz) {
		Vec3 world = pose.localToWorld(new Vec3(lx, ly, lz));
		return Shelter.isDry(SEA, vessel(hull, pose), world.x, world.y, world.z);
	}

	@Test
	void theInsideOfAFloatingHullIsDryBelowTheWaterlineAndTheSeaAroundItIsNot() {
		Hull hull = openBox(5, 3, 5).build();
		// Afloat with a draught of 1.6: the floor's top is 0.6 under the surface.
		VesselPose afloat = VesselPose.at(0, SURFACE - 1.6, 0);
		assertTrue(dry(hull, afloat, 2.5, 1.1, 2.5), "feet on the floor, under the waterline");
		assertTrue(dry(hull, afloat, 1.2, 2.7, 3.8), "higher in the hull, above the waterline");
		assertTrue(dry(hull, afloat, 2.5, 0.99, 2.5), "feet a hair inside the floor block");
		assertFalse(dry(hull, afloat, -0.5, 1.1, 2.5), "in the sea beside the hull");
		assertFalse(dry(hull, afloat, 2.5, -0.5, 2.5), "in the sea under the hull");
		assertFalse(dry(hull, afloat, 2.5, 3.2, 2.5), "above the rim");
		assertFalse(Shelter.isDry(SEA, List.of(), 2.5, SURFACE - 0.5, 2.5), "no vessel there");
	}

	@Test
	void theHullIsDryUntilTheWaterStandsAQuarterOfABlockOverItsRim() {
		Hull hull = openBox(5, 3, 5).build();
		// The rim is at local y = 3. With it at the surface, 0.2 under and 0.3 under:
		assertTrue(dry(hull, VesselPose.at(0, SURFACE - 3.0, 0), 2.5, 1.1, 2.5));
		assertTrue(dry(hull, VesselPose.at(0, SURFACE - 3.2, 0), 2.5, 1.1, 2.5));
		assertFalse(dry(hull, VesselPose.at(0, SURFACE - 3.3, 0), 2.5, 1.1, 2.5));
		assertFalse(dry(hull, VesselPose.at(0, SURFACE - 9.0, 0), 2.5, 1.1, 2.5), "sunk");
		// The same rule as the lift of the physics step: half flooded is where the hull stops counting as dry.
		assertEquals(0.5, Hull.flooding(0.25), 1e-12);
		assertTrue(Shelter.flooded(SEA, hull, VesselPose.at(0, SURFACE - 3.3, 0), hull.cellClass(2, 1, 2)));
		assertFalse(Shelter.flooded(SEA, hull, VesselPose.at(0, SURFACE - 3.2, 0), hull.cellClass(2, 1, 2)));
	}

	@Test
	void aHeeledHullFloodsFromTheSideWhoseRimDips() {
		// A wide, shallow hull tilted until one rim is under water and the other well out of it.
		Hull hull = openBox(9, 3, 9).build();
		VesselPose level = VesselPose.at(0, SURFACE - 2.5, 0);
		assertTrue(dry(hull, level, 1.5, 1.1, 1.5) && dry(hull, level, 7.5, 1.1, 7.5));
		VesselPose rolled = TestPoses.aboutPoint(4.5, 1.5, 4.5, 0, SURFACE - 1.0, 0, 0, 0, 20);
		boolean first = dry(hull, rolled, 1.5, 1.1, 1.5);
		boolean second = dry(hull, rolled, 7.5, 1.1, 7.5);
		assertTrue(first != second, "one corner of the heeled hull is dry and the opposite one flooded: " + first + ", " + second);
		Vec3 rimOverFirst = rolled.localToWorld(new Vec3(1.5, 3.5, 1.5));
		Vec3 rimOverSecond = rolled.localToWorld(new Vec3(7.5, 3.5, 7.5));
		assertTrue(first == rimOverFirst.y > rimOverSecond.y, "the flooded side is the one whose rim is lower");
	}

	@Test
	void aSealedCabinIsDryAtAnyDepthAndAnyAttitude() {
		Hull.Builder b = openBox(5, 4, 5);
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				b.block(x, 4, z, 1f, true);
			}
		}
		Hull hull = b.build();
		assertTrue(dry(hull, VesselPose.at(0, 20, 0), 2.5, 1.1, 2.5));
		assertTrue(dry(hull, VesselPose.fromYawPitchRoll(0, 20, 0, 50, 70, 160), 2.5, 2.5, 2.5));
		assertFalse(dry(hull, VesselPose.at(0, 20, 0), 2.5, 5.5, 2.5), "on its roof");
	}

	@Test
	void theSurfaceIsFoundWithinABlockOfAPoint() {
		assertEquals(SURFACE, Shelter.surfaceNear(SEA, 0.5, 63.5, 0.5), 1e-9);
		assertEquals(SURFACE, Shelter.surfaceNear(SEA, 0.5, 64.9, 0.5), 1e-9);
		assertEquals(SURFACE, Shelter.surfaceNear(SEA, 0.5, 62.1, 0.5), 1e-9);
		assertTrue(Double.isNaN(Shelter.surfaceNear(SEA, 0.5, 61.5, 0.5)), "deep under water the surface is further than a block away");
		assertTrue(Double.isNaN(Shelter.surfaceNear(SEA, 0.5, 65.5, 0.5)), "high above it too");
		FluidCells shallow = (x, y, z) -> y == 10 ? FluidField.encode(3, false) : 0;
		assertEquals(10 + 3 / 9.0, Shelter.surfaceNear(shallow, 0.5, 10.5, 0.5), 1e-9);
		// The level, read through Shelter, gives the physics thread's numbers: both use FluidField's rule.
		assertEquals(SURFACE - 63.2, FluidField.submerged(SEA, 0.5, 63.7, 0.5, 1.0, null), 1e-9);
	}

	/** Poses about a point of the vessel. */
	private static final class TestPoses {
		/** A pose with the given attitude that puts the vessel's local point (lx, ly, lz) at the world point (wx, wy, wz). */
		static VesselPose aboutPoint(double lx, double ly, double lz, double wx, double wy, double wz, double yaw, double pitch, double roll) {
			VesselPose rotation = VesselPose.fromYawPitchRoll(0, 0, 0, yaw, pitch, roll);
			org.joml.Vector3d offset = rotation.rotate(lx, ly, lz, new org.joml.Vector3d());
			return rotation.withPosition(wx - offset.x, wy - offset.y, wz - offset.z);
		}
	}
}
