package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.SlipwayDebug;
import dev.timstewart.slipway.math.VesselPose;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * flight-rotation: from the helm, with the pilot's own keys, a small ship climbs and turns level, then (level mode off)
 * pitches, rolls fully inverted and holds it, rolls back, loops through vertical, levels itself again and is
 * disassembled. Every tick is sampled: the pose stays finite and continuous (no snapping), rotation keeps to the
 * commanded axis through vertical (no gimbal lock), and the ship's blocks and chest survive. During the level turn
 * every frame is sampled too: the pilot's view turns with the ship as it is drawn in that frame.
 */
final class FlightScenarios {
	private FlightScenarios() {
	}

	static void flightRotation(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 40, 48);
			Map<BlockPos, BlockState> spec = Ships.smallShip();
			server.runOnServer(s -> {
				Ships.build(s.overworld(), helm, spec);
				Ships.fillSmall(s.overworld(), helm);
			});
			Map<BlockPos, CompoundTag> chest = server.computeOnServer(s -> Ships.blockEntityData(s.overworld(), helm, Set.of(Ships.SMALL_CHEST)));
			Game.teleport(ctx, sp, helm.getX() + 0.5, helm.getY(), helm.getZ() + 2.5, 180f, 20f);
			ctx.waitFor(mc -> mc.player.onGround(), 40);
			ctx.getInput().lookAt(helm);
			ctx.waitTick();
			ctx.getInput().pressKey(o -> o.keyUse);
			server.waitFor(s -> Game.manager(s).registry().size() == 1, 20);
			long id = server.computeOnServer(s -> Game.manager(s).registry().all().iterator().next().id);
			Game.waitClientReady(ctx, id, 100);
			Flight.takeHelm(ctx, server, id);

			// Climb, then turn with level mode on: the deck stays level.
			Flight.Sample start = Flight.sample(server, id);
			List<Flight.Sample> climb = Flight.hold(ctx, server, id, 30, "key.jump");
			Flight.checkContinuous("climb", climb);
			r.metric("climb.blocks", climb.getLast().pose().y() - start.pose().y());
			Check.atLeast("height gained climbing for 30 ticks", climb.getLast().pose().y() - start.pose().y(), 1.0);
			ctx.runOnClient(mc -> SlipwayDebug.viewTraceStart(id));
			List<Flight.Sample> turn = Flight.hold(ctx, server, id, 40, "key.right");
			turn.addAll(Flight.run(ctx, server, id, 20));
			SlipwayDebug.ViewTrace pilotView = ctx.computeOnClient(mc -> SlipwayDebug.viewTraceStop());
			Flight.checkContinuous("level turn", turn);
			double turned = Math.abs(Flight.yawChange(climb.getLast(), turn.getLast()));
			r.metric("levelTurn.yawDegrees", turned);
			r.metric("levelTurn.maxTiltDegrees", Flight.maxTilt(turn));
			Check.atLeast("yaw turned in 40 ticks of full yaw", turned, 45.0);
			Check.atMost("tilt during a level turn", Flight.maxTilt(turn), 5.0);
			// The pilot's view keeps its place on the ship in every frame. Turned once a tick, it stands still between
			// ticks and then jumps a tick's turn, which shows most in the frames early in a tick.
			r.metric("levelTurn.view.frames", pilotView.frames());
			r.metric("levelTurn.view.framesEarlyInATick", pilotView.earlyFrames());
			r.metric("levelTurn.view.drawnYawDegrees", Math.abs(pilotView.turned()));
			r.metric("levelTurn.view.largestTickTurnDegrees", pilotView.largestTickTurn());
			r.metric("levelTurn.view.slipDegrees", pilotView.slip());
			r.note("level turn: the pilot's view and the ship drawn in the same frame turned at most %.4f degrees apart over %d frames; the ship turned up to %.4f degrees a tick",
				pilotView.slip(), pilotView.frames(), pilotView.largestTickTurn());
			Check.atLeast("frames drawn during the level turn", pilotView.frames(), 100);
			Check.atLeast("frames drawn in the first half of a tick during the level turn", pilotView.earlyFrames(), pilotView.frames() / 5.0);
			Check.atLeast("the ship's largest turn in one tick while it was drawn (degrees)", pilotView.largestTickTurn(), 2.0);
			Check.atMost("how far the pilot's view and the ship drawn in the same frame turn apart (degrees)", pilotView.slip(), 0.1);
			Shots.take(ctx, r, "01-after-yaw");

