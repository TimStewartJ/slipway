package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.SlipwayDebug;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

/**
 * block-events: on a flying vessel the player opens a chest (the lid on the client swings open, stays open while the
 * screen is open and closes after it), flips a lever that drives a sticky piston (the client shows the block in
 * mid-stroke as a moving block, then has the same blocks as the server, draws the block where it now is and knows the
 * grown bounds; the same on the way back), plays a note block (the note particle and the sound are at the vessel) and
 * mines a block (the chips and the thud are at the vessel). Nothing the client shows or plays is left in the plot.
 */
final class BlockEventScenarios {
	private BlockEventScenarios() {
	}

	static final BlockPos CHEST = new BlockPos(-3, 0, 3);
	static final BlockPos PISTON = new BlockPos(3, 0, -2);
	static final BlockPos LEVER = new BlockPos(4, 0, -2);
	static final BlockPos NOTE = new BlockPos(-3, 0, -2);
	static final BlockPos ROCK = new BlockPos(0, 0, 4);

	static Map<BlockPos, BlockState> ship() {
		Map<BlockPos, BlockState> blocks = Ships.deck(5, Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		blocks.put(CHEST, Blocks.CHEST.defaultBlockState());
		blocks.put(PISTON, Blocks.STICKY_PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.UP));
		blocks.put(PISTON.above(), Blocks.IRON_BLOCK.defaultBlockState());
		blocks.put(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH));
		blocks.put(NOTE, Blocks.NOTE_BLOCK.defaultBlockState());
		blocks.put(ROCK, Blocks.STONE.defaultBlockState());
		return blocks;
	}

	/**
	 * What the client showed of one stroke: ticks with a moving block at the watched cell, its furthest progress, what
	 * the renderer drew, and the cell tick by tick (block, and the moving block's progress when there is one).
	 */
	record Stroke(int ticksMoving, float furthest, String drawn, String timeline) {
	}

	static float lid(Minecraft mc, BlockPos plotChest) {
		return mc.level.getBlockEntity(plotChest) instanceof ChestBlockEntity chest ? chest.getOpenNess(1f) : -1f;
	}

	/** Vertices of the vessel's mesh at the top face of a cell (vessel-local): none unless a block's top is there. */
	static int topVertices(ClientGameTestContext ctx, long id, BlockPos cell) {
		return ctx.computeOnClient(mc -> ClientVessels.get(id).mesh.vertexCountIn(cell.getX() - 0.01, cell.getY() + 0.9, cell.getZ() - 0.01,
			cell.getX() + 1.01, cell.getY() + 1.01, cell.getZ() + 1.01));
	}

	/** Flips the lever as a player and follows the stroke on the client tick by tick. */
	static Stroke flip(ClientGameTestContext ctx, TestServerContext server, Report.Result r, long id, BlockPos plotMoving, TickTimes ticks, String shot) {
		Flight.aimAtLocal(ctx, id, LEVER.getX() + 0.5, LEVER.getY() + 0.15, LEVER.getZ() + 0.5, LEVER);
		ctx.runOnClient(mc -> SlipwayDebug.blockEntitiesStart(id));
		ctx.getInput().pressKey(o -> o.keyUse);
		int moving = 0;
		float furthest = 0f;
		StringBuilder timeline = new StringBuilder();
		String last = "";
		for (int tick = 0; tick < 30; tick++) {
			ctx.waitTick();
			server.runOnServer(ticks::add);
			float progress = ctx.computeOnClient(mc -> mc.level.getBlockEntity(plotMoving) instanceof PistonMovingBlockEntity block ? block.getProgress(1f) : -1f);
			String onClient = ctx.computeOnClient(mc -> mc.level.getBlockState(plotMoving).getBlock().getName().getString());
			String onServer = server.computeOnServer(s -> s.overworld().getBlockState(plotMoving).getBlock().getName().getString());
			String now = String.format(java.util.Locale.ROOT, "client %s%s, server %s", onClient, progress >= 0f ? " " + progress : "", onServer);
			if (!now.equals(last)) {
				timeline.append(timeline.isEmpty() ? "" : "; ").append("tick ").append(tick).append(": ").append(now);
				last = now;
			}
			if (progress >= 0f) {
				// A screenshot takes a few ticks: a stroke that is photographed is not also timed.
				if (moving++ == 0 && shot != null) {
					Shots.take(ctx, r, shot);
				}
				furthest = Math.max(furthest, progress);
			}
		}
		return new Stroke(moving, furthest, ctx.computeOnClient(mc -> SlipwayDebug.blockEntitiesStop()), timeline.toString());
	}

	/** The piston's column on both sides: the same states, none of them in mid-move. */
	static void checkColumn(ClientGameTestContext ctx, TestServerContext server, VesselRecord record, String when, BlockState... expected) {
		for (int y = 0; y < expected.length; y++) {
			BlockPos plot = record.toPlot(PISTON.above(y));
			BlockState onServer = server.computeOnServer(s -> s.overworld().getBlockState(plot));
			BlockState onClient = ctx.computeOnClient(mc -> mc.level.getBlockState(plot));
			Check.equal(when + ": server block " + y + " above the piston's base", onServer, expected[y]);
			Check.equal(when + ": client block " + y + " above the piston's base", onClient, expected[y]);
		}
	}

	static void blockEvents(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 20, 40);
			server.runOnServer(s -> Ships.build(s.overworld(), helm, ship()));
			VesselRecord record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
			long id = record.id;
			Game.waitClientReady(ctx, id, 100);
			DeckScenarios.placeRider(ctx, server, id, new Vec3(-1.5, 0.3, 2.5));
			Game.waitClientComplete(ctx, id, 400);

			// Everything below happens under way: a slow turn to port at a tenth of full thrust.
			Flight.Sample start = Flight.sample(server, id);
			server.runCommand("slipway control " + id + " 0.1 0 0 0 0.05 0 6000");
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() > 0.3, 200);

			// The chest.
			BlockPos plotChest = record.toPlot(CHEST);
			Check.near("lid of the closed chest on the client", ctx.computeOnClient(mc -> lid(mc, plotChest)), 0.0, 1.0e-6);
			InteractionScenarios.useOn(ctx, id, CHEST, null);
			ctx.waitForScreen(ContainerScreen.class);
			int opened = -1;
			float lid = 0f;
			for (int tick = 1; tick <= 40 && opened < 0; tick++) {
				ctx.waitTick();
				lid = ctx.computeOnClient(mc -> lid(mc, plotChest));
				if (lid >= 0.999f) {
					opened = tick;
				}
			}
			r.metric("chest.ticksToOpen", opened);
			Check.that(opened > 0, "the chest's lid on the client opened only to %.2f in 40 ticks", lid);
			// The chest looks for the players who have it open every five ticks: the lid must stay up through four of those.
			float lowest = 1f;
			for (int tick = 0; tick < 22; tick++) {
				ctx.waitTick();
				lowest = Math.min(lowest, ctx.computeOnClient(mc -> lid(mc, plotChest)));
			}
			r.metric("chest.lowestLidWhileOpen", lowest);
			Check.atLeast("the chest's lid while its screen is open", lowest, 0.999);
			Check.equal("players the chest counts as having it open", server.computeOnServer(s -> ChestBlockEntity.getOpenCount(s.overworld(), plotChest)), 1);
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitForScreen(null);
			ctx.waitTicks(4);
			r.metric("chest.lidInTheShot", ctx.computeOnClient(mc -> lid(mc, plotChest)));
			Shots.take(ctx, r, "01-chest-lid-closing");
			ctx.waitFor(mc -> lid(mc, plotChest) == 0f, 40);
			Check.equal("players the chest counts after its screen was closed", server.computeOnServer(s -> ChestBlockEntity.getOpenCount(s.overworld(), plotChest)), 0);

			// The piston: out.
			BlockState base = Blocks.STICKY_PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.UP);
			BlockState head = Blocks.PISTON_HEAD.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.UP)
				.setValue(net.minecraft.world.level.block.piston.PistonHeadBlock.TYPE, net.minecraft.world.level.block.state.properties.PistonType.STICKY);
			BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
			BlockState air = Blocks.AIR.defaultBlockState();
			DeckScenarios.placeRider(ctx, server, id, new Vec3(4.5, 0.3, 2.5));
			checkColumn(ctx, server, record, "before the stroke", base, iron, air);
			Check.equal("top of the vessel's bounds on the client before the stroke", ctx.computeOnClient(mc -> ClientVessels.get(id).localMax.getY()), 1);
			Check.equal("mesh vertices where the block will be pushed to", topVertices(ctx, id, PISTON.above(2)), 0);
			TickTimes ticks = new TickTimes();
			Stroke out = flip(ctx, server, r, id, record.toPlot(PISTON.above(2)), ticks, null);
			r.metric("push.clientTicksWithMovingBlock", out.ticksMoving());
			r.metric("push.furthestProgressSeen", out.furthest());
			r.note("drawn during the push: %s", out.drawn().replace("\n", "; "));
			r.note("the cell pushed into: %s", out.timeline());
			// A stroke is two ticks of movement (half a block each) before the block is put down.
			Check.that(out.ticksMoving() >= 2 && out.furthest() >= 0.5f, "the client did not play the stroke at the cell the piston pushes into: %s", out.timeline());
			Check.that(out.drawn().contains("minecraft:piston@" + PISTON.above(2).toShortString()) && out.drawn().contains("minecraft:piston@" + PISTON.above().toShortString()),
				"the renderer did not draw the moving block and the moving head: %s", out.drawn());
			Game.waitClientComplete(ctx, id, 200);
			checkColumn(ctx, server, record, "after the push", base.setValue(PistonBaseBlock.EXTENDED, true), head, iron, air);
			Check.equal("top of the vessel's bounds on the client after the push", ctx.computeOnClient(mc -> ClientVessels.get(id).localMax.getY()), 2);
			Check.equal("top of the vessel's bounds on the server after the push", server.computeOnServer(s -> Game.active(s, id).record.localMax.getY()), 2);
			int pushed = topVertices(ctx, id, PISTON.above(2));
			r.metric("push.meshVerticesAtThePushedBlock", pushed);
			Check.that(pushed >= 4, "the client's mesh does not show the pushed block: %d vertices at its top", pushed);
			Shots.take(ctx, r, "02-piston-out");

			// And back: a sticky piston takes its block along.
			Stroke back = flip(ctx, server, r, id, record.toPlot(PISTON.above()), ticks, "03-piston-pulling");
			r.metric("pull.clientTicksWithMovingBlock", back.ticksMoving());
			r.note("drawn during the pull: %s", back.drawn().replace("\n", "; "));
			r.note("the cell pulled into: %s", back.timeline());
			Check.that(back.ticksMoving() >= 1, "the client never had a moving block at the cell the piston pulls into: the stroke was not animated");
			Check.that(back.drawn().contains("minecraft:piston@" + PISTON.above().toShortString()), "the renderer did not draw the block being pulled back: %s", back.drawn());
			Game.waitClientComplete(ctx, id, 200);
			checkColumn(ctx, server, record, "after the pull", base, iron, air);
			Check.equal("mesh vertices where the block was pushed to, after the pull", topVertices(ctx, id, PISTON.above(2)), 0);
			Check.that(topVertices(ctx, id, PISTON.above()) >= 4, "the client's mesh does not show the block back on the piston");
			ticks.check(r, "strokes");
			Shots.take(ctx, r, "04-piston-back");

			// A note block: the note above it and its sound.
			DeckScenarios.placeRider(ctx, server, id, new Vec3(-2.5, 0.3, 0.5));
			ctx.runOnClient(mc -> Effects.start(id));
			InteractionScenarios.useOn(ctx, id, NOTE, null);
			ctx.waitTicks(8);
			List<Effects.Seen> noteParticles = ctx.computeOnClient(mc -> Effects.particles());
			List<Effects.Seen> noteSounds = ctx.computeOnClient(mc -> Effects.sounds());
			Vec3 aboveNote = new Vec3(NOTE.getX() + 0.5, NOTE.getY() + 1.2, NOTE.getZ() + 0.5);
			r.note("note block: particles %s; sounds %s", noteParticles, noteSounds);
			Check.that(noteParticles.stream().anyMatch(p -> p.what().equals("NoteParticle") && p.local().distanceTo(aboveNote) < 0.3),
				"no note particle above the note block on the vessel: %s", noteParticles);
			Check.that(noteSounds.stream().anyMatch(s -> s.what().startsWith("minecraft:block.note_block.") && s.local().distanceTo(Vec3.atCenterOf(NOTE)) < 0.75),
				"the note was not played at the note block on the vessel: %s", noteSounds);

			// Mining: the chips and the thud.
			server.runCommand("gamemode survival @a");
			ctx.waitFor(mc -> !mc.player.getAbilities().instabuild, 20);
			DeckScenarios.placeRider(ctx, server, id, new Vec3(0.5, 0.3, 2.0));
			Flight.aimAtLocal(ctx, id, ROCK.getX() + 0.5, ROCK.getY() + 0.5, ROCK.getZ() + 0.5, ROCK);
			ctx.runOnClient(mc -> Effects.start(id));
			ctx.getInput().holdKey(o -> o.keyAttack);
			try {
				ctx.waitTicks(14);
			} finally {
				ctx.getInput().releaseKey(o -> o.keyAttack);
			}
			List<Effects.Seen> chips = ctx.computeOnClient(mc -> Effects.particles());
			List<Effects.Seen> thuds = ctx.computeOnClient(mc -> Effects.sounds());
			ctx.runOnClient(mc -> Effects.stop());
			server.runCommand("gamemode creative @a");
			ctx.waitFor(mc -> mc.player.getAbilities().instabuild, 20);
			r.metric("mining.particles", chips.size());
			r.metric("mining.sounds", thuds.size());
			r.note("mining: particles %s; sounds %s", chips.stream().limit(6).toList(), thuds);
			Check.that(chips.stream().anyMatch(p -> p.what().equals("TerrainParticle") && p.local().distanceTo(Vec3.atCenterOf(ROCK)) < 1.0),
				"no chips at the block being mined on the vessel: %s", chips);
			Check.that(thuds.stream().anyMatch(s -> s.what().equals("minecraft:block.stone.hit") && s.local().distanceTo(Vec3.atCenterOf(ROCK)) < 0.3),
				"the thud of mining was not played at the block on the vessel: %s", thuds);
			List<Effects.Seen> all = new ArrayList<>(noteParticles);
			all.addAll(noteSounds);
			all.addAll(chips);
			all.addAll(thuds);
			Check.that(all.stream().noneMatch(Effects.Seen::inPlot), "particles or sounds were left in the vessel's plot: %s", all.stream().filter(Effects.Seen::inPlot).toList());

			Flight.Sample end = Flight.sample(server, id);
			r.metric("flight.distance", Flight.horizontalDistance(start, end));
			r.metric("flight.speed", end.velocity().length());
			r.metric("flight.yawDegrees", Math.abs(Flight.yawChange(start, end)));
			Check.atLeast("distance the vessel flew during the scenario", Flight.horizontalDistance(start, end), 8.0);
			Check.atLeast("vessel speed at the end", end.velocity().length(), 0.3);
			Check.equal("the player is still carried by the vessel", Flight.rider(ctx, id).carrier(), id);
		}
	}
}