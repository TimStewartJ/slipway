package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselRegion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the client really adds to its particle engine and plays through its sound manager (recorded by the test mod's
 * mixins after Slipway's own hooks have run), with the place of each in the frame of one vessel at that moment.
 * Client thread only.
 */
public final class Effects {
	/** A particle (its class name) or a sound (its id): where in the world, and where on the watched vessel. */
	public record Seen(String what, Vec3 world, Vec3 local) {
		boolean inPlot() {
			return VesselRegion.isReserved(this.world.x, this.world.z);
		}

		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT, "%s at local %.2f, %.2f, %.2f", this.what, this.local.x, this.local.y, this.local.z);
		}
	}

	private static long vessel = -1;
	private static final List<Seen> PARTICLES = new ArrayList<>();
	private static final List<Seen> SOUNDS = new ArrayList<>();

	private Effects() {
	}

	static void start(long vesselId) {
		PARTICLES.clear();
		SOUNDS.clear();
		vessel = vesselId;
	}

	static void stop() {
		vessel = -1;
	}

	static List<Seen> particles() {
		return List.copyOf(PARTICLES);
	}

	static List<Seen> sounds() {
		return List.copyOf(SOUNDS);
	}

	private static Seen seen(String what, double x, double y, double z) {
		ClientVessel watched = ClientVessels.get(vessel);
		Vec3 world = new Vec3(x, y, z);
		return new Seen(what, world, watched != null && watched.ready() ? watched.tickPose().worldToLocal(world) : world);
	}

	public static void particle(Particle particle) {
		if (vessel >= 0 && PARTICLES.size() < 10_000) {
			AABB box = particle.getBoundingBox();
			PARTICLES.add(seen(particle.getClass().getSimpleName(), (box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0));
		}
	}

	public static void sound(SoundInstance sound) {
		if (vessel >= 0 && SOUNDS.size() < 10_000 && !sound.isRelative()) {
			SOUNDS.add(seen(sound.getIdentifier().toString(), sound.getX(), sound.getY(), sound.getZ()));
		}
	}
}