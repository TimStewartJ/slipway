package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;
import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.render.WaterMask;
import dev.timstewart.slipway.physics.FluidField;
import dev.timstewart.slipway.vessel.ActiveVessel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/**
 * afloat: an open hull of planks with iron ballast is dropped into a pool with hover off. It goes in with a splash,
 * bobs and comes to rest at the draught that displaces its weight, deep enough that its floor lies more than two
 * blocks under the waterline. Then, as a player sees it:
 * <ul>
 * <li>from above, the water's surface is not drawn across the inside of the hull (the water mask: the picture
 * changes when the mask is turned off, and with it on the floor has the colour of planks, not of water);</li>
 * <li>standing in the hull with the eyes under the waterline, the player is not in the water (no swimming, no
 * breath meter) and the view is not a view from under water, on the client and on the server, although the world
 * has water blocks there; looking up, the surface is not drawn overhead;</li>
 * <li>the same with the Bliss shader pack on (when Iris is there): from inside and from above the mask keeps the
 * water's surface out of the hull, and the view from inside is not a view from under water for the pack either;</li>
 * <li>a short push of the helm's thrust moves the boat along the water and the water stops it;</li>
 * <li>loaded beyond what it can displace, it goes down, the water runs over the rim, and the player in it is in
 * the water.</li>
 * </ul>
 */
final class AfloatScenarios {
	private AfloatScenarios() {
	}

	/** The pool: 15 x 15 blocks of water, seven deep, standing on the flat ground in a basin of stone. */
	private static final int POOL_X = 0, POOL_Z = 60, POOL_HALF = 7, POOL_DEPTH = 7;
	private static final double SURFACE = Game.GROUND_Y + POOL_DEPTH - 1 + 8.0 / 9.0;
	/** Ticks a shader pack is given before a picture: it blends each frame with the ones before it. */
	private static final int SHADER_SETTLE_TICKS = 60;

