package dev.timstewart.slipway.physics;

import dev.timstewart.slipway.physics.jolt.JoltEngine;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a {@link PhysicsEngine} on its own thread. The game thread queues commands during a tick and, at the
 * tick boundary, waits for the previous step, reads the snapshot it produced, and starts the next step with the
 * queued commands. The engine is created, used and closed only on the physics thread, which is therefore the
 * single owner of every native handle.
 */
public final class PhysicsWorld implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("Slipway/Physics");
	public static final float STEP_SECONDS = 0.05F;
	public static final int SUBSTEPS = 3;
	/** The step every world takes, as the controller needs to know it. */
	public static final VesselController.Step STEP = new VesselController.Step(STEP_SECONDS, SUBSTEPS);

	private final ExecutorService thread;
	private final String name;
	private PhysicsEngine engine;
	private List<Consumer<PhysicsEngine>> queued = new ArrayList<>();
	@Nullable
	private Future<?> inFlight;
	/** Written by the physics thread during a step, read by the game thread after {@link #await()}. */
	private final Long2ObjectMap<PhysicsEngine.BodyState> snapshot = new Long2ObjectOpenHashMap<>();
	private volatile long lastStepNanos;
	private volatile int lastVesselCount;
	private volatile int lastStaticCount;
	private volatile boolean failed;
	private boolean closed;
	/** Index of the next step to start; commands queued now run in that step. */
	private long nextStepIndex = 1;
	/** Index of the step whose results {@link #snapshot} holds. */
	private long snapshotStepIndex;

	public PhysicsWorld(String name, Supplier<PhysicsEngine> engineFactory) {
		this.name = name;
		this.thread = Executors.newSingleThreadExecutor(runnable -> {
			Thread t = new Thread(runnable, "Slipway Physics (" + name + ")");
			t.setDaemon(true);
			return t;
		});
		try {
			this.thread.submit(() -> this.engine = engineFactory.get()).get(30, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException error) {
			this.thread.shutdownNow();
			throw new IllegalStateException("Could not start the physics engine for " + name, error);
		}
	}

	public static PhysicsWorld jolt(String name) {
		return new PhysicsWorld(name, () -> new JoltEngine(2));
	}

	/** Queues a command for the next step (game thread). */
	public void submit(Consumer<PhysicsEngine> command) {
		if (!this.closed) {
			this.queued.add(command);
		}
	}

	/**
	 * Starts a step (game thread): runs queued commands, lets {@code beforeStep} apply control forces with the
	 * engine's current state, advances the simulation, and snapshots the given vessels.
	 */
	public void startStep(long[] vessels, Consumer<PhysicsEngine> beforeStep) {
		if (this.closed || this.failed) {
			return;
		}
		if (this.inFlight != null) {
			throw new IllegalStateException("previous physics step was not awaited");
		}
		List<Consumer<PhysicsEngine>> commands = this.queued;
		this.queued = new ArrayList<>();
		long stepIndex = this.nextStepIndex++;
		this.inFlight = this.thread.submit(() -> {
			long start = System.nanoTime();
			try {
				for (Consumer<PhysicsEngine> command : commands) {
					command.accept(this.engine);
				}
				beforeStep.accept(this.engine);
				this.engine.step(STEP_SECONDS, SUBSTEPS);
				this.snapshot.clear();
				for (long id : vessels) {
					PhysicsEngine.BodyState state = new PhysicsEngine.BodyState();
					if (this.engine.readVessel(id, state)) {
						this.snapshot.put(id, state);
					}
				}
				this.snapshotStepIndex = stepIndex;
				this.lastVesselCount = this.engine.vesselCount();
				this.lastStaticCount = this.engine.staticSectionCount();
			} catch (Throwable error) {
				this.failed = true;
				LOGGER.error("Physics step failed in {}; vessels are frozen until the level reloads", this.name, error);
			} finally {
				this.lastStepNanos = System.nanoTime() - start;
			}
		});
	}

	/** Waits for the step in flight, if any (game thread). */
	public void await() {
		Future<?> future = this.inFlight;
		if (future == null) {
			return;
		}
		this.inFlight = null;
		try {
			future.get(10, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException error) {
			this.failed = true;
			LOGGER.error("Physics step did not complete in {}", this.name, error);
		}
	}

	/** The state of a vessel after the last completed step, or null. Valid after {@link #await()}. */
	public PhysicsEngine.@Nullable BodyState result(long vesselId) {
		return this.snapshot.get(vesselId);
	}

	/** Index of the step that commands queued now will run in. */
	public long nextStepIndex() {
		return this.nextStepIndex;
	}

	/** Index of the step whose results {@link #result} returns. Valid after {@link #await()}. */
	public long snapshotStepIndex() {
		return this.snapshotStepIndex;
	}

	public long lastStepNanos() {
		return this.lastStepNanos;
	}

	public int vesselBodies() {
		return this.lastVesselCount;
	}

	public int staticBodies() {
		return this.lastStaticCount;
	}

	public boolean failed() {
		return this.failed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.await();
		this.closed = true;
		this.queued.clear();
		try {
			this.thread.submit(() -> {
				if (this.engine != null) {
					this.engine.close();
					this.engine = null;
				}
			}).get(30, TimeUnit.SECONDS);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException error) {
			LOGGER.error("Could not close the physics engine of {}", this.name, error);
		} finally {
			this.thread.shutdown();
			try {
				if (!this.thread.awaitTermination(10, TimeUnit.SECONDS)) {
					this.thread.shutdownNow();
				}
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
