package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.timstewart.slipway.vessel.VesselRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The plot columns round a vessel hold no block, and the sides of a vessel that lie on a chunk border are lit by
 * them. When the sky light of a chunk column is switched on, vanilla fills the light data that is already there for
 * it, and all zero, with full sky light: from the top down to the lowest section in which every block column is open
 * to the sky. Such data is there when the column next to it, with the vessel's blocks, was announced first, and in
 * which order chunks come from a save is not fixed. For a column without any block the lowest open block is "minus
 * infinity" ({@code Integer.MIN_VALUE}); vanilla subtracts one from it, the number overflows, the section comes out
 * far above the world and nothing is filled. The data beside the vessel stays zero, on the server and, sent from
 * there, on every client: the vessel's sides towards that column are black until the vessel is loaded again.
 *
 * <p>For plot columns the value is taken one higher, so that the subtraction gives minus infinity and every section
 * of the column is filled, which is what the vanilla code computes for any column that has a block. Columns outside
 * the plots are left as they are.
 */
@Mixin(SkyLightEngine.class)
public abstract class SkyLightEngineMixin {
	@ModifyExpressionValue(
		method = "setLightEnabled",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/ChunkSkyLightSources;getHighestLowestSourceY()I")
	)
	private int slipway$fillColumnsWithoutBlocks(int highestLowestSourceY, ChunkPos pos, boolean enable) {
		return highestLowestSourceY == ChunkSkyLightSources.NEGATIVE_INFINITY && VesselRegion.isReservedChunk(pos) ? highestLowestSourceY + 1 : highestLowestSourceY;
	}
}