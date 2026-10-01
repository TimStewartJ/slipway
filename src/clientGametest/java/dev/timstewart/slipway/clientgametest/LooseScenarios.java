package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.PhysicsWorld;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * loose-cargo: ten loose vessels of 2 to 30 blocks are dropped onto a hovering carrier of 2,080 blocks. They come to
 * rest on its deck without sinking in, the carrier keeps its height, and the client's poses follow the server's while
 * they tumble and when they rest. The carrier then flies and turns gently (the cargo rides along), rolls 45 degrees
 * (the cargo slides off and falls to the ground, where it comes to rest and is no longer simulated). Server tick
 * times and physics step times are recorded for each phase. Last, a pilot switches loose on and off with the key.
 */
final class LooseScenarios {
	private LooseScenarios() {
	}

	static final int[] SIZES = {2, 3, 4, 5, 6, 8, 12, 16, 24, 30};
	static final int HALF_X = 16;
	static final int HALF_Z = 10;

	/** The carrier: three layers of 33 x 21 concrete under a north-facing helm (it flies towards +z): 2,080 blocks. */
	static Map<BlockPos, BlockState> carrier() {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		BlockState[] layers = {Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState(), Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(),
			Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState()};
		for (int y = -3; y <= -1; y++) {
			for (int x = -HALF_X; x <= HALF_X; x++) {
				for (int z = -HALF_Z; z <= HALF_Z; z++) {
					blocks.put(new BlockPos(x, y, z), layers[y + 3]);
				}
			}
		}
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}

	/**
	 * A piece of {@code blocks} blocks: a helm on a low pile of wood or stone, a row for up to four blocks, else filled
	 * layer by layer over a square footprint (up to 4x4 for thirty). Positions are relative to the helm.
	 */
	static Map<BlockPos, BlockState> piece(int blocks, int index) {
		int body = blocks - 1;
		int width = body <= 3 ? body : (int)Math.ceil(Math.sqrt(body / 2.0));
		int depth = body <= 3 ? 1 : width;
		int layers = (body + width * depth - 1) / (width * depth);
		BlockState state = switch (index % 3) {
			case 0 -> Blocks.OAK_PLANKS.defaultBlockState();
			case 1 -> Blocks.STONE_BRICKS.defaultBlockState();
			default -> Blocks.SPRUCE_PLANKS.defaultBlockState();
		};
		Map<BlockPos, BlockState> out = new LinkedHashMap<>();
		int placed = 0;
		for (int layer = 0; layer < layers; layer++) {
			for (int z = 0; z < depth && placed < body; z++) {
				for (int x = 0; x < width && placed < body; x++) {
					out.put(new BlockPos(x, layer - layers, z), state);
					placed++;
				}
			}
		}
		out.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return out;
	}

	/** Every active vessel's pose for every game tick, as {@link ServerPoses} keeps for one vessel. */
	static final class Poses {
		private static final Map<Long, ConcurrentSkipListMap<Long, VesselPose>> HISTORY = new ConcurrentHashMap<>();
		private static volatile boolean recording;
		private static boolean installed;

		static synchronized void start() {
			if (!installed) {
				installed = true;
				ServerTickEvents.END_LEVEL_TICK.register(level -> {
					VesselManager manager = recording && level.dimension() == Level.OVERWORLD ? VesselManager.getIfPresent(level) : null;
					if (manager != null) {
						for (ActiveVessel vessel : manager.activeVessels()) {
							HISTORY.computeIfAbsent(vessel.record.id, id -> new ConcurrentSkipListMap<>()).put(level.getGameTime() - 1, vessel.record.pose);
						}
					}
				});
			}
			HISTORY.clear();
			recording = true;
		}

		static void stop() {
			recording = false;
			HISTORY.clear();
		}

