package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.physics.Buoyancy;
import dev.timstewart.slipway.physics.FluidField;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * What is seen and heard of a vessel in water: a splash when it goes in, and spray along its waterline while it
 * moves. The places come from the physics step (just outside the faces of outside blocks the surface cuts, see
 * {@link Buoyancy.State}), so the spray is where the hull meets the water whatever the hull's shape, and not in its hold. Sent as ordinary particle and sound packets.
 */
final class WaterEffects {
	/** Speed (blocks per second) a vessel must have when it goes in for a splash to be made. */
	private static final double SPLASH_SPEED = 1.5;
	/** Speed along the water from which a moving vessel throws spray. */
	private static final double SPRAY_SPEED = 2.0;

	private WaterEffects() {
	}

	static void tick(ServerLevel level, ActiveVessel vessel) {
		Buoyancy.State water = vessel.buoyancy;
		boolean in = vessel.hasBody && (water.displacedVolume > 0 || water.floodedVolume > 0);
		boolean wasIn = vessel.wasInFluid;
		vessel.wasInFluid = in;
		if (!in || water.waterlineCount == 0 || !vessel.bodyAwake) {
			return;
		}
		Vec3 velocity = vessel.record.linearVelocity;
		boolean lava = water.fluid == FluidField.LAVA;
		SimpleParticleType particle = lava ? ParticleTypes.LAVA : ParticleTypes.SPLASH;
		if (!wasIn) {
			double speed = velocity.length();
			if (speed < SPLASH_SPEED) {
				return;
			}
			Vec3 centre = VesselManager.worldCentre(vessel.record);
			float volume = (float)Math.min(2.0, 0.3 + speed / 8.0);
			// Larger hulls sound deeper.
			float pitch = (float)Math.max(0.5, 1.1 - Math.log10(Math.max(1.0, vessel.record.blockCount)) * 0.2);
			level.playSound(null, centre.x, water.waterline[1], centre.z, lava ? SoundEvents.LAVA_POP : speed > 8.0 ? SoundEvents.PLAYER_SPLASH_HIGH_SPEED
				: SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, volume, pitch);
			int each = (int)Math.min(12, 3 + speed);
			for (int i = 0; i < water.waterlineCount; i++) {
				level.sendParticles(particle, water.waterline[i * 3], water.waterline[i * 3 + 1] + 0.1, water.waterline[i * 3 + 2], each, 0.3, 0.1, 0.3, 0.2);
			}
			return;
		}
		double along = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		if (along >= SPRAY_SPEED && !lava && (level.getGameTime() & 1) == 0) {
			int each = along > 6.0 ? 2 : 1;
			for (int i = 0; i < water.waterlineCount; i++) {
				level.sendParticles(particle, water.waterline[i * 3], water.waterline[i * 3 + 1] + 0.05, water.waterline[i * 3 + 2], each, 0.2, 0.02, 0.2, 0.0);
			}
		}
	}
}
