package dev.timstewart.slipway.gametest;

import static dev.timstewart.slipway.gametest.TestShips.check;

import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The survival rules in the game: a vessel's sails and burners are read off its real blocks, its thrust is what helm
 * and sails give its weight, and it hovers on hot air or not at all. (The test server's settings make new vessels free
 * of the rules; these tests put their own vessels under them.)
 */
public class SurvivalGameTests {
	private static final String ARENA = "slipway:arena";
	private static final BlockState WOOL = Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState();

	/** A 3x3 deck with a helm (forward north) in its middle; returns the helm's position. */
	private static BlockPos deck(GameTestHelper helper, int cx, int y, int cz, Block block) {
		for (int x = cx - 1; x <= cx + 1; x++) {
			for (int z = cz - 1; z <= cz + 1; z++) {
				helper.setBlock(new BlockPos(x, y, z), block);
			}
		}
		BlockPos helm = new BlockPos(cx, y + 1, cz);
		helper.setBlock(helm, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.SOUTH));
		return helm;
	}

	/** A mast on the deck's north-west corner with a sail two wide and three high across the deck beside it: six sail blocks. */
	private static void mastAndSail(GameTestHelper helper, BlockPos helm) {
		BlockPos foot = helm.offset(-1, 0, -1);
		for (int y = 0; y <= 3; y++) {
			helper.setBlock(foot.above(y), Blocks.OAK_LOG);
		}
		for (int x = 1; x <= 2; x++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(foot.offset(x, y, 0), WOOL);
			}
		}
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void aRigIsReadOffTheBlocks(GameTestHelper helper) {
		BlockPos helm = deck(helper, 7, 3, 7, Blocks.OAK_PLANKS);
		mastAndSail(helper, helm);
		// Wool in the deck is no sail, and a fire under the open sky lifts nothing.
		helper.setBlock(helm.offset(-1, -1, 1), WOOL);
		helper.setBlock(helm.offset(1, 0, 1), Blocks.CAMPFIRE);
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		check(helper, record.free, "the test server's settings make new vessels free");
		helper.succeedWhen(() -> {
			check(helper, TestShips.settled(vessel), "no physics body yet");
			check(helper, vessel.rig.sails() == 6, "sails counted: " + vessel.rig.sails());
			check(helper, vessel.rig.burners() == 0 && vessel.rig.envelope() == 0.0, "a fire under the open sky: burners " + vessel.rig.burners() + ", air held "
				+ vessel.rig.envelope());
			SlipwayConfig config = SlipwayConfig.get();
			SlipwayPayloads.RigInfo info = VesselManager.rigInfo(vessel);
			double expected = config.maxSpeed * Math.min(1.0, (config.helmAcceleration + 6 * config.sailThrust / vessel.mass.mass()) / config.thrustAcceleration);
			check(helper, Math.abs(info.topSpeed() - expected) < 1.0e-3, String.format(Locale.ROOT, "top speed %.3f, the helm and six sails on %.1f t give %.3f",
				info.topSpeed(), vessel.mass.mass() / 1000.0, expected));
			check(helper, info.free() && info.sails() == 6, "what the pilot is shown: " + info);
			String text = String.join("\n", TestShips.command(helper, "slipway info " + record.id));
			check(helper, text.contains("free=true") && text.contains("sails=6") && text.contains("burners=0"), "the info command: " + text);
			check(helper, !TestShips.command(helper, "slipway mode " + record.id + " free false").isEmpty() && !record.free, "the mode command did not put it under the rules");
			check(helper, String.join("\n", TestShips.command(helper, "slipway info " + record.id)).contains("flies=false"), "under the rules, without lift, it does not fly");
		});
	}

	@GameTest(structure = ARENA, maxTicks = 100)
	public void underTheRulesSailsMoveAShipFasterThanItsHelmAlone(GameTestHelper helper) {
		// Two decks of iron, 70 t each, dropped side by side with the helm full ahead: one under oars, one under sail.
		BlockPos oarsHelm = deck(helper, 3, 11, 8, Blocks.IRON_BLOCK);
		BlockPos sailHelm = deck(helper, 11, 11, 8, Blocks.IRON_BLOCK);
		mastAndSail(helper, sailHelm);
		VesselRecord oars = TestShips.assemble(helper, oarsHelm);
		VesselRecord sail = TestShips.assemble(helper, sailHelm);
		ActiveVessel oarsVessel = TestShips.active(helper, oars);
		ActiveVessel sailVessel = TestShips.active(helper, sail);
		oars.free = false;
		sail.free = false;
		int[] since = {-1};
		helper.onEachTick(() -> {
			if (since[0] < 0 && oarsVessel.hasBody && sailVessel.hasBody) {
				since[0] = 0;
				long now = helper.getLevel().getGameTime();
				for (ActiveVessel vessel : new ActiveVessel[] {oarsVessel, sailVessel}) {
					vessel.input.set(1f, 0f, 0f, 0f, 0f, 0f, now);
					vessel.scriptedInputUntil = now + 100;
				}
			} else if (since[0] >= 0 && ++since[0] == 16) {
				SlipwayConfig config = SlipwayConfig.get();
				double drag = config.thrustAcceleration / config.maxSpeed;
				// Thrust against the drag every vessel has, for 16 ticks from rest.
				double reached = (1.0 - Math.exp(-drag * 0.8)) / drag;
				double oarsExpected = Math.min(config.thrustAcceleration, config.helmAcceleration) * reached;
				double sailExpected = Math.min(config.thrustAcceleration, config.helmAcceleration + 6 * config.sailThrust / sailVessel.mass.mass()) * reached;
				double oarsSpeed = Math.hypot(oars.linearVelocity.x, oars.linearVelocity.z), sailSpeed = Math.hypot(sail.linearVelocity.x, sail.linearVelocity.z);
				check(helper, sailVessel.rig.sails() == 6 && oarsVessel.rig.sails() == 0, "sails: " + sailVessel.rig.sails() + " and " + oarsVessel.rig.sails());
				check(helper, Math.abs(oarsSpeed - oarsExpected) < 0.15 * oarsExpected, String.format(Locale.ROOT, "under oars %.3f m/s after 0.8 s, its thrust on %.1f t gives %.3f",
					oarsSpeed, oarsVessel.mass.mass() / 1000.0, oarsExpected));
				check(helper, Math.abs(sailSpeed - sailExpected) < 0.15 * sailExpected, String.format(Locale.ROOT, "under sail %.3f m/s after 0.8 s, its thrust on %.1f t gives %.3f",
					sailSpeed, sailVessel.mass.mass() / 1000.0, sailExpected));
				check(helper, sailSpeed > 3.0 * oarsSpeed, String.format(Locale.ROOT, "six sails made %.3f m/s of %.3f", sailSpeed, oarsSpeed));
				// Hover is on for both and holds neither: they have no lift, and fall.
				check(helper, oars.hover && !oarsVessel.hovering() && oars.linearVelocity.y < -3.0, "a deck of iron without lift did not fall: " + oars.linearVelocity.y);
				helper.succeed();
			}
		});
	}

	@GameTest(structure = ARENA, maxTicks = 300)
	public void aBalloonFliesOnItsHotAirAndComesDownWhenTheFiresAreOut(GameTestHelper helper) {
		BlockPos helm = deck(helper, 7, 2, 7, Blocks.OAK_PLANKS);
		// Two fires beside the helm, four posts of fence from the deck's corners to the roof of the canopy, and the
		// canopy: 7 x 7 outside, walls of wool five high from two blocks above the deck, a roof on top. It holds
		// 5 x 5 x 5 cells of air less the posts.
		helper.setBlock(helm.west(), Blocks.CAMPFIRE);
		helper.setBlock(helm.east(), Blocks.CAMPFIRE);
		for (int dx = -1; dx <= 1; dx += 2) {
			for (int dz = -1; dz <= 1; dz += 2) {
				for (int y = 0; y <= 6; y++) {
					helper.setBlock(helm.offset(dx, y, dz), Blocks.OAK_FENCE);
				}
			}
		}
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				helper.setBlock(helm.offset(x, 7, z), WOOL);
				if (Math.abs(x) == 3 || Math.abs(z) == 3) {
					for (int y = 2; y <= 6; y++) {
						helper.setBlock(helm.offset(x, y, z), WOOL);
					}
				}
			}
		}
		VesselRecord record = TestShips.assemble(helper, helm);
		ActiveVessel vessel = TestShips.active(helper, record);
		record.free = false;
		double startY = record.pose.y();
		int[] phase = {0};
		int[] ticks = {0};
		double[] heldY = {0}, risenY = {0};
		helper.onEachTick(() -> {
			if (!TestShips.settled(vessel)) {
				return;
			}
			ticks[0]++;
			long now = helper.getLevel().getGameTime();
			double ratio = VesselManager.rigInfo(vessel).liftRatio();
			if (phase[0] == 0 && ticks[0] == 40) {
				// It hangs where it was built.
				check(helper, vessel.rig.burners() == 2, "burners under the canopy: " + vessel.rig.burners());
				check(helper, vessel.rig.envelope() > 115 && vessel.rig.envelope() <= 125, "air held by the canopy: " + vessel.rig.envelope());
				check(helper, vessel.rig.sails() == 0, "the skin of the canopy counted as " + vessel.rig.sails() + " sails");
				check(helper, ratio > 1.2 && vessel.hovering(), String.format(Locale.ROOT, "lift %.2f of its weight (%.1f t)", ratio, vessel.mass.mass() / 1000.0));
				check(helper, Math.abs(record.pose.y() - startY) < 0.2 && record.linearVelocity.length() < 0.05, String.format(Locale.ROOT,
					"it did not hold its place: %.3f from where it was built, moving at %.3f", record.pose.y() - startY, record.linearVelocity.length()));
				heldY[0] = record.pose.y();
				vessel.input.set(0f, 0f, 1f, 0f, 0f, 0f, now);
				vessel.scriptedInputUntil = now + 20;
				phase[0] = 1;
			} else if (phase[0] == 1 && ticks[0] == 70) {
				// It climbed with the lift it has to spare, no faster.
				double climbed = record.pose.y() - heldY[0];
				double spare = (ratio - 1.0) * 9.81;
				check(helper, climbed > 0.3, "it did not climb: " + climbed);
				check(helper, climbed < spare * 1.2, String.format(Locale.ROOT, "it climbed %.2f in a second and a half with %.2f m/s^2 to spare", climbed, spare));
				risenY[0] = record.pose.y();
				// Out with the fires.
				for (int dx = -1; dx <= 1; dx += 2) {
					BlockPos fire = record.toPlot(new BlockPos(dx, 0, 0));
					BlockState state = helper.getLevel().getBlockState(fire);
					check(helper, state.is(Blocks.CAMPFIRE), "no campfire in the plot at " + fire + ": " + state);
					helper.getLevel().setBlock(fire, state.setValue(CampfireBlock.LIT, false), 3);
				}
				phase[0] = 2;
			} else if (phase[0] == 2 && ticks[0] == 100) {
				check(helper, vessel.rig.burners() == 0 && !vessel.hovering(), "with its fires out it still has " + vessel.rig.burners() + " burners");
				check(helper, record.hover, "putting the fires out changed the hover setting");
				check(helper, record.pose.y() < risenY[0] - 1.0, String.format(Locale.ROOT, "with its fires out it did not come down: %.2f below where it was, at %.2f m/s",
					risenY[0] - record.pose.y(), record.linearVelocity.y));
				helper.succeed();
			}
		});
	}
}
