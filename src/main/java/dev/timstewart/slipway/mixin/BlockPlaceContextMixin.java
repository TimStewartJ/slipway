package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.timstewart.slipway.vessel.VesselLookup;
import dev.timstewart.slipway.vessel.VesselPlacement;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;

/** Look-direction queries used by block placement are answered in the vessel's frame when placing on a vessel. */
@Mixin(BlockPlaceContext.class)
public abstract class BlockPlaceContextMixin {
	@WrapMethod(method = "getNearestLookingDirection")
	private Direction slipway$nearestLookingDirection(Operation<Direction> original) {
		BlockPlaceContext self = (BlockPlaceContext)(Object)this;
		Player player = self.getPlayer();
		VesselLookup.View vessel = player == null ? null : VesselLookup.at(self.getLevel(), self.getClickedPos());
		return vessel == null ? original.call() : VesselPlacement.inVesselFrame(player, vessel, original::call);
	}

	@WrapMethod(method = "getNearestLookingVerticalDirection")
	private Direction slipway$nearestLookingVerticalDirection(Operation<Direction> original) {
		BlockPlaceContext self = (BlockPlaceContext)(Object)this;
		Player player = self.getPlayer();
		VesselLookup.View vessel = player == null ? null : VesselLookup.at(self.getLevel(), self.getClickedPos());
		return vessel == null ? original.call() : VesselPlacement.inVesselFrame(player, vessel, original::call);
	}

	@WrapMethod(method = "getNearestLookingDirections")
	private Direction[] slipway$nearestLookingDirections(Operation<Direction[]> original) {
		BlockPlaceContext self = (BlockPlaceContext)(Object)this;
		Player player = self.getPlayer();
		VesselLookup.View vessel = player == null ? null : VesselLookup.at(self.getLevel(), self.getClickedPos());
		return vessel == null ? original.call() : VesselPlacement.inVesselFrame(player, vessel, original::call);
	}
}
