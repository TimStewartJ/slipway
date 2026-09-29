package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Server-side owner of every vessel in one level: the saved {@link VesselRegistry}, the vessels that are active
 * (their entity is loaded), their plot-chunk tickets, the players who have been sent their chunks, and the
 * physics world. All methods run on the server thread.
 */
public final class VesselManager {
	private static final Map<ServerLevel, VesselManager> MANAGERS = new IdentityHashMap<>();

	private final ServerLevel level;
	private final VesselRegistry registry;
	private final Long2ObjectMap<ActiveVessel> active = new Long2ObjectLinkedOpenHashMap<>();
	private final Int2LongOpenHashMap plotToVessel = new Int2LongOpenHashMap();
	private final VesselPhysicsBridge physics;

	private VesselManager(ServerLevel level) {
		this.level = level;
		this.registry = level.getDataStorage().computeIfAbsent(VesselRegistry.TYPE);
		this.plotToVessel.defaultReturnValue(-1L);
		for (VesselRecord record : this.registry.all()) {
			this.plotToVessel.put(record.plot, record.id);
		}
		this.physics = new VesselPhysicsBridge(this);
	}

	public static VesselManager get(ServerLevel level) {
		return MANAGERS.computeIfAbsent(level, VesselManager::new);
	}

	@Nullable
	public static VesselManager getIfPresent(ServerLevel level) {
		return MANAGERS.get(level);
	}

	public static void onLevelUnload(ServerLevel level) {
		VesselManager manager = MANAGERS.remove(level);
		if (manager != null) {
			manager.close();
		}
	}

	public static void onServerStopped() {
		for (VesselManager manager : List.copyOf(MANAGERS.values())) {
			manager.close();
		}
		MANAGERS.clear();
	}

	public ServerLevel level() {
		return this.level;
	}

	public VesselRegistry registry() {
		return this.registry;
	}

	public VesselPhysicsBridge physics() {
		return this.physics;
	}

	public Collection<ActiveVessel> activeVessels() {
		return this.active.values();
	}

	@Nullable
	public ActiveVessel active(long id) {
		return this.active.get(id);
	}

	/** The vessel whose plot contains a plot block position, whether or not it is active. */
	@Nullable
	public VesselRecord recordAt(BlockPos plotPos) {
		int plot = VesselRegion.plotAt(plotPos.getX(), plotPos.getZ());
		if (plot < 0) {
			return null;
		}
		long id = this.plotToVessel.get(plot);
		return id < 0 ? null : this.registry.get(id);
	}

	@Nullable
	public ActiveVessel activeAt(BlockPos plotPos) {
		return this.activeAtPlot(VesselRegion.plotAt(plotPos.getX(), plotPos.getZ()));
	}

