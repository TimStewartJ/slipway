package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.timstewart.slipway.vessel.VesselPlacement;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A block item is not placed where its vessel may not grow to: in the margin of the vessel's plot, or so far out that
 * the vessel would span more than the configured largest size. The placement fails the way vanilla's does above the
 * build height, for a player and for a dispenser, and the item is kept. The position is the one the item settles on
 * (scaffolding moves it outwards), so the check sits behind {@code updatePlacementContext}.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@ModifyExpressionValue(
		method = "place",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/BlockItem;updatePlacementContext(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/item/context/BlockPlaceContext;"
		)
	)
	@Nullable
	private BlockPlaceContext slipway$notWhereTheVesselMayNotGrow(@Nullable BlockPlaceContext context) {
		return context == null || VesselPlacement.mayPlace(context) ? context : null;
	}
}