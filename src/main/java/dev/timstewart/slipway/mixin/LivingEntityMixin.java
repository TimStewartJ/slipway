package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.timstewart.slipway.vessel.VesselClimbing;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ladders, vines and other climbable blocks work on vessels: when the block of the world at an entity's feet is not
 * climbable, the block of a vessel there is asked (see {@link VesselClimbing}).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Shadow
	private Optional<BlockPos> lastClimbablePos;

	@Shadow
	private boolean trapdoorUsableAsLadder(BlockPos pos, BlockState state) {
		throw new AssertionError();
	}

	@ModifyReturnValue(method = "onClimbable", at = @At("RETURN"))
	private boolean slipway$climbOnVessels(boolean climbing) {
		LivingEntity self = (LivingEntity)(Object)this;
		if (climbing || self.isSpectator()) {
			return climbing;
		}
		BlockPos pos = VesselClimbing.climbableAt(self, this::trapdoorUsableAsLadder);
		if (pos == null) {
			return false;
		}
		this.lastClimbablePos = Optional.of(pos);
		return true;
	}
}
