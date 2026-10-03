package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.BoxList;
import dev.timstewart.slipway.physics.Buoyancy;
import dev.timstewart.slipway.physics.FluidField;
import dev.timstewart.slipway.physics.Hull;
import dev.timstewart.slipway.physics.PhysicsEngine;
import dev.timstewart.slipway.physics.PhysicsWorld;
import dev.timstewart.slipway.physics.Rig;
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
	/** Sections built in one tick beyond that for bodies made in this tick (see {@link ActiveVessel#surroundingsUrgent}). */
	private static final int URGENT_BUILDS_PER_TICK = 512;
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
	/**
	 * The water and lava in the terrain sections around the vessels, for buoyancy. Built here with each section's
	 * collision boxes and handed over with a physics command; only the physics thread reads and writes it.
	 */
	private final FluidField fluids = new FluidField();
	private final Buoyancy.Scratch buoyancyScratch = new Buoyancy.Scratch();
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
				vessel.rememberPose();
				if (vessel.hasBody) {
					this.applyResult(vessel, this.world.result(vessel.record.id));
					vessel.buoyancy.copyFrom(vessel.buoyancyStep);
				} else {
					vessel.buoyancy.clear();
				}
			}
		} else {
			for (ActiveVessel vessel : this.manager.activeVessels()) {
				vessel.rememberPose();
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
			if (vessel.hasBody && vessel.bodyLoose != vessel.record.loose) {
				long id = vessel.record.id;
				boolean loose = vessel.record.loose;
				vessel.bodyLoose = loose;
				vessel.bodyAwake = true;
				this.world().submit(engine -> engine.setVesselLoose(id, loose));
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
			vessel.holdReset = true;
			this.world().submit(engine -> engine.teleportVessel(record.id, last));
			record.linearVelocity = Vec3.ZERO;
			record.angularVelocity = Vec3.ZERO;
			return;
		}
		vessel.bodyAwake = state.active;
		record.pose = state.pose();
		record.linearVelocity = new Vec3(state.vx, state.vy, state.vz);
		record.angularVelocity = new Vec3(state.wx, state.wy, state.wz);
	}

	/** The hull of a vessel's blocks as they are in its plot now; null while a chunk of the plot is not loaded. */
	@Nullable
	Hull hullNow(VesselRecord record) {
		ServerLevel level = this.manager.level();
		BoxList unused = new BoxList();
		Hull.Builder hull = new Hull.Builder();
		BlockPos min = record.plotMin();
		BlockPos max = record.plotMax();
		for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
			for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					return null;
				}
				for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
					unused.clear();
					this.sectionShapes.build(level, chunk, sy, record.anchor.getX(), record.anchor.getY(), record.anchor.getZ(), unused, hull);
				}
			}
		}
		return hull.build();
	}

	/** Rebuilds the vessel's collision boxes from its plot and replaces its body shape. */
	private void rebuildShape(ActiveVessel vessel) {
		VesselRecord record = vessel.record;
		ServerLevel level = this.manager.level();
		BoxList boxes = new BoxList();
		Hull.Builder hull = new Hull.Builder();
		Rig.Builder rig = new Rig.Builder();
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
					blocks += this.sectionShapes.build(level, chunk, sy, record.anchor.getX(), record.anchor.getY(), record.anchor.getZ(), boxes, hull, rig);
				}
			}
		}
		vessel.shapeDirty = false;
		vessel.infoDirty = true;
		record.blockCount = blocks;
		BoxList.MassProperties mass = boxes.massProperties();
		vessel.mass = mass;
		// The same blocks displace the same water: a lever thrown or a chest opened builds no new hull.
		long fingerprint = hull.fingerprint();
		if (fingerprint != vessel.hullFingerprint || vessel.hull == Hull.EMPTY) {
			vessel.hull = hull.build();
			vessel.hullFingerprint = fingerprint;
		}
		// The air it holds from rising away matters only over a burner; the flood for it is run for the same blocks once.
		if (!rig.hasBurners()) {
			vessel.envelope = null;
		} else if (vessel.envelope == null || rig.fingerprint() != vessel.envelopeFingerprint) {
			vessel.envelope = rig.envelope();
			vessel.envelopeFingerprint = rig.fingerprint();
		}
		vessel.rig = rig.build(vessel.hull, vessel.envelope);
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
			vessel.bodyLoose = false;
			vessel.holdReset = true;
			vessel.surroundingsUrgent = true;
		}
		this.world().submit(engine -> engine.setVesselShape(id, boxes, pose, velocity, angular));
		vessel.hasBody = true;
	}

	private void startStep() {
		SlipwayConfig config = SlipwayConfig.get();
		VesselController.Params params = new VesselController.Params(config.thrustAcceleration, config.maxSpeed, config.angularAcceleration,
			config.maxTurnRate, config.levelStrength);
		Rig.Rules rules = config.rigRules();
		List<VesselController.Drive> drives = new ArrayList<>();
		List<Afloat> floats = new ArrayList<>();
		LongArrayList ids = new LongArrayList();
		for (ActiveVessel vessel : this.manager.activeVessels()) {
			if (!vessel.hasBody || vessel.mass == null) {
				continue;
			}
			VesselRecord record = vessel.record;
			ids.add(record.id);
			Vector3d forward = new Vector3d(record.helmFacing.getOpposite().getStepX(), 0, record.helmFacing.getOpposite().getStepZ());
			HelmInput in = vessel.input;
			VesselController.Rating rating = record.free ? null
				: vessel.rig.rating(rules, vessel.mass.mass(), config.thrustAcceleration, vessel.buoyancy.applied ? vessel.buoyancy.displacedMass : 0.0);
			VesselController.Drive drive = new VesselController.Drive(record.id, new VesselController.Axes(in.forward, in.strafe, in.vertical, in.pitch, in.yaw, in.roll),
				record.hover, record.level, record.loose, vessel.holdReset, vessel.mass, forward, vessel.brakeOnly ? null : vessel.hold, rating);
			drives.add(drive);
			vessel.holdReset = false;
			// Hover cancels gravity, and with it what makes things float: a vessel that hover holds up is left alone in
			// water too. One that asks for hover without the lift for its weight floats or sinks like any other.
			floats.add(new Afloat(record.id, vessel.hull, vessel.mass, forward, record.loose || !drive.hovering(), vessel.buoyancyStep));
		}
		Buoyancy.Params water = new Buoyancy.Params(config.buoyancy, config.waterDrag);
		PhysicsEngine.BodyState scratch = new PhysicsEngine.BodyState();
		this.world().startStep(ids.toLongArray(), engine -> {
			for (VesselController.Drive drive : drives) {
				VesselController.drive(engine, params, PhysicsWorld.STEP, drive, scratch);
			}
			for (Afloat afloat : floats) {
				Buoyancy.apply(engine, this.fluids, water, PhysicsWorld.STEP, afloat.id(), afloat.hull(), afloat.mass(), afloat.forward(), afloat.forces(),
					afloat.state(), this.buoyancyScratch);
			}
		});
	}

	/** What the physics thread needs to float one vessel for one step. */
	private record Afloat(long id, Hull hull, BoxList.MassProperties mass, Vector3d forward, boolean forces, Buoyancy.State state) {
	}

	// ---------------------------------------------------------------------------------------------------------
	// Terrain
	// ---------------------------------------------------------------------------------------------------------

	private void updateTerrain() {
		ServerLevel level = this.manager.level();
		LongOpenHashSet needed = new LongOpenHashSet();
		LongOpenHashSet urgent = new LongOpenHashSet();
		for (ActiveVessel vessel : this.manager.activeVessels()) {
			if (!vessel.hasBody) {
				continue;
			}
			boolean isNew = vessel.surroundingsUrgent;
			vessel.surroundingsUrgent = false;
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
						if (isNew) {
							urgent.add(SectionPos.asLong(sx, sy, sz));
						}
					}
				}
			}
		}
		int builds = 0;
		int urgentBuilds = 0;
		for (long key : needed) {
			this.terrainLastNeeded.put(key, this.ticks);
			boolean dirty = this.terrainDirty.remove(key);
			if (this.terrainBuilt.contains(key) && !dirty) {
				continue;
			}
			if (builds >= TERRAIN_BUILDS_PER_TICK && !(urgent.contains(key) && urgentBuilds < URGENT_BUILDS_PER_TICK)) {
				if (dirty) {
					this.terrainDirty.add(key);
				}
				continue;
			}
			if (builds >= TERRAIN_BUILDS_PER_TICK) {
				urgentBuilds++;
			}
			int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
			LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
			if (chunk == null) {
				continue;
			}
			BoxList boxes = new BoxList();
			this.sectionShapes.build(level, chunk, sy, sx << 4, sy << 4, sz << 4, boxes);
			byte[] fluid = SectionShapes.fluids(chunk, sy);
			this.terrainBuilt.add(key);
			builds++;
			if (boxes.isEmpty()) {
				this.world().submit(engine -> engine.removeStaticSection(key));
			} else {
				this.world().submit(engine -> engine.setStaticSection(key, boxes, sx << 4, sy << 4, sz << 4));
			}
			this.world().submit(engine -> {
				// Jolt wakes a sleeping body when a body near it changes; water is no body, so a vessel asleep on water
				// that is drained (or on a floor that is flooded) is woken here.
				if (this.fluids.put(key, fluid)) {
					engine.wakeLooseVessels(sx << 4, sy << 4, sz << 4, (sx << 4) + 16, (sy << 4) + 16, (sz << 4) + 16);
				}
			});
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
			this.world().submit(engine -> {
				engine.removeStaticSection(key);
				this.fluids.remove(key);
			});
		}
	}

	private void releaseAllTerrain() {
		for (long key : this.terrainBuilt) {
			this.world().submit(engine -> engine.removeStaticSection(key));
		}
		this.world().submit(engine -> this.fluids.clear());
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
		vessel.buoyancy.clear();
		vessel.bodyLoose = false;
		vessel.bodyAwake = true;
		vessel.shapeDirty = true;
		vessel.poseValidFromStep = Long.MAX_VALUE;
	}

	void teleport(ActiveVessel vessel) {
		if (vessel.hasBody && this.world != null) {
			long id = vessel.record.id;
			VesselPose pose = vessel.record.pose;
			vessel.poseValidFromStep = this.world.nextStepIndex();
			vessel.holdReset = true;
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
			// The physics thread has ended: nothing else touches the fluids now.
			this.fluids.clear();
		}
		this.terrainBuilt.clear();
		this.terrainLastNeeded.clear();
		this.terrainDirty.clear();
	}
}
