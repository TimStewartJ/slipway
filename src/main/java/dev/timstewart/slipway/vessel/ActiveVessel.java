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
	/** Physics results from steps before this one are stale (the body was created or teleported since). */
	public long poseValidFromStep = Long.MAX_VALUE;

	ActiveVessel(VesselRecord record, VesselEntity entity) {
		this.record = record;
		this.entity = entity;
	}
}
