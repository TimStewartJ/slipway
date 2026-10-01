package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import com.mojang.authlib.GameProfile;
import dev.timstewart.slipway.physics.BlockDensity;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import dev.timstewart.slipway.vessel.VesselRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block events on vessels (a chest's lid, a piston's stroke): who is told about them, how long a chest stays open,
 * and what a piston's move does to the vessel's block count, bounds, mass and shape.
 */
public class BlockEventGameTests {
	private static final String ARENA = "slipway:arena";

	/** A player whose outgoing packets can be read. */
	record Spy(ServerPlayer player, EmbeddedChannel channel) {
		<T> List<T> received(Class<T> type) {
			List<T> out = new ArrayList<>();
			for (Object message : this.channel.outboundMessages()) {
				if (type.isInstance(message)) {
					out.add(type.cast(message));
				}
			}
			return out;
		}

		long blockEventsAt(BlockPos pos) {
			return this.received(ClientboundBlockEventPacket.class).stream().filter(packet -> packet.getPos().equals(pos)).count();
		}

		long levelEventsAt(BlockPos pos, int type) {
			return this.received(ClientboundLevelEventPacket.class).stream().filter(packet -> packet.getPos().equals(pos) && packet.getType() == type).count();
		}
	}

	/** As {@link GameTestHelper#makeMockServerPlayerInLevel()}, keeping the channel the server writes the player's packets to. */
	static Spy spy(GameTestHelper helper, Vec3 at) {
		ServerLevel level = helper.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "slipway-spy"), false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation()) {
			@Override
			public GameType gameMode() {
				return GameType.CREATIVE;
			}
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		player.snapTo(at.x, at.y, at.z, 0f, 0f);
		return new Spy(player, channel);
	}

	private static void steer(GameTestHelper helper, ActiveVessel vessel, float forward) {
		long now = helper.getLevel().getGameTime();
		vessel.input.set(forward, 0f, 0f, 0f, 0f, 0f, now);
		vessel.scriptedInputUntil = now + 2;
	}

	private static BlockState piston(boolean sticky, Direction facing) {
		return (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState().setValue(DirectionalBlock.FACING, facing);
	}

	private static boolean is(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
		return level.getBlockState(pos).is(block);
	}

	private static boolean settled(ActiveVessel vessel) {
		return vessel.hasBody && vessel.mass != null && !vessel.shapeDirty;
	}

	@GameTest(structure = ARENA, maxTicks = 60)
	public void blockAndLevelEventsOfAVesselBlockGoToItsViewersNearIt(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 2);
		helper.setBlock(helm.east(), Blocks.CHEST);
		BlockPos noteBlock = new BlockPos(1, 10, 1);
		helper.setBlock(noteBlock, Blocks.NOTE_BLOCK);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos chest = record.anchor.east();
		BlockPos worldNote = helper.absolutePos(noteBlock);
		Vec3 atVessel = Vec3.atCenterOf(helper.absolutePos(helm));
		Spy near = spy(helper, atVessel.add(3, 0, 0));
		Spy far = spy(helper, atVessel.add(0, 80, 0));
		Spy stranger = spy(helper, atVessel.add(-3, 0, 0));
		// Viewers are the players who have the vessel's plot chunks; mock players never track entities, so say so.
		vessel.viewers.add(near.player());
		vessel.viewers.add(far.player());
		vessel.viewerGraceUntil = level.getGameTime() + 100;
		level.blockEvent(chest, Blocks.CHEST, 1, 1);
		level.blockEvent(worldNote, Blocks.NOTE_BLOCK, 0, 0);
		level.levelEvent(null, 1505, chest, 15);
		check(helper, near.levelEventsAt(chest, 1505) == 1, "the viewer beside the vessel got " + near.levelEventsAt(chest, 1505) + " level events at the plot position");
		check(helper, far.levelEventsAt(chest, 1505) == 0, "a viewer 80 blocks from the vessel got the level event");
		check(helper, stranger.levelEventsAt(chest, 1505) == 0, "a player who does not view the vessel got its level event");
		BlockPos worldChest = BlockPos.containing(VesselManager.plotToWorld(record, Vec3.atCenterOf(chest)));
		check(helper, near.levelEventsAt(worldChest, 1505) == 0, "the level event was also sent for the world position");
		helper.runAfterDelay(3, () -> {
			check(helper, near.blockEventsAt(chest) == 1, "the viewer beside the vessel got " + near.blockEventsAt(chest) + " block events for the chest");
			check(helper, far.blockEventsAt(chest) == 0, "a viewer 80 blocks from the vessel got the chest's block event");
			check(helper, stranger.blockEventsAt(chest) == 0, "a player who does not view the vessel got the chest's block event");
			// Events of world blocks go out as vanilla sends them: to everyone within 64 blocks.
			check(helper, stranger.blockEventsAt(worldNote) == 1 && near.blockEventsAt(worldNote) == 1, "a world block's event did not reach the players near it");
			check(helper, far.blockEventsAt(worldNote) == 0, "a world block's event reached a player 77 blocks away");
			vessel.viewers.remove(near.player());
			vessel.viewers.remove(far.player());
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void aChestOnAVesselStaysOpenWhileItsUserIsAtTheVessel(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 2);
		helper.setBlock(helm.east(), Blocks.CHEST);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos chest = record.anchor.east();
		// A quarter turn away from how it was built, so "where the chest is" is not where it stood.
		VesselManager.get(level).teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 90, 0, 0));
		ServerPlayer[] user = new ServerPlayer[1];
		helper.startSequence()
			.thenIdle(2)
			.thenExecute(() -> {
				user[0] = helper.makeMockServerPlayerInLevel();
				Vec3 beside = record.pose.localToWorld(new Vec3(1.5, 0.0, 2.5));
				user[0].snapTo(beside.x, beside.y, beside.z, 0f, 0f);
				check(helper, level.getBlockEntity(chest) instanceof ChestBlockEntity, "no chest in the plot at " + chest.toShortString());
				user[0].openMenu((ChestBlockEntity)level.getBlockEntity(chest));
				check(helper, user[0].containerMenu instanceof ChestMenu, "the chest's menu did not open");
				check(helper, ChestBlockEntity.getOpenCount(level, chest) == 1, "open count after opening: " + ChestBlockEntity.getOpenCount(level, chest));
			})
			// The chest looks for its users every five ticks: three of those checks.
			.thenIdle(16)
			.thenExecute(() -> {
				check(helper, ChestBlockEntity.getOpenCount(level, chest) == 1, "the chest forgot the player beside it: open count " + ChestBlockEntity.getOpenCount(level, chest));
				Vec3 away = record.pose.localToWorld(new Vec3(1.5, 0.0, 24.5));
				user[0].snapTo(away.x, away.y, away.z, 0f, 0f);
			})
			.thenIdle(7)
			.thenExecute(() -> check(helper, ChestBlockEntity.getOpenCount(level, chest) == 0,
				"the chest still counts a player 22 blocks away: open count " + ChestBlockEntity.getOpenCount(level, chest)))
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void aPistonOnAVesselMovesItsBlockAndTheBooksFollow(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 7, 4, 8, 2);
		helper.setBlock(helm.east(2), piston(false, Direction.EAST));
		helper.setBlock(helm.east(3), Blocks.IRON_BLOCK);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos base = record.anchor.east(2);
		BlockPos head = record.anchor.east(3);
		BlockPos pushed = record.anchor.east(4);
		BlockPos power = base.south();
		double iron = BlockDensity.densityOf(Blocks.IRON_BLOCK.defaultBlockState());
		double redstone = BlockDensity.densityOf(Blocks.REDSTONE_BLOCK.defaultBlockState());
		double[] before = new double[2];
		Spy viewer = spy(helper, Vec3.atCenterOf(helper.absolutePos(helm)).add(0, 0, 3));
		long plotChunk = ChunkPos.pack(base.getX() >> 4, base.getZ() >> 4);
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				before[0] = vessel.mass.mass();
				before[1] = vessel.mass.comX();
				check(helper, record.blockCount == 28, "blocks before the stroke: " + record.blockCount);
				check(helper, record.localMax.getX() == 3, "bounds before the stroke reach x " + record.localMax.getX());
				vessel.viewers.add(viewer.player());
				vessel.viewerGraceUntil = level.getGameTime() + 200;
				level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
			})
			// In mid-stroke the head and the iron block are moving-piston blocks; the shape built from them weighs what the blocks weigh.
			.thenWaitUntil(() -> check(helper, is(level, pushed, Blocks.MOVING_PISTON) && is(level, head, Blocks.MOVING_PISTON) && settled(vessel),
				"no stroke under way with a rebuilt shape: " + level.getBlockState(head) + ", " + level.getBlockState(pushed)))
			.thenExecute(() -> {
				check(helper, record.blockCount == 30, "blocks in mid-stroke: " + record.blockCount);
				check(helper, record.localMax.getX() == 4, "bounds in mid-stroke reach x " + record.localMax.getX());
				double extra = vessel.mass.mass() - before[0] - redstone;
				check(helper, Math.abs(extra) < 720, String.format(Locale.ROOT, "mass in mid-stroke is off by %.0f kg (an iron block weighs %.0f)", extra, iron));
				// The client plays the stroke itself from the piston's block event. Sending it the chunk again because the
				// bounds grew would replace the chunk and delete the moving blocks it has just made.
				check(helper, viewer.blockEventsAt(base) == 1, "the viewer got " + viewer.blockEventsAt(base) + " block events for the stroke");
				check(helper, !viewer.player().connection.chunkSender.isPending(plotChunk), "growing the bounds sent the vessel's chunk to its viewer again");
				vessel.viewers.remove(viewer.player());
			})
			.thenWaitUntil(() -> check(helper, is(level, pushed, Blocks.IRON_BLOCK) && is(level, head, Blocks.PISTON_HEAD) && settled(vessel),
				"the stroke did not finish: " + level.getBlockState(head) + ", " + level.getBlockState(pushed)))
			.thenExecute(() -> {
				check(helper, level.getBlockState(base).getValue(PistonBaseBlock.EXTENDED), "the piston's base is not extended");
				check(helper, record.blockCount == 30, "blocks after the stroke: " + record.blockCount);
				check(helper, record.localMax.getX() == 4, "bounds after the stroke reach x " + record.localMax.getX());
				double extra = vessel.mass.mass() - before[0] - redstone;
				check(helper, Math.abs(extra) < 720, String.format(Locale.ROOT, "mass after the stroke is off by %.0f kg", extra));
				check(helper, VesselManager.get(level).active(record.id) == vessel && record.pose.position().distanceTo(Vec3.atLowerCornerOf(helper.absolutePos(helm))) < 0.5,
					"the vessel moved or went away during the stroke: " + record.pose);
				level.setBlock(power, Blocks.AIR.defaultBlockState(), 3);
			})
			// A plain piston leaves the block where it pushed it.
			.thenWaitUntil(() -> check(helper, is(level, base, Blocks.PISTON) && !level.getBlockState(base).getValue(PistonBaseBlock.EXTENDED) && settled(vessel),
				"the piston did not retract: " + level.getBlockState(base)))
			.thenExecute(() -> {
				check(helper, level.getBlockState(head).isAir() && is(level, pushed, Blocks.IRON_BLOCK), "after retracting: " + level.getBlockState(head) + ", "
					+ level.getBlockState(pushed));
				check(helper, record.blockCount == 28, "blocks after retracting: " + record.blockCount);
				check(helper, record.localMax.getX() == 4, "bounds after retracting reach x " + record.localMax.getX());
				check(helper, Math.abs(vessel.mass.mass() - before[0]) < 1.0, String.format(Locale.ROOT, "mass changed by %.1f kg over a full stroke",
					vessel.mass.mass() - before[0]));
				// The same blocks with the iron block one further out: the centre of mass moved by its share, and only by that.
				double expected = iron / before[0];
				double moved = vessel.mass.comX() - before[1];
				check(helper, Math.abs(moved - expected) < 0.01 * expected + 1.0e-4, String.format(Locale.ROOT, "centre of mass moved %.4f, expected %.4f", moved, expected));
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void aStickyPistonWithSlimeWorksWhileTheVesselFliesRolled(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 9, 2);
		helper.setBlock(helm.offset(1, 0, 1), piston(true, Direction.UP));
		helper.setBlock(helm.offset(1, 1, 1), Blocks.SLIME_BLOCK);
		helper.setBlock(helm.offset(1, 2, 1), Blocks.IRON_BLOCK);
		helper.setBlock(helm.offset(2, 1, 1), Blocks.STONE);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos base = record.anchor.offset(1, 0, 1);
		BlockPos power = record.anchor.offset(0, 0, 1);
		double redstone = BlockDensity.densityOf(Blocks.REDSTONE_BLOCK.defaultBlockState());
		double[] before = new double[4];
		int[] flown = {0};
		record.level = false;
		VesselManager.get(level).teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 0, 0, 25));
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				before[0] = vessel.mass.mass();
				before[1] = vessel.mass.comX();
				before[2] = vessel.mass.comY();
				before[3] = record.pose.z();
				check(helper, record.blockCount == 30, "blocks before the stroke: " + record.blockCount);
				check(helper, record.localMax.getY() == 2, "bounds before the stroke reach y " + record.localMax.getY());
			})
			// Under way, rolled 25 degrees: the stroke lifts the slime block, the iron block on it and the stone stuck to its side.
			.thenWaitUntil(() -> {
				steer(helper, vessel, 0.5f);
				if (++flown[0] == 20) {
					level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
				}
				check(helper, flown[0] > 20 && is(level, base.above(), Blocks.PISTON_HEAD) && is(level, base.above(2), Blocks.SLIME_BLOCK)
					&& is(level, base.above(3), Blocks.IRON_BLOCK) && is(level, base.offset(1, 2, 0), Blocks.STONE) && settled(vessel), "the stroke has not finished");
			})
			.thenExecute(() -> {
				check(helper, level.getBlockState(base.above()).getValue(DirectionalBlock.FACING) == Direction.UP, "the head faces " + level.getBlockState(base.above()));
				check(helper, level.getBlockState(base.offset(1, 1, 0)).isAir(), "the stone stuck to the slime left a copy behind");
				check(helper, record.blockCount == 32, "blocks with the piston out: " + record.blockCount);
				check(helper, record.localMax.getY() == 3, "bounds with the piston out reach y " + record.localMax.getY());
				double extra = vessel.mass.mass() - before[0] - redstone;
				check(helper, Math.abs(extra) < 720, String.format(Locale.ROOT, "mass with the piston out is off by %.0f kg", extra));
				check(helper, record.linearVelocity.length() > 0.5 && before[3] - record.pose.z() > 0.5, "the vessel is not under way: " + record.linearVelocity);
				check(helper, Math.abs(record.pose.tiltDegrees() - 25.0) < 6.0, "the vessel's roll changed to " + record.pose.tiltDegrees() + " degrees");
				level.setBlock(power, Blocks.AIR.defaultBlockState(), 3);
			})
			// A sticky piston takes everything back (the helm is let go: the vessel is still moving while it brakes).
			.thenWaitUntil(() -> check(helper, is(level, base, Blocks.STICKY_PISTON) && !level.getBlockState(base).getValue(PistonBaseBlock.EXTENDED)
				&& is(level, base.above(), Blocks.SLIME_BLOCK) && settled(vessel), "the piston has not pulled back"))
			.thenExecute(() -> {
				check(helper, is(level, base.above(2), Blocks.IRON_BLOCK) && is(level, base.offset(1, 1, 0), Blocks.STONE) && level.getBlockState(base.above(3)).isAir()
					&& level.getBlockState(base.offset(1, 2, 0)).isAir(), "the blocks did not come back with the slime");
				check(helper, record.blockCount == 30, "blocks after the full stroke: " + record.blockCount);
				check(helper, record.localMax.getY() == 3, "bounds after the full stroke reach y " + record.localMax.getY());
				check(helper, Math.abs(vessel.mass.mass() - before[0]) < 1.0 && Math.abs(vessel.mass.comX() - before[1]) < 1.0e-3
					&& Math.abs(vessel.mass.comY() - before[2]) < 1.0e-3, "mass or centre of mass differ after a full stroke");
				check(helper, Math.abs(record.pose.tiltDegrees() - 25.0) < 6.0, "the vessel's roll changed to " + record.pose.tiltDegrees() + " degrees");
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void disassemblingInMidStrokeFinishesTheStroke(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 7, 4, 8, 2);
		helper.setBlock(helm.east(2), piston(true, Direction.EAST));
		helper.setBlock(helm.east(3), Blocks.IRON_BLOCK);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos pushed = record.anchor.east(4);
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			// A quarter turn: the stroke ends pointing north in the world.
			.thenExecute(() -> VesselManager.get(level).teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 90, 0, 0)))
			.thenIdle(2)
			.thenExecute(() -> level.setBlock(record.anchor.offset(2, 0, 1), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3))
			.thenWaitUntil(() -> check(helper, is(level, pushed, Blocks.MOVING_PISTON), "no stroke under way"))
			.thenExecute(() -> {
				var outcome = VesselManager.get(level).disassemble(record.id, null);
				check(helper, outcome.success(), "disassembly failed: " + outcome.message().getString());
				// Local +x is world -z after a quarter turn to the left... read the world instead of trusting the sign.
				BlockPos helmNow = helper.absolutePos(helm);
				Direction out = null;
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					if (is(level, helmNow.relative(direction, 2), Blocks.STICKY_PISTON)) {
						out = direction;
					}
				}
				check(helper, out != null, "no piston two blocks from the helm after disassembly");
				BlockState base = level.getBlockState(helmNow.relative(out, 2));
				check(helper, base.getValue(DirectionalBlock.FACING) == out && base.getValue(PistonBaseBlock.EXTENDED), "the piston's base in the world: " + base);
				BlockState headState = level.getBlockState(helmNow.relative(out, 3));
				check(helper, headState.is(Blocks.PISTON_HEAD) && headState.getValue(DirectionalBlock.FACING) == out, "the piston's head in the world: " + headState);
				check(helper, is(level, helmNow.relative(out, 4), Blocks.IRON_BLOCK), "the pushed block in the world: " + level.getBlockState(helmNow.relative(out, 4)));
				for (BlockPos pos : BlockPos.betweenClosed(helmNow.offset(-6, -2, -6), helmNow.offset(6, 3, 6))) {
					check(helper, !is(level, pos, Blocks.MOVING_PISTON), "a moving-piston block was carried into the world at " + pos.toShortString());
				}
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void pistonsRefuseToPushOutOfThePlot(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// The last plot, which no test vessel ever gets. Its usable columns end at local x 2015; 2016 is the margin.
		BlockPos centre = VesselRegion.anchor(VesselRegion.MAX_PLOTS - 1, helper.absolutePos(BlockPos.ZERO).getY() + 8);
		BlockPos last = centre.offset(2015, 0, 0);
		check(helper, VesselRegion.isUsable(last) && !VesselRegion.isUsable(last.east()) && VesselRegion.isReserved(last.east()), "the plot's edge is not where the test expects it");
		level.setBlock(last, Blocks.STONE.defaultBlockState(), 2);
		level.setBlock(last.offset(-1, 0, 2), Blocks.STONE.defaultBlockState(), 2);
		try {
			check(helper, !new PistonStructureResolver(level, last.west(), Direction.EAST, true).resolve(), "a piston may push a block into the margin");
			check(helper, new PistonStructureResolver(level, last.offset(-2, 0, 2), Direction.EAST, true).resolve(), "a piston may not push a block to the last usable column");
			check(helper, !new PistonStructureResolver(level, last.offset(0, 0, 4), Direction.EAST, true).resolve(), "a piston may put its head into the margin");
			check(helper, new PistonStructureResolver(level, last.offset(-1, 0, 4), Direction.EAST, true).resolve(), "a piston may not put its head on the last usable column");
			check(helper, new PistonStructureResolver(level, last.offset(-2, 0, 0), Direction.EAST, false).resolve(), "a sticky piston may not pull a block inwards at the edge");
		} finally {
			level.setBlock(last, Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(last.offset(-1, 0, 2), Blocks.AIR.defaultBlockState(), 2);
		}

		// A block that gets into a vessel's margin all the same (a command) is not taken into the vessel.
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		BlockPos margin = record.anchor.offset(2016, 0, 0);
		int[] blocks = new int[1];
		BlockPos[] max = new BlockPos[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				blocks[0] = record.blockCount;
				max[0] = record.localMax;
				level.setBlock(margin, Blocks.STONE.defaultBlockState(), 2);
			})
			.thenIdle(5)
			.thenExecute(() -> {
				check(helper, record.localMax.equals(max[0]), "a block in the margin grew the vessel's bounds to " + record.localMax);
				check(helper, record.blockCount == blocks[0] && settled(vessel), "a block in the margin changed the vessel: " + record.blockCount + " blocks");
				check(helper, VesselManager.get(level).active(record.id) == vessel, "the vessel went away");
				level.setBlock(margin, Blocks.AIR.defaultBlockState(), 2);
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void columnsOfABlockSetFarOutsideAVesselReachItsViewersOnceLoaded(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		Spy viewer = spy(helper, Vec3.atCenterOf(helper.absolutePos(helm)).add(3, 0, 0));
		// Six columns out, on both sides, so that the middle of the bounds stays where it is: the vessel's entity sits
		// there, and with one block it would move fifty blocks, into the arena of another test (which removes every
		// entity in it when it succeeds) or, from the last arena of a row, into chunks that are not loaded (where the
		// vessel unloads). A cobweb has no collision box, so the vessel's body does not reach into the arenas next door.
		BlockPos far = record.anchor.offset(100, 0, 0);
		BlockPos farWest = record.anchor.offset(-100, 0, 0);
		Vec3 entityAt = vessel.entity.position();
		List<Long> before = new ArrayList<>();
		int[] deferred = new int[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel) && vessel.chunksReady, "the vessel is not ready yet"))
			.thenExecute(() -> {
				vessel.viewers.add(viewer.player());
				vessel.viewerGraceUntil = level.getGameTime() + 200;
				before.addAll(vessel.viewChunks);
				level.setBlock(far, Blocks.COBWEB.defaultBlockState(), 2);
				level.setBlock(farWest, Blocks.COBWEB.defaultBlockState(), 2);
				check(helper, record.localMax.getX() == 100 && record.localMin.getX() == -100, "the bounds did not grow to the blocks: " + record.localMin + ".." + record.localMax);
				deferred[0] = vessel.unsentChunks.size();
			})
			.thenWaitUntil(() -> check(helper, vessel.unsentChunks.isEmpty(), vessel.unsentChunks.size() + " new columns are not loaded yet"))
			.thenExecute(() -> {
				// The columns between the old bounds and the block were not loaded when the bounds grew.
				check(helper, deferred[0] > 0, "every new column was loaded at once: the test did not reach the waiting columns");
				int added = 0;
				for (long chunk : vessel.viewChunks) {
					if (!before.contains(chunk)) {
						added++;
						check(helper, hasChunk(viewer, chunk), "the viewer was not sent the new column " + ChunkPos.unpack(chunk));
					}
				}
				// Six more columns of bounds on either side, and the ring around them: four columns wide and one beyond each block.
				check(helper, added == 48, added + " columns were added");
				check(helper, hasChunk(viewer, ChunkPos.pack((far.getX() >> 4) + 1, far.getZ() >> 4)) && hasChunk(viewer, ChunkPos.pack((farWest.getX() >> 4) - 1, farWest.getZ() >> 4)),
					"a column beyond a block was not sent");
				check(helper, VesselManager.get(level).active(record.id) == vessel && vessel.entity.position().distanceTo(entityAt) < 1.0,
					"the vessel's entity moved " + vessel.entity.position().distanceTo(entityAt) + " blocks, or the vessel unloaded");
				vessel.viewers.remove(viewer.player());
				// Its bounds stay long; take it away so it does not lie across the arenas next door.
				VesselManager.get(level).remove(record.id);
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void aVesselDoesNotGrowPastTheLargestSpan(GameTestHelper helper) {
		// A three by three deck round its helm: x and z from -1 to 1.
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos a = record.anchor;
		List<BlockPos> placed = new ArrayList<>();
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			// The configuration is the server's, so it is changed for the length of these calls only (they act at once).
			.thenExecute(() -> TestShips.withConfig(config -> config.maxVesselSpan = 8, () -> {
				// Eight blocks from x -1 end at x 6.
				placed.add(a.offset(7, 0, 0));
				level.setBlock(a.offset(7, 0, 0), Blocks.COBWEB.defaultBlockState(), 2);
				check(helper, record.localMax.getX() == 1, "a block past the largest span grew the bounds to x " + record.localMax.getX());
				placed.add(a.offset(6, 0, 0));
				level.setBlock(a.offset(6, 0, 0), Blocks.COBWEB.defaultBlockState(), 2);
				check(helper, record.localMax.getX() == 6, "a block at the largest span was not taken in: x " + record.localMax.getX());
				placed.add(a.offset(-2, 0, 0));
				level.setBlock(a.offset(-2, 0, 0), Blocks.COBWEB.defaultBlockState(), 2);
				check(helper, record.localMin.getX() == -1, "a block on the other side grew the full bounds to x " + record.localMin.getX());
				placed.add(a.offset(0, 9, 0));
				level.setBlock(a.offset(0, 9, 0), Blocks.COBWEB.defaultBlockState(), 2);
				check(helper, record.localMax.getY() < 9, "a block past the largest span above grew the bounds to y " + record.localMax.getY());
				// Pistons: a block may be pushed to x 6 and not to x 7; a head may not go to x 7.
				placed.add(a.offset(6, 1, 0));
				level.setBlock(a.offset(6, 1, 0), Blocks.STONE.defaultBlockState(), 2);
				placed.add(a.offset(5, 1, 2));
				level.setBlock(a.offset(5, 1, 2), Blocks.STONE.defaultBlockState(), 2);
				check(helper, !new PistonStructureResolver(level, a.offset(5, 1, 0), Direction.EAST, true).resolve(), "a piston may push a block past the largest span");
				check(helper, new PistonStructureResolver(level, a.offset(4, 1, 2), Direction.EAST, true).resolve(), "a piston may not push a block to the edge of the largest span");
				check(helper, !new PistonStructureResolver(level, a.offset(6, 1, -1), Direction.EAST, true).resolve(), "a piston may put its head past the largest span");
				check(helper, new PistonStructureResolver(level, a.offset(5, 1, -1), Direction.EAST, true).resolve(), "a piston may not put its head at the edge of the largest span");
			}))
			.thenExecute(() -> {
				// With the configured size back (512) the same push is allowed.
				check(helper, new PistonStructureResolver(level, a.offset(5, 1, 0), Direction.EAST, true).resolve(), "the limit stayed after the configuration was put back");
				placed.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2));
			})
			.thenIdle(3)
			.thenExecute(() -> check(helper, settled(vessel) && VesselManager.get(level).active(record.id) == vessel, "the vessel went away"))
			.thenSucceed();
	}

	/**
	 * A piston at the end of a vessel that is as long as a vessel may be: powered, it does not move and nothing is
	 * lost; with a block of room it works. The largest span is the server's setting, so it is changed only for calls
	 * that act at once. A piston asks twice whether it can move: when its neighbour changes (and then queues its
	 * stroke as a block event), and when the server carries that event out a tick later, which the test does itself.
	 */
	@GameTest(structure = ARENA, maxTicks = 200)
	public void aPistonAtTheEdgeOfAVesselThatSpansTheLimitDoesNotExtend(GameTestHelper helper) {
		// A five by five deck (x from -2 to 2) with a piston that would push the iron block on its east edge outwards.
		BlockPos helm = InteractionGameTests.deck(helper, 7, 4, 8, 2);
		helper.setBlock(helm.east(1), piston(false, Direction.EAST));
		helper.setBlock(helm.east(2), Blocks.IRON_BLOCK);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos base = record.anchor.east(1);
		BlockPos block = record.anchor.east(2);
		BlockPos beyond = record.anchor.east(3);
		BlockPos power = base.south();
		int[] blocks = new int[1];
		double[] mass = new double[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				blocks[0] = record.blockCount;
				mass[0] = vessel.mass.mass();
				check(helper, record.localMin.getX() == -2 && record.localMax.getX() == 2, "the vessel is not five blocks long: " + record.localMin + ".." + record.localMax);
				TestShips.withConfig(config -> config.maxVesselSpan = 5, () -> {
					level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
					check(helper, !level.getBlockState(base).triggerEvent(level, base, 0, Direction.EAST.get3DDataValue()), "a stroke past the largest span was carried out");
					check(helper, level.getBlockState(base).equals(piston(false, Direction.EAST)) && is(level, block, Blocks.IRON_BLOCK) && level.getBlockState(beyond).isAir(),
						"the powered piston at the limit moved: " + level.getBlockState(base) + ", " + level.getBlockState(block) + ", " + level.getBlockState(beyond));
					level.setBlock(power, Blocks.AIR.defaultBlockState(), 3);
				});
			})
			.thenIdle(5)
			.thenExecute(() -> {
				check(helper, level.getBlockState(base).equals(piston(false, Direction.EAST)) && is(level, block, Blocks.IRON_BLOCK) && level.getBlockState(beyond).isAir(),
					"the refused push changed blocks: " + level.getBlockState(base) + ", " + level.getBlockState(block) + ", " + level.getBlockState(beyond));
				check(helper, record.localMax.getX() == 2, "the refused push grew the bounds to x " + record.localMax.getX());
				check(helper, settled(vessel) && record.blockCount == blocks[0] && Math.abs(vessel.mass.mass() - mass[0]) < 1.0,
					"the refused push changed the vessel: " + record.blockCount + " blocks (" + blocks[0] + " before), mass " + vessel.mass.mass() + " (" + mass[0] + ")");
				// With the configured size back (512) there is room, and the same piston pushes the block out.
				level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
			})
			.thenWaitUntil(() -> check(helper, is(level, beyond, Blocks.IRON_BLOCK) && is(level, block, Blocks.PISTON_HEAD) && settled(vessel),
				"with room the piston did not push: " + level.getBlockState(block) + ", " + level.getBlockState(beyond)))
			.thenExecute(() -> {
				check(helper, record.localMax.getX() == 3, "bounds after the push reach x " + record.localMax.getX());
				// The piston's head and the redstone block are the two blocks more.
				check(helper, record.blockCount == blocks[0] + 2, "blocks after the push: " + record.blockCount + " (" + blocks[0] + " before)");
			})
			.thenSucceed();
	}

	/** A player places the block item in their hand against the east face of a block. */
	private static InteractionResult placeAgainst(ServerPlayer player, ItemStack stack, BlockPos against) {
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(against).add(0.5, 0, 0), Direction.EAST, against, false);
		return ((BlockItem)stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
	}

	private static long refusals(Spy spy) {
		return spy.received(ClientboundSystemChatPacket.class).stream()
			.filter(packet -> packet.overlay() && packet.content().getContents() instanceof TranslatableContents told && told.getKey().equals("slipway.place.too_far")).count();
	}

	/**
	 * Block items are not placed where a vessel may not grow to. At the largest span a player's block is placed and
	 * taken in; one block further it fails, nothing is used up, and the player is told and sent the inventory again
	 * (the client has placed the block by itself). A dispenser's block fails the same way.
	 */
	@GameTest(structure = ARENA, maxTicks = 100)
	public void blockItemsAreNotPlacedWhereAVesselMayNotGrow(GameTestHelper helper) {
		// A three by three deck round its helm: x from -1 to 1. With a largest span of five blocks it may reach x 3.
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos a = record.anchor;
		Spy builder = spy(helper, Vec3.atCenterOf(helper.absolutePos(helm)).add(2, 0, 0));
		// Not in creative mode: a placed block must cost one.
		builder.player().getAbilities().instabuild = false;
		ItemStack stone = new ItemStack(Items.STONE, 10);
		builder.player().setItemInHand(InteractionHand.MAIN_HAND, stone);
		int[] inventoriesSent = new int[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> TestShips.withConfig(config -> config.maxVesselSpan = 5, () -> {
				inventoriesSent[0] = builder.received(ClientboundContainerSetContentPacket.class).size();
				check(helper, placeAgainst(builder.player(), stone, a.offset(1, -1, 0)).consumesAction() && is(level, a.offset(2, -1, 0), Blocks.STONE),
					"a block inside the largest span was not placed");
				check(helper, placeAgainst(builder.player(), stone, a.offset(2, -1, 0)).consumesAction() && is(level, a.offset(3, -1, 0), Blocks.STONE),
					"a block at the largest span was not placed");
				check(helper, record.localMax.getX() == 3 && stone.getCount() == 8, "after two blocks the bounds reach x " + record.localMax.getX() + " and " + stone.getCount() + " items are left");
				InteractionResult past = placeAgainst(builder.player(), stone, a.offset(3, -1, 0));
				check(helper, !past.consumesAction() && level.getBlockState(a.offset(4, -1, 0)).isAir(), "a block past the largest span was placed: " + past);
				check(helper, record.localMax.getX() == 3 && stone.getCount() == 8, "the refused block changed the bounds (x " + record.localMax.getX() + ") or was used up (" + stone.getCount() + " left)");
				// A dispenser places a shulker box through the same code, without a player.
				ItemStack box = new ItemStack(Items.SHULKER_BOX);
				InteractionResult dispensed = ((BlockItem)Items.SHULKER_BOX).place(new DirectionalPlaceContext(level, a.offset(4, 0, 0), Direction.EAST, box, Direction.WEST));
				check(helper, !dispensed.consumesAction() && box.getCount() == 1 && level.getBlockState(a.offset(4, 0, 0)).isAir(), "a dispenser placed a block past the largest span: " + dispensed);
				InteractionResult within = ((BlockItem)Items.SHULKER_BOX).place(new DirectionalPlaceContext(level, a.offset(3, 0, 0), Direction.EAST, box, Direction.WEST));
				check(helper, within.consumesAction() && is(level, a.offset(3, 0, 0), Blocks.SHULKER_BOX), "a dispenser could not place a block at the largest span: " + within);
			}))
			.thenIdle(2)
			.thenExecute(() -> {
				check(helper, refusals(builder) == 1, "the player was told " + refusals(builder) + " times that the vessel cannot grow");
				check(helper, builder.received(ClientboundContainerSetContentPacket.class).size() > inventoriesSent[0], "the refused player was not sent the inventory again");
				// With the configured size back (512) the same block is placed.
				check(helper, placeAgainst(builder.player(), stone, a.offset(3, -1, 0)).consumesAction() && is(level, a.offset(4, -1, 0), Blocks.STONE),
					"with the configured size back the block was not placed");
				check(helper, record.localMax.getX() == 4 && stone.getCount() == 7, "bounds reach x " + record.localMax.getX() + ", " + stone.getCount() + " items left");
			})
			.thenIdle(2)
			.thenExecute(() -> check(helper, refusals(builder) == 1 && settled(vessel), "a block that was placed was reported as refused"))
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void viewersHaveTheColumnsAroundAVesselToo(GameTestHelper helper) {
		// A three by three deck round its helm: the helm stands on a chunk corner of the plot, so four columns.
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		Vec3 atVessel = Vec3.atCenterOf(helper.absolutePos(helm));
		Spy viewer = spy(helper, atVessel.add(3, 0, 0));
		Spy stranger = spy(helper, atVessel.add(-3, 0, 0));
		int cx = record.anchor.getX() >> 4;
		int cz = record.anchor.getZ() >> 4;
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel) && vessel.chunksReady && vessel.unsentChunks.isEmpty(), "the vessel is not ready yet"))
			.thenExecute(() -> {
				vessel.viewers.add(viewer.player());
				vessel.viewerGraceUntil = level.getGameTime() + 100;
				check(helper, vessel.ticketChunks.size() == 4, vessel.ticketChunks.size() + " columns hold the vessel's blocks");
				check(helper, vessel.viewChunks.size() == 16, vessel.viewChunks.size() + " columns are shared with viewers");
				// A face is lit by the block next to it: the client needs the columns next to the vessel's own.
				for (int[] column : new int[][] {{-2, -2}, {-2, 0}, {1, -1}, {1, 1}, {-1, -1}, {0, 0}}) {
					check(helper, manager.isPlotChunkViewed(viewer.player(), cx + column[0], cz + column[1]), "the viewer does not have column " + column[0] + ", " + column[1]);
					check(helper, level.getChunkSource().getChunkNow(cx + column[0], cz + column[1]) != null, "column " + column[0] + ", " + column[1] + " is not loaded");
					check(helper, !manager.isPlotChunkViewed(stranger.player(), cx + column[0], cz + column[1]), "a player who does not view the vessel has its column");
				}
				check(helper, !manager.isPlotChunkViewed(viewer.player(), cx - 3, cz) && !manager.isPlotChunkViewed(viewer.player(), cx, cz + 2), "the viewer has columns two out");
				vessel.viewers.remove(viewer.player());
			})
			.thenSucceed();
	}

	/** Whether a chunk column was sent to the player or waits in its send queue (a mock player takes one batch only). */
	private static boolean hasChunk(Spy spy, long chunk) {
		return spy.player().connection.chunkSender.isPending(chunk) || spy.received(ClientboundLevelChunkWithLightPacket.class).stream()
			.anyMatch(packet -> ChunkPos.pack(packet.x(), packet.z()) == chunk);
	}
}