package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.timstewart.slipway.vessel.VesselLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every reach check for blocks (using, breaking, containers, menus, sign editing) goes through
 * {@code isWithinBlockInteractionRange}. For a block on a vessel the player's eye is moved into the vessel's plot
 * space, so the check measures the real distance to where the block is now.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@ModifyExpressionValue(method = "isWithinBlockInteractionRange",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getEyePosition()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 slipway$eyeInVesselSpace(Vec3 eye, BlockPos pos, double buffer) {
		VesselLookup.View vessel = VesselLookup.at(((Player)(Object)this).level(), pos);
		return vessel == null ? eye : vessel.worldToPlot(eye);
	}
}
