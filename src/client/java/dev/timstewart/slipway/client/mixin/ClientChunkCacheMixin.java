package dev.timstewart.slipway.client.mixin;

import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselRegion;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vessel plot chunks lie millions of blocks from the player, outside the client's view-sized chunk storage. They
 * are kept in a separate map instead, so the level (block states, block entities, light) works for them, while
 * the terrain renderer (vanilla or Sodium), Fabric's chunk events and mods listening to them never see them:
 * vessels are drawn by their entity renderer.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Shadow
	@Final
	private LevelChunk emptyChunk;

	@Unique
	private final Long2ObjectOpenHashMap<LevelChunk> slipway$plotChunks = new Long2ObjectOpenHashMap<>();

	@Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
		at = @At("HEAD"), cancellable = true)
	private void slipway$getPlotChunk(int x, int z, ChunkStatus status, boolean loadOrGenerate, CallbackInfoReturnable<LevelChunk> cir) {
		if (VesselRegion.isReservedChunk(x, z)) {
			LevelChunk chunk = this.slipway$plotChunks.get(ChunkPos.pack(x, z));
			cir.setReturnValue(chunk != null ? chunk : loadOrGenerate ? this.emptyChunk : null);
		}
	}

	@Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
	private void slipway$storePlotChunk(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfoReturnable<LevelChunk> cir) {
		if (!VesselRegion.isReservedChunk(x, z)) {
			return;
		}
		long key = ChunkPos.pack(x, z);
		LevelChunk chunk = this.slipway$plotChunks.get(key);
		if (chunk == null) {
			chunk = new LevelChunk(this.level, new ChunkPos(x, z));
			this.slipway$plotChunks.put(key, chunk);
		}
		chunk.replaceWithPacketData(x, z, data);
		this.level.onChunkLoaded(new ChunkPos(x, z));
		ClientVessels.onPlotChunkChanged(x, z);
		cir.setReturnValue(chunk);
	}

	@Inject(method = "drop", at = @At("HEAD"), cancellable = true)
	private void slipway$dropPlotChunk(ChunkPos pos, CallbackInfo ci) {
		if (!VesselRegion.isReservedChunk(pos)) {
			return;
		}
		LevelChunk chunk = this.slipway$plotChunks.remove(pos.pack());
		if (chunk != null) {
			this.level.unload(chunk);
			ClientVessels.onPlotChunkChanged(pos.x(), pos.z());
		}
		ci.cancel();
	}

	@Inject(method = "replaceBiomes", at = @At("HEAD"), cancellable = true)
	private void slipway$plotBiomes(int x, int z, FriendlyByteBuf buffer, CallbackInfo ci) {
		if (VesselRegion.isReservedChunk(x, z)) {
			LevelChunk chunk = this.slipway$plotChunks.get(ChunkPos.pack(x, z));
			if (chunk != null) {
				chunk.replaceBiomes(buffer);
			}
			ci.cancel();
		}
	}

	@Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
	private void slipway$plotLight(LightLayer layer, SectionPos pos, CallbackInfo ci) {
		if (VesselRegion.isReservedChunk(pos.x(), pos.z())) {
			ClientVessels.onPlotSectionChanged(pos.x(), pos.y(), pos.z());
			ci.cancel();
		}
	}
}
