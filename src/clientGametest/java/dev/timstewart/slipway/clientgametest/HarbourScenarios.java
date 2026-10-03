package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.render.WaterMask;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.FluidField;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/**
 * make-harbour (a diagnostic: it runs only when named): makes a world to try floating in by hand, and tries it there
 * first. A normal world with a fixed seed; at the nearest deep sea a pier with four things moored at it, built of
 * blocks and not yet assembled: a raft of logs, a boat of planks with a mast, a closed submarine of planks and glass
 * with ballast, and a barge of stone bricks. Each is then assembled and tried in the open sea, with Distant Horizons
 * and with the Bliss shader pack: the boat floats, keeps its hold dry and sails; the submarine dives with its cabin
 * dry and comes up again when hover is turned off; the barge of stone floats; the raft floats as a loose body. After
 * its trial each is put back where it was built and disassembled, so the saved world has them as blocks, moored and
 * dry. The world is saved as "Slipway Harbor" in the test client's saves folder.
 */
final class HarbourScenarios {
	private HarbourScenarios() {
	}

	static final String WORLD_NAME = "Slipway Harbor";
	private static final String SEED = "slipway harbour";
	/** The topmost water block of the sea, and its surface. */
	private static final int SEA = 62;
	private static final double SURFACE = SEA + 8.0 / 9.0;
	/** The sea's floor must lie this deep or deeper under the whole harbour. */
	private static final int FLOOR_MAX = 44;
	/** Kelp under the harbour ends here: below the deepest hull, the barge's, and what it sinks by when loaded. */
	private static final int KELP_TOP = 50;
	private static final int SHADER_SETTLE_TICKS = 60;

	/** One moored thing: its blocks (relative to its helm), the air it must keep dry, and where its helm is. */
	record Moored(String name, BlockPos helm, Map<BlockPos, BlockState> blocks, List<BlockPos> dryAir) {
	}

	// A helm's FACING is the side with the wheel, which faces the pilot: SOUTH for a bow to the north. The pilot stands
	// on the block south of the helm.

	private static BlockState planks() {
		return Blocks.OAK_PLANKS.defaultBlockState();
	}

