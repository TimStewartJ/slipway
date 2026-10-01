package dev.timstewart.slipway.film;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Spike: proves sub-tick rendering. A skiff flies a slow turn over the superflat test world; 60 ticks from a fixed
 * camera that tracks it, then 60 ticks from a camera orbiting it. frames.csv logs camera and vessel positions per frame.
 */
final class SpikeShot {
	private SpikeShot() {
	}

	static void run(ClientGameTestContext ctx) {
		int[] size = FilmRig.size();
		Path out = FilmRig.outDir("spike");
		FilmRig.options(ctx, 12);
		ctx.getInput().resizeWindow(size[0], size[1]);
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			var server = sp.getServer();
			server.runCommand("time set 11800");
			server.runCommand("weather clear");
			server.runCommand("gamemode spectator @a");
			BlockPos helm = new BlockPos(0, -45, 0);
			long id = server.computeOnServer(s -> {
				var level = s.overworld();
				for (Map.Entry<BlockPos, BlockState> e : FilmShips.skiff().entrySet()) {
					level.setBlock(helm.offset(e.getKey()), e.getValue(), 2 | 16);
				}
				VesselAssembly.Outcome outcome = VesselManager.get(level).assemble(helm, null);
				if (!outcome.success() || outcome.record() == null) {
					throw new AssertionError("assembly failed: " + outcome.message().getString());
				}
				return outcome.record().id;
			});
			Vec3 fixed = new Vec3(28, -38, 22);
			FilmCamera.resetClock();
			FilmCamera.set((time, partial) -> FilmCamera.Frame.lookAt(fixed, centre(id, partial, new Vec3(0, -45, -3)), 0));
			FilmRig.followCamera(ctx);
			FilmRig.waitWorld(ctx, 2400);
			ctx.waitFor(mc -> {
				ClientVessel v = ClientVessels.get(id);
				return v != null && v.ready() && v.mesh.vertexCount() > 0 && !v.mesh.hasPendingWork();
			}, 1200);
			FilmRig.waitShaders(ctx);
			ctx.waitTicks(40);

			server.runCommand("slipway control " + id + " 0.35 0 0.1 0 0.45 0 400");
			ctx.waitTicks(20);
			long start = System.nanoTime();
			try (FilmRig.Recorder rec = new FilmRig.Recorder(ctx, out, "vesX,vesY,vesZ,gameTime,playback", (mc, partial) -> {
				Vec3 c = centre(id, partial, Vec3.ZERO);
				ClientVessel v = ClientVessels.get(id);
				return String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%d,%.4f", c.x, c.y, c.z, mc.level.getGameTime(), v == null ? Double.NaN : v.playbackTick());
			})) {
				for (int t = 0; t < 60; t++) {
					rec.tick();
				}
				double orbitStart = FilmCamera.time(1.0f);
				FilmCamera.set((time, partial) -> {
					Vec3 c = centre(id, partial, Vec3.ZERO);
					double a = Math.toRadians(35 + (time - orbitStart) * 1.5);
					Vec3 cam = c.add(Math.sin(a) * 22, 7, Math.cos(a) * 22);
					return FilmCamera.Frame.lookAt(cam, c, 0);
				});
				for (int t = 0; t < 60; t++) {
					rec.tick();
				}
				FilmMain.LOG.info("Spike: {} frames of {}x{} in {} s, {} ms per frame; frames in {}", rec.frames(), size[0], size[1],
					String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9), String.format(Locale.ROOT, "%.0f", rec.meanFrameMillis()), out);
			}
			FilmCamera.set(null);
		}
	}

	/** The vessel's centre as drawn at this partial tick (on the client thread), or {@code fallback} before it is known. */
	static Vec3 centre(long id, float partial, Vec3 fallback) {
		ClientVessel v = ClientVessels.get(id);
		return v != null && v.ready() ? v.worldCentre(v.renderPose(partial)) : fallback;
	}
}
