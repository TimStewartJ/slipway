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
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * The Reddit showcase, version 2 (about 27 s at 60 fps, 4:5, loopable): one continuous simulation filmed in five shots
 * with cuts between them. Between shots the vessel keeps flying unfilmed (an autopilot moves it to the next shot's
 * start). All motion of the ship is helm input (FilmPilot), never a teleport; the cargo moves by gravity and
 * collisions only. Frames and frames.csv go to build/film/out/reddit-&lt;size&gt;/, plus segments.json (shot and event
 * frames, for the captions and the encoder).
 *
 * <ol>
 * <li>hook: at rest over the bay, the galleon lifts off and rolls hard towards the camera;
 * <li>cargo: eight loose pieces (vessels of their own) drop onto the deck and pile up; the ship rolls and they slide
 * off through the open rail onto the shore;
 * <li>roll: slow motion with level mode off, a camera fixed to the ship while it rolls through inverted; on the
 * castle wall the lever starts a clock, lamps light in sequence, pistons pump, the door and the chest open;
 * <li>farm: wheat on deck grows as dispensers feed it bone meal on a redstone clock, sheep standing by;
 * <li>return: the autopilot brings the ship back to where it started, it levels and is disassembled into plain
 * blocks; the camera settles on the opening frame (the loop point).
 * </ol>
 * {@code only=cargo+roll} films just those shots (rehearsals; the ship is not brought home unless {@code return} is
 * among them).
 */
final class RedditShot {
	private record Event(String name, int frame) {
	}

	// Where each shot happens: the vessel's origin (the helm block's corner) and heading (degrees, 0 = north, 90 = east).
	/** Cargo: over the grassy headland east of the bay, bow north-east, port side towards the camera in the north-west. */
	private static final Vec3 CARGO_AT = new Vec3(-4537.7, 87, 5813.1);
	private static final double CARGO_HEADING = 45;
	private static final Vec3 ROLL_AT = new Vec3(-4650, 100, 5770);
	private static final Vec3 FARM_AT = new Vec3(-4665, 88, 5735);
	private static final Vec3 RETURN_FROM = new Vec3(-4632, 79, 5840);

	private final ClientGameTestContext ctx;
	private final int[] size = FilmRig.size();
	private final double k = this.size[1] > this.size[0] ? 1.3 : 1.0;
	private final List<Event> events = new ArrayList<>();
	private final List<String> only = FilmRig.opt("only", "").isEmpty() ? List.of() : List.of(FilmRig.opt("only", "").split("\\+"));
	private final Vec3[] lastCentre = new Vec3[1];
	private TestServerContext server;
	private FilmRig.Recorder rec;
	private long id;
	/** The vessel's origin and centre at rest, and the opening camera. */
	private Vec3 rest;
	private Vec3 c;
	private Vec3 c0Pos;
	private Vec3 c0Target;
	private FilmCamera.Frame c0;

	private RedditShot(ClientGameTestContext ctx) {
		this.ctx = ctx;
	}

	static void run(ClientGameTestContext ctx) {
		new RedditShot(ctx).film();
	}

	private boolean films(String shot) {
		return this.only.isEmpty() || this.only.contains(shot);
	}

