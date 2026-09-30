package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.vessel.VesselManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * collision: a 5x5 box ship with hover off drops onto a stone pad (terrain) and comes to rest on it, level, without
 * sinking in; then one box ship flies into another, which is pushed away without the two passing into each other.
 * Every tick is sampled.
 */
final class CollisionScenarios {
	private CollisionScenarios() {
	}

	/** A 5x5 floor with a 5x5 ring of walls two high and a helm in the middle. */
	static Map<BlockPos, BlockState> box(Direction facing) {
		Map<BlockPos, BlockState> blocks = Ships.deck(2, Blocks.OAK_PLANKS.defaultBlockState());
		for (int y = 0; y <= 1; y++) {
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					if (Math.abs(x) == 2 || Math.abs(z) == 2) {
						blocks.put(new BlockPos(x, y, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
					}
				}
			}
		}
		blocks.put(BlockPos.ZERO, Ships.helm(facing));
		return blocks;
	}

	record Tick(Vec3 centreA, Vec3 centreB, double speedA, double tiltA) {
	}

	static void collision(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			// A stone pad (terrain) 10 blocks above the flat ground; its top face is at y = padTop.
			int padY = Game.GROUND_Y + 10;
			int padTop = padY + 1;
			BlockPos padCentre = new BlockPos(0, padY, 80);
			server.runOnServer(s -> {
				for (BlockPos p : BlockPos.betweenClosed(padCentre.offset(-8, 0, -8), padCentre.offset(8, 0, 8))) {
					s.overworld().setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2 | 16);
				}
			});
			Game.teleport(ctx, sp, 14.5, padTop, 80.5, 90f, 10f);

			// Drop onto the pad.
			BlockPos helmA = new BlockPos(0, padTop + 15, 80);
			server.runOnServer(s -> Ships.build(s.overworld(), helmA, box(Direction.NORTH)));
			long a = server.computeOnServer(s -> Ships.assemble(s.overworld(), helmA).id);
			server.runCommand("slipway mode " + a + " hover false");
			Game.waitClientReady(ctx, a, 100);
			List<Flight.Sample> fall = new ArrayList<>();
			for (int i = 0; i < 300; i++) {
				ctx.waitTick();
				Flight.Sample s = Flight.sample(server, a);
				fall.add(s);
				if (i > 40 && s.velocity().length() < 0.02 && s.angularVelocity().length() < 0.02) {
					break;
				}
			}
			Flight.checkContinuous("fall", fall);
			Flight.Sample rest = fall.getLast();
			// The helm stands on the floor, which rests on the pad: the helm's block is at padTop + 1.
			double expectedY = padTop + 1;
			double lowest = fall.stream().mapToDouble(s -> s.pose().y()).min().orElse(Double.NaN);
			r.metric("fall.ticks", fall.size());
			r.metric("fall.restY", rest.pose().y());
			r.metric("fall.lowestY", lowest);
			r.metric("fall.restSpeed", rest.velocity().length());
			r.metric("fall.restTilt", rest.tilt());
			Check.near("resting height on the pad", rest.pose().y(), expectedY, 0.25);
			Check.atLeast("lowest point while landing (no sinking into the pad)", lowest, expectedY - 0.3);
			Check.atMost("speed at rest", rest.velocity().length(), 0.05);
			Check.atMost("tilt at rest", rest.tilt(), 5.0);
			Shots.take(ctx, r, "01-landed");

			// One vessel rams another.
			BlockPos helmB = new BlockPos(0, padTop + 25, 120);
			BlockPos helmD = new BlockPos(0, padTop + 25, 132);
			server.runOnServer(s -> {
				// a helm facing north drives its vessel towards +z (as in the old harness), at D
				Ships.build(s.overworld(), helmB, box(Direction.NORTH));
				Ships.build(s.overworld(), helmD, box(Direction.NORTH));
			});
			long b = server.computeOnServer(s -> Ships.assemble(s.overworld(), helmB).id);
			long d = server.computeOnServer(s -> Ships.assemble(s.overworld(), helmD).id);
			Game.teleport(ctx, sp, 14.5, padTop + 25, 126.5, 90f, 0f);
			server.runCommand("fill 12 " + (padTop + 24) + " 125 16 " + (padTop + 24) + " 128 minecraft:glass");
			Game.waitClientReady(ctx, b, 100);
			Game.waitClientReady(ctx, d, 100);
			server.waitFor(s -> Game.active(s, b).hasBody && Game.active(s, d).hasBody, 200);
			ctx.waitTicks(20);
			Vec3 dStart = server.computeOnServer(s -> VesselManager.worldCentre(Game.active(s, d).record));
			server.runCommand("slipway control " + b + " 1 0 0 0 0 0 80");
			List<Tick> ram = new ArrayList<>();
			double minGap = Double.POSITIVE_INFINITY;
			double maxPushSpeed = 0.0;
			for (int i = 0; i < 140; i++) {
				ctx.waitTick();
				Tick t = server.computeOnServer(s -> new Tick(VesselManager.worldCentre(Game.active(s, b).record), VesselManager.worldCentre(Game.active(s, d).record),
					Game.active(s, d).record.linearVelocity.z, 0));
				ram.add(t);
				minGap = Math.min(minGap, t.centreA().distanceTo(t.centreB()));
				maxPushSpeed = Math.max(maxPushSpeed, t.speedA());
				Check.that(t.centreB().z > t.centreA().z, "tick %d: the rammed vessel is no longer ahead of the rammer (passed through)", i);
			}
			Vec3 dEnd = ram.getLast().centreB();
			double pushed = dEnd.z - dStart.z;
			r.metric("ram.minCentreGap", minGap);
			r.metric("ram.pushedBlocks", pushed);
			r.metric("ram.maxPushSpeed", maxPushSpeed);
			Check.that(minGap >= 4.7 && minGap <= 6.5, "closest centre distance of two 5-wide vessels %.3f, expected contact (4.7..6.5, 5.0 at touch)", minGap);
			Check.atLeast("distance the rammed vessel was pushed along the ram direction", pushed, 0.5);
			Check.atLeast("speed the rammed vessel picked up along +z (blocks per second)", maxPushSpeed, 0.1);
			Shots.take(ctx, r, "02-vessels-after");
		}
	}
}