		static VesselPose at(long id, double tick) {
			ConcurrentSkipListMap<Long, VesselPose> history = HISTORY.get(id);
			if (history == null) {
				return null;
			}
			Map.Entry<Long, VesselPose> before = history.floorEntry((long)Math.floor(tick));
			Map.Entry<Long, VesselPose> after = history.ceilingEntry((long)Math.ceil(tick));
			if (before == null || after == null || after.getKey() - before.getKey() > 1) {
				return null;
			}
			return after.getKey().equals(before.getKey()) ? before.getValue() : before.getValue().interpolate(after.getValue(), tick - before.getKey());
		}
	}

	/** Largest distance (blocks) and angle (degrees) between the client's pose of each vessel and the server's pose for the tick the client shows. */
	static double[] clientError(ClientGameTestContext ctx, List<Long> ids) {
		List<double[]> shown = ctx.computeOnClient(mc -> {
			List<double[]> out = new ArrayList<>();
			for (long id : ids) {
				ClientVessel v = ClientVessels.get(id);
				Check.that(v != null && v.ready(), "the client does not know vessel %d", id);
				VesselPose p = v.tickPose();
				out.add(new double[] {id, v.playbackTick(), p.x(), p.y(), p.z(), p.qx(), p.qy(), p.qz(), p.qw()});
			}
			return out;
		});
		double distance = 0;
		double angle = 0;
		int compared = 0;
		for (double[] s : shown) {
			VesselPose server = Poses.at((long)s[0], s[1]);
			if (server == null) {
				continue;
			}
			VesselPose client = new VesselPose(s[2], s[3], s[4], s[5], s[6], s[7], s[8]);
			distance = Math.max(distance, client.position().distanceTo(server.position()));
			angle = Math.max(angle, FlightScenarios.angleBetween(client, server));
			compared++;
		}
		return new double[] {distance, angle, compared};
	}

	/** What the server knows about the cargo relative to the carrier. */
	record Cargo(long id, Vec3 local, double relativeSpeed, double spin, double speed, boolean awake, boolean finite) {
		boolean overDeck() {
			return Math.abs(this.local.x - 0.5) <= HALF_X + 4 && Math.abs(this.local.z - 0.5) <= HALF_Z + 4 && this.local.y > -1.0;
		}
	}

	static List<Cargo> cargo(MinecraftServer s, long carrier, List<Long> pieces) {
		VesselRecord base = Game.active(s, carrier).record;
		List<Cargo> out = new ArrayList<>();
		for (long id : pieces) {
			ActiveVessel v = Game.active(s, id);
			VesselRecord r = v.record;
			out.add(new Cargo(id, base.pose.worldToLocal(r.pose.position()), r.linearVelocity.subtract(base.linearVelocity).length(), r.angularVelocity.length(),
				r.linearVelocity.length(), v.bodyAwake, r.pose.position().isFinite() && r.linearVelocity.isFinite() && r.angularVelocity.isFinite()));
		}
		return out;
	}

	/** How far the blocks of one vessel reach into the full blocks of another (see the server GameTest of the same name). */
	static double overlap(ServerLevel level, VesselRecord a, VesselRecord b) {
		double[] insets = {0.05, 0.12, 0.2, 0.35, 0.5};
		double deepest = 0;
		for (BlockPos plot : BlockPos.betweenClosed(a.plotMin(), a.plotMax())) {
			if (level.getBlockState(plot).isAir()) {
				continue;
			}
			BlockPos local = a.toLocal(plot);
			for (double inset : insets) {
				for (int corner = 0; corner < 8; corner++) {
					Vec3 point = new Vec3(local.getX() + ((corner & 1) == 0 ? inset : 1 - inset), local.getY() + ((corner & 2) == 0 ? inset : 1 - inset),
						local.getZ() + ((corner & 4) == 0 ? inset : 1 - inset));
					Vec3 other = b.pose.worldToLocal(a.pose.localToWorld(point));
					BlockPos cell = BlockPos.containing(other);
					BlockState state = level.getBlockState(b.toPlot(cell));
					if (state.isAir() || !state.isCollisionShapeFullBlock(level, b.toPlot(cell))) {
						continue;
					}
					double inside = Math.min(Math.min(Math.min(other.x - cell.getX(), cell.getX() + 1 - other.x), Math.min(other.y - cell.getY(), cell.getY() + 1 - other.y)),
						Math.min(other.z - cell.getZ(), cell.getZ() + 1 - other.z));
					deepest = Math.max(deepest, Math.min(inset, inside));
				}
			}
		}
		return deepest;
	}

