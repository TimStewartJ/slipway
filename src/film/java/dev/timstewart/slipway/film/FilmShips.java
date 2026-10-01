package dev.timstewart.slipway.film;

import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.HelmBlock;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Ships for the film, as blocks relative to their helm (the vessel's origin), bow towards north (-z). */
final class FilmShips {
	private FilmShips() {
	}

	// ---- the hero ship: a three-masted sky galleon ----
	// Built in "ship" coordinates (main deck surface at y = 0, bow tip at z = -22, stern at z = 14), then shifted so
	// the helm (on the quarterdeck) is the origin.
	private static final BlockPos HERO_HELM = new BlockPos(0, 5, 12);
	/** Functional blocks, helm-relative. On the stern castle's front wall (z = 6): the door, the chest and the machine. */
	static final BlockPos HERO_DOOR = ship(0, 0, 6);
	static final BlockPos HERO_CHEST = ship(-2, 0, 5);
	/** The wall machine's lever (starts its clock). */
	static final BlockPos HERO_LEVER = ship(-3, 1, 5);
	/** The lamp strip in the wall's top row, in the order the signal reaches them, and the pistons in front of the wall. */
	static final BlockPos[] HERO_LAMPS = {ship(-4, 3, 6), ship(-2, 3, 6), ship(0, 3, 6), ship(2, 3, 6), ship(4, 3, 6)};
	static final BlockPos[] HERO_PISTONS = {ship(2, 0, 5), ship(3, 0, 5), ship(4, 0, 5)};
	/** The farm bed along the middle of the main deck, between the fore and main masts: wheat, the dispensers that face it, and the clock's lever. */
	static final BlockPos[] HERO_WHEAT = {ship(0, 0, -9), ship(0, 0, -8), ship(0, 0, -6), ship(0, 0, -5)};
	static final BlockPos[] HERO_DISPENSERS = {ship(1, 0, -9), ship(1, 0, -8), ship(1, 0, -6), ship(1, 0, -5)};
	static final BlockPos HERO_FARM_LEVER = ship(1, 1, -2);
	/** Where riders stand on the main deck: to starboard of the farm bed (helm-relative block positions of the deck surface). */
	static final BlockPos[] HERO_DECK_SPOTS = {ship(3, 0, -9), ship(4, 0, -7), ship(2, 0, -6), ship(4, 0, -10)};

	static BlockPos ship(int x, int y, int z) {
		return new BlockPos(x, y, z).subtract(HERO_HELM);
	}

	/** A point in the hero ship's build coordinates, helm-relative (vessel-local). */
	static net.minecraft.world.phys.Vec3 shipPoint(double x, double y, double z) {
		return new net.minecraft.world.phys.Vec3(x - HERO_HELM.getX(), y - HERO_HELM.getY(), z - HERO_HELM.getZ());
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
	 * The hero ship: a hollow dark-oak hull with a birch stripe and a keel, spruce decks, a raised forecastle and a
	 * two-storey stern castle with glass windows and a quarterdeck, three masts with billowing wool sails (red foot
	 * bands), yards, a crow's nest, flags, a bowsprit with a jib, dark-oak railings and lanterns. Working parts: on the
	 * castle's front wall a spruce door, a chest and the redstone machine ({@link #machine}); on the main deck the farm
	 * bed ({@link #farm}); and an opening in the port rail for cargo.
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
		// railings on the main deck and forecastle (not along the castle); the port rail is open for six blocks aft of the
		// main mast, where cargo is loaded (and spills)
		for (int z = -22; z <= 5; z++) {
			int w = halfWidth(z, -1);
			int y = top(z) + 1;
			for (int x : new int[] {-w, w}) {
				if (x < 0 && z >= CARGO_GAP_FROM && z <= CARGO_GAP_TO) {
					continue;
				}
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
		s.put(new BlockPos(-2, 0, 5), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
		s.put(new BlockPos(0, 3, 10), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		machine(s);
		farm(s);

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
			// the port one stands on the first rail post aft of the cargo bay's opening
			s.put(new BlockPos(x * halfWidth(4, -1), 1, x < 0 ? CARGO_GAP_TO + 1 : 4), lantern);
			s.put(new BlockPos(x * halfWidth(-20, -1), 3, -20), lantern);
			s.put(new BlockPos(x * halfWidth(5, -1), 6, 5), lantern);
			s.put(new BlockPos(x * (halfWidth(14, -1) - 1), 6, 15), lantern);
		}
		// the helm faces the pilot standing behind it: facing south, forward is north (the bow)
		s.put(HERO_HELM, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, FilmRig.opt("helm", "south").equals("north") ? Direction.NORTH : Direction.SOUTH));

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
				int layers = (int)FilmRig.optDouble("sailLayers", 3);
				if (layers >= 2 && Math.abs(x) <= half - 1 && y > footY && y < yardY - 1) {
					s.put(new BlockPos(x, y, z - 2), cloth);
				}
				if (layers >= 3 && half >= 4 && Math.abs(x) <= half - 3 && y > footY + 1 && y < yardY - 2) {
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

	/** The port rail is open from here to there (ship z): the cargo bay. */
	static final int CARGO_GAP_FROM = -1;
	static final int CARGO_GAP_TO = 4;

	private static BlockState wire() {
		return Blocks.REDSTONE_WIRE.defaultBlockState();
	}

	/** A repeater taking its input from {@code inputSide} and putting out on the opposite side. */
	private static BlockState repeater(Direction inputSide, int delay) {
		return Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, inputSide).setValue(RepeaterBlock.DELAY, Math.max(1, Math.min(4, delay)));
	}

	/** A comparator in subtract mode taking its input from {@code inputSide}. */
	private static BlockState comparator(Direction inputSide) {
		return Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, inputSide).setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT);
	}

	/**
	 * The machine on the stern castle's front wall (z = 6), all plain redstone, everything unpowered as built:
	 *
	 * <ul>
	 * <li>the lever on the wall (port of the door) powers its wall block; behind it, inside the castle, dust leads to a
	 * comparator in subtract mode whose output runs through a repeater back into its own side: a clock that runs
	 * while the lever is on (period 2 x (1 + repeater delay) redstone ticks);
	 * <li>the clock's output climbs a dust staircase to the top row of the wall, where five lamps alternate with four
	 * repeaters (each lamp is powered by the repeater before it and read by the one after it): the lamps light one
	 * after the other from port to starboard, and go out the same way;
	 * <li>behind the last lamp a repeater sends the signal down another staircase to three repeaters with delays of 1,
	 * 2 and 3 ticks, each powering the wall block behind a sticky piston that stands on the deck in front of the wall
	 * and lifts an iron block: the pistons pump one after the other.
	 * </ul>
	 * No powered block touches the door, which is opened separately.
	 */
	private static void machine(Map<BlockPos, BlockState> s) {
		BlockState spruce = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState back = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		int lampDelay = (int)FilmRig.optDouble("lampDelay", 1);
		for (int x = -4; x <= 4; x++) {
			if (x % 2 == 0) {
				s.put(new BlockPos(x, 3, 6), Blocks.REDSTONE_LAMP.defaultBlockState());
			} else {
				s.put(new BlockPos(x, 3, 6), repeater(Direction.WEST, lampDelay));
				s.put(new BlockPos(x, 3, 7), back);
			}
		}
		// lever and clock
		s.put(new BlockPos(-3, 1, 5), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.NORTH));
		s.put(new BlockPos(-3, 0, 7), spruce);
		s.put(new BlockPos(-3, 1, 7), wire());
		s.put(new BlockPos(-3, 0, 8), wire());
		s.put(new BlockPos(-3, 0, 9), comparator(Direction.NORTH));
		s.put(new BlockPos(-3, 0, 10), wire());
		s.put(new BlockPos(-2, 0, 10), wire());
		s.put(new BlockPos(-1, 0, 10), wire());
		s.put(new BlockPos(-1, 0, 9), wire());
		s.put(new BlockPos(-2, 0, 9), repeater(Direction.EAST, (int)FilmRig.optDouble("clockDelay", 4)));
		// up to the port end of the lamp strip
		s.put(new BlockPos(-3, 0, 11), wire());
		s.put(new BlockPos(-4, 0, 11), wire());
		s.put(new BlockPos(-4, 0, 10), spruce);
		s.put(new BlockPos(-4, 1, 10), wire());
		s.put(new BlockPos(-4, 1, 9), spruce);
		s.put(new BlockPos(-4, 2, 9), wire());
		s.put(new BlockPos(-4, 2, 8), spruce);
		s.put(new BlockPos(-4, 3, 8), wire());
		s.put(new BlockPos(-4, 2, 7), spruce);
		s.put(new BlockPos(-4, 3, 7), repeater(Direction.SOUTH, 1));
		// from the starboard end of the lamp strip down to the pistons
		s.put(new BlockPos(4, 2, 7), spruce);
		s.put(new BlockPos(4, 3, 7), repeater(Direction.NORTH, 1));
		s.put(new BlockPos(4, 2, 8), spruce);
		s.put(new BlockPos(4, 3, 8), wire());
		s.put(new BlockPos(4, 1, 9), spruce);
		s.put(new BlockPos(4, 2, 9), wire());
		s.put(new BlockPos(4, 0, 10), spruce);
		s.put(new BlockPos(4, 1, 10), wire());
		s.put(new BlockPos(4, 0, 11), wire());
		s.put(new BlockPos(3, 0, 11), wire());
		s.put(new BlockPos(3, 0, 10), wire());
		s.put(new BlockPos(3, 0, 9), wire());
		for (int x = 2; x <= 4; x++) {
			s.put(new BlockPos(x, 0, 8), wire());
			s.put(new BlockPos(x, 0, 7), repeater(Direction.SOUTH, x - 1));
			s.put(new BlockPos(x, 0, 5), Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
			s.put(new BlockPos(x, 1, 5), Blocks.IRON_BLOCK.defaultBlockState());
		}
	}

	/**
	 * The farm bed along the middle of the main deck, between the fore and main masts: four wheat plants (just
	 * planted) on moist farmland set into the deck around a waterlogged slab (water source blocks are not assembled;
	 * a waterlogged block is), a birch fence on the port side and at both ends, and on the starboard side four
	 * dispensers that face the wheat (a barrel stands behind the water). Redstone dust on top of the dispensers joins
	 * them to a clock aft of the bed: a lever on a block beside the main mast, a comparator in subtract mode reading
	 * that block, and two repeaters leading its output back into its side. While the lever is on, the dispensers fire
	 * once per period (2 x (1 + the two repeater delays) redstone ticks).
	 */
	private static void farm(Map<BlockPos, BlockState> s) {
		BlockState fence = Blocks.BIRCH_FENCE.defaultBlockState();
		for (int z = -9; z <= -5; z++) {
			boolean water = z == -7;
			s.put(new BlockPos(0, -1, z), water
				? Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED, true)
				: Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE));
			if (!water) {
				s.put(new BlockPos(0, 0, z), Blocks.WHEAT.defaultBlockState());
			}
			s.put(new BlockPos(1, 0, z), water ? Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP)
				: Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.WEST));
			s.put(new BlockPos(1, 1, z), wire());
		}
		for (int z = -10; z <= -4; z++) {
			s.put(new BlockPos(-1, 0, z), fence);
		}
		s.put(new BlockPos(0, 0, -10), fence);
		s.put(new BlockPos(1, 0, -10), fence);
		s.put(new BlockPos(0, 0, -4), fence);
		// the clock: comparator (input from the lever's block to its south, output north), its output led east through a
		// repeater, round and back through a second repeater into the comparator's east side
		s.put(new BlockPos(1, 0, -4), wire());
		s.put(new BlockPos(1, 0, -3), comparator(Direction.SOUTH));
		s.put(new BlockPos(1, 0, -2), Blocks.SPRUCE_PLANKS.defaultBlockState());
		s.put(new BlockPos(1, 1, -2), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH));
		s.put(new BlockPos(2, 0, -4), wire());
		s.put(new BlockPos(3, 0, -4), repeater(Direction.WEST, (int)FilmRig.optDouble("farmDelayA", 4)));
		s.put(new BlockPos(4, 0, -4), wire());
		s.put(new BlockPos(4, 0, -3), wire());
		s.put(new BlockPos(3, 0, -3), wire());
		s.put(new BlockPos(2, 0, -3), repeater(Direction.EAST, (int)FilmRig.optDouble("farmDelayB", 2)));
	}

	/** Fills the hero ship's containers (helm at {@code helm}): the chest, and bone meal for the dispensers. */
	static void fillHero(net.minecraft.server.level.ServerLevel level, BlockPos helm) {
		var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(helm.offset(HERO_CHEST));
		chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MAP, 1));
		chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS, 1));
		chest.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 24));
		chest.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 16));
		chest.setChanged();
		for (BlockPos pos : HERO_DISPENSERS) {
			var dispenser = (net.minecraft.world.level.block.entity.DispenserBlockEntity)level.getBlockEntity(helm.offset(pos));
			dispenser.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BONE_MEAL, 64));
			dispenser.setChanged();
		}
	}

	// ---- loose cargo ----

	/**
	 * A cargo piece: its blocks relative to its own helm (each piece is a vessel of its own and needs one), and where
	 * it is built before it is let go: the place of its middle over the hero ship's deck in ship coordinates (x, z)
	 * and the height of its lowest block above the deck surface.
	 */
	record Cargo(String name, Map<BlockPos, BlockState> blocks, double x, double z, int height) {
		int minY() {
			return this.blocks.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
		}

		/** The middle of the piece's footprint, relative to its helm block's corner. */
		double midX() {
			return (this.blocks.keySet().stream().mapToInt(BlockPos::getX).min().orElse(0) + this.blocks.keySet().stream().mapToInt(BlockPos::getX).max().orElse(0) + 1) / 2.0;
		}

		double midZ() {
			return (this.blocks.keySet().stream().mapToInt(BlockPos::getZ).min().orElse(0) + this.blocks.keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0) + 1) / 2.0;
		}
	}

	private static BlockState helmBlock() {
		return SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.NORTH);
	}

	private static Map<BlockPos, BlockState> box(int x0, int x1, int y0, int y1, int z0, int z1, BlockState state) {
		Map<BlockPos, BlockState> b = new LinkedHashMap<>();
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					b.put(new BlockPos(x, y, z), state);
				}
			}
		}
		b.put(BlockPos.ZERO, helmBlock());
		return b;
	}

	/**
	 * The cargo for the spill shot: eight pieces of 2 to 27 blocks. The helm looks like a wooden block with a wheel on
	 * one face; it is the hidden core of the barrel stack and shows as one block of the others.
	 */
	static java.util.List<Cargo> cargo() {
		BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
		BlockState hay = Blocks.HAY_BLOCK.defaultBlockState();
		BlockState log = Blocks.BIRCH_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
		BlockState beam = Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
		BlockState pumpkin = Blocks.PUMPKIN.defaultBlockState();
		BlockState wool = Blocks.WOOL.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
		BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
		Map<BlockPos, BlockState> pumpkins = box(0, 1, 0, 0, 0, 1, pumpkin);
		pumpkins.put(new BlockPos(0, 1, 0), pumpkin);
		Map<BlockPos, BlockState> keg = new LinkedHashMap<>();
		keg.put(BlockPos.ZERO, helmBlock());
		keg.put(new BlockPos(0, 1, 0), barrel);
		java.util.List<Cargo> pieces = new java.util.ArrayList<>();
		// kept clear of the main mast and its yards (forward of z = 0) and of the quarterdeck's overhang (z = 5)
		pieces.add(new Cargo("barrels", box(-1, 1, -1, 1, -1, 1, barrel), -2.0, 2.0, 3));
		pieces.add(new Cargo("keg", keg, 2.2, 0.8, 3));
		pieces.add(new Cargo("crate", box(-1, 1, 0, 1, 0, 1, planks), 1.6, 2.4, 7));
		pieces.add(new Cargo("pumpkins", pumpkins, -3.0, 2.5, 7));
		pieces.add(new Cargo("logs", box(0, 1, 0, 1, -1, 2, log), -0.3, 2.0, 10));
		pieces.add(new Cargo("hay", box(0, 1, 0, 1, 0, 1, hay), 0.6, 1.4, 13));
		pieces.add(new Cargo("beam", box(0, 0, 0, 0, -2, 2, beam), -3.0, 2.3, 13));
		pieces.add(new Cargo("wool", box(0, 1, 0, 1, 0, 1, wool), -0.2, 2.6, 16));
		return pieces;
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
