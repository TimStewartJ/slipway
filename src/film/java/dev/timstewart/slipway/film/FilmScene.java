package dev.timstewart.slipway.film;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The film's location: seed "sunsetcoast", a west-facing coast (open ocean to the west, sunset over the sea) with a
 * meadow shore and snowy jagged peaks rising from the water to the south-east (found with the scout shot). The hero
 * ship rests over the water off the shore with its helm at {@link #HELM}, bow north along the coast.
 */
final class FilmScene {
	private FilmScene() {
	}

	static final String SEED = "sunsetcoast";
	static final BlockPos HELM = new BlockPos(-4620, 80, 5800);

	static TestSingleplayerContext open(ClientGameTestContext ctx, int renderDistance) {
		int[] size = FilmRig.size();
		FilmRig.options(ctx, renderDistance);
		ctx.getInput().resizeWindow(size[0], size[1]);
		TestSingleplayerContext sp = FilmRig.scenicWorld(ctx, FilmRig.opt("seed", SEED));
		FilmRig.freezeWorld(sp.getServer(), (long)FilmRig.optDouble("daytime", 11700));
		return sp;
	}

	/** Builds the hero ship at {@link #HELM}, fills its chest and assembles it; returns the vessel id. */
	static long buildHero(TestServerContext server) {
		return server.computeOnServer(s -> {
			var level = s.overworld();
			Map<BlockPos, BlockState> hero = FilmShips.hero();
			// the chunks under the ship must exist before blocks go in
			for (BlockPos rel : hero.keySet()) {
				BlockPos p = HELM.offset(rel);
				level.getChunk(p.getX() >> 4, p.getZ() >> 4);
			}
			FilmShips.place(level, HELM, hero);
			FilmShips.fillHero(level, HELM);
			VesselAssembly.Outcome outcome = VesselManager.get(level).assemble(HELM, null);
			if (!outcome.success() || outcome.record() == null) {
				throw new AssertionError("assembly failed: " + outcome.message().getString());
			}
			var record = outcome.record();
			Map<String, Integer> missing = new java.util.TreeMap<>();
			for (Map.Entry<BlockPos, BlockState> e : hero.entrySet()) {
				if (level.getBlockState(record.toPlot(e.getKey())).isAir()) {
					missing.merge(e.getValue().getBlock().getDescriptionId() + (missing.size() < 12 ? "@" + e.getKey().toShortString() : ""), 1, Integer::sum);
				}
			}
			if (!missing.isEmpty()) {
				FilmMain.LOG.warn("Hero ship blocks not in the vessel: {}", missing);
			}
			FilmMain.LOG.info("Hero ship: {} blocks in the spec, {} assembled, vessel {}", hero.size(), outcome.record().blockCount, outcome.record().id);
			return outcome.record().id;
		});
	}

	/** Waits until the vessel's mesh is built on the client. */
	static void waitVessel(ClientGameTestContext ctx, long id) {
		ctx.waitFor(mc -> {
			ClientVessel v = ClientVessels.get(id);
			return v != null && v.ready() && v.mesh.vertexCount() > 0 && !v.mesh.hasPendingWork();
		}, 2400);
	}

	/** The vessel's centre as drawn at this partial tick (client thread), or {@code fallback} before it is known. */
	static Vec3 centre(long id, float partial, Vec3 fallback) {
		ClientVessel v = ClientVessels.get(id);
		return v != null && v.ready() ? v.worldCentre(v.renderPose(partial)) : fallback;
	}

	/** A helm-relative point of the vessel as drawn at this partial tick (client thread). */
	static Vec3 local(long id, float partial, Vec3 helmRelative, Vec3 fallback) {
		ClientVessel v = ClientVessels.get(id);
		return v != null && v.ready() ? v.renderPose(partial).localToWorld(helmRelative) : fallback;
	}
}
