package dev.timstewart.slipway.physics.jolt;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShapeSettings;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.JoltPhysicsObject;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.RotatedTranslatedShapeSettings;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.ShapeResult;
import com.github.stephengold.joltjni.ShapeSettings;
import com.github.stephengold.joltjni.StaticCompoundShapeSettings;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.BoxList;
import dev.timstewart.slipway.physics.PhysicsEngine;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PhysicsEngine} on Jolt Physics. This object is the single owner of every native object it creates: the
 * physics system, allocators, job system and layer tables live as long as it does; shape settings, shape results
 * and body settings are closed right after use; shapes are reference counted and owned by their bodies, which are
 * destroyed when removed. {@link #close()} destroys all bodies and frees everything.
 */
public final class JoltEngine implements PhysicsEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger("Slipway/Physics");
	public static final int LAYER_STATIC = 0;
	public static final int LAYER_MOVING = 1;
	private static final float MAX_CONVEX_RADIUS = 0.05F;
	private static final float MIN_HALF_EXTENT = 0.001F;
	/** Process-wide counts of live engines and bodies, for the in-game leak checks. */
	private static final AtomicInteger LIVE_ENGINES = new AtomicInteger();
	private static final AtomicInteger LIVE_BODIES = new AtomicInteger();

	private final BroadPhaseLayerInterfaceTable layerMap;
	private final ObjectLayerPairFilterTable pairFilter;
	private final ObjectVsBroadPhaseLayerFilterTable broadPhaseFilter;
	private final PhysicsSystem system;
	private final TempAllocatorImpl tempAllocator;
	private final JobSystemThreadPool jobSystem;
	private final BodyInterface bodies;
	private final Long2IntMap vesselBodies = new Long2IntOpenHashMap();
	private final Long2IntMap staticBodies = new Long2IntOpenHashMap();
	private final RVec3 scratchPosition = new RVec3();
	private final Quat scratchRotation = new Quat();
	private final Vec3 scratchVector = new Vec3();
	private int staticChangesSinceOptimize;
	private boolean closed;

	public JoltEngine(int workerThreads) {
		this.layerMap = new BroadPhaseLayerInterfaceTable(2, 2);
		this.layerMap.mapObjectToBroadPhaseLayer(LAYER_STATIC, 0);
		this.layerMap.mapObjectToBroadPhaseLayer(LAYER_MOVING, 1);
		this.pairFilter = new ObjectLayerPairFilterTable(2);
		this.pairFilter.enableCollision(LAYER_MOVING, LAYER_STATIC);
		this.pairFilter.enableCollision(LAYER_MOVING, LAYER_MOVING);
		this.pairFilter.disableCollision(LAYER_STATIC, LAYER_STATIC);
		this.broadPhaseFilter = new ObjectVsBroadPhaseLayerFilterTable(this.layerMap, 2, this.pairFilter, 2);
		this.system = new PhysicsSystem();
		this.system.init(65536, 0, 65536, 32768, this.layerMap, this.broadPhaseFilter, this.pairFilter);
		this.system.setGravity(0f, -9.81f, 0f);
		this.tempAllocator = new TempAllocatorImpl(32 * 1024 * 1024);
		this.jobSystem = new JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, Math.max(1, workerThreads));
		this.bodies = this.system.getBodyInterface();
		this.vesselBodies.defaultReturnValue(Jolt.cInvalidBodyId);
		this.staticBodies.defaultReturnValue(Jolt.cInvalidBodyId);
		LIVE_ENGINES.incrementAndGet();
	}

	/** Engines created and not yet closed, in this process. */
	public static int liveEngines() {
		return LIVE_ENGINES.get();
	}

	/** Bodies created and not yet destroyed, in this process. */
	public static int liveBodies() {
		return LIVE_BODIES.get();
	}

	@Override
	public void setVesselShape(long vesselId, BoxList boxes, VesselPose pose, Vector3d linearVelocity, Vector3d angularVelocity) {
		this.checkOpen();
		ShapeRefC shape = createShape(boxes);
		if (shape == null) {
			this.removeVessel(vesselId);
			return;
		}
		try {
			int existing = this.vesselBodies.get(vesselId);
			if (existing != Jolt.cInvalidBodyId) {
				this.bodies.setShape(existing, shape, true, EActivation.Activate);
				return;
			}
			BodyCreationSettings settings = new BodyCreationSettings(shape, new RVec3(pose.x(), pose.y(), pose.z()),
				new Quat((float)pose.qx(), (float)pose.qy(), (float)pose.qz(), (float)pose.qw()), EMotionType.Dynamic, LAYER_MOVING);
			try {
				settings.setAllowSleeping(false);
				settings.setMotionQuality(EMotionQuality.LinearCast);
				settings.setLinearDamping(0.0f);
				settings.setAngularDamping(0.0f);
				settings.setFriction(0.6f);
				settings.setRestitution(0.05f);
				settings.setMaxLinearVelocity(120f);
				settings.setMaxAngularVelocity(12f);
				settings.setLinearVelocity(new Vec3((float)linearVelocity.x, (float)linearVelocity.y, (float)linearVelocity.z));
				settings.setAngularVelocity(new Vec3((float)angularVelocity.x, (float)angularVelocity.y, (float)angularVelocity.z));
				settings.setUserData(vesselId);
				int bodyId = this.bodies.createAndAddBody(settings, EActivation.Activate);
				LIVE_BODIES.incrementAndGet();
				this.vesselBodies.put(vesselId, bodyId);
			} finally {
				settings.close();
			}
		} finally {
			// The body holds its own counted reference to the shape.
			release(shape);
		}
	}

	@Override
	public void removeVessel(long vesselId) {
		int bodyId = this.vesselBodies.remove(vesselId);
		if (bodyId != Jolt.cInvalidBodyId) {
			this.bodies.removeBody(bodyId);
			this.bodies.destroyBody(bodyId);
			LIVE_BODIES.decrementAndGet();
		}
	}

	@Override
	public boolean hasVessel(long vesselId) {
		return this.vesselBodies.containsKey(vesselId);
	}

	@Override
	public void teleportVessel(long vesselId, VesselPose pose) {
		int bodyId = this.vesselBodies.get(vesselId);
		if (bodyId == Jolt.cInvalidBodyId) {
			return;
		}
		this.bodies.setPositionAndRotation(bodyId, pose.x(), pose.y(), pose.z(), (float)pose.qx(), (float)pose.qy(), (float)pose.qz(), (float)pose.qw(),
			EActivation.Activate);
		this.bodies.setLinearAndAngularVelocity(bodyId, 0f, 0f, 0f, 0f, 0f, 0f);
	}

	@Override
	public void applyForceAndTorque(long vesselId, Vector3d force, Vector3d torque) {
		int bodyId = this.vesselBodies.get(vesselId);
		if (bodyId == Jolt.cInvalidBodyId || !force.isFinite() || !torque.isFinite()) {
			return;
		}
		this.bodies.addForce(bodyId, (float)force.x, (float)force.y, (float)force.z);
		this.bodies.addTorque(bodyId, (float)torque.x, (float)torque.y, (float)torque.z);
	}

	@Override
	public void setStaticSection(long sectionKey, BoxList boxes, int originX, int originY, int originZ) {
		this.checkOpen();
		this.removeStaticSection(sectionKey);
		ShapeRefC shape = createShape(boxes);
		if (shape == null) {
			return;
		}
		try {
			BodyCreationSettings settings = new BodyCreationSettings(shape, new RVec3(originX, originY, originZ), new Quat(), EMotionType.Static, LAYER_STATIC);
			try {
				settings.setFriction(0.8f);
				settings.setRestitution(0.0f);
				int bodyId = this.bodies.createAndAddBody(settings, EActivation.DontActivate);
				LIVE_BODIES.incrementAndGet();
				this.staticBodies.put(sectionKey, bodyId);
				this.staticChangesSinceOptimize++;
			} finally {
				settings.close();
			}
		} finally {
			release(shape);
		}
	}

	@Override
	public void removeStaticSection(long sectionKey) {
		int bodyId = this.staticBodies.remove(sectionKey);
		if (bodyId != Jolt.cInvalidBodyId) {
			this.bodies.removeBody(bodyId);
			this.bodies.destroyBody(bodyId);
			LIVE_BODIES.decrementAndGet();
			this.staticChangesSinceOptimize++;
		}
	}

	@Override
	public boolean hasStaticSection(long sectionKey) {
		return this.staticBodies.containsKey(sectionKey);
	}

	@Override
	public void step(float seconds, int substeps) {
		this.checkOpen();
		if (this.staticChangesSinceOptimize > 64) {
			this.system.optimizeBroadPhase();
			this.staticChangesSinceOptimize = 0;
		}
		int errors = this.system.update(seconds, substeps, this.tempAllocator, this.jobSystem);
		if (errors != 0) {
			LOGGER.warn("Jolt reported update errors 0x{}", Integer.toHexString(errors));
		}
	}

	@Override
	public boolean readVessel(long vesselId, BodyState out) {
		int bodyId = this.vesselBodies.get(vesselId);
		if (bodyId == Jolt.cInvalidBodyId) {
			return false;
		}
		this.bodies.getPositionAndRotation(bodyId, this.scratchPosition, this.scratchRotation);
		out.x = this.scratchPosition.xx();
		out.y = this.scratchPosition.yy();
		out.z = this.scratchPosition.zz();
		out.qx = this.scratchRotation.getX();
		out.qy = this.scratchRotation.getY();
		out.qz = this.scratchRotation.getZ();
		out.qw = this.scratchRotation.getW();
		this.bodies.getLinearVelocity(bodyId, this.scratchVector);
		out.vx = this.scratchVector.getX();
		out.vy = this.scratchVector.getY();
		out.vz = this.scratchVector.getZ();
		this.bodies.getAngularVelocity(bodyId, this.scratchVector);
		out.wx = this.scratchVector.getX();
		out.wy = this.scratchVector.getY();
		out.wz = this.scratchVector.getZ();
		out.active = this.bodies.isActive(bodyId);
		return true;
	}

	@Override
	public int vesselCount() {
		return this.vesselBodies.size();
	}

	@Override
	public int staticSectionCount() {
		return this.staticBodies.size();
	}

	/** Builds a shape whose origin is the frame the boxes are expressed in; null when there is nothing solid. */
	@Nullable
	static ShapeRefC createShape(BoxList boxes) {
		List<ShapeSettings> parts = new ArrayList<>(boxes.size());
		List<float[]> centres = new ArrayList<>(boxes.size());
		try {
			for (int b = 0; b < boxes.size(); b++) {
				float hx = (boxes.maxX(b) - boxes.minX(b)) * 0.5F;
				float hy = (boxes.maxY(b) - boxes.minY(b)) * 0.5F;
				float hz = (boxes.maxZ(b) - boxes.minZ(b)) * 0.5F;
				float smallest = Math.min(hx, Math.min(hy, hz));
				if (smallest < MIN_HALF_EXTENT) {
					continue;
				}
				BoxShapeSettings box = new BoxShapeSettings(hx, hy, hz, Math.min(MAX_CONVEX_RADIUS, smallest * 0.9F));
				box.setDensity(boxes.density(b));
				parts.add(box);
				centres.add(new float[] {boxes.minX(b) + hx, boxes.minY(b) + hy, boxes.minZ(b) + hz});
			}
			if (parts.isEmpty()) {
				return null;
			}
			ShapeSettings root;
			if (parts.size() == 1) {
				float[] c = centres.getFirst();
				root = new RotatedTranslatedShapeSettings(c[0], c[1], c[2], 0f, 0f, 0f, 1f, parts.getFirst());
			} else {
				StaticCompoundShapeSettings compound = new StaticCompoundShapeSettings();
				for (int i = 0; i < parts.size(); i++) {
					float[] c = centres.get(i);
					compound.addShape(c[0], c[1], c[2], parts.get(i));
				}
				root = compound;
			}
			try (ShapeResult result = root.create()) {
				if (result.hasError()) {
					LOGGER.error("Jolt could not build a shape of {} boxes: {}", parts.size(), result.getError());
					return null;
				}
				return result.get();
			} finally {
				root.close();
			}
		} finally {
			for (ShapeSettings part : parts) {
				part.close();
			}
		}
	}

	/**
	 * Drops our counted reference to a shape and the automatic reference of the shape wrapper jolt-jni creates in
	 * {@code ShapeResult.get()}; without the second, every shape would stay alive forever (found by the leak test).
	 */
	static void release(ShapeRefC shape) {
		if (shape.getPtr() instanceof JoltPhysicsObject wrapper) {
			wrapper.close();
		}
		shape.close();
	}

	private void checkOpen() {
		if (this.closed) {
			throw new IllegalStateException("physics engine is closed");
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		for (long id : this.vesselBodies.keySet().toLongArray()) {
			this.removeVessel(id);
		}
		for (long key : this.staticBodies.keySet().toLongArray()) {
			this.removeStaticSection(key);
		}
		// jolt-jni keeps every PhysicsSystem in a static map (for PhysicsSystem.find) until forgetMe(); freeing the
		// native system does not remove it, so without this each closed engine stays reachable with its Java objects.
		this.system.forgetMe();
		this.system.close();
		this.jobSystem.close();
		this.tempAllocator.close();
		this.broadPhaseFilter.close();
		this.pairFilter.close();
		this.layerMap.close();
		LIVE_ENGINES.decrementAndGet();
	}
}
