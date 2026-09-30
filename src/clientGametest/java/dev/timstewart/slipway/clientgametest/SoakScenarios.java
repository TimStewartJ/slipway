package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * soak: the mixed ship is flown from its helm with the pilot's keys through a one-minute program (thrust, turns,
 * climbs and dives, rolls with level mode off, strafing, hover off and on, and back) repeated for
 * {@code slipway.clientGametest.soakMinutes} minutes (20 for a release) with Bliss shaders on. Every second: the pose
 * is finite, the pilot is aboard, the block count is unchanged and the server keeps its tick time; at the end every
 * block and block-entity value is intact.
 */
final class SoakScenarios {
	private SoakScenarios() {
	}

	/** One step of the program: keys held for some seconds, or a mode set before idling. */
	private record Step(double seconds, String[] keys, Boolean level, Boolean hover) {
		static Step keys(double seconds, String... keys) {
			return new Step(seconds, keys, null, null);
		}

		static Step level(boolean on, double seconds) {
			return new Step(seconds, new String[0], on, null);
		}

		static Step hover(boolean on, double seconds) {
			return new Step(seconds, new String[0], null, on);
		}
	}

	private static final List<Step> PROGRAM = List.of(
		Step.keys(8, "key.forward"),
		Step.keys(6, "key.forward", "key.right"),
		Step.keys(3, "key.jump", "key.slipway.pitch_up"),
		Step.keys(4, "key.forward", "key.slipway.pitch_down"),
		Step.keys(3, "key.slipway.descend"),
		Step.level(true, 3),
		Step.level(false, 0),
		Step.keys(4, "key.slipway.roll_right"),
		Step.keys(4, "key.slipway.roll_right"),
		Step.level(true, 5),
		Step.keys(3, "key.slipway.strafe_left"),
		Step.keys(3, "key.slipway.strafe_right"),
		Step.hover(false, 2),
		Step.hover(true, 3),
		Step.keys(8, "key.back"),
		Step.keys(1));

