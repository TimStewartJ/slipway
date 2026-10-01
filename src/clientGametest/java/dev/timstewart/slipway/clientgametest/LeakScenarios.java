package dev.timstewart.slipway.clientgametest;

import com.sun.management.HotSpotDiagnosticMXBean;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.ClosedWorldCleanup;
import dev.timstewart.slipway.physics.jolt.JoltEngine;
import dev.timstewart.slipway.vessel.VesselManager;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * leak: the same saved world, with three vessels that are flown every time, is opened and closed again and again in one
 * game, first without shaders and then with Bliss. After every close, at the title screen and after full garbage
 * collections, nothing of that world may remain: each cycle's IntegratedServer, ServerLevels and ClientLevel must be
 * collected (weak references), Slipway must hold no Jolt engine, body, level or vessel, and Iris's pipeline cache must
 * be empty. Across cycles the heap after GC must not keep growing, no thread group may keep growing and (without
 * shaders) native memory must not keep growing. A surviving world writes a heap dump for the path to its GC roots.
 */
final class LeakScenarios {
	private LeakScenarios() {
	}

	record Cycle(int n, double heapMb, double privateMb, Map<String, Integer> threads, Map<String, Long> live) {
	}

	static void leak(ClientGameTestContext ctx, Report.Result r) throws Exception {
		int cycles = Integer.getInteger("slipway.clientGametest.leakCycles", 5);
		Check.that(cycles >= 3, "the leak test needs at least 3 cycles, got %d", cycles);
		// Worlds of earlier scenarios (including the multiplayer test's dedicated server) must be gone as well.
		gc(ctx);
		Cycle before = measure(0);
		r.note("before the first cycle: live %s", before.live());
		if (before.live().get("ServerLevel") > 0 || before.live().get("IntegratedServer") > 0 || before.live().get("ClientLevel") > 0) {
			Path dump = dumpHeap(r, "leak-before");
			throw new AssertionError("worlds of earlier scenarios are still reachable at the title screen: " + before.live() + "; heap dump " + dump);
		}
		// Every step that touches a world runs in its own method: a frame's dead locals are GC roots, so a test
		// context (which references the server) left in this frame would keep the world alive by itself.
		TestWorldSave save = createWorld(ctx);
		runPhase(ctx, r, save, cycles, false);
		runPhase(ctx, r, save, cycles, true);
	}