	/**
	 * Where a vessel's blocks are against the ground: the lowest corner of any of its blocks (world y), and how far its
	 * blocks reach into full blocks of the world (the largest inset of a block's corners that still lies in one).
	 */
	static double[] onTerrain(ServerLevel level, VesselRecord a) {
		double[] insets = {0.05, 0.12, 0.2, 0.35, 0.5};
		double lowest = Double.POSITIVE_INFINITY;
		double deepest = 0;
		for (BlockPos plot : BlockPos.betweenClosed(a.plotMin(), a.plotMax())) {
			if (level.getBlockState(plot).isAir()) {
				continue;
			}
			BlockPos local = a.toLocal(plot);
			for (int corner = 0; corner < 8; corner++) {
				lowest = Math.min(lowest, a.pose.localToWorld(new Vec3(local.getX() + (corner & 1), local.getY() + ((corner >> 1) & 1), local.getZ() + ((corner >> 2) & 1))).y);
				for (double inset : insets) {
					Vec3 world = a.pose.localToWorld(new Vec3(local.getX() + ((corner & 1) == 0 ? inset : 1 - inset), local.getY() + ((corner & 2) == 0 ? inset : 1 - inset),
						local.getZ() + ((corner & 4) == 0 ? inset : 1 - inset)));
					BlockPos cell = BlockPos.containing(world);
					BlockState state = level.getBlockState(cell);
					if (!state.isAir() && state.isCollisionShapeFullBlock(level, cell)) {
						double inside = Math.min(Math.min(Math.min(world.x - cell.getX(), cell.getX() + 1 - world.x), Math.min(world.y - cell.getY(), cell.getY() + 1 - world.y)),
							Math.min(world.z - cell.getZ(), cell.getZ() + 1 - world.z));
						deepest = Math.max(deepest, Math.min(inset, inside));
					}
				}
			}
		}
		return new double[] {lowest, deepest};
	}

	/** Tick times and physics times of one phase. */
	static final class Cost {
		final TickTimes ticks = new TickTimes();
		final List<Double> step = new ArrayList<>();
		final List<Double> exchange = new ArrayList<>();

		void sample(TestServerContext server) {
			server.runOnServer(s -> {
				this.ticks.add(s);
				PhysicsWorld world = Game.manager(s).physics().worldIfStarted();
				this.step.add(world == null ? 0.0 : world.lastStepNanos() / 1.0e6);
				this.exchange.add(Game.manager(s).physics().lastExchangeNanos() / 1.0e6);
			});
		}

