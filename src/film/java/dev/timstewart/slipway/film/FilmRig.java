package dev.timstewart.slipway.film;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Options;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.phys.Vec3;

/** Game setup and frame capture for the film. */
final class FilmRig {
	private FilmRig() {
	}

	static int[] size() {
		String[] parts = System.getProperty("slipway.film.size", "1080x1350").toLowerCase(Locale.ROOT).split("x");
		return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
	}

	/** A shot option from {@code -PslipwayFilmOpts=k=v,k=v}, or {@code fallback}. */
	static String opt(String key, String fallback) {
		for (String pair : System.getProperty("slipway.film.opts", "").split(",")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).trim().equals(key)) {
				return pair.substring(eq + 1).trim();
			}
		}
		return fallback;
	}

	static double optDouble(String key, double fallback) {
		return Double.parseDouble(opt(key, Double.toString(fallback)));
	}

	/** The film's world: normal worldgen (not the GameTest superflat) with a fixed seed, creative, commands on. */
	static net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext scenicWorld(ClientGameTestContext ctx, String seed) {
		return ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> {
			s.setSeed(seed);
			s.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE);
			s.setAllowCommands(true);
			s.setBonusChest(false);
			s.setName("film-" + seed);
		}).create();
	}

	/** Freezes the scene: fixed time of day, clear weather, no mobs spawning, no random ticks (leaves stay put). */
	static void freezeWorld(net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server, long dayTime) {
		server.runCommand("gamerule advance_time false");
		server.runCommand("gamerule advance_weather false");
		server.runCommand("gamerule spawn_mobs false");
		server.runCommand("gamerule spawn_monsters false");
		server.runCommand("gamerule spawn_patrols false");
		server.runCommand("gamerule spawn_phantoms false");
		server.runCommand("gamerule spawn_wandering_traders false");
		server.runCommand("gamerule random_tick_speed 0");
		server.runCommand("gamerule send_command_feedback false");
		server.runCommand("gamerule show_advancement_messages false");
		server.runCommand("weather clear");
		server.runCommand("time set " + dayTime);
		server.runCommand("gamemode spectator @a");
	}

	static int subframes() {
		return Integer.parseInt(System.getProperty("slipway.film.subframes", "3"));
	}

	static Path outDir(String shot) {
		int[] s = size();
		Path dir = Path.of(System.getProperty("slipway.film.out", "film-out")).resolve(shot + "-" + s[0] + "x" + s[1]).toAbsolutePath();
		try {
			if (Files.isDirectory(dir)) {
				try (var old = Files.list(dir)) {
					for (Path p : (Iterable<Path>)old::iterator) {
						Files.delete(p);
					}
				}
			}
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return dir;
	}

	/** Film options: no HUD, hand or chat, vanilla clouds off (Bliss draws its own), a long view, no frame cap. */
	static void options(ClientGameTestContext ctx, int renderDistance) {
		ctx.restoreDefaultGameOptions();
		options(ctx, o -> {
			o.cloudStatus().set(CloudStatus.OFF);
			o.framerateLimit().set(260);
			o.enableVsync().set(false);
			o.pauseOnLostFocus = false;
			o.renderDistance().set(renderDistance);
			o.simulationDistance().set(Math.min(renderDistance, 12));
			o.chatVisibility().set(ChatVisiblity.HIDDEN);
			o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
			o.fov().set(70);
			o.bobView().set(false);
		});
		ctx.runOnClient(mc -> {
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		});
	}

	static void options(ClientGameTestContext ctx, Consumer<Options> change) {
		ctx.runOnClient(mc -> {
			change.accept(mc.options);
			mc.options.save();
		});
	}

	/** Waits until Iris runs the Bliss pack. */
	static void waitShaders(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> IrisApi.getInstance().getConfig().setShadersEnabledAndApply(true));
		ctx.waitFor(mc -> mc.level != null && IrisApi.getInstance().isShaderPackInUse(), 2400);
	}

	/**
	 * Keeps the (spectating) player at the camera so the server loads and sends the terrain around it. The camera
	 * itself is placed by FilmCamera; the player is never in view.
	 */
	static void followCamera(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			FilmCamera.Frame f = FilmCamera.frame(1.0f);
			if (f != null && mc.player != null) {
				mc.player.setPos(f.position().x, f.position().y - mc.player.getEyeHeight(), f.position().z);
				mc.player.setDeltaMovement(Vec3.ZERO);
			}
		});
	}

	/**
	 * Waits until the client has every chunk in a circle around the player and the renderer has built the terrain in
	 * view (Sodium reports its own state).
	 */
	static void waitWorld(ClientGameTestContext ctx, int timeout) {
		ctx.waitFor(mc -> {
			if (mc.level == null || mc.player == null) {
				return false;
			}
			int r = Math.max(1, mc.options.getEffectiveRenderDistance() - 1);
			int cx = net.minecraft.core.SectionPos.blockToSectionCoord(mc.player.getBlockX());
			int cz = net.minecraft.core.SectionPos.blockToSectionCoord(mc.player.getBlockZ());
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dz * dz <= r * r
						&& mc.level.getChunkSource().getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) {
						return false;
					}
				}
			}
			return true;
		}, timeout);
		boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
		ctx.waitFor(mc -> mc.level != null && (sodium ? SodiumTerrain.complete() : mc.levelRenderer.hasRenderedAllSections()), timeout);
	}

	private static final class SodiumTerrain {
		static boolean complete() {
			var renderer = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
			return renderer != null && renderer.isTerrainRenderComplete();
		}
	}

	/** Records frames: {@code subframes} per tick, each at its partial tick, plus a CSV log of what each frame shows. */
	static final class Recorder implements AutoCloseable {
		interface Probe {
			/** Extra CSV columns for a frame, computed on the client thread at the frame's partial tick. */
			String columns(net.minecraft.client.Minecraft mc, float partialTick);
		}

		private final ClientGameTestContext ctx;
		private final Path dir;
		private final int subframes;
		private final BufferedWriter log;
		private final Probe probe;
		private final FilmCapture capture = new FilmCapture();
		private int frame;
		private long renderNanos;

		Recorder(ClientGameTestContext ctx, Path dir, String probeHeader, Probe probe) {
			this.ctx = ctx;
			this.dir = dir;
			this.subframes = subframes();
			this.probe = probe;
			try {
				this.log = Files.newBufferedWriter(dir.resolve("frames.csv"), StandardCharsets.UTF_8);
				this.log.write("frame,tick,partial,time,camX,camY,camZ,yaw,pitch,roll," + probeHeader + ",millis\n");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		/** Waits one tick (the scene advances), then renders this tick's frames. */
		void tick() {
			this.ctx.waitTick();
			FilmCamera.tick();
			FilmRig.followCamera(this.ctx);
			for (int k = 1; k <= this.subframes; k++) {
				float partial = (float)k / this.subframes;
				Path file = this.dir.resolve(String.format(Locale.ROOT, "f%06d.png", this.frame));
				String row = this.ctx.computeOnClient(mc -> {
					FilmCamera.Frame f = FilmCamera.frame(partial);
					String cam = f == null ? ",,,,," : String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.4f,%.4f,%.4f", f.position().x, f.position().y, f.position().z, f.yaw(), f.pitch(), f.roll());
					String r = String.format(Locale.ROOT, "%d,%d,%.5f,%.5f,%s,%s", this.frame, FilmCamera.ticks(), partial, FilmCamera.time(partial), cam, this.probe.columns(mc, partial));
					long start = System.nanoTime();
					this.capture.capture(mc, partial, 1.0f / this.subframes, file);
					long nanos = System.nanoTime() - start;
					this.renderNanos += nanos;
					return r + "," + (nanos / 1_000_000);
				});
				try {
					this.log.write(row + "\n");
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				this.frame++;
			}
		}

		int frames() {
			return this.frame;
		}

		double meanFrameMillis() {
			return this.frame == 0 ? 0 : this.renderNanos / 1.0e6 / this.frame;
		}

		@Override
		public void close() {
			this.capture.close();
			try {
				this.log.close();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
