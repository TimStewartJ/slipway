package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.timstewart.slipway.vessel.VesselLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Chests, barrels and ender chests count the players who have them open, and every five ticks look for those players
 * in a box around the block. On a vessel the block is in its plot and the players are where the vessel is, so the
 * count fell back to zero and the lid shut a quarter of a second after it opened. The box is put where the container
 * is in the world.
 */
@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterMixin {
	@ModifyArg(method = "getEntitiesWithContainerOpen", index = 1, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
	private AABB slipway$searchWhereTheVesselIs(AABB box, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
		VesselLookup.View vessel = VesselLookup.at(level, pos);
		if (vessel == null) {
			return box;
		}
		// The same reach around the container's place in the world, and a block more because the block may be turned.
		return AABB.ofSize(vessel.plotToWorld(Vec3.atCenterOf(pos)), box.getXsize() + 1.0, box.getYsize() + 1.0, box.getZsize() + 1.0);
	}
}
