package dev.timstewart.slipway.clientgametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;

/**
 * Server tick times, one per tick: the tick's own time and its server tick number. Summaries: mean, 95th percentile,
 * worst tick, and the worst 100-tick average (the average the game itself reports, evaluated after every tick), plus
 * the worst ticks and whether a world save ran in them.
 */
final class TickTimes {
	private final List<double[]> ticks = new ArrayList<>();

	/** Records the tick that just finished. */
	void add(MinecraftServer server) {
		long[] times = server.getTickTimesNanos();
		int count = server.getTickCount();
		this.ticks.add(new double[] {times[count % times.length] / 1.0e6, count});
	}

	int size() {
		return this.ticks.size();
	}

	double[] millis() {
		return this.ticks.stream().mapToDouble(t -> t[0]).toArray();
	}

	double mean() {
		return Arrays.stream(this.millis()).average().orElse(Double.NaN);
	}

	double p95() {
		return PerfScenarios.percentile(this.millis(), 95);
	}

	double worst() {
		return Arrays.stream(this.millis()).max().orElse(Double.NaN);
	}

	/** The highest average over any 100 consecutive ticks (the game's own tick-time average, at every tick). */
	double worstAverage100() {
		double[] ms = this.millis();
		if (ms.length < 100) {
			return Arrays.stream(ms).average().orElse(Double.NaN);
		}
		double sum = 0;
		for (int i = 0; i < 100; i++) {
			sum += ms[i];
		}
		double worst = sum / 100;
		for (int i = 100; i < ms.length; i++) {
			sum += ms[i] - ms[i - 100];
			worst = Math.max(worst, sum / 100);
		}
		return worst;
	}

	/** The worst ticks, each with its server tick number and whether a world save ran in it. */
	String worstTicks(int n) {
		List<String> out = new ArrayList<>();
		this.ticks.stream().sorted(Comparator.comparingDouble((double[] t) -> t[0]).reversed()).limit(n).forEach(t -> {
			int tick = (int)t[1];
			boolean saved = Lifecycle.SAVE_TICKS.stream().anyMatch(s -> Math.abs(s - tick) <= 1);
			out.add(String.format(Locale.ROOT, "%.1f ms at tick %d%s", t[0], tick, saved ? " (world save)" : ""));
		});
		return String.join(", ", out);
	}

	/**
	 * The acceptance limits for a flying vessel, equal to or stricter than the old harness's (which sampled the 100-tick
	 * average every 30 s): the 100-tick average after every tick under 25 ms, 95% of ticks under 20 ms, and every tick
	 * inside the 50 ms tick budget. A lone longer tick (a world save) is reported with the worst ticks.
	 */
	void check(Report.Result r, String phase) {
		r.metric(phase + ".msptMean", this.mean());
		r.metric(phase + ".msptP95", this.p95());
		r.metric(phase + ".msptMax", this.worst());
		r.metric(phase + ".msptWorst100TickAverage", this.worstAverage100());
		r.metric(phase + ".worstTicks", this.worstTicks(5));
		Check.atMost(phase + ": server tick time, worst 100-tick average (ms)", this.worstAverage100(), 25.0);
		Check.atMost(phase + ": server tick time, 95th percentile (ms)", this.p95(), 20.0);
		Check.atMost(phase + ": server tick time, worst single tick (ms; the tick budget is 50)", this.worst(), 50.0);
	}
}
