package dev.timstewart.slipway.film;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Not a shot: looks at the film's location before a shot is designed there. {@code mode=survey} writes the real
 * surface (height and top block per column, from generated chunks) around a point to
 * build/film/out/probe-&lt;size&gt;/survey.csv and saves stills from above and from the sides. Options: cx, cz, radius,
 * shipAt=x@y@z@heading (draws the hero ship there, placed as plain blocks, to judge clearances).
 */
final class ProbeShot {
	private ProbeShot() {
	}

	static void run(ClientGameTestContext ctx) {
		Path out = FilmRig.outDir("probe");
		try (TestSingleplayerContext sp = FilmScene.open(ctx, (int)FilmRig.optDouble("rd", 12))) {
			var server = sp.getServer();
			int cx = (int)FilmRig.optDouble("cx", -4525);
			int cz = (int)FilmRig.optDouble("cz", 5795);
			int radius = (int)FilmRig.optDouble("radius", 56);
			Vec3 centre = new Vec3(cx, 72, cz);
			FilmCamera.resetClock();
			FilmCamera.set((t, p) -> FilmCamera.Frame.lookAt(centre.add(0, 70, 0.01), centre, 0));
			FilmRig.followCamera(ctx);
			FilmRig.waitWorld(ctx, 6000);
			server.runOnServer(s -> {
				var level = s.overworld();
				try (BufferedWriter w = Files.newBufferedWriter(out.resolve("survey.csv"), StandardCharsets.UTF_8)) {
					w.write("x,z,height,block,leaves\n");
					for (int x = cx - radius; x <= cx + radius; x++) {
						for (int z = cz - radius; z <= cz + radius; z++) {
							level.getChunk(x >> 4, z >> 4);
							int solid = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
							int any = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
							String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, solid - 1, z)).getBlock()).getPath();
							w.write(String.format(Locale.ROOT, "%d,%d,%d,%s,%d\n", x, z, solid, block, any - solid));
						}
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			FilmRig.waitShaders(ctx);
			ctx.waitTicks((int)(20 * FilmRig.optDouble("dhWait", 20)));
			Map<String, FilmCamera.Frame> views = new LinkedHashMap<>();
			views.put("top", FilmCamera.Frame.lookAt(centre.add(0, 78, 0.01), centre, 0));
			views.put("from-nw", FilmCamera.Frame.lookAt(centre.add(-34, 16, -34), centre.add(0, 6, 0), 0));
			views.put("from-n", FilmCamera.Frame.lookAt(centre.add(0, 16, -46), centre.add(0, 6, 0), 0));
			views.put("from-w", FilmCamera.Frame.lookAt(centre.add(-46, 16, 0), centre.add(0, 6, 0), 0));
			views.put("from-nw-low", FilmCamera.Frame.lookAt(centre.add(-26, 8, -26), centre.add(0, 8, 0), 0));
			FilmClock.holdLoop = true;
			FilmCapture capture = new FilmCapture();
			try {
				for (Map.Entry<String, FilmCamera.Frame> e : views.entrySet()) {
					FilmCamera.set((t, p) -> e.getValue());
					FilmClock.holdLoop = false;
					FilmRig.followCamera(ctx);
					FilmRig.waitWorld(ctx, 6000);
					ctx.waitTicks(40);
					FilmClock.holdLoop = true;
					ctx.runOnClient(mc -> {
						for (int i = 0; i < 24; i++) {
							capture.render(mc, 1.0f, 1.0f / 3);
						}
						capture.capture(mc, 1.0f, 1.0f / 3, out.resolve(e.getKey() + ".png"));
					});
				}
			} finally {
				capture.close();
				FilmClock.holdLoop = false;
				FilmCamera.set(null);
			}
			FilmMain.LOG.info("Probe: survey of {} blocks around {}, {} written to {}", radius, cx, cz, out);
		}
	}
}
