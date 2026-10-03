package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.FluidCells;
import dev.timstewart.slipway.physics.FluidField;
import dev.timstewart.slipway.physics.Hull;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

/**
 * Whether a place in the world is kept dry by a vessel: it lies in air the vessel's {@link Hull} shelters or seals,
 * and the water has not run over the rim. A vessel's blocks are not in the world where the vessel is, so the world
 * still has its water there: someone standing in a floating hull below the waterline, or in the cabin of a
 * submerged one, stands in water blocks. The game asks here before it treats them as in the water (swimming, slowed,
 * drowning, pushed by a current) or draws the view from there as under water. The same on server and client, and
 * the same rule for where the water stands as the physics thread uses ({@link FluidField#submerged}).
 *
 * <p>A vessel under way is somewhere else every tick, and a place belongs to the pose it was reached with. An
 * entity this side moves itself is carried along once a tick (see {@link VesselCollisions#carry}), so it stands by
 * the previous pose until then and by the current one after: it is dry if either keeps it dry. A player on the
 * server stands where the player's client put it, which follows the vessel some ticks late (so does everything a
 * client is only told about): it is dry if any pose of the last half second keeps it dry. The camera of a frame is
 * asked with the pose that frame is drawn with. Without this, someone standing by the aft wall of a hull under way
 * would count as in the sea behind it. The price is the reverse: a swimmer right behind a moving hull, where its
 * hold was a moment ago, counts as dry for that moment.
 */
public final class Shelter {
	/** How far above an entity's feet the point lies that decides: clear of the block it stands on. */
	private static final double FEET = 0.1;

	private Shelter() {
	}

	/** How far around a place vessels are looked for whose earlier poses may have held it: what one covers in half a second. */
	private static final double REACH = 8.0;

	/** Whether a vessel keeps the entity out of the water it stands in. */
	public static boolean isDry(Entity entity) {
		double x = entity.getX(), y = entity.getY() + FEET, z = entity.getZ();
		Level level = entity.level();
		List<VesselLookup.View> near = VesselLookup.near(level, new AABB(x, y, z, x, y, z).inflate(REACH));
		if (near.isEmpty()) {
			return false;
		}
		return isDry(fluids(level), near, x, y, z, VesselCollisions.simulates(entity));
	}

	/** Whether a vessel keeps a place dry as the vessels are at this tick. */
	public static boolean isDry(Level level, double x, double y, double z) {
		List<VesselLookup.View> near = VesselLookup.near(level, new AABB(x, y, z, x, y, z).inflate(1.0e-3));
		return !near.isEmpty() && isDry(fluids(level), near, x, y, z);
	}

	/** Whether a vessel keeps a place dry as the vessels are drawn in a frame (for the camera). */
	public static boolean isDryInFrame(Level level, double x, double y, double z, float partialTick) {
		List<VesselLookup.View> near = VesselLookup.near(level, new AABB(x, y, z, x, y, z).inflate(REACH));
		if (near.isEmpty()) {
			return false;
		}
		FluidCells fluids = fluids(level);
		for (VesselLookup.View view : near) {
			if (isDry(fluids, view.hull(), view.framePose(partialTick), x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/** Whether one of the given vessels keeps a point dry at its current pose, with the fluids as {@code fluids} tells them. */
	public static boolean isDry(FluidCells fluids, Iterable<VesselLookup.View> vessels, double x, double y, double z) {
		for (VesselLookup.View view : vessels) {
			if (isDry(fluids, view.hull(), view.pose(), x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether one of the given vessels keeps dry the place of something that moves with it.
	 *
	 * @param carriedHere whether this side moves it (then it stands by the current or the previous pose), or it is
	 *        only told where it is (then by any of the vessel's recent poses)
	 */
	public static boolean isDry(FluidCells fluids, Iterable<VesselLookup.View> vessels, double x, double y, double z, boolean carriedHere) {
		for (VesselLookup.View view : vessels) {
			Hull hull = view.hull();
			if (hull.elementCount() == 0 || !hull.hasCavities()) {
				continue;
			}
			if (carriedHere) {
				VesselPose pose = view.pose(), previous = view.previousPose();
				if (isDry(fluids, hull, pose, x, y, z) || previous != null && !previous.equals(pose) && isDry(fluids, hull, previous, x, y, z)) {
					return true;
				}
			} else {
				for (VesselPose pose : view.recentPoses()) {
					if (isDry(fluids, hull, pose, x, y, z)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Whether a hull at a pose keeps a point dry. */
	public static boolean isDry(FluidCells fluids, Hull hull, VesselPose pose, double x, double y, double z) {
		if (pose == null || hull.elementCount() == 0 || !hull.hasCavities()) {
			return false;
		}
		Vector3d local = pose.worldToLocal(x, y, z, new Vector3d());
		int shelter = hull.shelterClass(local.x, local.y, local.z);
		return shelter == Hull.SEALED || shelter >= 0 && !flooded(fluids, hull, pose, shelter);
	}

	/** The level's fluids, block by block, in the form the physics thread keeps its copy of them. */
	public static FluidCells fluids(BlockGetter level) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return (x, y, z) -> {
			FluidState fluid = level.getFluidState(pos.set(x, y, z));
			return fluid.isEmpty() ? 0 : FluidField.encode(fluid.getAmount(), fluid.is(FluidTags.LAVA));
		};
	}

	/** Whether the water has run over a pour point far enough to count what it shelters as flooded. */
	public static boolean flooded(FluidCells fluids, Hull hull, VesselPose pose, int pour) {
		Vector3d rim = pose.localToWorld(hull.pourX(pour), hull.pourY(pour), hull.pourZ(pour), new Vector3d());
		return Hull.flooding(FluidField.submerged(fluids, rim.x, rim.y, rim.z, 1.0, null)) >= 0.5;
	}

	/**
	 * The height of the fluid's surface in the column of blocks through a point, looked for one block up and down
	 * from the point's own block; NaN when there is none there.
	 */
	public static double surfaceNear(FluidCells fluids, double x, double y, double z) {
		int cx = (int)Math.floor(x), cy = (int)Math.floor(y), cz = (int)Math.floor(z);
		for (int yy = cy + 1; yy >= cy - 1; yy--) {
			int cell = fluids.cell(cx, yy, cz);
			if (cell == 0) {
				continue;
			}
			int above = fluids.cell(cx, yy + 1, cz);
			if (above != 0 && (above & FluidField.LAVA_BIT) == (cell & FluidField.LAVA_BIT)) {
				// The surface is higher than we look.
				return Double.NaN;
			}
			return yy + (cell & FluidField.AMOUNT_MASK) / 9.0;
		}
		return Double.NaN;
	}
}
