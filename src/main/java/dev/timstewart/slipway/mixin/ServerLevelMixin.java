package dev.timstewart.slipway.mixin;

import dev.timstewart.slipway.vessel.VesselLookup;
import dev.timstewart.slipway.vessel.VesselRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Things that happen at a vessel block happen where the block is now: entities spawned in a plot (drops of a broken
 * block, experience, items thrown out of a container, falling blocks) are moved to the vessel's world position with
 * their velocity turned by its rotation, and sounds and level events (block breaking, doors) are played there.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Shadow
	public abstract void playSeededSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
		float volume, float pitch, long seed);

	@Shadow
	public abstract void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data);

	@Inject(method = "addFreshEntity", at = @At("HEAD"))
	private void slipway$spawnInWorld(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!VesselRegion.isReserved(entity.getX(), entity.getZ())) {
			return;
		}
		VesselLookup.View vessel = VesselLookup.at((ServerLevel)(Object)this, entity.blockPosition());
		if (vessel == null) {
			return;
		}
		Vec3 world = vessel.plotToWorld(entity.position());
		Vec3 velocity = entity.getDeltaMovement();
		Vector3d turned = vessel.pose().rotate(velocity.x, velocity.y, velocity.z, new Vector3d());
		entity.setPos(world);
		entity.setDeltaMovement(turned.x, turned.y, turned.z);
		entity.setYRot(entity.getYRot() - (float)vessel.pose().yawTwistDegrees());
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
		at = @At("HEAD"), cancellable = true)
	private void slipway$soundInWorld(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume,
		float pitch, long seed, CallbackInfo ci) {
		if (!VesselRegion.isReserved(x, z)) {
			return;
		}
		VesselLookup.View vessel = VesselLookup.at((ServerLevel)(Object)this, BlockPos.containing(x, y, z));
		if (vessel != null) {
			Vec3 world = vessel.plotToWorld(new Vec3(x, y, z));
			this.playSeededSound(except, world.x, world.y, world.z, sound, source, volume, pitch, seed);
			ci.cancel();
		}
	}

	@Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
	private void slipway$levelEventInWorld(@Nullable Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
		if (!VesselRegion.isReserved(pos)) {
			return;
		}
		VesselLookup.View vessel = VesselLookup.at((ServerLevel)(Object)this, pos);
		if (vessel != null) {
			this.levelEvent(source, type, BlockPos.containing(vessel.plotToWorld(Vec3.atCenterOf(pos))), data);
			ci.cancel();
		}
	}
}
