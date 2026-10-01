package dev.timstewart.slipway.client.mixin;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The position of a sound, to move one made at a plot position to its vessel. */
@Mixin(AbstractSoundInstance.class)
public interface AbstractSoundInstanceAccessor {
	@Accessor("x")
	void slipway$setX(double x);

	@Accessor("y")
	void slipway$setY(double y);

	@Accessor("z")
	void slipway$setZ(double z);
}