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
