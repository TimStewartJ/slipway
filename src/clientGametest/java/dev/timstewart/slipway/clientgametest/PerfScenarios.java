package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.physics.PhysicsWorld;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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

/**
 * perf: frame rate and server tick time with Bliss shaders on, in four phases of equal length: no vessel, a 961-block
 * barge hovering in view, the barge flying and turning, and no vessel again (warm). Frames are counted by the game
 * (one FPS sample a second); server tick times are read for every tick. Measured at 1280x720, the window size of the
 * M7 measurements, instead of the test runner's default size.
 */
final class PerfScenarios {
	static final int WIDTH = 1280;
	static final int HEIGHT = 720;

	private PerfScenarios() {
	}

	record Phase(String name, double[] fps, TickTimes ticks, double[] physicsMs) {
		double fpsMean() {
			return Arrays.stream(this.fps).average().orElse(Double.NaN);
		}

		double fpsLow() {
			return percentile(this.fps, 5);
		}

		double msptMean() {
			return this.ticks.mean();
		}

		double msptP95() {
			return this.ticks.p95();
		}

		double msptMax() {
			return this.ticks.worst();
		}
	}

	static void perf(ClientGameTestContext ctx, Report.Result r) {
		int phaseTicks = Integer.getInteger("slipway.clientGametest.perfPhaseTicks", 300);
		Game.options(ctx, o -> {
			o.framerateLimit().set(260);
			o.enableVsync().set(false);
			o.renderDistance().set(12);
		});
		Game.hud(ctx, false);
		int[] size = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
		ctx.getInput().resizeWindow(WIDTH, HEIGHT);
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			try {
				measure(ctx, sp, r, phaseTicks);
			} finally {
				RenderScenarios.shaders(ctx, r, false);
			}
		} finally {
			ctx.getInput().resizeWindow(size[0], size[1]);
		}
	}

	private static void measure(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r, int phaseTicks) {
		String window = ctx.computeOnClient(mc -> mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
		r.metric("window", window);
		Check.equal("window size while measuring", window, WIDTH + "x" + HEIGHT);
		TestServerContext server = sp.getServer();
		server.runCommand("time set 6000");
		RenderScenarios.shaders(ctx, r, true);
		int y = Game.GROUND_Y + 20;
		server.runCommand(String.format(Locale.ROOT, "fill -1 %d -1 1 %d 1 minecraft:glass", y - 1, y - 1));
		Game.teleport(ctx, sp, 0.5, y, 0.5, 0f, 5f);
		ctx.waitTicks(200);

		Map<String, Phase> phases = new LinkedHashMap<>();
		phases.put("baseline", phase(ctx, server, "baseline", phaseTicks, -1));
		BlockPos helm = new BlockPos(0, y, 40);
		server.runOnServer(s -> Ships.build(s.overworld(), helm, barge()));
		var record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
		long id = record.id;
		r.metric("barge.blocks", record.blockCount);
		Check.atLeast("barge block count", record.blockCount, 900);
		Game.waitClientReady(ctx, id, 400);
		ctx.waitTicks(80);
		phases.put("static", phase(ctx, server, "static", phaseTicks, id));
		server.runCommand(String.format(Locale.ROOT, "slipway control %d 0.1 0 0 0 0.3 0 %d", id, phaseTicks + 200));
		ctx.waitTicks(80);
		Shots.take(ctx, r, "01-barge-flying-bliss");
		double speed = server.computeOnServer(s -> Game.active(s, id).record.linearVelocity.length());
		double spin = server.computeOnServer(s -> Game.active(s, id).record.angularVelocity.length());
		phases.put("flying", phase(ctx, server, "flying", phaseTicks, id));
		r.metric("flying.speed", speed);
		r.metric("flying.spin", spin);
		Check.atLeast("barge speed while measuring (blocks per second)", speed, 1.0);
		Check.atLeast("barge spin while measuring (radians per second)", spin, 0.1);
		server.runOnServer(s -> Game.manager(s).remove(id));
		ctx.waitTicks(100);
		phases.put("warm", phase(ctx, server, "warm", phaseTicks, -1));

		for (Phase p : phases.values()) {
			r.metric(p.name() + ".fpsMean", p.fpsMean());
			r.metric(p.name() + ".fpsLow5", p.fpsLow());
			r.metric(p.name() + ".msptMean", p.msptMean());
			r.metric(p.name() + ".msptP95", p.msptP95());
			r.metric(p.name() + ".msptMax", p.msptMax());
			if (p.physicsMs().length > 0) {
				r.metric(p.name() + ".physicsStepMsMean", Arrays.stream(p.physicsMs()).average().orElse(Double.NaN));
				r.metric(p.name() + ".physicsStepMsMax", Arrays.stream(p.physicsMs()).max().orElse(Double.NaN));
			}
			Check.atLeast(p.name() + " FPS samples", p.fps().length, phaseTicks / 20 - 1);
		}
		Phase flying = phases.get("flying");
		Phase warm = phases.get("warm");
		double drop = 1.0 - flying.fpsMean() / warm.fpsMean();
		r.metric("fpsDropFlyingVsWarm", drop);
		r.metric("msptAddedFlyingVsWarm", flying.msptMean() - warm.msptMean());
		Check.atMost("server tick time while the barge flies, mean (ms)", flying.msptMean(), 15.0);
		flying.ticks().check(r, "flying");
		Check.atMost("frame-rate drop with the flying barge vs the warm baseline", drop, 0.5);
	}

	/** One measured phase: an FPS sample a second and every server tick's time. */
	private static Phase phase(ClientGameTestContext ctx, TestServerContext server, String name, int ticks, long vessel) {
		List<Double> fps = new ArrayList<>();
		TickTimes times = new TickTimes();
		List<Double> physics = new ArrayList<>();
		for (int i = 1; i <= ticks; i++) {
			ctx.waitTick();
			server.runOnServer(times::add);
			double step = server.computeOnServer(s -> {
				if (vessel < 0) {
					return Double.NaN;
				}
				PhysicsWorld world = Game.manager(s).physics().worldIfStarted();
				return world == null ? Double.NaN : world.lastStepNanos() / 1.0e6;
			});
			if (!Double.isNaN(step)) {
				physics.add(step);
			}
			if (i % 20 == 0) {
				fps.add((double)ctx.computeOnClient(mc -> mc.getFps()));
			}
		}
		SlipwayClientGameTests.LOG.info("perf phase {}: {} ticks", name, ticks);
		return new Phase(name, fps.stream().mapToDouble(Double::doubleValue).toArray(), times, physics.stream().mapToDouble(Double::doubleValue).toArray());
	}

	static double percentile(double[] values, double p) {
		if (values.length == 0) {
			return Double.NaN;
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int index = (int)Math.ceil(p / 100.0 * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
	}

	/** 20x16x3 concrete layers under a helm: 960 blocks and the helm. */
	static Map<BlockPos, BlockState> barge() {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		BlockState[] layers = {Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState(), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState()};
		for (int dy = -3; dy <= -1; dy++) {
			for (int x = -10; x <= 9; x++) {
				for (int z = -8; z <= 7; z++) {
					blocks.put(new BlockPos(x, dy, z), layers[dy + 3]);
				}
			}
		}
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}
}
