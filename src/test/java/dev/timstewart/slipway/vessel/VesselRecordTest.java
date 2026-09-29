package dev.timstewart.slipway.vessel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.RateLimiter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VesselRecordTest {
	static VesselRecord sample(long id, int plot) {
		return new VesselRecord(id, plot, VesselRegion.anchor(plot, 81), new BlockPos(-3, -1, -7), new BlockPos(4, 5, 2), BlockPos.ZERO, Direction.EAST,
			VesselPose.fromYawPitchRoll(1234.5, 90.25, -987.75, 33, -12, 170), new Vec3(1.5, -0.25, 3), new Vec3(0, 0.1, 0), false, true, 321);
	}

	@Test
	void recordRoundTripsThroughNbtAndJson() {
		VesselRecord record = sample(42, 1025);
		for (var ops : new com.mojang.serialization.DynamicOps<?>[] {NbtOps.INSTANCE, JsonOps.INSTANCE}) {
			roundTrip(record, ops);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> void roundTrip(VesselRecord record, com.mojang.serialization.DynamicOps<T> ops) {
		T encoded = VesselRecord.CODEC.encodeStart(ops, record).getOrThrow();
		VesselRecord back = VesselRecord.CODEC.parse(ops, encoded).getOrThrow();
		assertEquals(record.id, back.id);
		assertEquals(record.plot, back.plot);
		assertEquals(record.anchor, back.anchor);
		assertEquals(record.localMin, back.localMin);
		assertEquals(record.localMax, back.localMax);
		assertEquals(record.helmFacing, back.helmFacing);
		assertEquals(record.pose.x(), back.pose.x(), 0.0);
		assertEquals(record.pose.qw(), back.pose.qw(), 1e-12);
		assertEquals(record.linearVelocity, back.linearVelocity);
		assertEquals(record.angularVelocity, back.angularVelocity);
		assertEquals(record.hover, back.hover);
		assertEquals(record.level, back.level);
		assertEquals(record.blockCount, back.blockCount);
	}

	@Test
	void registryRoundTripKeepsAllocationState() {
		VesselRegistry registry = new VesselRegistry();
		long a = registry.allocateId();
		int plotA = registry.allocatePlot();
		registry.add(sample(a, plotA));
		long b = registry.allocateId();
		int plotB = registry.allocatePlot();
		registry.add(sample(b, plotB));
		registry.remove(a);
		registry.freePlot(plotA);
		Tag tag = VesselRegistry.CODEC.encodeStart(NbtOps.INSTANCE, registry).getOrThrow();
		VesselRegistry back = VesselRegistry.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		assertEquals(1, back.size());
		assertNotNull(back.get(b));
		assertEquals(registry.peekNextId(), back.peekNextId());
		// The freed plot is reused before a new one is taken; ids are never reused.
		assertEquals(plotA, back.allocatePlot());
		assertEquals(plotB + 1, back.allocatePlot());
		assertTrue(back.allocateId() > b);
	}

	@Test
	void localAndPlotCoordinatesConvert() {
		VesselRecord record = sample(1, 7);
		BlockPos local = new BlockPos(3, -1, -6);
		assertEquals(local, record.toLocal(record.toPlot(local)));
		assertTrue(record.containsPlotPos(record.toPlot(local)));
		assertFalse(record.containsPlotPos(VesselRegion.anchor(8, 64)));
		record.include(new BlockPos(10, 20, -30));
		assertEquals(new BlockPos(10, 20, 2), record.localMax);
		assertEquals(new BlockPos(-3, -1, -30), record.localMin);
	}

	@Test
	void nonFiniteVelocitiesAreDiscarded() {
		VesselRecord record = new VesselRecord(1, 0, VesselRegion.anchor(0, 64), BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, Direction.UP,
			VesselPose.at(0, 64, 0), new Vec3(Double.NaN, 0, 0), new Vec3(0, Double.POSITIVE_INFINITY, 0), true, true, 1);
		assertEquals(Vec3.ZERO, record.linearVelocity);
		assertEquals(Vec3.ZERO, record.angularVelocity);
		assertEquals(Direction.NORTH, record.helmFacing, "vertical helm facings fall back to north");
	}

	@Test
	void helmInputSanitisesEveryAxis() {
		HelmInput input = new HelmInput();
		input.set(Float.NaN, 5f, -9f, Float.POSITIVE_INFINITY, 0.25f, Float.NEGATIVE_INFINITY, 10);
		assertEquals(0f, input.forward);
		assertEquals(1f, input.strafe);
		assertEquals(-1f, input.vertical);
		assertEquals(0f, input.pitch);
		assertEquals(0.25f, input.yaw);
		assertEquals(0f, input.roll);
		assertFalse(input.isIdle());
		input.clear();
		assertTrue(input.isIdle());
	}

	@Test
	void rateLimiterAllowsABurstPerSecond() {
		RateLimiter limiter = new RateLimiter();
		int accepted = 0;
		for (int i = 0; i < 100; i++) {
			if (limiter.tryAcquire(1000, 40)) {
				accepted++;
			}
		}
		assertEquals(40, accepted);
		assertFalse(limiter.tryAcquire(1019, 40));
		assertTrue(limiter.tryAcquire(1020, 40));
	}
}
