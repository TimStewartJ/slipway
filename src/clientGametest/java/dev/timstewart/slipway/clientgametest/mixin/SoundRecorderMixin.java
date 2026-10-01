package dev.timstewart.slipway.clientgametest.mixin;

import dev.timstewart.slipway.clientgametest.Effects;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test only: tells the tests which sounds the client played and where. */
@Mixin(SoundManager.class)
public abstract class SoundRecorderMixin {
	@Inject(method = "play", at = @At("RETURN"))
	private void slipwayTest$record(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		Effects.sound(instance);
	}
}