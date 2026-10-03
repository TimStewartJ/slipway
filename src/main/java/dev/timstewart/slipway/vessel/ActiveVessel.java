package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.net.RateLimiter;
import dev.timstewart.slipway.physics.BoxList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** A vessel whose entity is loaded: its record, entity, plot-chunk tickets, viewers and physics state. */
public final class ActiveVessel {
	public final VesselRecord record;
	@Nullable
	public VesselEntity entity;
	/** Plot chunk columns holding a {@code slipway:vessel} ticket, packed {@code ChunkPos} longs. */
	public final LongLinkedOpenHashSet ticketChunks = new LongLinkedOpenHashSet();
	/** True once every ticketed plot chunk is loaded. */
	public boolean chunksReady;
	/**
	 * Plot chunk columns that viewers get: the ticketed ones and the ring of columns around them. A client lights a
	 * face by the block next to it, so without the ring every face on the outer edge of the ticketed columns was drawn
	 * black (the helm stands on a chunk corner of the plot: a vessel that begins at its helm has such faces).
	 */
	public final LongLinkedOpenHashSet viewChunks = new LongLinkedOpenHashSet();
	/** Columns of {@link #viewChunks} that were not loaded yet when viewers got the others; each goes out once it is. */
	public final LongLinkedOpenHashSet unsentChunks = new LongLinkedOpenHashSet();
	/** Players who have been sent this vessel's plot chunks. */
	public final Set<ServerPlayer> viewers = new HashSet<>();
	/** Until this game time, viewers sent the vessel at assembly are kept although they do not track its entity yet. */
	public long viewerGraceUntil = Long.MIN_VALUE;
	/** Helm input from the pilot, cleared when nobody pilots. */
	public final HelmInput input = new HelmInput();
	public final RateLimiter controlRate = new RateLimiter();
	/** Set when a block of the vessel changed; the collision shape is rebuilt at the next exchange. */
	public boolean shapeDirty = true;
	/** True while the physics body exists. */
	public boolean hasBody;
	/** Mass properties of the current collision shape, local frame. */
	public BoxList.@Nullable MassProperties mass;
	/** What the vessel displaces when it lies in water, built with the collision shape. */
	public dev.timstewart.slipway.physics.Hull hull = dev.timstewart.slipway.physics.Hull.EMPTY;
	/** Which blocks {@link #hull} was built from (see {@code Hull.Builder.fingerprint}). */
	long hullFingerprint;
	/** What the step in flight finds in the water; only the physics thread reads and writes it. */
	final dev.timstewart.slipway.physics.Buoyancy.State buoyancyStep = new dev.timstewart.slipway.physics.Buoyancy.State();
	/** What the last finished step found in the water: copied at the exchange, for the server thread. */
	public final dev.timstewart.slipway.physics.Buoyancy.State buoyancy = new dev.timstewart.slipway.physics.Buoyancy.State();
	/** Whether the vessel was in a fluid at the last tick, to notice it going in. */
	boolean wasInFluid;

	/** Whether water is running over a rim into air the hull kept dry: more than a splash, a twentieth of a block or of that air. */
	public boolean flooding() {
		return this.buoyancy.floodedVolume > Math.max(0.05, 0.02 * this.hull.shelteredVolume());
	}

