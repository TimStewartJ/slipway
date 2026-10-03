package dev.timstewart.slipway.physics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

/**
 * Where the water and the lava are around the vessels of one level, as the physics thread knows it: one byte per
 * block for every chunk section near a vessel that holds any fluid. The game thread builds a section's bytes (see
 * {@code VesselPhysicsBridge}) and hands them over with a physics command; from then on only the physics thread
 * touches them, so one instance serves one thread.
 *
 * <p>A cell's byte is 0 without fluid; otherwise its low four bits are the fluid's amount (1 to 8, a source or
 * falling fluid has 8) and {@link #LAVA_BIT} tells lava from water. A fluid's surface lies {@code amount / 9} above
 * the bottom of its block, or at the block's top when the same fluid stands above it, as the game draws it.
 */
public final class FluidField implements FluidCells {
	public static final int NONE = 0;
	public static final int WATER = 1;
	public static final int LAVA = 2;
	public static final int AMOUNT_MASK = 0x0F;
	public static final int LAVA_BIT = 0x10;
	/** Densities in kg/m^3 by fluid ({@link #WATER}, {@link #LAVA}). */
	private static final double[] DENSITY = {0.0, 1000.0, 3100.0};

	private final Long2ObjectOpenHashMap<byte[]> sections = new Long2ObjectOpenHashMap<>();
	private long cachedKey = Long.MIN_VALUE;
	private byte @Nullable [] cached;
	private int lastFluid = NONE;
	private final int[] decided = new int[1];

	public static byte encode(int amount, boolean lava) {
		int a = Math.max(1, Math.min(8, amount));
		return (byte)(lava ? a | LAVA_BIT : a);
	}

	public static int fluidOf(int cell) {
		return cell == 0 ? NONE : (cell & LAVA_BIT) != 0 ? LAVA : WATER;
	}

	public static double density(int fluid) {
		return DENSITY[fluid];
	}

	/** Index of a block in a section's bytes, from its coordinates within the section (0 to 15). */
	public static int index(int x, int y, int z) {
		return (y << 4 | z) << 4 | x;
	}

	/**
	 * Sets the fluid of a section (4096 bytes, see {@link #index}); null removes it.
	 *
	 * @return whether the section held fluid before or holds some now
	 */
	public boolean put(long sectionKey, byte @Nullable [] cells) {
		byte[] before;
		if (cells == null) {
			before = this.sections.remove(sectionKey);
		} else {
			if (cells.length != 4096) {
				throw new IllegalArgumentException("a section has 4096 blocks");
			}
			before = this.sections.put(sectionKey, cells);
		}
		this.cachedKey = Long.MIN_VALUE;
		this.cached = null;
		return before != null || cells != null;
	}

	public void remove(long sectionKey) {
		this.put(sectionKey, null);
	}

	public void clear() {
		this.sections.clear();
		this.cachedKey = Long.MIN_VALUE;
		this.cached = null;
	}

	public boolean isEmpty() {
		return this.sections.isEmpty();
	}

	public int sectionCount() {
		return this.sections.size();
	}

	/** The byte of a block, 0 without fluid or where nothing is known. */
	@Override
	public int cell(int x, int y, int z) {
		long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
		byte[] section;
		if (key == this.cachedKey) {
			section = this.cached;
		} else {
			section = this.sections.get(key);
			this.cachedKey = key;
			this.cached = section;
		}
		return section == null ? 0 : section[index(x & 15, y & 15, z & 15)];
	}

	/** Whether any section touching a box of world coordinates holds fluid. */
	public boolean anyIn(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		if (this.sections.isEmpty()) {
			return false;
		}
		int x0 = SectionPos.blockToSectionCoord(minX), x1 = SectionPos.blockToSectionCoord(maxX);
		int y0 = SectionPos.blockToSectionCoord(minY), y1 = SectionPos.blockToSectionCoord(maxY);
		int z0 = SectionPos.blockToSectionCoord(minZ), z1 = SectionPos.blockToSectionCoord(maxZ);
		long volume = (long)(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
		if (volume > this.sections.size() * 4L) {
			// Fewer sections hold fluid than the box has: ask each of those instead.
			for (long key : this.sections.keySet()) {
				int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
				if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1 && sz >= z0 && sz <= z1) {
					return true;
				}
			}
			return false;
		}
		for (int sx = x0; sx <= x1; sx++) {
			for (int sz = z0; sz <= z1; sz++) {
				for (int sy = y0; sy <= y1; sy++) {
					if (this.sections.containsKey(SectionPos.asLong(sx, sy, sz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * How much of an upright cube lies below the fluid's surface, from 0 to 1: the cube has its centre at
	 * (x, y, z) and edges {@code size} long, and the surface is the one of the column of blocks through its centre.
	 * Changes smoothly as the cube rises and sinks. {@link #lastFluid()} tells which fluid it is in afterwards.
	 */
	public double submerged(double x, double y, double z, double size) {
		double part = submerged(this, x, y, z, size, this.decided);
		this.lastFluid = fluidOf(this.decided[0]);
		return part;
	}

	/**
	 * As {@link #submerged(double, double, double, double)}, for any source of fluid cells (the level itself, read by
	 * {@code Shelter}): one rule for the physics thread and for everything that asks the level.
	 *
	 * @param decided when not null, gets the byte of the fluid cell whose surface decided (0 when there is none)
	 */
	public static double submerged(FluidCells cells, double x, double y, double z, double size, int[] decided) {
		int cx = floor(x), cz = floor(z);
		double bottom = y - size * 0.5;
		int cy = floor(y);
		int raw = cells.cell(cx, cy, cz);
		double surface;
		if (raw == 0) {
			// The centre is dry: the surface, if any, is in a block further down, at most at the cube's bottom.
			int lowest = floor(bottom);
			int yy = cy - 1;
			while (yy >= lowest && (raw = cells.cell(cx, yy, cz)) == 0) {
				yy--;
			}
			if (decided != null) {
				decided[0] = raw;
			}
			if (raw == 0) {
				return 0.0;
			}
			surface = yy + (raw & AMOUNT_MASK) / 9.0;
		} else {
			// The centre is in fluid: follow it up to its surface, or to above the cube's top.
			int highest = floor(y + size * 0.5) + 1;
			int yy = cy;
			while (yy < highest) {
				int above = cells.cell(cx, yy + 1, cz);
				if (above == 0 || (above & LAVA_BIT) != (raw & LAVA_BIT)) {
					break;
				}
				raw = above;
				yy++;
			}
			if (decided != null) {
				decided[0] = raw;
			}
			if (yy >= highest) {
				return 1.0;
			}
			surface = yy + (raw & AMOUNT_MASK) / 9.0;
		}
		double part = (surface - bottom) / size;
		return part <= 0.0 ? 0.0 : Math.min(1.0, part);
	}

	/** The fluid the last {@link #submerged} call found ({@link #NONE} when it returned 0 for a dry place). */
	public int lastFluid() {
		return this.lastFluid;
	}

	private static int floor(double value) {
		int i = (int)value;
		return value < i ? i - 1 : i;
	}
}
