package dev.timstewart.slipway.gametest;

import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.BoxList;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/** Builds small test ships inside a game-test arena and reads them back. */
final class TestShips {
	private TestShips() {
	}

	/** Relative positions and states of a mixed ship: a 5x3 plank deck with a helm and one of everything on it. */
	static Map<BlockPos, BlockState> mixedShip() {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 3; z++) {
				blocks.put(new BlockPos(x, 5, z), Blocks.OAK_PLANKS.defaultBlockState());
			}
		}
		blocks.put(new BlockPos(2, 6, 1), SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		blocks.put(new BlockPos(0, 6, 0), Blocks.CHEST.defaultBlockState());
		blocks.put(new BlockPos(4, 6, 0), Blocks.FURNACE.defaultBlockState());
		blocks.put(new BlockPos(1, 6, 2), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.FACING, Direction.SOUTH));
		blocks.put(new BlockPos(1, 7, 2), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.SOUTH));
		blocks.put(new BlockPos(3, 6, 2), Blocks.OAK_SIGN.defaultBlockState());
		blocks.put(new BlockPos(4, 6, 2), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
		blocks.put(new BlockPos(4, 7, 2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.POWERED, true));
		blocks.put(new BlockPos(0, 6, 2), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
		return blocks;
	}

	static final BlockPos MIXED_HELM = new BlockPos(2, 6, 1);

	static void build(GameTestHelper helper, Map<BlockPos, BlockState> blocks) {
		for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
			helper.getLevel().setBlock(helper.absolutePos(e.getKey()), e.getValue(), 2 | 16);
		}
	}

	/** Puts recognisable data into the mixed ship's block entities. */
	static void fillBlockEntities(GameTestHelper helper) {
		ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(0, 6, 0), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
		chest.setItem(13, new ItemStack(Items.OAK_LOG, 64));
		AbstractFurnaceBlockEntity furnace = helper.getBlockEntity(new BlockPos(4, 6, 0), AbstractFurnaceBlockEntity.class);
		furnace.setItem(0, new ItemStack(Items.RAW_IRON, 7));
		// No fuel: the furnace keeps ticking on the vessel and would otherwise start to smelt.
		furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
		SignBlockEntity sign = helper.getBlockEntity(new BlockPos(3, 6, 2), SignBlockEntity.class);
		java.util.List<Component> lines = java.util.List.of(Component.literal("Slipway"), Component.literal("test ship"), Component.empty(), Component.empty());
		sign.setText(new net.minecraft.world.level.block.entity.SignText(lines, lines, net.minecraft.world.item.DyeColor.BLUE, true), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
	}

	/** Full saved data of every block entity, keyed by relative position, without position fields. */
	static Map<BlockPos, CompoundTag> blockEntityData(ServerLevel level, BlockPos originAbs, Iterable<BlockPos> relatives, BlockPos relativeBase) {
		Map<BlockPos, CompoundTag> data = new LinkedHashMap<>();
		for (BlockPos rel : relatives) {
			BlockEntity be = level.getBlockEntity(originAbs.offset(rel.subtract(relativeBase)));
			if (be != null) {
				data.put(rel, be.saveWithoutMetadata(level.registryAccess()));
			}
		}
		return data;
	}

	static long itemEntitiesAround(GameTestHelper helper) {
		AABB box = AABB.encapsulatingFullBlocks(helper.absolutePos(new BlockPos(-4, -4, -4)), helper.absolutePos(new BlockPos(20, 20, 20)));
		return helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).size();
	}

	static VesselRecord assemble(GameTestHelper helper, BlockPos relativeHelm) {
		VesselManager manager = VesselManager.get(helper.getLevel());
		VesselAssembly.Outcome outcome = manager.assemble(helper.absolutePos(relativeHelm), null);
		if (!outcome.success() || outcome.record() == null) {
			throw fail(helper, "assembly failed: " + outcome.message().getString());
		}
		return outcome.record();
	}

	static RuntimeException fail(GameTestHelper helper, String message) {
		return helper.assertionException(Component.literal(message));
	}

	static void check(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw fail(helper, message);
		}
	}

	static ActiveVessel active(GameTestHelper helper, VesselRecord record) {
		return VesselManager.get(helper.getLevel()).active(record.id);
	}

	/** The vessel has a body built from its blocks as they are now. */
	static boolean settled(ActiveVessel vessel) {
		return vessel != null && vessel.hasBody && vessel.mass != null && !vessel.shapeDirty;
	}

	/** The same boxes, as far as their mass, centre and inertia tell: one more solid block changes all three. */
	static boolean sameMass(BoxList.MassProperties a, BoxList.MassProperties b) {
		boolean same = Math.abs(a.mass() - b.mass()) < 1.0e-6 && Math.abs(a.comX() - b.comX()) < 1.0e-6 && Math.abs(a.comY() - b.comY()) < 1.0e-6
			&& Math.abs(a.comZ() - b.comZ()) < 1.0e-6;
		for (int i = 0; same && i < a.inertia().length; i++) {
			same = Math.abs(a.inertia()[i] - b.inertia()[i]) < 1.0e-6 * Math.max(1.0, Math.abs(a.inertia()[i]));
		}
		return same;
	}

	/** Runs a command as the server in the test's level and returns what it replied. */
	static List<String> command(GameTestHelper helper, String command) {
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
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(helper.getLevel()).withSource(collector), command);
		return output;
	}

	/** How many blocks other than air are in the plot columns a vessel uses or used: those of its bounds and two columns round them. */
	static int blocksInPlot(ServerLevel level, VesselRecord record) {
		int count = 0;
		BlockPos min = record.plotMin();
		BlockPos max = record.plotMax();
		for (int cx = (min.getX() >> 4) - 2; cx <= (max.getX() >> 4) + 2; cx++) {
			for (int cz = (min.getZ() >> 4) - 2; cz <= (max.getZ() >> 4) + 2; cz++) {
				for (LevelChunkSection section : level.getChunk(cx, cz).getSections()) {
					if (section.hasOnlyAir()) {
						continue;
					}
					for (int i = 0; i < 4096; i++) {
						if (!section.getBlockState(i & 15, i >> 8, i >> 4 & 15).isAir()) {
							count++;
						}
					}
				}
			}
		}
		return count;
	}

	/** Item entities in and around a vessel's plot (there must never be any: drops belong where the vessel is). */
	static long itemEntitiesInPlot(ServerLevel level, VesselRecord record) {
		return level.getEntitiesOfClass(ItemEntity.class, AABB.encapsulatingFullBlocks(record.plotMin().offset(-64, -64, -64), record.plotMax().offset(64, 64, 64))).size();
	}

	/** A pose with the given rotation that keeps the helm block's centre where it is. */
	static VesselPose poseAboutHelm(BlockPos helmAbs, double yaw, double pitch, double roll) {
		VesselPose rotation = VesselPose.fromYawPitchRoll(0, 0, 0, yaw, pitch, roll);
		org.joml.Vector3d offset = rotation.rotate(0.5, 0.5, 0.5, new org.joml.Vector3d());
		return rotation.withPosition(helmAbs.getX() + 0.5 - offset.x, helmAbs.getY() + 0.5 - offset.y, helmAbs.getZ() + 0.5 - offset.z);
	}

	/** Runs with a temporary configuration. */
	static void withConfig(java.util.function.Consumer<SlipwayConfig> change, Runnable action) {
		SlipwayConfig previous = SlipwayConfig.get();
		SlipwayConfig temp = previous.sanitized();
		change.accept(temp);
		SlipwayConfig.set(temp);
		try {
			action.run();
		} finally {
			SlipwayConfig.set(previous);
		}
	}
}