	/**
	 * How much of its own weight the vessel can displace at most: above 1 it floats, with everything under water and
	 * nothing flooded it would be pushed up this many times as hard as it is pulled down. 0 while its mass is unknown.
	 */
	public double buoyancyReserve() {
		return this.mass == null || this.mass.mass() <= 0 ? 0.0 : this.hull.capacity() * 1000.0 / this.mass.mass();
	}
	/** Where the hovering vessel holds its position; only the physics thread reads and writes it. */
	final dev.timstewart.slipway.physics.VesselController.Hold hold = new dev.timstewart.slipway.physics.VesselController.Hold();
	/** The hold point is stale (the vessel was teleported): the next step takes a new one. */
	boolean holdReset;
	/** Set by tests to fly a reference: hover only brakes and holds nothing, as before 0.1.2. */
	public boolean brakeOnly;
	/** Whether the physics body has been told it is loose; differs from the record until the next exchange. */
	boolean bodyLoose;
	/** False while the body sleeps: a loose vessel that has come to rest is not simulated until something disturbs it. */
	public boolean bodyAwake = true;
	/** Bumped whenever the vessel's blocks change; clients and proxies use it to know when to refresh. */
	public int revision;
	/** Set when bounds, block count or mass changed and viewers need a new info packet. */
	public boolean infoDirty = true;
	/** Game time until which input set by a command is kept without a pilot (test harness). */
	public long scriptedInputUntil = Long.MIN_VALUE;
	/** The long-range proxy (exposed blocks) needs recomputing. */
	public boolean proxyDirty = true;
	/** Game time of the last proxy rebuild; "long ago" at first (kept far from Long.MIN_VALUE so subtraction cannot overflow). */
	public long lastProxyBuild = -1_000_000L;
	/** Pose at the start of the previous tick; entities standing on the vessel are carried from it to the current pose. */
	public dev.timstewart.slipway.math.VesselPose previousPose;
	/** How many poses before the current one are kept: half a second, for players, whom their clients move (see {@link Shelter}). */
	public static final int POSE_HISTORY = 10;
	/** The poses before the current one, newest first. */
	private final java.util.ArrayDeque<dev.timstewart.slipway.math.VesselPose> earlierPoses = new java.util.ArrayDeque<>(POSE_HISTORY + 1);

	/** Tick boundary: the pose of the tick that ends becomes the previous one. */
	void rememberPose() {
		this.previousPose = this.record.pose;
		this.earlierPoses.addFirst(this.record.pose);
		while (this.earlierPoses.size() > POSE_HISTORY) {
			this.earlierPoses.pollLast();
		}
	}

	/** The vessel was put somewhere else: where it was says nothing about who is aboard now. */
	void forgetPoses() {
		this.previousPose = this.record.pose;
		this.earlierPoses.clear();
	}
	private final VesselLookup.View view = new VesselLookup.View() {
		@Override
		public long id() {
			return ActiveVessel.this.record.id;
		}

		@Override
		public net.minecraft.core.BlockPos anchor() {
			return ActiveVessel.this.record.anchor;
		}

		@Override
		public dev.timstewart.slipway.math.VesselPose pose() {
			return ActiveVessel.this.record.pose;
		}

		@Override
		public dev.timstewart.slipway.math.VesselPose previousPose() {
			return ActiveVessel.this.previousPose == null ? ActiveVessel.this.record.pose : ActiveVessel.this.previousPose;
		}

		@Override
		public net.minecraft.world.phys.AABB worldBounds() {
			double[] b = VesselPhysicsBridge.worldBounds(ActiveVessel.this.record);
			return new net.minecraft.world.phys.AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
		}

		@Override
		public net.minecraft.world.phys.Vec3 velocity() {
			return ActiveVessel.this.record.linearVelocity;
		}

		@Override
		public dev.timstewart.slipway.physics.Hull hull() {
			return ActiveVessel.this.hull;
		}

		@Override
		public java.util.List<dev.timstewart.slipway.math.VesselPose> recentPoses() {
			java.util.List<dev.timstewart.slipway.math.VesselPose> poses = new java.util.ArrayList<>(POSE_HISTORY + 1);
			dev.timstewart.slipway.math.VesselPose last = ActiveVessel.this.record.pose;
			poses.add(last);
			for (dev.timstewart.slipway.math.VesselPose pose : ActiveVessel.this.earlierPoses) {
				if (!pose.equals(last)) {
					poses.add(pose);
					last = pose;
				}
			}
			return poses;
		}
	};

	public VesselLookup.View view() {
		return this.view;
	}
	/** Physics results from steps before this one are stale (the body was created or teleported since). */
	public long poseValidFromStep = Long.MAX_VALUE;

	ActiveVessel(VesselRecord record, VesselEntity entity) {
		this.record = record;
		this.entity = entity;
	}
}
