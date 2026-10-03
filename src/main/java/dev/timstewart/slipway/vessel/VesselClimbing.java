package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.math.VesselPose;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Ladders, vines and the other climbable blocks of a vessel. The game asks which block an entity's feet are in to
 * decide whether it climbs; a vessel's blocks are not in the world where the vessel is, so the feet are taken into
 * each nearby vessel's plot and the block there is asked the same question.
 *
 * <p>As with {@link Shelter}, a place belongs to the pose it was reached with. An entity this side moves itself is
 * carried along once a tick, so it stands by the previous pose before that and by the current one after; anything
 * else (a player on the server) stands where it was put some ticks ago, by one of the vessel's recent poses.
 */
public final class VesselClimbing {
	/** How far above the feet the point lies that decides: on a deck the feet are on the block below, not in it. */
	private static final double FEET = 0.01;
	/** How far around an entity vessels are looked for whose earlier poses may have held it. */
	private static final double REACH = 8.0;

	private VesselClimbing() {
	}

	/**
	 * The plot position of the climbable vessel block the entity's feet are in, or null.
	 *
	 * @param trapdoorLadder vanilla's rule for an open trapdoor above a ladder, asked with a plot position
	 */
	@Nullable
	public static BlockPos climbableAt(LivingEntity entity, BiPredicate<BlockPos, BlockState> trapdoorLadder) {
		Level level = entity.level();
		boolean carriedHere = VesselCollisions.simulates(entity);
		List<VesselLookup.View> near = VesselLookup.near(level, entity.getBoundingBox().inflate(carriedHere ? 2.0 : REACH));
		if (near.isEmpty()) {
			return null;
		}
		double x = entity.getX(), y = entity.getY() + FEET, z = entity.getZ();
		Vector3d local = new Vector3d();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (VesselLookup.View view : near) {
			List<VesselPose> poses;
			if (carriedHere) {
				VesselPose pose = view.pose(), previous = view.previousPose();
				poses = previous == null || previous.equals(pose) ? List.of(pose) : List.of(pose, previous);
			} else {
				poses = view.recentPoses();
			}
			BlockPos anchor = view.anchor();
			for (VesselPose pose : poses) {
				pose.worldToLocal(x, y, z, local);
				pos.set(anchor.getX() + (int)Math.floor(local.x), anchor.getY() + (int)Math.floor(local.y), anchor.getZ() + (int)Math.floor(local.z));
				if (!VesselRegion.isReserved(pos)) {
					continue;
				}
				BlockState state = level.getBlockState(pos);
				if (state.isAir() || entity.isFallFlying() && state.is(BlockTags.CAN_GLIDE_THROUGH)) {
					continue;
				}
				if (state.is(BlockTags.CLIMBABLE) || state.getBlock() instanceof TrapDoorBlock && trapdoorLadder.test(pos, state)) {
					return pos.immutable();
				}
			}
		}
		return null;
	}
}
