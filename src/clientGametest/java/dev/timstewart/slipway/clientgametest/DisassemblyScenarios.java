package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * disassembly: a ship hovering where it was built is disassembled while the player looks at it, and a picture is
 * taken after every one of the next ten ticks. The ship must be in every one of them: the vessel's picture is kept
 * until the terrain shows the blocks that were put back into the world. The same is done once with that turned off,
 * to record the blink it prevents (the ship missing from the pictures of a tick or two).
 */
final class DisassemblyScenarios {
	private DisassemblyScenarios() {
	}

	static Map<BlockPos, BlockState> ship() {
		Map<BlockPos, BlockState> blocks = Ships.deck(3, Blocks.GOLD_BLOCK.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		blocks.put(new BlockPos(2, 0, 2), Blocks.CHEST.defaultBlockState());
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

	/** Builds, assembles and disassembles the ship once; returns how many of the ten pictures after it lack the ship. */
	private static int run(ClientGameTestContext ctx, TestServerContext server, Report.Result r, BlockPos helm, boolean keep, String label) {
		server.runOnServer(s -> Ships.build(s.overworld(), helm, ship()));
		VesselRecord record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
		long id = record.id;
		Game.waitClientReady(ctx, id, 200);
		Game.waitClientComplete(ctx, id, 400);
		Shots.waitStill(ctx, r, label + "-assembled", 1200);
		Path before = Shots.take(ctx, r, label + "-1-before");
		ctx.runOnClient(mc -> ClientVessels.keepGoneVessels = keep);
		server.runCommand("slipway disassemble " + id);
		List<Path> frames = new ArrayList<>();
		for (int tick = 1; tick <= 10; tick++) {
			ctx.waitTick();
			frames.add(grab(ctx, r, label + "-tick-" + tick));
		}
		Check.that(server.computeOnServer(s -> Game.manager(s).registry().get(id) == null), "the vessel was not disassembled");
		Game.waitTerrain(ctx, 600);
		ctx.waitTicks(10);
		Path after = Shots.take(ctx, r, label + "-2-after");
		// The same view without the ship: what a picture looks like when the ship is not drawn.
		server.runOnServer(s -> Ships.clear(s.overworld(), helm.offset(-4, -2, -4), helm.offset(4, 3, 4)));
		Shots.waitStill(ctx, r, label + "-cleared", 1200);
		Path cleared = grab(ctx, r, label + "-3-cleared");
		double gone = difference(before, cleared);
		double landed = difference(before, after);
		int missing = 0;
		double worst = 0;
		List<String> shares = new ArrayList<>();
		for (int i = 0; i < frames.size(); i++) {
			double share = difference(before, frames.get(i)) / gone;
			shares.add(String.format(Locale.ROOT, "%.2f", share));
			worst = Math.max(worst, share);
			if (share > 0.5) {
				missing++;
				r.evidence(frames.get(i));
			}
		}
		r.metric(label + ".shipGoneMsd", gone);
		r.metric(label + ".afterLandingShareOfGone", landed / gone);
		r.metric(label + ".picturesWithoutTheShip", missing);
		r.metric(label + ".worstPictureShareOfGone", worst);
		r.note("%s: difference from the picture before, as a share of the difference a missing ship makes, tick by tick: %s", label, shares);
		Check.atLeast(label + ": difference a missing ship makes in the picture (mean squared difference)", gone, 1.0e-3);
		Check.atMost(label + ": difference between the ship before and the blocks after, as a share of a missing ship", landed / gone, 0.25);
		return missing;
	}

	static void disassembly(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 12, 30);
			LooseScenarios.watch(ctx, sp, new Vec3(0.5, helm.getY() + 5, helm.getZ() + 14.5), Vec3.atCenterOf(helm));
			try {
				int plain = run(ctx, server, r, helm, false, "plain");
				int kept = run(ctx, server, r, helm, true, "kept");
				r.note("without keeping the vessel's picture the ship was missing from %d of the 10 pictures after disassembly; with it, from %d", plain, kept);
				Check.equal("pictures without the ship in the ten ticks after disassembly", kept, 0);
			} finally {
				ctx.runOnClient(mc -> ClientVessels.keepGoneVessels = true);
			}
		}
	}
}