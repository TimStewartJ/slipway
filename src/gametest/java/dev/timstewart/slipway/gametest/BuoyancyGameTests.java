package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.physics.FluidField;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.Shelter;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Vessels in water in a real level: the pool's water reaches the physics thread, a raft floats at its draught, stone
 * sinks, an open hull carries cargo until it is too heavy, hover leaves a vessel alone, a sleeping raft wakes when
 * its pool is drained, what is inside a floating hull is dry, and a hull docked by disassembly stays dry.
 */
public class BuoyancyGameTests {
	private static final String ARENA = "slipway:arena";
	/** The pool: water in x, z = 3..11 and y = 2..5 of the arena, in a stone basin. */
	private static final int POOL_MIN = 3, POOL_MAX = 11, POOL_BOTTOM = 2, POOL_TOP = 5;

	/** Builds the pool and returns the height of its surface (world y). */
	static double pool(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = POOL_MIN - 1; x <= POOL_MAX + 1; x++) {
			for (int z = POOL_MIN - 1; z <= POOL_MAX + 1; z++) {
				boolean wall = x < POOL_MIN || x > POOL_MAX || z < POOL_MIN || z > POOL_MAX;
				for (int y = POOL_BOTTOM - 1; y <= POOL_TOP + 1; y++) {
					if (wall || y == POOL_BOTTOM - 1) {
						level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState(), 2 | 16);
					}
				}
			}
		}
		for (int x = POOL_MIN; x <= POOL_MAX; x++) {
			for (int z = POOL_MIN; z <= POOL_MAX; z++) {
				for (int y = POOL_BOTTOM; y <= POOL_TOP; y++) {
					level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.WATER.defaultBlockState(), 2 | 16);
				}
			}
		}
		return helper.absolutePos(new BlockPos(0, POOL_TOP, 0)).getY() + 8.0 / 9.0;
	}

	/** World y of the pool's floor. */
	private static double poolFloor(GameTestHelper helper) {
		return helper.absolutePos(new BlockPos(0, POOL_BOTTOM, 0)).getY();
	}

	/**
	 * An open hull of planks, 5 x 5 outside with a floor at {@code y} and two rows of wall, and a helm on the floor in
	 * its middle; returns the helm's position. 57 planks: 39.9 t, around 18 m^3 of air.
	 */
	static BlockPos hull(GameTestHelper helper, int cx, int y, int cz) {
		ServerLevel level = helper.getLevel();
		for (int x = cx - 2; x <= cx + 2; x++) {
			for (int z = cz - 2; z <= cz + 2; z++) {
				level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.OAK_PLANKS.defaultBlockState(), 2 | 16);
				if (Math.abs(x - cx) == 2 || Math.abs(z - cz) == 2) {
					level.setBlock(helper.absolutePos(new BlockPos(x, y + 1, z)), Blocks.OAK_PLANKS.defaultBlockState(), 2 | 16);
					level.setBlock(helper.absolutePos(new BlockPos(x, y + 2, z)), Blocks.OAK_PLANKS.defaultBlockState(), 2 | 16);
				}
			}
		}
		BlockPos helm = new BlockPos(cx, y + 1, cz);
		level.setBlock(helper.absolutePos(helm), SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH), 2 | 16);
		return helm;
	}

	/** The hull floats: its bottom (one block below the helm, the vessel's origin) lies where it displaces its weight. */
	private static void assertFloats(GameTestHelper helper, ActiveVessel vessel, double surface, double bottomArea) {
		check(helper, TestShips.settled(vessel), "no physics body yet");
		VesselRecord record = vessel.record;
		double draught = vessel.mass.mass() / 1000.0 / bottomArea;
		double bottom = record.pose.y() - 1.0;
		check(helper, Math.abs(bottom - (surface - draught)) < 0.06, String.format(Locale.ROOT, "the bottom lies %.3f below the surface, its weight asks for %.3f",
			surface - bottom, draught));
		check(helper, record.linearVelocity.length() < 0.03 && record.angularVelocity.length() < 0.03, "it still moves at " + record.linearVelocity.length());
		check(helper, record.pose.tiltDegrees() < 2.0, "it lists " + record.pose.tiltDegrees() + " degrees");
		check(helper, Math.abs(vessel.buoyancy.displacedVolume - vessel.mass.mass() / 1000.0) < 0.02 * vessel.mass.mass() / 1000.0 + 0.1,
			String.format(Locale.ROOT, "it displaces %.2f m^3 and weighs %.2f t", vessel.buoyancy.displacedVolume, vessel.mass.mass() / 1000.0));
		check(helper, vessel.buoyancy.fluid == FluidField.WATER && vessel.buoyancy.applied, "the water does not act on it");
		check(helper, !vessel.flooding(), "it is taking in water: " + vessel.buoyancy.floodedVolume);
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void aRaftFloatsAtTheDraughtThatDisplacesItsWeight(GameTestHelper helper) {
		double surface = pool(helper);
		VesselRecord record = TestShips.assemble(helper, PhysicsGameTests.raft(helper, 7, 7, 7));
		ActiveVessel vessel = TestShips.active(helper, record);
		record.hover = false;
		helper.succeedWhen(() -> {
			assertFloats(helper, vessel, surface, 9.0);
			check(helper, vessel.hull.shelteredVolume() == 0 && vessel.hull.blockVolume() >= 9.0, "a raft shelters no air: " + vessel.hull.shelteredVolume());
			String info = String.join("\n", TestShips.command(helper, "slipway info " + record.id));
			check(helper, info.contains("fluid=water") && info.contains("floats=true"), "the info command does not tell that it floats: " + info);
		});
	}

	@GameTest(structure = ARENA, maxTicks = 500)
	public void stoneSinksAndASleepingRaftWakesWhenThePoolIsDrained(GameTestHelper helper) {
		double surface = pool(helper);
		double floor = poolFloor(helper);
		VesselRecord stone = TestShips.assemble(helper, LooseGameTests.crate(helper, new BlockPos(5, 7, 5), Blocks.STONE, new BlockPos(-1, 0, 0),
			new BlockPos(0, 0, -1), new BlockPos(-1, 0, -1)));
		VesselRecord raft = TestShips.assemble(helper, PhysicsGameTests.raft(helper, 9, 7, 9));
		stone.loose = true;
		raft.loose = true;
		ActiveVessel sunk = TestShips.active(helper, stone);
		ActiveVessel floating = TestShips.active(helper, raft);
		helper.startSequence()
			.thenWaitUntil(() -> {
				check(helper, TestShips.settled(sunk) && TestShips.settled(floating), "no physics bodies yet");
				// The crate's four blocks of stone are one block below its helm.
				check(helper, Math.abs(stone.pose.y() - 1.0 - floor) < 0.08, String.format(Locale.ROOT, "the stone lies %.3f above the pool's floor", stone.pose.y() - 1.0 - floor));
				check(helper, sunk.buoyancy.displacedVolume > 3.9, "the stone on the bottom displaces " + sunk.buoyancy.displacedVolume);
				check(helper, raft.pose.y() - 1.0 > surface - 0.95 && raft.pose.y() - 1.0 < surface - 0.6, "the raft's bottom is at " + (raft.pose.y() - 1.0 - surface)
					+ " from the surface");
				check(helper, !floating.bodyAwake, "the raft on the water has not fallen asleep");
				check(helper, Math.abs(floating.buoyancy.displacedVolume - floating.mass.mass() / 1000.0) < 0.15, "asleep, the raft displaces " + floating.buoyancy.displacedVolume);
			})
			.thenExecute(() -> {
				for (int x = POOL_MIN; x <= POOL_MAX; x++) {
					for (int z = POOL_MIN; z <= POOL_MAX; z++) {
						for (int y = POOL_BOTTOM; y <= POOL_TOP; y++) {
							helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 2 | 16);
						}
					}
				}
			})
			.thenWaitUntil(() -> {
				check(helper, Math.abs(raft.pose.y() - 1.0 - floor) < 0.08, String.format(Locale.ROOT, "the raft lies %.3f above the floor of the drained pool",
					raft.pose.y() - 1.0 - floor));
				check(helper, raft.linearVelocity.length() < 0.05, "the raft still moves");
				check(helper, floating.buoyancy.displacedVolume == 0 && sunk.buoyancy.displacedVolume == 0, "something still displaces water in a dry pool");
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 700)
	public void anOpenHullCarriesCargoUntilItIsTooHeavyAndThenGoesDown(GameTestHelper helper) {
		double surface = pool(helper);
		double floor = poolFloor(helper);
		BlockPos helm = hull(helper, 7, 6, 7);
		helper.setBlock(helm.west(), Blocks.IRON_BLOCK);
		helper.setBlock(helm.east(), Blocks.IRON_BLOCK);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		record.hover = false;
		helper.startSequence()
			.thenWaitUntil(() -> {
				// 57 planks and two blocks of iron, 55.5 t and the helm: far more than its 60 m^3 of blocks displace,
				// carried by the 16 m^3 of air below its rim.
				assertFloats(helper, vessel, surface, 25.0);
				check(helper, vessel.mass.mass() > 55000 && vessel.hull.blockVolume() < 61.0, "mass " + vessel.mass.mass() + ", blocks " + vessel.hull.blockVolume());
				check(helper, Math.abs(vessel.hull.shelteredVolume() - 15.0) < 0.01 && Math.abs(vessel.hull.capacity() - 75.0) < 0.01,
					"sheltered " + vessel.hull.shelteredVolume() + ", capacity " + vessel.hull.capacity());
				check(helper, vessel.buoyancyReserve() > 1.2, "reserve " + vessel.buoyancyReserve());
			})
			// Six more blocks of iron on board (local positions: the helm is the origin, the floor is at y = -1).
			.thenExecute(() -> {
				for (int[] at : new int[][] {{0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
					helper.getLevel().setBlock(record.anchor.offset(at[0], 0, at[1]), Blocks.IRON_BLOCK.defaultBlockState(), 3);
				}
			})
			.thenWaitUntil(() -> {
				check(helper, vessel.mass.mass() > 100000, "the cargo has not been weighed yet: " + vessel.mass.mass());
				check(helper, vessel.buoyancyReserve() < 1.0, "102 t in a hull that displaces 75 m^3 at most still counts as floating");
				check(helper, Math.abs(record.pose.y() - 1.0 - floor) < 0.1, String.format(Locale.ROOT, "the hull's bottom is %.3f above the pool's floor",
					record.pose.y() - 1.0 - floor));
				check(helper, record.linearVelocity.length() < 0.05, "the hull still moves");
				check(helper, vessel.flooding(), "the water stands above the rim and has not run in: flooded " + vessel.buoyancy.floodedVolume);
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void aHoveringVesselIsLeftAloneByTheWaterAndFloatsOnceHoverIsOff(GameTestHelper helper) {
		double surface = pool(helper);
		// Built in the water, deep: its rim a little above the surface.
		VesselRecord record = TestShips.assemble(helper, hull(helper, 7, 3, 7));
		ActiveVessel vessel = TestShips.active(helper, record);
		Vec3 start = record.pose.position();
		helper.startSequence()
			.thenIdle(60)
			.thenExecute(() -> {
				check(helper, TestShips.settled(vessel), "no physics body");
				check(helper, record.hover, "a new vessel does not hover");
				check(helper, record.pose.position().distanceTo(start) < 0.05, "the water moved a hovering vessel by " + record.pose.position().distanceTo(start));
				check(helper, vessel.buoyancy.displacedVolume > 25.0 && !vessel.buoyancy.applied, "displaced " + vessel.buoyancy.displacedVolume + ", applied "
					+ vessel.buoyancy.applied);
				record.hover = false;
			})
			.thenWaitUntil(() -> assertFloats(helper, vessel, surface, 25.0))
			.thenExecute(() -> check(helper, record.pose.y() > start.y + 1.0, "the hull came up by " + (record.pose.y() - start.y)))
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 700)
	public void whatStandsInAFloatingHullIsDryUntilTheHullFloods(GameTestHelper helper) {
		double surface = pool(helper);
		BlockPos helm = hull(helper, 7, 6, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		record.hover = false;
		ServerLevel level = helper.getLevel();
		ArmorStand inside = InteractionGameTests.pig(helper, Vec3.atBottomCenterOf(helper.absolutePos(helm.north())).add(0, 0.3, 0));
		ArmorStand outside = InteractionGameTests.pig(helper, Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 7, 3))));
		helper.startSequence()
			.thenWaitUntil(() -> {
				assertFloats(helper, vessel, surface, 25.0);
				// The hull's floor is 0.6 below the surface: who stands on it stands in a water block of the world.
				check(helper, Math.abs(inside.getY() - record.pose.y()) < 0.05, "the stand in the hull is " + (inside.getY() - record.pose.y()) + " above its floor");
				check(helper, inside.getY() < surface - 0.4, "the hull's floor is not below the waterline: " + (surface - inside.getY()));
				check(helper, !level.getFluidState(BlockPos.containing(inside.getX(), inside.getY() + 0.1, inside.getZ())).isEmpty(), "there is no water block where the stand is");
				check(helper, Shelter.isDry(inside), "the hull does not shelter the stand");
				check(helper, !inside.isInWater() && !inside.isUnderWater(), "the stand in the hull counts as in the water");
				check(helper, outside.isInWater() && !Shelter.isDry(outside), "the stand in the pool does not count as in the water");
			})
			// Too much iron: the hull goes down and the water runs over its rim.
			.thenExecute(() -> {
				for (int[] at : new int[][] {{-1, 0}, {1, 0}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
					level.setBlock(record.anchor.offset(at[0], 0, at[1]), Blocks.IRON_BLOCK.defaultBlockState(), 3);
				}
			})
			.thenWaitUntil(() -> {
				check(helper, vessel.flooding(), "the hull has not flooded");
				check(helper, !Shelter.isDry(inside), "the flooded hull still shelters the stand");
				check(helper, inside.isInWater(), "the stand in the flooded hull does not count as in the water");
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void aHullDockedByDisassemblyStaysDry(GameTestHelper helper) {
		double surface = pool(helper);
		BlockPos helm = hull(helper, 7, 6, 7);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		record.hover = false;
		ServerLevel level = helper.getLevel();
		helper.startSequence()
			.thenWaitUntil(() -> assertFloats(helper, vessel, surface, 25.0))
			.thenExecute(() -> {
				var outcome = VesselManager.get(level).disassemble(record.id, null);
				check(helper, outcome.success(), "disassembly failed: " + outcome.message().getString());
				// The helm stands on the floor, 0.6 below the surface: its block is the highest one of the pool's water.
				BlockPos landed = helper.absolutePos(new BlockPos(7, POOL_TOP, 7));
				check(helper, level.getBlockState(landed).is(SlipwayRegistry.HELM), "the helm is not at the waterline: " + level.getBlockState(landed));
				int dry = 0;
				for (int x = -1; x <= 1; x++) {
					for (int z = -1; z <= 1; z++) {
						for (int y = 0; y <= 1; y++) {
							BlockState state = level.getBlockState(landed.offset(x, y, z));
							check(helper, !(state.getBlock() instanceof LiquidBlock), "water stands in the docked hull at " + x + "," + y + "," + z);
							dry += state.isAir() ? 1 : 0;
						}
					}
				}
				check(helper, dry == 17, "air cells in the docked hull: " + dry);
				check(helper, level.getBlockState(landed.offset(-2, 0, 0)).is(Blocks.OAK_PLANKS), "the hull's wall is not where it should be");
				check(helper, level.getBlockState(landed.offset(-3, 0, 0)).getBlock() instanceof LiquidBlock, "the pool beside the hull lost its water");
				check(helper, level.getBlockState(landed.offset(0, -2, 0)).getBlock() instanceof LiquidBlock, "the pool under the hull lost its water");
			})
			.thenSucceed();
	}
}