			// Level mode off (the pilot's toggle key).
			ctx.getInput().pressKey(Game.key(ctx, "key.slipway.toggle_level"));
			server.waitFor(s -> !Game.active(s, id).record.level, 10);
			ctx.waitFor(mc -> !dev.timstewart.slipway.client.ClientVessels.get(id).level, 10);

			// Pitch up for 12 ticks and let it settle for a second (the rotation carries on), then back down.
			List<Flight.Sample> pitch = Flight.hold(ctx, server, id, 12, "key.slipway.pitch_up");
			pitch.addAll(Flight.run(ctx, server, id, 20));
			double pitched = pitch.stream().mapToDouble(s -> Math.abs(s.pose().attitudeDegrees()[0])).max().orElse(0);
			r.metric("pitch.maxDegrees", pitched);
			Check.atLeast("pitch after 12 ticks of pitch-up and a second to settle", pitched, 15.0);
			pitch.addAll(Flight.hold(ctx, server, id, 12, "key.slipway.pitch_down"));
			pitch.addAll(Flight.run(ctx, server, id, 20));
			Flight.checkContinuous("pitch", pitch);
			Shots.take(ctx, r, "02-pitched");

			// Roll until fully inverted, then hold it there with no input.
			List<Flight.Sample> roll = rollUntil(ctx, server, id, "key.slipway.roll_right", s -> s.tilt() > 165.0, 300);
			List<Flight.Sample> held = Flight.run(ctx, server, id, 30);
			roll.addAll(held);
			Flight.checkContinuous("roll to inverted", roll);
			long inverted = held.stream().filter(s -> s.up().y < -0.94).count();
			r.metric("inverted.ticksHeld", inverted);
			r.metric("inverted.maxTiltDegrees", Flight.maxTilt(roll));
			Check.that(inverted >= 25, "the vessel did not stay inverted (up vector pointing down) with no input: %d of 30 ticks", inverted);
			Check.that(roll.stream().allMatch(Flight.Sample::piloted), "the pilot fell off the helm while inverted");
			Check.equal("the pilot still rides the helm (client)", Flight.ridingVessel(ctx), id);
			Shots.take(ctx, r, "03-inverted");

			// Roll back upright.
			List<Flight.Sample> back = rollUntil(ctx, server, id, "key.slipway.roll_left", s -> s.tilt() < 8.0, 300);
			back.addAll(Flight.run(ctx, server, id, 10));
			Flight.checkContinuous("roll back", back);

			// A full pitch loop through vertical and over the top: rotation stays about the vessel's own pitch axis.
			List<Flight.Sample> loop = Flight.hold(ctx, server, id, 150, "key.slipway.pitch_up");
			Flight.checkContinuous("pitch loop", loop);
			boolean throughVertical = loop.stream().anyMatch(s -> s.tilt() > 60.0 && s.tilt() < 120.0);
			double loopTilt = Flight.maxTilt(loop);
			double axis = pitchAxisShare(loop);
			r.metric("loop.maxTiltDegrees", loopTilt);
			r.metric("loop.pitchAxisShare", axis);
			Check.that(throughVertical, "the pitch loop never passed through vertical (tilt 60..120 degrees)");
			Check.atLeast("tilt reached in the pitch loop", loopTilt, 150.0);
			Check.atLeast("share of the loop's rotation about the vessel's pitch axis", axis, 0.9);
			Shots.take(ctx, r, "04-loop");

