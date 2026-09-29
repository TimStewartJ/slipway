package dev.timstewart.slipway.client;

import dev.timstewart.slipway.client.render.VesselMesh;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.vessel.VesselRegion;
import java.util.ArrayDeque;
import java.util.Iterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Client-side state of one vessel: how its plot maps to the world, the server's recent poses, the smoothly
 * interpolated pose for this client tick, and its render mesh.
 *
 * <p>Interpolation: server poses arrive every tick stamped with the server's game time. The client plays them
 * back {@value #DELAY_TICKS} ticks late, interpolating between the two snapshots around the playback time
 * (position lerp, rotation slerp), so network jitter does not show. Rendering then interpolates between the
 * previous and current client-tick poses with the frame's partial tick, exactly as entities do.
 */
public final class ClientVessel {
	static final double DELAY_TICKS = 2.0;
	private static final int MAX_SNAPSHOTS = 40;

	public final long id;
	public int entityId = -1;
	public BlockPos anchor = BlockPos.ZERO;
	public BlockPos localMin = BlockPos.ZERO;
	public BlockPos localMax = BlockPos.ZERO;
	public BlockPos helm = BlockPos.ZERO;
	public Direction helmFacing = Direction.NORTH;
	public int blocks;
	public float mass;
	public boolean hover;
	public boolean level;
	public boolean hasBody;
	public boolean hasInfo;
	public Vec3 velocity = Vec3.ZERO;
	public Vec3 angularVelocity = Vec3.ZERO;
	public final VesselMesh mesh = new VesselMesh(this);

	private final ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();
	private double playbackTick = Double.NaN;
	@Nullable
	private VesselPose previousTickPose;
	@Nullable
	private VesselPose tickPose;

	private record Snapshot(long tick, VesselPose pose) {
	}

	ClientVessel(long id) {
		this.id = id;
	}

	void applyInfo(SlipwayPayloads.VesselInfo info) {
		boolean boundsChanged = !info.anchor().equals(this.anchor) || !info.localMin().equals(this.localMin) || !info.localMax().equals(this.localMax);
		this.entityId = info.entityId();
		this.anchor = info.anchor();
		this.localMin = info.localMin();
		this.localMax = info.localMax();
		this.helm = info.helm();
		this.helmFacing = info.helmFacing();
		this.blocks = info.blocks();
		this.mass = info.mass();
		this.hasInfo = true;
		if (boundsChanged) {
			this.mesh.markAllDirty();
		}
	}

	void applyPose(SlipwayPayloads.PoseUpdate update) {
		if (!update.pose().isFinite()) {
			return;
		}
		VesselPose pose = update.pose().toPose();
		this.entityId = update.entityId();
		this.velocity = update.velocity();
		this.angularVelocity = update.angularVelocity();
		this.hover = (update.flags() & 1) != 0;
		this.level = (update.flags() & 2) != 0;
		this.hasBody = (update.flags() & 4) != 0;
		Snapshot last = this.snapshots.peekLast();
		if (last != null && update.gameTime() <= last.tick) {
			if (update.gameTime() == last.tick) {
				this.snapshots.pollLast();
			} else {
				return;
			}
		}
		this.snapshots.addLast(new Snapshot(update.gameTime(), pose));
		while (this.snapshots.size() > MAX_SNAPSHOTS) {
			this.snapshots.pollFirst();
		}
		if (this.tickPose == null) {
			this.tickPose = pose;
			this.previousTickPose = pose;
			this.playbackTick = update.gameTime() - DELAY_TICKS;
		}
	}

	/** Advances playback by one client tick. */
	void tick() {
		if (this.snapshots.isEmpty() || this.tickPose == null) {
			return;
		}
		long latest = this.snapshots.peekLast().tick;
		this.playbackTick += 1.0;
		// Stay DELAY_TICKS behind the newest snapshot: catch up after stalls, slow down when packets run late.
		if (this.playbackTick > latest || this.playbackTick < latest - DELAY_TICKS - 4) {
			this.playbackTick = latest - DELAY_TICKS;
		}
		this.previousTickPose = this.tickPose;
		this.tickPose = this.sample(this.playbackTick);
		// Drop snapshots that playback has passed, keeping one before it.
		while (this.snapshots.size() > 2) {
			Iterator<Snapshot> it = this.snapshots.iterator();
			it.next();
			if (it.next().tick <= this.playbackTick) {
				this.snapshots.pollFirst();
			} else {
				break;
			}
		}
	}

	private VesselPose sample(double tick) {
		Snapshot before = null;
		Snapshot after = null;
		for (Snapshot s : this.snapshots) {
			if (s.tick <= tick) {
				before = s;
			} else {
				after = s;
				break;
			}
		}
		if (before == null) {
			return this.snapshots.peekFirst().pose;
		}
		if (after == null) {
			return before.pose;
		}
		double t = (tick - before.tick) / (double)(after.tick - before.tick);
		return before.pose.interpolate(after.pose, t);
	}

	/** Pose of the current client tick (for collision, carrying and riding). */
	@Nullable
	public VesselPose tickPose() {
		return this.tickPose;
	}

	@Nullable
	public VesselPose previousTickPose() {
		return this.previousTickPose;
	}

	/** Pose for a frame between the previous and the current client tick. */
	@Nullable
	public VesselPose renderPose(float partialTick) {
		if (this.tickPose == null || this.previousTickPose == null) {
			return this.tickPose;
		}
		return this.previousTickPose.interpolate(this.tickPose, partialTick);
	}

	public boolean ready() {
		return this.hasInfo && this.tickPose != null;
	}

	public int plot() {
		return VesselRegion.plotAt(this.anchor.getX(), this.anchor.getZ());
	}

	public boolean containsPlotPos(BlockPos plotPos) {
		return this.hasInfo && VesselRegion.plotAt(plotPos.getX(), plotPos.getZ()) == this.plot();
	}

	public Vec3 localCenter() {
		return new Vec3((this.localMin.getX() + this.localMax.getX() + 1) / 2.0, (this.localMin.getY() + this.localMax.getY() + 1) / 2.0,
			(this.localMin.getZ() + this.localMax.getZ() + 1) / 2.0);
	}

	/** World-space box around the vessel at a pose. */
	public AABB worldBounds(VesselPose pose) {
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		Vector3d c = new Vector3d();
		for (int i = 0; i < 8; i++) {
			pose.localToWorld((i & 1) == 0 ? this.localMin.getX() : this.localMax.getX() + 1, (i & 2) == 0 ? this.localMin.getY() : this.localMax.getY() + 1,
				(i & 4) == 0 ? this.localMin.getZ() : this.localMax.getZ() + 1, c);
			minX = Math.min(minX, c.x); minY = Math.min(minY, c.y); minZ = Math.min(minZ, c.z);
			maxX = Math.max(maxX, c.x); maxY = Math.max(maxY, c.y); maxZ = Math.max(maxZ, c.z);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	public Vec3 worldCentre(VesselPose pose) {
		Vec3 local = this.localCenter();
		Vector3d w = pose.localToWorld(local.x, local.y, local.z, new Vector3d());
		return new Vec3(w.x, w.y, w.z);
	}

	/** Maps a plot-space point into world space at a pose. */
	public Vec3 plotToWorld(VesselPose pose, Vec3 plot) {
		Vector3d w = pose.localToWorld(plot.x - this.anchor.getX(), plot.y - this.anchor.getY(), plot.z - this.anchor.getZ(), new Vector3d());
		return new Vec3(w.x, w.y, w.z);
	}

	/** Maps a world-space point into plot space at a pose. */
	public Vec3 worldToPlot(VesselPose pose, Vec3 world) {
		Vector3d l = pose.worldToLocal(world.x, world.y, world.z, new Vector3d());
		return new Vec3(l.x + this.anchor.getX(), l.y + this.anchor.getY(), l.z + this.anchor.getZ());
	}

	void close() {
		this.mesh.clear();
	}

	private final dev.timstewart.slipway.vessel.VesselLookup.View view = new dev.timstewart.slipway.vessel.VesselLookup.View() {
		@Override
		public long id() {
			return ClientVessel.this.id;
		}

		@Override
		public BlockPos anchor() {
			return ClientVessel.this.anchor;
		}

		@Override
		public VesselPose pose() {
			return ClientVessel.this.tickPose;
		}

		@Override
		public VesselPose previousPose() {
			return ClientVessel.this.previousTickPose == null ? ClientVessel.this.tickPose : ClientVessel.this.previousTickPose;
		}

		@Override
		public AABB worldBounds() {
			return ClientVessel.this.worldBounds(ClientVessel.this.tickPose);
		}

		@Override
		public Vec3 velocity() {
			return ClientVessel.this.velocity;
		}
	};

	/** This vessel for side-independent collision and interaction code, at the current client-tick pose. */
	public dev.timstewart.slipway.vessel.VesselLookup.View view() {
		return this.view;
	}
}