	private void film() {
		Path out = FilmRig.outDir("reddit");
		long wallStart = System.nanoTime();
		try (TestSingleplayerContext sp = FilmScene.open(this.ctx, (int)FilmRig.optDouble("rd", 16))) {
			this.server = sp.getServer();
			double k = this.k;
			Vec3 helm = Vec3.atLowerCornerOf(FilmScene.HELM);
			Vec3 approx = helm.add(0.5, 4, -18);
			FilmCamera.resetClock();
			FilmCamera.set((t, p) -> FilmCamera.Frame.lookAt(approx.add(-34 * k, -7 * k, -24 * k), approx, 0));
			FilmRig.followCamera(this.ctx);
			FilmRig.waitWorld(this.ctx, 6000);
			this.id = FilmScene.buildHero(this.server);
			long id = this.id;
			FilmScene.waitVessel(this.ctx, id);
			this.rest = FilmPilot.state(this.server, id).position();
			this.c = this.ctx.computeOnClient(mc -> FilmScene.centre(id, 1.0f, approx));
			Vec3 c = this.c;
			this.c0Target = c.add(0, 3, 0);
			this.c0Pos = c.add(-34 * k, -7 * k, -24 * k);
			this.c0 = FilmCamera.Frame.lookAt(this.c0Pos, this.c0Target, 0);
			this.lastCentre[0] = c;
			FilmMain.LOG.info("Reddit: vessel {} at rest at {}, centre {}, opening camera {}", id, this.rest, c, this.c0);

			// warm up: every shot's surroundings loaded, then time for Distant Horizons at the opening view
			List<Vec3> tour = new ArrayList<>();
			if (FilmRig.optDouble("warm", 1) > 0) {
				if (this.films("cargo")) {
					tour.add(CARGO_AT.add(-20, -8, -30));
				}
				if (this.films("roll")) {
					tour.add(ROLL_AT);
				}
				if (this.films("farm")) {
					tour.add(FARM_AT);
				}
				if (this.films("return")) {
					tour.add(RETURN_FROM);
				}
			}
			tour.add(this.c0Pos);
			for (Vec3 p : tour) {
				FilmCamera.set((t, pp) -> FilmCamera.Frame.lookAt(p, c, 0));
				FilmRig.followCamera(this.ctx);
				FilmRig.waitWorld(this.ctx, 6000);
			}
			FilmCamera.set((t, p) -> this.c0);
			FilmRig.followCamera(this.ctx);
			FilmRig.waitWorld(this.ctx, 6000);
			FilmRig.waitShaders(this.ctx);
			this.ctx.waitTicks((int)(20 * FilmRig.optDouble("dhWait", 60)));
			long recordStart = System.nanoTime();

			try (FilmRig.Recorder rec = new FilmRig.Recorder(this.ctx, out, "vesX,vesY,vesZ,tilt,heading", (mc, partial) -> {
				VesselPose pose = FilmScene.pose(id, partial);
				Vec3 centre = FilmScene.centre(id, partial, this.lastCentre[0]);
				this.lastCentre[0] = centre;
				double tilt = pose == null ? 0 : pose.tiltDegrees();
				double heading = pose == null ? 0 : heading(pose);
				return String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.2f,%.2f", centre.x, centre.y, centre.z, tilt, heading);
			})) {
				this.rec = rec;
				if (this.films("hook")) {
					this.hook();
				}
				if (this.films("cargo")) {
					this.cargo();
				}
				if (this.films("roll")) {
					this.roll();
				}
				if (this.films("farm")) {
					this.farm();
				}
				if (this.films("return")) {
					this.homecoming();
				}
				this.events.add(new Event("end", rec.frames()));
				FilmMain.LOG.info("Reddit: {} frames of {}x{} in {} s recording ({} s total), {} ms per frame, {} settle renders; frames in {}", rec.frames(),
					this.size[0], this.size[1], String.format(Locale.ROOT, "%.1f", (System.nanoTime() - recordStart) / 1e9),
					String.format(Locale.ROOT, "%.1f", (System.nanoTime() - wallStart) / 1e9), String.format(Locale.ROOT, "%.0f", rec.meanFrameMillis()),
					rec.settleRenders(), out);
			}
			FilmCamera.set(null);
			writeSegments(out, this.events, this.size);
		}
	}

	private void event(String name) {
		this.events.add(new Event(name, this.rec.frames()));
	}

	// ---- 1. hook: lifts off and rolls hard ----
	private void hook() {
		long id = this.id;
		double k = this.k;
		Vec3 c = this.c;
		this.rec.cut("hook");
		this.event("hook");
		long b1 = FilmCamera.ticks();
		FilmCamera.set((t, p) -> {
			double s = t - b1;
			Vec3 ship = FilmScene.centre(id, p, c);
			double e = FilmCamera.ease(s, 8, 44);
			Vec3 target = FilmCamera.lerp(this.c0Target, ship.add(0, 3, 0), FilmCamera.ease(s, 8, 26));
			Vec3 pos = this.c0Pos.add(0, 5 * e, 0).add(this.c0Pos.subtract(this.c0Target).normalize().scale(7 * k * e));
			return FilmCamera.Frame.lookAt(pos, target, 0);
		});
		int hookTicks = (int)FilmRig.optDouble("hookTicks", 42);
		for (int i = 0; i < hookTicks; i++) {
			// the helm is pushed over within half a second, as a player's analogue input would be
			double ramp = Math.max(0, Math.min(1, (i - 5) / 9.0));
			FilmPilot.Input in = new FilmPilot.Input(0.5 * ramp, 0, 0.9 * ramp, 0, -0.35 * ramp, -1 * ramp);
			this.server.runOnServer(s -> FilmPilot.apply(s, id, in));
			this.rec.tick(3);
		}
		this.logState("hook end");
	}

