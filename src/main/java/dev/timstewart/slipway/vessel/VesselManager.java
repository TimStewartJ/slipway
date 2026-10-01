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
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
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
	/** How long players who got a new vessel early keep it while their tracking of its entity starts. */
	private static final int VIEWER_GRACE_TICKS = 100;
	/** How long a vessel's entity outlives the vessel: the longest a client may go on drawing a vessel that is gone. */
	public static final int RETIRED_ENTITY_TICKS = 6;

	private final ServerLevel level;
	private final VesselRegistry registry;
	private final Long2ObjectMap<ActiveVessel> active = new Long2ObjectLinkedOpenHashMap<>();
	private final Int2LongOpenHashMap plotToVessel = new Int2LongOpenHashMap();
	private final VesselPhysicsBridge physics;
	private final VesselProxies proxies;
	/** Work to do at the start of the next tick (after this tick's block updates have gone out). */
	private final List<Runnable> nextTick = new ArrayList<>();
	/** Entities of vessels that are gone, with the game time at which each is discarded. */
	private final java.util.Map<VesselEntity, Long> retired = new java.util.IdentityHashMap<>();

	private VesselManager(ServerLevel level) {
		this.level = level;
		this.registry = level.getDataStorage().computeIfAbsent(VesselRegistry.TYPE);
		this.plotToVessel.defaultReturnValue(-1L);
		for (VesselRecord record : this.registry.all()) {
			this.plotToVessel.put(record.plot, record.id);
		}
		this.physics = new VesselPhysicsBridge(this);
		this.proxies = new VesselProxies(this);
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

	/** Levels Slipway currently manages vessels for (0 once every server in this process has stopped). */
	public static int managerCount() {
		return MANAGERS.size();
	}

	/**
	 * Before the server saves (autosave, /save-all, shutdown): brings every active vessel's record up to the latest
	 * physics state so what is written is the vessel's current pose and velocity. On shutdown each saved state is
	 * logged, which lets the save/reload test compare it with what loads.
	 */
	public static void beforeSave(boolean stopping) {
		for (VesselManager manager : MANAGERS.values()) {
			for (ActiveVessel vessel : manager.active.values()) {
				manager.physics.syncRecord(vessel);
			}
			manager.registry.setDirty();
			if (stopping && !manager.registry.all().isEmpty()) {
				// Vessels whose chunks unloaded as the last players left are already inactive; their records hold the
				// state they had then. The end-to-end harness compares each saved state with what loads back.
				if (Boolean.getBoolean("slipway.e2e")) {
					for (VesselRecord r : manager.registry.all()) {
						Slipway.LOGGER.info(String.format(java.util.Locale.ROOT, "Vessel %d saved at pos=%.3f,%.3f,%.3f q=%.6f,%.6f,%.6f,%.6f vel=%.3f,%.3f,%.3f",
							r.id, r.pose.x(), r.pose.y(), r.pose.z(), r.pose.qx(), r.pose.qy(), r.pose.qz(), r.pose.qw(),
							r.linearVelocity.x, r.linearVelocity.y, r.linearVelocity.z));
					}
				}
				Slipway.LOGGER.info("Saved {} vessels in {}", manager.registry.size(), manager.level.dimension().identifier());
			}
		}
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
					player.sendSystemMessage(helmControls());
				}
			}
			return;
		}
		this.assemble(pos, player);
	}

	/** The helm's controls, with each key shown as the player has it bound. */
	static Component helmControls() {
		return Component.translatable("slipway.helm.taken",
			Component.keybind("key.forward"), Component.keybind("key.back"), Component.keybind("key.left"), Component.keybind("key.right"),
			Component.keybind("key.jump"), Component.keybind("key.slipway.descend"), Component.keybind("key.slipway.pitch_up"),
			Component.keybind("key.slipway.pitch_down"), Component.keybind("key.slipway.roll_left"), Component.keybind("key.slipway.roll_right"),
			Component.keybind("key.slipway.strafe_left"), Component.keybind("key.slipway.strafe_right"), Component.keybind("key.slipway.toggle_hover"),
			Component.keybind("key.slipway.toggle_level"), Component.keybind("key.slipway.toggle_loose"), Component.keybind("key.sneak"));
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
			// The entity joins the level first; its load event activates the vessel.
			this.level.addFreshEntity(entity);
			ActiveVessel vessel = this.active.get(record.id);
			if (vessel != null) {
				this.shareNow(vessel, centre);
			}
		}
		if (player != null) {
			player.sendOverlayMessage(outcome.message());
		}
		return outcome;
	}

	/**
	 * Sends a just-assembled vessel (info, pose and plot chunks) to every player near it right away, ahead of the
	 * block updates that remove its blocks from the world at the end of this tick. Players standing on the structure
	 * therefore find the vessel under their feet the moment the world blocks vanish instead of falling through while
	 * the regular tracking catches up.
	 */
	private void shareNow(ActiveVessel vessel, Vec3 centre) {
		vessel.chunksReady = this.plotChunksLoaded(vessel);
		if (!vessel.chunksReady || vessel.entity == null) {
			return;
		}
		long gameTime = this.level.getGameTime();
		vessel.viewerGraceUntil = gameTime + VIEWER_GRACE_TICKS;
		int rangeChunks = Math.min(SlipwayRegistry.VESSEL.clientTrackingRange(), this.level.getServer().getPlayerList().getViewDistance());
		double range = rangeChunks * 16.0;
		SlipwayPayloads.VesselInfo info = this.info(vessel, true);
		SlipwayPayloads.PoseUpdate pose = this.posePayload(vessel, gameTime);
		for (ServerPlayer player : this.level.players()) {
			if (player.distanceToSqr(centre) > range * range || vessel.viewers.contains(player)) {
				continue;
			}
			vessel.viewers.add(player);
			ServerPlayNetworking.send(player, info);
			ServerPlayNetworking.send(player, pose);
			for (long chunk : vessel.ticketChunks) {
				LevelChunk levelChunk = this.level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
				if (levelChunk != null) {
					player.connection.send(new ClientboundLevelChunkWithLightPacket(levelChunk, this.level.getLightEngine(), null, null));
				}
			}
		}
		// Those chunk packets carry the light from before the blocks moved in (vanilla only sends chunks once their light
		// work is done); send the finished light as soon as the light engine has caught up.
		ThreadedLevelLightEngine lightEngine = this.level.getChunkSource().getLightEngine();
		for (long chunk : vessel.ticketChunks) {
			int chunkX = ChunkPos.getX(chunk);
			int chunkZ = ChunkPos.getZ(chunk);
			lightEngine.waitForPendingTasks(chunkX, chunkZ).thenRunAsync(() -> {
				if (this.active.get(vessel.record.id) != vessel) {
					return;
				}
				ClientboundLightUpdatePacket light = new ClientboundLightUpdatePacket(new ChunkPos(chunkX, chunkZ), lightEngine, null, null);
				for (ServerPlayer viewer : vessel.viewers) {
					viewer.connection.send(light);
				}
			}, this.level.getServer());
		}
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
		VesselAssembly.finishPistonMoves(this.level, record);
		VesselAssembly.Placement placement = VesselAssembly.plan(this.level, record, SlipwayConfig.get());
		if (placement.refusal() != null) {
			Slipway.LOGGER.info("Vessel {} stays assembled: {}", id, placement.refusal().getString());
			if (player != null) {
				player.sendOverlayMessage(placement.refusal());
			}
			return VesselAssembly.Outcome.fail(placement.refusal());
		}
		if (vessel != null && vessel.entity != null) {
			vessel.entity.ejectPassengers();
		}
		List<Relocation> aboard = this.entitiesAboard(record, placement);
		int count = VesselAssembly.disassemble(this.level, record, placement);
		// The blocks moved by up to half a block and a few degrees to the snapped placement; move everything that was
		// on board with them. Server-simulated entities move now (the world blocks are there now). Players move their
		// own bodies: their teleport goes out next tick, after the new blocks have reached them and before the vessel
		// they stand on disappears for them.
		List<Relocation> players = new ArrayList<>();
		for (Relocation found : aboard) {
			Relocation move = this.settled(found);
			if (move.entity() instanceof ServerPlayer) {
				players.add(move);
			} else {
				move.entity().snapTo(move.target().x, move.target().y, move.target().z, move.yRot(), move.entity().getXRot());
			}
		}
		if (!players.isEmpty()) {
			this.nextTick.add(() -> {
				for (Relocation move : players) {
					if (move.entity() instanceof ServerPlayer moved && !moved.isRemoved() && moved.level() == this.level && !moved.isPassenger()) {
						moved.connection.teleport(move.target().x, move.target().y, move.target().z, move.yRot(), moved.getXRot());
					}
				}
			});
		}
		this.retire(id, vessel, record);
		Component message = Component.translatable("slipway.disassemble.done", count);
		if (player != null) {
			player.sendOverlayMessage(message);
		}
		return new VesselAssembly.Outcome(true, message, record);
	}

	/** Where an entity that was on a vessel goes when the vessel snaps into the world. */
	private record Relocation(Entity entity, Vec3 target, float yRot) {
	}

	/**
	 * The relocation pushed out of placed blocks its entity's box overlaps: float rounding in the pose can put a rider
	 * a few microns inside the deck it stood on, and vanilla collision ignores a floor a box is already inside, so the
	 * entity would fall through the new blocks.
	 */
	private Relocation settled(Relocation move) {
		Entity entity = move.entity();
		AABB box = entity.getDimensions(entity.getPose()).makeBoundingBox(move.target());
		Vector3d push = VesselCollisions.depenetration(box, this.level.getBlockCollisions(entity, box));
		if (push.x == 0.0 && push.y == 0.0 && push.z == 0.0) {
			return move;
		}
		return new Relocation(entity, move.target().add(push.x, push.y, push.z), move.yRot());
	}

	/**
	 * Entities standing on or inside a vessel (not passengers; the pilot has been set down already), with where the
	 * snapped placement puts them: the same position relative to the blocks, turned with them.
	 */
	private List<Relocation> entitiesAboard(VesselRecord record, VesselAssembly.Placement placement) {
		double[] b = VesselPhysicsBridge.worldBounds(record);
		AABB around = new AABB(b[0], b[1], b[2], b[3], b[4], b[5]).inflate(1.0, 3.0, 1.0);
		VesselPose pose = record.pose;
		BlockPos anchor = placement.worldAnchor();
		double turn = placement.quarterTurns() * 90.0 - pose.yawTwistDegrees();
		List<Relocation> result = new ArrayList<>();
		for (Entity entity : this.level.getEntities((Entity)null, around, e -> !(e instanceof VesselEntity) && !e.isPassenger() && !e.isRemoved())) {
			Vector3d local = pose.worldToLocal(entity.getX(), entity.getY(), entity.getZ(), new Vector3d());
			if (local.x < record.localMin.getX() - 1 || local.x > record.localMax.getX() + 2 || local.z < record.localMin.getZ() - 1
				|| local.z > record.localMax.getZ() + 2 || local.y < record.localMin.getY() - 1 || local.y > record.localMax.getY() + 4) {
				continue;
			}
			// Quarter turns about the anchor block's centre, matching how block cells turn.
			double lx = local.x - 0.5;
			double lz = local.z - 0.5;
			double tx;
			double tz;
			switch (Math.floorMod(placement.quarterTurns(), 4)) {
				case 1 -> { tx = lz; tz = -lx; }
				case 2 -> { tx = -lx; tz = -lz; }
				case 3 -> { tx = -lz; tz = lx; }
				default -> { tx = lx; tz = lz; }
			}
			Vec3 target = new Vec3(anchor.getX() + 0.5 + tx, anchor.getY() + local.y, anchor.getZ() + 0.5 + tz);
			result.add(new Relocation(entity, target, (float)(entity.getYRot() - turn)));
		}
		return result;
	}

	/** Deletes a vessel and all its blocks without drops (admin command); returns the number of blocks, or -1. */
	public int remove(long id) {
		VesselRecord record = this.registry.get(id);
		if (record == null) {
			return -1;
		}
		ActiveVessel vessel = this.active.get(id);
		if (vessel != null && vessel.entity != null) {
			vessel.entity.ejectPassengers();
		}
		int count = VesselAssembly.erase(this.level, record);
		this.retire(id, vessel, record);
		Slipway.LOGGER.info("Removed vessel {} ({} blocks)", id, count);
		return count;
	}

	/**
	 * Forgets a vessel whose blocks have left its plot. Clients keep drawing and colliding with it until they have the
	 * world blocks that replace it, which go out with the block updates at the end of this tick; the vessel is dropped
	 * on their side one tick later. The plot is only freed then, so a new vessel cannot take it before the old chunks
	 * are forgotten.
	 */
	private void retire(long id, @Nullable ActiveVessel vessel, VesselRecord record) {
		List<ServerPlayer> viewers = vessel == null ? List.of() : List.copyOf(vessel.viewers);
		List<Long> chunks = vessel == null ? List.of() : List.copyOf(vessel.ticketChunks);
		if (vessel != null) {
			vessel.viewers.clear();
			this.deactivate(vessel, true);
			if (vessel.entity != null) {
				// The entity outlives its vessel by a few ticks: it is what clients draw the vessel with, and they keep
				// drawing it until their terrain shows the blocks that were put back into the world (see ClientVessels).
				vessel.entity.retire();
				this.retired.put(vessel.entity, this.level.getGameTime() + RETIRED_ENTITY_TICKS);
			}
		}
		this.registry.remove(id);
		this.plotToVessel.remove(record.plot);
		this.proxies.forget(id);
		this.nextTick.add(() -> {
			for (ServerPlayer online : this.level.players()) {
				ServerPlayNetworking.send(online, new SlipwayPayloads.VesselGone(id, false));
			}
			for (ServerPlayer viewer : viewers) {
				if (!viewer.isRemoved() && viewer.level() == this.level) {
					for (long chunk : chunks) {
						viewer.connection.chunkSender.dropChunk(viewer, ChunkPos.unpack(chunk));
					}
				}
			}
			this.registry.freePlot(record.plot);
		});
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
		this.nextTick.forEach(Runnable::run);
		this.nextTick.clear();
		this.retired.keySet().forEach(VesselEntity::discard);
		this.retired.clear();
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
		if (!this.nextTick.isEmpty()) {
			List<Runnable> tasks = List.copyOf(this.nextTick);
			this.nextTick.clear();
			tasks.forEach(Runnable::run);
		}
		if (!this.retired.isEmpty()) {
			long now = this.level.getGameTime();
			this.retired.entrySet().removeIf(entry -> {
				if (now >= entry.getValue() || entry.getKey().isRemoved()) {
					entry.getKey().discard();
					return true;
				}
				return false;
			});
		}
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
		this.proxies.tick(gameTime);
		this.registry.setDirty();
	}

	private void broadcastPose(ActiveVessel vessel, long gameTime) {
		Collection<ServerPlayer> tracking = net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(vessel.entity);
		if (tracking.isEmpty() && vessel.viewers.isEmpty()) {
			return;
		}
		SlipwayPayloads.PoseUpdate pose = this.posePayload(vessel, gameTime);
		SlipwayPayloads.VesselInfo info = vessel.infoDirty ? this.info(vessel) : null;
		vessel.infoDirty = false;
		for (ServerPlayer player : tracking) {
			// Skip players whose connection is closing (a quitting client), rather than write to a closed channel.
			if (!player.connection.isAcceptingMessages()) {
				continue;
			}
			if (info != null) {
				ServerPlayNetworking.send(player, info);
			}
			ServerPlayNetworking.send(player, pose);
		}
		// Viewers that got the vessel early at assembly and are not tracking its entity yet.
		for (ServerPlayer player : vessel.viewers) {
			if (!tracking.contains(player) && player.connection.isAcceptingMessages()) {
				if (info != null) {
					ServerPlayNetworking.send(player, info);
				}
				ServerPlayNetworking.send(player, pose);
			}
		}
	}

	private SlipwayPayloads.PoseUpdate posePayload(ActiveVessel vessel, long gameTime) {
		VesselRecord record = vessel.record;
		byte flags = (byte)((record.hover ? SlipwayPayloads.PoseUpdate.FLAG_HOVER : 0) | (record.level ? SlipwayPayloads.PoseUpdate.FLAG_LEVEL : 0)
			| (vessel.hasBody ? SlipwayPayloads.PoseUpdate.FLAG_BODY : 0) | (record.loose ? SlipwayPayloads.PoseUpdate.FLAG_LOOSE : 0));
		return new SlipwayPayloads.PoseUpdate(record.id, vessel.entity == null ? -1 : vessel.entity.getId(), gameTime,
			SlipwayPayloads.VesselPoseData.of(record.pose), record.linearVelocity, record.angularVelocity, flags);
	}

	private SlipwayPayloads.VesselInfo info(ActiveVessel vessel) {
		return this.info(vessel, false);
	}

	private SlipwayPayloads.VesselInfo info(ActiveVessel vessel, boolean assembled) {
		VesselRecord record = vessel.record;
		return new SlipwayPayloads.VesselInfo(record.id, vessel.entity == null ? -1 : vessel.entity.getId(), record.anchor, record.localMin, record.localMax,
			record.helm, record.helmFacing, record.blockCount, vessel.mass == null ? 0F : (float)vessel.mass.mass(), assembled);
	}

	/**
	 * Sends a packet about a point of a vessel's plot to the players who view the vessel (they have its plot chunks)
	 * and are within {@code range} blocks of where that point is in the world.
	 */
	public void sendToViewersNear(ActiveVessel vessel, Vec3 plotPoint, double range, @Nullable Entity except, net.minecraft.network.protocol.Packet<?> packet) {
		Vec3 world = plotToWorld(vessel.record, plotPoint);
		for (ServerPlayer viewer : vessel.viewers) {
			if (viewer != except && viewer.level() == this.level && viewer.distanceToSqr(world) < range * range && viewer.connection.isAcceptingMessages()) {
				viewer.connection.send(packet);
			}
		}
	}

	/** A block in a plot changed: rebuild that vessel's collision shape and grow its bounds if needed. */
	void onPlotBlockChanged(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		ActiveVessel vessel = this.activeAt(pos);
		if (vessel == null) {
			return;
		}
		vessel.shapeDirty = true;
		vessel.proxyDirty = true;
		vessel.revision++;
		// A block in the margin between plots (only a command can put one there; pistons refuse) is not taken in: the
		// bounds would otherwise reach across the margin.
		if (!state.isAir() && VesselRegion.isUsable(pos)) {
			this.includeLocal(vessel, vessel.record.toLocal(pos));
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Plot chunks: tickets and viewers
	// ---------------------------------------------------------------------------------------------------------

	/** Tickets every plot chunk column of the vessel's bounds that has no ticket yet and returns those. */
	private List<Long> addTickets(ActiveVessel vessel) {
		List<Long> added = new ArrayList<>();
		for (long chunk : plotChunks(vessel.record)) {
			if (vessel.ticketChunks.add(chunk)) {
				this.level.getChunkSource().addTicketWithRadius(SlipwayRegistry.VESSEL_TICKET, ChunkPos.unpack(chunk), 2);
				added.add(chunk);
			}
		}
		return added;
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

	/**
	 * Grows a vessel's bounds to include a local position and loads and shares any new plot chunk column. Only new
	 * columns are sent: sending a chunk again replaces it on the client, which costs a full remesh and deletes what
	 * the client keeps there by itself (the moving blocks of a piston's stroke).
	 */
	public void includeLocal(ActiveVessel vessel, BlockPos local) {
		BlockPos oldMin = vessel.record.localMin;
		BlockPos oldMax = vessel.record.localMax;
		vessel.record.include(local);
		if (!oldMin.equals(vessel.record.localMin) || !oldMax.equals(vessel.record.localMax)) {
			List<Long> added = this.addTickets(vessel);
			for (ServerPlayer viewer : vessel.viewers) {
				this.sendChunks(viewer, added);
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
			boolean early = !tracking.contains(viewer) && this.level.getGameTime() <= vessel.viewerGraceUntil;
			if (viewer.isRemoved() || viewer.level() != this.level || (!tracking.contains(viewer) && !early)) {
				this.forgetChunks(vessel, viewer);
				vessel.viewers.remove(viewer);
				if (!viewer.isRemoved() && viewer.level() == this.level) {
					// The client drops its near view (mesh, pose playback); a Distant Horizons proxy may take over.
					ServerPlayNetworking.send(viewer, new SlipwayPayloads.VesselGone(vessel.record.id, true));
					this.proxies.forget(vessel.record.id, viewer);
				}
			}
		}
	}

	private void sendChunks(ActiveVessel vessel, ServerPlayer player) {
		this.sendChunks(player, vessel.ticketChunks);
	}

	private void sendChunks(ServerPlayer player, Iterable<Long> chunks) {
		for (long chunk : chunks) {
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
		vessel.previousPose = pose;
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
