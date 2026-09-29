package dev.timstewart.slipway.physics;

/**
 * Greedy merging of a solid voxel grid into few axis-aligned boxes: take the first unvisited solid cell, grow a
 * run along X, grow the run into a rectangle along Z, grow the rectangle into a box along Y, emit it, repeat.
 * This is the standard greedy-meshing idea applied to volumes; it is not optimal but runs in time linear in the
 * grid size and typically turns a hull of hundreds of blocks into tens of boxes.
 */
public final class GreedyBoxes {
	private GreedyBoxes() {
	}

	/**
	 * @param solid grid indexed {@code (y * sizeZ + z) * sizeX + x}; cleared cells are consumed
	 * @param material same indexing; only cells with equal material merge (use it for density classes)
	 * @param out receives boxes in grid coordinates offset by (ox, oy, oz)
	 */
	public static void merge(boolean[] solid, int[] material, float[] densityByMaterial, int sizeX, int sizeY, int sizeZ,
		float ox, float oy, float oz, BoxList out) {
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					int start = (y * sizeZ + z) * sizeX + x;
					if (!solid[start]) {
						continue;
					}
					int m = material[start];
					int x2 = x + 1;
					while (x2 < sizeX && solid[(y * sizeZ + z) * sizeX + x2] && material[(y * sizeZ + z) * sizeX + x2] == m) {
						x2++;
					}
					int z2 = z + 1;
					grow:
					while (z2 < sizeZ) {
						for (int xi = x; xi < x2; xi++) {
							int idx = (y * sizeZ + z2) * sizeX + xi;
							if (!solid[idx] || material[idx] != m) {
								break grow;
							}
						}
						z2++;
					}
					int y2 = y + 1;
					growY:
					while (y2 < sizeY) {
						for (int zi = z; zi < z2; zi++) {
							for (int xi = x; xi < x2; xi++) {
								int idx = (y2 * sizeZ + zi) * sizeX + xi;
								if (!solid[idx] || material[idx] != m) {
									break growY;
								}
							}
						}
						y2++;
					}
					for (int yi = y; yi < y2; yi++) {
						for (int zi = z; zi < z2; zi++) {
							for (int xi = x; xi < x2; xi++) {
								solid[(yi * sizeZ + zi) * sizeX + xi] = false;
							}
						}
					}
					out.add(ox + x, oy + y, oz + z, ox + x2, oy + y2, oz + z2, densityByMaterial[m]);
				}
			}
		}
	}
}
