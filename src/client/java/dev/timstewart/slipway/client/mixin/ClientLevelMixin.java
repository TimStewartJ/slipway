package dev.timstewart.slipway.client.mixin;

import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselLookup;
import dev.timstewart.slipway.vessel.VesselRegion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sounds the client plays itself at a vessel block (its own placing, breaking, door clicks) play where the block is;
 * and when light data for a plot section arrives or changes, the vessel mesh there is rebuilt (the terrain renderer,
 * which would normally be told, never sees plot sections).
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Shadow
	protected abstract void playSound(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay,
		long seed);

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