	// ---- 2. cargo: loose pieces drop onto the deck, the ship rolls, they slide off onto the shore ----
	private void cargo() {
		long id = this.id;
		this.gap();
		FilmPilot.modes(this.server, id, true, true);
		this.flyTo(CARGO_AT, CARGO_HEADING, 2400, 0.2);
		this.ctx.waitTicks(20);
		// the cargo: blocks placed over the deck and assembled through their own helms (unfilmed); they hover until let go
		List<FilmScene.Piece> pieces = FilmScene.buildCargo(this.server, id);
		for (FilmScene.Piece piece : pieces) {
			FilmScene.waitVessel(this.ctx, piece.id());
		}
		VesselPose pose = FilmPilot.state(this.server, id).pose();
		Vec3 bay = pose.localToWorld(FilmShips.shipPoint(-0.5, 0, 1.5));
		Vec3 port = dir(pose, -1, 0, 0);
		Vec3 fore = dir(pose, 0, 0, -1);
		double dist = FilmRig.optDouble("cargoCamDist", 30);
		double height = FilmRig.optDouble("cargoCamY", -4);
		double along = FilmRig.optDouble("cargoCamAlong", 3);
		Vec3 camA = bay.add(port.scale(dist)).add(fore.scale(along)).add(0, height, 0);
		Vec3 camB = camA.add(port.scale(-FilmRig.optDouble("cargoPush", 3))).add(0, FilmRig.optDouble("cargoRise", 1.5), 0);
		Vec3 aimA = bay.add(port.scale(FilmRig.optDouble("cargoAimOut", 3))).add(0, FilmRig.optDouble("cargoAimY", 0.5), 0);
		Vec3 aimB = aimA.add(port.scale(FilmRig.optDouble("cargoAimOutB", 3))).add(fore.scale(FilmRig.optDouble("cargoAimFore", 1.5))).add(0, FilmRig.optDouble("cargoAimDrop", -5.0), 0);
		int cargoTicks = (int)FilmRig.optDouble("cargoTicks", 112);
		long[] b2 = {Long.MAX_VALUE};
		FilmCamera.set((t, p) -> {
			double s = b2[0] == Long.MAX_VALUE ? 0 : Math.max(0, t - b2[0]) / cargoTicks;
			double e = FilmCamera.ease(s, 0.25, 0.9);
			return FilmCamera.Frame.lookAt(FilmCamera.lerp(camA, camB, e), FilmCamera.lerp(aimA, aimB, e), 0);
		});
		FilmRig.followCamera(this.ctx);
		FilmRig.waitWorld(this.ctx, 3000);
		this.settle();
		// let go: from here the pieces are loose bodies; a few ticks before the cut, so the shot opens on them falling
		boolean[] real = {true};
		this.server.runOnServer(s -> {
			for (FilmScene.Piece piece : pieces) {
				real[0] &= FilmPilot.loose(s, piece.id(), true);
			}
		});
		if (!real[0]) {
			FilmMain.LOG.warn("Reddit: this Slipway build has no loose mode; the cargo falls with hover and level off (controller drag and spin braking remain)");
		}
		// the helm is let go: the ship hovers where it is and holds that point under the load (hover mode)
		this.server.runOnServer(s -> FilmPilot.apply(s, id, FilmPilot.Input.NONE));
		this.ctx.waitTicks((int)FilmRig.optDouble("cargoLead", 12));
		this.rec.cut("cargo");
		this.event("cargo");
		b2[0] = FilmCamera.ticks();
		int rollAt = (int)FilmRig.optDouble("cargoRollAt", 22);
		int rollTicks = (int)FilmRig.optDouble("cargoRollTicks", 33);
		double rollInput = FilmRig.optDouble("cargoRoll", 1.0);
		int slowFrom = (int)FilmRig.optDouble("cargoSlowFrom", 1000);
		int slowTo = (int)FilmRig.optDouble("cargoSlowTo", 1000);
		for (int i = 0; i < cargoTicks; i++) {
			int j = i;
			if (j == rollAt) {
				FilmPilot.modes(this.server, id, true, false);
				this.event("spill");
			}
			// the only input of the shot: from rollAt the pilot rolls the ship to port; with level mode off it then stays rolled
			double ramp = Math.max(0, Math.min(1, Math.min((j - rollAt + 1) / 10.0, (rollAt + rollTicks - j) / 5.0)));
			this.server.runOnServer(s -> FilmPilot.apply(s, id, new FilmPilot.Input(0, 0, 0, 0, 0, -rollInput * ramp)));
			this.rec.tick(j >= slowFrom && j < slowTo ? 4 : 3);
			if (j % 10 == 9 || j == cargoTicks - 1) {
				this.cargoReport(pieces, j);
			}
		}
		this.logState("cargo end");
		// afterwards, unfilmed: the pieces are removed (/slipway remove), the ship levels again
		this.gap();
		this.server.runOnServer(s -> {
			for (FilmScene.Piece piece : pieces) {
				if (FilmPilot.command(s, "slipway remove " + piece.id()) <= 0) {
					throw new AssertionError("could not remove cargo piece " + piece);
				}
			}
			FilmPilot.apply(s, id, FilmPilot.Input.NONE);
		});
		FilmPilot.modes(this.server, id, true, true);
		this.ctx.waitTicks(80);
	}

