package dev.timstewart.slipway.client.mixin;

import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.Shelter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game lets specks drift in water and lets flowing water murmur, block by block around the player. The world
 * has water blocks in the air a hull keeps dry (see {@link Shelter}), so the specks would drift through a boat's hold
 * and a submarine's cabin: not there.
 */
@Mixin(WaterFluid.class)
public abstract class WaterFluidMixin {
	@Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
	private void slipway$notInAirAHullKeepsDry(Level level, BlockPos pos, FluidState fluidState, RandomSource random, CallbackInfo ci) {
		if (level.isClientSide() && !ClientVessels.all().isEmpty() && Shelter.isDry(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
			ci.cancel();
		}
	}
}
