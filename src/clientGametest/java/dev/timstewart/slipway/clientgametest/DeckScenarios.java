package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.SlipwayDebug;
import dev.timstewart.slipway.math.VesselPose;
import java.util.ArrayList;
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
import net.minecraft.world.phys.Vec3;

/**
 * deck-walk: a player walks across a 13x13 deck while the vessel turns, stands still on it banked 45 degrees (within
 * the 50-degree walkable limit) without slipping, walks again while it turns banked, and when the deck is rolled past
 * 70 degrees slides or falls off instead of clipping through it. The rider is sampled every tick.
 */
final class DeckScenarios {
	private DeckScenarios() {
	}

	static final int HALF = 6;

	static Map<BlockPos, BlockState> deckShip() {
		Map<BlockPos, BlockState> blocks = Ships.deck(HALF, Blocks.SPRUCE_PLANKS.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		blocks.put(new BlockPos(0, 0, 5), Blocks.OAK_LOG.defaultBlockState());
		blocks.put(new BlockPos(0, 1, 5), Blocks.OAK_LOG.defaultBlockState());
		blocks.put(new BlockPos(5, 0, 5), Blocks.GLOWSTONE.defaultBlockState());
		return blocks;
	}

	static void deckWalk(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 30, 40);
			server.runOnServer(s -> Ships.build(s.overworld(), helm, deckShip()));
			long id = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm).id);
			server.runCommand("slipway mode " + id + " level false");
			Game.waitClientReady(ctx, id, 100);

			// Onto the deck's north-west quarter.
			placeRider(ctx, sp, server, id, new Vec3(-3.5, 0.3, -3.5));

			// Walk to the far corner while the vessel turns and moves.
			server.runCommand("slipway control " + id + " 0.5 0 0 0 1 0 140");
			ctx.waitTicks(10);
			List<Flight.Rider> walk = walk(ctx, server, id, new Vec3(3.5, 1.6, 3.5), 30);
			walk.addAll(riders(ctx, id, 20));
			checkOnDeck("walking during a turn", walk, id);
			double walked = walk.getFirst().local().distanceTo(walk.getLast().local());
			r.metric("turnWalk.localBlocks", walked);
			Check.atLeast("distance walked across the deck during the turn", walked, 1.5);
			Shots.take(ctx, r, "01-walking-during-turn");
			server.waitFor(s -> Game.active(s, id).record.angularVelocity.length() < 0.05, 200);

			// Bank to 45 degrees with the vessel's own roll control, then stand still on it.
			placeRider(ctx, sp, server, id, new Vec3(-2.5, 0.3, 0.5));
			double bank = bankTo(ctx, server, id, 45.0, 3.0);
			r.metric("bank.tiltDegrees", bank);
			List<Flight.Rider> still = riders(ctx, id, 40);
			checkOnDeck("standing on the 45 degree bank", still, id);
			double slip = still.getFirst().local().distanceTo(still.getLast().local());
			r.metric("bank.slipBlocks", slip);
			Check.atMost("slip while standing on a 45 degree bank for 40 ticks", slip, 0.2);
			Check.near("the bank holds (degrees)", Flight.sample(server, id).tilt(), 45.0, 4.0);
			Shots.take(ctx, r, "02-banked-45");

			// Walk again while the banked vessel turns.
			Flight.Sample before = Flight.sample(server, id);
			server.runCommand("slipway control " + id + " 0 0 0 0 0.4 0 100");
			List<Flight.Rider> bankWalk = walk(ctx, server, id, new Vec3(2.5, 1.6, 3.5), 25);
			bankWalk.addAll(riders(ctx, id, 20));
			checkOnDeck("walking on the banked deck during a turn", bankWalk, id);
			double bankWalked = bankWalk.getFirst().local().distanceTo(bankWalk.getLast().local());
			double bankTurn = Math.abs(Flight.yawChange(before, Flight.sample(server, id)));
			r.metric("bankWalk.localBlocks", bankWalked);
			r.metric("bankWalk.vesselYawDegrees", bankTurn);
			Check.atLeast("distance walked on the banked deck", bankWalked, 1.5);
			Check.atLeast("vessel yaw during the banked walk", bankTurn, 10.0);
			server.waitFor(s -> Game.active(s, id).record.angularVelocity.length() < 0.05, 200);

			// Too steep: roll past 70 degrees. The rider must slide or fall, never pass through the deck.
			placeRider(ctx, sp, server, id, new Vec3(-0.5, 0.3, 0.5));
			List<Flight.Rider> steep = new ArrayList<>();
			double steepest = 0.0;
			for (int i = 0; i < 120 && steepest < 72.0; i++) {
				server.runCommand("slipway control " + id + " 0 0 0 0 0 0.5 2");
				steep.addAll(riders(ctx, id, 2));
				steepest = Math.max(steepest, Flight.sample(server, id).tilt());
			}
			steep.addAll(riders(ctx, id, 30));
			r.metric("steep.tiltDegrees", steepest);
			Check.atLeast("steepest roll reached", steepest, 70.0);
			double lowest = Double.POSITIVE_INFINITY;
			for (Flight.Rider rider : steep) {
				if (rider.local() != null && overDeck(rider.local())) {
					lowest = Math.min(lowest, rider.local().y);
				}
			}
			r.metric("steep.lowestLocalY", lowest);
			Check.that(lowest >= -0.05, "the rider sank %.3f blocks into the steep deck", lowest);
			Flight.Rider last = steep.getLast();
			double slid = steep.getFirst().local().distanceTo(last.local());
			boolean off = last.carrier() != id || !overDeck(last.local());
			r.metric("steep.slidBlocks", slid);
			r.note("steep deck: carrier at the end %d, local %s, slid %.2f blocks", last.carrier(), fmt(last.local()), slid);
			Check.that(off || slid >= 1.0, "on a %.0f degree deck the rider neither slid (%.2f blocks) nor left the deck (carrier %d, local %s)",
				steepest, slid, last.carrier(), fmt(last.local()));
			Shots.take(ctx, r, "03-steep");
			server.runCommand("slipway mode " + id + " level true");
			server.waitFor(s -> Game.active(s, id).record.pose.tiltDegrees() < 5.0, 400);
		}
	}

	static boolean overDeck(Vec3 local) {
		return Math.abs(local.x) <= HALF + 0.5 && Math.abs(local.z) <= HALF + 0.5;
	}

	static String fmt(Vec3 v) {
		return v == null ? "none" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
	}

	/** Puts the player at a vessel-local point just above the deck and waits until the deck carries it. */
	static void placeRider(ClientGameTestContext ctx, TestSingleplayerContext sp, TestServerContext server, long id, Vec3 local) {
		placeRider(ctx, server, id, local);
	}

	static void placeRider(ClientGameTestContext ctx, TestServerContext server, long id, Vec3 local) {
		server.runOnServer(s -> {
			VesselPose pose = Game.active(s, id).record.pose;
			Vec3 world = pose.localToWorld(local);
			Game.player(s).teleportTo(s.overworld(), world.x, world.y, world.z, java.util.Set.of(), Game.player(s).getYRot(), 10f, true);
		});
		ctx.waitFor(mc -> mc.player.onGround() && ((dev.timstewart.slipway.vessel.VesselCollisions.Rider)mc.player).slipway$carrier() == id, 100);
		ctx.waitTicks(5);
	}

	/** Samples the rider every tick. */
	static List<Flight.Rider> riders(ClientGameTestContext ctx, long id, int ticks) {
		List<Flight.Rider> out = new ArrayList<>(ticks);
		for (int i = 0; i < ticks; i++) {
			ctx.waitTick();
			out.add(Flight.rider(ctx, id));
		}
		return out;
	}

	/** Walks (forward key) towards a vessel-local point for some ticks, steering every few ticks as a player would. */
	static List<Flight.Rider> walk(ClientGameTestContext ctx, TestServerContext server, long id, Vec3 target, int ticks) {
		List<Flight.Rider> out = new ArrayList<>(ticks);
		ctx.getInput().holdKey(o -> o.keyUp);
		try {
			for (int i = 0; i < ticks; i++) {
				if (i % 5 == 0) {
					ctx.runOnClient(mc -> SlipwayDebug.lookAtLocal(id, target.x, target.y, target.z));
				}
				ctx.waitTick();
				out.add(Flight.rider(ctx, id));
			}
		} finally {
			ctx.getInput().releaseKey(o -> o.keyUp);
		}
		return out;
	}

	/** Every sample carried by the vessel with the feet on (not in, not far above) the deck. */
	static void checkOnDeck(String phase, List<Flight.Rider> samples, long id) {
		for (int i = 0; i < samples.size(); i++) {
			Flight.Rider s = samples.get(i);
			Check.that(s.carrier() == id, "%s: tick %d the rider is not carried by the vessel (carrier %d, local %s)", phase, i, s.carrier(), fmt(s.local()));
			Check.that(s.local() != null && s.local().y > -0.05 && s.local().y < 1.3, "%s: tick %d the rider's feet are off the deck surface (local %s)",
				phase, i, fmt(s.local()));
		}
	}

	/** Rolls with short roll pulses until the tilt is within {@code tolerance} of {@code target}; returns the tilt. */
	static double bankTo(ClientGameTestContext ctx, TestServerContext server, long id, double target, double tolerance) {
		for (int i = 0; i < 80; i++) {
			double tilt = Flight.sample(server, id).tilt();
			double spin = server.computeOnServer(s -> Game.active(s, id).record.angularVelocity.length());
			if (Math.abs(tilt - target) <= tolerance && spin < 0.05) {
				return tilt;
			}
			if (Math.abs(tilt - target) > tolerance) {
				float roll = tilt < target ? 0.3f : -0.3f;
				server.runCommand(String.format(Locale.ROOT, "slipway control %d 0 0 0 0 0 %.2f 2", id, roll));
			}
			ctx.waitTicks(4);
		}
		throw new AssertionError(String.format(Locale.ROOT, "could not bank the vessel to %.0f +/- %.0f degrees (tilt %.1f)", target, tolerance,
			Flight.sample(server, id).tilt()));
	}
}
