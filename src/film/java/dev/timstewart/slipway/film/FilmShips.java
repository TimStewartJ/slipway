package dev.timstewart.slipway.film;

import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.HelmBlock;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Ships for the film, as blocks relative to their helm (the vessel's origin), bow towards north (-z). */
final class FilmShips {
	private FilmShips() {
	}

	// ---- the hero ship: a three-masted sky galleon ----
	// Built in "ship" coordinates (main deck surface at y = 0, bow tip at z = -22, stern at z = 14), then shifted so
	// the helm (on the quarterdeck) is the origin.
	private static final BlockPos HERO_HELM = new BlockPos(0, 5, 12);
	/** Functional blocks on the stern castle's front wall, helm-relative. */
	static final BlockPos HERO_DOOR = ship(0, 0, 6);
	static final BlockPos HERO_CHEST = ship(-3, 0, 5);
	static final BlockPos HERO_LAMP = ship(3, 1, 6);
	static final BlockPos HERO_LEVER = ship(3, 1, 5);
	/** Where riders stand on the main deck (helm-relative block positions of the deck surface). */
	static final BlockPos[] HERO_DECK_SPOTS = {ship(-2, 0, -6), ship(2, 0, -4), ship(0, 0, 1), ship(-3, 0, 2)};

	private static BlockPos ship(int x, int y, int z) {
		return new BlockPos(x, y, z).subtract(HERO_HELM);
	}

	private static int bottom(int z) {
		if (z < -8) {
			return -7 + (int)Math.round(4 * Math.pow((-8 - z) / 14.0, 1.4));
		}
		if (z > 6) {
			return -7 + (int)Math.round(2.0 * (z - 6) / 8.0);
		}
		return -7;
	}

	private static int top(int z) {
		return z < -14 ? 1 : -1;
	}

	/** Hull half-width at a z and y (ship coordinates). */
	private static int halfWidth(int z, int y) {
		double lf;
		if (z < -9) {
			double t = (z + 9) / -13.0;
			lf = Math.sqrt(Math.max(0, 1 - t * t));
		} else if (z > 8) {
			double t = (z - 8) / 6.0;
			lf = 1 - 0.3 * t * t;
		} else {
			lf = 1;
		}
		int b = bottom(z);
		double d = Math.max(0, (-1.0 - y) / (-1.0 - b));
		double vf = Math.sqrt(Math.max(0, 1 - d * d * 0.8));
		return (int)Math.round(5.4 * lf * vf - 0.15);
	}

	private static boolean inHull(int x, int y, int z) {
		return z >= -22 && z <= 14 && y >= bottom(z) && y <= top(z) && Math.abs(x) <= halfWidth(z, Math.min(y, -1));
	}

	/** Any of the 26 neighbours outside the hull: the shell is then face-connected even where the curve steps. */
	private static boolean touchesOutside(int x, int y, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (!inHull(x + dx, y + dy, z + dz)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * The hero ship (about 1,500 blocks): a hollow dark-oak hull with a birch stripe and a keel, spruce decks, a raised
	 * forecastle and a two-storey stern castle with glass windows and a quarterdeck, three masts with billowing wool
	 * sails (red foot bands), yards, a crow's nest, flags, a bowsprit with a jib, dark-oak railings and lanterns. On the
	 * castle's front wall: a spruce door, a chest, and a redstone lamp with a lever on it.
	 */
	static Map<BlockPos, BlockState> hero() {
		Map<BlockPos, BlockState> s = new LinkedHashMap<>();
		BlockState darkOak = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState spruce = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState birch = Blocks.BIRCH_PLANKS.defaultBlockState();
		BlockState gunwale = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
		BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
		BlockState white = Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState();
		BlockState red = Blocks.WOOL.pick(DyeColor.RED).defaultBlockState();
		BlockState mast = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
		BlockState yard = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
		BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false);

		// hull: the shell of the volume (cells with a face outside it); decks are full layers
		for (int z = -22; z <= 14; z++) {
			for (int y = bottom(z); y <= top(z); y++) {
				int w = halfWidth(z, Math.min(y, -1));
				for (int x = -w; x <= w; x++) {
					boolean deck = y == top(z);
					boolean shell = deck || touchesOutside(x, y, z);
					if (!shell) {
						continue;
					}
					BlockState state;
					if (deck) {
						state = Math.abs(x) == w ? gunwale : spruce;
					} else if (x == 0 && y == bottom(z)) {
						state = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
					} else if (y == -3 && Math.abs(x) >= w - 1) {
						state = birch;
					} else {
						state = darkOak;
					}
					s.put(new BlockPos(x, y, z), state);
				}
			}
		}
		// railings on the main deck and forecastle (not along the castle)
		for (int z = -22; z <= 5; z++) {
			int w = halfWidth(z, -1);
			int y = top(z) + 1;
			for (int x : new int[] {-w, w}) {
				s.put(new BlockPos(x, y, z), fence);
			}
			if (z == -22) {
				for (int x = -w; x <= w; x++) {
					s.put(new BlockPos(x, y, z), fence);
				}
			}
			if (z == -14) {
				// the forecastle's aft edge
				for (int x = -halfWidth(-15, -1); x <= halfWidth(-15, -1); x++) {
					if (Math.abs(x) > 1) {
						s.put(new BlockPos(x, 2, -15), fence);
					}
				}
			}
		}
		// stern castle: walls y 0..3 from z 6 to 14, quarterdeck floor at y 4 with overhangs, railing at y 5
		BlockState post = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState();
		BlockState paneZ = Blocks.GLASS_PANE.defaultBlockState();
		for (int z = 6; z <= 14; z++) {
			int w = halfWidth(z, -1);
			for (int y = 0; y <= 3; y++) {
				for (int x = -w; x <= w; x++) {
					boolean side = Math.abs(x) == w;
					boolean end = z == 6 || z == 14;
					if (!side && !end) {
						continue;
					}
					BlockState state = spruce;
					if (side && end || (end && Math.abs(x) == 0 && z == 14)) {
						state = post;
					}
					boolean window = y >= 1 && y <= 2 && (side && (z == 8 || z == 9 || z == 11 || z == 12) && !end
						|| z == 14 && !side && (Math.abs(x) == 1 || Math.abs(x) == 2));
					if (window) {
						state = paneZ;
					}
					s.put(new BlockPos(x, y, z), state);
				}
			}
		}
		for (int z = 5; z <= 15; z++) {
			int w = z == 15 ? halfWidth(14, -1) - 1 : halfWidth(z, -1);
			for (int x = -w; x <= w; x++) {
				s.put(new BlockPos(x, 4, z), Math.abs(x) == w || z == 5 || z == 15 ? darkOak : spruce);
				if (Math.abs(x) == w || z == 5 || z == 15) {
					s.put(new BlockPos(x, 5, z), fence);
				}
			}
		}
		// the stair gap in the quarterdeck railing above the door is left closed; the helm stands aft
		// functional blocks on the castle front wall
		s.put(new BlockPos(0, 0, 6), Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		s.put(new BlockPos(0, 1, 6), Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		s.put(new BlockPos(-3, 0, 5), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
		s.put(new BlockPos(3, 1, 6), Blocks.REDSTONE_LAMP.defaultBlockState());
		s.put(new BlockPos(3, 1, 5), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.NORTH));
		s.put(new BlockPos(-2, 3, 5), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		s.put(new BlockPos(2, 3, 5), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		s.put(new BlockPos(0, 3, 10), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));

		// masts, yards, sails
		mast(s, -11, 0, 19, mast);
		mast(s, -2, 0, 24, mast);
		mast(s, 8, 5, 17, mast);
		sail(s, -11, 16, 10, 5, 4, white, red, yard);
		sail(s, -11, 9, 3, 6, 5, white, red, yard);
		sail(s, -2, 20, 13, 6, 5, white, red, yard);
		sail(s, -2, 12, 4, 7, 6, white, red, yard);
		sail(s, 8, 16, 9, 5, 4, white, red, yard);
		// crow's nest on the main mast
		for (int x = -1; x <= 1; x++) {
			for (int z = -3; z <= -1; z++) {
				if (x != 0 || z != -2) {
					s.put(new BlockPos(x, 21, z), spruce);
				}
			}
		}
		for (int x = -2; x <= 2; x++) {
			for (int z = -4; z <= 0; z++) {
				if (Math.abs(x) == 2 || z == -4 || z == 0) {
					if (Math.abs(x) <= 1 || Math.abs(z + 2) <= 1) {
						s.put(new BlockPos(x, 21, z), spruce);
						s.put(new BlockPos(x, 22, z), fence);
					}
				}
			}
		}
		// flags streaming aft from the mast tops
		flag(s, -11, 18, DyeColor.BLUE, DyeColor.WHITE);
		flag(s, -2, 23, DyeColor.RED, DyeColor.WHITE);
		flag(s, 8, 16, DyeColor.YELLOW, DyeColor.RED);
		// bowsprit and jib
		BlockState sprit = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
		int[][] bowsprit = {{2, -22}, {2, -23}, {2, -24}, {3, -24}, {3, -25}, {3, -26}, {4, -26}, {4, -27}};
		for (int[] c : bowsprit) {
			s.put(new BlockPos(0, c[0], c[1]), sprit);
		}
		for (int z = -25; z <= -14; z++) {
			double yHi = 4 + 13.0 * (z + 27) / 16.0;
			for (int y = 4; y <= (int)yHi; y++) {
				s.putIfAbsent(new BlockPos(0, y, z), white);
			}
		}
		// lanterns on railing posts
		for (int x : new int[] {-1, 1}) {
			s.put(new BlockPos(x * halfWidth(-13, -1), 1, -13), lantern);
			s.put(new BlockPos(x * halfWidth(4, -1), 1, 4), lantern);
			s.put(new BlockPos(x * halfWidth(-20, -1), 3, -20), lantern);
			s.put(new BlockPos(x * halfWidth(5, -1), 6, 5), lantern);
			s.put(new BlockPos(x * (halfWidth(14, -1) - 1), 6, 15), lantern);
		}
		s.put(HERO_HELM, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.NORTH));

		Map<BlockPos, BlockState> b = new LinkedHashMap<>();
		for (Map.Entry<BlockPos, BlockState> e : s.entrySet()) {
			b.put(e.getKey().subtract(HERO_HELM), e.getValue());
		}
		return b;
	}

	private static void mast(Map<BlockPos, BlockState> s, int z, int from, int to, BlockState mast) {
		for (int y = from; y <= to; y++) {
			s.put(new BlockPos(0, y, z), mast);
		}
	}

	/**
	 * A square sail hanging in front of a mast at {@code z}: a yard at {@code yardY} (half-width + 1), wool rows from
	 * {@code yardY - 1} down to {@code footY} (red at the foot), with a second layer in the middle for the belly.
	 */
	private static void sail(Map<BlockPos, BlockState> s, int z, int yardY, int footY, int yardHalf, int half, BlockState cloth, BlockState foot,
		BlockState yard) {
		for (int x = -yardHalf; x <= yardHalf; x++) {
			s.put(new BlockPos(x, yardY, z - 1), yard);
		}
		for (int y = footY; y < yardY; y++) {
			for (int x = -half; x <= half; x++) {
				BlockState state = y == footY ? foot : cloth;
				s.put(new BlockPos(x, y, z - 1), state);
				// the belly: two more layers, each inset, so the sail curves forward
				if (Math.abs(x) <= half - 1 && y > footY && y < yardY - 1) {
					s.put(new BlockPos(x, y, z - 2), cloth);
				}
				if (half >= 4 && Math.abs(x) <= half - 3 && y > footY + 1 && y < yardY - 2) {
					s.put(new BlockPos(x, y, z - 3), cloth);
				}
			}
		}
	}

	private static void flag(Map<BlockPos, BlockState> s, int z, int topY, DyeColor a, DyeColor b) {
		for (int dz = 1; dz <= 4; dz++) {
			s.put(new BlockPos(0, topY, z + dz), Blocks.WOOL.pick(dz % 2 == 1 ? a : b).defaultBlockState());
			if (dz <= 3) {
				s.put(new BlockPos(0, topY - 1, z + dz), Blocks.WOOL.pick(dz % 2 == 1 ? b : a).defaultBlockState());
			}
		}
	}

	/** Places a ship with its helm at {@code helm}, then lets fences, panes and doors take their connected shapes. */
	static void place(net.minecraft.server.level.ServerLevel level, BlockPos helm, Map<BlockPos, BlockState> blocks) {
		for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
			level.setBlock(helm.offset(e.getKey()), e.getValue(), 2 | 16);
		}
		for (BlockPos rel : blocks.keySet()) {
			BlockPos pos = helm.offset(rel);
			BlockState state = level.getBlockState(pos);
			BlockState shaped = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, level, pos);
			if (shaped != state) {
				level.setBlock(pos, shaped, 2 | 16);
			}
		}
	}

	/** Fills the hero ship's chest (helm at {@code helm}). */
	static void fillHero(net.minecraft.server.level.ServerLevel level, BlockPos helm) {
		var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(helm.offset(HERO_CHEST));
		chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MAP, 1));
		chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS, 1));
		chest.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 24));
		chest.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 16));
		chest.setChanged();
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
