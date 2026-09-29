package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.timstewart.slipway.vessel.VesselLookup;
import dev.timstewart.slipway.vessel.VesselPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** A player's facing, as used to orient placed blocks, is measured in the vessel's frame when clicking a vessel. */
@Mixin(UseOnContext.class)
public abstract class UseOnContextMixin {
	@Shadow
	public abstract BlockPos getClickedPos();

	@Shadow
	public abstract Player getPlayer();

	@Shadow
	public abstract Level getLevel();

	@WrapMethod(method = "getHorizontalDirection")
	private Direction slipway$horizontalDirection(Operation<Direction> original) {
		Player player = this.getPlayer();
		VesselLookup.View vessel = player == null ? null : VesselLookup.at(this.getLevel(), this.getClickedPos());
		return vessel == null ? original.call() : VesselPlacement.inVesselFrame(player, vessel, original::call);
	}

	@WrapMethod(method = "getRotation")
	private float slipway$rotation(Operation<Float> original) {
		Player player = this.getPlayer();
		VesselLookup.View vessel = player == null ? null : VesselLookup.at(this.getLevel(), this.getClickedPos());
		return vessel == null ? original.call() : VesselPlacement.inVesselFrame(player, vessel, original::call);
	}
}
