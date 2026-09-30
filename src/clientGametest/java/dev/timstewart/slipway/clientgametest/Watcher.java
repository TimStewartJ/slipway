package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.SlipwayDebug;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselCollisions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * The second client of the multiplayer test (see {@link WatcherProcess}): joins the test server and carries out the
 * main test's commands, ticking the game between them.
 */
final class Watcher {
	private Watcher() {
	}

	private record Sample(double tick, VesselPose pose) {
	}

	private record RiderSample(long carrier, boolean onGround, Vec3 local) {
	}

	static void run(ClientGameTestContext ctx) {
		Path dir = Path.of(System.getProperty("slipway.clientGametest.watcherDir"));
		String address = System.getProperty("slipway.clientGametest.watcherServer");
		Game.applyTestOptions(ctx);
		Game.hud(ctx, false);
		ctx.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
			new ServerData("Slipway test server", address, ServerData.Type.OTHER), false, null));
		ctx.waitFor(mc -> mc.level != null && mc.player != null && mc.gui.screen() == null, 3600);
		Properties ready = new Properties();
		ready.setProperty("status", "ok");
		ready.setProperty("player", ctx.computeOnClient(mc -> mc.player.getName().getString()));
		WatcherProcess.write(dir.resolve("reply-0.properties"), ready);

		long tracing = -1;
		List<Sample> samples = new ArrayList<>();
		int idle = 0;
		for (int n = 1; ; ) {
			Path command = dir.resolve("cmd-" + n + ".properties");
			if (!Files.isRegularFile(command)) {
				ctx.waitTick();
				if (tracing >= 0) {
					long id = tracing;
					Sample s = ctx.computeOnClient(mc -> {
						ClientVessel v = ClientVessels.get(id);
						if (v == null || !v.ready()) {
							return null;
						}
						// keep watching it: frames are only traced while the vessel is drawn
						mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, v.worldCentre(v.tickPose()));
						return new Sample(v.playbackTick(), v.tickPose());
					});
					if (s != null) {
						samples.add(s);
					}
				}
				Check.that(++idle < 20 * 60 * 10, "the watcher had no command for ten minutes");
				continue;
			}
			idle = 0;
			Properties cmd = WatcherProcess.read(command);
			String verb = cmd.getProperty("verb");
			Properties reply = new Properties();
			reply.setProperty("status", "ok");
			try {
				switch (verb) {
					case "wait-vessel" -> {
						long id = Long.parseLong(cmd.getProperty("arg0"));
						Game.waitClientReady(ctx, id, 1200);
					}
					case "trace-start" -> {
						long id = Long.parseLong(cmd.getProperty("arg0"));
						samples.clear();
						tracing = id;
						ctx.runOnClient(mc -> SlipwayDebug.traceStart(id));
					}
					case "trace-stop" -> {
						tracing = -1;
						reply.setProperty("trace", ctx.computeOnClient(mc -> SlipwayDebug.traceStop()));
						Path file = dir.resolve("poses-" + n + ".csv");
						writeSamples(file, samples);
						reply.setProperty("poses", file.toString());
						reply.setProperty("samples", String.valueOf(samples.size()));
					}
					case "rider" -> {
						long id = Long.parseLong(cmd.getProperty("arg0"));
						int ticks = Integer.parseInt(cmd.getProperty("arg1"));
						List<RiderSample> riders = new ArrayList<>();
						for (int i = 0; i < ticks; i++) {
							ctx.waitTick();
							riders.add(ctx.computeOnClient(mc -> {
								VesselCollisions.Rider r = (VesselCollisions.Rider)mc.player;
								ClientVessel v = ClientVessels.get(id);
								Vec3 local = v != null && v.ready() ? v.tickPose().worldToLocal(mc.player.position()) : null;
								return new RiderSample(r.slipway$carrier(), mc.player.onGround(), local);
							}));
						}
						long carried = riders.stream().filter(s -> s.carrier() == id).count();
						double minY = riders.stream().filter(s -> s.local() != null).mapToDouble(s -> s.local().y).min().orElse(Double.NaN);
						double maxY = riders.stream().filter(s -> s.local() != null).mapToDouble(s -> s.local().y).max().orElse(Double.NaN);
						reply.setProperty("samples", String.valueOf(riders.size()));
						reply.setProperty("carried", String.valueOf(carried));
						reply.setProperty("minLocalY", String.valueOf(minY));
						reply.setProperty("maxLocalY", String.valueOf(maxY));
					}
					case "screenshot" -> {
						Path shot = ctx.takeScreenshot(TestScreenshotOptions.of(cmd.getProperty("arg0")).disableCounterPrefix().withDestinationDir(dir));
						reply.setProperty("path", shot.toString());
					}
					case "quit" -> {
						WatcherProcess.write(dir.resolve("reply-" + n + ".properties"), reply);
						ctx.runOnClient(mc -> mc.disconnectFromWorld(Component.literal("done")));
						ctx.waitFor(mc -> mc.level == null, 1200);
						ctx.setScreen(TitleScreen::new);
						ctx.waitTicks(2);
						return;
					}
					default -> throw new IllegalArgumentException("unknown command " + verb);
				}
			} catch (Throwable t) {
				reply.setProperty("status", "error");
				reply.setProperty("message", t.toString());
			}
			WatcherProcess.write(dir.resolve("reply-" + n + ".properties"), reply);
			n++;
		}
	}

	private static void writeSamples(Path file, List<Sample> samples) {
		StringBuilder out = new StringBuilder("tick,x,y,z,qx,qy,qz,qw\n");
		for (Sample s : samples) {
			VesselPose p = s.pose();
			out.append(String.format(Locale.ROOT, "%.4f,%.6f,%.6f,%.6f,%.8f,%.8f,%.8f,%.8f%n", s.tick(), p.x(), p.y(), p.z(), p.qx(), p.qy(), p.qz(), p.qw()));
		}
		try {
			Files.writeString(file, out, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("cannot write " + file, e);
		}
	}

	/** Reads a pose file written by {@link #writeSamples}: tick followed by the pose. */
	static List<double[]> readSamples(Path file) {
		List<double[]> rows = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank() || line.startsWith("tick")) {
					continue;
				}
				String[] parts = line.split(",");
				double[] row = new double[parts.length];
				for (int i = 0; i < parts.length; i++) {
					row[i] = Double.parseDouble(parts[i]);
				}
				rows.add(row);
			}
		} catch (IOException e) {
			throw new AssertionError("cannot read " + file, e);
		}
		return rows;
	}
}
