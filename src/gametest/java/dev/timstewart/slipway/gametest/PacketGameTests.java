package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.net.ServerPackets;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselRecord;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Forged and out-of-range helm control packets are refused; valid ones are sanitised and applied. */
public class PacketGameTests {
	private static final String ARENA = "slipway:arena";

	private static SlipwayPayloads.HelmControl control(long id, float forward, float yaw, byte toggles) {
		return new SlipwayPayloads.HelmControl(id, 1, forward, 0f, 0f, 0f, yaw, 0f, toggles);
	}

	private static VesselRecord ship(GameTestHelper helper) {
		for (int x = 5; x <= 7; x++) {
			helper.setBlock(new BlockPos(x, 6, 7), Blocks.OAK_PLANKS);
		}
		helper.setBlock(new BlockPos(6, 7, 7), SlipwayRegistry.HELM.defaultBlockState());
		return TestShips.assemble(helper, new BlockPos(6, 7, 7));
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void helmControlIsOnlyAcceptedFromThePilot(GameTestHelper helper) {
		VesselRecord record = ship(helper);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
		long rejected = ServerPackets.rejectedCount();
		check(helper, ServerPackets.handleHelmControl(stranger, control(record.id, 1f, 0f, (byte)0)) != null, "a non-pilot steered the vessel");
		check(helper, vessel.input.isIdle(), "input changed by a non-pilot");
		check(helper, ServerPackets.rejectedCount() == rejected + 1, "rejection not counted");

		ServerPlayer pilot = helper.makeMockServerPlayerInLevel();
		check(helper, pilot.startRiding(vessel.entity, true, true), "mock player could not take the helm");
		check(helper, ServerPackets.handleHelmControl(pilot, control(record.id + 1000, 1f, 0f, (byte)0)) != null, "a packet for another vessel id was accepted");
		check(helper, ServerPackets.handleHelmControl(pilot, control(record.id, 0.5f, -0.25f, (byte)0)) == null, "a valid packet was refused");
		check(helper, vessel.input.forward == 0.5f && vessel.input.yaw == -0.25f, "valid input not applied");
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void nonFiniteAndOutOfRangeAxesAreRejectedOrClamped(GameTestHelper helper) {
		VesselRecord record = ship(helper);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerPlayer pilot = helper.makeMockServerPlayerInLevel();
		pilot.startRiding(vessel.entity, true, true);
		check(helper, ServerPackets.handleHelmControl(pilot, control(record.id, Float.NaN, 0f, (byte)0)) != null, "NaN accepted");
		check(helper, ServerPackets.handleHelmControl(pilot, new SlipwayPayloads.HelmControl(record.id, 2, 0f, Float.POSITIVE_INFINITY, 0f, 0f, 0f, 0f, (byte)0)) != null,
			"infinity accepted");
		check(helper, vessel.input.isIdle(), "input changed by a rejected packet");
		check(helper, ServerPackets.handleHelmControl(pilot, control(record.id, 40f, -1e30f, (byte)0)) == null, "finite out-of-range packet refused");
		check(helper, vessel.input.forward == 1f && vessel.input.yaw == -1f, "out-of-range axes not clamped: " + vessel.input.forward + ", " + vessel.input.yaw);
		boolean hoverBefore = record.hover;
		ServerPackets.handleHelmControl(pilot, control(record.id, 0f, 0f, SlipwayPayloads.HelmControl.TOGGLE_HOVER));
		check(helper, record.hover != hoverBefore, "hover toggle ignored");
		check(helper, !record.loose, "a new vessel is loose");
		ServerPackets.handleHelmControl(pilot, control(record.id, 0f, 0f, SlipwayPayloads.HelmControl.TOGGLE_LOOSE));
		check(helper, record.loose && record.hover != hoverBefore, "the loose toggle did not make the vessel loose, or changed hover");
		ServerPackets.handleHelmControl(pilot, control(record.id, 0f, 0f, SlipwayPayloads.HelmControl.TOGGLE_LOOSE));
		check(helper, !record.loose, "the loose toggle did not end loose");
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void controlPacketsAreRateLimited(GameTestHelper helper) {
		VesselRecord record = ship(helper);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerPlayer pilot = helper.makeMockServerPlayerInLevel();
		pilot.startRiding(vessel.entity, true, true);
		int accepted = 0;
		for (int i = 0; i < 100; i++) {
			if (ServerPackets.handleHelmControl(pilot, control(record.id, 0.1f, 0f, (byte)0)) == null) {
				accepted++;
			}
		}
		check(helper, accepted == ServerPackets.MAX_CONTROL_PACKETS_PER_SECOND, "accepted " + accepted + " packets in one tick");
		helper.succeed();
	}

	@GameTest(structure = ARENA, maxTicks = 40)
	public void plotChunksAreTrackedOnlyByTheirViewers(GameTestHelper helper) {
		VesselRecord record = ship(helper);
		ActiveVessel vessel = TestShips.active(helper, record);
		ServerPlayer viewer = helper.makeMockServerPlayerInLevel();
		ServerPlayer other = helper.makeMockServerPlayerInLevel();
		helper.runAfterDelay(3, () -> {
			var chunkMap = helper.getLevel().getChunkSource().chunkMap;
			int cx = record.anchor.getX() >> 4;
			int cz = record.anchor.getZ() >> 4;
			vessel.viewers.add(viewer);
			check(helper, chunkMap.isChunkTracked(viewer, cx, cz), "a viewer does not track the vessel's plot chunk");
			check(helper, !chunkMap.isChunkTracked(other, cx, cz), "a non-viewer tracks the vessel's plot chunk");
			check(helper, !chunkMap.isChunkTracked(viewer, cx + 300, cz), "a viewer tracks an unrelated reserved chunk");
			vessel.viewers.remove(viewer);
			helper.succeed();
		});
	}
}
