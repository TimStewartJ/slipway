package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.SlipwayDebug;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * deck-jump: jumping and climbing on a vessel is what it is on the ground. A survival player jumps (one tap, then the
 * key held) and climbs a five-block ladder on plain ground, and the same on a vessel's deck: hovering still, under
 * way, climbing, descending, turning, banked, and afloat in a pool; and the ship set down on the ground at full speed
 * stops on it with the player still on the deck. Each jump is measured against the deck (how high,
 * how long in the air, how far it came down from where it left) and compared with the one on the ground.
 */
final class JumpScenarios {
	private JumpScenarios() {
	}

	private static final int HALF = 6;
	/** The ladder's column on the ship: a pillar five blocks high with the ladder on its north side. */
	private static final int PILLAR_X = 3, PILLAR_Z = 3, PILLAR_HEIGHT = 5;
	private static final int POOL_X = 0, POOL_Z = 90, POOL_HALF = 8, POOL_DEPTH = 5;

	static Map<BlockPos, BlockState> ladderShip() {
		Map<BlockPos, BlockState> blocks = Ships.deck(HALF, Blocks.SPRUCE_PLANKS.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		for (int y = 0; y < PILLAR_HEIGHT; y++) {
			blocks.put(new BlockPos(PILLAR_X, y, PILLAR_Z), Blocks.OAK_PLANKS.defaultBlockState());
			blocks.put(new BlockPos(PILLAR_X, y, PILLAR_Z - 1), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
		}
		return blocks;
	}

	static Map<BlockPos, BlockState> raft() {
		Map<BlockPos, BlockState> blocks = Ships.deck(3, Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}

	/** One tick of the player: position (against the deck, or in the world) and whether it is on the ground. */
	record Tick(Vec3 pos, boolean onGround, String detail) {
	}

	/** A jump: its height, the ticks off the ground, how far from the take-off it came down, how many take-offs. */
	record Jump(double apex, int airTicks, double drift, double endY, int takeoffs, int ticksOffGroundAtRest, List<Tick> ticks, Tick start) {
		@Override
		public String toString() {
			return String.format(Locale.ROOT, "apex %.3f, %d ticks in the air, came down %.3f away and %.3f higher, %d take-offs", apex, airTicks, drift, endY, takeoffs);
		}
	}

	private static Supplier<Tick> inWorld(ClientGameTestContext ctx) {
		return () -> ctx.computeOnClient(mc -> new Tick(mc.player.position(), mc.player.onGround(), ""));
	}

	private static Supplier<Tick> onVessel(ClientGameTestContext ctx, long id) {
		return () -> {
			Flight.Rider rider = Flight.rider(ctx, id);
			String detail = ctx.computeOnClient(mc -> {
				var contact = ((dev.timstewart.slipway.vessel.VesselCollisions.Rider)mc.player).slipway$contact();
				var vessel = dev.timstewart.slipway.client.ClientVessels.get(id);
				Vec3 v = mc.player.getDeltaMovement();
				return String.format(Locale.ROOT, "world y %.3f, velocity (%.3f, %.3f, %.3f), vessel y %.3f, contact stage %d%s%s%s, collided h %s v %s", mc.player.getY(), v.x, v.y, v.z,
					vessel == null ? Double.NaN : vessel.tickPose().y(), contact.stage, contact.ground ? " ground" : "", contact.wall ? " wall" : "", contact.steep ? " steep" : "",
					mc.player.horizontalCollision, mc.player.verticalCollision);
			});
			return new Tick(Check.notNull(rider.local(), "vessel %d is not there on the client", id), rider.onGround(), detail);
		};
	}

	private static List<Tick> sample(ClientGameTestContext ctx, Supplier<Tick> sampler, int ticks) {
		List<Tick> out = new ArrayList<>(ticks);
		for (int i = 0; i < ticks; i++) {
			ctx.waitTick();
			out.add(sampler.get());
		}
		return out;
	}

	/** Ticks standing still that the player was not on the ground. */
	private static int offGround(List<Tick> standing) {
		return (int)standing.stream().filter(t -> !t.onGround()).count();
	}

	/** Holds the jump key for {@code hold} ticks and follows the player for {@code ticks}. */
	private static Jump jump(ClientGameTestContext ctx, Supplier<Tick> sampler, int hold, int ticks) {
		List<Tick> rest = sample(ctx, sampler, 10);
		Tick start = rest.getLast();
		List<Tick> all = new ArrayList<>();
		ctx.getInput().holdKey(o -> o.keyJump);
		try {
			all.addAll(sample(ctx, sampler, hold));
		} finally {
			ctx.getInput().releaseKey(o -> o.keyJump);
		}
		all.addAll(sample(ctx, sampler, ticks - hold));
		double apex = 0;
		int air = 0, firstAir = 0, takeoffs = 0;
		boolean ground = true, first = true;
		for (Tick t : all) {
			apex = Math.max(apex, t.pos().y - start.pos().y);
			if (ground && !t.onGround()) {
				takeoffs++;
			}
			if (!t.onGround()) {
				air++;
			} else if (air > 0 && first) {
				firstAir = air;
				first = false;
			}
			ground = t.onGround();
		}
		Tick end = all.getLast();
		double drift = Math.hypot(end.pos().x - start.pos().x, end.pos().z - start.pos().z);
		return new Jump(apex, first ? air : firstAir, drift, end.pos().y - start.pos().y, takeoffs, offGround(rest), all, start);
	}

	/**
	 * Walks into the ladder (forward key, looking at the pillar) and returns the ticks from the feet leaving the floor
	 * at {@code baseY} to standing on top at {@code topY}; -1 when the player does not get there.
	 */
	private static int climb(ClientGameTestContext ctx, Supplier<Tick> sampler, Runnable look, double baseY, double topY, int limit, List<Tick> trace) {
		ctx.getInput().holdKey(o -> o.keyUp);
		try {
			int left = -1;
			for (int i = 0; i < limit; i++) {
				if (i % 5 == 0) {
					look.run();
				}
				ctx.waitTick();
				Tick t = sampler.get();
				trace.add(t);
				if (left < 0 && t.pos().y > baseY + 0.01) {
					left = i;
				}
				if (left >= 0 && t.pos().y >= topY - 0.01 && t.onGround()) {
					return i - left;
				}
			}
		} finally {
			ctx.getInput().releaseKey(o -> o.keyUp);
		}
		return -1;
	}

	static void deckJump(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			server.runCommand("difficulty peaceful");
			server.runCommand("gamemode survival @a");
			List<String> problems = new ArrayList<>();

			// On the ground: what a jump and a ladder are in the game.
			Game.teleport(ctx, sp, 20.5, Game.GROUND_Y + 2, 20.5, 0f, 10f);
			ctx.waitFor(mc -> mc.player.onGround(), 100);
			int floor = ctx.computeOnClient(mc -> mc.player.blockPosition().getY());
			server.runOnServer(s -> {
				for (int y = 0; y < PILLAR_HEIGHT; y++) {
					s.overworld().setBlock(new BlockPos(20, floor + y, 24), Blocks.OAK_PLANKS.defaultBlockState(), 3);
					s.overworld().setBlock(new BlockPos(20, floor + y, 23), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH), 3);
				}
			});
			Supplier<Tick> world = inWorld(ctx);
			Jump groundTap = jump(ctx, world, 2, 30);
			Jump groundHeld = jump(ctx, world, 60, 80);
			r.note("on the ground: one jump: %s; key held 60 ticks: %s", groundTap, groundHeld);
			ctx.getInput().lookAt(0f, 10f);
			List<Tick> groundClimb = new ArrayList<>();
			int groundClimbTicks = climb(ctx, world, () -> ctx.getInput().lookAt(0f, 10f), floor, floor + PILLAR_HEIGHT, 200, groundClimb);
			r.metric("ground.jumpApex", groundTap.apex());
			r.metric("ground.jumpAirTicks", groundTap.airTicks());
			r.metric("ground.heldTakeoffs", groundHeld.takeoffs());
			r.metric("ground.ladderTicks", groundClimbTicks);
			Check.that(groundClimbTicks > 0, "the player did not climb the ladder on the ground (ended at y %.2f above the floor)", groundClimb.getLast().pos().y - floor);

			// The ship.
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 60, 40);
			server.runOnServer(s -> Ships.build(s.overworld(), helm, ladderShip()));
			long id = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm).id);
			Game.waitClientReady(ctx, id, 100);
			Supplier<Tick> deck = onVessel(ctx, id);
			Vec3 spot = new Vec3(-3.5, 0.3, -3.5);

