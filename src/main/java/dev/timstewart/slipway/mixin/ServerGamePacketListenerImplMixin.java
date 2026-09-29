package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.timstewart.slipway.vessel.VesselCollisions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player standing on a vessel deck has no world blocks under them, so the server would treat them as floating and
 * kick them for flying after four seconds, and every ten seconds log "standing on air" and resend the air blocks
 * below them. Vessel blocks count as blocks around the player, and the pointless resend is skipped on decks.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@ModifyReturnValue(method = "noBlocksAround", at = @At("RETURN"))
	private boolean slipway$vesselBlocksCount(boolean noBlocks, Entity entity) {
		return noBlocks && !(entity instanceof ServerPlayer serverPlayer && VesselCollisions.isNearVessel(serverPlayer));
	}

	@Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
	private void slipway$deckIsSupport(CallbackInfo ci) {
		if (VesselCollisions.isNearVessel(this.player)) {
			ci.cancel();
		}
	}
}
