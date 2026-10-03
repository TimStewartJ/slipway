package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.vessel.VesselManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;

/**
 * Diagnostic scenarios: not part of the suite. They run only when named in {@code -PslipwayClientGametestOnly} and
 * take pictures of a situation reported from play, changing one factor at a time; they assert only their setup.
 */
final class DiagScenarios {
	private DiagScenarios() {
	}

	/**
	 * diag-plain-ship: a copy of a play-instance Slipway Sandbox ({@code -PslipwayDiagWorld=<save folder>}) with the
	 * player's graphics settings ({@code -PslipwayDiagPackOptions=<Bliss options file>}): the plain skiff (helm 8 81 0)
	 * next to vessel #1 put back where it was built (helm -8 81 0), both seen from the same viewpoints with Bliss on,
	 * again after half a minute, after shader reloads with a static camera (with the pack option variants of
	 * {@code -PslipwayDiagVariants}), and with shaders off. {@code -PslipwayDiagFreshLods=true} drops the saved LOD
	 * databases; {@code -PslipwayTestDhJar} picks the Distant Horizons build. Built to find the blotchy dark lighting on
	 * world blocks under shaders (DESIGN.md, "Dark blotches under shaders"); it reports, it does not assert.
	 */
	static void plainShip(ClientGameTestContext ctx, Report.Result r) throws Exception {
		String world = System.getProperty("slipway.diag.world", "");
		Check.that(!world.isEmpty(), "set -PslipwayDiagWorld=<save folder>");
		Path source = Path.of(world);
		Check.that(Files.isRegularFile(source.resolve("level.dat")), "not a save folder: %s", source);
		Path saves = ctx.computeOnClient(mc -> mc.getLevelSource().getBaseDir());
		Path copy = saves.resolve("diag-world");
		delete(copy);
		copy(source, copy);
		Files.deleteIfExists(copy.resolve("session.lock"));
		if (Boolean.getBoolean("slipway.diag.freshLods")) {
			// Drop the saved LOD databases so the Distant Horizons build under test generates its own.
			try (Stream<Path> files = Files.walk(copy)) {
				for (Path p : files.filter(p -> p.getFileName().toString().startsWith("DistantHorizons.sqlite")).toList()) {
					Files.delete(p);
					r.note("deleted %s", copy.relativize(p));
				}
			}
		}
		// The player's settings (their options.txt): render distance 6, smooth lighting, field of view 100.
		Game.options(ctx, o -> {
			o.renderDistance().set(6);
			o.simulationDistance().set(6);
			o.ambientOcclusion().set(true);
			o.fov().set(100);
		});
		String packOptions = System.getProperty("slipway.diag.packOptions", "");
		if (!packOptions.isEmpty()) {
			// The player's Bliss options (clouds moving, cloud shadows on) instead of the test run's frozen clouds.
			Path packs = SlipwayClientGameTests.reportDir().resolve("shaderpacks");
			try (Stream<Path> files = Files.list(packs)) {
				Path options = files.filter(p -> p.getFileName().toString().endsWith(".zip.txt")).findFirst().orElseThrow();
				Files.copy(Path.of(packOptions), options, StandardCopyOption.REPLACE_EXISTING);
				r.note("Bliss options from %s", packOptions);
			}
		}
		ctx.getInput().resizeWindow(1280, 720);
		try (TestSingleplayerContext sp = new TestWorldSaveImpl(ctx, copy).open()) {
			try {
				views(ctx, sp, r);
			} finally {
				RenderScenarios.shaders(ctx, r, false);
				dhRendering(ctx, true);
			}
		} finally {
			ctx.getInput().resizeWindow(854, 480);
		}
	}

