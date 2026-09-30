package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;

/**
 * The test ships of the old end-to-end harness, relative to their helm (vessel-local coordinates: the helm is the
 * vessel's origin), built on the server thread.
 */
final class Ships {
	private Ships() {
	}

	static BlockState helm(Direction facing) {
		return SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, facing);
	}

	/** A square deck of oak planks one block below the helm, half-width {@code r} (so (2r+1)^2 planks). */
	static Map<BlockPos, BlockState> deck(int r, BlockState plank) {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		for (int x = -r; x <= r; x++) {
			for (int z = -r; z <= r; z++) {
				blocks.put(new BlockPos(x, -1, z), plank);
			}
		}
		return blocks;
	}

	static final BlockPos MIXED_CHEST = new BlockPos(-3, 0, -3);
	static final BlockPos MIXED_FURNACE = new BlockPos(-3, 0, -1);
	static final BlockPos MIXED_DOOR = new BlockPos(3, 0, -3);
	static final BlockPos MIXED_SIGN = new BlockPos(3, 0, 3);
	static final BlockPos MIXED_LAMP = new BlockPos(-3, 0, 3);
	static final BlockPos MIXED_LEVER = new BlockPos(-3, 1, 3);

	/**
	 * The mixed ship: a 9x9 oak deck with a north-facing helm, a chest (7 diamonds, 32 oak logs, a name tag "Slipway"),
	 * an unlit furnace (3 coal as fuel, 9 iron ingots as output: nothing to smelt), a closed oak door, a standing sign
	 * ("Slipway" / "e2e test"), an unlit redstone lamp with an unpowered lever on it, east-facing stairs and a torch:
	 * 81 + 10 = 91 blocks, three of them with block entities.
	 */
	static Map<BlockPos, BlockState> mixedShip() {
		Map<BlockPos, BlockState> blocks = deck(4, Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(BlockPos.ZERO, helm(Direction.NORTH));
		blocks.put(MIXED_CHEST, Blocks.CHEST.defaultBlockState());
		blocks.put(MIXED_FURNACE, Blocks.FURNACE.defaultBlockState());
		blocks.put(MIXED_DOOR, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.FACING, Direction.NORTH));
		blocks.put(MIXED_DOOR.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.NORTH));
		blocks.put(MIXED_SIGN, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 4));
		blocks.put(MIXED_LAMP, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, false));
		blocks.put(MIXED_LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH)
			.setValue(LeverBlock.POWERED, false));
		blocks.put(new BlockPos(1, 0, -3), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.BOTTOM));
		blocks.put(new BlockPos(2, 0, 2), Blocks.TORCH.defaultBlockState());
		return blocks;
	}

	static void fillMixed(ServerLevel level, BlockPos helm) {
		ChestBlockEntity chest = (ChestBlockEntity)level.getBlockEntity(helm.offset(MIXED_CHEST));
		chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
		chest.setItem(1, new ItemStack(Items.OAK_LOG, 32));
		ItemStack tag = new ItemStack(Items.NAME_TAG);
		tag.set(DataComponents.CUSTOM_NAME, Component.literal("Slipway"));
		chest.setItem(2, tag);
		chest.setChanged();
		AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity)level.getBlockEntity(helm.offset(MIXED_FURNACE));
		furnace.setItem(1, new ItemStack(Items.COAL, 3));
		furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 9));
		furnace.setChanged();
		SignBlockEntity sign = (SignBlockEntity)level.getBlockEntity(helm.offset(MIXED_SIGN));
		List<Component> lines = List.of(Component.literal("Slipway"), Component.literal("e2e test"), Component.empty(), Component.empty());
		sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
		sign.setChanged();
	}

	/**
	 * The small ship: a 7x7 oak deck with a north-facing helm, an oak-log mast with a red wool flag, a chest (5 gold
	 * ingots), a lantern and two fence posts.
	 */
	static Map<BlockPos, BlockState> smallShip() {
		Map<BlockPos, BlockState> blocks = deck(3, Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(BlockPos.ZERO, helm(Direction.NORTH));
		blocks.put(new BlockPos(0, 0, 3), Blocks.OAK_LOG.defaultBlockState());
		blocks.put(new BlockPos(0, 1, 3), Blocks.OAK_LOG.defaultBlockState());
		blocks.put(new BlockPos(0, 2, 3), Blocks.OAK_LOG.defaultBlockState());
		blocks.put(new BlockPos(1, 2, 3), Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
		blocks.put(SMALL_CHEST, Blocks.CHEST.defaultBlockState());
		blocks.put(new BlockPos(2, 0, -2), Blocks.LANTERN.defaultBlockState());
		blocks.put(new BlockPos(-3, 0, 3), Blocks.OAK_FENCE.defaultBlockState());
		blocks.put(new BlockPos(3, 0, 3), Blocks.OAK_FENCE.defaultBlockState());
		return blocks;
	}

	static final BlockPos SMALL_CHEST = new BlockPos(-2, 0, -2);

	static void fillSmall(ServerLevel level, BlockPos helm) {
		ChestBlockEntity chest = (ChestBlockEntity)level.getBlockEntity(helm.offset(SMALL_CHEST));
		chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
		chest.setChanged();
	}

	static void build(ServerLevel level, BlockPos helm, Map<BlockPos, BlockState> blocks) {
		// supports first (decks), then what stands on them, so nothing pops off while building
		for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
			level.setBlock(helm.offset(e.getKey()), e.getValue(), 2 | 16);
		}
	}

	/** Clears a box to air (no drops). */
	static void clear(ServerLevel level, BlockPos min, BlockPos max) {
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			if (!level.getBlockState(p).isAir()) {
				level.removeBlockEntity(p);
				level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
			}
		}
	}

	/** Saved data of each block entity at the given helm-relative positions (without position, as a vessel stores it). */
	static Map<BlockPos, CompoundTag> blockEntityData(ServerLevel level, BlockPos helm, Iterable<BlockPos> relatives) {
		Map<BlockPos, CompoundTag> data = new LinkedHashMap<>();
		for (BlockPos rel : relatives) {
			BlockEntity be = level.getBlockEntity(helm.offset(rel));
			if (be != null) {
				data.put(rel.immutable(), be.saveWithoutMetadata(level.registryAccess()));
			}
		}
		return data;
	}

	/** Block-entity data of a vessel's blocks, read from its plot, keyed by vessel-local position. */
	static Map<BlockPos, CompoundTag> plotBlockEntityData(ServerLevel level, VesselRecord record, Iterable<BlockPos> locals) {
		Map<BlockPos, CompoundTag> data = new LinkedHashMap<>();
		for (BlockPos local : locals) {
			BlockEntity be = level.getBlockEntity(record.toPlot(local));
			if (be != null) {
				data.put(local.immutable(), be.saveWithoutMetadata(level.registryAccess()));
			}
		}
		return data;
	}

	/** Mismatches between a ship spec and the vessel's plot (vessel-local = helm-relative). Empty when identical. */
	static List<String> plotMismatches(ServerLevel level, VesselRecord record, Map<BlockPos, BlockState> spec) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : spec.entrySet()) {
			BlockState actual = level.getBlockState(record.toPlot(e.getKey()));
			if (actual != e.getValue()) {
				out.add(e.getKey().toShortString() + ": expected " + e.getValue() + ", plot has " + actual);
			}
		}
		return out;
	}

	/** Positions of a spec (helm-relative) that are not air in the world. */
	static List<String> notAir(ServerLevel level, BlockPos helm, Iterable<BlockPos> relatives) {
		List<String> out = new ArrayList<>();
		for (BlockPos rel : relatives) {
			BlockState state = level.getBlockState(helm.offset(rel));
			if (!state.isAir()) {
				out.add(rel.toShortString() + " still " + state);
			}
		}
		return out;
	}

	/** Where a disassembled ship landed: its helm's world position and the quarter turns it was placed with. */
	record Landing(BlockPos helm, int quarterTurns, Rotation rotation) {
	}

	/** Finds the only helm within {@code radius} of a point after disassembly; the helm's facing gives the turns. */
	static Landing findLanding(ServerLevel level, BlockPos around, int radius, Direction originalFacing) {
		List<BlockPos> helms = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(around.offset(-radius, -radius, -radius), around.offset(radius, radius, radius))) {
			if (level.getBlockState(p).is(SlipwayRegistry.HELM)) {
				helms.add(p.immutable());
			}
		}
		Check.that(helms.size() == 1, "expected exactly one helm within %d blocks of %s after disassembly, found %s", radius, around.toShortString(), helms);
		Direction facing = level.getBlockState(helms.getFirst()).getValue(HelmBlock.FACING);
		for (int q = 0; q < 4; q++) {
			Rotation rotation = VesselPose.minecraftRotation(q);
			if (rotation.rotate(originalFacing) == facing) {
				return new Landing(helms.getFirst(), q, rotation);
			}
		}
		throw new AssertionError("helm facing " + facing + " is not a quarter turn of " + originalFacing);
	}

	/** World position of a helm-relative block after landing with the given rotation. */
	static BlockPos landed(Landing landing, BlockPos relative) {
		int[] xz = VesselPose.rotateQuarterTurns(relative.getX(), relative.getZ(), landing.quarterTurns());
		return landing.helm().offset(xz[0], relative.getY(), xz[1]);
	}

	/** Mismatches between a spec and a landed ship: every block at its rotated place with its rotated state. */
	static List<String> landedMismatches(ServerLevel level, Landing landing, Map<BlockPos, BlockState> spec) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : spec.entrySet()) {
			BlockPos pos = landed(landing, e.getKey());
			BlockState expected = e.getValue().rotate(landing.rotation());
			BlockState actual = level.getBlockState(pos);
			if (actual != expected) {
				out.add(e.getKey().toShortString() + " at " + pos.toShortString() + ": expected " + expected + ", world has " + actual);
			}
		}
		return out;
	}

	/** Block-entity data of a landed ship, keyed by the spec's helm-relative positions. */
	static Map<BlockPos, CompoundTag> landedBlockEntityData(ServerLevel level, Landing landing, Iterable<BlockPos> relatives) {
		Map<BlockPos, CompoundTag> data = new LinkedHashMap<>();
		for (BlockPos rel : relatives) {
			BlockEntity be = level.getBlockEntity(landed(landing, rel));
			if (be != null) {
				data.put(rel.immutable(), be.saveWithoutMetadata(level.registryAccess()));
			}
		}
		return data;
	}

	static long itemEntities(ServerLevel level, AABB box) {
		return level.getEntitiesOfClass(ItemEntity.class, box).size();
	}

	static VesselRecord assemble(ServerLevel level, BlockPos helmAbs) {
		VesselAssembly.Outcome outcome = VesselManager.get(level).assemble(helmAbs, null);
		Check.that(outcome.success() && outcome.record() != null, "assembly at %s failed: %s", helmAbs.toShortString(), outcome.message().getString());
		return outcome.record();
	}
}
