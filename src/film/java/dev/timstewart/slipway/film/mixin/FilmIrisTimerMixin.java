package dev.timstewart.slipway.film.mixin;

import dev.timstewart.slipway.film.FilmClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris's frameTimeCounter (the shader clock that moves clouds and water) follows film time while a film frame renders,
 * so animation is smooth and deterministic at any render speed, and slows down with slow motion.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
abstract class FilmIrisTimerMixin {
	@Shadow(remap = false)
	private float frameTimeCounter;
	@Shadow(remap = false)
	private float lastFrameTime;

	@Inject(method = "beginFrame", at = @At("TAIL"), remap = false)
	private void slipwayFilm$filmTime(long frameStartTime, CallbackInfo ci) {
		if (FilmClock.frameActive) {
			this.frameTimeCounter = (float)(FilmClock.seconds % 3600.0);
			this.lastFrameTime = FilmClock.frameSeconds;
		}
	}
}
