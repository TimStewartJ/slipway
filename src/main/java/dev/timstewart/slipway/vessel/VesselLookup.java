package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.Hull;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Side-independent access to vessels for code that runs on both logical sides (collision, reach checks,
 * placement orientation): the server answers from its {@link VesselManager}, the client from the state the
 * client entrypoint installs.
 */
public final class VesselLookup {
	/** A vessel as collision and interaction code sees it on one side. */
	public interface View {
		long id();

		BlockPos anchor();

		/** Pose at the current tick. */
		VesselPose pose();

		/** Pose at the previous tick (for carrying entities). */
		VesselPose previousPose();

		/** World-space bounds at the current pose. */
		AABB worldBounds();

		/** Linear velocity in blocks per second. */
		Vec3 velocity();

		/** What the vessel keeps the water out of (see {@link Shelter}); empty while it is not known. */
		default Hull hull() {
			return Hull.EMPTY;
		}

		/**
		 * The poses of the last few ticks, newest first and beginning with {@link #pose()}: something whose position
		 * this side does not move itself (a player on the server, anyone else on a client) stands where one of these
		 * put it, and which one is not known.
		 */
		default List<VesselPose> recentPoses() {
			VesselPose pose = this.pose(), previous = this.previousPose();
			return previous == null || previous.equals(pose) ? List.of(pose) : List.of(pose, previous);
		}

		/** The pose a frame is drawn with, between the previous tick and this one (the same as {@link #pose()} on a server). */
		default VesselPose framePose(float partialTick) {
			return this.pose();
		}

		default Vec3 plotToWorld(Vec3 plot) {
			Vector3d w = this.pose().localToWorld(plot.x - this.anchor().getX(), plot.y - this.anchor().getY(), plot.z - this.anchor().getZ(), new Vector3d());
			return new Vec3(w.x, w.y, w.z);
		}

		default Vec3 worldToPlot(Vec3 world) {
			Vector3d l = this.pose().worldToLocal(world.x, world.y, world.z, new Vector3d());
			return new Vec3(l.x + this.anchor().getX(), l.y + this.anchor().getY(), l.z + this.anchor().getZ());
		}
	}

	public interface ClientSource {
		@Nullable
		View atPlotPos(BlockPos plotPos);

		void collectNear(AABB box, List<View> out);
	}

	@Nullable
	public static volatile ClientSource client;

	private VesselLookup() {
	}

	/** The vessel owning a plot position on the side of {@code level}, or null. */
	@Nullable
	public static View at(Level level, BlockPos plotPos) {
		if (!VesselRegion.isReserved(plotPos)) {
			return null;
		}
		if (level instanceof ServerLevel serverLevel) {
			VesselManager manager = VesselManager.getIfPresent(serverLevel);
			ActiveVessel vessel = manager == null ? null : manager.activeAt(plotPos);
			return vessel == null ? null : vessel.view();
		}
		ClientSource source = client;
		return source == null ? null : source.atPlotPos(plotPos);
	}

	/** Vessels whose bounds intersect a world box on the side of {@code level}. */
	public static List<View> near(Level level, AABB box) {
		List<View> out = new ArrayList<>(2);
		if (level instanceof ServerLevel serverLevel) {
			VesselManager manager = VesselManager.getIfPresent(serverLevel);
			if (manager != null) {
				for (ActiveVessel vessel : manager.activeVessels()) {
					if (vessel.hasBody || vessel.chunksReady) {
						View view = vessel.view();
						if (view.worldBounds().intersects(box)) {
							out.add(view);
						}
					}
				}
			}
		} else {
			ClientSource source = client;
			if (source != null) {
				source.collectNear(box, out);
			}
		}
		return out;
	}
}
