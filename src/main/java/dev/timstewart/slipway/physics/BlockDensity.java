package dev.timstewart.slipway.physics;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A deliberately simple per-block density table, in kg per cubic metre of collision volume, keyed by the block's
 * sound type as a stand-in for its material. A block's mass is its density times the volume of its collision
 * shape; blocks without collision (torches, flowers) add no mass. Class indices double as merge keys, so boxes of
 * different materials never merge.
 */
public final class BlockDensity {
	public static final int DEFAULT = 0;
	public static final int WOOD = 1;
	public static final int STONE = 2;
	public static final int METAL = 3;
	public static final int GLASS = 4;
	public static final int LIGHT = 5;
	public static final int EARTH = 6;
	/** Density in kg/m^3 by class index. */
	public static final float[] DENSITIES = {1000F, 700F, 2400F, 7800F, 2500F, 200F, 1600F};
	public static final String[] NAMES = {"default", "wood", "stone", "metal", "glass", "light", "earth"};

	/** Lazily built: touching {@link SoundType} needs a bootstrapped game. */
	private static final class BySound {
		static final Map<SoundType, Integer> BY_SOUND = new IdentityHashMap<>();

		static {
		for (SoundType s : new SoundType[] {SoundType.WOOD, SoundType.LADDER, SoundType.BAMBOO, SoundType.BAMBOO_WOOD, SoundType.NETHER_WOOD,
			SoundType.CHERRY_WOOD, SoundType.SCAFFOLDING, SoundType.HANGING_SIGN, SoundType.NETHER_WOOD_HANGING_SIGN, SoundType.BAMBOO_WOOD_HANGING_SIGN,
			SoundType.CHERRY_WOOD_HANGING_SIGN, SoundType.CHISELED_BOOKSHELF, SoundType.SHELF, SoundType.STEM}) {
			BY_SOUND.put(s, WOOD);
		}
		for (SoundType s : new SoundType[] {SoundType.STONE, SoundType.DEEPSLATE, SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES,
			SoundType.POLISHED_DEEPSLATE, SoundType.TUFF, SoundType.TUFF_BRICKS, SoundType.POLISHED_TUFF, SoundType.CALCITE, SoundType.BASALT,
			SoundType.NETHERRACK, SoundType.NETHER_BRICKS, SoundType.NETHER_ORE, SoundType.NETHER_GOLD_ORE, SoundType.GILDED_BLACKSTONE,
			SoundType.DRIPSTONE_BLOCK, SoundType.MUD_BRICKS, SoundType.BONE_BLOCK, SoundType.CORAL_BLOCK, SoundType.RESIN_BRICKS,
			SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED, SoundType.LODESTONE, SoundType.AMETHYST}) {
			BY_SOUND.put(s, STONE);
		}
		for (SoundType s : new SoundType[] {SoundType.METAL, SoundType.ANVIL, SoundType.CHAIN, SoundType.LANTERN, SoundType.NETHERITE_BLOCK,
			SoundType.ANCIENT_DEBRIS, SoundType.COPPER, SoundType.COPPER_BULB, SoundType.COPPER_GRATE, SoundType.IRON, SoundType.HEAVY_CORE,
			SoundType.VAULT, SoundType.TRIAL_SPAWNER, SoundType.SPAWNER}) {
			BY_SOUND.put(s, METAL);
		}
		BY_SOUND.put(SoundType.GLASS, GLASS);
		for (SoundType s : new SoundType[] {SoundType.WOOL, SoundType.SNOW, SoundType.POWDER_SNOW, SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES,
			SoundType.MOSS, SoundType.MOSS_CARPET, SoundType.SPONGE, SoundType.WET_SPONGE, SoundType.SLIME_BLOCK, SoundType.HONEY_BLOCK,
			SoundType.WART_BLOCK, SoundType.SHROOMLIGHT, SoundType.FROGLIGHT, SoundType.CROP, SoundType.HARD_CROP, SoundType.VINE,
			SoundType.POPLAR_LEAVES, SoundType.LEAF_LITTER, SoundType.CANDLE, SoundType.COBWEB, SoundType.STRAW_BED}) {
			BY_SOUND.put(s, LIGHT);
		}
		for (SoundType s : new SoundType[] {SoundType.GRAVEL, SoundType.SAND, SoundType.GRASS, SoundType.ROOTED_DIRT, SoundType.MUD,
			SoundType.PACKED_MUD, SoundType.SOUL_SAND, SoundType.SOUL_SOIL, SoundType.SUSPICIOUS_SAND, SoundType.SUSPICIOUS_GRAVEL,
			SoundType.NYLIUM, SoundType.SCULK, SoundType.SCULK_CATALYST}) {
			BY_SOUND.put(s, EARTH);
		}
		}
	}

	private BlockDensity() {
	}

	public static int classOf(BlockState state) {
		Integer c = BySound.BY_SOUND.get(state.getSoundType());
		return c == null ? DEFAULT : c;
	}

	public static float densityOf(BlockState state) {
		return DENSITIES[classOf(state)];
	}
}
