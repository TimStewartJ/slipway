package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.timstewart.slipway.vessel.VesselRegion;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A piston on a vessel refuses a move that would put its head or a pushed block outside the usable part of the
 * vessel's plot, the way vanilla pistons refuse to push past the build height. Pistons are the one thing that moves
 * blocks by themselves (a slime-block flying machine keeps going), and a block that crossed the margin between plots
 * would end up in another vessel.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
	@Shadow
	@Final
	private boolean extending;

	@Shadow
	@Final
	private BlockPos startPos;

	@Shadow
	@Final
	private Direction pushDirection;

	@Shadow
	@Final
	private List<BlockPos> toPush;

	@ModifyReturnValue(method = "resolve", at = @At("RETURN"))
	private boolean slipway$stayInsideThePlot(boolean resolved) {
		if (!resolved || !VesselRegion.isReserved(this.startPos)) {
			return resolved;
		}
		if (this.extending && !VesselRegion.isUsable(this.startPos)) {
			return false;
		}
		for (BlockPos pos : this.toPush) {
			if (!VesselRegion.isUsable(pos.relative(this.pushDirection))) {
				return false;
			}
		}
		return true;
	}
}
