package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.BoxList;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import dev.timstewart.slipway.vessel.VesselRegion;
import dev.timstewart.slipway.vessel.VesselRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Assembly and disassembly of real blocks, block entities, the deny list and the reserved region. */
public class AssemblyGameTests {
	private static final String ARENA = "slipway:arena";

	@GameTest(structure = ARENA, maxTicks = 40)
	public void assemblyAndDisassemblyPreserveEveryBlockAndBlockEntity(GameTestHelper helper) {
		Map<BlockPos, BlockState> ship = TestShips.mixedShip();
		TestShips.build(helper, ship);
		TestShips.fillBlockEntities(helper);
		ServerLevel level = helper.getLevel();
		BlockPos helmAbs = helper.absolutePos(TestShips.MIXED_HELM);
		Map<BlockPos, CompoundTag> before = TestShips.blockEntityData(level, helmAbs, ship.keySet(), TestShips.MIXED_HELM);
		check(helper, before.size() == 3, "expected chest, furnace and sign block entities, got " + before.keySet());

		VesselRecord record = TestShips.assemble(helper, TestShips.MIXED_HELM);
		check(helper, record.blockCount == ship.size(), "assembled " + record.blockCount + " of " + ship.size() + " blocks");
		for (BlockPos rel : ship.keySet()) {
			check(helper, helper.getBlockState(rel).isAir(), "world block left behind at " + rel);
			BlockState moved = level.getBlockState(record.anchor.offset(rel.subtract(TestShips.MIXED_HELM)));
			check(helper, moved.equals(ship.get(rel)), "plot has " + moved + " instead of " + ship.get(rel) + " for " + rel);
		}
		Map<BlockPos, CompoundTag> inPlot = TestShips.blockEntityData(level, record.anchor, ship.keySet(), TestShips.MIXED_HELM);
		check(helper, inPlot.equals(before), "block entity data changed in the plot: " + inPlot + " vs " + before);
		check(helper, TestShips.itemEntitiesAround(helper) == 0, "assembly dropped items");

		helper.runAfterDelay(5, () -> {
			VesselAssembly.Outcome back = VesselManager.get(level).disassemble(record.id, null);
			check(helper, back.success(), "disassembly failed: " + back.message().getString());
			for (BlockPos rel : ship.keySet()) {
				check(helper, helper.getBlockState(rel).equals(ship.get(rel)), "restored " + helper.getBlockState(rel) + " instead of " + ship.get(rel) + " at " + rel);
				check(helper, level.getBlockState(record.anchor.offset(rel.subtract(TestShips.MIXED_HELM))).isAir(), "plot not cleared at " + rel);
			}
			Map<BlockPos, CompoundTag> after = TestShips.blockEntityData(level, helmAbs, ship.keySet(), TestShips.MIXED_HELM);
			check(helper, after.equals(before), "block entity data changed after the round trip: " + after + " vs " + before);
			check(helper, TestShips.itemEntitiesAround(helper) == 0, "disassembly dropped items");
			check(helper, VesselManager.get(level).registry().get(record.id) == null, "vessel record not removed");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void removeDeletesAVesselAndItsBlocksWithoutDrops(GameTestHelper helper) {
		Map<BlockPos, BlockState> ship = TestShips.mixedShip();
		TestShips.build(helper, ship);
		TestShips.fillBlockEntities(helper);
		ServerLevel level = helper.getLevel();
		VesselRecord record = TestShips.assemble(helper, TestShips.MIXED_HELM);
		helper.runAfterDelay(5, () -> {
			VesselManager manager = VesselManager.get(level);
			int removed = manager.remove(record.id);
			check(helper, removed == ship.size(), "removed " + removed + " blocks, expected " + ship.size());
			check(helper, manager.registry().get(record.id) == null && manager.active(record.id) == null, "the vessel is still known");
			for (BlockPos rel : ship.keySet()) {
				check(helper, level.getBlockState(record.anchor.offset(rel.subtract(TestShips.MIXED_HELM))).isAir(), "plot not cleared at " + rel);
				check(helper, helper.getBlockState(rel).isAir(), "a block appeared in the world at " + rel);
			}
			check(helper, TestShips.itemEntitiesAround(helper) == 0, "removing dropped items");
			check(helper, manager.remove(record.id) == -1, "removing twice did not report an unknown vessel");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 20)
	public void deniedBlocksAndOtherHelmsStayInTheWorld(GameTestHelper helper) {
		for (int x = 0; x < 4; x++) {
			helper.setBlock(new BlockPos(x, 5, 5), Blocks.SPRUCE_PLANKS);
		}
		helper.setBlock(new BlockPos(1, 6, 5), SlipwayRegistry.HELM.defaultBlockState());
		helper.setBlock(new BlockPos(4, 5, 5), Blocks.BEDROCK);
		helper.setBlock(new BlockPos(3, 6, 5), SlipwayRegistry.HELM.defaultBlockState());
		helper.setBlock(new BlockPos(0, 4, 5), Blocks.WATER);
		VesselRecord record = TestShips.assemble(helper, new BlockPos(1, 6, 5));
		check(helper, record.blockCount == 5, "expected the 4 planks and the helm, got " + record.blockCount);
		helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(4, 5, 5));
		helper.assertBlockPresent(SlipwayRegistry.HELM, new BlockPos(3, 6, 5));
		helper.assertBlockPresent(Blocks.WATER, new BlockPos(0, 4, 5));
		helper.assertBlockNotPresent(Blocks.SPRUCE_PLANKS, new BlockPos(0, 5, 5));
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 20)
	public void structuresOverTheCapAreRefusedUntouched(GameTestHelper helper) {
		for (int x = 0; x < 6; x++) {
			for (int z = 0; z < 4; z++) {
				helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
			}
		}
		helper.setBlock(new BlockPos(2, 6, 2), SlipwayRegistry.HELM.defaultBlockState());
		VesselRegistry registry = VesselManager.get(helper.getLevel()).registry();
		int vesselsBefore = registry.size();
		TestShips.withConfig(c -> c.maxVesselBlocks = 10, () -> {
			VesselAssembly.Outcome outcome = VesselManager.get(helper.getLevel()).assemble(helper.absolutePos(new BlockPos(2, 6, 2)), null);
			check(helper, !outcome.success(), "a 25-block structure was assembled with a cap of 10");
		});
		check(helper, registry.size() == vesselsBefore, "a vessel was registered");
		helper.assertBlockPresent(Blocks.STONE, new BlockPos(0, 5, 0));
		helper.assertBlockPresent(SlipwayRegistry.HELM, new BlockPos(2, 6, 2));
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void disassemblySnapsToTheNearestQuarterTurnAndRotatesStates(GameTestHelper helper) {
		// An L of planks with a north-facing stair at the far end, helm at the corner.
		for (int x = 4; x <= 8; x++) {
			helper.setBlock(new BlockPos(x, 5, 4), Blocks.OAK_PLANKS);
		}
		helper.setBlock(new BlockPos(4, 5, 5), Blocks.OAK_PLANKS);
		helper.setBlock(new BlockPos(4, 6, 4), SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		helper.setBlock(new BlockPos(8, 6, 4), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		VesselRecord record = TestShips.assemble(helper, new BlockPos(4, 6, 4));
		ActiveVessel vessel = TestShips.active(helper, record);
		check(helper, vessel != null, "vessel not active");
		// Yaw 84 degrees with a little roll: within tolerance, one quarter turn (east maps to north).
		BlockPos helmAbs = helper.absolutePos(new BlockPos(4, 6, 4));
		VesselManager.get(helper.getLevel()).teleport(vessel, TestShips.poseAboutHelm(helmAbs, 84, 0, 3));
		helper.runAfterDelay(2, () -> {
			VesselAssembly.Outcome outcome = VesselManager.get(helper.getLevel()).disassemble(record.id, null);
			check(helper, outcome.success(), "disassembly failed: " + outcome.message().getString());
			// Local +X (the plank run) now points north: the stair at local (4,0,0) lands at (0,0,-4) from the helm.
			BlockState stair = helper.getBlockState(new BlockPos(4, 6, 0));
			check(helper, stair.is(Blocks.OAK_STAIRS), "stair not at the rotated position, found " + stair);
			check(helper, stair.getValue(StairBlock.FACING) == Direction.WEST, "stair facing " + stair.getValue(StairBlock.FACING) + ", expected west");
			BlockState helm = helper.getBlockState(new BlockPos(4, 6, 4));
			check(helper, helm.is(SlipwayRegistry.HELM) && helm.getValue(HelmBlock.FACING) == Direction.EAST, "helm rotated wrongly: " + helm);
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void disassemblyRefusesWhenTiltedOrBlocked(GameTestHelper helper) {
		for (int x = 2; x <= 5; x++) {
			helper.setBlock(new BlockPos(x, 8, 2), Blocks.BIRCH_PLANKS);
		}
		helper.setBlock(new BlockPos(3, 9, 2), SlipwayRegistry.HELM.defaultBlockState());
		VesselRecord record = TestShips.assemble(helper, new BlockPos(3, 9, 2));
		ActiveVessel vessel = TestShips.active(helper, record);
		VesselManager manager = VesselManager.get(helper.getLevel());
		vessel.record.level = false;
		BlockPos helmAbs = helper.absolutePos(new BlockPos(3, 9, 2));
		manager.teleport(vessel, TestShips.poseAboutHelm(helmAbs, 0, 35, 0));
		helper.runAfterDelay(2, () -> {
			VesselAssembly.Outcome tilted = manager.disassemble(record.id, null);
			check(helper, !tilted.success() && tilted.message().getString().contains("tilted"), "tilted disassembly not refused: " + tilted.message().getString());
			manager.teleport(vessel, VesselPose.at(helmAbs.getX(), helmAbs.getY(), helmAbs.getZ()));
			// A cobweb has no collision, so it blocks the landing spot without touching the hovering hull.
			helper.setBlock(new BlockPos(5, 8, 2), Blocks.COBWEB);
			helper.runAfterDelay(2, () -> {
				VesselAssembly.Outcome blocked = manager.disassemble(record.id, null);
				check(helper, !blocked.success() && blocked.message().getString().contains("in the way"), "blocked disassembly not refused: " + blocked.message().getString());
				check(helper, manager.registry().get(record.id) != null, "the refused vessel was removed");
				helper.setBlock(new BlockPos(5, 8, 2), Blocks.AIR);
				helper.runAfterDelay(2, () -> {
					check(helper, manager.disassemble(record.id, null).success(), "disassembly failed once clear");
					helper.succeed();
				});
			});
		});
	}

	/** In a section the deck is in. */
	private static final BlockPos STRAY_NEAR = new BlockPos(4, 0, 0);
	private static final BlockPos STRAY_CHEST = new BlockPos(0, 6, 0);
	private static final BlockPos STRAY_WATER = new BlockPos(-6, 0, 0);
	/** Two chunk columns from the deck's own, the last its tickets keep loaded. */
	private static final BlockPos STRAY_FAR = new BlockPos(-40, 0, 0);

	/**
	 * Sets four blocks in the plot of a three by three deck that the vessel does not take in, with the largest span at
	 * five blocks: a stone, a chest with diamonds in it, water, and a stone two columns away that a command sets. (The
	 * setting is the server's, so it is lowered for these calls only; a block is taken in or left out when it is set.)
	 */
	private static void setBlocksPastTheLargestSpan(GameTestHelper helper, VesselRecord record) {
		ServerLevel level = helper.getLevel();
		BlockPos min = record.localMin;
		BlockPos max = record.localMax;
		check(helper, TestShips.blocksInPlot(level, record) == 10, "the deck's plot holds " + TestShips.blocksInPlot(level, record) + " blocks before any is added");
		TestShips.withConfig(config -> config.maxVesselSpan = 5, () -> {
			level.setBlock(record.toPlot(STRAY_NEAR), Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(record.toPlot(STRAY_CHEST), Blocks.CHEST.defaultBlockState(), 3);
			((ChestBlockEntity)level.getBlockEntity(record.toPlot(STRAY_CHEST))).setItem(0, new ItemStack(Items.DIAMOND, 3));
			// placed like a copy, so that it stays where it is and does not start to flow
			level.setBlock(record.toPlot(STRAY_WATER), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
			BlockPos far = record.toPlot(STRAY_FAR);
			TestShips.command(helper, "setblock " + far.getX() + " " + far.getY() + " " + far.getZ() + " minecraft:stone");
		});
		check(helper, level.getBlockState(record.toPlot(STRAY_FAR)).is(Blocks.STONE), "/setblock did not set a block in the plot");
		check(helper, record.localMin.equals(min) && record.localMax.equals(max), "the vessel took in a block past the largest span: " + record.localMin + ".." + record.localMax);
		check(helper, TestShips.blocksInPlot(level, record) == 14, "the plot holds " + TestShips.blocksInPlot(level, record) + " blocks with the four that were set");
	}

	private static void checkThePlotIsEmpty(GameTestHelper helper, VesselRecord record, String when) {
		ServerLevel level = helper.getLevel();
		int left = TestShips.blocksInPlot(level, record);
		check(helper, left == 0, when + " the vessel's plot still holds " + left + " blocks ("
			+ level.getBlockState(record.toPlot(STRAY_NEAR)) + ", " + level.getBlockState(record.toPlot(STRAY_CHEST)) + ", " + level.getBlockState(record.toPlot(STRAY_WATER))
			+ ", " + level.getBlockState(record.toPlot(STRAY_FAR)) + ")");
		check(helper, level.getBlockEntity(record.toPlot(STRAY_CHEST)) == null, when + " the chest's block entity is still in the plot");
		check(helper, TestShips.itemEntitiesAround(helper) == 0 && TestShips.itemEntitiesInPlot(level, record) == 0, when + " items were dropped");
	}

	/**
	 * Blocks a vessel did not take in do not outlive it: after disassembly its plot is empty, and the same ship
	 * assembled again (it gets the plot freed last, which is usually that one) is made of its own blocks only.
	 */
	@GameTest(structure = ARENA, maxTicks = 100)
	public void disassemblyLeavesNothingInThePlot(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		BoxList.MassProperties[] mass = new BoxList.MassProperties[1];
		VesselRecord[] again = new VesselRecord[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				mass[0] = vessel.mass;
				setBlocksPastTheLargestSpan(helper, record);
			})
			.thenIdle(3)
			.thenExecute(() -> {
				VesselAssembly.Outcome back = manager.disassemble(record.id, null);
				check(helper, back.success(), "disassembly failed: " + back.message().getString());
				checkThePlotIsEmpty(helper, record, "after disassembly");
				// The deck is back in the world, and none of the four came with it.
				check(helper, helper.getBlockState(helm).is(SlipwayRegistry.HELM) && helper.getBlockState(helm.offset(1, -1, 1)).is(Blocks.OAK_PLANKS), "the deck is not back in the world");
				for (BlockPos stray : new BlockPos[] {STRAY_NEAR, STRAY_CHEST, STRAY_WATER}) {
					check(helper, helper.getBlockState(helm.offset(stray)).isAir(), "a block the vessel had not taken in was put into the world: " + helper.getBlockState(helm.offset(stray)));
				}
			})
			// The plot is freed a tick later.
			.thenIdle(3)
			.thenExecute(() -> again[0] = TestShips.assemble(helper, helm))
			.thenWaitUntil(() -> check(helper, TestShips.settled(TestShips.active(helper, again[0])), "the ship assembled again has no body yet"))
			.thenExecute(() -> {
				String plots = " (plot " + again[0].plot + ", the first one had " + record.plot + ")";
				check(helper, again[0].blockCount == 10, "the ship assembled again counts " + again[0].blockCount + " blocks" + plots);
				check(helper, TestShips.sameMass(TestShips.active(helper, again[0]).mass, mass[0]), "the ship assembled again has another shape: it weighs "
					+ TestShips.active(helper, again[0]).mass.mass() + ", not " + mass[0].mass() + plots);
				check(helper, TestShips.blocksInPlot(level, again[0]) == 10, "its plot holds " + TestShips.blocksInPlot(level, again[0]) + " blocks" + plots);
				check(helper, manager.disassemble(again[0].id, null).success(), "the second disassembly failed");
				check(helper, TestShips.blocksInPlot(level, again[0]) == 0, "after the second disassembly the plot holds " + TestShips.blocksInPlot(level, again[0]) + " blocks");
				for (BlockPos stray : new BlockPos[] {STRAY_NEAR, STRAY_CHEST, STRAY_WATER}) {
					check(helper, helper.getBlockState(helm.offset(stray)).isAir(), "a block of the first vessel's plot came into the world with the second: " + helper.getBlockState(helm.offset(stray)));
				}
			})
			.thenSucceed();
	}

	/** The same for a vessel that <code>/slipway remove</code> deletes. */
	@GameTest(structure = ARENA, maxTicks = 60)
	public void removingAVesselLeavesNothingInThePlot(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> setBlocksPastTheLargestSpan(helper, record))
			.thenIdle(3)
			.thenExecute(() -> {
				List<String> reply = TestShips.command(helper, "slipway remove " + record.id);
				check(helper, manager.registry().get(record.id) == null && reply.size() == 1 && reply.getFirst().contains("its 10 blocks"),
					"/slipway remove did not remove the vessel and its 10 blocks: " + reply);
				checkThePlotIsEmpty(helper, record, "after removing");
				check(helper, helper.getBlockState(helm).isAir() && helper.getBlockState(helm.offset(STRAY_NEAR)).isAir(), "removing the vessel put blocks into the world");
			})
			.thenSucceed();
	}

	/**
	 * A plot that is handed out with blocks in it (a world in which a version before this one left them there) is
	 * emptied before the vessel's blocks go in: the vessel is made of its own blocks only, and only they come back.
	 * The registry hands out the plot that was freed last, so the test takes one, puts blocks into it, gives it back
	 * and assembles in the same tick.
	 */
	@GameTest(structure = ARENA, maxTicks = 100)
	public void aPlotHandedOutWithBlocksInItIsEmptiedFirst(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		// For comparison: the same ship in a plot that was never used.
		VesselRecord first = TestShips.assemble(helper, helm);
		ActiveVessel firstVessel = TestShips.active(helper, first);
		// Where the deck has air inside its bounds, in a section the deck is in, and in the column next to its own.
		BlockPos inside = new BlockPos(1, 0, 1);
		BlockPos chest = new BlockPos(-1, 0, -1);
		BlockPos near = new BlockPos(5, 0, 0);
		BlockPos nextColumn = new BlockPos(-20, 0, 0);
		BoxList.MassProperties[] mass = new BoxList.MassProperties[1];
		VesselRecord[] second = new VesselRecord[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.settled(firstVessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				mass[0] = firstVessel.mass;
				check(helper, first.blockCount == 10, "the deck counts " + first.blockCount + " blocks");
				check(helper, manager.disassemble(first.id, null).success(), "the first disassembly failed");
			})
			.thenIdle(3)
			.thenExecute(() -> {
				VesselRegistry registry = manager.registry();
				int plot = registry.allocatePlot();
				BlockPos anchor = VesselRegion.anchor(plot, helper.absolutePos(helm).getY());
				for (BlockPos stale : new BlockPos[] {inside, near, nextColumn}) {
					level.setBlock(anchor.offset(stale), Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
				}
				level.setBlock(anchor.offset(chest), Blocks.CHEST.defaultBlockState(), 2);
				((ChestBlockEntity)level.getBlockEntity(anchor.offset(chest))).setItem(0, new ItemStack(Items.DIAMOND, 3));
				registry.freePlot(plot);
				second[0] = TestShips.assemble(helper, helm);
				check(helper, second[0].plot == plot, "the ship got plot " + second[0].plot + ", not the one freed last (" + plot + ")");
				check(helper, TestShips.blocksInPlot(level, second[0]) == 10, "the plot holds " + TestShips.blocksInPlot(level, second[0]) + " blocks after assembly, the ship has 10");
				check(helper, level.getBlockEntity(anchor.offset(chest)) == null, "the chest's block entity is still in the plot");
				check(helper, TestShips.itemEntitiesAround(helper) == 0 && TestShips.itemEntitiesInPlot(level, second[0]) == 0, "emptying the plot dropped items");
			})
			.thenWaitUntil(() -> check(helper, TestShips.settled(TestShips.active(helper, second[0])), "the second vessel has no body yet"))
			.thenExecute(() -> {
				ActiveVessel vessel = TestShips.active(helper, second[0]);
				check(helper, second[0].blockCount == 10, "the vessel counts " + second[0].blockCount + " blocks, the ship has 10");
				check(helper, TestShips.sameMass(vessel.mass, mass[0]), "the vessel has another shape than the ship: it weighs " + vessel.mass.mass() + ", the ship " + mass[0].mass());
				check(helper, manager.disassemble(second[0].id, null).success(), "the second disassembly failed");
				check(helper, helper.getBlockState(helm.offset(inside)).isAir() && helper.getBlockState(helm.offset(chest)).isAir(),
					"blocks that were in the plot came into the world: " + helper.getBlockState(helm.offset(inside)) + ", " + helper.getBlockState(helm.offset(chest)));
				check(helper, helper.getBlockState(helm).is(SlipwayRegistry.HELM) && helper.getBlockState(helm.offset(1, -1, 1)).is(Blocks.OAK_PLANKS), "the deck is not back in the world");
				check(helper, TestShips.blocksInPlot(level, second[0]) == 0 && TestShips.itemEntitiesAround(helper) == 0, "the plot is not empty after the disassembly, or items were dropped");
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 20)
	public void reservedChunksGenerateEmptyVoid(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int plot = VesselRegion.MAX_PLOTS - 7;
		BlockPos anchor = VesselRegion.anchor(plot, 64);
		LevelChunk chunk = level.getChunk(anchor.getX() >> 4, anchor.getZ() >> 4);
		for (LevelChunkSection section : chunk.getSections()) {
			check(helper, section.hasOnlyAir(), "a reserved chunk generated blocks");
		}
		var biome = level.getBiome(anchor);
		check(helper, biome.is(Biomes.THE_VOID), "reserved biome is " + biome.getRegisteredName());
		check(helper, level.registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.THE_VOID).isPresent(), "the void biome exists");
		helper.succeed();
	}

	/**
	 * The plot columns around a vessel hold no block, and the sides of a vessel that lie on a chunk border are lit by
	 * them. When such a column's light comes on after the vessel's own column has made room for light data beside
	 * its blocks (the order in which chunks come from a save is not fixed), the game fills that room with sky light.
	 * For a column without any block vanilla's own code did not (SkyLightEngineMixin): the sides stayed black.
	 */
	@GameTest(structure = ARENA, maxTicks = 100)
	public void anEmptyPlotColumnGetsItsSkyLightWhenItsLightComesOnLast(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// A plot that no test vessel gets.
		BlockPos edge = VesselRegion.anchor(VesselRegion.MAX_PLOTS - 11, 64);
		BlockPos beside = edge.west();
		ChunkPos own = new ChunkPos(edge.getX() >> 4, edge.getZ() >> 4);
		ChunkPos west = new ChunkPos(beside.getX() >> 4, beside.getZ() >> 4);
		check(helper, west.x() == own.x() - 1, "the plot's anchor is not on the west edge of its chunk column");
		// Both columns stay loaded for the test (the runner releases forced chunks when the batch ends): light data
		// that is dropped with an unloaded chunk reads as full sky light too.
		level.setChunkForced(own.x(), own.z(), true);
		level.setChunkForced(west.x(), west.z(), true);
		level.getChunk(own.x(), own.z());
		level.getChunk(west.x(), west.z());
		ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
		SectionPos section = SectionPos.of(edge);
		// A block on the edge of its column, with both columns lit as they are when a vessel is assembled.
		level.setBlock(edge, Blocks.STONE.defaultBlockState(), 2);
		CompletableFuture<?>[] done = {light.waitForPendingTasks(own.x(), own.z())};
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, done[0].isDone(), "the light engine is still at work"))
			.thenExecute(() -> {
				check(helper, level.getBrightness(LightLayer.SKY, beside) == 15, "beside the block the sky light is " + level.getBrightness(LightLayer.SKY, beside) + " before anything is unloaded");
				// Both columns as they are once they have been unloaded: the column to the west without light, and no
				// room for light data round the block's section.
				light.setLightEnabled(west, false);
				light.updateSectionStatus(section, true);
				done[0] = light.waitForPendingTasks(own.x(), own.z());
			})
			.thenWaitUntil(() -> check(helper, done[0].isDone(), "the light engine is still at work"))
			.thenExecute(() -> {
				// The block's column comes back first, as a chunk from a save does: its section is announced, nothing else.
				light.updateSectionStatus(section, false);
				done[0] = light.waitForPendingTasks(own.x(), own.z());
			})
			.thenWaitUntil(() -> check(helper, done[0].isDone(), "the light engine is still at work"))
			.thenExecute(() -> {
				int sky = level.getBrightness(LightLayer.SKY, beside);
				check(helper, sky == 0 && light.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(beside)) != null,
					"the test did not get the place beside the block into the dark: sky light " + sky);
				// Then the column to the west.
				light.setLightEnabled(west, true);
				done[0] = light.waitForPendingTasks(west.x(), west.z());
			})
			.thenWaitUntil(() -> check(helper, done[0].isDone(), "the light engine is still at work"))
			.thenIdle(2)
			.thenExecute(() -> {
				int sky = level.getBrightness(LightLayer.SKY, beside);
				boolean stored = light.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(beside)) != null;
				level.setBlock(edge, Blocks.AIR.defaultBlockState(), 2);
				check(helper, stored, "the light data beside the block is gone: the columns did not stay loaded");
				check(helper, sky == 15, "beside the block, in the column without blocks, the sky light is " + sky + " after that column's light came on");
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 20)
	public void theRegistryRoundTripsThroughItsCodec(GameTestHelper helper) {
		for (int x = 0; x < 3; x++) {
			helper.setBlock(new BlockPos(x, 5, 8), Blocks.ACACIA_PLANKS);
		}
		helper.setBlock(new BlockPos(1, 6, 8), SlipwayRegistry.HELM.defaultBlockState());
		VesselRecord record = TestShips.assemble(helper, new BlockPos(1, 6, 8));
		VesselRegistry registry = VesselManager.get(helper.getLevel()).registry();
		Tag tag = VesselRegistry.CODEC.encodeStart(helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), registry).getOrThrow();
		VesselRegistry back = VesselRegistry.CODEC.parse(helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
		VesselRecord loaded = back.get(record.id);
		check(helper, loaded != null, "record missing after the round trip");
		check(helper, loaded.anchor.equals(record.anchor) && loaded.plot == record.plot && loaded.blockCount == record.blockCount, "record differs: " + loaded);
		check(helper, VesselManager.get(helper.getLevel()).disassemble(record.id, null).success(), "cleanup failed");
		helper.succeed();
	}
}
