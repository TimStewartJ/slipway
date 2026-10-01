package dev.timstewart.slipway.client;

import dev.timstewart.slipway.client.mixin.AbstractSoundInstanceAccessor;
import dev.timstewart.slipway.client.mixin.ParticleAccessor;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselRegion;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Particles and sounds the client makes at a vessel block start at plot coordinates, millions of blocks from the
 * camera (a note above a note block, the green sparkle of bone meal on a crop, the chips and the thud of mining).
 * They are put where the block is: the position goes through the vessel's pose, and a particle's velocity is turned
 * with the vessel and gets the vessel's own movement at that point, so it starts at rest relative to the deck it
 * came from. From then on a particle lives in the world: it neither follows the vessel nor collides with it.
 */
public final class VesselEffects {
	/** Set while a particle is created whose position has already been moved to the world; its velocity has not. */
	@Nullable
	private static ClientVessel creatingFor;
	private static Vec3 creatingAt = Vec3.ZERO;

	private VesselEffects() {
	}

	/** The vessel whose plot holds a point, once the client can place things on it. */
	@Nullable
	public static ClientVessel vesselAt(double x, double y, double z) {
		if (!VesselRegion.isReserved(x, z)) {
			return null;
		}
		ClientVessel vessel = ClientVessels.atPlotPos(BlockPos.containing(x, y, z));
		return vessel != null && vessel.ready() ? vessel : null;
	}

	public static Vec3 toWorld(ClientVessel vessel, Vec3 plotPoint) {
		return vessel.plotToWorld(vessel.tickPose(), plotPoint);
	}

	/** Runs the creation of a particle for a plot point at that point's place in the world. */
	public static void creating(ClientVessel vessel, Vec3 plotPoint, Runnable create) {
		ClientVessel outerFor = creatingFor;
		Vec3 outerAt = creatingAt;
		creatingFor = vessel;
		creatingAt = plotPoint;
		try {
			create.run();
		} finally {
			creatingFor = outerFor;
			creatingAt = outerAt;
		}
	}

	/** Every particle passes here when it is added: one made at a plot point is moved to the vessel. */
	public static void place(Particle particle) {
		ParticleAccessor fields = (ParticleAccessor)particle;
		ClientVessel vessel = creatingFor;
		Vec3 plotPoint = creatingAt;
		if (vessel == null) {
			vessel = vesselAt(fields.slipway$x(), fields.slipway$y(), fields.slipway$z());
			if (vessel == null) {
				return;
			}
			plotPoint = new Vec3(fields.slipway$x(), fields.slipway$y(), fields.slipway$z());
			Vec3 world = toWorld(vessel, plotPoint);
			particle.setPos(world.x, world.y, world.z);
			fields.slipway$setXo(world.x);
			fields.slipway$setYo(world.y);
			fields.slipway$setZo(world.z);
		}
		VesselPose pose = vessel.tickPose();
		Vector3d velocity = pose.rotate(fields.slipway$xd(), fields.slipway$yd(), fields.slipway$zd(), new Vector3d());
		VesselPose before = vessel.previousTickPose();
		if (before != null) {
			// What the vessel moved at this point in the last tick: velocities of particles are per tick.
			Vec3 now = vessel.plotToWorld(pose, plotPoint);
			Vec3 then = vessel.plotToWorld(before, plotPoint);
			velocity.add(now.x - then.x, now.y - then.y, now.z - then.z);
		}
		particle.setParticleSpeed(velocity.x, velocity.y, velocity.z);
	}

	/** Every sound passes here when it is played: one positioned at a plot point is moved to the vessel. */
	public static void place(SoundInstance sound) {
		if (!(sound instanceof AbstractSoundInstanceAccessor fields) || sound.isRelative()) {
			return;
		}
		ClientVessel vessel = vesselAt(sound.getX(), sound.getY(), sound.getZ());
		if (vessel != null) {
			Vec3 world = toWorld(vessel, new Vec3(sound.getX(), sound.getY(), sound.getZ()));
			fields.slipway$setX(world.x);
			fields.slipway$setY(world.y);
			fields.slipway$setZ(world.z);
		}
	}
}