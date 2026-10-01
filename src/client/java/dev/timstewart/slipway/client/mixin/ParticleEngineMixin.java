package dev.timstewart.slipway.client.mixin;

import dev.timstewart.slipway.client.VesselEffects;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every particle is added here, also the ones made without the level's distance check (the chips of a block being
 * mined or broken): one made at a vessel block's plot position is moved to where the block is.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
	@Inject(method = "add", at = @At("HEAD"))
	private void slipway$particleAtVessel(Particle particle, CallbackInfo ci) {
		VesselEffects.place(particle);
	}
}