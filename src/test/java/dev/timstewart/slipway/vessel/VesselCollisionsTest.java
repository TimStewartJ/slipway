package dev.timstewart.slipway.vessel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class VesselCollisionsTest {
	/** A 3x1x3 floor whose top is at y = 0, and a wall block standing on it east of the origin. */
	private static final VoxelShape FLOOR = Shapes.box(-1, -1, -1, 2, 0, 2);
	private static final VoxelShape WALL = Shapes.box(1, 0, 0, 2, 2, 1);

	private static Vector3d push(AABB box, VoxelShape... shapes) {
		return VesselCollisions.depenetration(box, List.of(shapes));
	}

	@Test
	void aBoxRestingOnTheFloorIsNotPushed() {
		Vector3d p = push(new AABB(0.2, 0.0, 0.2, 0.8, 1.8, 0.8), FLOOR);
		assertEquals(0.0, p.length(), 0.0);
	}

	@Test
	void aBoxSunkIntoTheFloorIsLiftedOut() {
		Vector3d p = push(new AABB(0.2, -0.2, 0.2, 0.8, 1.6, 0.8), FLOOR);
		assertEquals(0.2, p.y, 1e-12);
		assertEquals(0.0, p.x, 0.0);
		assertEquals(0.0, p.z, 0.0);
	}

	@Test
	void evenASubMicronSinkIsLiftedOut() {
		// Vanilla's sweep ignores faces a box overlaps by more than 1e-7, so such sinks must be corrected here.
		Vector3d p = push(new AABB(0.2, -2.7e-7, 0.2, 0.8, 1.8, 0.8), FLOOR);
		assertEquals(2.7e-7, p.y, 1e-15);
	}

	@Test
	void aBoxOverlappingAWallIsPushedOutSideways() {
		Vector3d p = push(new AABB(0.3, 0.0, 0.2, 1.1, 1.8, 0.8), FLOOR, WALL);
		assertEquals(-0.1, p.x, 1e-12);
		assertEquals(0.0, p.y, 0.0);
	}

	@Test
	void aLowStepOverlappedSidewaysIsNotClimbed() {
		VoxelShape slab = Shapes.box(1, 0, 0, 2, 0.5, 1);
		Vector3d p = push(new AABB(0.3, 0.0, 0.2, 1.05, 1.8, 0.8), FLOOR, slab);
		assertEquals(-0.05, p.x, 1e-12);
		assertEquals(0.0, p.y, 0.0);
	}

	@Test
	void deepOverlapsAreLeftAlone() {
		// Inside a solid block: neither up (1.0 > MAX_LIFT) nor sideways (0.8 > MAX_SIDE_PUSH) is a short way out.
		VoxelShape block = Shapes.box(-1, 0, -1, 2, 1, 2);
		Vector3d p = push(new AABB(0.1, 0.0, 0.1, 0.9, 1.8, 0.9), block);
		assertEquals(0.0, p.length(), 0.0);
	}
}
