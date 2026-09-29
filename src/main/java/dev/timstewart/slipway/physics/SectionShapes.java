package dev.timstewart.slipway.physics;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Turns the blocks of one 16x16x16 chunk section into collision boxes: full-cube blocks are greedily merged per
 * density class, other shapes (slabs, stairs, fences, ...) contribute the boxes of their collision
 * {@link VoxelShape}. Reuses its scratch arrays, so one instance serves one thread.
 */
public final class SectionShapes {
	private final boolean[] solid = new boolean[4096];
	private final int[] material = new int[4096];
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	/**
	 * Adds the section's boxes to {@code out}, positioned relative to the block position (ox, oy, oz).
	 *
	 * @return the number of non-air blocks seen
	 */
	public int build(Level level, LevelChunk chunk, int sectionY, int ox, int oy, int oz, BoxList out) {
		int index = chunk.getSectionIndexFromSectionY(sectionY);
		if (index < 0 || index >= chunk.getSectionsCount()) {
			return 0;
		}
		LevelChunkSection section = chunk.getSection(index);
		if (section.hasOnlyAir()) {
			return 0;
		}
		Arrays.fill(this.solid, false);
		int baseX = chunk.getPos().getMinBlockX();
		int baseY = sectionY << 4;
		int baseZ = chunk.getPos().getMinBlockZ();
		float dx = baseX - ox, dy = baseY - oy, dz = baseZ - oz;
		int blocks = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, y, z);
					if (state.isAir()) {
						continue;
					}
					blocks++;
					this.cursor.set(baseX + x, baseY + y, baseZ + z);
					if (state.isCollisionShapeFullBlock(level, this.cursor)) {
						int i = (y * 16 + z) * 16 + x;
						this.solid[i] = true;
						this.material[i] = BlockDensity.classOf(state);
						continue;
					}
					VoxelShape shape = state.getCollisionShape(level, this.cursor);
					if (shape.isEmpty()) {
						continue;
					}
					float density = BlockDensity.densityOf(state);
					for (AABB box : shape.toAabbs()) {
						out.add((float)(dx + x + box.minX), (float)(dy + y + box.minY), (float)(dz + z + box.minZ),
							(float)(dx + x + box.maxX), (float)(dy + y + box.maxY), (float)(dz + z + box.maxZ), density);
					}
				}
			}
		}
		GreedyBoxes.merge(this.solid, this.material, BlockDensity.DENSITIES, 16, 16, 16, dx, dy, dz, out);
		return blocks;
	}
}
