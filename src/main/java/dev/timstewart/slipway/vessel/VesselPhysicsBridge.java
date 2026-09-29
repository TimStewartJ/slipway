package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.BoxList;
import dev.timstewart.slipway.physics.PhysicsEngine;
import dev.timstewart.slipway.physics.PhysicsWorld;
import dev.timstewart.slipway.physics.SectionShapes;
import dev.timstewart.slipway.physics.VesselController;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Game-thread side of vessel physics for one level: builds collision boxes from vessel plots and nearby terrain
 * (bounded work per tick), queues them for the physics thread, feeds helm input, and at each tick boundary copies
 * the finished step's poses and velocities into the vessel records. Numbers coming back from the engine are
 * checked; a non-finite state puts the vessel back where it last was valid and stops it.
 */
public final class VesselPhysicsBridge {
	/** Terrain sections built per tick at most; the rest wait for the next tick. */
	private static final int TERRAIN_BUILDS_PER_TICK = 24;
	/** A terrain section no vessel needed for this many ticks is released. */
	private static final int TERRAIN_GRACE_TICKS = 100;
	/** Helm input older than this is treated as released. */
	private static final long INPUT_TIMEOUT_TICKS = 20;

	private final VesselManager manager;
	@Nullable
	private PhysicsWorld world;
	private final SectionShapes sectionShapes = new SectionShapes();
	/** Terrain section (packed SectionPos) to the last tick a vessel needed it. */
	private final Long2LongOpenHashMap terrainLastNeeded = new Long2LongOpenHashMap();
	private final LongOpenHashSet terrainBuilt = new LongOpenHashSet();
	private final LongOpenHashSet terrainDirty = new LongOpenHashSet();
	private long ticks;
	private long lastExchangeNanos;

	VesselPhysicsBridge(VesselManager manager) {
		this.manager = manager;
	}

	private PhysicsWorld world() {
		if (this.world == null) {
			this.world = PhysicsWorld.jolt(this.manager.level().dimension().identifier().toString());
		}
		return this.world;
	}

	@Nullable
	public PhysicsWorld worldIfStarted() {
		return this.world;
	}

	public long lastExchangeNanos() {
		return this.lastExchangeNanos;
	}

	/** Tick boundary: finish the step in flight, publish its results, queue new work and start the next step. */
	void exchange() {
		long start = System.nanoTime();
		this.ticks++;
		if (this.world != null) {
			this.world.await();
			for (ActiveVessel vessel : this.manager.activeVessels()) {
				vessel.previousPose = vessel.record.pose;
				if (vessel.hasBody) {
					this.applyResult(vessel, this.world.result(vessel.record.id));
				}
			}
		} else {
			for (ActiveVessel vessel : this.manager.activeVessels()) {
				vessel.previousPose = vessel.record.pose;
			}
		}
		boolean anyBody = false;
		ServerLevel level = this.manager.level();
		long gameTime = level.getGameTime();
		for (ActiveVessel vessel : this.manager.activeVessels()) {
			boolean piloted = vessel.entity != null && !vessel.entity.getPassengers().isEmpty();
			boolean scripted = gameTime < vessel.scriptedInputUntil;
			if (!scripted && (!piloted || gameTime - vessel.input.lastUpdate > INPUT_TIMEOUT_TICKS)) {
				vessel.input.clear();
			}
			if (vessel.chunksReady && vessel.shapeDirty) {
				this.rebuildShape(vessel);
			}
			anyBody |= vessel.hasBody;
		}
		if (anyBody) {
			this.updateTerrain();
			this.startStep();
		} else if (this.world != null && !this.terrainBuilt.isEmpty()) {
			this.releaseAllTerrain();
		}
		this.lastExchangeNanos = System.nanoTime() - start;
	}

	private void applyResult(ActiveVessel vessel, PhysicsEngine.@Nullable BodyState state) {
		// Results of steps that ran before a teleport (or before the body existed) describe a stale pose.
		if (state == null || this.world == null || this.world.snapshotStepIndex() < vessel.poseValidFromStep) {
			return;
		}
		VesselRecord record = vessel.record;
		if (!state.isFinite()) {
			Slipway.LOGGER.warn("Vessel {} produced a non-finite physics state; restoring its last pose", record.id);
			VesselPose last = record.pose;
			vessel.poseValidFromStep = this.world().nextStepIndex();
			this.world().submit(engine -> engine.teleportVessel(record.id, last));
			record.linearVelocity = Vec3.ZERO;
			record.angularVelocity = Vec3.ZERO;
			return;
		}
		record.pose = state.pose();
		record.linearVelocity = new Vec3(state.vx, state.vy, state.vz);
		record.angularVelocity = new Vec3(state.wx, state.wy, state.wz);
	}

