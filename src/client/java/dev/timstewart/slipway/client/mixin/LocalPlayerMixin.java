package dev.timstewart.slipway.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.timstewart.slipway.client.VesselPicking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The crosshair can pick blocks of vessels (see {@link VesselPicking}). */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@ModifyReturnValue(method = "raycastHitResult", at = @At("RETURN"))
	private HitResult slipway$pickVesselBlocks(HitResult original, float partialTicks, Entity camera) {
		return VesselPicking.pick(camera, partialTicks, ((LocalPlayer)(Object)this).blockInteractionRange(), original);
	}
}