	static void soak(ClientGameTestContext ctx, Report.Result r) {
		double minutes = Double.parseDouble(System.getProperty("slipway.clientGametest.soakMinutes", "2"));
		Check.that(minutes > 0 && minutes <= 180, "soak minutes out of range: %s", minutes);
		Game.options(ctx, o -> o.framerateLimit().set(120));
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			try {
				fly(ctx, sp, r, minutes);
			} finally {
				RenderScenarios.shaders(ctx, r, false);
			}
		}
	}

	private static void fly(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r, double minutes) {
		TestServerContext server = sp.getServer();
		server.runCommand("time set 6000");
		RenderScenarios.shaders(ctx, r, true);
		BlockPos helm = new BlockPos(0, Game.GROUND_Y + 60, 40);
		Map<BlockPos, BlockState> spec = Ships.mixedShip();
		server.runOnServer(s -> {
			Ships.build(s.overworld(), helm, spec);
			Ships.fillMixed(s.overworld(), helm);
		});
		VesselRecord record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
		long id = record.id;
		Map<BlockPos, CompoundTag> data = server.computeOnServer(s -> Ships.plotBlockEntityData(s.overworld(), record, spec.keySet()));
		Game.waitClientReady(ctx, id, 200);
		DeckScenarios.placeRider(ctx, server, id, new net.minecraft.world.phys.Vec3(0.5, 0.3, 2.5));
		Flight.takeHelm(ctx, server, id);
		double startY = Flight.sample(server, id).pose().y();

		long totalTicks = Math.round(minutes * 60 * 20);
		long tick = 0;
		int samples = 0;
		List<Double> mspt = new ArrayList<>();
		double heapStart = Double.NaN;
		int shots = 0;
		while (tick < totalTicks) {
			for (Step step : PROGRAM) {
				if (tick >= totalTicks) {
					break;
				}
				if (step.level() != null && server.computeOnServer(s -> Game.active(s, id).record.level) != step.level()) {
					ctx.getInput().pressKey(Game.key(ctx, "key.slipway.toggle_level"));
				}
				if (step.hover() != null && server.computeOnServer(s -> Game.active(s, id).record.hover) != step.hover()) {
					ctx.getInput().pressKey(Game.key(ctx, "key.slipway.toggle_hover"));
				}
				String[] keys = step.keys();
				// hold altitude within a few blocks of the start: climb or descend instead of idling
				double dy = Flight.sample(server, id).pose().y() - startY;
				if (keys.length == 0 && Math.abs(dy) > 6) {
					keys = new String[] {dy > 0 ? "key.slipway.descend" : "key.jump"};
				}
				List<net.minecraft.client.KeyMapping> held = new ArrayList<>();
				for (String key : keys) {
					held.add(Game.key(ctx, key));
				}
				held.forEach(k -> ctx.getInput().holdKey(k));
				try {
					int stepTicks = (int)Math.round(step.seconds() * 20);
					for (int i = 0; i < stepTicks && tick < totalTicks; i++, tick++) {
						ctx.waitTick();
						mspt.add(server.computeOnServer(PerfScenarios::lastTickMs));
						if (tick % 20 == 0) {
							check(ctx, server, id, record.blockCount, tick);
							samples++;
						}
						if (tick % 1200 == 0) {
							double heap = heapAfterGc();
							if (Double.isNaN(heapStart) && tick > 0) {
								heapStart = heap;
							}
							r.note("minute %d: heap after GC %.0f MB, altitude %+.1f", tick / 1200, heap, Flight.sample(server, id).pose().y() - startY);
						}
						if (tick % 6000 == 1200) {
							Shots.take(ctx, r, String.format("soak-%02dmin", tick / 1200));
							shots++;
						}
					}
				} finally {
					held.forEach(k -> ctx.getInput().releaseKey(k));
				}
			}
		}
		double heapEnd = heapAfterGc();
		Shots.take(ctx, r, "soak-end");
		double[] times = mspt.stream().mapToDouble(Double::doubleValue).toArray();
		r.metric("minutes", minutes);
		r.metric("ticks", tick);
		r.metric("samples", samples);
		r.metric("msptMean", java.util.Arrays.stream(times).average().orElse(Double.NaN));
		r.metric("msptP95", PerfScenarios.percentile(times, 95));
		r.metric("msptMax", java.util.Arrays.stream(times).max().orElse(Double.NaN));
		r.metric("heapAfterGcStartMb", heapStart);
		r.metric("heapAfterGcEndMb", heapEnd);
		Check.equal("ticks flown", tick, totalTicks);
		Check.that(samples >= totalTicks / 20, "only %d of %d one-second samples were taken", samples, totalTicks / 20);
		Check.atMost("server tick time, 95th percentile (ms)", PerfScenarios.percentile(times, 95), 20.0);
		Check.atMost("server tick time, worst (ms)", java.util.Arrays.stream(times).max().orElse(Double.NaN), 25.0);
		if (!Double.isNaN(heapStart)) {
			Check.atMost("heap growth after GC over the soak (MB)", heapEnd - heapStart, 256.0);
		}
		// The ship survived the whole flight.
		List<String> blocks = server.computeOnServer(s -> Ships.plotMismatches(s.overworld(), record, spec));
		Check.that(blocks.isEmpty(), "blocks changed during the soak: %s", blocks);
		Check.equal("block entities after the soak", server.computeOnServer(s -> Ships.plotBlockEntityData(s.overworld(), record, spec.keySet())), data);
	}

	/** One-second health check of the flight. */
	private static void check(ClientGameTestContext ctx, TestServerContext server, long id, int blocks, long tick) {
		Flight.Sample s = Flight.sample(server, id);
		var p = s.pose();
		Check.finite("pose at tick " + tick, p.x(), p.y(), p.z(), p.qx(), p.qy(), p.qz(), p.qw());
		Check.finite("velocity at tick " + tick, s.velocity().x, s.velocity().y, s.velocity().z, s.angularVelocity().x, s.angularVelocity().y, s.angularVelocity().z);
		Check.that(s.piloted(), "tick %d: the pilot is no longer at the helm", tick);
		int now = server.computeOnServer(sv -> {
			ActiveVessel v = Game.active(sv, id);
			return v.record.blockCount;
		});
		Check.equal("block count at tick " + tick, now, blocks);
		Check.that(ctx.computeOnClient(mc -> mc.level != null && mc.player != null), "tick %d: the client left the world", tick);
	}

	static double heapAfterGc() {
		System.gc();
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
	}
}
