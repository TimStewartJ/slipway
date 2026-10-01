package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselEntity;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import dev.timstewart.slipway.vessel.VesselRegion;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * A vessel's entity: where it is decides whether the vessel is loaded and which players are shown it, so nothing
 * but the vessel itself may move or remove it.
 */
public class EntityGameTests {
	private static final String ARENA = "slipway:arena";
	/**
	 * How far from its helm a test lets the middle of a vessel's bounds move (the bounds grow to twice that; 512 is the
	 * most they may span). The arenas of a run lie east and south of the first one, eight to a row and at most 184
	 * blocks across, with their own chunks and two more round them loaded: this far west or north of any helm nothing
	 * is loaded unless a test loads it.
	 */
	private static final int AWAY = 240;

	/**
	 * A block to set in a vessel's plot so that the middle of its bounds (a three by three deck round its helm) moves
	 * {@link #AWAY} blocks in a direction, to the middle of a chunk.
	 */
	private record Growth(BlockPos farLocal, int chunkX, int chunkZ, BlockPos there) {
		static Growth towards(BlockPos helmAbs, Direction direction) {
			boolean alongX = direction.getAxis() == Direction.Axis.X;
			int helm = alongX ? helmAbs.getX() : helmAbs.getZ();
			int chunk = (helm + direction.getAxisDirection().getStep() * AWAY) >> 4;
			int middle = chunk * 16 + 8 - helm;
			// Bounds from -1 to far have their middle at far / 2, bounds from far to 1 at far / 2 + 1.
			int far = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 2 * middle : 2 * (middle - 1);
			return alongX ? new Growth(new BlockPos(far, 0, 0), chunk, helmAbs.getZ() >> 4, new BlockPos(helmAbs.getX() + middle, helmAbs.getY(), helmAbs.getZ()))
				: new Growth(new BlockPos(0, 0, far), helmAbs.getX() >> 4, chunk, new BlockPos(helmAbs.getX(), helmAbs.getY(), helmAbs.getZ() + middle));
		}

