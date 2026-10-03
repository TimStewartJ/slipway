package dev.timstewart.slipway.physics;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Turns the blocks of one 16x16x16 chunk section into collision boxes: full-cube blocks are greedily merged per
 * density class, other shapes (slabs, stairs, fences, ...) contribute the boxes of their collision
 * {@link VoxelShape}. Reuses its scratch arrays, so one instance serves one thread.
 */
public final class SectionShapes {
	/** Blocks with a collision shape that water passes all the same (see data/slipway/tags/block/not_watertight.json). */
	public static final TagKey<Block> NOT_WATERTIGHT = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("slipway", "not_watertight"));

	private final boolean[] solid = new boolean[4096];
	private final int[] material = new int[4096];
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	/**
	 * Adds the section's boxes to {@code out}, positioned relative to the block position (ox, oy, oz).
	 *
	 * @return the number of non-air blocks seen
	 */
	public int build(Level level, LevelChunk chunk, int sectionY, int ox, int oy, int oz, BoxList out) {
		return this.build(level, chunk, sectionY, ox, oy, oz, out, null);
	}

	/**
	 * As {@link #build(Level, LevelChunk, int, int, int, int, BoxList)}, and tells {@code hull} every block with a
	 * collision shape: its position relative to (ox, oy, oz), the volume of its shape, and whether it keeps water out
	 * (every such block does, except those in the {@code slipway:not_watertight} tag: fences, bars, ladders).
	 */
	public int build(Level level, LevelChunk chunk, int sectionY, int ox, int oy, int oz, BoxList out, Hull.@Nullable Builder hull) {
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
						if (hull != null) {
							hull.block(baseX - ox + x, baseY - oy + y, baseZ - oz + z, 1f, true);
						}
						continue;
					}
					VoxelShape shape = state.getCollisionShape(level, this.cursor);
					if (shape.isEmpty()) {
						continue;
					}
					// A block a piston is moving keeps its own weight while it moves (the moving-piston block has none).
					float density = BlockDensity.densityOf(state.is(Blocks.MOVING_PISTON) && level.getBlockEntity(this.cursor) instanceof PistonMovingBlockEntity moving
						? moving.getMovedState() : state);
					float volume = 0f;
					for (AABB box : shape.toAabbs()) {
						volume += (float)((box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ));
						out.add((float)(dx + x + box.minX), (float)(dy + y + box.minY), (float)(dz + z + box.minZ),
							(float)(dx + x + box.maxX), (float)(dy + y + box.maxY), (float)(dz + z + box.maxZ), density);
					}
					if (hull != null) {
						hull.block(baseX - ox + x, baseY - oy + y, baseZ - oz + z, volume, !state.is(NOT_WATERTIGHT), (float)shape.min(Direction.Axis.Y),
							(float)shape.max(Direction.Axis.Y));
					}
				}
			}
		}
		GreedyBoxes.merge(this.solid, this.material, BlockDensity.DENSITIES, 16, 16, 16, dx, dy, dz, out);
		return blocks;
	}

	/**
	 * The fluid in a chunk section as {@link FluidField} keeps it: a byte for each of its 4096 blocks, or null when it
	 * holds none. Waterlogged blocks count as the water they hold.
	 */
	public static byte @Nullable [] fluids(LevelChunk chunk, int sectionY) {
		int index = chunk.getSectionIndexFromSectionY(sectionY);
		if (index < 0 || index >= chunk.getSectionsCount()) {
			return null;
		}
		LevelChunkSection section = chunk.getSection(index);
		if (section.hasOnlyAir() || !section.hasFluid()) {
			return null;
		}
		byte[] cells = null;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					FluidState fluid = section.getFluidState(x, y, z);
					if (fluid.isEmpty()) {
						continue;
					}
					if (cells == null) {
						cells = new byte[4096];
					}
					cells[FluidField.index(x, y, z)] = FluidField.encode(fluid.getAmount(), fluid.is(FluidTags.LAVA));
				}
			}
		}
		return cells;
	}
}