	@Nullable
	public ActiveVessel activeAtPlot(int plot) {
		if (plot < 0) {
			return null;
		}
		long id = this.plotToVessel.get(plot);
		return id < 0 ? null : this.active.get(id);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Player actions
	// ---------------------------------------------------------------------------------------------------------

	/** A player used a helm block at {@code pos}. */
	public void useHelm(BlockPos pos, ServerPlayer player) {
		if (VesselRegion.isReserved(pos)) {
			ActiveVessel vessel = this.activeAt(pos);
			if (vessel == null || vessel.entity == null) {
				return;
			}
			if (player.isShiftKeyDown()) {
				this.disassemble(vessel.record.id, player);
			} else if (player.getVehicle() != vessel.entity) {
				if (player.startRiding(vessel.entity, true, true)) {
					player.sendOverlayMessage(Component.translatable("slipway.helm.taken"));
				}
			}
			return;
		}
		this.assemble(pos, player);
	}

	public VesselAssembly.Outcome assemble(BlockPos helmPos, @Nullable ServerPlayer player) {
		VesselAssembly.Outcome outcome = VesselAssembly.assemble(this.level, this.registry, helmPos, SlipwayConfig.get());
		if (outcome.success() && outcome.record() != null) {
			VesselRecord record = outcome.record();
			this.plotToVessel.put(record.plot, record.id);
			VesselEntity entity = SlipwayRegistry.VESSEL.create(this.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (entity == null) {
				throw new IllegalStateException("Could not create a vessel entity");
			}
			entity.setVesselId(record.id);
			Vec3 centre = worldCentre(record);
			entity.setPos(centre);
			// The entity joins the level first; its first load activates the vessel.
			this.level.addFreshEntity(entity);
		}
		if (player != null) {
			player.sendOverlayMessage(outcome.message());
		}
		return outcome;
	}

	public VesselAssembly.Outcome disassemble(long id, @Nullable ServerPlayer player) {
		VesselRecord record = this.registry.get(id);
		if (record == null) {
			return VesselAssembly.Outcome.fail(Component.translatable("slipway.disassemble.unknown", id));
		}
		ActiveVessel vessel = this.active.get(id);
		if (vessel != null) {
			this.physics.syncRecord(vessel);
		}
		VesselAssembly.Placement placement = VesselAssembly.plan(this.level, record, SlipwayConfig.get());
		if (placement.refusal() != null) {
			if (player != null) {
				player.sendOverlayMessage(placement.refusal());
			}
			return VesselAssembly.Outcome.fail(placement.refusal());
		}
		if (vessel != null && vessel.entity != null) {
			vessel.entity.ejectPassengers();
		}
		int count = VesselAssembly.disassemble(this.level, record, placement);
		for (ServerPlayer viewer : this.level.players()) {
			ServerPlayNetworking.send(viewer, new SlipwayPayloads.VesselGone(id));
		}
		if (vessel != null) {
			this.deactivate(vessel, true);
			if (vessel.entity != null) {
				vessel.entity.discard();
			}
		}
		this.registry.remove(id);
		this.registry.freePlot(record.plot);
		this.plotToVessel.remove(record.plot);
		Component message = Component.translatable("slipway.disassemble.done", count);
		if (player != null) {
			player.sendOverlayMessage(message);
		}
		return new VesselAssembly.Outcome(true, message, record);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Entity lifecycle
	// ---------------------------------------------------------------------------------------------------------

	/** Called from {@link VesselEntity#tick} and when the entity loads: activates the vessel it names. */
	public void tickEntity(VesselEntity entity) {
		long id = entity.vesselId();
		ActiveVessel vessel = this.active.get(id);
		if (vessel == null) {
			VesselRecord record = this.registry.get(id);
			if (record == null) {
				Slipway.LOGGER.warn("Discarding vessel entity {} for unknown vessel {}", entity.getUUID(), id);
				entity.discard();
				return;
			}
			this.activate(record, entity);
		} else if (vessel.entity != entity) {
			if (vessel.entity == null || vessel.entity.isRemoved()) {
				vessel.entity = entity;
			} else {
				Slipway.LOGGER.warn("Discarding duplicate entity {} for vessel {}", entity.getUUID(), id);
				entity.discard();
			}
		}
	}

	public void onEntityUnloaded(VesselEntity entity) {
		ActiveVessel vessel = this.active.get(entity.vesselId());
		if (vessel != null && vessel.entity == entity) {
			this.deactivate(vessel, false);
		}
	}

	private void activate(VesselRecord record, VesselEntity entity) {
		ActiveVessel vessel = new ActiveVessel(record, entity);
		this.active.put(record.id, vessel);
		this.addTickets(vessel);
		entity.updateFrom(record.pose, record.helm, record.helmFacing, worldCentre(record));
		Slipway.LOGGER.debug("Activated vessel {}", record.id);
	}

	private void deactivate(ActiveVessel vessel, boolean removed) {
		this.physics.removeBody(vessel);
		for (ServerPlayer viewer : List.copyOf(vessel.viewers)) {
			this.forgetChunks(vessel, viewer);
		}
		this.removeTickets(vessel);
		this.active.remove(vessel.record.id);
		if (!removed) {
			this.registry.setDirty();
		}
	}

	private void close() {
		for (ActiveVessel vessel : List.copyOf(this.active.values())) {
			this.physics.syncRecord(vessel);
			this.deactivate(vessel, false);
		}
		this.physics.close();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Ticking
	// ---------------------------------------------------------------------------------------------------------

	/** Start of the level tick: exchange with physics, then place entities and sync viewers. */
	public void tickStart() {
		for (ActiveVessel vessel : this.active.values()) {
			if (!vessel.chunksReady) {
				vessel.chunksReady = this.plotChunksLoaded(vessel);
			}
		}
		this.physics.exchange();
		long gameTime = this.level.getGameTime();
		for (ActiveVessel vessel : List.copyOf(this.active.values())) {
			VesselRecord record = vessel.record;
			if (vessel.entity != null && !vessel.entity.isRemoved()) {
				vessel.entity.updateFrom(record.pose, record.helm, record.helmFacing, worldCentre(record));
				this.broadcastPose(vessel, gameTime);
			}
			this.syncViewers(vessel);
		}
		this.registry.setDirty();
	}

	private void broadcastPose(ActiveVessel vessel, long gameTime) {
		VesselRecord record = vessel.record;
		Collection<ServerPlayer> tracking = net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(vessel.entity);
		if (tracking.isEmpty()) {
			return;
		}
		byte flags = (byte)((record.hover ? 1 : 0) | (record.level ? 2 : 0) | (vessel.hasBody ? 4 : 0));
		SlipwayPayloads.PoseUpdate pose = new SlipwayPayloads.PoseUpdate(record.id, vessel.entity.getId(), gameTime,
			SlipwayPayloads.VesselPoseData.of(record.pose), record.linearVelocity, record.angularVelocity, flags);
		SlipwayPayloads.VesselInfo info = vessel.infoDirty ? this.info(vessel) : null;
		vessel.infoDirty = false;
		for (ServerPlayer player : tracking) {
			if (info != null) {
				ServerPlayNetworking.send(player, info);
			}
			ServerPlayNetworking.send(player, pose);
		}
	}

	private SlipwayPayloads.VesselInfo info(ActiveVessel vessel) {
		VesselRecord record = vessel.record;
		return new SlipwayPayloads.VesselInfo(record.id, vessel.entity == null ? -1 : vessel.entity.getId(), record.anchor, record.localMin, record.localMax,
			record.helm, record.helmFacing, record.blockCount, vessel.mass == null ? 0F : (float)vessel.mass.mass());
	}

	/** A block in a plot changed: rebuild that vessel's collision shape and grow its bounds if needed. */
	void onPlotBlockChanged(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		ActiveVessel vessel = this.activeAt(pos);
		if (vessel == null) {
			return;
		}
		vessel.shapeDirty = true;
		vessel.revision++;
		if (!state.isAir()) {
			this.includeLocal(vessel, vessel.record.toLocal(pos));
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Plot chunks: tickets and viewers
	// ---------------------------------------------------------------------------------------------------------

	private void addTickets(ActiveVessel vessel) {
		for (long chunk : plotChunks(vessel.record)) {
			if (vessel.ticketChunks.add(chunk)) {
				this.level.getChunkSource().addTicketWithRadius(SlipwayRegistry.VESSEL_TICKET, ChunkPos.unpack(chunk), 2);
			}
		}
	}

	private void removeTickets(ActiveVessel vessel) {
		for (long chunk : vessel.ticketChunks) {
			this.level.getChunkSource().removeTicketWithRadius(SlipwayRegistry.VESSEL_TICKET, ChunkPos.unpack(chunk), 2);
		}
		vessel.ticketChunks.clear();
	}

	/** The plot chunk columns covering a vessel's current bounds. */
	public static List<Long> plotChunks(VesselRecord record) {
		BlockPos min = record.plotMin();
		BlockPos max = record.plotMax();
		List<Long> chunks = new ArrayList<>();
		for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
			for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
				chunks.add(ChunkPos.pack(cx, cz));
			}
		}
		return chunks;
	}

	private boolean plotChunksLoaded(ActiveVessel vessel) {
		for (long chunk : vessel.ticketChunks) {
			if (this.level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null) {
				return false;
			}
		}
		return true;
	}

	/** Grows a vessel's bounds to include a local position and loads and shares any new plot chunk column. */
	public void includeLocal(ActiveVessel vessel, BlockPos local) {
		BlockPos oldMin = vessel.record.localMin;
		BlockPos oldMax = vessel.record.localMax;
		vessel.record.include(local);
		if (!oldMin.equals(vessel.record.localMin) || !oldMax.equals(vessel.record.localMax)) {
			this.addTickets(vessel);
			for (ServerPlayer viewer : vessel.viewers) {
				this.sendChunks(vessel, viewer);
			}
			this.registry.setDirty();
		}
	}

	private void syncViewers(ActiveVessel vessel) {
		if (vessel.entity == null) {
			return;
		}
		Collection<ServerPlayer> tracking = net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(vessel.entity);
		if (vessel.chunksReady) {
			for (ServerPlayer player : tracking) {
				if (!vessel.viewers.contains(player)) {
					vessel.viewers.add(player);
					ServerPlayNetworking.send(player, this.info(vessel));
					this.sendChunks(vessel, player);
				}
			}
		}
		for (ServerPlayer viewer : List.copyOf(vessel.viewers)) {
			if (!tracking.contains(viewer) || viewer.isRemoved() || viewer.level() != this.level) {
				this.forgetChunks(vessel, viewer);
				vessel.viewers.remove(viewer);
			}
		}
	}

	private void sendChunks(ActiveVessel vessel, ServerPlayer player) {
		for (long chunk : vessel.ticketChunks) {
			var levelChunk = this.level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
			if (levelChunk != null) {
				player.connection.chunkSender.markChunkPendingToSend(levelChunk);
			}
		}
	}

	private void forgetChunks(ActiveVessel vessel, ServerPlayer player) {
		if (player.isRemoved() || player.level() != this.level) {
			return;
		}
		for (long chunk : vessel.ticketChunks) {
			player.connection.chunkSender.dropChunk(player, ChunkPos.unpack(chunk));
		}
	}

	/** Whether a player has been sent a reserved chunk (used by the chunk-tracking hook). */
	public boolean isPlotChunkViewed(ServerPlayer player, int chunkX, int chunkZ) {
		ActiveVessel vessel = this.activeAtPlot(VesselRegion.plotAtChunk(chunkX, chunkZ));
		return vessel != null && vessel.viewers.contains(player) && vessel.ticketChunks.contains(ChunkPos.pack(chunkX, chunkZ));
	}

	// ---------------------------------------------------------------------------------------------------------
	// Geometry helpers
	// ---------------------------------------------------------------------------------------------------------

	public static Vec3 worldCentre(VesselRecord record) {
		Vec3 local = record.localCenter();
		Vector3d world = record.pose.localToWorld(local.x, local.y, local.z, new Vector3d());
		return new Vec3(world.x, world.y, world.z);
	}

	/** Maps a plot-space point of a vessel into world space. */
	public static Vec3 plotToWorld(VesselRecord record, Vec3 plotPoint) {
		Vector3d world = record.pose.localToWorld(plotPoint.x - record.anchor.getX(), plotPoint.y - record.anchor.getY(),
			plotPoint.z - record.anchor.getZ(), new Vector3d());
		return new Vec3(world.x, world.y, world.z);
	}

	/** Maps a world-space point into a vessel's plot space. */
	public static Vec3 worldToPlot(VesselRecord record, Vec3 worldPoint) {
		Vector3d local = record.pose.worldToLocal(worldPoint.x, worldPoint.y, worldPoint.z, new Vector3d());
		return new Vec3(local.x + record.anchor.getX(), local.y + record.anchor.getY(), local.z + record.anchor.getZ());
	}

	/** Sets a vessel's pose directly (commands and tests); physics picks it up at the next exchange. */
	public void teleport(ActiveVessel vessel, VesselPose pose) {
		vessel.record.pose = pose;
		vessel.record.linearVelocity = Vec3.ZERO;
		vessel.record.angularVelocity = Vec3.ZERO;
		this.physics.teleport(vessel);
		if (vessel.entity != null) {
			vessel.entity.updateFrom(pose, vessel.record.helm, vessel.record.helmFacing, worldCentre(vessel.record));
		}
		this.registry.setDirty();
	}

	public static boolean isVesselEntity(Entity entity) {
		return entity instanceof VesselEntity;
	}
}