	private void cargoReport(List<FilmScene.Piece> pieces, int tick) {
		long id = this.id;
		this.server.runOnServer(s -> {
			VesselPose ship = record(s, id).pose;
			StringBuilder b = new StringBuilder();
			for (FilmScene.Piece piece : pieces) {
				VesselRecord r = record(s, piece.id());
				Vec3 centre = VesselManager.worldCentre(r);
				Vec3 local = ship.worldToLocal(centre);
				b.append(String.format(Locale.ROOT, "%s y %.1f deck(%.1f,%.1f,%.1f) v %.1f; ", piece.name(), centre.y, local.x, local.y + 5, local.z + 12, r.linearVelocity.length()));
			}
			FilmMain.LOG.info("Reddit: cargo at tick {} (ship tilt {}): {}", tick, fmt(ship.tiltDegrees()), b);
		});
	}

	// ---- 3. roll (slow motion, level off): the machine on the castle wall ----
	private void roll() {
		long id = this.id;
		this.gap();
		FilmPilot.modes(this.server, id, true, true);
		this.flyTo(ROLL_AT, 0, 2400, 0.4);
		long b3 = FilmCamera.ticks();
		double rollTicksGuess = FilmRig.optDouble("rollTicks", 64);
		// a camera on the ship, just aft of the main sail and beside the main mast, looking at the castle wall
		Vec3 look = FilmShips.shipPoint(FilmRig.optDouble("rollLookX", 0.3), FilmRig.optDouble("rollLookY", 2.0), 6);
		Vec3 fromA = FilmShips.shipPoint(FilmRig.optDouble("rollAX", -2.4), FilmRig.optDouble("rollAY", 3.6), FilmRig.optDouble("rollAZ", -1.9));
		Vec3 fromB = FilmShips.shipPoint(FilmRig.optDouble("rollBX", -2.0), FilmRig.optDouble("rollBY", 3.3), FilmRig.optDouble("rollBZ", -0.6));
		FilmCamera.set((t, p) -> {
			double s = Math.max(0, t - b3) / rollTicksGuess;
			VesselPose pose = FilmScene.pose(id, p);
			if (pose == null) {
				return this.c0;
			}
			Vec3 from = pose.localToWorld(FilmCamera.lerp(fromA, fromB, FilmCamera.ease(s)));
			Vec3 at = pose.localToWorld(look);
			Vector3d up = pose.rotate(0, 1, 0, new Vector3d());
			return FilmCamera.Frame.basis(from, at.subtract(from).normalize(), new Vec3(up.x, up.y, up.z));
		});
		FilmRig.followCamera(this.ctx);
		FilmRig.waitWorld(this.ctx, 3000);
		this.settle();
		FilmPilot.modes(this.server, id, true, false);
		int preRoll = (int)FilmRig.optDouble("preRoll", 34);
		FilmPilot.Input rolling = new FilmPilot.Input(0.5, 0, 0, 0, 0, -1);
		for (int i = 0; i < preRoll; i++) {
			this.server.runOnServer(s -> FilmPilot.apply(s, id, rolling));
			this.ctx.waitTick();
		}
		this.rec.cut("roll");
		this.event("roll");
		int rollStart = this.rec.frames();
		int rollFrames = (int)FilmRig.optDouble("rollFrames", 366);
		int leverAt = (int)FilmRig.optDouble("leverAt", 6);
		int doorAt = (int)FilmRig.optDouble("doorAt", 32);
		int chestAt = (int)FilmRig.optDouble("chestAt", 40);
		boolean pistons = false;
		for (int i = 0; this.rec.frames() - rollStart < rollFrames; i++) {
			int j = i;
			this.server.runOnServer(s -> {
				FilmPilot.apply(s, id, rolling);
				if (j == leverAt) {
					lever(s, id, FilmShips.HERO_LEVER);
				}
				if (j == doorAt) {
					door(s, id, true);
				}
				if (j == chestAt) {
					chest(s, id, true);
				}
			});
			if (j == leverAt) {
				this.event("lever");
				this.logState("lever pulled");
			}
			if (j == doorAt) {
				this.event("door");
			}
			if (j == chestAt) {
				this.event("chest");
			}
			this.rec.tick(i < 2 ? 3 : i < 4 ? 4 : i < 6 ? 5 : 6);
			this.machineReport(j);
			if (!pistons && this.server.computeOnServer(s -> pistonExtended(s, id))) {
				pistons = true;
				this.event("pistons");
			}
		}
		this.logState("roll end");
		// afterwards, unfilmed: everything on the wall back as it was, the ship level again
		this.gap();
		this.server.runOnServer(s -> {
			FilmPilot.apply(s, id, FilmPilot.Input.NONE);
			lever(s, id, FilmShips.HERO_LEVER);
			door(s, id, false);
			chest(s, id, false);
		});
		FilmPilot.modes(this.server, id, true, true);
		this.ctx.waitTicks(100);
		this.machineReport(-1);
	}