	/** The world with three vessels, made once and closed; only its save handle is kept. */
	private static TestWorldSave createWorld(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			for (int k = 0; k < 3; k++) {
				BlockPos helm = new BlockPos(-14 + 14 * k, Game.GROUND_Y + 20, 24);
				Map<BlockPos, BlockState> deck = Ships.deck(2, Blocks.OAK_PLANKS.defaultBlockState());
				deck.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
				deck.put(new BlockPos(1, 0, 1), Blocks.CHEST.defaultBlockState());
				server.runOnServer(s -> Ships.build(s.overworld(), helm, deck));
				server.runOnServer(s -> Ships.assemble(s.overworld(), helm));
			}
			ctx.waitTicks(40);
			return sp.getWorldSave();
		}
	}

	/** Opens the world, checks physics runs, flies its vessels and closes it, remembering its worlds weakly. */
	private static void openFlyClose(ClientGameTestContext ctx, Report.Result r, TestWorldSave save, String phase, boolean shaders, int n,
		List<WeakReference<Object>> worlds, List<String> worldNames) {
		try (TestSingleplayerContext sp = save.open()) {
			TestServerContext server = sp.getServer();
			if (shaders) {
				RenderScenarios.checkPackInUse(ctx, r);
			}
			ctx.waitFor(mc -> ClientVessels.all().stream().filter(v -> v.ready()).count() >= 3, 1200);
			// The client has a vessel from the first packets about its entity; its body is made when its plot chunks
			// have loaded, which under load can be some ticks later.
			for (int waited = 0; JoltEngine.liveEngines() < 1 || JoltEngine.liveBodies() < 3; waited++) {
				Check.that(waited < 400, "%s cycle %d: physics is not running with the world open (engines %d, bodies %d)", phase, n, JoltEngine.liveEngines(), JoltEngine.liveBodies());
				ctx.waitTick();
			}
			List<Long> ids = server.computeOnServer(s -> Game.manager(s).registry().all().stream().map(v -> v.id).toList());
			Check.equal(phase + " cycle " + n + ": vessels in the world", ids.size(), 3);
			for (long id : ids) {
				server.runCommand("slipway control " + id + " 0.3 0 0.1 0 0.4 0 200");
			}
			ctx.waitTicks(100);
			double fastest = server.computeOnServer(s -> Game.manager(s).activeVessels().stream().mapToDouble(v -> v.record.linearVelocity.length()).max().orElse(0));
			Check.atLeast(phase + " cycle " + n + ": fastest vessel speed", fastest, 0.5);
			server.runOnServer(s -> {
				worlds.add(new WeakReference<>(s));
				worldNames.add("cycle " + n + " IntegratedServer");
				for (ServerLevel level : s.getAllLevels()) {
					worlds.add(new WeakReference<>(level));
					worldNames.add("cycle " + n + " ServerLevel " + level.dimension().identifier());
				}
			});
			ctx.runOnClient(mc -> {
				worlds.add(new WeakReference<>(mc.level));
				worldNames.add("cycle " + n + " ClientLevel");
			});
		}
	}

	private static void runPhase(ClientGameTestContext ctx, Report.Result r, TestWorldSave save, int cycles, boolean shaders) throws Exception {
		String phase = shaders ? "bliss" : "plain";
		if (shaders) {
			RenderScenarios.shaders(ctx, r, true);
		}
		try {
			List<Cycle> rows = new ArrayList<>();
			List<WeakReference<Object>> worlds = new ArrayList<>();
			List<String> worldNames = new ArrayList<>();
			for (int n = 1; n <= cycles; n++) {
				int releasesBefore = ClosedWorldCleanup.releases();
				openFlyClose(ctx, r, save, phase, shaders, n, worlds, worldNames);
				ctx.waitTicks(20);
				Check.equal(phase + " cycle " + n + ": closed-world cleanups run", ClosedWorldCleanup.releases() - releasesBefore, 1);
				if (FabricLoader.getInstance().isModLoaded("iris")) {
					Check.that(ClosedWorldCleanup.irisCacheFound(), "Iris is loaded but its pipeline override cache was not found");
					Check.equal(phase + " cycle " + n + ": entries left in Iris's pipeline override cache", irisOverrides(), 0);
				}
				Check.equal(phase + " cycle " + n + ": live Jolt engines after close", JoltEngine.liveEngines(), 0);
				Check.equal(phase + " cycle " + n + ": live Jolt bodies after close", JoltEngine.liveBodies(), 0);
				Check.equal(phase + " cycle " + n + ": levels Slipway manages after close", VesselManager.managerCount(), 0);
				Check.equal(phase + " cycle " + n + ": client vessels after close", ctx.computeOnClient(mc -> ClientVessels.all().size()), 0);
				gc(ctx);
				// With a shader pack Iris keeps its current pipeline after the world closes (it rebuilds pipelines only
				// when the dimension changes), and that pipeline's uniforms hold the ClientLevel it was built with: the
				// newest world's ClientLevel may stay until the next world loads. Every older world must be gone, and
				// no server object ever (see DESIGN.md, world-retention RCA).
				String heldByIris = shaders ? "cycle " + n + " ClientLevel" : null;
				List<String> alive = new ArrayList<>();
				for (int i = 0; i < worlds.size(); i++) {
					if (worlds.get(i).get() != null && !worldNames.get(i).equals(heldByIris)) {
						alive.add(worldNames.get(i));
					}
				}
				Cycle row = measure(n);
				rows.add(row);
				r.note("%s cycle %d: heap after GC %.1f MB, private %.1f MB, threads %d, live %s", phase, n, row.heapMb(), row.privateMb(),
					row.threads().values().stream().mapToInt(Integer::intValue).sum(), row.live());
				if (!alive.isEmpty()) {
					Path dump = dumpHeap(r, String.format(Locale.ROOT, "leak-%s-cycle%d", phase, n));
					throw new AssertionError(phase + " cycle " + n + ": closed worlds are still reachable after GC: " + alive + "; heap dump " + dump);
				}
				if (row.live().get("IntegratedServer") > 0 || row.live().get("ServerLevel") > 0 || row.live().get("ClientLevel") > (shaders ? 1 : 0)) {
					Path dump = dumpHeap(r, String.format(Locale.ROOT, "leak-%s-cycle%d-counts", phase, n));
					r.note("unexpected live world objects %s; heap dump %s", row.live(), dump);
				}
				Check.equal(phase + " cycle " + n + ": live IntegratedServer objects", row.live().get("IntegratedServer"), 0L);
				Check.equal(phase + " cycle " + n + ": live ServerLevel objects", row.live().get("ServerLevel"), 0L);
				Check.atMost(phase + " cycle " + n + ": live ClientLevel objects", row.live().get("ClientLevel"), shaders ? 1 : 0);
				// jolt-jni's static PhysicsSystem map kept every closed engine's system until Slipway called forgetMe().
				Check.equal(phase + " cycle " + n + ": live jolt-jni PhysicsSystem objects", row.live().get("PhysicsSystem"), 0L);
			}
			Cycle second = rows.get(1);
			Cycle last = rows.getLast();
			int span = Math.max(1, rows.size() - 2);
			double heapPerCycle = (last.heapMb() - second.heapMb()) / span;
			double privatePerCycle = (last.privateMb() - second.privateMb()) / span;
			r.metric(phase + ".heapAfterGcMb", rows.stream().map(c -> String.format(Locale.ROOT, "%.0f", c.heapMb())).toList().toString());
			r.metric(phase + ".privateMb", rows.stream().map(c -> String.format(Locale.ROOT, "%.0f", c.privateMb())).toList().toString());
			r.metric(phase + ".heapPerCycleMb", heapPerCycle);
			r.metric(phase + ".privatePerCycleMb", privatePerCycle);
			Check.atMost(phase + ": heap after GC growth per cycle from cycle 2 (MB)", heapPerCycle, 16.0);
			// A per-world thread leak adds threads on (nearly) every reopening: flag groups that rose in at least half of
			// the cycle-to-cycle steps from cycle 2 and by at least 2 overall. (One-off JDK helpers such as the HTTP
			// keep-alive timer come and go.) Netty's local event-loop group is one static pool of at most 2 x processors
			// threads that starts them lazily, round-robin, as connections are made (vanilla EventLoopGroupHolder.LOCAL).
			Map<String, Integer> grew = new TreeMap<>();
			int nettyCap = 2 * Runtime.getRuntime().availableProcessors();
			for (String name : last.threads().keySet()) {
				int rises = 0;
				for (int i = 2; i < rows.size(); i++) {
					if (rows.get(i).threads().getOrDefault(name, 0) > rows.get(i - 1).threads().getOrDefault(name, 0)) {
						rises++;
					}
				}
				int growth = last.threads().get(name) - second.threads().getOrDefault(name, 0);
				boolean boundedNetty = name.startsWith("Netty Local IO") && last.threads().get(name) <= nettyCap;
				if (!boundedNetty && growth >= 2 && rises * 2 >= rows.size() - 2) {
					grew.put(name, growth);
				}
			}
			if (!grew.isEmpty()) {
				// A thread that ends by itself is no leak, however many of its kind there are at one moment. Distant
				// Horizons starts two timer threads per level of every world ("DH-ChunkSaveIgnoreTimer") and never
				// cancels them: each ends when its last task has run (5 s after the last chunk DH generated) and its
				// generator has been collected. A second after a world closes they are there or not, depending on
				// whether DH generated a chunk in that world's last seconds, so the counts at the cycles' ends can
				// rise (0, 0, 6, 6, 12 in one run; 0, 6, 0, 0, 0 in another) with nothing piling up. What rose is
				// therefore counted again once such threads have had time to end; a thread leaked per world stays.
				r.metric(phase + ".threadGroupsThatRoseAtCycleEnds", grew.toString());
				ctx.waitTicks(20 * 8);
				Map<String, Integer> later = new HashMap<>();
				for (int round = 0; round < 5 && !grew.isEmpty(); round++) {
					gc(ctx);
					ctx.waitTicks(20);
					later.clear();
					later.putAll(threadGroups());
					grew.keySet().removeIf(name -> later.getOrDefault(name, 0) - second.threads().getOrDefault(name, 0) < 2);
				}
				Map<String, Integer> settled = new TreeMap<>();
				for (String name : last.threads().keySet()) {
					if (!later.getOrDefault(name, 0).equals(last.threads().get(name))) {
						settled.put(name, later.getOrDefault(name, 0));
					}
				}
				r.metric(phase + ".threadGroupsThatChangedAfterTheLastCycle", settled.toString());
				grew.replaceAll((name, growth) -> later.getOrDefault(name, 0) - second.threads().getOrDefault(name, 0));
			}
			r.metric(phase + ".threadGroupsThatGrew", grew.toString());
			// Every group whose size changed, per cycle, so the report shows where the total went.
			Map<String, List<Integer>> changing = new TreeMap<>();
			java.util.Set<String> names = new java.util.TreeSet<>();
			rows.forEach(c -> names.addAll(c.threads().keySet()));
			for (String name : names) {
				List<Integer> counts = rows.stream().map(c -> c.threads().getOrDefault(name, 0)).toList();
				if (counts.stream().distinct().count() > 1) {
					changing.put(name, counts);
				}
			}
			r.metric(phase + ".threadGroupsThatChanged", changing.toString());
			Check.that(grew.isEmpty(), "%s: thread groups kept growing across world loads: %s", phase, grew);
			if (!shaders) {
				Check.atMost(phase + ": native memory (private bytes) growth per cycle from cycle 2 (MB)", privatePerCycle, 64.0);
			}
		} finally {
			if (shaders) {
				RenderScenarios.shaders(ctx, r, false);
			}
		}
	}

	/** Full collections, with game ticks between them so reference processing and cleaners run. */
	static void gc(ClientGameTestContext ctx) {
		for (int i = 0; i < 3; i++) {
			System.gc();
			ctx.waitTicks(2);
		}
	}

	/** A heap dump of live objects in the report directory, for the path from a surviving world to its GC roots. */
	static Path dumpHeap(Report.Result r, String name) throws java.io.IOException {
		Path dump = SlipwayClientGameTests.reportDir().resolve(name + "-" + System.currentTimeMillis() + ".hprof");
		ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
		r.evidence(dump);
		return dump;
	}

	static Cycle measure(int n) throws Exception {
		double heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
		double privateMb = ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getCommittedVirtualMemorySize() / 1048576.0;
		Map<String, Integer> threads = threadGroups();
		Map<String, Long> live = new java.util.LinkedHashMap<>();
		String histogram = (String)ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
			new Object[] {new String[0]}, new String[] {String[].class.getName()});
		Map<String, String> classes = Map.of("IntegratedServer", "net.minecraft.client.server.IntegratedServer", "ServerLevel", "net.minecraft.server.level.ServerLevel",
			"ClientLevel", "net.minecraft.client.multiplayer.ClientLevel", "LevelChunk", "net.minecraft.world.level.chunk.LevelChunk",
			"PhysicsSystem", "com.github.stephengold.joltjni.PhysicsSystem");
		classes.keySet().forEach(k -> live.put(k, 0L));
		Matcher m = Pattern.compile("(?m)^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)").matcher(histogram);
		while (m.find()) {
			for (Map.Entry<String, String> e : classes.entrySet()) {
				if (m.group(3).equals(e.getValue())) {
					live.put(e.getKey(), Long.parseLong(m.group(1)));
				}
			}
		}
		return new Cycle(n, heap, privateMb, threads, live);
	}

	/** Live threads counted by name, with numbers in the names taken out. */
	static Map<String, Integer> threadGroups() {
		Map<String, Integer> threads = new HashMap<>();
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			threads.merge(t.getName().replaceAll("\\d+", "#"), 1, Integer::sum);
		}
		return threads;
	}

	/** Entries in Iris's pipeline override cache (the map Slipway's closed-world cleanup clears). */
	static int irisOverrides() {
		try {
			var field = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("iris$overrides");
			field.setAccessible(true);
			return field.get(null) instanceof Map<?, ?> map ? map.size() : -1;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Iris's pipeline override cache is not where Slipway's cleanup expects it", e);
		}
	}
}
