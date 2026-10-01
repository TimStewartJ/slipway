package dev.timstewart.slipway.client.mixin;

import dev.timstewart.slipway.client.VesselEffects;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sounds the client plays directly at a block position (the thud of mining a block, a jukebox's music) are played
 * where the block is when it belongs to a vessel.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("HEAD"))
	private void slipway$playAtVessel(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		VesselEffects.place(instance);
	}

	@Inject(method = "playDelayed", at = @At("HEAD"))
	private void slipway$playDelayedAtVessel(SoundInstance instance, int delay, CallbackInfo ci) {
		VesselEffects.place(instance);
	}
}