	/** Whether any of the machine's pistons is extended (or extending). */
	private static boolean pistonExtended(MinecraftServer s, long id) {
		ServerLevel level = s.overworld();
		VesselRecord r = record(s, id);
		for (BlockPos pos : FilmShips.HERO_PISTONS) {
			BlockState state = level.getBlockState(r.toPlot(pos));
			if (state.hasProperty(PistonBaseBlock.EXTENDED) && state.getValue(PistonBaseBlock.EXTENDED)) {
				return true;
			}
		}
		return false;
	}

	/** Logs the machine's state (lamps lit, pistons extended), to check it against the frames. */
	private void machineReport(int tick) {
		long id = this.id;
		this.server.runOnServer(s -> {
			ServerLevel level = s.overworld();
			VesselRecord r = record(s, id);
			StringBuilder b = new StringBuilder("lamps ");
			for (BlockPos pos : FilmShips.HERO_LAMPS) {
				BlockState state = level.getBlockState(r.toPlot(pos));
				b.append(state.hasProperty(RedstoneLampBlock.LIT) ? state.getValue(RedstoneLampBlock.LIT) ? '#' : '.' : '?');
			}
			b.append(" pistons ");
			for (BlockPos pos : FilmShips.HERO_PISTONS) {
				BlockState state = level.getBlockState(r.toPlot(pos));
				b.append(state.hasProperty(PistonBaseBlock.EXTENDED) ? state.getValue(PistonBaseBlock.EXTENDED) ? '#' : '.' : '?');
			}
			BlockState leverState = level.getBlockState(r.toPlot(FilmShips.HERO_LEVER));
			b.append(" lever ").append(leverState.hasProperty(LeverBlock.POWERED) ? leverState.getValue(LeverBlock.POWERED) : "?");
			FilmMain.LOG.info("Reddit: machine at tick {}: {}", tick, b);
		});
	}