		/** Whether neither the chunk the middle moves into nor one next to it is loaded. */
		boolean nothingLoaded(ServerLevel level) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (level.getChunkSource().getChunkNow(this.chunkX + dx, this.chunkZ + dz) != null) {
						return false;
					}
				}
			}
			return true;
		}

		boolean reached(Vec3 middle) {
			return SectionPos.blockToSectionCoord(middle.x) == this.chunkX && SectionPos.blockToSectionCoord(middle.z) == this.chunkZ;
		}
	}

	/**
	 * A block set far out in the plot by a command grows the bounds, and their middle, where the entity sits, moves
	 * half that distance at once. Where nothing is loaded there the entity stays at its place on the vessel: the vessel
	 * went with it before, unloaded under the players on it. It stays there when it is unloaded and loaded again, and
	 * goes to the middle once entities tick there.
	 */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void growingBoundsDoNotTakeAVesselWhereNothingIsLoaded(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel[] vessel = {TestShips.active(helper, record)};
		VesselEntity entity = vessel[0].entity;
		BlockPos helmAbs = helper.absolutePos(helm);
		Growth[] growth = new Growth[1];
		Vec3[] before = new Vec3[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.settled(vessel[0]) && vessel[0].chunksReady, "the vessel is not ready yet"))
			.thenExecute(() -> {
				// West of the arenas nothing is loaded; the other ways are tried so that the test does not depend on that.
				for (Direction direction : new Direction[] {Direction.WEST, Direction.EAST, Direction.SOUTH, Direction.NORTH}) {
					if (growth[0] == null && Growth.towards(helmAbs, direction).nothingLoaded(level)) {
						growth[0] = Growth.towards(helmAbs, direction);
					}
				}
				check(helper, growth[0] != null, AWAY + " blocks from the helm chunks are loaded on every side: the test proves nothing here");
				before[0] = entity.position();
				check(helper, before[0].distanceTo(VesselManager.worldCentre(record)) < 1.0e-6 && !vessel[0].entityHeld, "the entity does not start at the middle of the bounds");
				level.setBlock(record.anchor.offset(growth[0].farLocal()), Blocks.COBWEB.defaultBlockState(), 2);
				Vec3 middle = VesselManager.worldCentre(record);
				check(helper, growth[0].reached(middle) && middle.distanceTo(before[0]) > 200,
					"the bounds did not grow as planned: " + record.localMin + " to " + record.localMax + ", middle " + middle);
			})
			.thenIdle(5)
			.thenExecute(() -> {
				check(helper, manager.active(record.id) == vessel[0] && vessel[0].hasBody && !vessel[0].ticketChunks.isEmpty(), "the vessel unloaded when its bounds grew");
				check(helper, level.getEntity(entity.getUUID()) == entity && entity.position().distanceTo(before[0]) < 0.01,
					"the entity moved " + entity.position().distanceTo(before[0]) + " blocks, to where nothing is loaded");
				check(helper, vessel[0].entityHeld, "viewers would not be told that the entity is not at the middle of the bounds");
				// Unloaded and loaded again, as when the last player near it leaves and one comes back: the entity comes
				// back where it was saved. (The same entity is handed to the manager here; a loaded one is a new object
				// with the saved position.)
				manager.onEntityUnloaded(entity);
				check(helper, manager.active(record.id) == null, "the vessel did not unload with its entity");
				manager.tickEntity(entity);
				vessel[0] = manager.active(record.id);
				check(helper, vessel[0] != null && vessel[0].entity == entity, "the vessel did not load with its entity");
			})
			.thenIdle(5)
			.thenExecute(() -> {
				check(helper, manager.active(record.id) == vessel[0] && level.getEntity(entity.getUUID()) == entity && entity.position().distanceTo(before[0]) < 0.01
					&& vessel[0].entityHeld, "loaded again, the vessel went to the middle of its bounds: the entity is " + entity.position().distanceTo(before[0]) + " blocks from where it was");
				TestShips.loadNow(level, growth[0].chunkX(), growth[0].chunkZ(), 2);
			})
			.thenWaitUntil(() -> check(helper, level.isPositionEntityTicking(growth[0].there()), "entities do not tick at the middle of the bounds yet"))
			.thenIdle(2)
			.thenExecute(() -> {
				Vec3 middle = VesselManager.worldCentre(record);
				check(helper, manager.active(record.id) == vessel[0] && level.getEntity(entity.getUUID()) == entity && entity.position().distanceTo(middle) < 1.0e-6
					&& !vessel[0].entityHeld, "the entity did not go to the middle of the bounds once entities tick there: it is " + entity.position().distanceTo(middle) + " blocks from it");
				// Its bounds stay long; take it away.
				manager.remove(record.id);
			})
			.thenSucceed();
	}

	/**
	 * Where entities do tick at the new middle, a player who has the vessel and would not track its entity there
	 * (vanilla shows an entity to players within its tracking range, at most their view distance) keeps the entity
	 * where it is: it went before, and the player lost the vessel under their feet. When that player has gone it goes.
	 */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void growingBoundsDoNotTakeAVesselFromItsViewers(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		VesselEntity entity = vessel.entity;
		BlockPos helmAbs = helper.absolutePos(helm);
		BlockEventGameTests.Spy viewer = BlockEventGameTests.spy(helper, Vec3.atCenterOf(helmAbs).add(3, 0, 0));
		// North of every arena (the test above goes west), in a chunk this test makes entities tick in.
		Growth growth = Growth.towards(helmAbs, Direction.NORTH);
		TestShips.loadNow(level, growth.chunkX(), growth.chunkZ(), 2);
		Vec3[] before = new Vec3[1];
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.settled(vessel) && vessel.chunksReady, "the vessel is not ready yet"))
			.thenWaitUntil(() -> check(helper, level.isPositionEntityTicking(growth.there()), "entities do not tick where the middle of the bounds is to move yet"))
			.thenExecute(() -> {
				// Mock players never track entities, so say that this one has the vessel (see BlockEventGameTests).
				vessel.viewers.add(viewer.player());
				vessel.viewerGraceUntil = level.getGameTime() + 1000;
				before[0] = entity.position();
				level.setBlock(record.anchor.offset(growth.farLocal()), Blocks.COBWEB.defaultBlockState(), 2);
				Vec3 middle = VesselManager.worldCentre(record);
				check(helper, growth.reached(middle) && middle.distanceTo(before[0]) > 200,
					"the bounds did not grow as planned: " + record.localMin + " to " + record.localMax + ", middle " + middle);
			})
			.thenIdle(5)
			.thenExecute(() -> {
				check(helper, manager.active(record.id) == vessel && entity.position().distanceTo(before[0]) < 0.01 && vessel.entityHeld,
					"the entity went " + entity.position().distanceTo(before[0]) + " blocks from a player who has the vessel");
				long forgotten = viewer.received(ClientboundForgetLevelChunkPacket.class).stream().filter(packet -> VesselRegion.isReservedChunk(packet.pos().x(), packet.pos().z())).count();
				check(helper, vessel.viewers.contains(viewer.player()) && forgotten == 0, "the player was told to forget " + forgotten + " columns of the vessel");
				vessel.viewers.remove(viewer.player());
			})
			.thenIdle(3)
			.thenExecute(() -> {
				Vec3 middle = VesselManager.worldCentre(record);
				check(helper, manager.active(record.id) == vessel && entity.position().distanceTo(middle) < 1.0e-6 && !vessel.entityHeld,
					"with nobody left to lose it, the entity did not go to the middle of the bounds: it is " + entity.position().distanceTo(middle) + " blocks from it");
				manager.remove(record.id);
			})
			.thenSucceed();
	}

	/**
	 * <code>/kill @e</code> and <code>/tp @e</code> are common commands, and the vessel's entity is an entity: killed, or
	 * moved to where nothing is loaded or into another dimension, it left its vessel stopped, unseen and without a way
	 * back but <code>/slipway disassemble</code>. It takes neither.
	 */
	@GameTest(structure = ARENA, maxTicks = 100)
	public void killAndTeleportCommandsLeaveAVessel(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		VesselEntity entity = vessel.entity;
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				Vec3 before = entity.position();
				BlockPos at = entity.blockPosition();
				String it = "@e[type=slipway:vessel,x=" + at.getX() + ",y=" + at.getY() + ",z=" + at.getZ() + ",distance=..3]";
				List<String> killed = TestShips.command(helper, "kill " + it);
				check(helper, killed.size() == 1 && killed.getFirst().startsWith("Killed"), "/kill did not find the vessel's entity: " + killed);
				check(helper, !entity.isRemoved() && manager.active(record.id) == vessel, "/kill removed the vessel's entity (" + entity.getRemovalReason() + ")");
				List<String> moved = TestShips.command(helper, "tp " + it + " " + (at.getX() - 5000) + " " + at.getY() + " " + at.getZ());
				check(helper, entity.position().distanceTo(before) < 1.0e-6 && manager.active(record.id) == vessel,
					"/tp moved the vessel's entity " + entity.position().distanceTo(before) + " blocks: " + moved);
				List<String> sent = TestShips.command(helper, "execute in minecraft:the_nether run tp " + entity.getStringUUID() + " 0 80 0");
				check(helper, !entity.isRemoved() && entity.level() == level && manager.active(record.id) == vessel,
					"a teleport took the vessel's entity out of its dimension (" + entity.getRemovalReason() + "): " + sent);
			})
			.thenIdle(5)
			.thenExecute(() -> check(helper, manager.active(record.id) == vessel && vessel.hasBody && level.getEntity(entity.getUUID()) == entity,
				"the vessel did not stay after the commands"))
			.thenSucceed();
	}

	/**
	 * Another mod can still remove the entity (a test's cleanup does: it discards every entity in its arena). The
	 * vessel then stops and stays stored, and <code>/slipway disassemble</code> puts its blocks back into the world.
	 */
	@GameTest(structure = ARENA, maxTicks = 100)
	public void aVesselThatLostItsEntityCanBePutBackIntoTheWorld(GameTestHelper helper) {
		BlockPos helm = InteractionGameTests.deck(helper, 8, 4, 8, 1);
		ServerLevel level = helper.getLevel();
		VesselManager manager = VesselManager.get(level);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		helper.startSequence()
			.thenWaitUntil(() -> check(helper, TestShips.settled(vessel), "the vessel has no body yet"))
			.thenExecute(() -> {
				check(helper, helper.getBlockState(helm).isAir(), "the helm is still in the world");
				vessel.entity.discard();
				check(helper, manager.active(record.id) == null && manager.registry().get(record.id) == record, "a vessel without its entity is still active, or forgotten");
			})
			.thenIdle(3)
			.thenExecute(() -> {
				check(helper, TestShips.blocksInPlot(level, record) == 10, "the plot holds " + TestShips.blocksInPlot(level, record) + " blocks");
				List<String> reply = TestShips.command(helper, "slipway disassemble " + record.id);
				check(helper, manager.registry().get(record.id) == null && TestShips.blocksInPlot(level, record) == 0, "/slipway disassemble left the vessel: " + reply);
				check(helper, helper.getBlockState(helm).is(SlipwayRegistry.HELM) && helper.getBlockState(helm.offset(1, -1, 1)).is(Blocks.OAK_PLANKS),
					"the blocks did not come back into the world: " + helper.getBlockState(helm) + ", " + helper.getBlockState(helm.offset(1, -1, 1)));
			})
			.thenSucceed();
	}
}