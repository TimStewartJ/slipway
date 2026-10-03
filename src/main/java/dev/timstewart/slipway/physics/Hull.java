package dev.timstewart.slipway.physics;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/**
 * What a vessel pushes aside when it lies in water, in the vessel's own frame: its blocks, and the air its blocks
 * keep the water out of. Built from the blocks alone (plain Java, the same on server and client), immutable after.
 *
 * <p>Every block cell of the vessel is one of four things. A <em>watertight</em> cell holds a block that water does
 * not pass (any block with a collision shape, unless tagged otherwise). An <em>open</em> cell is one the water
 * outside reaches as soon as it stands that high. A <em>sheltered</em> cell is air below a rim: think of pouring
 * water into the vessel standing upright; it is a cell that would fill. Water from outside only gets there by
 * running over that rim, so until it does the cell is dry and its volume counts as displaced: this is what lets an
 * open hull of planks carry more than a raft of the same planks, and a hull of stone float at all. A <em>sealed</em>
 * cell is air with no way out at all (a closed cabin); it is always dry.
 *
 * <p>The rim of a sheltered cell is found with a priority flood from outside (Barnes, Lehman and Mulla, "Priority-
 * flood: an optimal depression-filling and watershed-labeling algorithm for digital elevation models", 2014, here in
 * three dimensions with the cell's height as its elevation): every open cell gets the lowest level the water outside
 * must reach to run into it, and the cell on the way in where that level is reached, its <em>pour point</em>. A cell
 * whose level is above itself is sheltered. At run time a sheltered cell floods as its pour point goes under water
 * ({@link #flooding}), wherever the vessel's attitude has taken that point: a boat that heels until its gunwale dips
 * takes in water along that side first.
 *
 * <p>For buoyancy the cells are listed as {@linkplain #elementCount() elements}: a centre, a volume and an edge
 * length each, and for sheltered air the pour point that decides whether it is dry. Very large vessels get coarser
 * elements (cubes of 2, 4, ... cells merged) so that a step never has more than {@link #MAX_ELEMENTS} to look at.
 */
public final class Hull {
	/** Class of an open cell: the water outside reaches it. */
	public static final int OPEN = -2;
	/** Class of a cell holding a watertight block. */
	public static final int WATERTIGHT = -3;
	/** Class of an air cell with no way out; also the pour index of an element that always displaces. */
	public static final int SEALED = -1;
	/** Most elements one hull is evaluated with per step. */
	public static final int MAX_ELEMENTS = 8192;
	/** Largest box of cells (the vessel's bounds and one cell around) the flood is run on; larger hulls get no sheltered air. */
	public static final int MAX_GRID_CELLS = 1 << 20;
	/** The least height a block's shape is taken to have (a carpet is a sixteenth). */
	public static final float MIN_HEIGHT = 1f / 16f;
	/** A sheltered cell is fully flooded once this much of its pour point's cell is under water. */
	public static final double FLOOD_DEPTH = 0.5;

	public static final Hull EMPTY = new Hull(0, 0, 0, 0, 0, 0, null, new Elements(), new float[0], 0, 0, 0, true);

	private final int minX, minY, minZ;
	private final int sizeX, sizeY, sizeZ;
	/** Class of every cell of the bounds ({@link #OPEN}, {@link #WATERTIGHT}, {@link #SEALED} or a pour index), or null. */
	private final int[] cells;
	private final float[] ex, ey, ez, volume, edge;
	private final int[] pour;
	private final boolean[] outside;
	private final float[] outX, outZ;
	/** Pour points: local x, y, z of the centre of each pour cell. */
	private final float[] pours;
	private final double blockVolume, shelteredVolume, sealedVolume;
	private final boolean flooded;

