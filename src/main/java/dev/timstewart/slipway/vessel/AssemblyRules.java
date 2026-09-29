package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks may become part of a vessel. Everything that is not air qualifies except: blocks in the
 * {@code slipway:assembly_deny} tag (bedrock, portals, command and structure blocks, moving pistons, ...),
 * blocks listed in the config's {@code extraDeniedBlocks}, fluid blocks (water and lava sources and flows are
 * never assembled, so a ship built on the sea does not take the sea with it; waterlogged blocks keep their
 * waterlogged state), and other helms.
 */
public final class AssemblyRules {
	private final Set<Block> extraDenied = new HashSet<>();

	public AssemblyRules(SlipwayConfig config) {
		for (String id : config.extraDeniedBlocks) {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier != null) {
				BuiltInRegistries.BLOCK.getOptional(identifier).ifPresent(this.extraDenied::add);
			}
		}
	}

	public boolean accepts(BlockState state, boolean isOriginHelm) {
		if (state.isAir()) {
			return false;
		}
		if (state.getBlock() instanceof LiquidBlock) {
			return false;
		}
		if (state.is(SlipwayRegistry.HELM)) {
			return isOriginHelm;
		}
		if (state.is(SlipwayRegistry.ASSEMBLY_DENY) || this.extraDenied.contains(state.getBlock())) {
			return false;
		}
		return true;
	}

	/** A level-backed grid for {@link StructureScan}; never loads chunks. */
	public StructureScan.Grid grid(ServerLevel level, net.minecraft.core.BlockPos origin) {
		return (x, y, z) -> {
			if (level.isOutsideBuildHeight(y)) {
				return StructureScan.Cell.REJECT;
			}
			if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
				return StructureScan.Cell.UNLOADED;
			}
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
			if (VesselRegion.isReserved(pos)) {
				return StructureScan.Cell.REJECT;
			}
			boolean origin2 = pos.equals(origin);
			return this.accepts(level.getBlockState(pos), origin2) ? StructureScan.Cell.ACCEPT : StructureScan.Cell.REJECT;
		};
	}
}