			DeckScenarios.placeRider(ctx, server, id, spot);
			compare(r, problems, "hovering still", ctx, server, deck, groundTap, groundHeld);
			Shots.take(ctx, r, "01-on-the-deck");

			// The ladder, hovering still.
			ladder(ctx, r, problems, server, id, deck, "hovering still", groundClimbTicks);
			Shots.take(ctx, r, "02-up-the-ladder");

			// Under way.
			DeckScenarios.placeRider(ctx, server, id, spot);
			server.runCommand("slipway control " + id + " 1 0 0 0 0 0 400");
			ctx.waitTicks(80);
			r.metric("underWay.speed", Flight.sample(server, id).velocity().length());
			compare(r, problems, "under way", ctx, server, deck, groundTap, groundHeld);
			ladder(ctx, r, problems, server, id, deck, "under way", groundClimbTicks);
			server.runCommand("slipway control " + id + " 0 0 0 0 0 0 1");
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() < 0.2, 400);

			// Climbing, then descending.
			DeckScenarios.placeRider(ctx, server, id, spot);
			server.runCommand("slipway control " + id + " 0 0 0.3 0 0 0 260");
			ctx.waitTicks(40);
			r.metric("climbing.verticalSpeed", Flight.sample(server, id).velocity().y);
			compare(r, problems, "climbing", ctx, server, deck, groundTap, groundHeld);
			server.runCommand("slipway control " + id + " 0 0 -0.3 0 0 0 260");
			ctx.waitTicks(60);
			r.metric("descending.verticalSpeed", Flight.sample(server, id).velocity().y);
			compare(r, problems, "descending", ctx, server, deck, groundTap, groundHeld);
			server.runCommand("slipway control " + id + " 0 0 0 0 0 0 1");
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() < 0.2, 400);

			// Turning, standing well away from the middle.
			DeckScenarios.placeRider(ctx, server, id, new Vec3(-4.5, 0.3, -4.5));
			server.runCommand("slipway control " + id + " 0 0 0 0 1 0 400");
			ctx.waitTicks(40);
			r.metric("turning.spin", server.computeOnServer(s -> Game.active(s, id).record.angularVelocity.length()));
			compare(r, problems, "turning", ctx, server, deck, groundTap, groundHeld);
			ladder(ctx, r, problems, server, id, deck, "turning", groundClimbTicks);
			server.runCommand("slipway control " + id + " 0 0 0 0 0 0 1");
			server.waitFor(s -> Game.active(s, id).record.angularVelocity.length() < 0.05, 400);

			// Banked 12 degrees, as a boat heels.
			server.runCommand("slipway mode " + id + " level false");
			DeckScenarios.placeRider(ctx, server, id, spot);
			r.metric("banked.tiltDegrees", DeckScenarios.bankTo(ctx, server, id, 12.0, 3.0));
			compare(r, problems, "banked", ctx, server, deck, groundTap, groundHeld);
			server.runCommand("slipway mode " + id + " level true");
			server.waitFor(s -> Game.active(s, id).record.pose.tiltDegrees() < 2.0, 400);

			// Down onto the ground at full speed: the ship stops on it and the player stays on the deck.
			DeckScenarios.placeRider(ctx, server, id, spot);
			server.runCommand("slipway control " + id + " 0 0 -1 0 0 0 600");
			int[] still = {0};
			double[] fastest = {0};
			for (int i = 0; i < 600 && still[0] < 20; i++) {
				ctx.waitTick();
				double speed = server.computeOnServer(s -> {
					var v = Game.manager(s).active(id);
					return v == null ? Double.NaN : v.record.linearVelocity.length();
				});
				Check.that(!Double.isNaN(speed), "tick %d of the descent: the vessel is gone from the server", i);
				fastest[0] = Math.max(fastest[0], speed);
				still[0] = i > 20 && speed < 0.05 ? still[0] + 1 : 0;
			}
			server.runCommand("slipway control " + id + " 0 0 0 0 0 0 1");
			double restY = Flight.sample(server, id).pose().y();
			Flight.Rider landed = Flight.rider(ctx, id);
			r.metric("landing.fastest", fastest[0]);
			r.metric("landing.helmAboveGround", restY - Game.GROUND_Y);
			r.note("set down on the ground: fastest %.1f blocks a second, the helm came to rest %.2f above the ground level, rider at %s", fastest[0], restY - Game.GROUND_Y,
				DeckScenarios.fmt(landed.local()));
			if (still[0] < 20 || restY < Game.GROUND_Y + 0.9) {
				problems.add(String.format(Locale.ROOT, "set down at full speed, the ship did not come to rest on the ground (helm %.2f above ground level)", restY - Game.GROUND_Y));
			}
			if (landed.carrier() != id || landed.local() == null || Math.abs(landed.local().y) > 0.1) {
				problems.add(String.format(Locale.ROOT, "set down at full speed, the player did not stay on the deck (carrier %d, at %s)", landed.carrier(), DeckScenarios.fmt(landed.local())));
			}
			Shots.take(ctx, r, "03-set-down");
			server.runCommand("slipway remove " + id);

			// Afloat: a raft in a pool, bobbing under the player's jumps.
			int top = Game.GROUND_Y + POOL_DEPTH - 1;
			server.runOnServer(s -> {
				var level = s.overworld();
				for (int x = POOL_X - POOL_HALF - 1; x <= POOL_X + POOL_HALF + 1; x++) {
					for (int z = POOL_Z - POOL_HALF - 1; z <= POOL_Z + POOL_HALF + 1; z++) {
						boolean wall = Math.abs(x - POOL_X) > POOL_HALF || Math.abs(z - POOL_Z) > POOL_HALF;
						for (int y = Game.GROUND_Y; y <= top + (wall ? 1 : 0); y++) {
							level.setBlock(new BlockPos(x, y, z), (wall ? Blocks.STONE : Blocks.WATER).defaultBlockState(), 2 | 16);
						}
					}
				}
			});
			BlockPos raftHelm = new BlockPos(POOL_X, top + 3, POOL_Z);
			Game.teleport(ctx, sp, POOL_X + 0.5, top + 3, POOL_Z + POOL_HALF + 1.5, 180f, 20f);
			server.runOnServer(s -> Ships.build(s.overworld(), raftHelm, raft()));
			long raft = server.computeOnServer(s -> Ships.assemble(s.overworld(), raftHelm).id);
			Game.waitClientReady(ctx, raft, 100);
			server.runCommand("slipway mode " + raft + " hover false");
			server.waitFor(s -> Game.active(s, raft).buoyancy.applied, 200);
			ctx.waitTicks(100);
			Supplier<Tick> onRaft = onVessel(ctx, raft);
			DeckScenarios.placeRider(ctx, server, raft, new Vec3(-1.5, 0.3, -1.5));
			ctx.waitTicks(60);
			compare(r, problems, "afloat", ctx, server, onRaft, groundTap, groundHeld);
			Shots.take(ctx, r, "04-on-the-raft");

			Check.that(problems.isEmpty(), "%d differences from jumping and climbing on the ground: %s", problems.size(), String.join("; ", problems));
		}
	}

	/** Damage the server has dealt the player so far, in tenths of a point of health (the statistic, so healing does not hide it). */
	private static int damageTaken(TestServerContext server) {
		return server.computeOnServer(s -> Game.player(s).getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DAMAGE_TAKEN)));
	}

	/** A tap and a held jump on a deck, against the same on the ground. */
	private static void compare(Report.Result r, List<String> problems, String where, ClientGameTestContext ctx, TestServerContext server, Supplier<Tick> deck, Jump groundTap,
		Jump groundHeld) {
		int hurtBefore = damageTaken(server);
		List<Tick> standing = sample(ctx, deck, 40);
		int off = offGround(standing);
		Jump tap = jump(ctx, deck, 2, 30);
		Jump held = jump(ctx, deck, 60, 80);
		String key = where.replace(' ', '_');
		r.note("%s: standing 40 ticks, off the ground %d; one jump: %s; key held 60 ticks: %s", where, off, tap, held);
		r.metric(key + ".standingOffGroundTicks", off);
		r.metric(key + ".jumpApex", tap.apex());
		r.metric(key + ".jumpAirTicks", tap.airTicks());
		r.metric(key + ".jumpDrift", tap.drift());
		r.metric(key + ".heldTakeoffs", held.takeoffs());
		int before = problems.size();
		if (off > 0) {
			problems.add(String.format(Locale.ROOT, "%s: standing still, off the ground %d of 40 ticks", where, off));
		}
		if (Math.abs(tap.apex() - groundTap.apex()) > 0.08) {
			problems.add(String.format(Locale.ROOT, "%s: a jump is %.3f blocks high (%.3f on the ground)", where, tap.apex(), groundTap.apex()));
		}
		if (Math.abs(tap.airTicks() - groundTap.airTicks()) > 1) {
			problems.add(String.format(Locale.ROOT, "%s: a jump is %d ticks in the air (%d on the ground)", where, tap.airTicks(), groundTap.airTicks()));
		}
		if (tap.takeoffs() != 1) {
			problems.add(String.format(Locale.ROOT, "%s: one tap of the jump key was %d take-offs", where, tap.takeoffs()));
		}
		if (tap.drift() > 0.15 || Math.abs(tap.endY()) > 0.05) {
			problems.add(String.format(Locale.ROOT, "%s: a standing jump came down %.3f blocks away and %.3f higher", where, tap.drift(), tap.endY()));
		}
		if (held.takeoffs() != groundHeld.takeoffs() || Math.abs(held.airTicks() - groundHeld.airTicks()) > 1) {
			problems.add(String.format(Locale.ROOT, "%s: holding the jump key 60 ticks was %d jumps, the first %d ticks in the air (%d and %d on the ground)", where, held.takeoffs(),
				held.airTicks(), groundHeld.takeoffs(), groundHeld.airTicks()));
		}
		if (held.drift() > 0.5) {
			problems.add(String.format(Locale.ROOT, "%s: jumping on the spot for 60 ticks drifted %.3f blocks", where, held.drift()));
		}
		int hurt = damageTaken(server) - hurtBefore;
		r.metric(key + ".damageTaken", hurt / 10.0);
		if (hurt > 0) {
			problems.add(String.format(Locale.ROOT, "%s: jumping on the deck cost the player %.1f points of health", where, hurt / 10.0));
		}
		if (Math.abs(held.apex() - groundHeld.apex()) > 0.08) {
			problems.add(String.format(Locale.ROOT, "%s: with the key held the jumps are %.3f blocks high (%.3f on the ground)", where, held.apex(), groundHeld.apex()));
		}
		if (problems.size() > before) {
			// Tick by tick, for whoever has to find out why.
			for (Jump jump : List.of(tap, held)) {
				StringBuilder trace = new StringBuilder();
				for (int i = 0; i < Math.min(30, jump.ticks().size()); i++) {
					Tick t = jump.ticks().get(i);
					trace.append(String.format(Locale.ROOT, "%n      %2d: %+.3f above the take-off%s; %s", i, t.pos().y - jump.start().pos().y, t.onGround() ? ", on the ground" : "", t.detail()));
				}
				r.note("%s, %s: %s", where, jump == tap ? "one jump" : "key held", trace);
			}
		}
	}

	/** Up the ship's ladder from the deck, against the time the same ladder takes on the ground. */
	private static void ladder(ClientGameTestContext ctx, Report.Result r, List<String> problems, TestServerContext server, long id, Supplier<Tick> deck, String where,
		int groundTicks) {
		DeckScenarios.placeRider(ctx, server, id, new Vec3(PILLAR_X + 0.5, 0.3, PILLAR_Z - 2.5));
		List<Tick> trace = new ArrayList<>();
		Runnable look = () -> ctx.runOnClient(mc -> SlipwayDebug.lookAtLocal(id, PILLAR_X + 0.5, 1.6, PILLAR_Z + 0.5));
		int ticks = climb(ctx, deck, look, 0.0, PILLAR_HEIGHT, 200, trace);
		double highest = trace.stream().mapToDouble(t -> t.pos().y).max().orElse(0);
		String key = where.replace(' ', '_');
		r.metric(key + ".ladderTicks", ticks);
		r.metric(key + ".ladderHighest", highest);
		r.note("%s: the ladder took %d ticks (%d on the ground), highest %.2f above the deck", where, ticks, groundTicks, highest);
		if (ticks < 0) {
			problems.add(String.format(Locale.ROOT, "%s: the player did not get up the ladder (highest %.2f blocks above the deck)", where, highest));
		} else if (Math.abs(ticks - groundTicks) > 3) {
			problems.add(String.format(Locale.ROOT, "%s: the ladder took %d ticks (%d on the ground)", where, ticks, groundTicks));
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < trace.size(); i++) {
				out.append(String.format(Locale.ROOT, "%n      %2d: %+.3f%s; %s", i, trace.get(i).pos().y, trace.get(i).onGround() ? ", on the ground" : "", trace.get(i).detail()));
			}
			r.note("%s, the ladder: %s", where, out);
		}
	}
}
