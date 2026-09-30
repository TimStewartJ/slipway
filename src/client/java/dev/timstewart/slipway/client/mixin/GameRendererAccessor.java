package dev.timstewart.slipway.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's decision whether to draw the hovered block's outline (not with the HUD hidden, not where adventure mode
 * forbids interaction, not when a mod turned outlines off), so vessel blocks follow the same rule as world blocks.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("shouldRenderBlockOutline")
	boolean slipway$shouldRenderBlockOutline();
}