	/** A raft: five by five logs, a helm and two barrels on it. */
	static Moored raft(BlockPos helm) {
		Map<BlockPos, BlockState> b = new LinkedHashMap<>();
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				b.put(new BlockPos(x, -1, z), Blocks.OAK_LOG.defaultBlockState());
			}
		}
		b.put(new BlockPos(-2, 0, 2), Blocks.BARREL.defaultBlockState());
		b.put(new BlockPos(2, 0, -2), Blocks.BARREL.defaultBlockState());
		b.put(BlockPos.ZERO, Ships.helm(Direction.SOUTH));
		return new Moored("raft", helm, b, List.of());
	}

	/** A boat: five wide and nine long with a blunt bow to the north, two rows of wall, a mast and a sail of wool. */
	static Moored boat(BlockPos helm) {
		Map<BlockPos, BlockState> b = new LinkedHashMap<>();
		List<BlockPos> air = new ArrayList<>();
		// The helm stands on the floor, two blocks from the stern: rows z = -6 (bow) to 2 (stern).
		for (int z = -6; z <= 2; z++) {
			int half = z == -6 ? 1 : 2;
			for (int x = -half; x <= half; x++) {
				b.put(new BlockPos(x, -1, z), planks());
				boolean wall = z == 2 || z == -6 || Math.abs(x) == 2;
				for (int y = 0; y <= 1; y++) {
					if (wall) {
						b.put(new BlockPos(x, y, z), planks());
					} else {
						air.add(new BlockPos(x, y, z));
					}
				}
			}
		}
		for (int y = 0; y <= 5; y++) {
			b.put(new BlockPos(0, y, -2), Blocks.OAK_LOG.defaultBlockState());
			air.remove(new BlockPos(0, y, -2));
		}
		for (int y = 3; y <= 5; y++) {
			for (int x = -2; x <= 2; x++) {
				if (x != 0) {
					b.put(new BlockPos(x, y, -2), Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState());
				}
			}
		}
		b.put(BlockPos.ZERO, Ships.helm(Direction.SOUTH));
		air.remove(BlockPos.ZERO);
		return new Moored("boat", helm, b, air);
	}

	/**
	 * A submarine: a closed box five wide, five high and nine long of dark oak with windows along both sides and at
	 * the front, a hatch in its roof over a ladder, two lanterns, and four blocks of iron as ballast: a little lighter
	 * than the water it displaces.
	 */
	static Moored submarine(BlockPos helm) {
		Map<BlockPos, BlockState> b = new LinkedHashMap<>();
		List<BlockPos> air = new ArrayList<>();
		BlockState shell = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		// The helm stands on the floor two blocks behind the front wall: x = -2..2, y = -1..3, z = -2 (front) .. 6 (back).
		for (int x = -2; x <= 2; x++) {
			for (int y = -1; y <= 3; y++) {
				for (int z = -2; z <= 6; z++) {
					boolean outer = Math.abs(x) == 2 || y == -1 || y == 3 || z == -2 || z == 6;
					if (!outer) {
						air.add(new BlockPos(x, y, z));
						continue;
					}
					boolean side = Math.abs(x) == 2 && (y == 1 || y == 2) && z >= 0 && z <= 4;
					boolean front = z == -2 && Math.abs(x) <= 1 && (y == 1 || y == 2);
					b.put(new BlockPos(x, y, z), side || front ? Blocks.GLASS.defaultBlockState() : shell);
				}
			}
		}
		// The ballast lies aft of the middle: it balances the glass of the front window, so the boat floats level.
		for (int x = -1; x <= 1; x += 2) {
			for (int z = 3; z <= 4; z++) {
				put(b, air, new BlockPos(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
			}
		}
		put(b, air, new BlockPos(0, 2, 1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		put(b, air, new BlockPos(0, 2, 4), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		for (int y = 0; y <= 2; y++) {
			put(b, air, new BlockPos(0, y, 5), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
		}
		b.put(new BlockPos(0, 3, 5), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP).setValue(TrapDoorBlock.FACING, Direction.NORTH));
		put(b, air, BlockPos.ZERO, Ships.helm(Direction.SOUTH));
		return new Moored("submarine", helm, b, air);
	}

	/**
	 * A barge of stone bricks: thirteen wide, seventeen long, a floor and six rows of wall, with a deck of planks
	 * across its stern for the helm, another across its bow to balance it, and a ladder down into the hold. 557
	 * blocks of stone, 1,337 t, and 46 t of planks: it floats on the 1,300 m^3 of air below its rim, with three
	 * quarters of a block of freeboard.
	 */
	static Moored barge(BlockPos helm) {
		Map<BlockPos, BlockState> b = new LinkedHashMap<>();
		List<BlockPos> air = new ArrayList<>();
		BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
		// The helm stands on the stern deck: the deck is y = -1, the floor y = -7, the walls go up to y = -1.
		// x = -6..6, z = -14 (bow) .. 2 (stern); the decks cover z = -1..1 and z = -13..-11 inside the walls.
		for (int x = -6; x <= 6; x++) {
			for (int z = -14; z <= 2; z++) {
				b.put(new BlockPos(x, -7, z), stone);
				boolean wall = Math.abs(x) == 6 || z == -14 || z == 2;
				for (int y = -6; y <= -1; y++) {
					if (wall) {
						b.put(new BlockPos(x, y, z), stone);
					} else if (y == -1 && (z >= -1 || z <= -11)) {
						b.put(new BlockPos(x, y, z), planks());
					} else {
						air.add(new BlockPos(x, y, z));
					}
				}
			}
		}
		for (int y = -6; y <= -1; y++) {
			put(b, air, new BlockPos(-5, y, -4), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
		}
		b.put(BlockPos.ZERO, Ships.helm(Direction.SOUTH));
		return new Moored("barge", helm, b, air);
	}

	private static void put(Map<BlockPos, BlockState> blocks, List<BlockPos> air, BlockPos at, BlockState state) {
		blocks.put(at, state);
		air.remove(at);
	}

	/** Whether the sea is open and deep enough for the harbour around a place. */
	private static boolean deepSeaAround(ServerLevel level, int ox, int oz) {
		for (int x = ox - 24; x <= ox + 28; x += 4) {
			for (int z = oz - 24; z <= oz + 8; z += 4) {
				if (level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) > FLOOR_MAX || !level.getFluidState(new BlockPos(x, SEA, z)).isSource()
					|| !level.getBlockState(new BlockPos(x, SEA + 1, z)).isAir()) {
					return false;
				}
			}
		}
		return true;
	}

	private static void sign(ServerLevel level, BlockPos at, String... lines) {
		level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0), 3);
		if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
			SignText.Mutable text = SignText.EMPTY.asMutable();
			for (int i = 0; i < lines.length && i < SignText.LINES; i++) {
				text.setLine(i, Component.literal(lines[i]));
			}
			sign.setText(text.asImmutable(), SignTextSlot.FRONT);
			sign.setWaxed(true);
			sign.setChanged();
		}
	}

	/** The pier, south of the moorings: a deck of planks one block above the water on posts down to the sea's floor. */
	private static void pier(ServerLevel level, int ox, int oz) {
		for (int x = ox - 19; x <= ox + 22; x++) {
			for (int z = oz + 2; z <= oz + 4; z++) {
				level.setBlock(new BlockPos(x, SEA + 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), 2 | 16);
			}
			if (Math.floorMod(x - ox + 19, 7) == 0 || x == ox + 22) {
				for (int z = oz + 2; z <= oz + 4; z += 2) {
					for (int y = SEA; y >= level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z); y--) {
						level.setBlock(new BlockPos(x, y, z), Blocks.SPRUCE_LOG.defaultBlockState(), 2 | 16);
					}
				}
				level.setBlock(new BlockPos(x, SEA + 2, oz + 4), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
				level.setBlock(new BlockPos(x, SEA + 3, oz + 4), Blocks.LANTERN.defaultBlockState(), 3);
			}
		}
		// Cargo to overload a hull with.
		for (int x = ox + 20; x <= ox + 22; x++) {
			for (int y = SEA + 2; y <= SEA + 3; y++) {
				level.setBlock(new BlockPos(x, y, oz + 3), Blocks.IRON_BLOCK.defaultBlockState(), 3);
			}
		}
	}

	private static void build(ServerLevel level, Moored m) {
		for (BlockPos air : m.dryAir()) {
			level.setBlock(m.helm().offset(air), Blocks.AIR.defaultBlockState(), 2 | 16);
		}
		Ships.build(level, m.helm(), m.blocks());
	}

	/** What is not as it was built: blocks that differ, and water in the air the hull keeps dry. */
	private static List<String> mismatches(ServerLevel level, Moored m) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : m.blocks().entrySet()) {
			BlockState now = level.getBlockState(m.helm().offset(e.getKey()));
			if (now.getBlock() != e.getValue().getBlock()) {
				out.add(e.getKey().toShortString() + " is " + now + ", built as " + e.getValue());
			}
		}
		for (BlockPos air : m.dryAir()) {
			BlockState now = level.getBlockState(m.helm().offset(air));
			if (now.getBlock() instanceof LiquidBlock || !now.getFluidState().isEmpty()) {
				out.add("water at " + air.toShortString());
			} else if (!now.isAir()) {
				out.add(air.toShortString() + " is " + now + ", built as air");
			}
		}
		return out;
	}

	/** Assembles a moored thing and waits until the client has it. */
	private static long assemble(ClientGameTestContext ctx, TestServerContext server, Report.Result r, Moored m) {
		long id = server.computeOnServer(s -> Ships.assemble(s.overworld(), m.helm()).id);
		Game.waitClientReady(ctx, id, 200);
		ctx.waitTicks(10);
		double[] numbers = server.computeOnServer(s -> {
			ActiveVessel v = Game.active(s, id);
			return new double[] {v.mass.mass() / 1000.0, v.hull.capacity(), v.hull.shelteredVolume(), v.hull.sealedVolume(), v.buoyancyReserve(), v.hull.elementCount()};
		});
		r.metric(m.name() + ".massTonnes", numbers[0]);
		r.metric(m.name() + ".capacity", numbers[1]);
		r.metric(m.name() + ".shelteredAir", numbers[2]);
		r.metric(m.name() + ".sealedAir", numbers[3]);
		r.metric(m.name() + ".reserve", numbers[4]);
		r.metric(m.name() + ".elements", numbers[5]);
		return id;
	}

	/** Waits until a vessel lies still on the water and checks that it floats; returns how deep its helm lies below where it was built. */
	private static double waitAfloat(ClientGameTestContext ctx, TestServerContext server, Report.Result r, Moored m, long id, VesselPose built, double maxTilt) {
		int still = 0;
		List<Flight.Sample> samples = new ArrayList<>();
		for (int i = 0; i < 900 && still < 20; i++) {
			ctx.waitTick();
			Flight.Sample s = Flight.sample(server, id);
			samples.add(s);
			still = i > 20 && s.velocity().length() < 0.03 && s.angularVelocity().length() < 0.03 ? still + 1 : 0;
		}
		Flight.checkContinuous(m.name() + " settling on the sea", samples);
		Flight.Sample rest = samples.getLast();
		AfloatScenarios.Water water = AfloatScenarios.water(server, id);
		double lowest = samples.stream().mapToDouble(s -> s.pose().y()).min().orElse(Double.NaN);
		r.metric(m.name() + ".ticksToRest", samples.size());
		r.metric(m.name() + ".sinkage", built.y() - rest.pose().y());
		r.metric(m.name() + ".deepestSinkage", built.y() - lowest);
		r.metric(m.name() + ".tiltAtRest", rest.pose().tiltDegrees());
		r.metric(m.name() + ".displaced", water.displaced());
		Check.that(still >= 20, "the %s did not come to rest on the sea within %d ticks: speed %.3f", m.name(), samples.size(), rest.velocity().length());
		Check.that(water.fluid() == FluidField.WATER && water.applied() && !water.flooding(), "the %s on the sea: %s", m.name(), water);
		Check.near(m.name() + ": water displaced at rest (m^3) against its mass (t)", water.displaced(), water.mass() / 1000.0, 0.03 * water.mass() / 1000.0 + 0.3);
		Check.atMost(m.name() + ": tilt at rest (degrees)", rest.pose().tiltDegrees(), maxTilt);
		return built.y() - rest.pose().y();
	}

	/** Puts a vessel back where it was built and disassembles it; everything must be as built, and dry. */
	private static void moorAgain(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r, Moored m, long id, VesselPose built, Vec3 watch) {
		TestServerContext server = sp.getServer();
		AfloatScenarios.flyTo(ctx, sp, watch.x, watch.y, watch.z, 180f, 30f);
		server.runCommand("slipway mode " + id + " loose false");
		server.runCommand("slipway mode " + id + " hover true");
		server.runOnServer(s -> VesselManager.get(s.overworld()).teleport(Game.active(s, id), built));
		ctx.waitTicks(20);
		String failure = server.computeOnServer(s -> {
			var outcome = VesselManager.get(s.overworld()).disassemble(id, null);
			return outcome.success() ? "" : outcome.message().getString();
		});
		Check.that(failure.isEmpty(), "the %s could not be disassembled at its mooring: %s", m.name(), failure);
		ctx.waitTicks(40);
		List<String> wrong = server.computeOnServer(s -> mismatches(s.overworld(), m));
		r.metric(m.name() + ".mooredAgain.mismatches", wrong.size());
		Check.that(wrong.isEmpty(), "the %s moored again is not as it was built: %s", m.name(), wrong.size() > 12 ? wrong.subList(0, 12) + " and " + (wrong.size() - 12) + " more"
			: wrong);
	}

	/** The view from a place relative to a vessel, looking at it. */
	private static void lookFrom(ClientGameTestContext ctx, TestSingleplayerContext sp, long id, Vec3 localEye, Vec3 localTarget) {
		Vec3[] points = sp.getServer().computeOnServer(s -> {
			VesselPose pose = Game.active(s, id).record.pose;
			return new Vec3[] {pose.localToWorld(localEye), pose.localToWorld(localTarget)};
		});
		Vec3 d = points[1].subtract(points[0]);
		float yaw = (float)Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float)-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		// The player's eyes are 1.62 above the feet.
		AfloatScenarios.flyTo(ctx, sp, points[0].x, points[0].y - 1.62, points[0].z, yaw, pitch);
	}

	private static void standIn(ClientGameTestContext ctx, TestServerContext server, long id, Vec3 local, float yaw, float pitch) {
		server.runOnServer(s -> {
			Game.player(s).getAbilities().flying = false;
			Game.player(s).onUpdateAbilities();
		});
		DeckScenarios.placeRider(ctx, server, id, local);
		ctx.getInput().lookAt(yaw, pitch);
		ctx.waitTicks(20);
	}

	/** What the game makes of the player where they stand. */
	private record Standing(double eyeY, boolean waterAtEyes, boolean waterAtFeet, boolean inWater, boolean underWater, boolean viewUnderWater, int air, int maxAir, long carrier) {
	}

	private static Standing standing(ClientGameTestContext ctx, long id) {
		return ctx.computeOnClient(mc -> new Standing(mc.player.getEyeY(), !mc.level.getFluidState(BlockPos.containing(mc.player.getEyePosition())).isEmpty(),
			!mc.level.getFluidState(BlockPos.containing(mc.player.getX(), mc.player.getY() + 0.1, mc.player.getZ())).isEmpty(), mc.player.isInWater(), mc.player.isUnderWater(),
			mc.gameRenderer.mainCamera().getFluidInCamera() != FogType.NONE, mc.player.getAirSupply(), mc.player.getMaxAirSupply(),
			((dev.timstewart.slipway.vessel.VesselCollisions.Rider)mc.player).slipway$carrier()));
	}

	private static boolean iris() {
		return FabricLoader.getInstance().isModLoaded("iris");
	}

	/** Pictures of the same view without the shader pack and with it. */
	private static void shots(ClientGameTestContext ctx, Report.Result r, String name) {
		ctx.waitTicks(10);
		Shots.take(ctx, r, name);
		if (iris()) {
			RenderScenarios.shaders(ctx, r, true);
			ctx.waitTicks(SHADER_SETTLE_TICKS);
			Shots.take(ctx, r, name + "-bliss");
			ctx.runOnClient(mc -> WaterMask.enabled = false);
			ctx.waitTicks(SHADER_SETTLE_TICKS);
			Shots.take(ctx, r, name + "-bliss-mask-off");
			ctx.runOnClient(mc -> WaterMask.enabled = true);
			RenderScenarios.shaders(ctx, r, false);
		}
	}

	static void makeHarbour(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> {
			s.setSeed(SEED);
			s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			s.setAllowCommands(true);
			s.setBonusChest(false);
			s.setName(WORLD_NAME);
		}).create()) {
			TestServerContext server = sp.getServer();
			server.runCommand("gamerule spawn_mobs false");
			server.runCommand("gamerule spawn_monsters false");
			server.runCommand("weather clear 1000000");
			server.runCommand("time set 2000");
			server.runCommand("gamerule advance_time false");

			// The nearest deep sea, and in it a place where the floor lies deep under the whole harbour.
			int[] sea = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos from = s.getRespawnData().pos();
				var found = level.findClosestBiome3d(h -> h.is(Biomes.DEEP_OCEAN) || h.is(Biomes.DEEP_LUKEWARM_OCEAN), from, 6400, 32, 64);
				if (found == null) {
					found = level.findClosestBiome3d(h -> h.is(Biomes.OCEAN) || h.is(Biomes.LUKEWARM_OCEAN), from, 6400, 32, 64);
				}
				return found == null ? null : new int[] {found.getFirst().getX(), found.getFirst().getZ(), from.getX(), from.getZ()};
			});
			Check.that(sea != null, "no sea within 6,400 blocks of the world's spawn");
			r.note("world spawn at %d, %d; nearest deep sea at %d, %d", sea[2], sea[3], sea[0], sea[1]);
			int[] origin = null;
			search:
			for (int ring = 0; ring <= 12; ring++) {
				for (int dx = -ring; dx <= ring; dx++) {
					for (int dz = -ring; dz <= ring; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
							continue;
						}
						int ox = sea[0] + dx * 32, oz = sea[1] + dz * 32;
						if (server.computeOnServer(s -> deepSeaAround(s.overworld(), ox, oz))) {
							origin = new int[] {ox, oz};
							break search;
						}
					}
				}
			}
			Check.that(origin != null, "no open sea with its floor at %d or deeper within 384 blocks of %d, %d", FLOOR_MAX, sea[0], sea[1]);
			int ox = origin[0], oz = origin[1];
			r.note("harbour at %d, %d", ox, oz);
			r.metric("harbour.x", ox);
			r.metric("harbour.z", oz);
			Game.hud(ctx, false);
			AfloatScenarios.flyTo(ctx, sp, ox + 1.5, SEA + 14, oz + 22.5, 180f, 30f);

			// Moorings north of the pier, bows to the north, a block of water between each and the pier.
			Moored raft = raft(new BlockPos(ox - 15, SEA + 1, oz - 2));
			Moored boat = boat(new BlockPos(ox - 7, SEA, oz - 2));
			Moored submarine = submarine(new BlockPos(ox + 1, SEA - 2, oz - 6));
			Moored barge = barge(new BlockPos(ox + 13, SEA + 1, oz - 2));
			List<Moored> all = List.of(raft, boat, submarine, barge);
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				// Kelp is cut below the hulls and kept from growing back up into them (it stays out of a vessel anyway).
				for (BlockPos p : BlockPos.betweenClosed(ox - 24, KELP_TOP, oz - 24, ox + 28, SEA, oz + 8)) {
					BlockState state = level.getBlockState(p);
					if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getFluidState().isEmpty() || !state.getCollisionShape(level, p).isEmpty()) {
						continue;
					}
					boolean kelp = state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT);
					level.setBlock(p, p.getY() == KELP_TOP && kelp ? Blocks.KELP.defaultBlockState().setValue(net.minecraft.world.level.block.KelpBlock.AGE, 25)
						: Blocks.WATER.defaultBlockState(), 2 | 16);
				}
				pier(level, ox, oz);
				all.forEach(m -> build(level, m));
				sign(level, new BlockPos(ox - 15, SEA + 2, oz + 2), "RAFT", "Use the helm.", "Hover off or", "loose: it floats");
				sign(level, new BlockPos(ox - 7, SEA + 2, oz + 2), "BOAT", "Use the helm,", "hover off,", "then sail");
				sign(level, new BlockPos(ox + 1, SEA + 2, oz + 2), "SUBMARINE", "Hatch on top.", "Hover on: dive", "with descend");
				sign(level, new BlockPos(ox + 13, SEA + 2, oz + 2), "STONE BARGE", "Hover off: it", "floats. Load", "iron: it sinks");
				sign(level, new BlockPos(ox + 19, SEA + 2, oz + 2), "CARGO", "Iron to", "overload", "a hull with");
			});
			ctx.waitTicks(60);
			for (Moored m : all) {
				List<String> wrong = server.computeOnServer(s -> mismatches(s.overworld(), m));
				Check.that(wrong.isEmpty(), "the %s as built at its mooring: %s", m.name(), wrong.size() > 12 ? wrong.subList(0, 12) : wrong);
			}
			Game.waitTerrain(ctx, 1200);
			shots(ctx, r, "01-harbour-built");
			Vec3 watch = new Vec3(ox + 1.5, SEA + 14, oz + 22.5);

			// The boat: floats, keeps its hold dry, sails.
			{
				long id = assemble(ctx, server, r, boat);
				VesselPose built = Flight.sample(server, id).pose();
				server.runCommand("slipway mode " + id + " hover false");
				double sinkage = waitAfloat(ctx, server, r, boat, id, built, 4.0);
				// Built with its bottom a block and 8/9 under the surface; it floats where it displaces its weight.
				Check.that(sinkage > -1.0 && sinkage < 0.8, "the boat settled %.2f blocks below where it was built", sinkage);
				standIn(ctx, server, id, new Vec3(0.5, 0.05, 1.5), 205f, 5f);
				Standing in = standing(ctx, id);
				r.metric("boat.feetBelowSurface", SURFACE - (in.eyeY() - 1.62));
				Check.that(in.waterAtFeet(), "the world has no water where the player stands in the boat: the check would prove nothing");
				Check.that(!in.inWater() && !in.viewUnderWater() && !server.computeOnServer(s -> Game.player(s).isInWater()),
					"standing in the boat's hold counts as in the water: %s", in);
				shots(ctx, r, "02-boat-from-its-hold");
				lookFrom(ctx, sp, id, new Vec3(0.5, 12.0, 1.0), new Vec3(0.5, 0.0, -1.5));
				shots(ctx, r, "03-boat-from-above");
				lookFrom(ctx, sp, id, new Vec3(9.0, 4.5, 12.0), new Vec3(0.5, 0.5, -2.0));
				shots(ctx, r, "04-boat-afloat");

				// Under way: full thrust for six seconds, then a turn.
				standIn(ctx, server, id, new Vec3(0.5, 0.05, 1.5), 205f, 5f);
				Flight.Sample before = Flight.sample(server, id);
				server.runCommand("slipway control " + id + " 1 0 0 0 0 0 120");
				List<Flight.Sample> run = Flight.run(ctx, server, id, 120);
				Flight.checkContinuous("the boat under thrust", run);
				Flight.Sample after = run.getLast();
				double fastest = run.stream().mapToDouble(s -> Math.hypot(s.velocity().x, s.velocity().z)).max().orElse(0);
				r.metric("boat.run.distance", Flight.horizontalDistance(before, after));
				r.metric("boat.run.fastest", fastest);
				r.metric("boat.run.maxTilt", Flight.maxTilt(run));
				r.metric("boat.run.heave", run.stream().mapToDouble(s -> Math.abs(s.pose().y() - before.pose().y())).max().orElse(0));
				Check.atLeast("distance the boat sailed in six seconds (blocks)", Flight.horizontalDistance(before, after), 20.0);
				Check.atLeast("how far north, bow first, the boat sailed (blocks)", before.pose().z() - after.pose().z(), 20.0);
				Check.atLeast("the boat's fastest speed (blocks/s)", fastest, 5.0);
				Check.atMost("the boat's fastest speed (blocks/s)", fastest, 12.0);
				Check.atMost("the boat's tilt under thrust (degrees)", Flight.maxTilt(run), 10.0);
				Standing sailing = standing(ctx, id);
				Check.equal("the player is carried by the boat under way", sailing.carrier(), id);
				Check.that(!sailing.inWater() && !sailing.viewUnderWater() && !server.computeOnServer(s -> Game.player(s).isInWater()),
					"standing in the hold of the boat under way counts as in the water: %s", sailing);
				Shots.take(ctx, r, "05-boat-under-way-from-its-hold");
				server.runCommand("slipway control " + id + " 0.6 0 0 0 1 0 80");
				List<Flight.Sample> turn = Flight.run(ctx, server, id, 80);
				r.metric("boat.turn.yawDegrees", Flight.yawChange(after, turn.getLast()));
				r.metric("boat.turn.maxTilt", Flight.maxTilt(turn));
				Check.atLeast("how far the boat turned in four seconds (degrees)", Math.abs(Flight.yawChange(after, turn.getLast())), 20.0);
				Check.that(!AfloatScenarios.water(server, id).flooding(), "the boat took in water while sailing");
				Check.equal("the player is carried by the boat through the turn", standing(ctx, id).carrier(), id);
				server.runCommand("slipway control " + id + " 0 0 0 0 0 0 1");
				ctx.waitTicks(100);
				lookFrom(ctx, sp, id, new Vec3(10.0, 5.0, 14.0), new Vec3(0.5, 0.5, -2.0));
				shots(ctx, r, "06-boat-out-at-sea");
				moorAgain(ctx, sp, r, boat, id, built, watch);
			}

			// The submarine: dives with its cabin dry, and comes up when hover is turned off.
			{
				long id = assemble(ctx, server, r, submarine);
				VesselPose built = Flight.sample(server, id).pose();
				double sealed = server.computeOnServer(s -> Game.active(s, id).hull.sealedVolume());
				Check.atLeast("air sealed in the submarine (m^3)", sealed, 50.0);
				standIn(ctx, server, id, new Vec3(0.5, 0.05, 2.5), 180f, 0f);
				// Down with the descend axis, never faster than 2.5 blocks a second, to eight blocks below its mooring.
				for (int i = 0; i < 600; i++) {
					Flight.Sample s = Flight.sample(server, id);
					if (s.pose().y() <= built.y() - 8.0) {
						break;
					}
					if (s.velocity().y > -2.5) {
						server.runCommand("slipway control " + id + " 0 0 -0.5 0 0 0 2");
					}
					ctx.waitTick();
				}
				server.runCommand("slipway control " + id + " 0 0 0 0 0 0 1");
				ctx.waitTicks(80);
				Flight.Sample deep = Flight.sample(server, id);
				r.metric("submarine.dive.depth", built.y() - deep.pose().y());
				r.metric("submarine.dive.speedAtRest", deep.velocity().length());
				Check.atLeast("how deep the submarine dived (blocks)", built.y() - deep.pose().y(), 7.0);
				Check.atMost("the hovering submarine's speed after the dive", deep.velocity().length(), 0.1);
				Standing in = standing(ctx, id);
				r.metric("submarine.eyesBelowSurface", SURFACE - in.eyeY());
				Check.atLeast("how far the player's eyes are under the sea's surface in the submarine (blocks)", SURFACE - in.eyeY(), 6.0);
				Check.that(in.waterAtEyes(), "the world has no water at the player's eyes in the submarine: the check would prove nothing");
				Check.equal("the player is carried by the submarine", in.carrier(), id);
				Check.that(!in.inWater() && !in.underWater() && !in.viewUnderWater() && in.air() == in.maxAir() && !server.computeOnServer(s -> Game.player(s).isInWater()),
					"the player in the submarine's cabin, eight blocks down, counts as in the water: %s", in);
				shots(ctx, r, "07-submarine-cabin-looking-ahead");
				ctx.getInput().lookAt(90f, 0f);
				shots(ctx, r, "08-submarine-cabin-looking-out-of-a-window");
				// Ten more seconds under water: the breath stays.
				ctx.waitTicks(200);
				Standing later = standing(ctx, id);
				Check.that(later.air() == later.maxAir() && !later.inWater(), "after ten seconds in the submerged cabin: %s", later);
				lookFrom(ctx, sp, id, new Vec3(9.0, 4.0, -9.0), new Vec3(0.5, 1.0, 2.0));
				shots(ctx, r, "09-submarine-from-outside-under-water");
				// Hover off: lighter than the water it displaces, it comes up and floats.
				server.runCommand("slipway mode " + id + " hover false");
				waitAfloat(ctx, server, r, submarine, id, built, 4.0);
				Flight.Sample up = Flight.sample(server, id);
				// Its roof is four blocks above its helm's floor.
				r.metric("submarine.roofAboveSurface", up.pose().y() + 4.0 - SURFACE);
				Check.that(up.pose().y() + 4.0 > SURFACE + 0.2 && up.pose().y() + 4.0 < SURFACE + 2.0, "the submarine's roof lies %.2f above the surface", up.pose().y() + 4.0 - SURFACE);
				lookFrom(ctx, sp, id, new Vec3(9.0, 6.0, 14.0), new Vec3(0.5, 2.0, 2.0));
				shots(ctx, r, "10-submarine-surfaced");
				moorAgain(ctx, sp, r, submarine, id, built, watch);
			}

			// The barge: stone floats on the air its walls keep dry.
			{
				long id = assemble(ctx, server, r, barge);
				VesselPose built = Flight.sample(server, id).pose();
				server.runCommand("slipway mode " + id + " hover false");
				waitAfloat(ctx, server, r, barge, id, built, 3.0);
				Flight.Sample rest = Flight.sample(server, id);
				// Its rim is the top of the helm's deck: the helm's own height.
				r.metric("barge.freeboard", rest.pose().y() - SURFACE);
				Check.atLeast("the barge's freeboard (blocks)", rest.pose().y() - SURFACE, 0.4);
				standIn(ctx, server, id, new Vec3(0.5, -5.95, -7.5), 180f, -10f);
				Standing in = standing(ctx, id);
				r.metric("barge.eyesBelowSurface", SURFACE - in.eyeY());
				Check.atLeast("how far the player's eyes are under the sea's surface in the barge's hold (blocks)", SURFACE - in.eyeY(), 3.0);
				Check.that(in.waterAtEyes() && !in.inWater() && !in.viewUnderWater() && in.air() == in.maxAir(), "the player in the barge's hold: %s", in);
				shots(ctx, r, "11-barge-from-its-hold");
				lookFrom(ctx, sp, id, new Vec3(0.5, 22.0, -5.5), new Vec3(0.5, 0.0, -6.0));
				shots(ctx, r, "12-barge-from-above");
				lookFrom(ctx, sp, id, new Vec3(20.0, 8.0, 16.0), new Vec3(0.5, -2.0, -6.0));
				shots(ctx, r, "13-barge-afloat");
				moorAgain(ctx, sp, r, barge, id, built, watch);
			}

			// The raft: a loose body on the sea.
			{
				long id = assemble(ctx, server, r, raft);
				VesselPose built = Flight.sample(server, id).pose();
				server.runCommand("slipway mode " + id + " loose true");
				waitAfloat(ctx, server, r, raft, id, built, 6.0);
				lookFrom(ctx, sp, id, new Vec3(7.0, 4.0, 9.0), new Vec3(0.5, 0.0, 0.5));
				Shots.take(ctx, r, "14-raft-loose-on-the-sea");
				moorAgain(ctx, sp, r, raft, id, built, watch);
			}

			// As it is left for a player: nothing assembled, everything moored and dry, the player on the pier.
			int vessels = server.computeOnServer(s -> VesselManager.get(s.overworld()).activeVessels().size());
			Check.equal("vessels left in the world", vessels, 0);
			server.runCommand("kill @e[type=item]");
			server.runCommand("gamerule advance_time true");
			server.runCommand("time set 1000");
			server.runCommand("weather clear 24000");
			server.runCommand("gamerule spawn_mobs true");
			server.runCommand("gamerule spawn_monsters true");
			server.runCommand("setworldspawn " + (ox - 3) + " " + (SEA + 2) + " " + (oz + 4));
			server.runCommand("give @a slipway:helm 16");
			server.runCommand("give @a minecraft:iron_block 64");
			server.runCommand("give @a minecraft:oak_planks 64");
			AfloatScenarios.flyTo(ctx, sp, ox + 1.5, SEA + 14, oz + 22.5, 180f, 30f);
			shots(ctx, r, "15-harbour-as-left");
			server.runOnServer(s -> {
				var p = Game.player(s);
				p.getAbilities().flying = false;
				p.onUpdateAbilities();
				p.teleportTo(s.overworld(), ox - 2.5, SEA + 2, oz + 4.5, java.util.Set.of(), 180f, 8f, true);
			});
			ctx.waitFor(mc -> mc.player != null && mc.player.onGround(), 200);
			ctx.waitTicks(20);
			Game.hud(ctx, true);
			Shots.take(ctx, r, "16-where-the-player-starts");
			r.note("saved as \"%s\" in %s", WORLD_NAME, SlipwayClientGameTests.reportDir().resolve("saves"));
		} finally {
			ctx.runOnClient(mc -> WaterMask.enabled = true);
		}
	}
}
