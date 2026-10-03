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
 */
public final class Shelter {
	/** How far above an entity's feet the point lies that decides: clear of the block it stands on. */
	private static final double FEET = 0.1;

	private Shelter() {
	}

	public static boolean isDry(Entity entity) {
		return isDry(entity.level(), entity.getX(), entity.getY() + FEET, entity.getZ());
	}

	public static boolean isDry(Level level, double x, double y, double z) {
		List<VesselLookup.View> near = VesselLookup.near(level, new AABB(x, y, z, x, y, z).inflate(1.0e-3));
		return !near.isEmpty() && isDry(fluids(level), near, x, y, z);
	}

	/** Whether one of the given vessels keeps a point dry, with the fluids as {@code fluids} tells them. */
	public static boolean isDry(FluidCells fluids, Iterable<VesselLookup.View> vessels, double x, double y, double z) {
		for (VesselLookup.View view : vessels) {
			Hull hull = view.hull();
			VesselPose pose = view.pose();
			if (pose == null || hull.elementCount() == 0 || !hull.hasCavities()) {
				continue;
			}
			Vector3d local = pose.worldToLocal(x, y, z, new Vector3d());
			int shelter = hull.shelterClass(local.x, local.y, local.z);
			if (shelter == Hull.SEALED || shelter >= 0 && !flooded(fluids, hull, pose, shelter)) {
				return true;
			}
		}
		return false;
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
