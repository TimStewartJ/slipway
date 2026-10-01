package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;
import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * disassembly: a ship hovering where it was built is disassembled while the player looks at it, and the frame on the
 * screen before each of the next {@value #TICKS} ticks is kept. The ship must be in every one of them, whole: the
 * vessel's picture is kept until the terrain shows the blocks that were put back into the world. Done with a small
 * ship and with a carrier of 2,080 blocks, the carrier also with the terrain renderer limited to one build thread
 * (a weak machine: the sections that got the blocks are then built over several frames). Each is also done once with
 * the kept picture turned off, to record the blink it prevents (the ship missing from the frames of a tick or more).
 */
final class DisassemblyScenarios {
	private DisassemblyScenarios() {
	}

	static final int TICKS = 12;

	/** A ship to disassemble: its blocks relative to the helm, the box that holds them, and whether it has a chest on deck. */
	private record Ship(Map<BlockPos, BlockState> blocks, BlockPos min, BlockPos max, boolean blockEntities) {
	}

	/** How many of the frames lack the ship, the largest part of it any frame lacks (1 = all of it), and for how many ticks the gone vessel was still drawn. */
	private record Outcome(int missing, double worst, int drawnTicks) {
	}

	/** The small ship's chest in the open, lit by the sky and the glowstone beside it. */
	static final BlockPos CHEST = new BlockPos(2, 0, 2);
	/** The small ship's chest in the dark. */
	static final BlockPos DARK_CHEST = new BlockPos(-2, 0, 2);

	static Map<BlockPos, BlockState> ship() {
		Map<BlockPos, BlockState> blocks = Ships.deck(3, Blocks.GOLD_BLOCK.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		blocks.put(CHEST, Blocks.CHEST.defaultBlockState());
		// Next to the chest: its light (14 at the chest) is in the plot only, and goes with the plot's chunks.
		blocks.put(CHEST.west(), Blocks.GLOWSTONE.defaultBlockState());
		// A second chest walled in and roofed over: no light reaches it, from the sky or from the glowstone.
		blocks.put(DARK_CHEST, Blocks.CHEST.defaultBlockState());
		for (Direction side : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP}) {
			blocks.put(DARK_CHEST.relative(side), Blocks.GOLD_BLOCK.defaultBlockState());
		}
		for (int x = -3; x <= 3; x += 6) {
			for (int z = -3; z <= 3; z += 6) {
				blocks.put(new BlockPos(x, 0, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
				blocks.put(new BlockPos(x, 1, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
			}
		}
		return blocks;
	}

	private static Path grab(ClientGameTestContext ctx, Report.Result r, String name) {
		return ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(Shots.dir(r)));
	}

	private static double difference(Path a, Path b) {
		try (NativeImage first = Shots.load(a); NativeImage second = Shots.load(b)) {
			return Shots.msd(first, second, null);
		}
	}

	/**
	 * The frame on the screen before each of the next ticks, as files. A test screenshot draws a frame of its own and
	 * takes several ticks; this copies the frame the game drew last (the game's own screenshot copy, which does not
	 * hold the game up), so no tick is skipped.
	 */
	private static List<Path> framesOfTheNextTicks(ClientGameTestContext ctx, Report.Result r, String label, long id, int[] kept) {
		AtomicReferenceArray<NativeImage> images = new AtomicReferenceArray<>(TICKS);
		for (int tick = 0; tick < TICKS; tick++) {
			ctx.waitTick();
			int index = tick;
			ctx.runOnClient(mc -> {
				// Only the kept picture counts: until the client hears of the disassembly the vessel itself is still there.
				ClientVessel drawn = ClientVessels.drawn(id);
				if (drawn != null && drawn.gone()) {
					kept[0]++;
				}
				Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> images.set(index, image));
			});
		}
		ctx.waitFor(mc -> {
			for (int i = 0; i < TICKS; i++) {
				if (images.get(i) == null) {
					return false;
				}
			}
			return true;
		}, 200);
		List<Path> frames = new ArrayList<>();
		for (int i = 0; i < TICKS; i++) {
			Path file = Shots.dir(r).resolve(String.format(Locale.ROOT, "%s-tick-%02d.png", label, i + 1));
			try (NativeImage image = images.get(i)) {
				image.writeToFile(file);
			} catch (IOException e) {
				throw new AssertionError("cannot write " + file, e);
			}
			frames.add(file);
		}
		return frames;
	}

	/** Builds, assembles and disassembles the ship once and compares the frames after it with the picture before. */
	private static Outcome run(ClientGameTestContext ctx, TestServerContext server, Report.Result r, BlockPos helm, Ship ship, boolean keep, String label) {
		server.runOnServer(s -> Ships.build(s.overworld(), helm, ship.blocks()));
		VesselRecord record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
		long id = record.id;
		Game.waitClientReady(ctx, id, 200);
		Game.waitClientComplete(ctx, id, 400);
		Shots.waitStill(ctx, r, label + "-assembled", 1200);
		Path before = Shots.take(ctx, r, label + "-1-before");
		ctx.runOnClient(mc -> {
			ClientVessels.keepGoneVessels = keep;
			dev.timstewart.slipway.client.SlipwayDebug.blockEntitiesStart(id);
		});
		server.runCommand("slipway disassemble " + id);
		int[] drawnTicks = new int[1];
		List<Path> frames = framesOfTheNextTicks(ctx, r, label, id, drawnTicks);
		r.metric(label + ".ticksTheGoneVesselWasDrawn", drawnTicks[0]);
		// The chests of the kept picture: their plot chunk is gone by then, where a light lookup answers full sky light
		// and no block light. Each must be drawn with the light it had while the vessel was there: the one on deck by
		// the sky and the glowstone beside it, the walled-in one dark (it lit up like daylight otherwise).
		Map<BlockPos, dev.timstewart.slipway.client.SlipwayDebug.KeptLight> keptLight = ctx.computeOnClient(mc -> {
			var light = dev.timstewart.slipway.client.SlipwayDebug.keptBlockEntityLight();
			dev.timstewart.slipway.client.SlipwayDebug.blockEntitiesStop();
			return light;
		});
		if (ship.blockEntities()) {
			r.metric(label + ".keptBlockEntityDraws", keptLight.values().stream().mapToInt(light -> light.draws()).sum());
			if (keep) {
				var open = Check.notNull(keptLight.get(CHEST), "%s: the chest on deck was not drawn in the kept picture (drawn: %s)", label, keptLight.keySet());
				var dark = Check.notNull(keptLight.get(DARK_CHEST), "%s: the walled-in chest was not drawn in the kept picture (drawn: %s)", label, keptLight.keySet());
				r.note("%s: kept picture's chest on deck %s, walled-in chest %s", label, open, dark);
				Check.equal(label + ": draws of the kept chest on deck with another light than it had", open.changed(), 0);
				Check.equal(label + ": draws of the kept walled-in chest with another light than it had", dark.changed(), 0);
				Check.atLeast(label + ": lowest sky light the kept chest on deck was drawn with", open.darkestSky(), 14);
				Check.atLeast(label + ": lowest block light the kept chest on deck was drawn with (glowstone beside it)", open.darkestBlock(), 10);
				Check.equal(label + ": brightest sky light the kept walled-in chest was drawn with", dark.brightestSky(), 0);
				Check.equal(label + ": brightest block light the kept walled-in chest was drawn with", dark.brightestBlock(), 0);
			} else {
				Check.that(keptLight.isEmpty(), "%s: block entities of a kept picture were drawn with the picture turned off: %s", label, keptLight);
			}
		}
		Check.that(server.computeOnServer(s -> Game.manager(s).registry().get(id) == null), "the vessel was not disassembled");
		Game.waitTerrain(ctx, 600);
		ctx.waitTicks(10);
		Path after = Shots.take(ctx, r, label + "-2-after");
		// The same view without the ship: what a frame looks like when the ship is not drawn.
		server.runOnServer(s -> Ships.clear(s.overworld(), helm.offset(ship.min()), helm.offset(ship.max())));
		Shots.waitStill(ctx, r, label + "-cleared", 1200);
		Path cleared = grab(ctx, r, label + "-3-cleared");
		double gone = difference(before, cleared);
		double landed = difference(before, after);
		int missing = 0;
		double worst = 0;
		List<String> shares = new ArrayList<>();
		for (Path frame : frames) {
			double share = difference(before, frame) / gone;
			shares.add(String.format(Locale.ROOT, "%.2f", share));
			worst = Math.max(worst, share);
			if (share > 0.5) {
				missing++;
			}
			if (share > PART) {
				r.evidence(frame);
			}
		}
		r.metric(label + ".shipGoneMsd", gone);
		r.metric(label + ".afterLandingShareOfGone", landed / gone);
		r.metric(label + ".framesWithoutTheShip", missing);
		r.metric(label + ".worstFrameShareOfGone", worst);
		r.note("%s: difference from the picture before, as a share of the difference a missing ship makes, tick by tick: %s", label, shares);
		Check.atLeast(label + ": difference a missing ship makes in the picture (mean squared difference)", gone, 1.0e-3);
		Check.atMost(label + ": difference between the ship before and the blocks after, as a share of a missing ship", landed / gone, 0.25);
		return new Outcome(missing, worst, drawnTicks[0]);
	}

	/** A frame that differs from the picture before by more than this share of a missing ship lacks a part of it. */
	private static final double PART = 0.05;

	/** Disassembles a ship without and with the kept picture; with it, no frame may lack the ship or a part of it. */
	private static void pair(ClientGameTestContext ctx, TestServerContext server, Report.Result r, BlockPos helm, Ship ship, String label) {
		Outcome plain = run(ctx, server, r, helm, ship, false, label + "-plain");
		Outcome kept = run(ctx, server, r, helm, ship, true, label + "-kept");
		r.note("%s: without keeping the vessel's picture the ship was missing from %d of the %d frames after disassembly; with it, from %d (worst frame: %.3f of a missing ship)",
			label, plain.missing(), TICKS, kept.missing(), kept.worst());
		Check.equal(label + ": ticks the gone vessel was drawn with its picture turned off", plain.drawnTicks(), 0);
		// Two ticks without terrain work at the least, six at the most (ClientVessels.GONE_QUIET_TICKS, GONE_MAX_TICKS).
		Check.atLeast(label + ": ticks the gone vessel was still drawn", kept.drawnTicks(), 2);
		Check.atMost(label + ": ticks the gone vessel was still drawn", kept.drawnTicks(), 6);
		Check.equal(label + ": frames without the ship in the " + TICKS + " ticks after disassembly", kept.missing(), 0);
		Check.atMost(label + ": largest part of the ship any frame lacks (share of the difference a missing ship makes)", kept.worst(), PART);
	}

	static void disassembly(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 12, 30);
			int threads = BuildThreads.get();
			try {
				LooseScenarios.watch(ctx, sp, new Vec3(0.5, helm.getY() + 5, helm.getZ() + 14.5), Vec3.atCenterOf(helm));
				pair(ctx, server, r, helm, new Ship(ship(), new BlockPos(-4, -2, -4), new BlockPos(4, 3, 4), true), "small");

				Ship carrier = new Ship(LooseScenarios.carrier(), new BlockPos(-LooseScenarios.HALF_X, -3, -LooseScenarios.HALF_Z), new BlockPos(LooseScenarios.HALF_X, 0, LooseScenarios.HALF_Z), false);
				r.metric("carrier.blocks", carrier.blocks().size());
				LooseScenarios.watch(ctx, sp, new Vec3(0.5, helm.getY() + 14, helm.getZ() + 30.5), Vec3.atCenterOf(helm));
				pair(ctx, server, r, helm, carrier, "carrier");

				// One build thread: the terrain renderer hands out fewer sections per frame, as on a machine with few cores.
				r.metric("terrain.buildThreadsSetting", threads);
				BuildThreads.set(ctx, 1);
				Game.waitTerrain(ctx, 1200);
				pair(ctx, server, r, helm, carrier, "carrier-one-thread");
			} finally {
				ctx.runOnClient(mc -> ClientVessels.keepGoneVessels = true);
				BuildThreads.set(ctx, threads);
			}
		}
	}

	/** Sodium's number of terrain build threads (0: its own choice). Kept apart so Sodium's classes load only when it is present. */
	private static final class BuildThreads {
		static boolean sodium() {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
		}

		static int get() {
			return sodium() ? Sodium.get() : 0;
		}

		static void set(ClientGameTestContext ctx, int threads) {
			if (sodium()) {
				ctx.runOnClient(mc -> {
					Sodium.set(threads);
					mc.levelExtractor.allChanged();
				});
			}
		}

		private static final class Sodium {
			static int get() {
				return net.caffeinemc.mods.sodium.client.SodiumClientMod.options().performance.chunkBuilderThreads;
			}

			static void set(int threads) {
				net.caffeinemc.mods.sodium.client.SodiumClientMod.options().performance.chunkBuilderThreads = threads;
			}
		}
	}
}