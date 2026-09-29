package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Jolt bodies in a real level: gravity, terrain contact, hover, helm control, rotation, vessel-vessel contact. */
public class PhysicsGameTests {
	private static final String ARENA = "slipway:arena";

	/** A 3x3 plank raft with a helm (facing south, so forward is north) at its centre; returns the helm position. */
	static BlockPos raft(GameTestHelper helper, int cx, int y, int cz) {
		for (int x = cx - 1; x <= cx + 1; x++) {
			for (int z = cz - 1; z <= cz + 1; z++) {
				helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
			}
		}
		BlockPos helm = new BlockPos(cx, y + 1, cz);
		helper.setBlock(helm, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		return helm;
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void withoutHoverAVesselFallsAndRestsOnTheGround(GameTestHelper helper) {
		for (int x = 2; x <= 12; x++) {
			for (int z = 2; z <= 12; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		BlockPos helm = raft(helper, 7, 9, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		vessel.record.hover = false;
		double floorTop = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
		helper.succeedWhen(() -> {
			check(helper, vessel.hasBody, "no physics body yet");
			// The raft's lowest face is local y = -1 below the helm.
			double bottom = record.pose.y() - 1.0;
			check(helper, Math.abs(bottom - floorTop) < 0.08, String.format(Locale.ROOT, "raft bottom at %.3f, floor top %.3f", bottom, floorTop));
			check(helper, record.linearVelocity.length() < 0.05, "still moving at " + record.linearVelocity.length());
			check(helper, record.pose.tiltDegrees() < 2.0, "landed tilted " + record.pose.tiltDegrees());
		});
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void aNewVesselGetsItsLongRangeProxy(GameTestHelper helper) {
		BlockPos helm = raft(helper, 7, 8, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		TestShips.active(helper, record);
		helper.succeedWhen(() -> {
			// 9 planks and the helm, every one exposed: one entry (position, colour) per block.
			check(helper, record.proxy.length == 20, "proxy entries " + record.proxy.length / 2);
			check(helper, record.proxyRevision > 0, "proxy revision " + record.proxyRevision);
		});
	}

	@GameTest(structure = ARENA, maxTicks = 120)
	public void hoverHoldsAVesselInPlace(GameTestHelper helper) {
		BlockPos helm = raft(helper, 7, 8, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		Vec3 start = record.pose.position();
		helper.runAfterDelay(80, () -> {
			check(helper, vessel.hasBody, "no physics body");
			double moved = record.pose.position().distanceTo(start);
			check(helper, moved < 0.1, "hovering vessel drifted " + moved);
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 120)
	public void forwardThrustMovesAlongTheHelmsForward(GameTestHelper helper) {
		BlockPos helm = raft(helper, 7, 8, 12);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		VesselManager manager = VesselManager.get(helper.getLevel());
		helper.runAfterDelay(5, () -> {
			Vec3 start = record.pose.position();
			long now = helper.getLevel().getGameTime();
			vessel.input.set(1f, 0f, 0f, 0f, 0f, 0f, now);
			vessel.scriptedInputUntil = now + 10;
			helper.runAfterDelay(30, () -> {
				Vec3 moved = record.pose.position().subtract(start);
				// Forward is north (-Z): at 12 m/s^2 for 0.8 s and hover braking after, the raft travels a few blocks.
				check(helper, moved.z < -2.0, "moved " + moved + ", expected north");
				check(helper, Math.abs(moved.x) < 0.3 && Math.abs(moved.y) < 0.3, "drifted sideways or vertically: " + moved);
				manager.disassemble(record.id, null);
				helper.succeed();
			});
		});
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void pitchInputRotatesThroughInvertedWithoutGimbalLock(GameTestHelper helper) {
		BlockPos helm = raft(helper, 7, 8, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		record.level = false;
		double[] maxTilt = {0};
		boolean[] passedVertical = {false};
		helper.onEachTick(() -> {
			if (!vessel.hasBody) {
				return;
			}
			long now = helper.getLevel().getGameTime();
			vessel.input.set(0f, 0f, 0f, 1f, 0f, 0f, now);
			vessel.scriptedInputUntil = now + 2;
			double tilt = record.pose.tiltDegrees();
			maxTilt[0] = Math.max(maxTilt[0], tilt);
			double[] attitude = record.pose.attitudeDegrees();
			if (Math.abs(attitude[0]) > 80) {
				passedVertical[0] = true;
			}
			check(helper, Double.isFinite(tilt) && Double.isFinite(attitude[2]), "non-finite attitude");
		});
		helper.succeedWhen(() -> {
			check(helper, passedVertical[0], "never pitched past 80 degrees");
			check(helper, maxTilt[0] > 170, String.format(Locale.ROOT, "max tilt %.1f, never inverted", maxTilt[0]));
		});
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void levelModeRightsARolledVessel(GameTestHelper helper) {
		BlockPos helm = raft(helper, 7, 8, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		VesselManager manager = VesselManager.get(helper.getLevel());
		manager.teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 30, 0, 50));
		helper.succeedWhen(() -> {
			check(helper, vessel.hasBody, "no body");
			double tilt = record.pose.tiltDegrees();
			check(helper, tilt < 3.0, String.format(Locale.ROOT, "still tilted %.1f", tilt));
			double heading = record.pose.attitudeDegrees()[1];
			check(helper, Math.abs(Math.abs(heading) - 30) < 8 || Math.abs(heading + 30) < 8, String.format(Locale.ROOT, "heading changed to %.1f", heading));
		});
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void vesselsCollideInsteadOfPassingThrough(GameTestHelper helper) {
		BlockPos helmA = raft(helper, 3, 6, 7);
		BlockPos helmB = raft(helper, 11, 6, 7);
		VesselRecord a = TestShips.assemble(helper, helmA);
		VesselRecord b = TestShips.assemble(helper, helmB);
		ActiveVessel vesselA = TestShips.active(helper, a);
		ActiveVessel vesselB = TestShips.active(helper, b);
		// Ram A east into B: strafe right (east when facing north) for a second.
		helper.runAfterDelay(5, () -> {
			long now = helper.getLevel().getGameTime();
			vesselA.input.set(0f, 1f, 0f, 0f, 0f, 0f, now);
			vesselA.scriptedInputUntil = now + 20;
		});
		double startB = b.pose.x();
		double[] closest = {Double.MAX_VALUE};
		helper.onEachTick(() -> closest[0] = Math.min(closest[0], b.pose.x() - a.pose.x()));
		helper.runAfterDelay(80, () -> {
			// Rafts are 3 wide: their centres can never be closer than 3 blocks.
			check(helper, closest[0] > 2.9, String.format(Locale.ROOT, "rafts overlapped: centres %.2f apart", closest[0]));
			check(helper, b.pose.x() - startB > 0.3, String.format(Locale.ROOT, "the struck raft did not move (%.2f)", b.pose.x() - startB));
			check(helper, vesselA.hasBody && vesselB.hasBody, "bodies missing");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void addingBlocksMakesAVesselHeavier(GameTestHelper helper) {
		BlockPos helm = raft(helper, 7, 8, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		helper.runAfterDelay(5, () -> {
			check(helper, vessel.mass != null, "no mass yet");
			double before = vessel.mass.mass();
			BlockPos plotPos = record.anchor.offset(0, -1, 2);
			helper.getLevel().setBlock(plotPos, Blocks.IRON_BLOCK.defaultBlockState(), 3);
			helper.runAfterDelay(3, () -> {
				double after = vessel.mass.mass();
				check(helper, after > before + 7000, "mass " + before + " -> " + after + " after adding an iron block");
				check(helper, record.localMin.getZ() <= -1 && record.localMax.getZ() >= 2, "bounds did not grow: " + record.localMin + ".." + record.localMax);
				helper.succeed();
			});
		});
	}
}
