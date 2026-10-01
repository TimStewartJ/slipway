package dev.timstewart.slipway.client.mixin;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.VesselEffects;
import dev.timstewart.slipway.vessel.VesselLookup;
import dev.timstewart.slipway.vessel.VesselRegion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sounds the client plays itself at a vessel block (its own placing, breaking, door clicks) play where the block is,
 * and particles it makes there (a note, bone meal's sparkle, redstone dust at a lever) appear there; and when light
 * data for a plot section arrives or changes, the vessel mesh there is rebuilt (the terrain renderer, which would
 * normally be told, never sees plot sections).
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Shadow
	protected abstract void playSound(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay,
		long seed);

	@Shadow
	protected abstract void doAddParticle(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShowParticles, double x, double y, double z,
		double xd, double yd, double zd);

	/** Before the check that drops particles more than 32 blocks from the camera: that distance is to where the vessel is. */
	@Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
	private void slipway$particleAtVessel(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShowParticles, double x, double y, double z,
		double xd, double yd, double zd, CallbackInfo ci) {
		ClientVessel vessel = VesselEffects.vesselAt(x, y, z);
		if (vessel != null) {
			Vec3 plotPoint = new Vec3(x, y, z);
			Vec3 world = VesselEffects.toWorld(vessel, plotPoint);
			VesselEffects.creating(vessel, plotPoint, () -> this.doAddParticle(particle, overrideLimiter, alwaysShowParticles, world.x, world.y, world.z, xd, yd, zd));
			ci.cancel();
		}
	}

	@Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
	private void slipway$soundAtVessel(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay,
		long seed, CallbackInfo ci) {
		if (!VesselRegion.isReserved(x, z)) {
			return;
		}
		VesselLookup.View vessel = VesselLookup.at((ClientLevel)(Object)this, BlockPos.containing(x, y, z));
		if (vessel != null) {
			Vec3 world = vessel.plotToWorld(new Vec3(x, y, z));
			this.playSound(world.x, world.y, world.z, sound, source, volume, pitch, distanceDelay, seed);
			ci.cancel();
		}
	}

	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"), cancellable = true)
	private void slipway$plotSectionDirty(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
		if (VesselRegion.isReservedChunk(sectionX, sectionZ)) {
			ClientVessels.onPlotSectionChanged(sectionX, sectionY, sectionZ);
			ci.cancel();
		}
	}

	@Inject(method = "setSectionRangeDirty", at = @At("HEAD"), cancellable = true)
	private void slipway$plotSectionRangeDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
		boolean allReserved = true;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (VesselRegion.isReservedChunk(x, z)) {
					ClientVessels.onPlotChunkChanged(x, z);
				} else {
					allReserved = false;
				}
			}
		}
		if (allReserved) {
			ci.cancel();
		}
	}
}
