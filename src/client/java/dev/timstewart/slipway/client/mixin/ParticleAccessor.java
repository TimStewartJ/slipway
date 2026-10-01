package dev.timstewart.slipway.client.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a particle is, where it was a tick ago and how it moves: what is needed to move one from a plot to its vessel. */
@Mixin(Particle.class)
public interface ParticleAccessor {
	@Accessor("x")
	double slipway$x();

	@Accessor("y")
	double slipway$y();

	@Accessor("z")
	double slipway$z();

	@Accessor("xd")
	double slipway$xd();

	@Accessor("yd")
	double slipway$yd();

	@Accessor("zd")
	double slipway$zd();

	@Accessor("xo")
	void slipway$setXo(double xo);

	@Accessor("yo")
	void slipway$setYo(double yo);

	@Accessor("zo")
	void slipway$setZo(double zo);
}