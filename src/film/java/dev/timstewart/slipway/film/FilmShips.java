package dev.timstewart.slipway.film;

import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.HelmBlock;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Ships for the film, as blocks relative to their helm (the vessel's origin), bow towards north (-z). */
final class FilmShips {
	private FilmShips() {
	}

	/** A small sailing skiff (spike test ship): deck, hull, keel, railings, a mast with a sail and a flag, a chest, a lantern. */
	static Map<BlockPos, BlockState> skiff() {
		Map<BlockPos, BlockState> b = new LinkedHashMap<>();
		BlockState deck = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState hull = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		for (int z = -8; z <= 2; z++) {
			int half = z <= -8 ? 0 : z <= -7 ? 1 : 2;
			for (int x = -half; x <= half; x++) {
				b.put(new BlockPos(x, -1, z), deck);
			}
		}
		for (int z = -7; z <= 2; z++) {
			int half = z <= -6 ? 0 : 1;
			for (int x = -half; x <= half; x++) {
				b.put(new BlockPos(x, -2, z), hull);
			}
		}
		for (int z = -5; z <= 1; z++) {
			b.put(new BlockPos(0, -3, z), Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
		}
		BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
		for (int z = -6; z <= 2; z++) {
			b.put(new BlockPos(-2, 0, z), fence);
			b.put(new BlockPos(2, 0, z), fence);
		}
		for (int y = 0; y <= 6; y++) {
			b.put(new BlockPos(0, y, -3), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
		}
		for (int x = -2; x <= 2; x++) {
			for (int y = 2; y <= 5; y++) {
				b.put(new BlockPos(x, y, -2), Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState());
			}
		}
		b.put(new BlockPos(0, 7, -3), Blocks.WOOL.pick(DyeColor.RED).defaultBlockState());
		b.put(new BlockPos(1, 0, 2), Blocks.CHEST.defaultBlockState());
		b.put(new BlockPos(-2, 1, 2), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
		b.put(BlockPos.ZERO, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.NORTH));
		return b;
	}
}
