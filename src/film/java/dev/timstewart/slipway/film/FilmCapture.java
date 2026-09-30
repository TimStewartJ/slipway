package dev.timstewart.slipway.film;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Renders one frame at a chosen partial tick and reads it back without letting the game tick. (Fabric's
 * takeScreenshot waits game ticks until the GPU readback completes, so every screenshot would advance the world.)
 * Runs on the render thread; PNG encoding happens on background threads.
 */
final class FilmCapture implements AutoCloseable {
	private final ExecutorService writers = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "slipway-film-writer");
		t.setDaemon(true);
		return t;
	});
	private final Semaphore inFlight = new Semaphore(24);
	private final AtomicReference<Throwable> failure = new AtomicReference<>();

	/** A frame's timing: {@code frameTicks} long, drawn at {@code partialTick} between the previous tick and this one. */
	private record FrameTime(float partialTick, float frameTicks) implements DeltaTracker {
		@Override
		public float getGameTimeDeltaTicks() {
			return this.frameTicks;
		}

		@Override
		public float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame) {
			return this.partialTick;
		}

		@Override
		public float getRealtimeDeltaTicks() {
			return this.frameTicks;
		}
	}

	/** Renders one frame at a partial tick without reading it back (primes temporal effects); call on the render thread. */
	void render(Minecraft mc, float partialTick, float frameTicks) {
		FrameTime time = new FrameTime(partialTick, frameTicks);
		FilmClock.seconds = FilmCamera.time(partialTick) / 20.0 + FilmClock.offsetSeconds;
		FilmClock.frameSeconds = frameTicks / 20.0f;
		FilmClock.frameActive = true;
		try {
			mc.gameRenderer.update(time);
			mc.gameRenderer.extract(time, true);
			mc.gameRenderer.render();
		} finally {
			FilmClock.frameActive = false;
		}
		RenderSystem.getDevice().createCommandEncoder().submit();
	}

	/** Renders and saves one frame; call on the render thread. */
	void capture(Minecraft mc, float partialTick, float frameTicks, Path file) {
		Throwable earlier = this.failure.get();
		if (earlier != null) {
			throw new IllegalStateException("writing an earlier frame failed", earlier);
		}
		this.render(mc, partialTick, frameTicks);
		AtomicReference<NativeImage> result = new AtomicReference<>();
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), result::set);
		// The readback's fence belongs to the next submission, which the render loop would send with the next frame;
		// submit ourselves (each submit waits for the one two back) until its callback has run.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (result.get() == null) {
			RenderSystem.getDevice().createCommandEncoder().submit();
			RenderSystem.executePendingTasks();
			if (result.get() == null && System.nanoTime() > deadline) {
				throw new IllegalStateException("the GPU readback of a film frame did not complete in 30 s");
			}
		}
		NativeImage image = result.get();
		this.inFlight.acquireUninterruptibly();
		this.writers.execute(() -> {
			try (image) {
				image.writeToFile(file);
			} catch (IOException | RuntimeException e) {
				this.failure.compareAndSet(null, e);
			} finally {
				this.inFlight.release();
			}
		});
	}

	@Override
	public void close() {
		this.writers.shutdown();
		try {
			if (!this.writers.awaitTermination(5, TimeUnit.MINUTES)) {
				throw new IllegalStateException("film frames were still being written after 5 minutes");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		Throwable f = this.failure.get();
		if (f != null) {
			throw f instanceof IOException io ? new UncheckedIOException(io) : new IllegalStateException(f);
		}
	}
}
