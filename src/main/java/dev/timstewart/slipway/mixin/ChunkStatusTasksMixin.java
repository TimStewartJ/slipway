package dev.timstewart.slipway.mixin;

import dev.timstewart.slipway.vessel.VesselRegion;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunks in the reserved vessel region generate empty: no structures, terrain, carvers, features or mob spawns,
 * and every biome is {@code minecraft:the_void} (no rain or snow falls on vessel blocks). Light and full-status
 * steps run normally.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
	@Inject(method = "generateStructureStarts", at = @At("HEAD"), cancellable = true)
	private static void slipway$emptyStructureStarts(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (VesselRegion.isReservedChunk(chunk.getPos())) {
			context.level().onStructureStartsAvailable(chunk);
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}

	@Inject(method = "generateStructureReferences", at = @At("HEAD"), cancellable = true)
	private static void slipway$emptyStructureReferences(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (VesselRegion.isReservedChunk(chunk.getPos())) {
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}

	@Inject(method = "generateBiomes", at = @At("HEAD"), cancellable = true)
	private static void slipway$voidBiomes(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (VesselRegion.isReservedChunk(chunk.getPos())) {
			Holder<Biome> voidBiome = context.level().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
			chunk.fillBiomesFromNoise((x, y, z) -> voidBiome);
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}

	@Inject(method = "buildTerrain", at = @At("HEAD"), cancellable = true)
	private static void slipway$emptyTerrain(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (VesselRegion.isReservedChunk(chunk.getPos())) {
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}

	@Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
	private static void slipway$noFeatures(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (VesselRegion.isReservedChunk(chunk.getPos())) {
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}

	@Inject(method = "generateSpawn", at = @At("HEAD"), cancellable = true)
	private static void slipway$noSpawns(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
		ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		if (VesselRegion.isReservedChunk(chunk.getPos())) {
			cir.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}
}
