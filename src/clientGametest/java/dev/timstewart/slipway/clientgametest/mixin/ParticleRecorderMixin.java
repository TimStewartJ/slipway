package dev.timstewart.slipway.clientgametest.mixin;

import dev.timstewart.slipway.clientgametest.Effects;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test only: tells the tests which particles the client added and where they ended up. */
@Mixin(ParticleEngine.class)
public abstract class ParticleRecorderMixin {
	@Inject(method = "add", at = @At("RETURN"))
	private void slipwayTest$record(Particle particle, CallbackInfo ci) {
		Effects.particle(particle);
	}
}