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
 * (position lerp, rotation slerp), so network jitter does not show. The playback clock follows the newest snapshot
 * like a jitter buffer: it runs up to {@value #MAX_RATE_CHANGE} faster or slower to hold the delay, holds the last
 * pose if data runs out, and only jumps when it is more than {@value #SNAP_TICKS} ticks off (joining, a teleport,
 * a long stall). Rendering then interpolates between the previous and current client-tick poses with the frame's
 * partial tick, exactly as entities do.
 */
public final class ClientVessel {
	public static final double DELAY_TICKS = 2.0;
	/** Largest fraction by which the playback clock runs fast or slow to return to the target delay. */
	static final double MAX_RATE_CHANGE = 0.1;
	/** Playback further off the target delay than this jumps to it. */
	static final double SNAP_TICKS = 10.0;
	private static final int MAX_SNAPSHOTS = 40;
	/** How many of the newest snapshots count among the poses something aboard may stand by (see {@code Shelter}). */
	private static final int RECENT_SNAPSHOTS = 6;

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
	public boolean loose;
	public boolean hasBody;
	/** The vessel lies in water or lava, as the server's last step found it. */
	public boolean inFluid;
	/** Water is running over a rim of the hull, as the server's last step found it. */
	public boolean flooding;
	/** Hover is on but the vessel has not the lift for its weight (survival rules). */
	public boolean noLift;
	/** What the vessel has to move and lift itself with, as the server works it out. */
	public SlipwayPayloads.RigInfo rig = SlipwayPayloads.RigInfo.FREE;
	public boolean hasInfo;
	public Vec3 velocity = Vec3.ZERO;
	public Vec3 angularVelocity = Vec3.ZERO;
	public final VesselMesh mesh = new VesselMesh(this);
	/**
	 * What the vessel keeps the water out of, built from the plot's blocks as the server builds its own: for
	 * entities and the camera inside the hull (see {@code Shelter}), the water mask and the pilot's display.
	 */
	public dev.timstewart.slipway.physics.Hull hull = dev.timstewart.slipway.physics.Hull.EMPTY;
	private long hullFingerprint;
	/** The plot's blocks changed (or arrived) since {@link #hull} was built. */
	boolean hullDirty = true;
	public final dev.timstewart.slipway.client.render.WaterMask waterMask = new dev.timstewart.slipway.client.render.WaterMask(this);
	/** One per thread that builds hulls; this is the client thread's. */
	private static final dev.timstewart.slipway.physics.SectionShapes SHAPES = new dev.timstewart.slipway.physics.SectionShapes();
	/**
	 * Set when the vessel is gone on the server but still drawn (see {@link ClientVessels#onGone}): the client tick
	 * it went at, and its block entities as they were, kept because its plot chunks are dropped.
	 */
	long goneAtTick = -1;
	/** The last client tick at which the terrain renderer still had work waiting (from {@link #goneAtTick} on). */
	long terrainBusyAtTick;
	public java.util.List<net.minecraft.world.level.block.entity.BlockEntity> keptBlockEntities = java.util.List.of();
	/** The light each of {@link #keptBlockEntities} was drawn with when the vessel went: the plot's light goes with its chunks. */
	public int[] keptBlockEntityLight = new int[0];

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
		this.rig = info.rig();
		this.hasInfo = true;
		this.hullDirty = true;
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
		this.hover = (update.flags() & SlipwayPayloads.PoseUpdate.FLAG_HOVER) != 0;
		this.level = (update.flags() & SlipwayPayloads.PoseUpdate.FLAG_LEVEL) != 0;
		this.hasBody = (update.flags() & SlipwayPayloads.PoseUpdate.FLAG_BODY) != 0;
		this.loose = (update.flags() & SlipwayPayloads.PoseUpdate.FLAG_LOOSE) != 0;
		this.inFluid = (update.flags() & SlipwayPayloads.PoseUpdate.FLAG_IN_FLUID) != 0;
		this.flooding = (update.flags() & SlipwayPayloads.PoseUpdate.FLAG_FLOODING) != 0;
		this.noLift = (update.flags() & SlipwayPayloads.PoseUpdate.FLAG_NO_LIFT) != 0;
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
		this.playbackTick = nextPlaybackTick(this.playbackTick, this.snapshots.peekLast().tick);
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

	/**
	 * After {@link #tick()}: builds the hull anew when the plot's blocks changed (at most once a tick, and only once
	 * all of the plot's chunks are here), and picks the cells the water mask covers until the next tick.
	 */
	void tickWater(net.minecraft.client.multiplayer.ClientLevel level) {
		if (this.gone() || !this.ready()) {
			return;
		}
		if (this.hullDirty) {
			this.rebuildHull(level);
		}
		this.waterMask.prepare(level);
	}

	private void rebuildHull(net.minecraft.client.multiplayer.ClientLevel level) {
		dev.timstewart.slipway.physics.Hull.Builder builder = new dev.timstewart.slipway.physics.Hull.Builder();
		dev.timstewart.slipway.physics.BoxList unused = new dev.timstewart.slipway.physics.BoxList();
		BlockPos min = this.anchor.offset(this.localMin);
		BlockPos max = this.anchor.offset(this.localMax);
		for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
			for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
				net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null) {
					return;
				}
				for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
					unused.clear();
					SHAPES.build(level, chunk, sy, this.anchor.getX(), this.anchor.getY(), this.anchor.getZ(), unused, builder);
				}
			}
		}
		this.hullDirty = false;
		long fingerprint = builder.fingerprint();
		if (fingerprint != this.hullFingerprint || this.hull == dev.timstewart.slipway.physics.Hull.EMPTY) {
			this.hull = builder.build();
			this.hullFingerprint = fingerprint;
		}
	}

	/**
	 * How much of its own weight the vessel can displace at most (above 1 it floats), from the hull as this client
	 * built it and the mass the server told; 0 while either is unknown.
	 */
	public double buoyancyReserve() {
		return this.mass <= 0 ? 0.0 : this.hull.capacity() * 1000.0 / this.mass;
	}

	/**
	 * The playback time one client tick later: one tick on, run up to {@link #MAX_RATE_CHANGE} fast or slow towards
	 * {@link #DELAY_TICKS} behind the newest snapshot, or straight to it when more than {@link #SNAP_TICKS} off.
	 */
	static double nextPlaybackTick(double playback, long latest) {
		double target = latest - DELAY_TICKS;
		double behind = target - (playback + 1.0);
		if (!Double.isFinite(playback) || Math.abs(behind) > SNAP_TICKS) {
			return target;
		}
		return playback + 1.0 + Math.max(-MAX_RATE_CHANGE, Math.min(MAX_RATE_CHANGE, behind * MAX_RATE_CHANGE));
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

	/** The server game time (with fraction) that {@link #tickPose()} shows; NaN before the first pose. */
	public double playbackTick() {
		return this.playbackTick;
	}

	/**
	 * How far {@link #tickPose()} is behind the newest pose received, in server ticks: {@value #DELAY_TICKS} when in
	 * step, more while playback catches up after this client stalled; NaN before the first pose.
	 */
	public double playbackLag() {
		Snapshot newest = this.snapshots.peekLast();
		return newest == null ? Double.NaN : newest.tick - this.playbackTick;
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

	/** Whether this is the picture of a vessel that is gone. */
	public boolean gone() {
		return this.goneAtTick >= 0;
	}

	/** Keeps the vessel's picture as it is now: the mesh and the block entities of its plot. */
	void keepPicture(net.minecraft.client.multiplayer.ClientLevel level, long clientTick) {
		this.mesh.freeze(level);
		this.waterMask.clear();
		java.util.List<net.minecraft.world.level.block.entity.BlockEntity> kept = new java.util.ArrayList<>();
		BlockPos min = this.anchor.offset(this.localMin);
		BlockPos max = this.anchor.offset(this.localMax);
		for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
			for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
				var chunk = level.getChunkSource().getChunk(cx, cz, false);
				if (chunk != null) {
					kept.addAll(chunk.getBlockEntities().values());
				}
			}
		}
		this.keptBlockEntities = kept;
		this.keptBlockEntityLight = kept.stream().mapToInt(dev.timstewart.slipway.client.render.VesselRenderer::lightOf).toArray();
		this.goneAtTick = clientTick;
		this.terrainBusyAtTick = clientTick;
	}

	void close() {
		this.mesh.clear();
		this.waterMask.clear();
		this.hull = dev.timstewart.slipway.physics.Hull.EMPTY;
		this.keptBlockEntities = java.util.List.of();
		this.keptBlockEntityLight = new int[0];
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

		@Override
		public dev.timstewart.slipway.physics.Hull hull() {
			return ClientVessel.this.hull;
		}

		@Override
		public java.util.List<VesselPose> recentPoses() {
			// The poses this client plays back and the newer ones it already has: what the server tells about other
			// entities is ahead of the playback.
			java.util.List<VesselPose> poses = new java.util.ArrayList<>(4);
			VesselPose last = ClientVessel.this.tickPose;
			poses.add(last);
			if (ClientVessel.this.previousTickPose != null && !ClientVessel.this.previousTickPose.equals(last)) {
				poses.add(ClientVessel.this.previousTickPose);
			}
			int newer = 0;
			for (java.util.Iterator<Snapshot> it = ClientVessel.this.snapshots.descendingIterator(); it.hasNext() && newer < RECENT_SNAPSHOTS; newer++) {
				Snapshot snapshot = it.next();
				if (snapshot.tick <= ClientVessel.this.playbackTick) {
					break;
				}
				if (!snapshot.pose.equals(last)) {
					poses.add(snapshot.pose);
					last = snapshot.pose;
				}
			}
			return poses;
		}

		@Override
		public VesselPose framePose(float partialTick) {
			return ClientVessels.poseMatchingEntities(ClientVessel.this, partialTick);
		}
	};

	/** This vessel for side-independent collision and interaction code, at the current client-tick pose. */
	public dev.timstewart.slipway.vessel.VesselLookup.View view() {
		return this.view;
	}
}
