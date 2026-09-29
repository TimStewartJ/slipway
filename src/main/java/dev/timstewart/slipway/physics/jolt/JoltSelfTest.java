package dev.timstewart.slipway.physics.jolt;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShapeSettings;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.ShapeRefC;
import com.github.stephengold.joltjni.StaticCompoundShapeSettings;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionType;

/**
 * A self-contained Jolt simulation used to prove that the native library works on this machine: a compound
 * body made of boxes falls onto a static floor far from the origin (where single precision would fail) and
 * comes to rest. Every native object it creates is closed before it returns.
 */
public final class JoltSelfTest {
	private JoltSelfTest() {
	}

	public record Result(double restY, double expectedRestY, int steps, long nanos, boolean doublePrecision) {
		public boolean passed() {
			return Math.abs(this.restY - this.expectedRestY) < 0.05;
		}
	}

	public static Result run() {
		final double baseX = 27_000_000.5;
		final double baseZ = -27_000_000.5;
		final double floorTop = 64.0;
		long start = System.nanoTime();
		BroadPhaseLayerInterfaceTable layerMap = new BroadPhaseLayerInterfaceTable(2, 2);
		ObjectLayerPairFilterTable pairFilter = new ObjectLayerPairFilterTable(2);
		ObjectVsBroadPhaseLayerFilterTable bpFilter = null;
		PhysicsSystem system = new PhysicsSystem();
		TempAllocatorImpl temp = new TempAllocatorImpl(4 * 1024 * 1024);
		JobSystemThreadPool jobs = new JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, 1);
		BoxShapeSettings floorShape = new BoxShapeSettings(64f, 0.5f, 64f);
		StaticCompoundShapeSettings hull = new StaticCompoundShapeSettings();
		BoxShapeSettings deck = new BoxShapeSettings(2.5f, 0.5f, 1.5f, 0.05f);
		BoxShapeSettings mast = new BoxShapeSettings(0.5f, 1.5f, 0.5f, 0.05f);
		BodyCreationSettings floorSettings = null;
		BodyCreationSettings hullSettings = null;
		ShapeRefC hullShape = null;
		int floorId = Jolt.cInvalidBodyId;
		int hullId = Jolt.cInvalidBodyId;
		int steps = 0;
		double restY;
		try {
			layerMap.mapObjectToBroadPhaseLayer(0, 0);
			layerMap.mapObjectToBroadPhaseLayer(1, 1);
			pairFilter.enableCollision(1, 0);
			pairFilter.enableCollision(1, 1);
			pairFilter.disableCollision(0, 0);
			bpFilter = new ObjectVsBroadPhaseLayerFilterTable(layerMap, 2, pairFilter, 2);
			system.init(64, 0, 256, 256, layerMap, bpFilter, pairFilter);
			system.setGravity(0f, -9.81f, 0f);
			BodyInterface bodies = system.getBodyInterface();

			floorSettings = new BodyCreationSettings(floorShape, new RVec3(baseX, floorTop - 0.5, baseZ), new Quat(), EMotionType.Static, 0);
			floorId = bodies.createAndAddBody(floorSettings, EActivation.DontActivate);

			// Deck 5x1x3 centred on the origin, mast 1x3x1 standing on it: the lowest point is 0.5 below the origin.
			hull.addShape(0f, 0f, 0f, deck);
			hull.addShape(0f, 2f, 0f, mast);
			hullShape = hull.create().get();
			hullSettings = new BodyCreationSettings(hullShape, new RVec3(baseX, floorTop + 10.0, baseZ), new Quat(), EMotionType.Dynamic, 1);
			hullId = bodies.createAndAddBody(hullSettings, EActivation.Activate);

			for (; steps < 20 * 6; steps++) {
				int errors = system.update(0.05f, 3, temp, jobs);
				if (errors != 0) {
					throw new IllegalStateException("Jolt update reported errors " + errors);
				}
			}
			RVec3 position = bodies.getPosition(hullId);
			restY = position.yy();
			if (Math.abs(position.xx() - baseX) > 0.25 || Math.abs(position.zz() - baseZ) > 0.25) {
				throw new IllegalStateException("The body drifted sideways to " + position);
			}
			bodies.removeBody(hullId);
			bodies.destroyBody(hullId);
			hullId = Jolt.cInvalidBodyId;
			bodies.removeBody(floorId);
			bodies.destroyBody(floorId);
			floorId = Jolt.cInvalidBodyId;
		} finally {
			if (hullSettings != null) {
				hullSettings.close();
			}
			if (floorSettings != null) {
				floorSettings.close();
			}
			if (hullShape != null) {
				hullShape.close();
			}
			hull.close();
			deck.close();
			mast.close();
			floorShape.close();
			system.close();
			jobs.close();
			temp.close();
			if (bpFilter != null) {
				bpFilter.close();
			}
			pairFilter.close();
			layerMap.close();
		}
		return new Result(restY, floorTop + 0.5, steps, System.nanoTime() - start, Jolt.isDoublePrecision());
	}
}
