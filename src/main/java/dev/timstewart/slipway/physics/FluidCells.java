package dev.timstewart.slipway.physics;

/**
 * Fluid by block, as {@link FluidField} keeps it: the byte of the block at a position, 0 without fluid, otherwise
 * the fluid's amount (1 to 8) and {@link FluidField#LAVA_BIT} for lava. The physics thread reads its own copy
 * ({@link FluidField}); everything else reads the level through one of these (see {@code Shelter}).
 */
@FunctionalInterface
public interface FluidCells {
	int cell(int x, int y, int z);
}