	// ---- 4. farm: wheat grows on deck in flight ----
	private void farm() {
		long id = this.id;
		this.gap();
		FilmPilot.modes(this.server, id, true, true);
		this.flyTo(FARM_AT, 0, 2400, 0.4);
		List<Integer> sheep = this.server.computeOnServer(s -> spawnSheep(s, id));
		this.ctx.waitTicks(60);
		Vec3 from = FilmShips.shipPoint(FilmRig.optDouble("farmX", -4.0), FilmRig.optDouble("farmY", 3.7), FilmRig.optDouble("farmZ", -7.0));
		Vec3 fromB = from.add(FilmRig.optDouble("farmDX", 0.4), FilmRig.optDouble("farmDY", -0.3), FilmRig.optDouble("farmDZ", -0.2));
		Vec3 look = FilmShips.shipPoint(FilmRig.optDouble("farmLookX", 1.0), FilmRig.optDouble("farmLookY", 1.7), FilmRig.optDouble("farmLookZ", -7.0));
		int farmTicks = (int)FilmRig.optDouble("farmTicks", 102);
		long[] b4 = {Long.MAX_VALUE};
		FilmCamera.set((t, p) -> {
			VesselPose pose = FilmScene.pose(id, p);
			if (pose == null) {
				return this.c0;
			}
			double s = b4[0] == Long.MAX_VALUE ? 0 : Math.max(0, t - b4[0]) / farmTicks;
			return FilmCamera.Frame.lookAt(pose.localToWorld(FilmCamera.lerp(from, fromB, FilmCamera.ease(s))), pose.localToWorld(look), 0);
		});
		FilmRig.followCamera(this.ctx);
		FilmRig.waitWorld(this.ctx, 3000);
		this.settle();
		double speed = FilmRig.optDouble("farmSpeed", 0.14);
		double turn = FilmRig.optDouble("farmTurn", 0.06);
		for (int i = 0; i < 40; i++) {
			this.server.runOnServer(s -> FilmPilot.apply(s, id, new FilmPilot.Input(speed, 0, 0, 0, turn, 0)));
			this.ctx.waitTick();
		}
		this.rec.cut("farm");
		this.event("farm");
		b4[0] = FilmCamera.ticks();
		int clockAt = (int)FilmRig.optDouble("farmClockAt", 10);
		for (int i = 0; i < farmTicks; i++) {
			int j = i;
			this.server.runOnServer(s -> {
				FilmPilot.apply(s, id, new FilmPilot.Input(speed, 0, 0, 0, turn, 0));
				if (j == clockAt) {
					lever(s, id, FilmShips.HERO_FARM_LEVER);
				}
			});
			if (j == clockAt) {
				this.event("clock");
			}
			this.rec.tick(3);
			this.farmReport(j);
		}
		this.logState("farm end");
		// afterwards, unfilmed: the clock is switched off, the sheep leave
		this.gap();
		this.server.runOnServer(s -> {
			lever(s, id, FilmShips.HERO_FARM_LEVER);
			sheepReport(s, id, sheep);
			for (int sheepId : sheep) {
				Entity e = s.overworld().getEntity(sheepId);
				if (e != null) {
					e.discard();
				}
			}
		});
		this.ctx.waitTicks(40);
	}

	/** Logs the wheat's growth stages when they change. */
	private String lastWheat = "";

