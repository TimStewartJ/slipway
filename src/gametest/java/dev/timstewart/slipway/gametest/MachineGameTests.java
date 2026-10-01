package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Redstone, a farm and small machines on a vessel that is under way: the blocks tick in the vessel's plot (scheduled
 * ticks, random ticks, block entities) wherever it flies, and what they throw out and the sounds they make appear
 * where the vessel is. (A test's arena is a closed box of barrier blocks, 16 blocks each way, and vessels collide with
 * it: "under way" here is rising, sinking and turning inside the box. Free flight is in the client GameTests.)
 */
public class MachineGameTests {
	private static final String ARENA = "slipway:arena";

	private static BlockState repeater(Direction input, int delay) {
		return Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, input).setValue(RepeaterBlock.DELAY, delay);
	}

	private static boolean settled(ActiveVessel vessel) {
		return vessel.hasBody && vessel.mass != null && !vessel.shapeDirty;
	}

	/** What a vessel did while a test ran: how far it went up and down, how fast, and how far it turned. */
	private static final class Motion {
		long since = -1;
		double lowest = Double.POSITIVE_INFINITY;
		double highest = Double.NEGATIVE_INFINITY;
		double fastest;
		double turned;
		VesselPose last;

		boolean started() {
			return this.since >= 0;
		}

		/** Rises for a second and a half, sinks for as long, and turns: movement that stays inside the arena. */
		void fly(GameTestHelper helper, ActiveVessel vessel, float vertical, float yaw) {
			long now = helper.getLevel().getGameTime();
			if (this.since < 0) {
				this.since = now;
				this.last = vessel.record.pose;
			}
			vessel.input.set(0f, 0f, (now - this.since) / 30 % 2 == 0 ? vertical : -vertical, 0f, yaw, 0f, now);
			vessel.scriptedInputUntil = now + 2;
			this.lowest = Math.min(this.lowest, vessel.record.pose.y());
			this.highest = Math.max(this.highest, vessel.record.pose.y());
			this.fastest = Math.max(this.fastest, vessel.record.linearVelocity.length());
			this.turned += Math.abs(vessel.record.pose.yawTurnSinceDegrees(this.last));
			this.last = vessel.record.pose;
		}

		void check(GameTestHelper helper, double range, double speed, double degrees) {
			TestShips.check(helper, this.highest - this.lowest >= range && this.fastest >= speed && this.turned >= degrees, String.format(Locale.ROOT,
				"the vessel was not under way: it went up and down over %.2f blocks, at up to %.2f blocks per second, and turned %.0f degrees",
				this.highest - this.lowest, this.fastest, this.turned));
		}
	}

	private static List<Integer> risingEdges(List<boolean[]> samples, int lamp) {
		List<Integer> edges = new ArrayList<>();
		for (int tick = 1; tick < samples.size(); tick++) {
			if (samples.get(tick)[lamp] && !samples.get(tick - 1)[lamp]) {
				edges.add(tick);
			}
		}
		return edges;
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void aRepeaterClockLightsARowOfLampsInTurnWhileTheVesselFlies(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 4);
		// The clock: two repeaters of four ticks side by side, joined by dust at both ends. Dust goes down first; every
		// neighbour placed after it makes it work out its connections.
		for (BlockPos dust : List.of(new BlockPos(-4, 0, -3), new BlockPos(-2, 0, -3), new BlockPos(-4, 0, -2), new BlockPos(-2, 0, -2))) {
			helper.setBlock(helm.offset(dust), Blocks.REDSTONE_WIRE);
		}
		helper.setBlock(helm.offset(-3, 0, -3), repeater(Direction.WEST, 4));
		helper.setBlock(helm.offset(-3, 0, -2), repeater(Direction.EAST, 4));
		// The row: repeater, lamp, repeater, lamp, repeater, lamp, each repeater one tick.
		BlockPos[] lamps = {new BlockPos(0, 0, -2), new BlockPos(2, 0, -2), new BlockPos(4, 0, -2)};
		for (BlockPos lamp : lamps) {
			helper.setBlock(helm.offset(lamp), Blocks.REDSTONE_LAMP);
			helper.setBlock(helm.offset(lamp.west()), repeater(Direction.WEST, 1));
		}
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos kick = record.anchor.offset(-4, 0, -4);
		record.level = false;
		VesselManager.get(level).teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 0, 0, 15));
		List<boolean[]> lit = new ArrayList<>();
		Motion motion = new Motion();
		boolean[] flying = new boolean[1];
		helper.onEachTick(() -> {
			if (flying[0]) {
				motion.fly(helper, vessel, 0.2f, 0.3f);
			}
		});
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			// Start the clock with a pulse as long as one repeater's delay.
			.thenExecute(() -> {
				flying[0] = true;
				level.setBlock(kick, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
			})
			.thenIdle(8)
			.thenExecute(() -> level.setBlock(kick, Blocks.AIR.defaultBlockState(), 3))
			.thenExecuteFor(120, () -> {
				boolean[] now = new boolean[lamps.length];
				for (int i = 0; i < lamps.length; i++) {
					now[i] = level.getBlockState(record.toPlot(lamps[i])).getValue(RedstoneLampBlock.LIT);
				}
				lit.add(now);
			})
			.thenExecute(() -> {
				List<List<Integer>> edges = List.of(risingEdges(lit, 0), risingEdges(lit, 1), risingEdges(lit, 2));
				String seen = "lamps came on at ticks " + edges;
				for (List<Integer> lamp : edges) {
					check(helper, lamp.size() >= 6, "a lamp came on fewer than six times in 120 ticks: " + seen);
					for (int i = 1; i < lamp.size(); i++) {
						// Two repeaters of four ticks (eight game ticks each): the clock's period.
						check(helper, lamp.get(i) - lamp.get(i - 1) == 16, "the clock lost or gained time: " + seen);
					}
				}
				for (int i = 0; i < 6; i++) {
					check(helper, edges.get(1).get(i) - edges.get(0).get(i) == 2 && edges.get(2).get(i) - edges.get(1).get(i) == 2,
						"the lamps did not come on one repeater tick after another: " + seen);
				}
				motion.check(helper, 1.5, 1.0, 60.0);
				check(helper, Math.abs(record.pose.tiltDegrees() - 15.0) < 6.0, "the vessel's roll changed to " + record.pose.tiltDegrees() + " degrees");
			})
			.thenSucceed();
	}

	/** Half the side of the field: 13 by 13 leaves a block of room to the arena's walls. */
	private static final int FIELD = 6;
	private static final BlockPos WATER = new BlockPos(0, -1, 1);
	private static final BlockPos DISPENSER = new BlockPos(1, 0, 0);
	private static final BlockPos FED = new BlockPos(2, 0, 0);

	private static boolean farmland(int x, int z) {
		return !(z == 0 && (x == 0 || x == 1)) && !(x == WATER.getX() && z == WATER.getZ());
	}

	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aFarmOnAFlyingVesselTakesBoneMealAndGrowsByItself(GameTestHelper helper) {
		BlockPos helm = new BlockPos(8, 5, 8);
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		for (int x = -FIELD; x <= FIELD; x++) {
			for (int z = -FIELD; z <= FIELD; z++) {
				blocks.put(helm.offset(x, -1, z), farmland(x, z) ? Blocks.FARMLAND.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
			}
		}
		// Water for the field the way it is carried on a vessel: inside a block. Farmland on all four sides keeps it in.
		blocks.put(helm.offset(WATER), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED, true));
		for (int x = -FIELD; x <= FIELD; x++) {
			for (int z = -FIELD; z <= FIELD; z++) {
				if (farmland(x, z)) {
					blocks.put(helm.offset(x, 0, z), Blocks.WHEAT.defaultBlockState());
				}
			}
		}
		blocks.put(helm, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		blocks.put(helm.offset(DISPENSER), Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
		TestShips.build(helper, blocks);
		helper.getBlockEntity(helm.offset(DISPENSER), DispenserBlockEntity.class).setItem(0, new ItemStack(Items.BONE_MEAL, 16));
		int crops = (2 * FIELD + 1) * (2 * FIELD + 1) - 3;

		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockPos dispenser = record.toPlot(DISPENSER);
		BlockPos fed = record.toPlot(FED);
		check(helper, record.blockCount == 2 * crops + 5, "the farm was assembled from " + record.blockCount + " blocks");
		record.level = false;
		VesselManager.get(level).teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 0, 8, 6));
		BlockEventGameTests.Spy viewer = BlockEventGameTests.spy(helper, Vec3.atCenterOf(helper.absolutePos(helm)).add(0, 2, 0));
		Motion motion = new Motion();
		boolean[] flying = new boolean[1];
		helper.onEachTick(() -> {
			if (flying[0]) {
				motion.fly(helper, vessel, 0.15f, 0f);
				// The viewer rides along (a mock player does not move by itself).
				Vec3 above = record.pose.localToWorld(new Vec3(0.5, 2.0, 0.5));
				viewer.player().snapTo(above.x, above.y, above.z, 0f, 0f);
			}
		});
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				flying[0] = true;
				vessel.viewers.add(viewer.player());
				vessel.viewerGraceUntil = level.getGameTime() + 2000;
			})
			.thenIdle(5)
			// One dose of bone meal from the dispenser.
			.thenExecute(() -> level.setBlock(dispenser.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3))
			.thenWaitUntil(() -> check(helper, level.getBlockState(fed).getValue(CropBlock.AGE) >= 2, "the wheat in front of the dispenser did not grow: "
				+ level.getBlockState(fed)))
			// Packets written during a tick go out at its end.
			.thenIdle(1)
			.thenExecute(() -> {
				level.setBlock(dispenser.above(), Blocks.AIR.defaultBlockState(), 3);
				check(helper, ((DispenserBlockEntity)level.getBlockEntity(dispenser)).getItem(0).getCount() == 15, "bone meal left in the dispenser: "
					+ ((DispenserBlockEntity)level.getBlockEntity(dispenser)).getItem(0));
				// What the player sees and hears of it is made by the client from these events, at the blocks' plot positions.
				check(helper, viewer.levelEventsAt(fed, 1505) == 1, "growth events for the wheat sent to the viewer: " + viewer.levelEventsAt(fed, 1505));
				check(helper, viewer.levelEventsAt(dispenser, 2000) == 1 && viewer.levelEventsAt(dispenser, 1000) == 1, "the dispenser's smoke and click were not sent to the viewer");
			})
			.thenIdle(1000)
			.thenExecute(() -> {
				int farmland = 0;
				int moist = 0;
				int grown = 0;
				int wheat = 0;
				for (int x = -FIELD; x <= FIELD; x++) {
					for (int z = -FIELD; z <= FIELD; z++) {
						if (!farmland(x, z)) {
							continue;
						}
						BlockState ground = level.getBlockState(record.anchor.offset(x, -1, z));
						BlockState crop = level.getBlockState(record.anchor.offset(x, 0, z));
						if (ground.is(Blocks.FARMLAND)) {
							farmland++;
							moist += ground.getValue(FarmlandBlock.MOISTURE) == 7 ? 1 : 0;
						}
						if (crop.is(Blocks.WHEAT)) {
							wheat++;
							grown += crop.getValue(CropBlock.AGE) > 0 && !(x == FED.getX() && z == FED.getZ()) ? 1 : 0;
						}
					}
				}
				String seen = String.format(Locale.ROOT, "%d of %d farmland, %d moist, %d of %d wheat, %d grown by itself", farmland, crops, moist, wheat, crops, grown);
				check(helper, farmland == crops && wheat == crops, "the field did not stay whole: " + seen);
				// Random ticks reach every block about once in 68 seconds: in 50 seconds half of the 78 fields within four
				// blocks of the water have been wetted (41, give or take 4), and ten to twenty crops have grown a stage.
				check(helper, moist >= 20, "the waterlogged block did not wet the farmland around it: " + seen);
				check(helper, grown >= 1, "no crop grew by itself in 1,000 ticks: " + seen);
				BlockState water = level.getBlockState(record.toPlot(WATER));
				check(helper, water.is(Blocks.OAK_SLAB) && water.getValue(SlabBlock.WATERLOGGED), "the waterlogged slab became " + water);
				for (BlockPos pos : BlockPos.betweenClosed(record.plotMin().offset(-2, -2, -2), record.plotMax().offset(2, 2, 2))) {
					check(helper, !level.getBlockState(pos).is(Blocks.WATER), "water ran out of the waterlogged slab to " + pos.toShortString());
				}
				motion.check(helper, 1.5, 1.0, 0.0);
				check(helper, record.pose.tiltDegrees() > 5.0, "the vessel levelled out");
				vessel.viewers.remove(viewer.player());
			})
			.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void machinesOnAFlyingVesselWorkAndTheirOutputAppearsAtTheVessel(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 3);
		BlockPos chest = new BlockPos(2, 0, 2);
		BlockPos observer = new BlockPos(-2, 0, -2);
		BlockPos dropper = new BlockPos(2, 0, -2);
		BlockPos note = new BlockPos(-2, 0, 2);
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		blocks.put(helm.offset(chest), Blocks.CHEST.defaultBlockState());
		blocks.put(helm.offset(chest.above()), Blocks.HOPPER.defaultBlockState());
		// The observer watches the cell north of it and powers the lamp behind it.
		blocks.put(helm.offset(observer), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.NORTH));
		blocks.put(helm.offset(observer.south()), Blocks.REDSTONE_LAMP.defaultBlockState());
		blocks.put(helm.offset(dropper), Blocks.DROPPER.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.UP));
		blocks.put(helm.offset(note), Blocks.NOTE_BLOCK.defaultBlockState());
		TestShips.build(helper, blocks);
		helper.getBlockEntity(helm.offset(chest.above()), HopperBlockEntity.class).setItem(0, new ItemStack(Items.IRON_INGOT, 5));
		helper.getBlockEntity(helm.offset(dropper), DispenserBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 1));

		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerLevel level = helper.getLevel();
		BlockEventGameTests.Spy viewer = BlockEventGameTests.spy(helper, Vec3.atCenterOf(helper.absolutePos(helm)).add(0, 2, 0));
		Motion motion = new Motion();
		boolean[] flying = new boolean[1];
		boolean[] lampLit = {false};
		Vec3[] thrownAt = new Vec3[1];
		helper.onEachTick(() -> {
			if (flying[0]) {
				motion.fly(helper, vessel, 0.1f, 0.2f);
				Vec3 above = record.pose.localToWorld(new Vec3(0.5, 2.0, 0.5));
				viewer.player().snapTo(above.x, above.y, above.z, 0f, 0f);
				lampLit[0] |= level.getBlockState(record.toPlot(observer.south())).getValue(RedstoneLampBlock.LIT);
			}
		});
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				flying[0] = true;
				vessel.viewers.add(viewer.player());
				vessel.viewerGraceUntil = level.getGameTime() + 1000;
			})
			.thenIdle(20)
			.thenExecute(() -> {
				check(helper, !lampLit[0], "the observer's lamp lit before anything changed in front of the observer");
				// Something changes in front of the observer; the dropper and the note block get a redstone signal.
				level.setBlock(record.toPlot(observer.north()), Blocks.STONE.defaultBlockState(), 3);
				level.setBlock(record.toPlot(dropper.east()), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
				level.setBlock(record.toPlot(note.west()), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
				thrownAt[0] = record.pose.localToWorld(new Vec3(dropper.getX() + 0.5, dropper.getY() + 1.2, dropper.getZ() + 0.5));
			})
			.thenIdle(8)
			.thenExecute(() -> {
				check(helper, lampLit[0], "the observer did not light its lamp");
				// The dropper threw its diamond out where the vessel is, not into the plot.
				check(helper, ((DispenserBlockEntity)level.getBlockEntity(record.toPlot(dropper))).getItem(0).isEmpty(), "the dropper kept its diamond");
				List<ItemEntity> near = level.getEntitiesOfClass(ItemEntity.class, new AABB(thrownAt[0], thrownAt[0]).inflate(4.0), e -> e.getItem().is(Items.DIAMOND));
				List<ItemEntity> inPlot = level.getEntitiesOfClass(ItemEntity.class, AABB.encapsulatingFullBlocks(record.plotMin(), record.plotMax()).inflate(8.0));
				check(helper, near.size() == 1, near.size() + " diamonds near the dropper's place in the world " + thrownAt[0]);
				check(helper, inPlot.isEmpty(), "the dropper's item stayed in the vessel's plot");
				near.forEach(ItemEntity::discard);
				// The note sounded at the vessel; the viewer was told to show the note at the plot position.
				Vec3 noteAt = record.pose.localToWorld(Vec3.atCenterOf(note));
				double off = viewer.received(ClientboundSoundPacket.class).stream().filter(packet -> packet.getSound().getRegisteredName().contains("note_block"))
					.mapToDouble(packet -> new Vec3(packet.getX(), packet.getY(), packet.getZ()).distanceTo(noteAt)).min().orElse(Double.NaN);
				check(helper, off < 1.5, String.format(Locale.ROOT, "the nearest note sound sent to the viewer was %.1f blocks from the note block's place in the world", off));
				check(helper, viewer.blockEventsAt(record.toPlot(note)) == 1, "the note block's event was not sent to the viewer");
			})
			// Five ingots through the hopper, one every eight ticks.
			.thenWaitUntil(() -> check(helper, ((ChestBlockEntity)level.getBlockEntity(record.toPlot(chest))).getItem(0).getCount() == 5,
				"the hopper has not passed its five ingots to the chest: " + ((ChestBlockEntity)level.getBlockEntity(record.toPlot(chest))).getItem(0)))
			.thenExecute(() -> {
				check(helper, ((HopperBlockEntity)level.getBlockEntity(record.toPlot(chest.above()))).getItem(0).isEmpty(), "the hopper kept ingots");
				motion.check(helper, 0.5, 0.5, 8.0);
				vessel.viewers.remove(viewer.player());
			})
			.thenSucceed();
	}
}