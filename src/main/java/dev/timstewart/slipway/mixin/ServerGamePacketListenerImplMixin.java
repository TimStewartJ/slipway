package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.timstewart.slipway.vessel.DeckFall;
import dev.timstewart.slipway.vessel.VesselCollisions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player standing on a vessel deck has no world blocks under them, so the server would treat them as floating and
 * kick them for flying after four seconds, and every ten seconds log "standing on air" and resend the air blocks
 * below them. Vessel blocks count as blocks around the player, and the pointless resend is skipped on decks.
 *
 * <p>The server adds up how far a player falls from the moves the client reports, in the world. On a vessel that
 * is the player's own movement and the vessel's: for a player on a vessel the fall is counted against the deck (see
 * {@link DeckFall}).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Unique
	private final DeckFall slipway$deckFall = new DeckFall();

	@WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;doCheckFallDamage(DDDZ)V"))
	private void slipway$fallAgainstTheDeck(ServerPlayer mover, double dx, double dy, double dz, boolean onGround, Operation<Void> original) {
		original.call(mover, dx, this.slipway$deckFall.against(mover, dy), dz, onGround);
	}

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