	private static void views(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r) {
		TestServerContext server = sp.getServer();
		server.runCommand("gamerule advance_time false");
		dhClouds(ctx, true);
		Game.hud(ctx, false);
		// Vessel #1 is wherever the player left it: go there, then put it back where it was built, hovering and level.
		VesselPoint at = server.computeOnServer(s -> {
			var v = VesselManager.get(s.overworld()).registry().get(1L);
			return v == null ? null : new VesselPoint(v.pose.x(), v.pose.y(), v.pose.z());
		});
		Check.notNull(at, "vessel 1 is not in the world copy");
		Game.teleport(ctx, sp, at.x(), at.y() + 6, at.z() - 12, 0f, 20f);
		server.waitFor(s -> VesselManager.get(s.overworld()).active(1L) != null, 400);
		server.runCommand("slipway mode 1 hover true");
		server.runCommand("slipway mode 1 level true");
		server.runCommand("slipway pose 1 -8 81 0 0 0 0");
		ctx.waitTicks(20);
		r.note("vessel 1 was at %.1f %.1f %.1f; moved back to -8 81 0", at.x(), at.y(), at.z());

		RenderScenarios.shaders(ctx, r, true);
		server.runCommand("time set 3000");
		view(ctx, sp, r, "left-t3000", 9.0, 85.5, -8.0, 0f, 22f);
		view(ctx, sp, r, "right-t3000", -7.0, 85.5, -8.0, 0f, 22f);
		lightValues(ctx, r);
		// Does the first-load state persist? Same view again after half a minute, with no reload.
		ctx.waitTicks(600);
		view(ctx, sp, r, "left-t3000-later", 9.0, 85.5, -8.0, 0f, 22f);
		if (Boolean.getBoolean("slipway.diag.series")) {
			// The plain skiff every 20 ticks after a long move (teleport far away and back) and after a shader reload.
			view(ctx, sp, r, "series-away", -150.0, 110.0, -30.0, 0f, 22f);
			series(ctx, sp, r, "series-after-move");
			RenderScenarios.shaders(ctx, r, false);
			RenderScenarios.shaders(ctx, r, true);
			series(ctx, sp, r, "series-after-reload");
		}
		// Variants of the shader pack's options (-PslipwayDiagVariants=name:KEY=value,KEY=value;name:...), one at a
		// time on top of the player's options, same two views; by default Bliss's own debug views of its lighting.
		String packOptions = System.getProperty("slipway.diag.packOptions", "");
		Path optionsFile;
		try (Stream<Path> files = Files.list(SlipwayClientGameTests.reportDir().resolve("shaderpacks"))) {
			optionsFile = files.filter(p -> p.getFileName().toString().endsWith(".zip.txt")).findFirst().orElseThrow();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		String base = read(packOptions.isEmpty() ? optionsFile : Path.of(packOptions));
		String variants = System.getProperty("slipway.diag.variants", "");
		if (variants.isEmpty()) {
			variants = "debug_NORMALS:DEBUG_VIEW=debug_NORMALS;debug_DIRECT:DEBUG_VIEW=debug_DIRECT;debug_INDIRECT:DEBUG_VIEW=debug_INDIRECT";
		}
		try {
			for (String variant : variants.split(";")) {
				String[] parts = variant.split(":", 2);
				String extra = parts.length > 1 ? String.join("\n", parts[1].split(",")) : "";
				// Park the camera at the view first, reload with the variant, and take the picture without moving:
				// the artifact appears after a pipeline (re)creation and stays while the camera stays.
				view(ctx, sp, r, "left-before-" + parts[0], 9.0, 85.5, -8.0, 0f, 22f);
				write(optionsFile, base + "\n" + extra + "\n");
				RenderScenarios.shaders(ctx, r, false);
				RenderScenarios.shaders(ctx, r, true);
				r.note("variant %s: %s", parts[0], extra.replace('\n', ' '));
				ctx.waitTicks(100);
				Shots.take(ctx, r, "left-t3000-" + parts[0]);
				ctx.waitTicks(200);
				Shots.take(ctx, r, "left-t3000-" + parts[0] + "-later");
			}
		} finally {
			write(optionsFile, base);
		}
		RenderScenarios.shaders(ctx, r, false);
		view(ctx, sp, r, "left-t3000-no-shaders", 9.0, 85.5, -8.0, 0f, 22f);
		view(ctx, sp, r, "right-t3000-no-shaders", -7.0, 85.5, -8.0, 0f, 22f);
	}

	/**
	 * diag-water-patch: a copy of a saved harbour world ({@code -PslipwayDiagWorld}, as {@code make-harbour} leaves
	 * it), seen from above with Bliss on. Without {@code DhCullRepair} the water of the two chunks holding the moored
	 * submarine (glass windows in the sea) looks like a mirror of the sky while the sea around is dark, and so does
	 * the chunk of a single glass block put in the sea. Pictures with and without the repair, and whether GL's
	 * back-face culling agrees with Minecraft's state cache at points inside the frame.
	 */
	static void waterPatch(ClientGameTestContext ctx, Report.Result r) throws Exception {
		String world = System.getProperty("slipway.diag.world", "");
		Check.that(!world.isEmpty(), "set -PslipwayDiagWorld=<save folder>");
		Path source = Path.of(world);
		Check.that(Files.isRegularFile(source.resolve("level.dat")), "not a save folder: %s", source);
		Path saves = ctx.computeOnClient(mc -> mc.getLevelSource().getBaseDir());
		Path copy = saves.resolve("diag-world");
		delete(copy);
		copy(source, copy);
		Files.deleteIfExists(copy.resolve("session.lock"));
		int ox = Integer.getInteger("slipway.diag.x", 96), oz = Integer.getInteger("slipway.diag.z", -352);
		try (TestSingleplayerContext sp = new TestWorldSaveImpl(ctx, copy).open()) {
			try {
				TestServerContext server = sp.getServer();
				server.runCommand("gamerule advance_time false");
				server.runCommand("time set 2000");
				Game.hud(ctx, false);
				double x = ox + 0.5, y = 62 + 26, z = oz - 7.5;
				view(ctx, sp, r, "a-no-shaders", x, y, z, 180f, 90f);
				RenderScenarios.shaders(ctx, r, true);
				view(ctx, sp, r, "b-bliss", x, y, z, 180f, 90f);
				// One glass block in the sea of an untouched chunk: enough for the patch.
				sp.getServer().runCommand("setblock " + (ox - 40) + " 60 " + (oz - 12) + " minecraft:glass");
				ctx.waitTicks(60);
				Shots.take(ctx, r, "d-bliss-glass-block-in-the-sea");
				cullProbe(ctx, r, "with the repair");
				dev.timstewart.slipway.client.dh.DhCullRepair.enabled = false;
				ctx.waitTicks(60);
				Shots.take(ctx, r, "e-bliss-without-the-repair");
				cullProbe(ctx, r, "without the repair");
				dev.timstewart.slipway.client.dh.DhCullRepair.enabled = true;
				ctx.waitTicks(40);
				Shots.take(ctx, r, "f-bliss-with-the-repair-again");
			} finally {
				RenderScenarios.shaders(ctx, r, false);
				dhRendering(ctx, true);
				Game.hud(ctx, true);
			}
		}
	}

	private static volatile boolean cullProbing;
	private static boolean cullRegistered;
	private static final java.util.Map<String, int[]> CULL = new java.util.LinkedHashMap<>();

	private static void cullSample(String name) {
		if (!cullProbing) {
			return;
		}
		boolean gl = org.lwjgl.opengl.GL11C.glIsEnabled(org.lwjgl.opengl.GL11C.GL_CULL_FACE);
		boolean cache = GlStateCheck.cachedCull();
		synchronized (CULL) {
			int[] counts = CULL.computeIfAbsent(name, n -> new int[3]);
			counts[0]++;
			counts[1] += gl ? 0 : 1;
			counts[2] += gl != cache ? 1 : 0;
		}
	}

	/** Thirty frames: at each of Fabric's level render events, how often GL had culling off and how often the cache disagreed. */
	private static void cullProbe(ClientGameTestContext ctx, Report.Result r, String when) {
		ctx.runOnClient(mc -> {
			if (!cullRegistered) {
				cullRegistered = true;
				net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.START_MAIN.register(c -> cullSample("startMain"));
				net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(c -> cullSample("afterOpaqueTerrain"));
				net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_SOLID_FEATURES.register(c -> cullSample("afterSolidFeatures"));
				net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(c -> cullSample("beforeTranslucentTerrain"));
				net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.END_MAIN.register(c -> cullSample("endMain"));
			}
		});
		synchronized (CULL) {
			CULL.clear();
		}
		cullProbing = true;
		ctx.waitTicks(30);
		cullProbing = false;
		StringBuilder s = new StringBuilder();
		synchronized (CULL) {
			CULL.forEach((name, c) -> s.append(String.format(java.util.Locale.ROOT, "%s: %d samples, GL culling off %d, cache disagrees %d; ", name, c[0], c[1], c[2])));
		}
		r.note("back-face culling %s: %s", when, s);
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static void write(Path file, String text) {
		try {
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	record VesselPoint(double x, double y, double z) {
	}

	/**
	 * The light values the vessel is drawn with (block, sky; histogram over its mesh's vertices), and block light in
	 * the air around the plain skiff (helm 8 81 0) against the same positions of the vessel in its storage plot.
	 */
	private static void lightValues(ClientGameTestContext ctx, Report.Result r) {
		String mesh = ctx.computeOnClient(mc -> {
			var vessel = Check.notNull(dev.timstewart.slipway.client.ClientVessels.get(1L), "the client does not know vessel 1");
			java.util.Map<String, Integer> histogram = new java.util.TreeMap<>();
			com.mojang.blaze3d.vertex.VertexConsumer probe = new com.mojang.blaze3d.vertex.VertexConsumer() {
				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setColor(int red, int green, int blue, int alpha) {
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setColor(int color) {
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
					histogram.merge(String.format(java.util.Locale.ROOT, "b%02d s%02d", u >> 4, v >> 4), 1, Integer::sum);
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
					return this;
				}

				@Override
				public com.mojang.blaze3d.vertex.VertexConsumer setUv3(float u, float v) {
					return this;
				}
			};
			for (var layer : net.minecraft.client.renderer.chunk.ChunkSectionLayer.values()) {
				vessel.mesh.emit(layer, new com.mojang.blaze3d.vertex.PoseStack().last(), probe);
			}
			return histogram.toString();
		});
		r.note("vessel mesh vertices by light (block, sky): %s", mesh);
		String air = ctx.computeOnClient(mc -> {
			var vessel = dev.timstewart.slipway.client.ClientVessels.get(1L);
			net.minecraft.core.BlockPos plainHelm = new net.minecraft.core.BlockPos(8, 81, 0);
			java.util.Map<String, Integer> world = new java.util.TreeMap<>();
			java.util.Map<String, Integer> plot = new java.util.TreeMap<>();
			for (net.minecraft.core.BlockPos local : net.minecraft.core.BlockPos.betweenClosed(vessel.localMin, vessel.localMax)) {
				net.minecraft.core.BlockPos w = plainHelm.offset(local);
				net.minecraft.core.BlockPos p = vessel.anchor.offset(local);
				if (!mc.level.getBlockState(w).isAir() || !mc.level.getBlockState(p).isAir()) {
					continue;
				}
				world.merge("b" + mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, w) + " s" + mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, w), 1, Integer::sum);
				plot.merge("b" + mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p) + " s" + mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p), 1, Integer::sum);
			}
			return "plain skiff " + world + " | vessel plot " + plot;
		});
		r.note("air inside the ship's bounds by light (block, sky): %s", air);
	}

	/** The plain skiff's view, then a picture every 20 ticks for 30 seconds. */
	private static void series(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r, String name) {
		sp.getServer().runOnServer(s -> {
			var p = Game.player(s);
			p.teleportTo(s.overworld(), 9.0, 85.5, -8.0, java.util.Set.of(), 0f, 22f, true);
			p.getAbilities().flying = true;
			p.onUpdateAbilities();
		});
		ctx.waitFor(mc -> mc.player != null && mc.player.position().distanceToSqr(9.0, 85.5, -8.0) < 0.01, 100);
		ctx.getInput().lookAt(0f, 22f);
		for (int i = 0; i < 30; i++) {
			ctx.waitTicks(20);
			Shots.take(ctx, r, String.format(java.util.Locale.ROOT, "%s-%02d", name, i));
		}
	}

	/** Stands (flying, so it stays put) at a viewpoint, lets the terrain and the shader's temporal filters settle, and takes a picture. */
	private static void view(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r, String name, double x, double y, double z, float yaw, float pitch) {
		// Teleport and fly in the same server tick: a creative player standing on a block stops flying at once.
		sp.getServer().runOnServer(s -> {
			var p = Game.player(s);
			p.teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, true);
			p.getAbilities().flying = true;
			p.onUpdateAbilities();
		});
		ctx.waitFor(mc -> mc.player != null && mc.player.position().distanceToSqr(x, y, z) < 0.01 && mc.player.getAbilities().flying, 100);
		Game.waitChunks(ctx, 1200);
		Game.waitTerrain(ctx, 1200);
		ctx.getInput().lookAt(yaw, pitch);
		ctx.waitTicks(100);
		double[] at = ctx.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		Check.that(Math.abs(at[0] - x) < 0.5 && Math.abs(at[1] - y) < 0.5 && Math.abs(at[2] - z) < 0.5, "%s: the camera drifted to %.1f %.1f %.1f", name, at[0], at[1], at[2]);
		Shots.take(ctx, r, name);
	}

	private static void dhRendering(ClientGameTestContext ctx, boolean on) {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			return;
		}
		ctx.runOnClient(mc -> {
			var configs = com.seibel.distanthorizons.api.DhApi.Delayed.configs;
			if (configs != null) {
				configs.graphics().renderingEnabled().setValue(on);
			}
		});
		ctx.waitTicks(20);
	}

	private static void dhClouds(ClientGameTestContext ctx, boolean on) {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			return;
		}
		ctx.runOnClient(mc -> {
			var configs = com.seibel.distanthorizons.api.DhApi.Delayed.configs;
			if (configs != null) {
				configs.graphics().genericRendering().cloudRenderingEnabled().setValue(on);
			}
		});
	}

	private static void copy(Path from, Path to) throws IOException {
		try (Stream<Path> paths = Files.walk(from)) {
			for (Path p : paths.toList()) {
				Path target = to.resolve(from.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(target);
				} else {
					Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void delete(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}
}
