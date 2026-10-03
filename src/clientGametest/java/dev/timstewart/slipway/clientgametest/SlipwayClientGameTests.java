package dev.timstewart.slipway.clientgametest;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slipway's in-game tests on a real client (Fabric client GameTests). Fabric's runner stops at the first exception, so
 * this one entrypoint runs every scenario itself: each gets a clean title screen and default options, a failure is
 * recorded and the next scenario still runs, and the whole run fails at the end if any scenario failed.
 * {@code -Dslipway.clientGametest.only=a,b} selects scenarios; the report goes to {@code slipway.clientGametest.reportDir}.
 */
public final class SlipwayClientGameTests implements FabricClientGameTest {
	static final Logger LOG = LoggerFactory.getLogger("slipway-client-gametest");

	/** One in-game scenario. */
	interface Body {
		void run(ClientGameTestContext ctx, Report.Result result) throws Exception;
	}

	record Scenario(String name, Body body) {
	}

	/** Every scenario, in the order they run (cheap functional checks first, the long soak last). */
	static List<Scenario> scenarios() {
		return List.of(
			new Scenario("assemble-mixed", AssemblyScenarios::assembleMixed),
			new Scenario("flight-rotation", FlightScenarios::flightRotation),
			new Scenario("deck-walk", DeckScenarios::deckWalk),
			new Scenario("deck-jump", JumpScenarios::deckJump),
			new Scenario("interaction", InteractionScenarios::interaction),
			new Scenario("collision", CollisionScenarios::collision),
			new Scenario("small-vessel-light", LightScenarios::smallVesselLight),
			new Scenario("loose-cargo", LooseScenarios::looseCargo),
			new Scenario("afloat", AfloatScenarios::afloat),
			new Scenario("block-events", BlockEventScenarios::blockEvents),
			new Scenario("farm", FarmScenarios::farm),
			new Scenario("disassembly", DisassemblyScenarios::disassembly),
			new Scenario("forged-packets", PacketScenarios::forgedPackets),
			new Scenario("save-reload", SaveScenarios::saveReload),
			new Scenario("render-iris", RenderScenarios::renderIris),
			new Scenario("multiplayer", MultiplayerScenarios::multiplayer),
			new Scenario("perf", PerfScenarios::perf),
			new Scenario("leak", LeakScenarios::leak),
			new Scenario("soak", SoakScenarios::soak));
	}

	/** Diagnostic scenarios: run only when named in {@code slipway.clientGametest.only}, never by default. */
	static List<Scenario> diagnostics() {
		return List.of(new Scenario("diag-plain-ship", DiagScenarios::plainShip), new Scenario("make-harbour", HarbourScenarios::makeHarbour),
			new Scenario("diag-water-patch", DiagScenarios::waterPatch));
	}

	static Path reportDir() {
		return Path.of(System.getProperty("slipway.clientGametest.reportDir", "slipway-client-gametest")).toAbsolutePath();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if ("watcher".equals(System.getProperty("slipway.clientGametest.role"))) {
			Watcher.run(ctx);
			return;
		}
		Set<String> only = Arrays.stream(System.getProperty("slipway.clientGametest.only", "").split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
		List<Scenario> all = new java.util.ArrayList<>(scenarios());
		diagnostics().stream().filter(d -> only.contains(d.name())).forEach(all::add);
		Set<String> known = java.util.stream.Stream.concat(scenarios().stream(), diagnostics().stream()).map(Scenario::name).collect(Collectors.toSet());
		for (String name : only) {
			Check.that(known.contains(name), "unknown scenario %s (known: %s)", name, known);
		}
		Report report = new Report(reportDir());
		boolean usable = true;
		for (Scenario scenario : all) {
			if (!only.isEmpty() && !only.contains(scenario.name())) {
				continue;
			}
			Report.Result result = report.start(scenario.name());
			if (!usable) {
				result.message = "not run: the game could not be brought back to the title screen after an earlier failure";
				continue;
			}
			LOG.info("=== Slipway client GameTest {} ===", scenario.name());
			long start = System.nanoTime();
			try {
				Check.that(Game.clean(ctx), "the game is not on the title screen before %s", scenario.name());
				Shots.clear(result);
				Game.applyTestOptions(ctx);
				scenario.body().run(ctx, result);
				Check.that(Game.clean(ctx), "%s did not end on the title screen with no world open", scenario.name());
				result.status = Report.Status.PASSED;
			} catch (Throwable t) {
				result.status = Report.Status.FAILED;
				result.message = t.getClass().getSimpleName() + ": " + t.getMessage();
				result.stackTrace = Report.stackTrace(t);
				LOG.error("Slipway client GameTest {} failed", scenario.name(), t);
				usable = Game.recover(ctx);
			} finally {
				result.seconds = (System.nanoTime() - start) / 1.0e9;
				LOG.info("=== {} {} in {} s ===", scenario.name(), result.status, String.format(Locale.ROOT, "%.1f", result.seconds));
				report.write();
			}
		}
		report.write();
		long failed = report.count(Report.Status.FAILED) + report.count(Report.Status.NOT_RUN);
		LOG.info("Slipway client GameTests: {} passed, {} failed, {} not run; report in {}", report.count(Report.Status.PASSED),
			report.count(Report.Status.FAILED), report.count(Report.Status.NOT_RUN), report.dir());
		if (failed > 0) {
			throw new AssertionError(failed + " Slipway client GameTest scenario(s) failed or did not run: " + report.results().stream()
				.filter(r -> r.status != Report.Status.PASSED).map(r -> r.name + " (" + r.message + ")").collect(Collectors.joining("; ")));
		}
	}
}
