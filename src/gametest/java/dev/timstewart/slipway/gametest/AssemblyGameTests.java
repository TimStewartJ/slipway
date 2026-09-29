package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import dev.timstewart.slipway.vessel.VesselRegion;
import dev.timstewart.slipway.vessel.VesselRegistry;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
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