	private Hull(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, int[] cells, Elements elements, float[] pours, double blockVolume,
		double shelteredVolume, double sealedVolume, boolean flooded) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cells = cells;
		this.ex = elements.x.toFloatArray();
		this.ey = elements.y.toFloatArray();
		this.ez = elements.z.toFloatArray();
		this.volume = elements.volume.toFloatArray();
		this.edge = elements.edge.toFloatArray();
		this.pour = elements.pour.toIntArray();
		this.outside = elements.outside.toBooleanArray();
		this.outX = elements.outX.toFloatArray();
		this.outZ = elements.outZ.toFloatArray();
		this.pours = pours;
		this.blockVolume = blockVolume;
		this.shelteredVolume = shelteredVolume;
		this.sealedVolume = sealedVolume;
		this.flooded = flooded;
	}

	public int elementCount() {
		return this.volume.length;
	}

	/** Local bounds of the blocks the hull was built from: the lowest corner and the size in blocks. */
	public int minX() { return this.minX; }
	public int minY() { return this.minY; }
	public int minZ() { return this.minZ; }
	public int sizeX() { return this.sizeX; }
	public int sizeY() { return this.sizeY; }
	public int sizeZ() { return this.sizeZ; }

	public float elementX(int i) { return this.ex[i]; }
	public float elementY(int i) { return this.ey[i]; }
	public float elementZ(int i) { return this.ez[i]; }
	/** Cubic metres the element displaces when all of it is under water and dry. */
	public float elementVolume(int i) { return this.volume[i]; }
	/** How high the element stands, in blocks: the edge of the cube it is taken for (less than 1 for a slab or a carpet). */
	public float elementEdge(int i) { return this.edge[i]; }
	/**
	 * Whether the element is blocks on the outside of the vessel, with open water or air to a side of them: where a
	 * waterline shows. Not so for the air a hull keeps dry, nor for the blocks inside it or under it.
	 */
	public boolean elementIsOutside(int i) { return this.outside[i]; }
	/** Which way is out from an outside element, along the vessel's own x and z (length 1, or 0 when it is open all round). */
	public float elementOutX(int i) { return this.outX[i]; }
	public float elementOutZ(int i) { return this.outZ[i]; }
	/** Index of the pour point that floods the element, or {@link #SEALED} when it always displaces. */
	public int elementPour(int i) { return this.pour[i]; }

	public int pourCount() {
		return this.pours.length / 3;
	}

	public float pourX(int p) { return this.pours[p * 3]; }
	public float pourY(int p) { return this.pours[p * 3 + 1]; }
	public float pourZ(int p) { return this.pours[p * 3 + 2]; }

	/** Volume of the vessel's own blocks, m^3. */
	public double blockVolume() {
		return this.blockVolume;
	}

	/** Volume of the air below a rim (dry until the water runs over it), m^3. */
	public double shelteredVolume() {
		return this.shelteredVolume;
	}

	/** Volume of the air with no way out, m^3. */
	public double sealedVolume() {
		return this.sealedVolume;
	}

	/** The most water the vessel can displace: everything under water and nothing flooded, m^3. */
	public double capacity() {
		return this.blockVolume + this.shelteredVolume + this.sealedVolume;
	}

	/** Whether the flood was run; false for a hull whose bounds are too large, which then has no sheltered or sealed air. */
	public boolean hasCavities() {
		return this.flooded;
	}

	/** Class of the cell at a local block position; {@link #OPEN} outside the bounds. */
	public int cellClass(int x, int y, int z) {
		int lx = x - this.minX, ly = y - this.minY, lz = z - this.minZ;
		if (this.cells == null || lx < 0 || ly < 0 || lz < 0 || lx >= this.sizeX || ly >= this.sizeY || lz >= this.sizeZ) {
			return OPEN;
		}
		return this.cells[(ly * this.sizeZ + lz) * this.sizeX + lx];
	}

	/**
	 * Every cell the hull keeps dry, four ints each: its local block position (x, y, z) and its class ({@link #SEALED}
	 * or the pour point that floods it).
	 */
	public int[] dryCells() {
		int[] list = this.dryCells;
		if (list == null) {
			int n = 0;
			list = new int[64];
			for (int i = 0; this.cells != null && i < this.cells.length; i++) {
				int cls = this.cells[i];
				if (cls == OPEN || cls == WATERTIGHT) {
					continue;
				}
				if (n + 4 > list.length) {
					list = Arrays.copyOf(list, list.length * 2);
				}
				list[n++] = this.minX + i % this.sizeX;
				list[n++] = this.minY + i / (this.sizeX * this.sizeZ);
				list[n++] = this.minZ + i / this.sizeX % this.sizeZ;
				list[n++] = cls;
			}
			list = Arrays.copyOf(list, n);
			this.dryCells = list;
		}
		return list;
	}

	/** Built on first use; the same from whichever thread builds it. */
	private int[] dryCells;

	/**
	 * How far a sheltered cell is flooded, 0 to 1, from how much of its pour point's cell is under water (0 to 1):
	 * the water starts to run in as it reaches the rim and has filled the cell at {@link #FLOOD_DEPTH} above it.
	 */
	public static double flooding(double pourSubmerged) {
		return Math.min(1.0, pourSubmerged / FLOOD_DEPTH);
	}

	/**
	 * The class that decides whether a point of the vessel's frame is dry: that of its cell, or for a point in a
	 * watertight block (feet on a slab or a carpet) that of the cell above.
	 */
	public int shelterClass(double x, double y, double z) {
		int cx = (int)Math.floor(x), cy = (int)Math.floor(y), cz = (int)Math.floor(z);
		int c = this.cellClass(cx, cy, cz);
		if (c == WATERTIGHT) {
			c = this.cellClass(cx, cy + 1, cz);
		}
		return c;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Building
	// ---------------------------------------------------------------------------------------------------------

	/** Collects a vessel's blocks and builds its hull. */
	public static final class Builder {
		private final IntArrayList xs = new IntArrayList(), ys = new IntArrayList(), zs = new IntArrayList();
		private final it.unimi.dsi.fastutil.floats.FloatArrayList volumes = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		private final it.unimi.dsi.fastutil.booleans.BooleanArrayList watertight = new it.unimi.dsi.fastutil.booleans.BooleanArrayList();
		private final it.unimi.dsi.fastutil.floats.FloatArrayList bottoms = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		private final it.unimi.dsi.fastutil.floats.FloatArrayList tops = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		/** A block of the vessel that stands as high as its cell (a full cube, stairs, a fence post). */
		public void block(int x, int y, int z, float volume, boolean watertight) {
			this.block(x, y, z, volume, watertight, 0f, 1f);
		}

		/**
		 * A block of the vessel at a local position.
		 *
		 * @param volume the volume of its collision shape, m^3 (1 for a full cube)
		 * @param watertight whether it keeps water out
		 * @param bottom where its collision shape begins above the bottom of its cell, 0 to 1
		 * @param top where it ends (0.5 for a bottom slab): it is lifted by as much of that height as is under water
		 */
		public void block(int x, int y, int z, float volume, boolean watertight, float bottom, float top) {
			if (!(volume > 0)) {
				return;
			}
			float low = Math.max(0f, Math.min(1f, bottom));
			float high = Math.max(low + MIN_HEIGHT, Math.min(1f, top));
			this.xs.add(x);
			this.ys.add(y);
			this.zs.add(z);
			this.volumes.add(Math.min(1f, volume));
			this.watertight.add(watertight);
			this.bottoms.add(low);
			this.tops.add(high);
			this.minX = Math.min(this.minX, x);
			this.minY = Math.min(this.minY, y);
			this.minZ = Math.min(this.minZ, z);
			this.maxX = Math.max(this.maxX, x);
			this.maxY = Math.max(this.maxY, y);
			this.maxZ = Math.max(this.maxZ, z);
		}

		public boolean isEmpty() {
			return this.xs.isEmpty();
		}

		/** A number that changes whenever the blocks given so far do (order matters): to skip rebuilding the same hull. */
		public long fingerprint() {
			long h = 1125899906842597L;
			for (int i = 0; i < this.xs.size(); i++) {
				h = 31 * h + this.xs.getInt(i);
				h = 31 * h + this.ys.getInt(i);
				h = 31 * h + this.zs.getInt(i);
				h = 31 * h + Float.floatToIntBits(this.volumes.getFloat(i));
				h = 31 * h + (this.watertight.getBoolean(i) ? 1 : 0);
				h = 31 * h + Float.floatToIntBits(this.bottoms.getFloat(i));
				h = 31 * h + Float.floatToIntBits(this.tops.getFloat(i));
			}
			return h;
		}

		public Hull build() {
			return this.build(MAX_ELEMENTS, MAX_GRID_CELLS);
		}

		Hull build(int maxElements, int maxGridCells) {
			int n = this.xs.size();
			if (n == 0) {
				return EMPTY;
			}
			int sx = this.maxX - this.minX + 1, sy = this.maxY - this.minY + 1, sz = this.maxZ - this.minZ + 1;
			long gridCells = (long)(sx + 2) * (sy + 2) * (sz + 2);
			Elements out = new Elements();
			double blockVolume = 0;
			for (int i = 0; i < n; i++) {
				// The block itself: where its shape is in its cell, as high as its shape (the first n elements, in order).
				float bottom = this.bottoms.getFloat(i), top = this.tops.getFloat(i);
				out.add(this.xs.getInt(i) + 0.5f, this.ys.getInt(i) + (bottom + top) * 0.5f, this.zs.getInt(i) + 0.5f, this.volumes.getFloat(i), top - bottom, SEALED, true);
				blockVolume += this.volumes.getFloat(i);
			}
			if (gridCells > maxGridCells) {
				out.coarsen(maxElements, this.minX, this.minY, this.minZ);
				return new Hull(this.minX, this.minY, this.minZ, sx, sy, sz, null, out, new float[0], blockVolume, 0, 0, false);
			}

			// The grid: the bounds and one cell of open space around them, so the flood can start all around.
			int gx = sx + 2, gy = sy + 2, gz = sz + 2;
			int total = gx * gy * gz;
			float[] blockVol = new float[total];
			boolean[] barrier = new boolean[total];
			for (int i = 0; i < n; i++) {
				int index = ((this.ys.getInt(i) - this.minY + 1) * gz + this.zs.getInt(i) - this.minZ + 1) * gx + this.xs.getInt(i) - this.minX + 1;
				blockVol[index] = Math.min(1f, blockVol[index] + this.volumes.getFloat(i));
				barrier[index] |= this.watertight.getBoolean(i);
			}

			// Priority flood from the outside, lowest level first: level[c] is how high the water outside must stand
			// to run into c, pourCell[c] the cell on the way in where it gets that high.
			int[] level = new int[total];
			int[] pourCell = new int[total];
			Arrays.fill(level, Integer.MAX_VALUE);
			IntArrayFIFOQueue[] buckets = new IntArrayFIFOQueue[gy];
			for (int y = 0; y < gy; y++) {
				buckets[y] = new IntArrayFIFOQueue();
			}
			for (int y = 0; y < gy; y++) {
				for (int z = 0; z < gz; z++) {
					for (int x = 0; x < gx; x++) {
						if (x == 0 || y == 0 || z == 0 || x == gx - 1 || y == gy - 1 || z == gz - 1) {
							int index = (y * gz + z) * gx + x;
							level[index] = y;
							pourCell[index] = index;
							buckets[y].enqueue(index);
						}
					}
				}
			}
			int[] step = {1, -1, gx, -gx, gx * gz, -gx * gz};
			for (int l = 0; l < gy; l++) {
				IntArrayFIFOQueue queue = buckets[l];
				while (!queue.isEmpty()) {
					int c = queue.dequeueInt();
					int cx = c % gx, cz = c / gx % gz, cy = c / (gx * gz);
					for (int d = 0; d < 6; d++) {
						if (d == 0 && cx == gx - 1 || d == 1 && cx == 0 || d == 2 && cz == gz - 1 || d == 3 && cz == 0 || d == 4 && cy == gy - 1 || d == 5 && cy == 0) {
							continue;
						}
						int nIndex = c + step[d];
						if (barrier[nIndex] || level[nIndex] != Integer.MAX_VALUE) {
							continue;
						}
						int ny = d == 4 ? cy + 1 : d == 5 ? cy - 1 : cy;
						if (ny >= l) {
							level[nIndex] = ny;
							pourCell[nIndex] = nIndex;
							buckets[ny].enqueue(nIndex);
						} else {
							level[nIndex] = l;
							pourCell[nIndex] = pourCell[c];
							queue.enqueue(nIndex);
						}
					}
				}
			}

			// The blocks with open space to a side of them are the outside of the vessel, and that side is the way out.
			for (int i = 0; i < n; i++) {
				int x = this.xs.getInt(i) - this.minX + 1, y = this.ys.getInt(i) - this.minY + 1, z = this.zs.getInt(i) - this.minZ + 1;
				int index = (y * gz + z) * gx + x;
				boolean open = !barrier[index] && level[index] <= y;
				float wayX = 0, wayZ = 0;
				for (int d = 0; d < 4; d++) {
					int nIndex = index + step[d];
					if (!barrier[nIndex] && level[nIndex] <= y) {
						open = true;
						wayX += d == 0 ? 1 : d == 1 ? -1 : 0;
						wayZ += d == 2 ? 1 : d == 3 ? -1 : 0;
					}
				}
				float length = (float)Math.sqrt(wayX * wayX + wayZ * wayZ);
				out.outside.set(i, open);
				out.outX.set(i, length > 0 ? wayX / length : 0f);
				out.outZ.set(i, length > 0 ? wayZ / length : 0f);
			}

			// Classes and elements.
			int[] cells = new int[sx * sy * sz];
			Int2IntOpenHashMap pourIndex = new Int2IntOpenHashMap();
			pourIndex.defaultReturnValue(-1);
			it.unimi.dsi.fastutil.floats.FloatArrayList pours = new it.unimi.dsi.fastutil.floats.FloatArrayList();
			double sheltered = 0, sealed = 0;
			for (int y = 1; y <= sy; y++) {
				for (int z = 1; z <= sz; z++) {
					for (int x = 1; x <= sx; x++) {
						int index = (y * gz + z) * gx + x;
						float cx = this.minX + x - 1 + 0.5f, cy = this.minY + y - 1 + 0.5f, cz = this.minZ + z - 1 + 0.5f;
						int cls;
						float air;
						float own = blockVol[index];
						if (!barrier[index]) {
							// Air, or a block that water passes: its class is its own.
							air = 1f - own;
							cls = level[index] == Integer.MAX_VALUE ? SEALED : level[index] > y ? pourOf(pourCell[index], pourIndex, pours, gx, gz, this.minX, this.minY, this.minZ) : OPEN;
						} else if (own >= 1f) {
							air = 0f;
							cls = WATERTIGHT;
						} else {
							// A watertight block that does not fill its cell (a slab, a carpet, a chest): the rest of the cell
							// is as dry as the air around it, when all of that air is sheltered.
							air = 1f - own;
							int around = SEALED;
							int lowest = Integer.MAX_VALUE;
							boolean any = false;
							for (int d = 0; d < 6 && around != OPEN; d++) {
								int nIndex = index + step[d];
								if (barrier[nIndex]) {
									continue;
								}
								any = true;
								int ny = d == 4 ? y + 1 : d == 5 ? y - 1 : y;
								if (level[nIndex] == Integer.MAX_VALUE) {
									continue;
								}
								if (level[nIndex] <= ny) {
									around = OPEN;
								} else if (level[nIndex] < lowest) {
									lowest = level[nIndex];
									around = pourCell[nIndex];
								}
							}
							cls = !any || around == SEALED ? SEALED : around == OPEN ? OPEN : pourOf(around, pourIndex, pours, gx, gz, this.minX, this.minY, this.minZ);
						}
						if (air > 0f && cls != OPEN) {
							out.add(cx, cy, cz, air, 1f, cls, false);
							if (cls == SEALED) {
								sealed += air;
							} else {
								sheltered += air;
							}
						}
						cells[((y - 1) * sz + z - 1) * sx + x - 1] = barrier[index] && (own >= 1f || cls == OPEN) ? WATERTIGHT : cls;
					}
				}
			}
			out.coarsen(maxElements, this.minX, this.minY, this.minZ);
			return new Hull(this.minX, this.minY, this.minZ, sx, sy, sz, cells, out, pours.toFloatArray(), blockVolume, sheltered, sealed, true);
		}

		private static int pourOf(int gridIndex, Int2IntOpenHashMap pourIndex, it.unimi.dsi.fastutil.floats.FloatArrayList pours, int gx, int gz, int minX, int minY,
			int minZ) {
			int p = pourIndex.get(gridIndex);
			if (p < 0) {
				p = pours.size() / 3;
				pourIndex.put(gridIndex, p);
				pours.add(minX + gridIndex % gx - 1 + 0.5f);
				pours.add(minY + gridIndex / (gx * gz) - 1 + 0.5f);
				pours.add(minZ + gridIndex / gx % gz - 1 + 0.5f);
			}
			return p;
		}
	}

	/** Elements while they are collected, and their merging into coarser ones. */
	private static final class Elements {
		it.unimi.dsi.fastutil.floats.FloatArrayList x = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		it.unimi.dsi.fastutil.floats.FloatArrayList y = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		it.unimi.dsi.fastutil.floats.FloatArrayList z = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		it.unimi.dsi.fastutil.floats.FloatArrayList volume = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		it.unimi.dsi.fastutil.floats.FloatArrayList edge = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		IntArrayList pour = new IntArrayList();
		it.unimi.dsi.fastutil.booleans.BooleanArrayList outside = new it.unimi.dsi.fastutil.booleans.BooleanArrayList();
		it.unimi.dsi.fastutil.floats.FloatArrayList outX = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		it.unimi.dsi.fastutil.floats.FloatArrayList outZ = new it.unimi.dsi.fastutil.floats.FloatArrayList();

		void add(float ex, float ey, float ez, float v, float e, int p, boolean isOutside) {
			this.x.add(ex);
			this.y.add(ey);
			this.z.add(ez);
			this.volume.add(v);
			this.edge.add(e);
			this.pour.add(p);
			this.outside.add(isOutside);
			this.outX.add(0f);
			this.outZ.add(0f);
		}

		/**
		 * Merges elements cube by cube of 2, 4, 8, ... cells until there are at most {@code max}: the outside blocks of
		 * a cube into one element, all else of it that always displaces into another, its sheltered air into a third,
		 * which keeps the pour point of its first.
		 */
		void coarsen(int max, int minX, int minY, int minZ) {
			for (int k = 2; this.volume.size() > max && k <= 64; k *= 2) {
				Elements merged = new Elements();
				Long2IntOpenHashMap where = new Long2IntOpenHashMap();
				where.defaultReturnValue(-1);
				for (int i = 0; i < this.volume.size(); i++) {
					long cx = (long)Math.floor((this.x.getFloat(i) - minX) / k);
					long cy = (long)Math.floor((this.y.getFloat(i) - minY) / k);
					long cz = (long)Math.floor((this.z.getFloat(i) - minZ) / k);
					long key = (cx & 0xFFFFF) << 42 | (cy & 0xFFFFF) << 22 | (cz & 0xFFFFF) << 2 | (this.outside.getBoolean(i) ? 0 : this.pour.getInt(i) == SEALED ? 1 : 2);
					float v = this.volume.getFloat(i);
					int at = where.get(key);
					if (at < 0) {
						where.put(key, merged.volume.size());
						merged.add(this.x.getFloat(i) * v, this.y.getFloat(i) * v, this.z.getFloat(i) * v, v, k, this.pour.getInt(i), this.outside.getBoolean(i));
						merged.outX.set(merged.outX.size() - 1, this.outX.getFloat(i));
						merged.outZ.set(merged.outZ.size() - 1, this.outZ.getFloat(i));
					} else {
						merged.outX.set(at, merged.outX.getFloat(at) + this.outX.getFloat(i));
						merged.outZ.set(at, merged.outZ.getFloat(at) + this.outZ.getFloat(i));
						merged.x.set(at, merged.x.getFloat(at) + this.x.getFloat(i) * v);
						merged.y.set(at, merged.y.getFloat(at) + this.y.getFloat(i) * v);
						merged.z.set(at, merged.z.getFloat(at) + this.z.getFloat(i) * v);
						merged.volume.set(at, merged.volume.getFloat(at) + v);
					}
				}
				for (int i = 0; i < merged.volume.size(); i++) {
					float v = merged.volume.getFloat(i);
					merged.x.set(i, merged.x.getFloat(i) / v);
					merged.y.set(i, merged.y.getFloat(i) / v);
					merged.z.set(i, merged.z.getFloat(i) / v);
					float wayX = merged.outX.getFloat(i), wayZ = merged.outZ.getFloat(i);
					float length = (float)Math.sqrt(wayX * wayX + wayZ * wayZ);
					merged.outX.set(i, length > 1.0e-3f ? wayX / length : 0f);
					merged.outZ.set(i, length > 1.0e-3f ? wayZ / length : 0f);
				}
				// Start again from the single cells would be exact; merging the merged gives the same cubes, since k doubles.
				this.x = merged.x;
				this.y = merged.y;
				this.z = merged.z;
				this.volume = merged.volume;
				this.edge = merged.edge;
				this.pour = merged.pour;
				this.outside = merged.outside;
				this.outX = merged.outX;
				this.outZ = merged.outZ;
			}
		}
	}
}
