package dev.timstewart.slipway.film;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * The Reddit showcase (~30 s at 60 fps, loopable), one continuous simulation filmed in five shots with cuts between
 * them; between shots the vessel keeps flying unfilmed (an autopilot moves it to the next shot's start). All vessel
 * motion is helm input (FilmPilot), never a teleport. Frames and frames.csv go to build/film/out/reddit-&lt;size&gt;/,
 * plus segments.json (shot and event frames, for the captions and the encoder).
 *
 * <ol>
 * <li>hook: at rest over the bay, the galleon lifts off and rolls hard towards the camera;
 * <li>flyby: a pass along the coast in front of the mountains, banking;
 * <li>roll: slow motion with level mode off, a camera fixed to the ship while it rolls through inverted; the
 * door opens and the lever lights the lamp;
 * <li>deck: sheep on the main deck while the ship banks one way and the other;
 * <li>return: a low pass over the water, the autopilot brings it back to where it started, it levels and is
 * disassembled into plain blocks; the camera settles on the opening frame (the loop point).
 * </ol>
 */
final class RedditShot {
	private RedditShot() {
	}

	private record Event(String name, int frame) {
	}

	private static final Vec3 P2 = new Vec3(-4670, 90, 5975);
	private static final Vec3 P3 = new Vec3(-4700, 100, 5790);
	private static final Vec3 P4 = new Vec3(-4690, 92, 5700);
	private static final Vec3 P5 = new Vec3(-4642, 78, 5872);

	static void run(ClientGameTestContext ctx) {
		int[] size = FilmRig.size();
		double k = size[1] > size[0] ? 1.3 : 1.0;
		Path out = FilmRig.outDir("reddit");
		List<Event> events = new ArrayList<>();
		long wallStart = System.nanoTime();
		try (TestSingleplayerContext sp = FilmScene.open(ctx, (int)FilmRig.optDouble("rd", 16))) {
			TestServerContext server = sp.getServer();
			Vec3 helm = Vec3.atLowerCornerOf(FilmScene.HELM);
			Vec3 approx = helm.add(0.5, 4, -18);
			FilmCamera.resetClock();
			FilmCamera.set((t, p) -> FilmCamera.Frame.lookAt(approx.add(-34 * k, -7 * k, -24 * k), approx, 0));
			FilmRig.followCamera(ctx);
			FilmRig.waitWorld(ctx, 6000);
			long id = FilmScene.buildHero(server);
			FilmScene.waitVessel(ctx, id);
			Vec3 rest = FilmPilot.state(server, id).position();
			Vec3 c = ctx.computeOnClient(mc -> FilmScene.centre(id, 1.0f, approx));
			Vec3 c0Target = c.add(0, 3, 0);
			Vec3 c0Pos = c.add(-34 * k, -7 * k, -24 * k);
			FilmCamera.Frame c0 = FilmCamera.Frame.lookAt(c0Pos, c0Target, 0);
			FilmMain.LOG.info("Reddit: vessel {} at rest at {}, centre {}, opening camera {}", id, rest, c, c0);

			// warm up: every shot's surroundings loaded, then time for Distant Horizons at the opening view
			List<Vec3> tour = FilmRig.optDouble("warm", 1) > 0 ? List.of(P2.add(-45 * k, -3, -75), P3, P4, P5, c0Pos) : List.of(c0Pos);
			for (Vec3 p : tour) {
				FilmCamera.set((t, pp) -> FilmCamera.Frame.lookAt(p, c, 0));
				FilmRig.followCamera(ctx);
				FilmRig.waitWorld(ctx, 6000);
			}
			FilmCamera.set((t, p) -> c0);
			FilmRig.followCamera(ctx);
			FilmRig.waitWorld(ctx, 6000);
			FilmRig.waitShaders(ctx);
			ctx.waitTicks((int)(20 * FilmRig.optDouble("dhWait", 60)));
			long recordStart = System.nanoTime();

			Vec3[] lastCentre = {c};
			try (FilmRig.Recorder rec = new FilmRig.Recorder(ctx, out, "vesX,vesY,vesZ,tilt,heading", (mc, partial) -> {
				VesselPose pose = FilmScene.pose(id, partial);
				Vec3 centre = FilmScene.centre(id, partial, lastCentre[0]);
				lastCentre[0] = centre;
				double tilt = pose == null ? 0 : pose.tiltDegrees();
				double heading = pose == null ? 0 : heading(pose);
				return String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.2f,%.2f", centre.x, centre.y, centre.z, tilt, heading);
			})) {
				// ---- 1. hook ----
				rec.cut("hook");
				events.add(new Event("hook", rec.frames()));
				long b1 = FilmCamera.ticks();
				FilmCamera.set((t, p) -> {
					double s = t - b1;
					Vec3 ship = FilmScene.centre(id, p, c);
					double e = FilmCamera.ease(s, 8, 44);
					Vec3 target = FilmCamera.lerp(c0Target, ship.add(0, 3, 0), FilmCamera.ease(s, 8, 26));
					Vec3 pos = c0Pos.add(0, 5 * e, 0).add(c0Pos.subtract(c0Target).normalize().scale(7 * k * e));
					return FilmCamera.Frame.lookAt(pos, target, 0);
				});
				int hookTicks = (int)FilmRig.optDouble("hookTicks", 44);
				for (int i = 0; i < hookTicks; i++) {
					// the helm is pushed over within a few ticks, as a player's analogue input would be
					double ramp = Math.max(0, Math.min(1, (i - 7) / 6.0));
					FilmPilot.Input in = new FilmPilot.Input(0.5 * ramp, 0, 0.9 * ramp, 0, -0.35 * ramp, -1 * ramp);
					server.runOnServer(s -> FilmPilot.apply(s, id, in));
					rec.tick(3);
				}
				logState(server, id, "hook end");
				if (FilmRig.opt("stopAfter", "").equals("hook")) {
					return;
				}

				// ---- 2. flyby ----
				gap(ctx, server, id, rec);
				flyTo(ctx, server, id, P2, 0, 1600, 0.4);
				long b2 = FilmCamera.ticks();
				int flybyTicks = (int)FilmRig.optDouble("flybyTicks", 112);
				FilmCamera.set((t, p) -> {
					double s = Math.max(0, t - b2) / flybyTicks;
					Vec3 ship = FilmScene.centre(id, p, P2);
					Vec3 pos = new Vec3(P2.x - 32 * k, 85, FilmCamera.lerp(5915, 5872, FilmCamera.ease(s)));
					return FilmCamera.Frame.lookAt(pos, ship.add(0, 2, -4), 0);
				});
				FilmRig.followCamera(ctx);
				FilmRig.waitWorld(ctx, 3000);
				settle(ctx);
				for (int i = 0; i < 50; i++) {
					server.runOnServer(s -> FilmPilot.apply(s, id, new FilmPilot.Input(1, 0, 0, 0, 0, 0)));
					ctx.waitTick();
				}
				rec.cut("flyby");
				events.add(new Event("flyby", rec.frames()));
				for (int i = 0; i < flybyTicks; i++) {
					int j = i;
					FilmPilot.Input in = j >= 10 && j < 40 ? new FilmPilot.Input(1, 0, 0, 0, -0.1, -0.35) : new FilmPilot.Input(1, 0, 0, 0, 0, 0);
					server.runOnServer(s -> FilmPilot.apply(s, id, in));
					rec.tick(3);
				}
				logState(server, id, "flyby end");

				// ---- 3. roll (slow motion, level off) ----
				gap(ctx, server, id, rec);
				flyTo(ctx, server, id, P3, 0, 1600, 0.4);
				long b3 = FilmCamera.ticks();
				double rollTicksGuess = FilmRig.optDouble("rollTicks", 64);
				// a camera on the ship, between the main mast and the castle (not scaled for portrait: it would enter the sail)
				Vec3 look = FilmShips.shipPoint(1.2, 1.2, 6);
				Vec3 fromA = FilmShips.shipPoint(-5, 6.5, k > 1 ? -1.2 : -2.5);
				Vec3 fromB = FilmShips.shipPoint(-1.8, 3.6, 1.2);
				FilmCamera.set((t, p) -> {
					double s = Math.max(0, t - b3) / rollTicksGuess;
					VesselPose pose = FilmScene.pose(id, p);
					if (pose == null) {
						return c0;
					}
					Vec3 from = pose.localToWorld(FilmCamera.lerp(fromA, fromB, FilmCamera.ease(s)));
					Vec3 at = pose.localToWorld(look);
					Vector3d up = pose.rotate(0, 1, 0, new Vector3d());
					return FilmCamera.Frame.basis(from, at.subtract(from).normalize(), new Vec3(up.x, up.y, up.z));
				});
				FilmRig.followCamera(ctx);
				FilmRig.waitWorld(ctx, 3000);
				settle(ctx);
				FilmPilot.modes(server, id, true, false);
				int preRoll = (int)FilmRig.optDouble("preRoll", 34);
				for (int i = 0; i < preRoll; i++) {
					server.runOnServer(s -> FilmPilot.apply(s, id, new FilmPilot.Input(0.5, 0, 0, 0, 0, -1)));
					ctx.waitTick();
				}
				rec.cut("roll");
				events.add(new Event("roll", rec.frames()));
				int rollStart = rec.frames();
				boolean lever = false;
				IntFunction<Integer> slow = i -> i < 2 ? 3 : i < 4 ? 4 : i < 6 ? 5 : 6;
				int rollFrames = (int)FilmRig.optDouble("rollFrames", 360);
				for (int i = 0; rec.frames() - rollStart < rollFrames; i++) {
					server.runOnServer(s -> FilmPilot.apply(s, id, new FilmPilot.Input(0.5, 0, 0, 0, 0, -1)));
					if (i == 32) {
						server.runOnServer(s -> door(s, id, true));
						events.add(new Event("door", rec.frames()));
					}
					if (!lever && FilmPilot.state(server, id).tilt() > 160) {
						lever = true;
						server.runOnServer(s -> lever(s, id));
						events.add(new Event("lever", rec.frames()));
						logState(server, id, "lever pulled");
					}
					rec.tick(slow.apply(i));
				}
				logState(server, id, "roll end");

				// ---- 4. deck ----
				gap(ctx, server, id, rec);
				server.runOnServer(s -> FilmPilot.apply(s, id, FilmPilot.Input.NONE));
				FilmPilot.modes(server, id, true, true);
				ctx.waitTicks(80);
				server.runOnServer(s -> {
					lever(s, id);
					door(s, id, false);
				});
				flyTo(ctx, server, id, P4, 0, 1600, 0.4);
				List<Integer> sheep = server.computeOnServer(s -> spawnSheep(s, id));
				ctx.waitTicks(60);
				long b4 = FilmCamera.ticks();
				FilmCamera.set((t, p) -> {
					VesselPose pose = FilmScene.pose(id, p);
					if (pose == null) {
						return c0;
					}
					Vec3 centre = FilmScene.centre(id, p, P4);
					Vec3 deck = pose.localToWorld(FilmShips.shipPoint(0.5, 1.2, -3));
					Vec3 offset = new Vec3(-22 * k, 3, 10 * k).yRot((float)-Math.toRadians(heading(pose)));
					return FilmCamera.Frame.lookAt(centre.add(offset), deck, 0);
				});
				FilmRig.followCamera(ctx);
				FilmRig.waitWorld(ctx, 3000);
				settle(ctx);
				for (int i = 0; i < 20; i++) {
					server.runOnServer(s -> FilmPilot.apply(s, id, new FilmPilot.Input(0.35, 0, 0, 0, 0, 0)));
					ctx.waitTick();
				}
				rec.cut("deck");
				events.add(new Event("deck", rec.frames()));
				int deckTicks = (int)FilmRig.optDouble("deckTicks", 110);
				double bank = FilmRig.optDouble("bank", 0.6);
				for (int i = 0; i < deckTicks; i++) {
					FilmPilot.Input in;
					if (i < 28) {
						in = new FilmPilot.Input(0.35, 0, 0, 0, -0.25, -bank);
					} else if (i < 52) {
						in = new FilmPilot.Input(0.35, 0, 0, 0, -0.25, 0);
					} else if (i < 80) {
						in = new FilmPilot.Input(0.35, 0, 0, 0, 0.25, bank);
					} else {
						in = new FilmPilot.Input(0.35, 0, 0, 0, 0.1, 0);
					}
					server.runOnServer(s -> FilmPilot.apply(s, id, in));
					rec.tick(3);
				}
				logState(server, id, "deck end");
				server.runOnServer(s -> sheepReport(s, id, sheep));

				// ---- 5. return and disassembly ----
				gap(ctx, server, id, rec);
				server.runOnServer(s -> {
					for (int sheepId : sheep) {
						Entity e = s.overworld().getEntity(sheepId);
						if (e != null) {
							e.discard();
						}
					}
				});
				flyTo(ctx, server, id, P5, 0, 1600, 0.4);
				FilmCamera.set((t, p) -> {
					Vec3 ship = FilmScene.centre(id, p, lastCentre[0]);
					double d = ship.distanceTo(c);
					double e = FilmCamera.ease(1 - d / 50.0);
					double e2 = FilmCamera.ease(1 - d / 12.0);
					Vec3 extra = new Vec3(-10, -3, 30).scale(k * (1 - e));
					Vec3 chase = ship.add(c0Pos.subtract(c)).add(extra);
					chase = new Vec3(chase.x, Math.max(65.5, chase.y), chase.z);
					return FilmCamera.blend(FilmCamera.Frame.lookAt(chase, ship.add(0, 3, 0), 0), c0, e2);
				});
				FilmRig.followCamera(ctx);
				FilmRig.waitWorld(ctx, 3000);
				settle(ctx);
				int runUp = (int)FilmRig.optDouble("returnRunUp", 40);
				for (int i = 0; i < runUp; i++) {
					server.runOnServer(s -> FilmPilot.apply(s, id, new FilmPilot.Input(1, 0, 0, 0, 0, 0)));
					ctx.waitTick();
				}
				rec.cut("return");
				events.add(new Event("return", rec.frames()));
				double returnGain = FilmRig.optDouble("returnGain", 1.0);
				double returnSpeed = FilmRig.optDouble("returnSpeed", 17);
				int after = -1;
				for (int i = 0; i < 600; i++) {
					if (after < 0) {
						FilmPilot.State st = FilmPilot.state(server, id);
						if (i > 20 && FilmPilot.settled(st, rest, 0, 0.06)) {
							logState(server, id, "settled, disassembling");
							VesselAssembly.Outcome outcome = server.computeOnServer(s -> VesselManager.get(s.overworld()).disassemble(id, null));
							if (!outcome.success()) {
								throw new AssertionError("disassembly failed: " + outcome.message().getString());
							}
							events.add(new Event("disassemble", rec.frames()));
							after = 0;
							// the client drops the vessel a tick or two before the placed blocks arrive and are meshed
							// (reported, not fixed here): hold film time until the blocks are drawn
							BlockPos helmPos = FilmScene.HELM;
							int held = rec.hold(mc -> mc.level.getBlockState(helmPos).is(dev.timstewart.slipway.registry.SlipwayRegistry.HELM), 100);
							FilmMain.LOG.info("Reddit: disassembled; held film time {} ticks until the placed blocks were drawn", held);
							continue;
						} else {
							server.runOnServer(s -> FilmPilot.apply(s, id, FilmPilot.autopilot(st, rest, 0, returnGain, returnSpeed)));
						}
					}
					rec.tick(3);
					if (after >= 0 && ++after >= (int)FilmRig.optDouble("holdTicks", 76)) {
						break;
					}
				}
				if (after < 0) {
					throw new AssertionError("the vessel did not settle at its starting point");
				}
				events.add(new Event("end", rec.frames()));
				FilmMain.LOG.info("Reddit: {} frames of {}x{} in {} s recording ({} s total), {} ms per frame, {} settle renders; frames in {}", rec.frames(),
					size[0], size[1], String.format(Locale.ROOT, "%.1f", (System.nanoTime() - recordStart) / 1e9),
					String.format(Locale.ROOT, "%.1f", (System.nanoTime() - wallStart) / 1e9), String.format(Locale.ROOT, "%.0f", rec.meanFrameMillis()),
					rec.settleRenders(), out);
			}
			FilmCamera.set(null);
			writeSegments(out, events, size);
		}
	}

	/** Between shots: the game renders normally again and the camera rides along so the terrain around the ship loads. */
	private static void gap(ClientGameTestContext ctx, TestServerContext server, long id, FilmRig.Recorder rec) {
		FilmClock.holdLoop = false;
		FilmCamera.set((t, p) -> {
			Vec3 ship = FilmScene.centre(id, p, Vec3.ZERO);
			return FilmCamera.Frame.lookAt(ship.add(-40, 14, 12), ship, 0);
		});
		FilmRig.followCamera(ctx);
	}

	/**
	 * Unfilmed ticks at a shot's first camera position (film time does not advance): Distant Horizons updates its far
	 * terrain around the new view, which otherwise shows as pale patches on the water for a second after a cut.
	 */
	private static void settle(ClientGameTestContext ctx) {
		ctx.waitTicks((int)FilmRig.optDouble("shotSettle", 100));
	}

	/** Autopilot (unfilmed) to a point and heading, until settled there. */
	private static void flyTo(ClientGameTestContext ctx, TestServerContext server, long id, Vec3 target, double heading, int maxTicks, double distance) {
		for (int i = 0; i < maxTicks; i++) {
			boolean done = server.computeOnServer(s -> {
				FilmPilot.State st = FilmPilot.state(s, id);
				if (FilmPilot.settled(st, target, heading, distance)) {
					return true;
				}
				FilmPilot.apply(s, id, FilmPilot.autopilot(st, target, heading, 0.8, 22));
				return false;
			});
			if (done) {
				FilmMain.LOG.info("Reddit: at {} after {} ticks", target, i);
				return;
			}
			ctx.waitTick();
			FilmRig.followCamera(ctx);
		}
		logState(server, id, "flyTo timeout");
		throw new AssertionError("autopilot did not reach " + target);
	}

	private static void logState(TestServerContext server, long id, String what) {
		FilmPilot.State st = FilmPilot.state(server, id);
		double[] att = st.pose().attitudeDegrees();
		FilmMain.LOG.info("Reddit: {}: pos {} heading {} pitch {} roll {} tilt {} speed {}", what, st.position(), fmt(st.heading()), fmt(att[0]), fmt(att[2]),
			fmt(st.tilt()), fmt(st.velocity().length()));
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static Vec3 scaleFrom(Vec3 look, Vec3 from, double k) {
		return look.add(from.subtract(look).scale(k));
	}

	static double heading(VesselPose pose) {
		Vector3d f = pose.rotate(0, 0, -1, new Vector3d());
		return Math.toDegrees(Math.atan2(f.x, -f.z));
	}

	private static VesselRecord record(MinecraftServer s, long id) {
		return FilmPilot.active(s, id).record;
	}

	/** Pulls the vessel's lever as a player's click does (it powers the lamp it is on). */
	private static void lever(MinecraftServer s, long id) {
		ServerLevel level = s.overworld();
		BlockPos pos = record(s, id).toPlot(FilmShips.HERO_LEVER);
		BlockState state = level.getBlockState(pos);
		((LeverBlock)state.getBlock()).pull(state, level, pos, null);
	}

	private static void door(MinecraftServer s, long id, boolean open) {
		ServerLevel level = s.overworld();
		BlockPos pos = record(s, id).toPlot(FilmShips.HERO_DOOR);
		BlockState state = level.getBlockState(pos);
		((DoorBlock)state.getBlock()).setOpen(null, level, state, pos, open);
	}

	// No chest-lid shot: the lid animation is a block event, which the game sends only to players near the block's
	// position, i.e. the vessel's plot far away; the mod does not forward it, so a player would not see it either.

	private static List<Integer> spawnSheep(MinecraftServer s, long id) {
		ServerLevel level = s.overworld();
		VesselPose pose = record(s, id).pose;
		DyeColor[] colours = {DyeColor.WHITE, DyeColor.PINK, DyeColor.LIGHT_BLUE, DyeColor.YELLOW};
		List<Integer> ids = new ArrayList<>();
		for (int i = 0; i < FilmShips.HERO_DECK_SPOTS.length; i++) {
			BlockPos spot = FilmShips.HERO_DECK_SPOTS[i];
			Vec3 at = pose.localToWorld(new Vec3(spot.getX() + 0.5, spot.getY() + 0.05, spot.getZ() + 0.5));
			Sheep sheep = EntityTypes.SHEEP.create(level, EntitySpawnReason.COMMAND);
			sheep.setColor(colours[i % colours.length]);
			sheep.snapTo(at.x, at.y, at.z, 180f + i * 70f, 0f);
			sheep.setPersistenceRequired();
			level.addFreshEntity(sheep);
			ids.add(sheep.getId());
		}
		return ids;
	}

	private static void sheepReport(MinecraftServer s, long id, List<Integer> sheep) {
		VesselPose pose = record(s, id).pose;
		StringBuilder b = new StringBuilder();
		for (int sheepId : sheep) {
			Entity e = s.overworld().getEntity(sheepId);
			b.append(e == null ? "gone " : String.format(Locale.ROOT, "local %s ", pose.worldToLocal(e.position())));
		}
		FilmMain.LOG.info("Reddit: sheep after the deck shot: {}", b);
	}

	private static void writeSegments(Path out, List<Event> events, int[] size) {
		StringBuilder json = new StringBuilder("{\n  \"size\": [" + size[0] + ", " + size[1] + "],\n  \"fps\": 60,\n  \"events\": [\n");
		for (int i = 0; i < events.size(); i++) {
			Event e = events.get(i);
			json.append(String.format(Locale.ROOT, "    {\"name\": \"%s\", \"frame\": %d}%s\n", e.name(), e.frame(), i + 1 < events.size() ? "," : ""));
		}
		json.append("  ]\n}\n");
		try {
			Files.writeString(out.resolve("segments.json"), json.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
