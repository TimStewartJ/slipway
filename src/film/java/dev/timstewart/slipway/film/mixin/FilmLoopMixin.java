package dev.timstewart.slipway.film.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.timstewart.slipway.film.FilmClock;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While the film records, the game's own render loop draws nothing: only film frames reach the renderer, so temporal
 * effects (the shader's TAA history, auto exposure) see exactly the sequence that is saved.
 */
@Mixin(Minecraft.class)
abstract class FilmLoopMixin {
	@WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
	private boolean slipwayFilm$update(GameRenderer renderer, DeltaTracker time) {
		return !FilmClock.holdLoop;
	}

	@WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private boolean slipwayFilm$extract(GameRenderer renderer, DeltaTracker time, boolean advance) {
		return !FilmClock.holdLoop;
	}

	@WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
	private boolean slipwayFilm$render(GameRenderer renderer) {
		return !FilmClock.holdLoop;
	}
}
