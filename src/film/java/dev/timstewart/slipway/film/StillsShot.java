package dev.timstewart.slipway.film;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

/**
 * Stills of the hero ship at rest over the water: several angles (for review and the post), and with
 * {@code times=t1+t2+...} one angle at several times of day (to choose the light). Frames go to
 * build/film/out/stills-&lt;w&gt;x&lt;h&gt;/.
 */
final class StillsShot {
	private StillsShot() {
	}

	static void run(ClientGameTestContext ctx) {
		Path out = FilmRig.outDir("stills");
		try (TestSingleplayerContext sp = FilmScene.open(ctx, (int)FilmRig.optDouble("rd", 16))) {
			var server = sp.getServer();
			Vec3 helm = Vec3.atLowerCornerWithOffset(FilmScene.HELM, 0.5, 0.5, 0.5);
			// ship centre, roughly: the hull's middle (helm-relative 0, -3, -16)
			Vec3 c = helm.add(0, -3, -16);
			FilmCamera.resetClock();
			FilmCamera.set((t, p) -> FilmCamera.Frame.lookAt(c.add(-60, 20, 10), c, 0));
			FilmRig.followCamera(ctx);
			FilmRig.waitWorld(ctx, 6000);
			long id = FilmScene.buildHero(server);
			FilmScene.waitVessel(ctx, id);
			FilmRig.waitShaders(ctx);
			ctx.waitTicks((int)(20 * FilmRig.optDouble("dhWait", 45)));

			Vec3 door = helm.add(Vec3.atLowerCornerOf(FilmShips.HERO_DOOR)).add(0.5, 1, 0.5);
			Map<String, FilmCamera.Frame> angles = new LinkedHashMap<>();
			angles.put("01-front-quarter", FilmCamera.Frame.lookAt(c.add(-34, 12, -38), c.add(0, 3, 0), 0));
			angles.put("02-broadside-west", FilmCamera.Frame.lookAt(c.add(-52, 4, 4), c.add(0, 4, 0), 0));
			angles.put("03-stern-quarter", FilmCamera.Frame.lookAt(c.add(-26, 14, 44), c.add(0, 2, 0), 0));
			angles.put("04-deck-castle", FilmCamera.Frame.lookAt(door.add(-4.5, 3.2, -6.5), door.add(0, -0.4, 0), 0));
			angles.put("05-low-water", FilmCamera.Frame.lookAt(c.add(-30, -13, -24), c.add(0, 6, 0), 0));
			angles.put("06-east-backlit", FilmCamera.Frame.lookAt(c.add(46, 8, -10), c.add(0, 4, 0), 0));
			String times = FilmRig.opt("times", "");
			FilmClock.holdLoop = true;
			FilmCapture capture = new FilmCapture();
			try {
				if (!times.isEmpty()) {
					FilmCamera.Frame f = angles.get("01-front-quarter");
					for (String t : times.split("\\+")) {
						server.runCommand("time set " + t);
						ctx.waitTicks(3);
						still(ctx, capture, f, out.resolve("time-" + t + ".png"));
					}
					server.runCommand("time set " + (long)FilmRig.optDouble("daytime", 11700));
				}
				for (Map.Entry<String, FilmCamera.Frame> e : angles.entrySet()) {
					still(ctx, capture, e.getValue(), out.resolve(e.getKey() + ".png"));
				}
			} finally {
				capture.close();
				FilmClock.holdLoop = false;
				FilmCamera.set(null);
			}
			FilmMain.LOG.info("Stills written to {}", out);
		}
	}

	/** Moves the camera, lets the terrain there load and build, primes the shader's history, then saves one frame. */
	private static void still(ClientGameTestContext ctx, FilmCapture capture, FilmCamera.Frame frame, Path file) {
		FilmCamera.set((t, p) -> frame);
		FilmClock.holdLoop = false;
		FilmRig.followCamera(ctx);
		FilmRig.waitWorld(ctx, 6000);
		ctx.waitTicks(10);
		FilmClock.holdLoop = true;
		ctx.runOnClient(mc -> {
			for (int i = 0; i < 24; i++) {
				capture.render(mc, 1.0f, 1.0f / 3);
			}
			capture.capture(mc, 1.0f, 1.0f / 3, file);
		});
	}
}