	private void farmReport(int tick) {
		long id = this.id;
		String wheat = this.server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			VesselRecord r = record(s, id);
			StringBuilder b = new StringBuilder();
			for (BlockPos pos : FilmShips.HERO_WHEAT) {
				BlockState state = level.getBlockState(r.toPlot(pos));
				b.append(state.hasProperty(CropBlock.AGE) ? Integer.toString(state.getValue(CropBlock.AGE)) : "?");
			}
			return b.toString();
		});
		if (!wheat.equals(this.lastWheat)) {
			FilmMain.LOG.info("Reddit: wheat stages at tick {}: {}", tick, wheat);
			this.lastWheat = wheat;
		}
	}

	// ---- 5. return and disassembly ----
	private void homecoming() {
		long id = this.id;
		double k = this.k;
		Vec3 c = this.c;
		Vec3 rest = this.rest;
		this.gap();
		FilmPilot.modes(this.server, id, true, true);
		this.flyTo(RETURN_FROM, 0, 2400, 0.4);
		// the machines must be at rest before the ship turns back into blocks
		this.machineReport(-2);
		FilmCamera.set((t, p) -> {
			Vec3 ship = FilmScene.centre(id, p, this.lastCentre[0]);
			double d = ship.distanceTo(c);
			double e = FilmCamera.ease(1 - d / 40.0);
			double e2 = FilmCamera.ease(1 - d / 12.0);
			Vec3 extra = new Vec3(-10, -3, 30).scale(k * (1 - e));
			Vec3 chase = ship.add(this.c0Pos.subtract(c)).add(extra);
			chase = new Vec3(chase.x, Math.max(65.5, chase.y), chase.z);
			return FilmCamera.blend(FilmCamera.Frame.lookAt(chase, ship.add(0, 3, 0), 0), this.c0, e2);
		});
		FilmRig.followCamera(this.ctx);
		FilmRig.waitWorld(this.ctx, 3000);
		this.settle();
		// the approach starts unfilmed (the same autopilot as the filmed part); the shot cuts in for the last stretch
		double returnGain = FilmRig.optDouble("returnGain", 1.8);
		double returnSpeed = FilmRig.optDouble("returnSpeed", 17);
		double returnBrake = FilmRig.optDouble("returnBrake", 6.5);
		double cutAt = FilmRig.optDouble("returnCut", 15);
		for (int i = 0; i < 400 && FilmPilot.state(this.server, id).position().distanceTo(rest) > cutAt; i++) {
			this.server.runOnServer(s -> FilmPilot.apply(s, id, FilmPilot.autopilot(FilmPilot.state(s, id), rest, 0, returnGain, returnSpeed, returnBrake)));
			this.ctx.waitTick();
		}
		this.logState("return cut");
		this.rec.cut("return");
		this.event("return");
		int after = -1;
		for (int i = 0; i < 600; i++) {
			if (after < 0) {
				FilmPilot.State st = FilmPilot.state(this.server, id);
				if (i > 10 && FilmPilot.settled(st, rest, 0, 0.06)) {
					this.logState("settled, disassembling");
					VesselAssembly.Outcome outcome = this.server.computeOnServer(s -> VesselManager.get(s.overworld()).disassemble(id, null));
					if (!outcome.success()) {
						throw new AssertionError("disassembly failed: " + outcome.message().getString());
					}
					this.event("disassemble");
					after = 0;
					// The change from the vessel to its placed blocks takes the client a few ticks (0.1.1 drew neither for a tick
					// or two; 0.1.2 keeps the vessel's picture until the terrain shows the blocks, 2 to 6 ticks). Film time is
					// held over them: the next frame shows the placed blocks alone.
					BlockPos helmPos = FilmScene.HELM;
					int held = this.rec.hold(mc -> mc.level.getBlockState(helmPos).is(dev.timstewart.slipway.registry.SlipwayRegistry.HELM)
						&& dev.timstewart.slipway.client.ClientVessels.drawn(id) == null, 100);
					FilmMain.LOG.info("Reddit: disassembled; held film time {} ticks until the placed blocks were drawn and the vessel's picture was gone", held);
					continue;
				} else {
					this.server.runOnServer(s -> FilmPilot.apply(s, id, FilmPilot.autopilot(st, rest, 0, returnGain, returnSpeed, returnBrake)));
				}
			}
			this.rec.tick(3);
			if (after >= 0 && ++after >= (int)FilmRig.optDouble("holdTicks", 110)) {
				break;
			}
		}
		if (after < 0) {
			throw new AssertionError("the vessel did not settle at its starting point");
		}
	}

	/** Between shots: the game renders normally again and the camera rides along so the terrain around the ship loads. */
	private void gap() {
		long id = this.id;
		FilmClock.holdLoop = false;
		FilmCamera.set((t, p) -> {
			Vec3 ship = FilmScene.centre(id, p, Vec3.ZERO);
			return FilmCamera.Frame.lookAt(ship.add(-40, 14, 12), ship, 0);
		});
		FilmRig.followCamera(this.ctx);
	}

	/**
	 * Unfilmed ticks at a shot's first camera position (film time does not advance): Distant Horizons updates its far
	 * terrain around the new view, which otherwise shows as pale patches on the water for a second after a cut.
	 */
	private void settle() {
		this.ctx.waitTicks((int)FilmRig.optDouble("shotSettle", 100));
	}

	/** Autopilot (unfilmed) to a point and heading, until settled there. */
	private void flyTo(Vec3 target, double heading, int maxTicks, double distance) {
		long id = this.id;
		for (int i = 0; i < maxTicks; i++) {
			boolean done = this.server.computeOnServer(s -> {
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
			this.ctx.waitTick();
			FilmRig.followCamera(this.ctx);
		}
		this.logState("flyTo timeout");
		throw new AssertionError("autopilot did not reach " + target);
	}

	private void logState(String what) {
		FilmPilot.State st = FilmPilot.state(this.server, this.id);
		double[] att = st.pose().attitudeDegrees();
		// the server's own work per tick (it runs in step with the film, so waiting for frames does not count)
		double[] perf = this.server.computeOnServer(s -> {
			VesselManager manager = VesselManager.get(s.overworld());
			var world = manager.physics().worldIfStarted();
			return new double[] {s.getAverageTickTimeNanos() / 1.0e6, world == null ? 0 : world.lastStepNanos() / 1.0e6, manager.activeVessels().size()};
		});
		FilmMain.LOG.info("Reddit: {}: pos {} heading {} pitch {} roll {} tilt {} speed {}; server {} ms per tick, physics step {} ms, {} vessels", what, st.position(),
			fmt(st.heading()), fmt(att[0]), fmt(att[2]), fmt(st.tilt()), fmt(st.velocity().length()), fmt(perf[0]), String.format(Locale.ROOT, "%.2f", perf[1]), (int)perf[2]);
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	/** A direction of the vessel's own axes in the world. */
	private static Vec3 dir(VesselPose pose, double x, double y, double z) {
		Vector3d v = pose.rotate(x, y, z, new Vector3d());
		return new Vec3(v.x, v.y, v.z);
	}

	static double heading(VesselPose pose) {
		Vector3d f = pose.rotate(0, 0, -1, new Vector3d());
		return Math.toDegrees(Math.atan2(f.x, -f.z));
	}

	private static VesselRecord record(MinecraftServer s, long id) {
		return FilmPilot.active(s, id).record;
	}

	/** Pulls a lever of the vessel as a player's click does. */
	private static void lever(MinecraftServer s, long id, BlockPos helmRelative) {
		ServerLevel level = s.overworld();
		BlockPos pos = record(s, id).toPlot(helmRelative);
		BlockState state = level.getBlockState(pos);
		((LeverBlock)state.getBlock()).pull(state, level, pos, null);
	}

	/** Opens or closes the vessel's door as a player's click does. */
	private static void door(MinecraftServer s, long id, boolean open) {
		ServerLevel level = s.overworld();
		BlockPos pos = record(s, id).toPlot(FilmShips.HERO_DOOR);
		BlockState state = level.getBlockState(pos);
		((DoorBlock)state.getBlock()).setOpen(null, level, state, pos, open);
	}

	/**
	 * The chest's own open or close event: block event 1 with the number of players looking into it (1 or 0), which
	 * is what the chest sends when a player opens or closes it.
	 */
	private static void chest(MinecraftServer s, long id, boolean open) {
		ServerLevel level = s.overworld();
		BlockPos pos = record(s, id).toPlot(FilmShips.HERO_CHEST);
		level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, open ? 1 : 0);
	}

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
			sheep.snapTo(at.x, at.y, at.z, 150f + i * 40f, 0f);
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
		FilmMain.LOG.info("Reddit: sheep after the farm shot: {}", b);
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
