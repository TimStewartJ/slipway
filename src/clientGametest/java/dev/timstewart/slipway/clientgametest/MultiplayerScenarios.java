package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.ActiveVessel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * multiplayer: a dedicated server with two real clients, each its own game process. This client pilots a vessel; the
 * second client watches it and then stands on its deck. Both clients show the server's pose for the same server tick
 * (compared tick by tick after interpolation), the watcher's rendered motion is smooth, the rider stays on the deck on
 * both its client and the server, and the server corrects or kicks no one.
 */
final class MultiplayerScenarios {
	private MultiplayerScenarios() {
	}

	static final int PORT = 25611;

	record PoseError(int matched, double maxPosition, double maxDegrees) {
	}

	static void multiplayer(ClientGameTestContext ctx, Report.Result r) throws Exception {
		ServerPoses.install();
		ServerWarnings.install();
		ServerWarnings.reset();
		Properties props = new Properties();
		props.setProperty("max-players", "2");
		props.setProperty("server-port", String.valueOf(PORT));
		props.setProperty("gamemode", "creative");
		props.setProperty("difficulty", "peaceful");
		props.setProperty("view-distance", "8");
		props.setProperty("simulation-distance", "8");
		props.setProperty("allow-flight", "true");
		// no watchdog: its thread outlives the server by up to max-tick-time and would keep the stopped server reachable
		props.setProperty("max-tick-time", "-1");
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props); TestDedicatedServerConnection connection = server.connect()) {
			Game.waitChunks(ctx, 1200);
			Game.waitTerrain(ctx, 1200);
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 20, 40);
			Map<BlockPos, BlockState> ship = Ships.smallShip();
			ship.putAll(Ships.deck(4, Blocks.OAK_PLANKS.defaultBlockState()));
			server.runOnServer(s -> Ships.build(s.overworld(), helm, ship));
			long id = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm).id);
			Game.waitClientReady(ctx, id, 200);
			DeckScenarios.placeRider(ctx, server, id, new Vec3(0.5, 0.3, 2.5));
			Flight.takeHelm(ctx, server, id);

			try (WatcherProcess watcher = startWatcher(server)) {
				Properties ready = watcher.awaitReady(ctx, 20 * 240);
				r.note("the watcher joined as %s", ready.getProperty("player"));
				Check.equal("the second client's player", ready.getProperty("player"), WatcherProcess.NAME);
				Check.that(!WatcherProcess.NAME.equals(Game.clientName), "both clients have the same name");
				server.waitFor(s -> s.getPlayerList().getPlayerCount() == 2, 200);
				server.runOnServer(s -> Game.player(s, WatcherProcess.NAME).teleportTo(s.overworld(), 16.5, helm.getY() + 4, 46.5, java.util.Set.of(), 120f, 15f, true));
				watcher.call(ctx, 1200, "wait-vessel", String.valueOf(id));

				// A flies while both clients' views are recorded against the server's pose at the same server tick.
				ServerPoses.record(id);
				watcher.call(ctx, 200, "trace-start", String.valueOf(id));
				List<double[]> pilotView = new ArrayList<>();
				holdAndRecord(ctx, id, 100, pilotView, "key.forward", "key.jump", "key.right");
				recordFor(ctx, id, 40, pilotView);
				Properties trace = watcher.call(ctx, 200, "trace-stop");
				ServerPoses.stop();
				r.metric("server.ticksRecorded", ServerPoses.size());
				PoseError pilot = compare(pilotView);
				PoseError watching = compare(Watcher.readSamples(Path.of(trace.getProperty("poses"))));
				r.metric("pilot.matchedTicks", pilot.matched());
				r.metric("pilot.maxPositionError", pilot.maxPosition());
				r.metric("pilot.maxDegreesError", pilot.maxDegrees());
				r.metric("watcher.matchedTicks", watching.matched());
				r.metric("watcher.maxPositionError", watching.maxPosition());
				r.metric("watcher.maxDegreesError", watching.maxDegrees());
				Check.atLeast("pilot client ticks matched with the server's record", pilot.matched(), 110);
				Check.atLeast("watcher client ticks matched with the server's record", watching.matched(), 110);
				Check.atMost("pilot client's pose vs the server's at the same server tick (blocks)", pilot.maxPosition(), 0.05);
				Check.atMost("pilot client's rotation vs the server's at the same server tick (degrees)", pilot.maxDegrees(), 0.5);
				Check.atMost("watcher client's pose vs the server's at the same server tick (blocks)", watching.maxPosition(), 0.05);
				Check.atMost("watcher client's rotation vs the server's at the same server tick (degrees)", watching.maxDegrees(), 0.5);

				// The watcher's rendered motion, frame by frame.
				Map<String, Double> smooth = parse(trace.getProperty("trace"));
				r.metric("watcher.trace", trace.getProperty("trace"));
				Check.atLeast("frames the watcher rendered while tracing", smooth.get("frames"), 100);
				Check.equal("direction reversals in the watcher's rendered motion", smooth.get("reversals"), 0.0);
				Check.atMost("largest change of velocity between the watcher's frames (blocks/tick)", smooth.get("maxDeltaV"), 0.25);
				Check.atLeast("mean speed seen by the watcher (blocks/tick)", smooth.get("meanSpeed"), 0.1);
				Check.atMost("watcher client stalls (frame gaps over 3 ticks)", smooth.get("stalls"), 2);
				Check.atMost("largest step between the watcher's frames (blocks)", smooth.get("maxStep"), 1.0);
				Check.atMost("largest turn between the watcher's frames (degrees)", smooth.get("maxTurnDeg"), 10.0);
				evidence(r, watcher.call(ctx, 200, "screenshot", "01-watcher-view"));
				Shots.take(ctx, r, "02-pilot-view");

				// The watcher stands on the deck while A keeps flying: carried on its client and on the server.
				server.runOnServer(s -> {
					VesselPose pose = Game.active(s, id).record.pose;
					Vec3 world = pose.localToWorld(new Vec3(2.5, 0.4, 1.5));
					Game.player(s, WatcherProcess.NAME).teleportTo(s.overworld(), world.x, world.y, world.z, java.util.Set.of(), 0f, 10f, true);
				});
				ctx.getInput().holdKey(Game.key(ctx, "key.forward"));
				ctx.getInput().holdKey(Game.key(ctx, "key.right"));
				Properties rider;
				double[] serverLocal;
				try {
					ctx.waitTicks(20);
					rider = watcher.call(ctx, 400, "rider", String.valueOf(id), "60");
					serverLocal = server.computeOnServer(s -> {
						ActiveVessel v = Game.active(s, id);
						Vec3 l = v.record.pose.worldToLocal(Game.player(s, WatcherProcess.NAME).position());
						return new double[] {l.x, l.y, l.z};
					});
					Shots.take(ctx, r, "03-pilot-sees-watcher-on-deck");
					evidence(r, watcher.call(ctx, 200, "screenshot", "04-watcher-riding"));
				} finally {
					ctx.getInput().releaseKey(Game.key(ctx, "key.forward"));
					ctx.getInput().releaseKey(Game.key(ctx, "key.right"));
				}
				r.metric("watcherRider", String.format(Locale.ROOT, "carried %s of %s ticks, local y %s..%s", rider.getProperty("carried"), rider.getProperty("samples"),
					rider.getProperty("minLocalY"), rider.getProperty("maxLocalY")));
				Check.equal("ticks the watcher's client had it carried by the vessel", rider.getProperty("carried"), rider.getProperty("samples"));
				Check.atLeast("watcher's lowest local y on its client (no sinking into the deck)", Double.parseDouble(rider.getProperty("minLocalY")), -0.05);
				Check.atMost("watcher's highest local y on its client (feet on the deck)", Double.parseDouble(rider.getProperty("maxLocalY")), 1.3);
				r.metric("watcherServerLocal", String.format(Locale.ROOT, "%.3f,%.3f,%.3f", serverLocal[0], serverLocal[1], serverLocal[2]));
				Check.that(serverLocal[1] > -0.05 && serverLocal[1] < 1.3 && Math.abs(serverLocal[0]) <= 4.5 && Math.abs(serverLocal[2]) <= 4.5,
					"on the server the watcher is not on the deck: local (%.2f, %.2f, %.2f)", serverLocal[0], serverLocal[1], serverLocal[2]);
			}
			server.waitFor(s -> s.getPlayerList().getPlayerCount() == 1, 400);
			r.metric("server.movementCorrections", ServerWarnings.MOVEMENT_CORRECTIONS.get());
			r.metric("server.kicks", ServerWarnings.KICKS.get());
			Check.equal("movement corrections by the server", ServerWarnings.MOVEMENT_CORRECTIONS.get(), 0);
			Check.equal("players kicked", ServerWarnings.KICKS.get(), 0);
		} finally {
			// Vanilla's dedicated-server Main registers a JVM shutdown hook that references the server (a real server's
			// JVM exits when it stops). Hosted inside this test game, the hook would keep the stopped server and its
			// levels reachable for the rest of the run, so it is removed once the server has stopped.
			r.metric("vanillaServerShutdownHooksRemoved", removeServerShutdownHooks());
		}
	}

	/** Removes vanilla's "Server Shutdown Thread" JVM shutdown hooks (see above); returns how many were removed. */
	static int removeServerShutdownHooks() {
		try {
			Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
			java.lang.reflect.Field field = hooksClass.getDeclaredField("hooks");
			field.setAccessible(true);
			List<Thread> serverHooks = new ArrayList<>();
			synchronized (hooksClass) {
				Map<?, ?> hooks = (Map<?, ?>)field.get(null);
				if (hooks != null) {
					for (Object hook : hooks.keySet()) {
						if (hook instanceof Thread thread && "Server Shutdown Thread".equals(thread.getName())) {
							serverHooks.add(thread);
						}
					}
				}
			}
			serverHooks.forEach(Runtime.getRuntime()::removeShutdownHook);
			return serverHooks.size();
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new AssertionError("could not remove vanilla's dedicated-server shutdown hook (the run needs --add-opens java.base/java.lang)", e);
		}
	}

	/** Fabric's test server only whitelists this client: the watcher's offline profile is added before it starts. */
	private static WatcherProcess startWatcher(TestServerContext server) throws java.io.IOException {
		server.runOnServer(s -> s.getPlayerList().getWhiteList().add(new net.minecraft.server.players.UserWhiteListEntry(
			new net.minecraft.server.players.NameAndId(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(WatcherProcess.NAME), WatcherProcess.NAME))));
		return WatcherProcess.start(PORT);
	}

	private static void evidence(Report.Result r, Properties reply) {
		r.evidence(Path.of(reply.getProperty("path")));
	}

	private static void holdAndRecord(ClientGameTestContext ctx, long id, int ticks, List<double[]> out, String... keys) {
		List<net.minecraft.client.KeyMapping> mappings = new ArrayList<>();
		for (String key : keys) {
			mappings.add(Game.key(ctx, key));
		}
		mappings.forEach(k -> ctx.getInput().holdKey(k));
		try {
			recordFor(ctx, id, ticks, out);
		} finally {
			mappings.forEach(k -> ctx.getInput().releaseKey(k));
		}
	}

	/** This client's pose of a vessel each tick, with the server tick it shows. */
	private static void recordFor(ClientGameTestContext ctx, long id, int ticks, List<double[]> out) {
		for (int i = 0; i < ticks; i++) {
			ctx.waitTick();
			double[] row = ctx.computeOnClient(mc -> {
				ClientVessel v = ClientVessels.get(id);
				if (v == null || !v.ready()) {
					return null;
				}
				VesselPose p = v.tickPose();
				return new double[] {v.playbackTick(), p.x(), p.y(), p.z(), p.qx(), p.qy(), p.qz(), p.qw()};
			});
			if (row != null) {
				out.add(row);
			}
		}
	}

	/** Largest difference between client poses and the server's pose at the same (interpolated) server tick. */
	static PoseError compare(List<double[]> samples) {
		int matched = 0;
		double position = 0.0;
		double degrees = 0.0;
		for (double[] s : samples) {
			VesselPose server = ServerPoses.at(s[0]);
			if (server == null) {
				continue;
			}
			matched++;
			position = Math.max(position, Math.sqrt(sq(s[1] - server.x()) + sq(s[2] - server.y()) + sq(s[3] - server.z())));
			double dot = Math.abs(s[4] * server.qx() + s[5] * server.qy() + s[6] * server.qz() + s[7] * server.qw());
			degrees = Math.max(degrees, Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot))));
		}
		return new PoseError(matched, position, degrees);
	}

	private static double sq(double v) {
		return v * v;
	}

	/** Parses "key=value key=value" as written by SlipwayDebug.traceStop. */
	static Map<String, Double> parse(String text) {
		Map<String, Double> values = new HashMap<>();
		for (String part : text.trim().split("\\s+")) {
			int eq = part.indexOf('=');
			if (eq > 0) {
				try {
					values.put(part.substring(0, eq), Double.parseDouble(part.substring(eq + 1)));
				} catch (NumberFormatException ignored) {
					// not a number
				}
			}
		}
		for (String key : List.of("frames", "reversals", "maxDeltaV", "meanSpeed", "stalls", "maxStep", "maxTurnDeg")) {
			Check.that(values.containsKey(key), "the watcher's trace has no %s: %s", key, text);
		}
		return values;
	}
}