			// Level mode back on: it rights itself.
			ctx.getInput().pressKey(Game.key(ctx, "key.slipway.toggle_level"));
			server.waitFor(s -> Game.active(s, id).record.level, 10);
			int levelTicks = server.waitFor(s -> Game.active(s, id).record.pose.tiltDegrees() < 5.0, 400);
			r.metric("levelling.ticks", levelTicks);
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() < 0.05 && Game.active(s, id).record.angularVelocity.length() < 0.02, 400);
			Flight.Sample rest = Flight.sample(server, id);
			ctx.waitTicks(5);
			Game.ClientView view = Game.clientView(ctx, id);
			double offset = view.pose().position().distanceTo(rest.pose().position());
			double angle = angleBetween(view.pose(), rest.pose());
			r.metric("rest.clientServerOffset", offset);
			r.metric("rest.clientServerDegrees", angle);
			Check.atMost("client and server positions at rest", offset, 0.05);
			Check.atMost("client and server rotations at rest (degrees)", angle, 1.0);
			Shots.take(ctx, r, "05-levelled");

			// Disassemble from the deck: every block back, turned by whole quarter turns, chest intact.
			Flight.disassembleAsPlayer(ctx, server, id);
			BlockPos around = BlockPos.containing(rest.pose().x(), rest.pose().y(), rest.pose().z());
			Ships.Landing landing = server.computeOnServer(s -> Ships.findLanding(s.overworld(), around, 3, Direction.NORTH));
			List<String> mismatches = server.computeOnServer(s -> Ships.landedMismatches(s.overworld(), landing, spec));
			Check.that(mismatches.isEmpty(), "the disassembled ship differs from the original: %s", mismatches);
			Check.equal("chest after the flight", server.computeOnServer(s -> Ships.landedBlockEntityData(s.overworld(), landing, Set.of(Ships.SMALL_CHEST))), chest);
			Shots.take(ctx, r, "06-disassembled");
		}
	}

	private static List<Flight.Sample> rollUntil(ClientGameTestContext ctx, TestServerContext server, long id, String key, java.util.function.Predicate<Flight.Sample> done, int maxTicks) {
		net.minecraft.client.KeyMapping k = Game.key(ctx, key);
		List<Flight.Sample> samples = new java.util.ArrayList<>();
		ctx.getInput().holdKey(k);
		try {
			for (int i = 0; i < maxTicks; i++) {
				ctx.waitTick();
				Flight.Sample s = Flight.sample(server, id);
				samples.add(s);
				if (done.test(s)) {
					return samples;
				}
			}
		} finally {
			ctx.getInput().releaseKey(k);
		}
		throw new AssertionError(String.format("%s held for %d ticks did not reach the target attitude (last tilt %.1f)", key, maxTicks, samples.getLast().tilt()));
	}

	/**
	 * How much of the rotation during a pitch loop is about the vessel's own pitch (x) axis: the angular-velocity
	 * weighted mean of |local x| / |w| over ticks with meaningful spin. 1 is a pure pitch rotation.
	 */
	static double pitchAxisShare(List<Flight.Sample> samples) {
		double weighted = 0.0;
		double total = 0.0;
		for (Flight.Sample s : samples) {
			Vec3 w = s.angularVelocity();
			double length = w.length();
			if (length < 0.1) {
				continue;
			}
			org.joml.Vector3d local = s.pose().inverseRotate(w.x, w.y, w.z, new org.joml.Vector3d());
			weighted += Math.abs(local.x);
			total += length;
		}
		return total == 0.0 ? 0.0 : weighted / total;
	}

	static double angleBetween(VesselPose a, VesselPose b) {
		double dot = Math.abs(a.qx() * b.qx() + a.qy() * b.qy() + a.qz() * b.qz() + a.qw() * b.qw());
		return Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot)));
	}
}