		void report(Report.Result r, String phase) {
			r.metric(phase + ".ticks", this.ticks.size());
			r.metric(phase + ".physicsStepMsMean", this.step.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
			r.metric(phase + ".physicsStepMsMax", this.step.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));
			r.metric(phase + ".exchangeMsMean", this.exchange.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
			r.metric(phase + ".exchangeMsMax", this.exchange.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));
			this.ticks.check(r, phase);
			// The physics step runs beside the tick on its own thread; it must stay far inside one tick.
			Check.atMost(phase + ": physics step, worst (ms)", this.step.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN), 25.0);
		}
	}

	/** Stands the player on a glass block at a point given in the carrier's frame, looking at the middle of its deck. */
	static void watchFrom(ClientGameTestContext ctx, TestSingleplayerContext sp, long carrier, Vec3 local) {
		Vec3[] points = sp.getServer().computeOnServer(s -> {
			VesselPose pose = Game.active(s, carrier).record.pose;
			return new Vec3[] {pose.localToWorld(local), pose.localToWorld(new Vec3(0.5, 1, 0.5))};
		});
		watch(ctx, sp, points[0], points[1]);
	}

	/** Stands the player on a glass block at a point in the world, looking at another. */
	static void watch(ClientGameTestContext ctx, TestSingleplayerContext sp, Vec3 from, Vec3 target) {
		BlockPos glass = BlockPos.containing(from).below();
		sp.getServer().runCommand(String.format(Locale.ROOT, "setblock %d %d %d minecraft:glass", glass.getX(), glass.getY(), glass.getZ()));
		Vec3 d = target.subtract(glass.getX() + 0.5, glass.getY() + 1 + 1.62, glass.getZ() + 0.5);
		float yaw = (float)Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float)-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		Game.teleport(ctx, sp, glass.getX() + 0.5, glass.getY() + 1, glass.getZ() + 0.5, yaw, pitch);
	}

	static void looseCargo(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			// High enough that the rolled carrier's low edge stays far above the cargo that slid off it: cargo leaning on
			// a hovering vessel is never put to sleep.
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 32, 60);
			server.runOnServer(s -> Ships.build(s.overworld(), helm, carrier()));
			VesselRecord carrierRecord = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
			long carrier = carrierRecord.id;
			r.metric("carrier.blocks", carrierRecord.blockCount);
			Check.atLeast("carrier block count", carrierRecord.blockCount, 2000);

			// Ten pieces in two rows, four to six blocks above the deck, none touching another.
			List<Long> pieces = new ArrayList<>();
			int total = 0;
			for (int i = 0; i < SIZES.length; i++) {
				Map<BlockPos, BlockState> spec = piece(SIZES[i], i);
				int layers = 1 - spec.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0) - 1;
				BlockPos at = helm.offset(-14 + (i % 5) * 6, 4 + i % 3 + layers, i < 5 ? -7 : 3);
				VesselRecord record = server.computeOnServer(s -> {
					Ships.build(s.overworld(), at, spec);
					return Ships.assemble(s.overworld(), at);
				});
				Check.equal("blocks of piece " + i, record.blockCount, SIZES[i]);
				pieces.add(record.id);
				total += record.blockCount;
			}
			r.metric("cargo.vessels", pieces.size());
			r.metric("cargo.blocks", total);
			List<Long> everything = new ArrayList<>(pieces);
			everything.add(carrier);

			watchFrom(ctx, sp, carrier, new Vec3(0.5, 12, -HALF_Z - 12));
			for (long id : everything) {
				Game.waitClientReady(ctx, id, 200);
			}
			Game.waitClientComplete(ctx, carrier, 400);
			server.waitFor(s -> everything.stream().allMatch(id -> Game.active(s, id).hasBody), 200);
			double mass = server.computeOnServer(s -> Game.active(s, carrier).mass.mass());
			double load = server.computeOnServer(s -> pieces.stream().mapToDouble(id -> Game.active(s, id).mass.mass()).sum()) / mass;
			double startY = Game.serverPose(server, carrier).y();
			r.metric("carrier.massTonnes", mass / 1000.0);
			r.metric("cargo.loadFractionOfCarrierWeight", load);
			Shots.take(ctx, r, "01-before-drop");

			// Drop them: the agreed command makes each a plain rigid body.
			Poses.start();
			try {
				for (long id : pieces) {
					server.runCommand("slipway mode " + id + " loose true");
				}
				Cost drop = new Cost();
				double fastest = 0;
				double[] worstError = new double[2];
				int compared = 0;
				int calm = 0;
				int settledAfter = -1;
				for (int tick = 1; tick <= 600 && settledAfter < 0; tick++) {
					ctx.waitTick();
					drop.sample(server);
					List<Cargo> state = server.computeOnServer(s -> cargo(s, carrier, pieces));
					boolean allCalm = true;
					for (Cargo c : state) {
						Check.that(c.finite(), "tick %d: vessel %d is not finite", tick, c.id());
						Check.that(c.overDeck(), "tick %d: vessel %d left the deck while landing: %s", tick, c.id(), c.local());
						fastest = Math.max(fastest, c.speed());
						allCalm &= c.relativeSpeed() < 0.05 && c.spin() < 0.05;
					}
					if (tick > 4) {
						double[] error = clientError(ctx, everything);
						worstError[0] = Math.max(worstError[0], error[0]);
						worstError[1] = Math.max(worstError[1], error[1]);
						compared += (int)error[2];
					}
					if (tick == 12) {
						Shots.take(ctx, r, "02-falling");
					}
					calm = allCalm ? calm + 1 : 0;
					if (calm >= 40) {
						settledAfter = tick;
					}
				}
				r.metric("drop.settledAfterTicks", settledAfter);
				r.metric("drop.fastestBlocksPerSecond", fastest);
				r.metric("drop.clientServerOffsetMax", worstError[0]);
				r.metric("drop.clientServerDegreesMax", worstError[1]);
				r.metric("drop.clientPosesCompared", compared);
				Check.that(settledAfter > 0, "the cargo did not come to rest on the deck within 600 ticks");
				// The highest piece falls six blocks (11 m/s); anything much faster was thrown by a contact.
				Check.atMost("fastest piece while landing (blocks per second)", fastest, 14.0);
				Check.atLeast("client poses compared with the server's while the cargo landed", compared, 200);
				Check.atMost("client and server positions while the cargo landed", worstError[0], 0.05);
				Check.atMost("client and server rotations while the cargo landed (degrees)", worstError[1], 1.0);
				drop.report(r, "drop");

				// At rest: on the deck or on each other, not inside anything; the carrier kept its height and stays level.
				double deepest = server.computeOnServer(s -> {
					double worst = 0;
					for (long a : pieces) {
						for (long b : everything) {
							if (a != b) {
								worst = Math.max(worst, overlap(s.overworld(), Game.active(s, a).record, Game.active(s, b).record));
							}
						}
					}
					return worst;
				});
				Flight.Sample rest = Flight.sample(server, carrier);
				double sag = startY - rest.pose().y();
				double expectedSag = load * 9.81 / 2.25;
				r.metric("rest.deepestOverlapBlocks", deepest);
				r.metric("rest.carrierSagBlocks", sag);
				r.metric("rest.carrierSagExpected", expectedSag);
				r.metric("rest.carrierTiltDegrees", rest.tilt());
				Check.atMost("how far a piece rests inside another vessel (blocks)", deepest, 0.12);
				Check.near("how far the carrier sagged under the cargo (blocks)", sag, expectedSag, 0.05);
				Check.atMost("carrier tilt under the cargo (degrees)", rest.tilt(), 2.0);
				List<Cargo> rested = server.computeOnServer(s -> cargo(s, carrier, pieces));
				Cost resting = new Cost();
				for (int tick = 0; tick < 100; tick++) {
					ctx.waitTick();
					resting.sample(server);
				}
				List<Cargo> later = server.computeOnServer(s -> cargo(s, carrier, pieces));
				double crept = 0;
				for (int i = 0; i < pieces.size(); i++) {
					crept = Math.max(crept, rested.get(i).local().distanceTo(later.get(i).local()));
				}
				r.metric("rest.creepIn100TicksBlocks", crept);
				Check.atMost("movement of resting cargo in 100 ticks (blocks)", crept, 0.02);
				Check.near("carrier height after 100 more ticks", Game.serverPose(server, carrier).y(), rest.pose().y(), 0.01);
				double[] restError = clientError(ctx, everything);
				r.metric("rest.clientServerOffset", restError[0]);
				r.metric("rest.clientServerDegrees", restError[1]);
				Check.atMost("client and server positions at rest", restError[0], 0.02);
				Check.atMost("client and server rotations at rest (degrees)", restError[1], 0.5);
				resting.report(r, "rest");
				Shots.take(ctx, r, "03-at-rest");

				// Fly and turn as a careful pilot does: a fifth of full thrust, and easing off before letting go (the hard
				// stop of a released helm is what throws cargo; see LooseCargoTest.aHardStopSlidesLooseCargoAlongTheDeck).
				Cost flying = new Cost();
				double flightError = 0;
				for (int tick = 0; tick < 200; tick++) {
					if (tick == 0) {
						server.runCommand("slipway control " + carrier + " 0.2 0 0 0 0.25 0 140");
					} else if (tick == 140) {
						server.runCommand("slipway control " + carrier + " 0.1 0 0 0 0.1 0 30");
					} else if (tick == 170) {
						server.runCommand("slipway control " + carrier + " 0.04 0 0 0 0 0 30");
					}
					ctx.waitTick();
					flying.sample(server);
					flightError = Math.max(flightError, clientError(ctx, everything)[0]);
					if (tick == 120) {
						Shots.take(ctx, r, "04-flying");
					}
				}
				server.waitFor(s -> Game.active(s, carrier).record.linearVelocity.length() < 0.05 && Game.active(s, carrier).record.angularVelocity.length() < 0.02, 300);
				Flight.Sample flown = Flight.sample(server, carrier);
				List<Cargo> afterFlight = server.computeOnServer(s -> cargo(s, carrier, pieces));
				double shifted = 0;
				for (int i = 0; i < pieces.size(); i++) {
					Check.that(afterFlight.get(i).overDeck(), "vessel %d fell off during gentle flying: %s", pieces.get(i), afterFlight.get(i).local());
					shifted = Math.max(shifted, later.get(i).local().distanceTo(afterFlight.get(i).local()));
				}
				r.metric("flight.distance", Flight.horizontalDistance(rest, flown));
				r.metric("flight.yawDegrees", Math.abs(Flight.yawChange(rest, flown)));
				r.metric("flight.cargoShiftBlocks", shifted);
				r.metric("flight.clientServerOffsetMax", flightError);
				Check.atLeast("distance the loaded carrier flew", Flight.horizontalDistance(rest, flown), 15.0);
				Check.atLeast("yaw the loaded carrier turned (degrees)", Math.abs(Flight.yawChange(rest, flown)), 20.0);
				Check.atMost("how far any piece shifted on the deck during gentle flying (blocks)", shifted, 0.5);
				Check.atMost("client and server positions during the flight", flightError, 0.05);
				Check.near("carrier height after the flight", flown.pose().y(), rest.pose().y(), 0.1);
				flying.report(r, "flying");

				// Roll 45 degrees with level off: past the friction angle (31 degrees) everything slides off and falls.
				watchFrom(ctx, sp, carrier, new Vec3(0.5, 14, -HALF_Z - 16));
				server.runCommand("slipway mode " + carrier + " level false");
				Cost rolling = new Cost();
				double tilt = DeckScenarios.bankTo(ctx, server, carrier, 45.0, 4.0);
				r.metric("roll.tiltDegrees", tilt);
				Shots.take(ctx, r, "05-rolled");
				double groundTop = Game.GROUND_Y;
				int landedAfter = -1;
				double fastestFall = 0;
				for (int tick = 1; tick <= 800 && landedAfter < 0; tick++) {
					ctx.waitTick();
					rolling.sample(server);
					List<Cargo> state = server.computeOnServer(s -> cargo(s, carrier, pieces));
					boolean done = true;
					for (Cargo c : state) {
						Check.that(c.finite(), "roll tick %d: vessel %d is not finite", tick, c.id());
						fastestFall = Math.max(fastestFall, c.speed());
						done &= !c.awake();
					}
					if (done) {
						landedAfter = tick;
					}
				}
				r.metric("roll.cargoAsleepAfterTicks", landedAfter);
				r.metric("roll.fastestBlocksPerSecond", fastestFall);
				List<Cargo> afterRoll = server.computeOnServer(s -> cargo(s, carrier, pieces));
				Check.that(landedAfter > 0, "800 ticks after the roll some cargo is still simulated: %s", afterRoll);
				// From the low edge of the rolled deck it is about 20 blocks to the ground: 20 m/s, plus the slide.
				Check.atMost("fastest piece while sliding off and falling (blocks per second)", fastestFall, 40.0);
				List<double[]> landed = server.computeOnServer(s -> pieces.stream().map(id -> {
					VesselRecord record = Game.active(s, id).record;
					double[] ground = onTerrain(s.overworld(), record);
					return new double[] {id, ground[0], ground[1], record.linearVelocity.length()};
				}).toList());
				double lowest = Double.POSITIVE_INFINITY;
				double highest = Double.NEGATIVE_INFINITY;
				double sunk = 0;
				for (double[] p : landed) {
					lowest = Math.min(lowest, p[1]);
					highest = Math.max(highest, p[1]);
					sunk = Math.max(sunk, p[2]);
					Check.that(p[3] == 0.0, "vessel %d sleeps but its record still has speed %s", (long)p[0], p[3]);
				}
				r.metric("ground.lowestCorner", lowest);
				r.metric("ground.highestLowestCorner", highest);
				r.metric("ground.deepestInTerrainBlocks", sunk);
				// Every piece lies on the ground (its top is at GROUND_Y) or on another piece, none in it or still aloft.
				Check.atLeast("lowest block corner of the cargo on the ground", lowest, groundTop - 0.06);
				Check.atMost("lowest block corner of the highest-lying piece", highest, groundTop + 6.0);
				Check.atMost("how far a piece lies inside the ground (blocks)", sunk, 0.12);
				Check.near("carrier roll held while the cargo slid off (degrees)", Flight.sample(server, carrier).tilt(), tilt, 6.0);
				rolling.report(r, "rolling");
				Cost asleep = new Cost();
				for (int tick = 0; tick < 100; tick++) {
					ctx.waitTick();
					asleep.sample(server);
				}
				asleep.report(r, "asleep");
				double[] groundError = clientError(ctx, everything);
				r.metric("ground.clientServerOffset", groundError[0]);
				Check.atMost("client and server positions of the cargo on the ground", groundError[0], 0.02);
				Vec3 heap = server.computeOnServer(s -> {
					Vec3 sum = Vec3.ZERO;
					for (long id : pieces) {
						sum = sum.add(Game.active(s, id).record.pose.position());
					}
					return sum.scale(1.0 / pieces.size());
				});
				watch(ctx, sp, heap.add(-14, 9, -14), heap);
				ctx.waitTicks(10);
				Shots.take(ctx, r, "06-cargo-on-the-ground");
			} finally {
				Poses.stop();
			}

			// The pilot's own key: loose on (the HUD shows it, the carrier starts to fall), and off again (hover catches it).
			server.runCommand("slipway mode " + carrier + " level true");
			server.waitFor(s -> Game.active(s, carrier).record.pose.tiltDegrees() < 3.0 && Game.active(s, carrier).record.angularVelocity.length() < 0.02, 400);
			DeckScenarios.placeRider(ctx, server, carrier, new Vec3(0.5, 0.3, -2.5));
			Flight.takeHelm(ctx, server, carrier);
			double before = Game.serverPose(server, carrier).y();
			ctx.getInput().pressKey(Game.key(ctx, "key.slipway.toggle_loose"));
			server.waitFor(s -> Game.active(s, carrier).record.loose, 10);
			ctx.waitFor(mc -> ClientVessels.get(carrier).loose, 10);
			ctx.getInput().holdKeyFor(o -> o.keyJump, 6);
			double fell = before - Game.serverPose(server, carrier).y();
			r.metric("key.fellWhileLooseBlocks", fell);
			Check.atLeast("how far the loose carrier fell with the pilot holding the climb key (blocks)", fell, 0.3);
			Shots.take(ctx, r, "07-pilot-loose");
			ctx.getInput().pressKey(Game.key(ctx, "key.slipway.toggle_loose"));
			server.waitFor(s -> !Game.active(s, carrier).record.loose, 10);
			ctx.waitFor(mc -> !ClientVessels.get(carrier).loose, 10);
			server.waitFor(s -> Game.active(s, carrier).record.linearVelocity.length() < 0.05, 200);
			Check.that(server.computeOnServer(s -> Game.active(s, carrier).record.hover && Game.active(s, carrier).record.level), "loose changed the carrier's hover or level");
			Check.equal("the pilot still rides the helm", Flight.ridingVessel(ctx), carrier);
		}
	}
}