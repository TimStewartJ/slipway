package dev.timstewart.slipway.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class VesselPoseTest {
	@Test
	void localAndWorldRoundTripExactlyFarFromTheOrigin() {
		VesselPose pose = VesselPose.fromYawPitchRoll(24_002_048.25, 71.5, 27_500_000.75, 37, -61, 143);
		for (Vec3 local : new Vec3[] {Vec3.ZERO, new Vec3(12.5, -3, 7.25), new Vec3(-200, 40, 150)}) {
			Vec3 world = pose.localToWorld(local);
			Vec3 back = pose.worldToLocal(world);
			assertEquals(local.x, back.x, 1e-6);
			assertEquals(local.y, back.y, 1e-6);
			assertEquals(local.z, back.z, 1e-6);
		}
	}

	@Test
	void identityKeepsBlocksWhereTheyWere() {
		VesselPose pose = VesselPose.at(100, 64, -50);
		Vec3 world = pose.localToWorld(new Vec3(1, 2, 3));
		assertEquals(new Vec3(101, 66, -47), world);
		assertEquals(0.0, pose.tiltDegrees(), 1e-9);
		assertEquals(0, pose.nearestQuarterTurns());
	}

	@Test
	void quarterTurnsMatchMinecraftBlockRotation() {
		for (int turns = 0; turns < 4; turns++) {
			VesselPose pose = VesselPose.fromYawPitchRoll(0, 0, 0, 90.0 * turns, 0, 0);
			assertEquals(turns, pose.nearestQuarterTurns(), "turns " + turns);
			Rotation rotation = VesselPose.minecraftRotation(turns);
			for (BlockPos local : new BlockPos[] {new BlockPos(3, 1, 0), new BlockPos(0, 2, 5), new BlockPos(-4, 0, 7)}) {
				int[] rotated = VesselPose.rotateQuarterTurns(local.getX(), local.getZ(), turns);
				BlockPos minecraft = local.rotate(rotation);
				assertEquals(minecraft.getX(), rotated[0], "x for " + local + " at " + turns);
				assertEquals(minecraft.getZ(), rotated[1], "z for " + local + " at " + turns);
				// The continuous pose agrees with the integer rotation on block centres.
				Vector3d w = pose.rotate(local.getX(), local.getY(), local.getZ(), new Vector3d());
				assertEquals(rotated[0], w.x, 1e-9);
				assertEquals(rotated[1], w.z, 1e-9);
			}
		}
	}

	@Test
	void nearestQuarterTurnRoundsSmallYawErrors() {
		assertEquals(1, VesselPose.fromYawPitchRoll(0, 0, 0, 81, 0, 0).nearestQuarterTurns());
		assertEquals(0, VesselPose.fromYawPitchRoll(0, 0, 0, -40, 0, 0).nearestQuarterTurns());
		assertEquals(3, VesselPose.fromYawPitchRoll(0, 0, 0, -50, 0, 0).nearestQuarterTurns());
		assertEquals(2, VesselPose.fromYawPitchRoll(0, 0, 0, 181, 0, 0).nearestQuarterTurns());
	}

	@Test
	void tiltAndAttitudeFollowPitchAndRoll() {
		assertEquals(30.0, VesselPose.fromYawPitchRoll(0, 0, 0, 0, 30, 0).tiltDegrees(), 1e-6);
		assertEquals(45.0, VesselPose.fromYawPitchRoll(0, 0, 0, 70, 0, 45).tiltDegrees(), 1e-6);
		assertEquals(180.0, VesselPose.fromYawPitchRoll(0, 0, 0, 0, 0, 180).tiltDegrees(), 1e-6);
		double[] attitude = VesselPose.fromYawPitchRoll(0, 0, 0, 0, -20, 0).attitudeDegrees();
		// Rotating about +X by -20 degrees lifts +Z: nose up is positive pitch on the HUD.
		assertEquals(20.0, attitude[0], 1e-6);
		double[] banked = VesselPose.fromYawPitchRoll(0, 0, 0, 0, 0, 30).attitudeDegrees();
		assertEquals(30.0, Math.abs(banked[2]), 1e-6);
	}

	@Test
	void interpolationIsSmoothAndClamped() {
		VesselPose a = VesselPose.fromYawPitchRoll(0, 64, 0, 0, 0, 0);
		VesselPose b = VesselPose.fromYawPitchRoll(10, 74, -10, 90, 0, 0);
		VesselPose mid = a.interpolate(b, 0.5);
		assertEquals(5, mid.x(), 1e-9);
		assertEquals(69, mid.y(), 1e-9);
		assertEquals(45.0, Math.abs(mid.headingDegrees()), 1e-6);
		VesselPose clamped = a.interpolate(b, 2.0);
		VesselPose end = a.interpolate(b, 1.0);
		assertEquals(end.x(), clamped.x(), 1e-12);
		assertEquals(end.headingDegrees(), clamped.headingDegrees(), 1e-9);
		assertEquals(a.x(), a.interpolate(b, -1).x(), 1e-12);
	}

	@Test
	void nonFinitePosesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new VesselPose(Double.NaN, 0, 0, 0, 0, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new VesselPose(0, 0, 0, 0, 0, Double.POSITIVE_INFINITY, 1));
		assertTrue(new VesselPose(0, 0, 0, 0, 0, 0, 0).normalized().qw() == 1.0, "a zero quaternion falls back to identity");
	}
}
