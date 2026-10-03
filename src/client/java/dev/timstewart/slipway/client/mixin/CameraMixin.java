package dev.timstewart.slipway.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.timstewart.slipway.vessel.Shelter;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The view from inside a hull that keeps the water out is not a view from under water, although the world has water
 * blocks there (see {@link Shelter}): no water fog, and nothing else that asks the camera which fluid it is in (a
 * shader pack's "eye in water").
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private Level level;
	@Shadow
	private Vec3 position;

	@ModifyReturnValue(method = "getFluidInCamera", at = @At("RETURN"))
	private FogType slipway$dryInsideAHull(FogType fluid) {
		if (fluid != FogType.WATER && fluid != FogType.LAVA || this.level == null) {
			return fluid;
		}
		return Shelter.isDry(this.level, this.position.x, this.position.y, this.position.z) ? FogType.NONE : fluid;
	}
}