	/** Rebuilds the vessel's collision boxes from its plot and replaces its body shape. */
	private void rebuildShape(ActiveVessel vessel) {
		VesselRecord record = vessel.record;
		ServerLevel level = this.manager.level();
		BoxList boxes = new BoxList();
		BlockPos min = record.plotMin();
		BlockPos max = record.plotMax();
		int blocks = 0;
		for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
			for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					return;
				}
				for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
					blocks += this.sectionShapes.build(level, chunk, sy, record.anchor.getX(), record.anchor.getY(), record.anchor.getZ(), boxes);
				}
			}
		}
		vessel.shapeDirty = false;
		vessel.infoDirty = true;
		record.blockCount = blocks;
		BoxList.MassProperties mass = boxes.massProperties();
		vessel.mass = mass;
		if (boxes.isEmpty() || mass.mass() <= 0) {
			if (vessel.hasBody) {
				long id = record.id;
				this.world().submit(engine -> engine.removeVessel(id));
				vessel.hasBody = false;
			}
			return;
		}
		long id = record.id;
		VesselPose pose = record.pose;
		Vector3d velocity = new Vector3d(record.linearVelocity.x, record.linearVelocity.y, record.linearVelocity.z);
		Vector3d angular = new Vector3d(record.angularVelocity.x, record.angularVelocity.y, record.angularVelocity.z);
		if (!vessel.hasBody) {
			vessel.poseValidFromStep = this.world().nextStepIndex();
		}
		this.world().submit(engine -> engine.setVesselShape(id, boxes, pose, velocity, angular));
		vessel.hasBody = true;
	}

	private void startStep() {
		SlipwayConfig config = SlipwayConfig.get();
		VesselController.Params params = new VesselController.Params(config.thrustAcceleration, config.maxSpeed, config.angularAcceleration,
			config.maxTurnRate, config.levelStrength);
		List<ControlJob> jobs = new ArrayList<>();
		LongArrayList ids = new LongArrayList();
		for (ActiveVessel vessel : this.manager.activeVessels()) {
			if (!vessel.hasBody || vessel.mass == null) {
				continue;
			}
			ids.add(vessel.record.id);
			Vector3d forward = new Vector3d(vessel.record.helmFacing.getOpposite().getStepX(), 0, vessel.record.helmFacing.getOpposite().getStepZ());
			jobs.add(new ControlJob(vessel.record.id, vessel.input.copy(), vessel.record.hover, vessel.record.level, vessel.mass, forward));
		}
		PhysicsEngine.BodyState scratch = new PhysicsEngine.BodyState();
		this.world().startStep(ids.toLongArray(), engine -> {
			for (ControlJob job : jobs) {
				if (!engine.readVessel(job.id, scratch) || !scratch.isFinite()) {
					continue;
				}
				HelmInput in = job.input;
				VesselController.Command command = VesselController.compute(params, in.forward, in.strafe, in.vertical, in.pitch, in.yaw, in.roll,
					job.hover, job.level, new Quaterniond(scratch.qx, scratch.qy, scratch.qz, scratch.qw),
					new Vector3d(scratch.vx, scratch.vy, scratch.vz), new Vector3d(scratch.wx, scratch.wy, scratch.wz),
					job.mass.mass(), job.mass.inertia(), job.forward);
				if (command.isFinite()) {
					engine.applyForceAndTorque(job.id, command.force(), command.torque());
				}
			}
		});
	}

	private record ControlJob(long id, HelmInput input, boolean hover, boolean level, BoxList.MassProperties mass, Vector3d forward) {
	}

	// ---------------------------------------------------------------------------------------------------------
	// Terrain
	// ---------------------------------------------------------------------------------------------------------

	private void updateTerrain() {
		ServerLevel level = this.manager.level();
		LongOpenHashSet needed = new LongOpenHashSet();
		for (ActiveVessel vessel : this.manager.activeVessels()) {
			if (!vessel.hasBody) {
				continue;
			}
			double[] box = worldBounds(vessel.record);
			double speed = vessel.record.linearVelocity.length();
			double margin = 3.0 + speed * 0.25;
			int minSx = SectionPos.blockToSectionCoord(box[0] - margin), maxSx = SectionPos.blockToSectionCoord(box[3] + margin);
			int minSy = SectionPos.blockToSectionCoord(Math.max(level.getMinY(), box[1] - margin));
			int maxSy = SectionPos.blockToSectionCoord(Math.min(level.getMaxY(), box[4] + margin));
			int minSz = SectionPos.blockToSectionCoord(box[2] - margin), maxSz = SectionPos.blockToSectionCoord(box[5] + margin);
			long volume = (long)(maxSx - minSx + 1) * (maxSy - minSy + 1) * (maxSz - minSz + 1);
			if (volume > 4096) {
				continue;
			}
			for (int sx = minSx; sx <= maxSx; sx++) {
				for (int sz = minSz; sz <= maxSz; sz++) {
					for (int sy = minSy; sy <= maxSy; sy++) {
						needed.add(SectionPos.asLong(sx, sy, sz));
					}
				}
			}
		}
		int builds = 0;
		for (long key : needed) {
			this.terrainLastNeeded.put(key, this.ticks);
			boolean dirty = this.terrainDirty.remove(key);
			if (this.terrainBuilt.contains(key) && !dirty) {
				continue;
			}
			if (builds >= TERRAIN_BUILDS_PER_TICK) {
				if (dirty) {
					this.terrainDirty.add(key);
				}
				continue;
			}
			int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
			LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
			if (chunk == null) {
				continue;
			}
			BoxList boxes = new BoxList();
			this.sectionShapes.build(level, chunk, sy, sx << 4, sy << 4, sz << 4, boxes);
			this.terrainBuilt.add(key);
			builds++;
			if (boxes.isEmpty()) {
				this.world().submit(engine -> engine.removeStaticSection(key));
			} else {
				this.world().submit(engine -> engine.setStaticSection(key, boxes, sx << 4, sy << 4, sz << 4));
			}
		}
		LongArrayList release = new LongArrayList();
		for (long key : this.terrainBuilt) {
			if (this.ticks - this.terrainLastNeeded.getOrDefault(key, 0L) > TERRAIN_GRACE_TICKS) {
				release.add(key);
			}
		}
		for (long key : release) {
			this.terrainBuilt.remove(key);
			this.terrainLastNeeded.remove(key);
			this.world().submit(engine -> engine.removeStaticSection(key));
		}
	}

	private void releaseAllTerrain() {
		for (long key : this.terrainBuilt) {
			this.world().submit(engine -> engine.removeStaticSection(key));
		}
		this.terrainBuilt.clear();
		this.terrainLastNeeded.clear();
		this.terrainDirty.clear();
	}

	/** World-space axis-aligned bounds of a vessel's rotated block bounds: minX, minY, minZ, maxX, maxY, maxZ. */
	public static double[] worldBounds(VesselRecord record) {
		double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		Vector3d corner = new Vector3d();
		for (int i = 0; i < 8; i++) {
			double lx = (i & 1) == 0 ? record.localMin.getX() : record.localMax.getX() + 1;
			double ly = (i & 2) == 0 ? record.localMin.getY() : record.localMax.getY() + 1;
			double lz = (i & 4) == 0 ? record.localMin.getZ() : record.localMax.getZ() + 1;
			record.pose.localToWorld(lx, ly, lz, corner);
			out[0] = Math.min(out[0], corner.x);
			out[1] = Math.min(out[1], corner.y);
			out[2] = Math.min(out[2], corner.z);
			out[3] = Math.max(out[3], corner.x);
			out[4] = Math.max(out[4], corner.y);
			out[5] = Math.max(out[5], corner.z);
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Notifications from the manager and block-change hook
	// ---------------------------------------------------------------------------------------------------------

	/** A block changed in the world (outside any plot): rebuild the terrain body of its section if it has one. */
	public void onTerrainChanged(BlockPos pos) {
		long key = SectionPos.asLong(pos);
		if (this.terrainBuilt.contains(key)) {
			this.terrainDirty.add(key);
		}
	}

	void removeBody(ActiveVessel vessel) {
		if (vessel.hasBody && this.world != null) {
			long id = vessel.record.id;
			this.world.submit(engine -> engine.removeVessel(id));
		}
		vessel.hasBody = false;
		vessel.shapeDirty = true;
		vessel.poseValidFromStep = Long.MAX_VALUE;
	}

	void teleport(ActiveVessel vessel) {
		if (vessel.hasBody && this.world != null) {
			long id = vessel.record.id;
			VesselPose pose = vessel.record.pose;
			vessel.poseValidFromStep = this.world.nextStepIndex();
			this.world.submit(engine -> engine.teleportVessel(id, pose));
		}
	}

	/** Makes sure the record holds the latest physics result (before disassembly or saving). */
	void syncRecord(ActiveVessel vessel) {
		if (this.world != null) {
			this.world.await();
			if (vessel.hasBody) {
				this.applyResult(vessel, this.world.result(vessel.record.id));
			}
		}
	}

	void close() {
		if (this.world != null) {
			this.world.close();
			this.world = null;
		}
		this.terrainBuilt.clear();
		this.terrainLastNeeded.clear();
		this.terrainDirty.clear();
	}
}
