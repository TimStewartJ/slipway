package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Entities on decks, reach, placement orientation and drops on vessels. */
public class InteractionGameTests {
	private static final String ARENA = "slipway:arena";

	/** A square plank deck of the given half size at height y with a helm (forward north) in the middle. */
	static BlockPos deck(GameTestHelper helper, int cx, int y, int cz, int half) {
		for (int x = cx - half; x <= cx + half; x++) {
			for (int z = cz - half; z <= cz + half; z++) {
				helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
			}
		}
		BlockPos helm = new BlockPos(cx, y + 1, cz);
		helper.setBlock(helm, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		return helm;
	}

	/** An armor stand: it falls and collides like any entity but never walks on its own (a NoAI mob does not even fall). */
	static ArmorStand pig(GameTestHelper helper, Vec3 at) {
		ArmorStand stand = net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
		stand.snapTo(at.x, at.y, at.z, 0f, 0f);
		helper.getLevel().addFreshEntity(stand);
		return stand;
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void entitiesLandOnADeckAndRideAlong(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 4, 11, 2);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		// Stand the pig beside the helm, one block east of it.
		Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(helm.east())).add(0, 2.0, 0);
		ArmorStand pig = pig(helper, start);
		double deckTop = helper.absolutePos(helm).getY();
		Vec3[] landedLocal = {null};
		helper.onEachTick(() -> {
			if (landedLocal[0] == null && vessel.hasBody && pig.onGround() && Math.abs(pig.getY() - deckTop) < 0.05) {
				landedLocal[0] = record.pose.worldToLocal(pig.position());
				long now = helper.getLevel().getGameTime();
				vessel.input.set(1f, 0f, 0f, 0f, 0f, 0f, now);
				vessel.scriptedInputUntil = now + 10;
			}
		});
		helper.runAfterDelay(120, () -> {
			check(helper, landedLocal[0] != null, "the pig never landed on the deck (y " + pig.getY() + ", deck top " + deckTop + ")");
			Vec3 localNow = record.pose.worldToLocal(pig.position());
			double drift = localNow.distanceTo(landedLocal[0]);
			double travelled = helper.absolutePos(helm).getZ() - record.pose.z();
			check(helper, travelled > 2.0, String.format(Locale.ROOT, "the vessel only moved %.2f", travelled));
			check(helper, drift < 0.6, String.format(Locale.ROOT, "the pig slid %.2f blocks on the deck while it moved %.2f", drift, travelled));
			check(helper, localNow.y > -0.05 && localNow.y < 0.1, "the pig is not on the deck: local y " + localNow.y);
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void theDeckCarriesEntitiesWhenItRises(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 3, 8, 2);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ArmorStand pig = pig(helper, Vec3.atBottomCenterOf(helper.absolutePos(helm.west())).add(0, 1.0, 0));
		boolean[] started = {false};
		helper.onEachTick(() -> {
			if (!started[0] && vessel.hasBody && pig.onGround()) {
				started[0] = true;
				long now = helper.getLevel().getGameTime();
				vessel.input.set(0f, 0f, 1f, 0f, 0f, 0f, now);
				vessel.scriptedInputUntil = now + 15;
			}
		});
		helper.runAfterDelay(100, () -> {
			check(helper, started[0], "the pig never stood on the deck");
			double risen = record.pose.y() - helper.absolutePos(helm).getY();
			double pigLocalY = record.pose.worldToLocal(pig.position()).y;
			check(helper, risen > 1.5, String.format(Locale.ROOT, "the vessel rose only %.2f", risen));
			check(helper, Math.abs(pigLocalY) < 0.1, String.format(Locale.ROOT, "the pig fell through or was left behind: local y %.2f after rising %.2f", pigLocalY, risen));
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void steepDecksShedEntities(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 7, 8, 3);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		record.level = false;
		VesselManager.get(helper.getLevel()).teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 0, 0, 60));
		ArmorStand[] pig = {null};
		// Drop it on the downhill half, clear of the helm (roll about Z puts local -X downhill).
		helper.runAfterDelay(5, () -> pig[0] = pig(helper, record.pose.localToWorld(new Vec3(-1.5, 3.0, 0.5))));
		helper.runAfterDelay(90, () -> {
			Vec3 local = record.pose.worldToLocal(pig[0].position());
			boolean onDeck = Math.abs(local.x) < 3.5 && Math.abs(local.z) < 3.5 && local.y > -0.2 && local.y < 0.5;
			check(helper, !onDeck, String.format(Locale.ROOT, "the pig is still resting on a 60-degree deck at local %.2f, %.2f, %.2f", local.x, local.y, local.z));
			check(helper, record.pose.tiltDegrees() > 50, "the deck levelled out");
			helper.succeed();
		});
	}

	/**
	 * The pilot keeps facing the same way relative to the vessel while it turns, and the turn of each tick is made
	 * within the pilot's own tick. An entity's tick begins by keeping its rotation as the old one, and the view of a
	 * frame is interpolated from the old rotation to the new one: only when the old one is the facing before the turn
	 * does the pilot's view turn in every frame, as the vessel is drawn. Up to 0.1.3 the pilot was turned when the
	 * vessel's pose arrived, before the pilot's tick; the old facing was then the new one already, and the view stood
	 * still between ticks and jumped at each of them.
	 */
	@GameTest(structure = ARENA, maxTicks = 200)
	public void thePilotTurnsWithTheVesselWithinTheTick(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 4, 8, 2);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerPlayer pilot = helper.makeMockServerPlayerInLevel();
		int ticksToMeasure = 50;
		VesselPose[] last = {null};
		float[] facingAtFirst = {0f};
		int[] ticks = {0};
		// The vessel's turn in all, its largest turn in a tick, and the furthest the pilot's turn within a tick was from the vessel's.
		double[] turned = {0.0, 0.0, 0.0};
		helper.onEachTick(() -> {
			if (ticks[0] >= ticksToMeasure) {
				return;
			}
			if (last[0] == null) {
				if (vessel.hasBody && vessel.entity != null && vessel.entity.pose() != null) {
					check(helper, pilot.startRiding(vessel.entity, true, true), "the mock player could not take the helm");
					long now = helper.getLevel().getGameTime();
					vessel.input.set(0f, 0f, 0f, 0f, 1f, 0f, now);
					vessel.scriptedInputUntil = now + ticksToMeasure + 20;
					last[0] = vessel.entity.pose();
					facingAtFirst[0] = pilot.getYRot();
				}
				return;
			}
			VesselPose pose = vessel.entity.pose();
			double turn = pose.yawTurnSinceDegrees(last[0]);
			last[0] = pose;
			double withinTheTick = Mth.wrapDegrees(pilot.yRotO - pilot.getYRot());
			turned[0] += turn;
			turned[1] = Math.max(turned[1], Math.abs(turn));
			turned[2] = Math.max(turned[2], Math.abs(Mth.wrapDegrees(withinTheTick - turn)));
			if (++ticks[0] == ticksToMeasure) {
				check(helper, pilot.getVehicle() == vessel.entity, "the pilot left the helm");
				check(helper, turned[1] > 2.0, String.format(Locale.ROOT, "the vessel turned at most %.3f degrees in a tick: too little to tell", turned[1]));
				double facing = Mth.wrapDegrees(facingAtFirst[0] - pilot.getYRot() - turned[0]);
				check(helper, Math.abs(facing) < 0.05,
					String.format(Locale.ROOT, "the vessel turned %.2f degrees and the pilot's facing is %.3f degrees off it", turned[0], facing));
				check(helper, turned[2] < 0.01, String.format(Locale.ROOT,
					"the pilot's facing at the start of a tick and at its end differ by up to %.3f degrees more or less than the vessel turned in that tick "
						+ "(it turned up to %.3f): the turn is not made within the pilot's tick, so a frame's view does not turn with the vessel", turned[2], turned[1]));
				helper.succeed();
			}
		});
		helper.runAfterDelay(190, () -> check(helper, false, "only " + ticks[0] + " of " + ticksToMeasure + " ticks with the pilot at the helm were measured in 190 ticks"));
	}

	@GameTest(structure = ARENA, maxTicks = 200)
	public void entitiesAboardMoveWithTheSnappedBlocks(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 4, 8, 2);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		// Park the vessel off the grid, turned 12 degrees and 0.4 blocks low: disassembly snaps it back and up.
		VesselPose turned = TestShips.poseAboutHelm(helper.absolutePos(helm), 12, 0, 0);
		VesselManager.get(helper.getLevel()).teleport(vessel, turned.withPosition(turned.x(), turned.y() - 0.4, turned.z()));
		ArmorStand[] stand = {null};
		helper.runAfterDelay(5, () -> stand[0] = pig(helper, record.pose.localToWorld(new Vec3(1.5, 0.5, 0.5))));
		helper.runAfterDelay(60, () -> {
			Vec3 localBefore = record.pose.worldToLocal(stand[0].position());
			check(helper, Math.abs(localBefore.y) < 0.05, "the stand did not settle on the deck: local y " + localBefore.y);
			// Float rounding in the pose can leave a rider a hair inside the deck, and vanilla collision ignores a floor a
			// box already overlaps: model the worst case.
			stand[0].setPos(stand[0].getX(), stand[0].getY() - 1.0e-5, stand[0].getZ());
			var outcome = VesselManager.get(helper.getLevel()).disassemble(record.id, null);
			check(helper, outcome.success(), "disassembly failed: " + outcome.message().getString());
			BlockPos below = BlockPos.containing(stand[0].getX(), stand[0].getY() - 0.5, stand[0].getZ());
			check(helper, helper.getLevel().getBlockState(below).is(Blocks.OAK_PLANKS), "the stand is not above a deck block: " + below.toShortString());
			check(helper, Math.abs(stand[0].getY() - (below.getY() + 1)) < 0.01,
				String.format(Locale.ROOT, "the stand is at y %.3f, not on the deck top %d", stand[0].getY(), below.getY() + 1));
		});
		helper.runAfterDelay(80, () -> {
			BlockPos below = BlockPos.containing(stand[0].getX(), stand[0].getY() - 0.5, stand[0].getZ());
			check(helper, helper.getLevel().getBlockState(below).is(Blocks.OAK_PLANKS) && Math.abs(stand[0].getY() - (below.getY() + 1)) < 0.01,
				String.format(Locale.ROOT, "the stand fell through the snapped deck: y %.3f", stand[0].getY()));
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void aVesselsLadderIsClimbable(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 4, 8, 2);
		for (int y = 0; y < 2; y++) {
			helper.setBlock(helm.east(2).above(y), Blocks.OAK_PLANKS);
			helper.setBlock(helm.east(1).above(y), Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.WEST));
		}
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		// Away from where it was built, turned and heeling: the ladder is nowhere near the blocks the world has there.
		BlockPos moved = helper.absolutePos(helm).above(6);
		VesselManager.get(helper.getLevel()).teleport(vessel, TestShips.poseAboutHelm(moved, 70, 0, 15));
		helper.runAfterDelay(2, () -> {
			ArmorStand onLadder = pig(helper, record.pose.localToWorld(new Vec3(1.5, 0.6, 0.5)));
			ArmorStand onDeck = pig(helper, record.pose.localToWorld(new Vec3(-1.5, 0.6, 0.5)));
			ArmorStand whereItWas = pig(helper, Vec3.atBottomCenterOf(helper.absolutePos(helm.east(1))).add(0, 0.6, 0));
			check(helper, onLadder.onClimbable(), "an entity in the ladder of a vessel is not on something it can climb");
			check(helper, onLadder.getLastClimbablePos().filter(pos -> helper.getLevel().getBlockState(pos).is(Blocks.LADDER)).isPresent(),
				"the block it climbs is not the vessel's ladder: " + onLadder.getLastClimbablePos());
			check(helper, !onDeck.onClimbable(), "an entity on the deck, away from the ladder, counts as climbing");
			check(helper, !whereItWas.onClimbable(), "an entity where the ladder stood before the vessel left counts as climbing");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void aFallIsCountedAgainstTheDeckAndAVesselNeverAddsToOne(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 4, 8, 2);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		VesselManager manager = VesselManager.get(helper.getLevel());
		BlockPos helmAbs = helper.absolutePos(helm);
		// One stands on the deck, one on nothing beside it, within the bounds that count as "at the vessel".
		ArmorStand onDeck = pig(helper, record.pose.localToWorld(new Vec3(1.5, 0.0, 0.5)));
		ArmorStand beside = pig(helper, record.pose.localToWorld(new Vec3(3.4, 0.0, 0.5)));
		onDeck.setNoGravity(true);
		beside.setNoGravity(true);
		dev.timstewart.slipway.vessel.DeckFall deckFall = new dev.timstewart.slipway.vessel.DeckFall();
		dev.timstewart.slipway.vessel.DeckFall besideFall = new dev.timstewart.slipway.vessel.DeckFall();
		check(helper, deckFall.against(onDeck, 0.0) == 0.0 && besideFall.against(beside, 0.0) == 0.0, "standing still at a vessel at rest is no fall");
		// The vessel goes down two blocks and the one on its deck with it: no fall. The one beside it stays (and is
		// still within three blocks of the vessel's bounds, which is what counts as "at the vessel"): no fall.
		manager.teleport(vessel, dev.timstewart.slipway.math.VesselPose.at(helmAbs.getX(), helmAbs.getY() - 2, helmAbs.getZ()));
		onDeck.setPos(onDeck.getX(), onDeck.getY() - 2.0, onDeck.getZ());
		double carriedDown = deckFall.against(onDeck, -2.0);
		check(helper, Math.abs(carriedDown) < 1.0e-6, "carried two blocks down by the deck counted as a move of " + carriedDown);
		check(helper, besideFall.against(beside, 0.0) == 0.0, "standing beside a vessel that went down counted as a move");
		// The vessel rises four blocks. Before 0.2.0's review this was a fall of four blocks for the one standing beside it.
		manager.teleport(vessel, dev.timstewart.slipway.math.VesselPose.at(helmAbs.getX(), helmAbs.getY() + 2, helmAbs.getZ()));
		double bystander = besideFall.against(beside, 0.0);
		check(helper, bystander == 0.0, "standing beside a vessel that rose four blocks counted as a move of " + bystander);
		// A real fall beside a vessel at rest is the fall it is.
		beside.setPos(beside.getX(), beside.getY() - 3.0, beside.getZ());
		double fell = besideFall.against(beside, -3.0);
		check(helper, Math.abs(fell + 3.0) < 1.0e-6, "a fall of three blocks beside a vessel at rest counted as " + fell);
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void reachIsMeasuredToWhereTheVesselIs(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 4, 8, 1);
		VesselRecord record = TestShips.assemble(helper, helm);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos plotDeck = record.anchor.offset(1, -1, 0);
		Vec3 near = Vec3.atBottomCenterOf(helper.absolutePos(helm.east(2))).add(0, 0, 0);
		player.snapTo(near.x, near.y, near.z, 0f, 0f);
		check(helper, player.isWithinBlockInteractionRange(plotDeck, 1.0), "a block two metres away on the vessel is out of reach");
		player.snapTo(near.x + 20, near.y, near.z, 0f, 0f);
		check(helper, !player.isWithinBlockInteractionRange(plotDeck, 1.0), "a vessel block 20 metres away is within reach");
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void blocksPlacedOnATurnedVesselFaceTheVesselFrame(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 4, 8, 2);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		// Turn the vessel a quarter turn: local north now points world west... local +Z points world east.
		VesselManager.get(helper.getLevel()).teleport(vessel, TestShips.poseAboutHelm(helper.absolutePos(helm), 90, 0, 0));
		helper.runAfterDelay(2, () -> {
			ServerPlayer player = helper.makeMockServerPlayerInLevel();
			Vec3 standAt = record.pose.localToWorld(new Vec3(1.5, 0, 1.5));
			// Look world west (yaw 90) and a little down: in the vessel's frame that is local north.
			player.snapTo(standAt.x, standAt.y, standAt.z, 90f, 30f);
			ItemStack stairs = new ItemStack(Items.OAK_STAIRS);
			player.setItemInHand(InteractionHand.MAIN_HAND, stairs);
			BlockPos plotDeck = record.anchor.offset(-1, -1, 1);
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(plotDeck).add(0, 0.5, 0), Direction.UP, plotDeck, false);
			var result = ((BlockItem)Items.OAK_STAIRS).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stairs, hit));
			check(helper, result.consumesAction(), "placing on the vessel failed: " + result);
			BlockState placed = helper.getLevel().getBlockState(plotDeck.above());
			check(helper, placed.is(Blocks.OAK_STAIRS), "no stair on the vessel, found " + placed);
			check(helper, placed.getValue(StairBlock.FACING) == Direction.NORTH, "stair faces " + placed.getValue(StairBlock.FACING) + " in the vessel frame, expected north");
			helper.succeed();
		});
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void brokenVesselBlocksDropWhereTheVesselIs(GameTestHelper helper) {
		BlockPos helm = deck(helper, 8, 5, 8, 1);
		VesselRecord record = TestShips.assemble(helper, helm);
		helper.runAfterDelay(2, () -> {
			BlockPos plotDeck = record.anchor.offset(1, -1, 1);
			helper.getLevel().destroyBlock(plotDeck, true);
			Vec3 world = record.pose.localToWorld(new Vec3(1.5, -0.5, 1.5));
			List<ItemEntity> nearVessel = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(world, world).inflate(2.0));
			List<ItemEntity> inPlot = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(Vec3.atCenterOf(plotDeck), Vec3.atCenterOf(plotDeck)).inflate(4.0));
			check(helper, !nearVessel.isEmpty(), "no drop near the vessel's world position " + world);
			check(helper, inPlot.isEmpty(), "the drop stayed in the vessel's plot");
			check(helper, nearVessel.getFirst().getItem().is(Items.OAK_PLANKS), "unexpected drop " + nearVessel.getFirst().getItem());
			nearVessel.forEach(ItemEntity::discard);
			helper.succeed();
		});
	}
}