	/** A 5x5 floor, three rows of wall and a helm in the middle, with a block of iron in each inner corner. */
	static Map<BlockPos, BlockState> ballastedHull() {
		Map<BlockPos, BlockState> blocks = Ships.deck(2, Blocks.OAK_PLANKS.defaultBlockState());
		for (int y = 0; y <= 2; y++) {
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					if (Math.abs(x) == 2 || Math.abs(z) == 2) {
						blocks.put(new BlockPos(x, y, z), Blocks.OAK_PLANKS.defaultBlockState());
					}
				}
			}
		}
		for (int x = -1; x <= 1; x += 2) {
			for (int z = -1; z <= 1; z += 2) {
				blocks.put(new BlockPos(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
			}
		}
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}

	/** What the server's last step found of a vessel in the water. */
	record Water(double displaced, double flooded, int fluid, boolean applied, boolean flooding, double mass, double capacity, double sheltered, double reserve) {
	}

	private static Water water(TestServerContext server, long id) {
		return server.computeOnServer(s -> {
			ActiveVessel v = Game.active(s, id);
			return new Water(v.buoyancy.displacedVolume, v.buoyancy.floodedVolume, v.buoyancy.fluid, v.buoyancy.applied, v.flooding(),
				v.mass == null ? 0 : v.mass.mass(), v.hull.capacity(), v.hull.shelteredVolume(), v.buoyancyReserve());
		});
	}

	/** Flies (so it stays put) to a viewpoint. */
	private static void flyTo(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double y, double z, float yaw, float pitch) {
		sp.getServer().runOnServer(s -> {
			var p = Game.player(s);
			p.stopRiding();
			p.teleportTo(s.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, true);
			p.getAbilities().flying = true;
			p.onUpdateAbilities();
		});
		ctx.waitFor(mc -> mc.player != null && mc.player.position().distanceToSqr(x, y, z) < 0.01 && mc.player.getAbilities().flying, 100);
		Game.waitChunks(ctx, 1200);
		Game.waitTerrain(ctx, 1200);
		ctx.getInput().lookAt(yaw, pitch);
		ctx.waitTicks(10);
	}

	/** The same view with the water mask and without it: both pictures, and how much a part of them differs. */
	private static double maskDifference(ClientGameTestContext ctx, Report.Result r, String name, double centreX, double centreY, double halfSize, double[] meanWithMask) {
		return maskDifference(ctx, r, name, centreX, centreY, halfSize, meanWithMask, 5);
	}

	/** As above, waiting {@code settleTicks} before each picture (a shader pack blends a frame with the ones before it). */
	private static double maskDifference(ClientGameTestContext ctx, Report.Result r, String name, double centreX, double centreY, double halfSize, double[] meanWithMask,
		int settleTicks) {
		ctx.waitTicks(settleTicks);
		Path with = Shots.take(ctx, r, name + "-mask-on");
		ctx.runOnClient(mc -> WaterMask.enabled = false);
		Path without;
		try {
			ctx.waitTicks(settleTicks);
			without = Shots.take(ctx, r, name + "-mask-off");
		} finally {
			ctx.runOnClient(mc -> WaterMask.enabled = true);
		}
		ctx.waitTicks(5);
		try (NativeImage a = Shots.load(with); NativeImage b = Shots.load(without)) {
			// Positions and sizes are in units of half the picture's height, from its middle.
			int unit = a.getHeight() / 2;
			int size = Math.max(4, (int)Math.round(2 * halfSize * unit));
			int[] region = {a.getWidth() / 2 + (int)Math.round(centreX * unit) - size / 2, a.getHeight() / 2 + (int)Math.round(centreY * unit) - size / 2, size, size};
			double[] mean = Shots.mean(a, region[0], region[1], region[2], region[3]);
			double[] meanOff = Shots.mean(b, region[0], region[1], region[2], region[3]);
			System.arraycopy(mean, 0, meanWithMask, 0, 4);
			r.note("%s: mean colour of the checked part with the mask %.0f, %.0f, %.0f and without it %.0f, %.0f, %.0f", name, mean[0], mean[1], mean[2],
				meanOff[0], meanOff[1], meanOff[2]);
			return Shots.msd(a, b, region);
		}
	}

	static void afloat(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			int top = Game.GROUND_Y + POOL_DEPTH - 1;
			server.runOnServer(s -> {
				var level = s.overworld();
				for (int x = POOL_X - POOL_HALF - 1; x <= POOL_X + POOL_HALF + 1; x++) {
					for (int z = POOL_Z - POOL_HALF - 1; z <= POOL_Z + POOL_HALF + 1; z++) {
						boolean wall = Math.abs(x - POOL_X) > POOL_HALF || Math.abs(z - POOL_Z) > POOL_HALF;
						for (int y = Game.GROUND_Y; y <= top + (wall ? 1 : 0); y++) {
							level.setBlock(new BlockPos(x, y, z), (wall ? Blocks.STONE : Blocks.WATER).defaultBlockState(), 2 | 16);
						}
					}
				}
			});
			Game.hud(ctx, false);
			flyTo(ctx, sp, POOL_X + 0.5, top + 6, POOL_Z + 10.5, 180f, 30f);

			// Dropped in from a block above the water.
			BlockPos helm = new BlockPos(POOL_X, top + 3, POOL_Z);
			server.runOnServer(s -> Ships.build(s.overworld(), helm, ballastedHull()));
			long id = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm).id);
			Game.waitClientReady(ctx, id, 100);
			ctx.runOnClient(mc -> Effects.start(id));
			server.runCommand("slipway mode " + id + " hover false");
			List<Flight.Sample> drop = new ArrayList<>();
			int still = 0;
			for (int i = 0; i < 600 && still < 20; i++) {
				ctx.waitTick();
				Flight.Sample s = Flight.sample(server, id);
				drop.add(s);
				still = i > 20 && s.velocity().length() < 0.02 && s.angularVelocity().length() < 0.02 ? still + 1 : 0;
			}
			List<Effects.Seen> splashes = ctx.computeOnClient(mc -> Effects.particles().stream().filter(p -> p.what().contains("Splash")).toList());
			List<Effects.Seen> sounds = ctx.computeOnClient(mc -> Effects.sounds().stream().filter(p -> p.what().contains("splash")).toList());
			ctx.runOnClient(mc -> Effects.stop());
			Flight.checkContinuous("drop into the water", drop);
			Flight.Sample rest = drop.getLast();
			Water water = water(server, id);
			// The helm is the vessel's origin and stands on the floor: the hull's bottom is one block below it.
			double draught = water.mass() / 1000.0 / 25.0;
			double deepest = SURFACE - (drop.stream().mapToDouble(s -> s.pose().y()).min().orElse(Double.NaN) - 1.0);
			int turns = 0;
			for (int i = 2; i < drop.size(); i++) {
				double before = drop.get(i - 1).velocity().y, now = drop.get(i).velocity().y;
				if (Math.abs(before) > 0.03 && before * now < 0) {
					turns++;
				}
			}
			r.metric("drop.ticksToRest", drop.size());
			r.metric("drop.massTonnes", water.mass() / 1000.0);
			r.metric("drop.draught", SURFACE - (rest.pose().y() - 1.0));
			r.metric("drop.draughtExpected", draught);
			r.metric("drop.deepestDraught", deepest);
			r.metric("drop.turnsOfTheBobbing", turns);
			r.metric("drop.displaced", water.displaced());
			r.metric("drop.capacity", water.capacity());
			r.metric("drop.reserve", water.reserve());
			r.metric("drop.splashParticles", splashes.size());
			r.metric("drop.splashSounds", sounds.size());
			Check.that(still >= 20, "the hull did not come to rest on the water within %d ticks: speed %.3f", drop.size(), rest.velocity().length());
			Check.near("draught at rest (blocks)", SURFACE - (rest.pose().y() - 1.0), draught, 0.06);
			Check.near("water displaced at rest (m^3) against the vessel's mass (t)", water.displaced(), water.mass() / 1000.0, 1.5);
			Check.atMost("tilt at rest (degrees)", rest.tilt(), 2.0);
			Check.that(water.fluid() == FluidField.WATER && water.applied() && !water.flooding(), "the server's view of the water: %s", water);
			Check.atMost("deepest draught while going in (the rim is at 4.0)", deepest, 3.95);
			Check.atLeast("splash particles the client drew as the hull went in", splashes.size(), 5);
			Check.atLeast("splash sounds the client played as the hull went in", sounds.size(), 1);
			Check.near("air the hull shelters (m^3)", water.sheltered(), 27.0 - 5.0, 0.01);

			// The client knows it, and has built the same hull from its copy of the plot.
			double[] client = ctx.computeOnClient(mc -> {
				ClientVessel v = ClientVessels.get(id);
				return new double[] {v.inFluid ? 1 : 0, v.flooding ? 1 : 0, v.hull.capacity(), v.hull.shelteredVolume(), v.buoyancyReserve(), v.waterMask.pickedCells()};
			});
			r.metric("client.pickedMaskCells", client[5]);
			Check.that(client[0] == 1 && client[1] == 0, "the client's flags: in fluid %s, flooding %s", client[0], client[1]);
			Check.near("the client's hull: capacity (m^3)", client[2], water.capacity(), 0.01);
			Check.near("the client's hull: sheltered air (m^3)", client[3], water.sheltered(), 0.01);
			Check.near("the client's reserve", client[4], water.reserve(), 0.01);
			// The waterline crosses the lowest row of air (the helm's row): its four free cells, at least.
			Check.atLeast("cells of the hull the water mask covers", client[5], 4);
			Shots.take(ctx, r, "01-afloat");

			// From straight above: with the mask the floor beside the helm is planks, without it the pool's surface.
			flyTo(ctx, sp, POOL_X + 0.5, SURFACE + 7.0, POOL_Z + 0.5, 180f, 90f);
			int quads = ctx.computeOnClient(mc -> ClientVessels.get(id).waterMask.lastQuads());
			r.metric("above.maskQuads", quads);
			Check.atLeast("patches of the water mask drawn in the last frame", quads, 4);
			// The floor one block east or west of the helm: about an eighth of half the picture's height from its middle.
			double[] colour = new double[4];
			double changed = maskDifference(ctx, r, "02-from-above", 0.125, 0.0, 0.025, colour);
			r.metric("above.msdMaskOnOff", changed);
			Check.atLeast("how much the floor seen from above changes when the mask goes (mean squared difference)", changed, 0.002);
			Check.that(colour[0] > colour[2] + 10, "with the mask the floor from above is not plank-coloured: red %.0f, green %.0f, blue %.0f", colour[0], colour[1], colour[2]);

			// Standing in the hull, eyes under the waterline.
			Game.hud(ctx, true);
			server.runOnServer(s -> {
				// Flying, the player would hang over the floor instead of standing on it.
				Game.player(s).getAbilities().flying = false;
				Game.player(s).onUpdateAbilities();
			});
			DeckScenarios.placeRider(ctx, server, id, new Vec3(0.5, 0.05, 1.5));
			ctx.waitTicks(20);
			double[] eye = ctx.computeOnClient(mc -> new double[] {mc.player.getEyeY(), mc.level.getFluidState(BlockPos.containing(mc.player.getEyePosition())).isEmpty() ? 0 : 1,
				mc.player.isInWater() ? 1 : 0, mc.player.isUnderWater() ? 1 : 0, mc.gameRenderer.mainCamera().getFluidInCamera() == FogType.NONE ? 0 : 1,
				mc.player.getAirSupply(), mc.player.getMaxAirSupply()});
			boolean serverWet = server.computeOnServer(s -> Game.player(s).isInWater());
			r.metric("inside.eyeBelowSurface", SURFACE - eye[0]);
			Check.atLeast("how far the player's eyes are below the waterline (blocks)", SURFACE - eye[0], 0.3);
			Check.that(eye[1] == 1, "the world has no water block at the player's eyes: the check would prove nothing");
			Check.that(eye[2] == 0 && eye[3] == 0, "the player in the hull counts as in the water on the client (in water %s, under water %s)", eye[2], eye[3]);
			Check.that(!serverWet, "the player in the hull counts as in the water on the server");
			Check.that(eye[4] == 0, "the view from inside the hull counts as a view from under water");
			Check.that(eye[5] == eye[6], "the player in the hull is losing breath: %s of %s", eye[5], eye[6]);
			Game.hud(ctx, false);
			ctx.getInput().lookAt(180f, -75f);
			double[] up = new double[4];
			double overhead = maskDifference(ctx, r, "03-inside-looking-up", 0.0, 0.0, 0.2, up);
			r.metric("inside.msdMaskOnOff", overhead);
			Check.atLeast("how much the view up from inside the hull changes when the mask goes (mean squared difference)", overhead, 0.001);
			ctx.getInput().lookAt(180f, 10f);
			ctx.waitTicks(5);
			Shots.take(ctx, r, "04-inside-below-the-waterline");

			// The same with a shader pack: it draws the water itself, in its own passes, from what the game hands it.
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
				RenderScenarios.shaders(ctx, r, true);
				try {
					ctx.waitTicks(SHADER_SETTLE_TICKS);
					Check.that(ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().getFluidInCamera() == FogType.NONE && !mc.player.isInWater()),
						"with the shader pack on, the player in the hull counts as in or under the water");
					Shots.take(ctx, r, "06-bliss-inside-below-the-waterline");
					ctx.getInput().lookAt(180f, -75f);
					double[] blissUp = new double[4];
					double blissOverhead = maskDifference(ctx, r, "07-bliss-inside-looking-up", 0.0, 0.0, 0.2, blissUp, SHADER_SETTLE_TICKS);
					r.metric("bliss.inside.msdMaskOnOff", blissOverhead);
					flyTo(ctx, sp, POOL_X + 0.5, SURFACE + 7.0, POOL_Z + 0.5, 180f, 90f);
					double[] blissFloor = new double[4];
					double blissAbove = maskDifference(ctx, r, "08-bliss-from-above", 0.125, 0.0, 0.025, blissFloor, SHADER_SETTLE_TICKS);
					r.metric("bliss.above.msdMaskOnOff", blissAbove);
					flyTo(ctx, sp, POOL_X + 0.5, top + 6, POOL_Z + 10.5, 180f, 30f);
					ctx.waitTicks(SHADER_SETTLE_TICKS);
					Shots.take(ctx, r, "09-bliss-afloat");
					Check.atLeast("with Bliss: how much the view up from inside the hull changes when the mask goes (mean squared difference)", blissOverhead, 0.001);
					Check.atLeast("with Bliss: how much the floor seen from above changes when the mask goes (mean squared difference)", blissAbove, 0.002);
					Check.that(blissFloor[0] > blissFloor[2] + 10, "with Bliss and the mask the floor from above is not plank-coloured: red %.0f, green %.0f, blue %.0f",
						blissFloor[0], blissFloor[1], blissFloor[2]);
				} finally {
					RenderScenarios.shaders(ctx, r, false);
				}
				// Back on the hull's floor for what follows.
				server.runOnServer(s -> {
					Game.player(s).getAbilities().flying = false;
					Game.player(s).onUpdateAbilities();
				});
				DeckScenarios.placeRider(ctx, server, id, new Vec3(0.5, 0.05, 1.5));
				ctx.waitTicks(20);
			}

			// A push of thrust: it moves along the water and the water stops it.
			Flight.Sample before = Flight.sample(server, id);
			server.runCommand("slipway control " + id + " 0.4 0 0 0 0 0 12");
			double fastest = 0;
			List<Flight.Sample> push = new ArrayList<>();
			for (int i = 0; i < 160; i++) {
				ctx.waitTick();
				Flight.Sample s = Flight.sample(server, id);
				push.add(s);
				fastest = Math.max(fastest, Math.hypot(s.velocity().x, s.velocity().z));
			}
			Flight.checkContinuous("push", push);
			Flight.Sample after = push.getLast();
			double moved = Math.hypot(after.pose().x() - before.pose().x(), after.pose().z() - before.pose().z());
			r.metric("push.movedBlocks", moved);
			r.metric("push.fastest", fastest);
			r.metric("push.speedAfter", after.velocity().length());
			Check.atLeast("distance the boat moved under a push of thrust (blocks)", moved, 0.5);
			Check.atMost("distance the boat moved under a push of thrust (blocks)", moved, 4.0);
			Check.atMost("speed eight seconds after the push (the water stops it)", after.velocity().length(), 0.1);
			Check.near("height after the push", after.pose().y(), before.pose().y(), 0.1);
			Check.equal("the player is still carried by the boat", Flight.rider(ctx, id).carrier(), id);

			// Three more blocks of iron: more than the hull can displace. It goes down and the water comes in.
			server.runOnServer(s -> {
				var record = Game.active(s, id).record;
				for (int[] at : new int[][] {{-1, 0}, {1, 0}, {0, -1}}) {
					s.overworld().setBlock(record.anchor.offset(at[0], 0, at[1]), Blocks.IRON_BLOCK.defaultBlockState(), 3);
				}
			});
			server.waitFor(s -> Game.active(s, id).flooding() && Game.active(s, id).record.linearVelocity.length() < 0.05, 600);
			ctx.waitTicks(20);
			Water sunk = water(server, id);
			double[] drowned = ctx.computeOnClient(mc -> new double[] {mc.player.isInWater() ? 1 : 0, mc.gameRenderer.mainCamera().getFluidInCamera() == FogType.WATER ? 1 : 0,
				ClientVessels.get(id).flooding ? 1 : 0, ClientVessels.get(id).waterMask.pickedCells()});
			r.metric("sunk.reserve", sunk.reserve());
			r.metric("sunk.flooded", sunk.flooded());
			r.metric("sunk.restY", Flight.sample(server, id).pose().y());
			Check.atMost("how much of its weight the overloaded hull can displace", sunk.reserve(), 0.99);
			Check.near("the sunk hull's bottom on the pool's floor", Flight.sample(server, id).pose().y() - 1.0, Game.GROUND_Y, 0.15);
			Check.that(drowned[2] == 1, "the client was not told that the hull is flooding");
			Check.that(drowned[0] == 1 && server.computeOnServer(s -> Game.player(s).isInWater()), "the player in the flooded hull does not count as in the water");
			Check.that(drowned[1] == 1, "the view from inside the flooded hull is not a view from under water");
			Check.that(drowned[3] == 0, "the water mask still covers %s cells of a flooded hull", drowned[3]);
			Shots.take(ctx, r, "05-flooded-and-sunk");
			Game.hud(ctx, true);
		} finally {
			ctx.runOnClient(mc -> WaterMask.enabled = true);
		}
	}
}
