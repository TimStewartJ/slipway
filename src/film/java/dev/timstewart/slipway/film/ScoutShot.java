package dev.timstewart.slipway.film;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Not a shot: maps a seed's terrain (surface height and biome on a grid, from the noise, without generating chunks) to
 * build/film/out/scout-&lt;seed&gt;.csv so tools/film/scout_map.py can draw it and rank scenic coast spots.
 * Options: seed, radius (blocks), step (blocks), cx, cz (centre).
 */
final class ScoutShot {
	private ScoutShot() {
	}

	static void run(ClientGameTestContext ctx) {
		FilmRig.options(ctx, 4);
		for (String spec : FilmRig.opt("seed", "slipway").split("\\+")) {
			// seed or seed@cx@cz
			String[] p = spec.split("@");
			scout(ctx, p[0], p.length > 2 ? Integer.parseInt(p[1]) : (int)FilmRig.optDouble("cx", 0), p.length > 2 ? Integer.parseInt(p[2]) : (int)FilmRig.optDouble("cz", 0));
		}
	}

	private static void scout(ClientGameTestContext ctx, String seed, int cx, int cz) {
		int radius = (int)FilmRig.optDouble("radius", 3000);
		int step = (int)FilmRig.optDouble("step", 48);
		Path out = Path.of(System.getProperty("slipway.film.out", "film-out")).resolve("scout-" + seed + "@" + cx + "@" + cz + ".csv").toAbsolutePath();
		try (TestSingleplayerContext sp = FilmRig.scenicWorld(ctx, seed)) {
			long start = System.nanoTime();
			sp.getServer().runOnServer(s -> {
				var level = s.overworld();
				var generator = level.getChunkSource().getGenerator();
				var random = level.getChunkSource().randomState();
				try {
					Files.createDirectories(out.getParent());
					try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
						w.write("x,z,height,biome\n");
						for (int x = cx - radius; x <= cx + radius; x += step) {
							for (int z = cz - radius; z <= cz + radius; z += step) {
								int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
								var biome = level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(Math.max(h, 63)), QuartPos.fromBlock(z));
								String name = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
								w.write(x + "," + z + "," + h + "," + name + "\n");
							}
						}
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			FilmMain.LOG.info("Scout: seed {} mapped in {} s to {}", seed, (System.nanoTime() - start) / 1_000_000_000L, out);
		}
	}
}
