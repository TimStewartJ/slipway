package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;
import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.dh.DhProxies;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * render-iris: how vessels look with Sodium, with Iris running the Bliss shader pack, and far away in Distant Horizons.
 * <ul>
 * <li>Distant Horizons first records the built ship as terrain; after assembly its LOD there is air again (no stale
 * ship left behind).</li>
 * <li>The hovered vessel block is outlined exactly when vanilla would outline a block: on, and off with outlines
 * turned off or the HUD hidden (F1).</li>
 * <li>With Bliss on: the near view matches its reference image, and the vessel casts a shadow (the ground under it is
 * darker than with the vessel moved away; without shaders it is not).</li>
 * <li>A barge 350 blocks away, beyond vanilla's view, is drawn by its Distant Horizons proxy, which follows it when it
 * moves; the far view matches its reference image and shows the barge.</li>
 * </ul>
 */
final class RenderScenarios {
	private RenderScenarios() {
	}

	static void renderIris(ClientGameTestContext ctx, Report.Result r) {
		for (String mod : List.of("sodium", "iris", "distanthorizons")) {
			Check.that(FabricLoader.getInstance().isModLoaded(mod), "%s is not loaded in the client GameTest run", mod);
		}
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			try {
				render(ctx, sp, r);
			} finally {
				shaders(ctx, r, false);
			}
		}
	}

	private static void render(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r) {
		TestServerContext server = sp.getServer();
		server.runCommand("time set 6000");
		BlockPos helm = new BlockPos(0, Game.GROUND_Y + 4, 40);
		Map<BlockPos, BlockState> spec = Ships.mixedShip();
		server.runOnServer(s -> {
			Ships.build(s.overworld(), helm, spec);
			Ships.fillMixed(s.overworld(), helm);
		});
		Game.teleport(ctx, sp, 0.5, Game.GROUND_Y, 52.5, 180f, 0f);

		// Distant Horizons records the ship as terrain (the refresh is asked again until it shows: this is setup); after
		// assembly Slipway's own single refresh must turn the LOD there to air.
		BlockPos probe = helm.offset(2, -1, 2);
		Runnable refresh = () -> ctx.runOnClient(mc -> DhProxies.queueChunkRefresh(new AABB(helm).inflate(6)));
		String before = waitLod(ctx, probe, s -> s.contains("oak_planks"), 1800, "Distant Horizons to record the built ship", refresh);
		r.metric("lod.beforeAssembly", before);
		VesselRecord record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
		long id = record.id;
		Game.waitClientReady(ctx, id, 200);
		String after = waitLod(ctx, probe, s -> s.startsWith("air"), 1800, "the ship's old LOD to become air after assembly", null);
		r.metric("lod.afterAssembly", after);

		// The outline: exactly when vanilla would outline a block (the player stands on the deck, within reach).
		DeckScenarios.placeRider(ctx, sp, server, id, new net.minecraft.world.phys.Vec3(0.5, 0.3, 1.5));
		Game.hud(ctx, true);
		Flight.aimAtLocal(ctx, id, Ships.MIXED_SIGN.getX() + 0.5, Ships.MIXED_SIGN.getY() + 0.4, Ships.MIXED_SIGN.getZ() + 0.5, Ships.MIXED_SIGN);
		Shots.waitStill(ctx, r, "outline", 1200);
		int outlined = outlinePixels(ctx, r, "01-outline");
		r.metric("outline.pixels", outlined);
		Check.atLeast("pixels that change when the vessel block's outline is turned off", outlined, 60);
		Game.hud(ctx, false);
		Shots.waitStill(ctx, r, "outline-f1", 1200);
		int hidden = outlinePixels(ctx, r, "02-outline-f1");
		r.metric("outline.pixelsWithHudHidden", hidden);
		Check.atMost("outline pixels with the HUD hidden (F1: no outlines, as for world blocks)", hidden, 5);

		// Shadow control without shaders: a floating vessel does not darken the ground.
		Game.teleport(ctx, sp, 0.5, Game.GROUND_Y, 52.5, 180f, 7.7f);
		double plainRatio = shadowRatio(ctx, sp, r, id, helm, "03-ground-plain");
		r.metric("shadow.ratioWithoutShaders", plainRatio);
		Check.atLeast("ground brightness under the vessel vs without it, no shaders", plainRatio, 0.9);

		// Bliss on.
		shaders(ctx, r, true);
		Game.teleport(ctx, sp, 12.5, Game.GROUND_Y, 54.5, 135f, 0f);
		lookAtVessel(ctx, server, id);
		ctx.waitTicks(40);
		Game.waitChunks(ctx, 1200);
		Game.waitTerrain(ctx, 1200);
		Shots.matchTemplate(ctx, r, "render-near-bliss", 0.02);
		Game.teleport(ctx, sp, 0.5, Game.GROUND_Y, 52.5, 180f, 7.7f);
		double shadedRatio = shadowRatio(ctx, sp, r, id, helm, "04-ground-bliss");
		r.metric("shadow.ratioWithBliss", shadedRatio);
		Check.atMost("ground brightness under the vessel vs without it, Bliss (the vessel casts a shadow)", shadedRatio, 0.8);

		// Far away: a barge 350 blocks south, beyond vanilla's view, drawn by its Distant Horizons proxy.
		BlockPos bargeHelm = new BlockPos(0, Game.GROUND_Y + 20, 400);
		server.runCommand("forceload add -32 368 32 432");
		ctx.waitTicks(20);
		server.runOnServer(s -> Ships.build(s.overworld(), bargeHelm, barge()));
		long barge = server.computeOnServer(s -> Ships.assemble(s.overworld(), bargeHelm).id);
		Game.teleport(ctx, sp, 0.5, Game.GROUND_Y, 52.5, 0f, 0f);
		double[] proxy = waitProxy(ctx, server, barge, 1200);
		r.metric("proxy.boxes", proxy[4]);
		boolean nearRendered = ctx.computeOnClient(mc -> {
			ClientVessel v = ClientVessels.get(barge);
			return v != null && v.ready();
		});
		Check.that(!nearRendered, "the barge 350 blocks away is tracked as a near vessel; the far view would not test Distant Horizons");
		lookAtVessel(ctx, server, barge);
		ctx.waitTicks(100);
		Path present = Shots.take(ctx, r, "05-barge-far-bliss");
		Shots.matchTemplate(ctx, r, "render-far-dh-bliss", 0.02);
		// Out of view: straight up, 300 blocks above the camera's view cone, still in its loaded chunks.
		server.runCommand(String.format(Locale.ROOT, "slipway pose %d 0 %d 400 0 0 0", barge, Game.GROUND_Y + 320));
		ctx.waitTicks(60);
		Path gone = Shots.take(ctx, r, "06-barge-moved-away");
		try (NativeImage a = Shots.load(present); NativeImage b = Shots.load(gone)) {
			int[] blob = changedBlob(a, b, a.getWidth() / 2 - 120, a.getHeight() / 2 - 60, 240, 120);
			r.metric("far.changedPixels", blob[0]);
			r.metric("far.blob", String.format(Locale.ROOT, "%dx%d at %+d,%+d from the aim point", blob[3] - blob[1] + 1, blob[4] - blob[2] + 1,
				(blob[1] + blob[3]) / 2 - a.getWidth() / 2, (blob[2] + blob[4]) / 2 - a.getHeight() / 2));
			r.metric("far.blobLuminance", String.format(Locale.ROOT, "%.1f with the barge, %.1f without", blob[5] / 10.0, blob[6] / 10.0));
			Check.atLeast("pixels that show the barge 350 blocks away (present vs moved away)", blob[0], 40);
			Check.that(blob[3] - blob[1] < 90 && blob[4] - blob[2] < 45, "what changed in the far view is not a compact barge-sized shape: %dx%d px",
				blob[3] - blob[1] + 1, blob[4] - blob[2] + 1);
			Check.that(Math.abs((blob[1] + blob[3]) / 2 - a.getWidth() / 2) <= 40 && Math.abs((blob[2] + blob[4]) / 2 - a.getHeight() / 2) <= 30,
				"the far barge is not drawn where the camera aims at it");
			Check.that(blob[5] < blob[6], "the far barge is not darker than the sky behind it");
		}
		// The proxy follows the vessel: moved 24 east, 10 up, 20 south (inside the force-loaded area).
		server.runCommand(String.format(Locale.ROOT, "slipway pose %d 24 %d 420 0 0 0", barge, Game.GROUND_Y + 30));
		double[] moved = waitProxy(ctx, server, barge, 400);
		r.metric("proxy.afterMove", String.format(Locale.ROOT, "%.2f,%.2f,%.2f", moved[0], moved[1], moved[2]));
		server.runCommand("forceload remove all");
	}

	/** The barge: 20x16 blue, white and red concrete layers under the helm and a red 20x8 sail on its north side. */
	static Map<BlockPos, BlockState> barge() {
		Map<BlockPos, BlockState> blocks = new java.util.LinkedHashMap<>();
		BlockState[] layers = {Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState(), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState()};
		for (int dy = -3; dy <= -1; dy++) {
			for (int x = -10; x <= 9; x++) {
				for (int z = -8; z <= 7; z++) {
					blocks.put(new BlockPos(x, dy, z), layers[dy + 3]);
				}
			}
		}
		for (int x = -10; x <= 9; x++) {
			for (int dy = 0; dy <= 7; dy++) {
				blocks.put(new BlockPos(x, dy, -8), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
			}
		}
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}

	/**
	 * Turns Iris's shaders on (the Bliss pack configured for the run) or off. In a world it waits until the pipeline is
	 * in use; at the title screen Iris has no pipeline yet, so only the setting changes (see {@link #checkPackInUse}).
	 */
	static void shaders(ClientGameTestContext ctx, Report.Result r, boolean on) {
		ctx.runOnClient(mc -> IrisApi.getInstance().getConfig().setShadersEnabledAndApply(on));
		if (!ctx.computeOnClient(mc -> mc.level != null)) {
			Check.that(ctx.computeOnClient(mc -> IrisApi.getInstance().getConfig().areShadersEnabled()) == on, "Iris did not take the shader setting %s", on);
			return;
		}
		ctx.waitFor(mc -> IrisApi.getInstance().isShaderPackInUse() == on, 2400);
		if (on) {
			checkPackInUse(ctx, r);
		}
		ctx.waitTicks(20);
	}

	/** Waits (in a world) until Iris runs the Bliss pack. */
	static void checkPackInUse(ClientGameTestContext ctx, Report.Result r) {
		ctx.waitFor(mc -> IrisApi.getInstance().isShaderPackInUse(), 2400);
		String pack = ctx.computeOnClient(mc -> net.irisshaders.iris.Iris.getCurrentPackName());
		r.metric("shaderPack", pack);
		Check.that(pack != null && pack.startsWith("Bliss"), "the shader pack in use is %s, expected Bliss", pack);
		r.metric("sunPathRotation", ctx.computeOnClient(mc -> (double)IrisApi.getInstance().getSunPathRotation()));
	}

	/**
	 * Polls Distant Horizons' LOD data at a block until it satisfies a condition (DH answers asynchronously);
	 * {@code retrigger}, when given, runs every 100 ticks while waiting.
	 */
	static String waitLod(ClientGameTestContext ctx, BlockPos pos, Predicate<String> done, int maxTicks, String what, Runnable retrigger) {
		String last = "none";
		for (int t = 0; t < maxTicks; t += 10) {
			if (retrigger != null && t % 100 == 0) {
				retrigger.run();
			}
			last = ctx.computeOnClient(mc -> DhProxies.lodBlockAt(pos.getX(), pos.getY(), pos.getZ()));
			if (done.test(last)) {
				return last;
			}
			ctx.waitTicks(10);
		}
		throw new AssertionError("timed out waiting for " + what + " at " + pos.toShortString() + "; Distant Horizons reports: " + last);
	}

	/** Waits until a vessel's proxy is registered, active and at the vessel's pose; returns its state. */
	static double[] waitProxy(ClientGameTestContext ctx, TestServerContext server, long id, int maxTicks) {
		double[] state = null;
		VesselPose pose = null;
		for (int t = 0; t < maxTicks; t += 5) {
			pose = Game.serverPose(server, id);
			state = ctx.computeOnClient(mc -> DhProxies.proxyState(id));
			if (state != null && state[3] == 1.0 && Math.abs(state[0] - pose.x()) < 0.5 && Math.abs(state[1] - pose.y()) < 0.5 && Math.abs(state[2] - pose.z()) < 0.5) {
				return state;
			}
			ctx.waitTicks(5);
		}
		throw new AssertionError(String.format(Locale.ROOT, "vessel %d's Distant Horizons proxy is not active at its pose (%.2f, %.2f, %.2f): %s", id,
			pose.x(), pose.y(), pose.z(), state == null ? "no proxy" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f) active=%s", state[0], state[1], state[2], state[3] == 1.0)));
	}

	static void lookAtVessel(ClientGameTestContext ctx, TestServerContext server, long id) {
		var centre = server.computeOnServer(s -> dev.timstewart.slipway.vessel.VesselManager.worldCentre(Game.active(s, id).record));
		ctx.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, centre));
	}

	/** Pixels that change when the outline is switched off (vanilla's own switch), same frame otherwise. */
	static int outlinePixels(ClientGameTestContext ctx, Report.Result r, String name) {
		Path on = Shots.take(ctx, r, name + "-on");
		ctx.runOnClient(mc -> mc.gameRenderer.setRenderBlockOutline(false));
		Path off;
		try {
			off = Shots.take(ctx, r, name + "-off");
		} finally {
			ctx.runOnClient(mc -> mc.gameRenderer.setRenderBlockOutline(true));
		}
		try (NativeImage a = Shots.load(on); NativeImage b = Shots.load(off)) {
			int changed = 0;
			for (int y = 0; y < a.getHeight(); y++) {
				for (int x = 0; x < a.getWidth(); x++) {
					int p = a.getPixel(x, y);
					int q = b.getPixel(x, y);
					int d = Math.max(Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)), Math.max(Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)), Math.abs((p & 0xFF) - (q & 0xFF))));
					if (d > 24) {
						changed++;
					}
				}
			}
			return changed;
		}
	}

	/**
	 * Mean brightness of the ground under the vessel (the centre of the view, which aims under its deck) with the
	 * vessel in place, divided by the same with the vessel moved 100 blocks away.
	 */
	static double shadowRatio(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r, long id, BlockPos helm, String name) {
		ctx.waitTicks(20);
		Path with = Shots.take(ctx, r, name + "-with-vessel");
		sp.getServer().runCommand(String.format(Locale.ROOT, "slipway pose %d %d %d %d 0 0 0", id, helm.getX(), helm.getY(), helm.getZ() + 100));
		ctx.waitTicks(40);
		Path without = Shots.take(ctx, r, name + "-without-vessel");
		sp.getServer().runCommand(String.format(Locale.ROOT, "slipway pose %d %d %d %d 0 0 0", id, helm.getX(), helm.getY(), helm.getZ()));
		ctx.waitTicks(40);
		try (NativeImage a = Shots.load(with); NativeImage b = Shots.load(without)) {
			int w = 200;
			int h = 40;
			double shaded = Shots.mean(a, a.getWidth() / 2 - w / 2, a.getHeight() / 2 - h / 2, w, h)[3];
			double lit = Shots.mean(b, b.getWidth() / 2 - w / 2, b.getHeight() / 2 - h / 2, w, h)[3];
			r.note("%s: ground luminance %.1f with the vessel, %.1f without", name, shaded, lit);
			return lit <= 0.0 ? Double.NaN : shaded / lit;
		}
	}

	/**
	 * Pixels of a region that differ clearly between two shots of the same view: {count, minX, minY, maxX, maxY, mean
	 * luminance x10 of those pixels in a, the same in b}.
	 */
	static int[] changedBlob(NativeImage a, NativeImage b, int x0, int y0, int w, int h) {
		int count = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		double lumA = 0, lumB = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int p = a.getPixel(x, y);
				int q = b.getPixel(x, y);
				int d = Math.max(Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)), Math.max(Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)), Math.abs((p & 0xFF) - (q & 0xFF))));
				if (d > 30) {
					count++;
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
					lumA += luminance(p);
					lumB += luminance(q);
				}
			}
		}
		if (count == 0) {
			return new int[] {0, 0, 0, 0, 0, 0, 0};
		}
		return new int[] {count, minX, minY, maxX, maxY, (int)Math.round(lumA / count * 10), (int)Math.round(lumB / count * 10)};
	}

	static double luminance(int argb) {
		return 0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF);
	}

	static int redPixels(NativeImage image, int[] region) {
		int count = 0;
		for (int y = region[1]; y < region[1] + region[3]; y++) {
			for (int x = region[0]; x < region[0] + region[2]; x++) {
				int p = image.getPixel(x, y);
				int red = (p >> 16) & 0xFF;
				int green = (p >> 8) & 0xFF;
				int blue = p & 0xFF;
				if (red > 60 && red > 1.4 * green && red > 1.4 * blue) {
					count++;
				}
			}
		}
		return count;
	}
}
