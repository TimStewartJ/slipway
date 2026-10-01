package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.math.VesselPose;
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
import net.minecraft.server.level.ServerLevel;
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

	/**
	 * Twelve blocks in a row from north to south, the three at the bow of iron, with the helm on the southernmost:
	 * forward is north, and the centre of mass lies eight blocks ahead of the helm.
	 */
	static BlockPos sternHelmedShip(GameTestHelper helper, int x) {
		for (int z = 2; z <= 13; z++) {
			helper.setBlock(new BlockPos(x, 8, z), z <= 4 ? Blocks.IRON_BLOCK : Blocks.OAK_PLANKS);
		}
		BlockPos helm = new BlockPos(x, 9, 13);
		helper.setBlock(helm, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		return helm;
	}

	private static Vec3 centreOfMass(ActiveVessel vessel) {
		org.joml.Vector3d centre = vessel.record.pose.localToWorld(vessel.mass.comX(), vessel.mass.comY(), vessel.mass.comZ(), new org.joml.Vector3d());
		return new Vec3(centre.x, centre.y, centre.z);
	}

	private static void steer(GameTestHelper helper, ActiveVessel vessel, float forward, float yaw) {
		long now = helper.getLevel().getGameTime();
		vessel.input.set(forward, 0f, 0f, 0f, yaw, 0f, now);
		vessel.scriptedInputUntil = now + 2;
	}

	/** How fast a vessel moves over the ground, and the radius of the turn it is in (speed over yaw rate). */
	private static double[] speedAndRadius(VesselRecord record) {
		double speed = Math.hypot(record.linearVelocity.x, record.linearVelocity.z);
		return new double[] {speed, speed / Math.abs(record.angularVelocity.y)};
	}

	/**
	 * A ship steered from its stern, eight blocks from its centre of mass, beside a reference of the same build that
	 * hover only brakes (as before 0.1.2). Turning on the spot leaves the centre of mass where it is, and in a turn
	 * at full thrust the ship with the hold has the reference's speed, radius and path, and stops where it stops.
	 */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void aShipSteeredFromItsSternTurnsAboutItsCentreAndFliesAsUnderThePlainBrake(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		VesselRecord held = TestShips.assemble(helper, sternHelmedShip(helper, 5));
		VesselRecord plain = TestShips.assemble(helper, sternHelmedShip(helper, 10));
		ActiveVessel a = TestShips.active(helper, held);
		ActiveVessel b = TestShips.active(helper, plain);
		b.brakeOnly = true;
		// They fly in the open, 60 and 100 blocks above the arenas (a turn at full thrust is 30 blocks across), over
		// ground that has to stay loaded, or the ships would be unloaded with it. The test runner releases forced
		// chunks when its batch ends.
		BlockPos base = helper.absolutePos(new BlockPos(8, 0, 8));
		for (int cx = base.getX() - 56 >> 4; cx <= base.getX() + 56 >> 4; cx++) {
			for (int cz = base.getZ() - 56 >> 4; cz <= base.getZ() + 56 >> 4; cz++) {
				level.setChunkForced(cx, cz, true);
			}
		}
		Vec3[] centres = new Vec3[2];
		Vec3[] helms = new Vec3[2];
		VesselPose[] poses = new VesselPose[2];
		double[] strayed = new double[2];
		int[] ticks = {0};
		double[] measured = new double[5];
		Runnable track = () -> {
			strayed[0] = Math.max(strayed[0], centreOfMass(a).distanceTo(centres[0]));
			strayed[1] = Math.max(strayed[1], centreOfMass(b).distanceTo(centres[1]));
		};
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, a.hasBody && b.hasBody && a.mass != null && b.mass != null, "no physics bodies yet"))
			.thenExecute(() -> {
				manager.teleport(a, held.pose.withPosition(held.pose.x(), base.getY() + 60, held.pose.z()));
				manager.teleport(b, plain.pose.withPosition(plain.pose.x(), base.getY() + 100, plain.pose.z()));
			})
			.thenIdle(20)
			.thenExecute(() -> {
				centres[0] = centreOfMass(a);
				centres[1] = centreOfMass(b);
				helms[0] = held.pose.position();
				helms[1] = plain.pose.position();
				poses[0] = held.pose;
				poses[1] = plain.pose;
				check(helper, centres[0].distanceTo(helms[0]) > 7.5, "the helm is only " + centres[0].distanceTo(helms[0]) + " blocks from the centre of mass");
			})
			// Yaw on the spot until both have turned a quarter, then let go and wait for them to stop.
			.thenWaitUntil(() -> {
				steer(helper, a, 0f, 1f);
				steer(helper, b, 0f, 1f);
				track.run();
				check(helper, Math.abs(held.pose.yawTurnSinceDegrees(poses[0])) > 90 && Math.abs(plain.pose.yawTurnSinceDegrees(poses[1])) > 90, "not turned a quarter yet");
			})
			.thenWaitUntil(() -> {
				track.run();
				check(helper, held.angularVelocity.length() < 0.002 && plain.angularVelocity.length() < 0.002 && held.linearVelocity.length() < 0.002
					&& plain.linearVelocity.length() < 0.002, "still turning");
			})
			.thenExecute(() -> {
				check(helper, held.pose.position().distanceTo(helms[0]) > 10.0, "the helm only swung " + held.pose.position().distanceTo(helms[0]) + " blocks round the centre of mass");
				check(helper, strayed[0] < 0.01, String.format(Locale.ROOT, "turning on the spot moved the centre of mass %.3f blocks (%.3f under the plain brake)",
					strayed[0], strayed[1]));
				check(helper, strayed[1] < 0.01, String.format(Locale.ROOT, "turning on the spot moved the reference's centre of mass %.3f blocks", strayed[1]));
				centres[0] = centreOfMass(a);
				centres[1] = centreOfMass(b);
			})
			// Full thrust and full yaw for eight seconds: both settle into a circle.
			.thenWaitUntil(() -> {
				steer(helper, a, 1f, 1f);
				steer(helper, b, 1f, 1f);
				check(helper, ++ticks[0] > 160, "turning");
			})
			.thenExecute(() -> {
				double[] with = speedAndRadius(held);
				double[] without = speedAndRadius(plain);
				check(helper, without[0] > 8.0 && without[1] > 5.0 && without[1] < 40.0, "the reference is not in a turn at speed: " + without[0] + " blocks per second, radius " + without[1]);
				check(helper, Math.abs(with[0] - without[0]) < 0.0005 * without[0], String.format(Locale.ROOT, "speed in the turn %.4f, under the plain brake %.4f", with[0], without[0]));
				check(helper, Math.abs(with[1] - without[1]) < 0.0005 * without[1], String.format(Locale.ROOT, "turn radius %.4f, under the plain brake %.4f", with[1], without[1]));
				double apart = centreOfMass(a).subtract(centres[0]).distanceTo(centreOfMass(b).subtract(centres[1]));
				check(helper, apart < 0.01, String.format(Locale.ROOT, "after eight seconds of turning the ship is %.3f blocks from where the plain brake has it", apart));
				measured[0] = with[0];
				measured[1] = without[0];
				measured[2] = with[1];
				measured[3] = without[1];
				measured[4] = apart;
			})
			.thenWaitUntil(() -> check(helper, held.linearVelocity.length() < 0.01 && plain.linearVelocity.length() < 0.01, "still moving"))
			.thenExecute(() -> {
				double apart = centreOfMass(a).subtract(centres[0]).distanceTo(centreOfMass(b).subtract(centres[1]));
				check(helper, apart < 0.01, String.format(Locale.ROOT, "the ship stopped %.3f blocks from where the plain brake stops it", apart));
				dev.timstewart.slipway.Slipway.LOGGER.info(String.format(Locale.ROOT, "Stern-helmed ship: centre of mass strayed %.4f blocks in a quarter turn on the spot (%.4f under the plain brake); "
					+ "at full thrust and yaw %.4f blocks per second on a radius of %.4f (%.4f and %.4f under the plain brake), %.5f blocks apart after eight seconds, %.5f after the stop",
					strayed[0], strayed[1], measured[0], measured[2], measured[1], measured[3], measured[4], apart));
				manager.remove(held.id);
				manager.remove(plain.id);
			})
			.thenSucceed();
	}
}
