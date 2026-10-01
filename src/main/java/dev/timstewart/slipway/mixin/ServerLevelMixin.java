package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselLookup;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
 * Things that happen at a vessel block happen where the block is now. Entities spawned in a plot (drops of a broken
 * block, experience, items thrown out of a container, falling blocks) are moved to the vessel's world position with
 * their velocity turned by its rotation, and sounds are played there.
 *
 * <p>Block events (a chest's lid, a piston's move, a note block, a bell) and level events (block breaking, doors,
 * bone meal, dispenser smoke) are what vanilla sends to the players within 64 blocks of the block. For a vessel
 * block that is nobody, the plot being millions of blocks away, so they are sent to the players who view the vessel
 * and are within 64 blocks of where the block is in the world. They keep the plot position: the client has the
 * plot's blocks there (a chest to open, a piston to move, a crop whose shape places the particles) and puts the
 * sounds and particles they cause where the vessel is.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Shadow
	public abstract void playSeededSound(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
		float volume, float pitch, long seed);

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
	private void slipway$levelEventToViewers(@Nullable Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
		if (slipway$sendToViewers(pos.getX(), pos.getY(), pos.getZ(), 64.0, source instanceof Player player ? player : null,
			new ClientboundLevelEventPacket(type, pos, data, false))) {
			ci.cancel();
		}
	}

	@WrapOperation(method = "runBlockEvents", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/players/PlayerList;broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V"))
	private void slipway$blockEventToViewers(PlayerList players, @Nullable Player except, double x, double y, double z, double range, ResourceKey<Level> dimension,
		Packet<?> packet, Operation<Void> broadcast) {
		if (!slipway$sendToViewers(x, y, z, range, except, packet)) {
			broadcast.call(players, except, x, y, z, range, dimension, packet);
		}
	}

	/** Sends a packet about the block at a plot position to its vessel's viewers near that block; false when it is not a vessel block. */
	private boolean slipway$sendToViewers(double x, double y, double z, double range, @Nullable Player except, Packet<?> packet) {
		if (!VesselRegion.isReserved(x, z)) {
			return false;
		}
		VesselManager manager = VesselManager.getIfPresent((ServerLevel)(Object)this);
		ActiveVessel vessel = manager == null ? null : manager.activeAt(BlockPos.containing(x, y, z));
		if (vessel == null) {
			return false;
		}
		manager.sendToViewersNear(vessel, new Vec3(x + 0.5, y + 0.5, z + 0.5), range, except, packet);
		return true;
	}
}
