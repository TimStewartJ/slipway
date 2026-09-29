package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRegion;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player who has been sent a vessel's plot chunks tracks them, although they lie far outside the player's view:
 * block, block-entity and light updates in those chunks are then broadcast to that player like any other change.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@ModifyReturnValue(method = "isChunkTracked", at = @At("RETURN"))
	private boolean slipway$trackVesselChunks(boolean tracked, ServerPlayer player, int chunkX, int chunkZ) {
		if (tracked || !VesselRegion.isReservedChunk(chunkX, chunkZ)) {
			return tracked;
		}
		VesselManager manager = VesselManager.getIfPresent(this.level);
		return manager != null && manager.isPlotChunkViewed(player, chunkX, chunkZ)
			&& !player.connection.chunkSender.isPending(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
	}
}
