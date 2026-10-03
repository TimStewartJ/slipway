package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Loose vessels in a real level: no control in free fall, resting on and riding a hovering carrier, sliding off a
 * rolled one, a pile, sleeping on terrain, and the command that switches the mode.
 */
public class LooseGameTests {
	private static final String ARENA = "slipway:arena";

	/**
	 * A crate: blocks of one kind at the given offsets from {@code base} with a helm on top of the first; returns the
	 * helm's position. The vessel's origin is its helm, so the crate's lowest face is one block below its pose.
	 */
	static BlockPos crate(GameTestHelper helper, BlockPos base, Block block, BlockPos... more) {
		helper.setBlock(base, block);
		for (BlockPos offset : more) {
			helper.setBlock(base.offset(offset), block);
		}
		helper.setBlock(base.above(), SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		return base.above();
	}

	private static void steer(GameTestHelper helper, ActiveVessel vessel, float forward, float yaw, float roll) {
		long now = helper.getLevel().getGameTime();
		vessel.input.set(forward, 0f, 0f, 0f, yaw, roll, now);
		vessel.scriptedInputUntil = now + 2;
	}

	/** A vessel's origin in another vessel's frame. */
	private static Vec3 in(VesselRecord vessel, VesselRecord frame) {
		return frame.pose.worldToLocal(vessel.pose.position());
	}

	@GameTest(structure = ARENA, maxTicks = 120)
	public void aLooseVesselInFreeFallKeepsItsSpinAndAControlledOneIsBraked(GameTestHelper helper) {
		VesselRecord loose = TestShips.assemble(helper, PhysicsGameTests.raft(helper, 4, 13, 8));
		VesselRecord controlled = TestShips.assemble(helper, PhysicsGameTests.raft(helper, 12, 13, 8));
		ActiveVessel a = TestShips.active(helper, loose);
		ActiveVessel b = TestShips.active(helper, controlled);
		double[] spin = new double[2];
		helper.startSequence()
			// Spin both up with the helm's own yaw control while they hover.
			.thenWaitUntil(() -> {
				steer(helper, a, 0f, 1f, 0f);
				steer(helper, b, 0f, 1f, 0f);
				check(helper, a.hasBody && b.hasBody && loose.angularVelocity.length() > 0.7 && controlled.angularVelocity.length() > 0.7, "not spinning yet");
			})
			// Let both fall with the helm released: one loose, the other only without hover and level.
			.thenExecute(() -> {
				spin[0] = loose.angularVelocity.length();
				spin[1] = controlled.angularVelocity.length();
				a.input.clear();
				b.input.clear();
				a.scriptedInputUntil = b.scriptedInputUntil = Long.MIN_VALUE;
				loose.loose = true;
				controlled.hover = false;
				controlled.level = false;
			})
			.thenIdle(16)
			.thenExecute(() -> {
				// Three quarters of a second: the turn-rate controller (gain 3 per second) leaves a tenth of the spin.
				check(helper, loose.angularVelocity.length() > 0.9 * spin[0], String.format(Locale.ROOT, "the loose vessel's spin fell from %.3f to %.3f",
					spin[0], loose.angularVelocity.length()));
				check(helper, controlled.angularVelocity.length() < 0.3 * spin[1], String.format(Locale.ROOT, "the controlled vessel's spin only fell from %.3f to %.3f",
					spin[1], controlled.angularVelocity.length()));
				check(helper, loose.linearVelocity.y < -6.5, "the loose vessel falls at " + loose.linearVelocity.y);
				check(helper, loose.linearVelocity.y < controlled.linearVelocity.y - 0.3, "air drag slowed the loose vessel's fall as much as the controlled one's: "
					+ loose.linearVelocity.y + " vs " + controlled.linearVelocity.y);
				check(helper, loose.hover && loose.level, "loose changed the hover or level setting");
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void aLooseVesselRestsOnAHoveringCarrierAndRidesAlong(GameTestHelper helper) {
		VesselRecord carrier = TestShips.assemble(helper, InteractionGameTests.deck(helper, 8, 3, 11, 3));
		VesselRecord crate = TestShips.assemble(helper, crate(helper, new BlockPos(10, 8, 12), Blocks.OAK_PLANKS));
		ActiveVessel carrying = TestShips.active(helper, carrier);
		double startY = carrier.pose.y();
		double startZ = carrier.pose.z();
		Vec3[] rested = new Vec3[1];
		int[] thrustTicks = {0};
		crate.loose = true;
		helper.startSequence()
			.thenWaitUntil(() -> assertRestsOnDeck(helper, crate, carrier))
			.thenExecute(() -> {
				rested[0] = in(crate, carrier);
				// A crate of 1.4 t on a deck of 35 t: the hold lets the carrier sag 0.04 g / 2.25 = 0.17 blocks, no more.
				check(helper, Math.abs(carrier.pose.y() - startY) < 0.3, String.format(Locale.ROOT, "the carrier moved %.3f blocks vertically under the crate",
					carrier.pose.y() - startY));
				// The crate lies 2.2 blocks off the centre of this small deck: level mode gives by about three degrees.
				check(helper, carrier.pose.tiltDegrees() < 6.0, "the crate tipped the carrier " + carrier.pose.tiltDegrees() + " degrees");
			})
			// Fly gently north for a second and a half, then let the carrier stop.
			.thenWaitUntil(() -> {
				steer(helper, carrying, 0.25f, 0f, 0f);
				check(helper, ++thrustTicks[0] > 30, "thrusting");
			})
			.thenIdle(10)
			.thenWaitUntil(() -> check(helper, carrier.linearVelocity.length() < 0.05, "the carrier still moves"))
			.thenExecute(() -> {
				double travelled = startZ - carrier.pose.z();
				check(helper, travelled > 2.0, String.format(Locale.ROOT, "the carrier only flew %.2f blocks", travelled));
				assertRestsOnDeck(helper, crate, carrier);
				double slid = in(crate, carrier).distanceTo(rested[0]);
				check(helper, slid < 0.3, String.format(Locale.ROOT, "the crate slid %.2f blocks on the deck while the carrier flew %.2f", slid, travelled));
				check(helper, Math.abs(carrier.pose.y() - startY) < 0.3, "the loaded carrier changed height while flying: " + (carrier.pose.y() - startY));
			})
			.thenSucceed();
	}

	/** The crate (helm on one block) stands on the carrier's deck: its origin one block above the deck, moving with it. */
	private static void assertRestsOnDeck(GameTestHelper helper, VesselRecord crate, VesselRecord carrier) {
		ActiveVessel a = TestShips.active(helper, crate);
		check(helper, a != null && a.hasBody && TestShips.active(helper, carrier).hasBody, "no physics bodies yet");
		Vec3 local = in(crate, carrier);
		check(helper, Math.abs(local.y - 1.0) < 0.06, String.format(Locale.ROOT, "the crate's bottom is %.3f blocks above the deck", local.y - 1.0));
		check(helper, Math.abs(local.x) < 3.5 && Math.abs(local.z) < 3.5, "the crate is not over the deck: " + local);
		double relative = crate.linearVelocity.subtract(carrier.linearVelocity).length();
		check(helper, relative < 0.05 && crate.angularVelocity.length() < 0.05, String.format(Locale.ROOT, "the crate still moves on the deck at %.3f, spin %.3f",
			relative, crate.angularVelocity.length()));
	}

	@GameTest(structure = ARENA, maxTicks = 500)
	public void aLooseVesselSlidesOffASteeplyRolledCarrier(GameTestHelper helper) {
		VesselRecord carrier = TestShips.assemble(helper, InteractionGameTests.deck(helper, 8, 6, 8, 3));
		VesselRecord crate = TestShips.assemble(helper, crate(helper, new BlockPos(8, 8, 10), Blocks.OAK_PLANKS));
		ActiveVessel carrying = TestShips.active(helper, carrier);
		carrier.level = false;
		crate.loose = true;
		Vec3[] rested = new Vec3[1];
		double[] slidGently = {0};
		helper.startSequence()
			.thenWaitUntil(() -> assertRestsOnDeck(helper, crate, carrier))
			.thenExecute(() -> rested[0] = in(crate, carrier))
			// Roll with the carrier's own roll control. Wood on wood has friction 0.6: the crate holds up to 31 degrees.
			.thenWaitUntil(() -> {
				double tilt = carrier.pose.tiltDegrees();
				if (tilt < 20.0) {
					slidGently[0] = Math.max(slidGently[0], in(crate, carrier).distanceTo(rested[0]));
				}
				if (tilt < 45.0) {
					steer(helper, carrying, 0f, 0f, 0.2f);
				}
				check(helper, tilt >= 45.0, "rolled " + tilt + " degrees so far");
			})
			.thenWaitUntil(() -> {
				Vec3 local = in(crate, carrier);
				check(helper, Math.abs(local.x) > 4.5 || local.y < -1.0, String.format(Locale.ROOT, "the crate is still on a deck rolled %.0f degrees, at %s",
					carrier.pose.tiltDegrees(), local));
			})
			.thenExecute(() -> {
				check(helper, slidGently[0] < 0.15, String.format(Locale.ROOT, "the crate slid %.2f blocks while the deck was rolled less than 20 degrees", slidGently[0]));
				check(helper, carrier.pose.tiltDegrees() > 40.0, "the carrier did not hold its roll");
				check(helper, crate.pose.position().isFinite() && crate.linearVelocity.length() < 60, "the sliding crate was thrown: speed " + crate.linearVelocity.length());
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 600)
	public void aPileOfLooseVesselsOnACarrierStaysCalm(GameTestHelper helper) {
		// A tray: a 7x7 floor with a rim two blocks high and its helm in the middle.
		for (int x = 5; x <= 11; x++) {
			for (int z = 5; z <= 11; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.SPRUCE_PLANKS);
				if (x == 5 || x == 11 || z == 5 || z == 11) {
					helper.setBlock(new BlockPos(x, 2, z), Blocks.SPRUCE_PLANKS);
					helper.setBlock(new BlockPos(x, 3, z), Blocks.SPRUCE_PLANKS);
				}
			}
		}
		BlockPos trayHelm = new BlockPos(8, 2, 8);
		helper.setBlock(trayHelm, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		VesselRecord tray = TestShips.assemble(helper, trayHelm);
		// Eight pieces of two to five blocks in two layers over the tray, none touching another.
		BlockPos east = new BlockPos(1, 0, 0);
		BlockPos south = new BlockPos(0, 0, 1);
		BlockPos up = new BlockPos(0, 1, 0);
		List<VesselRecord> pieces = new ArrayList<>();
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(6, 6, 6), Blocks.OAK_PLANKS)));
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(9, 6, 6), Blocks.OAK_PLANKS, east)));
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(6, 6, 9), Blocks.STONE, south)));
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(9, 6, 9), Blocks.OAK_PLANKS, east, south)));
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(6, 10, 6), Blocks.OAK_PLANKS, east, south, new BlockPos(1, 0, 1))));
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(9, 10, 6), Blocks.STONE)));
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(6, 10, 9), Blocks.OAK_PLANKS, east)));
		pieces.add(TestShips.assemble(helper, crate(helper, new BlockPos(9, 10, 9), Blocks.IRON_BLOCK, south)));
		check(helper, pieces.stream().mapToInt(p -> p.blockCount).sum() == 2 + 3 + 3 + 4 + 5 + 2 + 3 + 3, "the pieces were not assembled apart from each other");
		double startY = tray.pose.y();
		double[] fastest = {0};
		int[] calmTicks = {0};
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.active(helper, tray).hasBody && pieces.stream().allMatch(p -> TestShips.active(helper, p).hasBody), "no bodies yet"))
			.thenExecute(() -> pieces.forEach(p -> p.loose = true))
			.thenExecuteFor(300, () -> {
				for (VesselRecord piece : pieces) {
					Vec3 local = in(piece, tray);
					check(helper, piece.pose.position().isFinite() && piece.linearVelocity.isFinite() && piece.angularVelocity.isFinite(), "vessel " + piece.id + " is not finite");
					check(helper, Math.abs(local.x) < 4.0 && Math.abs(local.z) < 4.0 && local.y > -0.5 && local.y < 14.0,
						"vessel " + piece.id + " left the tray: " + local);
					fastest[0] = Math.max(fastest[0], piece.linearVelocity.length());
				}
			})
			.thenWaitUntil(() -> {
				boolean calm = pieces.stream().allMatch(p -> p.linearVelocity.subtract(tray.linearVelocity).length() < 0.05 && p.angularVelocity.length() < 0.05);
				calmTicks[0] = calm ? calmTicks[0] + 1 : 0;
				check(helper, calmTicks[0] >= 40, "the pile is not calm");
			})
			.thenExecute(() -> {
				// The upper layer falls eight blocks at most: 12.5 m/s. Much more means a contact threw something.
				check(helper, fastest[0] < 16.0, "a piece reached " + fastest[0] + " blocks per second");
				List<VesselRecord> all = new ArrayList<>(pieces);
				all.add(tray);
				ServerLevel level = helper.getLevel();
				for (VesselRecord a : pieces) {
					for (VesselRecord b : all) {
						if (a != b) {
							double depth = overlap(level, a, b);
							check(helper, depth < 0.12, String.format(Locale.ROOT, "vessel %d rests %.2f blocks inside vessel %d", a.id, depth, b.id));
						}
					}
				}
				check(helper, tray.pose.tiltDegrees() < 10.0, "the pile tipped the tray " + tray.pose.tiltDegrees() + " degrees");
				check(helper, Math.abs(tray.pose.y() - startY) < 1.5, "the tray sank " + (startY - tray.pose.y()) + " blocks under the pile");
			})
			.thenSucceed();
	}

	/**
	 * How far the blocks of one vessel reach into the full blocks of another: the largest inset d (of 0.05, 0.12, 0.2
	 * and 0.35 blocks, and 0.5 for the centre) for which a point d inside a block of {@code a} from each of its corners
	 * lies at least d inside a block of {@code b}.
	 */
	static double overlap(ServerLevel level, VesselRecord a, VesselRecord b) {
		double[] insets = {0.05, 0.12, 0.2, 0.35, 0.5};
		double deepest = 0;
		for (BlockPos plot : BlockPos.betweenClosed(a.plotMin(), a.plotMax())) {
			if (level.getBlockState(plot).isAir()) {
				continue;
			}
			BlockPos local = a.toLocal(plot);
			for (double inset : insets) {
				for (int corner = 0; corner < 8; corner++) {
					Vec3 point = new Vec3(local.getX() + ((corner & 1) == 0 ? inset : 1 - inset), local.getY() + ((corner & 2) == 0 ? inset : 1 - inset),
						local.getZ() + ((corner & 4) == 0 ? inset : 1 - inset));
					Vec3 other = b.pose.worldToLocal(a.pose.localToWorld(point));
					BlockPos cell = BlockPos.containing(other);
					BlockState state = level.getBlockState(b.toPlot(cell));
					if (state.isAir() || !state.isCollisionShapeFullBlock(level, b.toPlot(cell))) {
						continue;
					}
					double inside = Math.min(Math.min(Math.min(other.x - cell.getX(), cell.getX() + 1 - other.x), Math.min(other.y - cell.getY(), cell.getY() + 1 - other.y)),
						Math.min(other.z - cell.getZ(), cell.getZ() + 1 - other.z));
					deepest = Math.max(deepest, Math.min(inset, inside));
				}
			}
		}
		return deepest;
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void aLooseVesselAtRestOnTheGroundSleepsAndWakesWhenTheGroundGoes(GameTestHelper helper) {
		for (int x = 3; x <= 12; x++) {
			for (int z = 3; z <= 12; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		VesselRecord crate = TestShips.assemble(helper, crate(helper, new BlockPos(7, 6, 7), Blocks.OAK_PLANKS, new BlockPos(1, 0, 0)));
		ActiveVessel vessel = TestShips.active(helper, crate);
		double floorTop = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
		Vec3[] asleepAt = new Vec3[1];
		crate.loose = true;
		helper.startSequence()
			.thenWaitUntil(() -> {
				check(helper, vessel.hasBody && Math.abs(crate.pose.y() - 1.0 - floorTop) < 0.06, "the crate's bottom is at " + (crate.pose.y() - 1.0) + ", the floor's top at " + floorTop);
				check(helper, !vessel.bodyAwake && crate.linearVelocity.length() == 0.0, "the crate at rest on the ground is still simulated");
			})
			.thenExecute(() -> asleepAt[0] = crate.pose.position())
			.thenIdle(20)
			// It lies where it fell asleep and sleeps. (The tests beside this one change blocks and vessels within a few
			// blocks, which wakes it for half a second, so that it sleeps without a break is left to LooseCargoTest.)
			.thenWaitUntil(() -> {
				check(helper, crate.pose.position().distanceTo(asleepAt[0]) < 0.01, "the crate moved " + crate.pose.position().distanceTo(asleepAt[0]) + " blocks after it fell asleep");
				check(helper, !vessel.bodyAwake && crate.linearVelocity.length() == 0.0, "the crate at rest on the ground is simulated again");
			})
			// Break the ground under it: it must wake and fall, not hang in the air.
			.thenExecute(() -> {
				for (int x = 6; x <= 9; x++) {
					for (int z = 6; z <= 8; z++) {
						helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
					}
				}
			})
			.thenWaitUntil(() -> check(helper, crate.pose.y() - 1.0 < floorTop - 1.5, "the crate did not fall through the hole: bottom at " + (crate.pose.y() - 1.0)))
			// Turning loose off brings hover and level back: it stops falling and stays.
			.thenExecute(() -> crate.loose = false)
			.thenWaitUntil(() -> check(helper, vessel.bodyAwake && crate.linearVelocity.length() < 0.05, "hover did not catch the vessel after loose was turned off"))
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void aLooseWoodenVesselFloatsOnWater(GameTestHelper helper) {
		// A stone basin with three blocks of water in it. Up to 0.1.3 water was nothing to a vessel and even a wooden
		// crate went to the bottom; now it floats where it displaces its weight (more in BuoyancyGameTests).
		for (int x = 4; x <= 10; x++) {
			for (int z = 4; z <= 10; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
				boolean rim = x == 4 || x == 10 || z == 4 || z == 10;
				for (int y = 2; y <= 4; y++) {
					helper.setBlock(new BlockPos(x, y, z), rim ? Blocks.STONE : Blocks.WATER);
				}
			}
		}
		VesselRecord crate = TestShips.assemble(helper, crate(helper, new BlockPos(7, 9, 7), Blocks.OAK_PLANKS));
		ActiveVessel vessel = TestShips.active(helper, crate);
		double floorTop = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
		crate.loose = true;
		helper.startSequence()
			.thenWaitUntil(() -> {
				check(helper, TestShips.settled(vessel) && crate.linearVelocity.length() < 0.05 && crate.angularVelocity.length() < 0.05, "the crate still moves at "
					+ crate.linearVelocity.length());
				check(helper, Math.abs(vessel.buoyancy.displacedVolume - vessel.mass.mass() / 1000.0) < 0.1, String.format(Locale.ROOT,
					"the crate displaces %.2f m^3 and weighs %.2f t", vessel.buoyancy.displacedVolume, vessel.mass.mass() / 1000.0));
				// Two blocks of wood, plank and helm: however it lies, its lowest point is well clear of the floor.
				check(helper, VesselManager.worldCentre(crate).y > floorTop + 1.2, "the crate's centre is " + (VesselManager.worldCentre(crate).y - floorTop)
					+ " above the basin's floor");
			})
			.thenExecute(() -> check(helper, helper.getBlockState(new BlockPos(7, 4, 7)).is(Blocks.WATER) && helper.getBlockState(new BlockPos(6, 2, 6)).is(Blocks.WATER),
				"the water is gone from the basin"))
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void theModeCommandSwitchesLooseAndInfoShowsIt(GameTestHelper helper) {
		VesselRecord record = TestShips.assemble(helper, PhysicsGameTests.raft(helper, 8, 8, 8));
		List<String> output = new ArrayList<>();
		CommandSource collector = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				output.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		var server = helper.getLevel().getServer();
		var source = server.createCommandSourceStack().withLevel(helper.getLevel()).withSource(collector);
		server.getCommands().performPrefixedCommand(source, "slipway mode " + record.id + " loose true");
		check(helper, record.loose, "/slipway mode <id> loose true did not make the vessel loose: " + output);
		check(helper, record.hover && record.level, "loose changed hover or level");
		server.getCommands().performPrefixedCommand(source, "slipway info " + record.id);
		check(helper, output.getLast().contains("loose=true") && output.getLast().contains("hover=true"), "/slipway info does not show the mode: " + output.getLast());
		server.getCommands().performPrefixedCommand(source, "slipway mode " + record.id + " loose false");
		check(helper, !record.loose, "/slipway mode <id> loose false did not end loose: " + output);
		server.getCommands().performPrefixedCommand(source, "slipway mode " + record.id + " sideways true");
		check(helper, output.getLast().contains("hover, level or loose"), "an unknown mode is not refused with the list of modes: " + output.getLast());
		helper.succeed();
	}
}