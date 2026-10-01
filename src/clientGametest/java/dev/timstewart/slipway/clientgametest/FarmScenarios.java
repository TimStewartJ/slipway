package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

/**
 * farm: on a flying vessel the player flips a lever at a dispenser that feeds bone meal to wheat. The wheat grows on
 * both sides, and what the client shows and plays for it is at the vessel: the green sparkle on the crop, the
 * dispenser's smoke and click, the bone meal's sound. Nothing is shown or played in the vessel's plot.
 */
final class FarmScenarios {
	private FarmScenarios() {
	}

	static final BlockPos DISPENSER = new BlockPos(1, 0, -2);
	static final BlockPos WHEAT = new BlockPos(2, 0, -2);
	static final BlockPos LEVER = new BlockPos(1, 0, -1);

	static Map<BlockPos, BlockState> ship() {
		Map<BlockPos, BlockState> blocks = Ships.deck(3, Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(WHEAT.below(), Blocks.FARMLAND.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		blocks.put(DISPENSER, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
		blocks.put(WHEAT, Blocks.WHEAT.defaultBlockState());
		blocks.put(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH));
		return blocks;
	}

	static int age(BlockState state) {
		return state.is(Blocks.WHEAT) ? state.getValue(CropBlock.AGE) : -1;
	}

	static void flipLever(ClientGameTestContext ctx, long id) {
		InteractionScenarios.useAt(ctx, id, LEVER, null, new Vec3(LEVER.getX() + 0.5, LEVER.getY() + 0.15, LEVER.getZ() + 0.5));
	}

	static void farm(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 20, 40);
			server.runOnServer(s -> {
				Ships.build(s.overworld(), helm, ship());
				((DispenserBlockEntity)s.overworld().getBlockEntity(helm.offset(DISPENSER))).setItem(0, new ItemStack(Items.BONE_MEAL, 8));
			});
			VesselRecord record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
			long id = record.id;
			BlockPos plotWheat = record.toPlot(WHEAT);
			Game.waitClientReady(ctx, id, 100);
			DeckScenarios.placeRider(ctx, server, id, new Vec3(1.5, 0.3, 1.5));
			Game.waitClientComplete(ctx, id, 400);
			Flight.Sample start = Flight.sample(server, id);
			server.runCommand("slipway control " + id + " 0.2 0 0 0 0.05 0 6000");
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() > 1.0, 200);
			Check.equal("age of the wheat before the first dose", server.computeOnServer(s -> age(s.overworld().getBlockState(plotWheat))), 0);

			// The first dose, watched closely.
			ctx.runOnClient(mc -> Effects.start(id));
			flipLever(ctx, id);
			server.waitFor(s -> age(s.overworld().getBlockState(plotWheat)) >= 2, 40);
			ctx.waitTicks(3);
			Shots.take(ctx, r, "01-bone-meal");
			ctx.waitTicks(6);
			List<Effects.Seen> particles = ctx.computeOnClient(mc -> Effects.particles());
			List<Effects.Seen> sounds = ctx.computeOnClient(mc -> Effects.sounds());
			ctx.runOnClient(mc -> Effects.stop());
			int grown = server.computeOnServer(s -> age(s.overworld().getBlockState(plotWheat)));
			ctx.waitFor(mc -> age(mc.level.getBlockState(plotWheat)) == grown, 40);
			r.metric("dose1.age", grown);
			Vec3 crop = new Vec3(WHEAT.getX() + 0.5, WHEAT.getY() + 0.5, WHEAT.getZ() + 0.5);
			Vec3 mouth = new Vec3(DISPENSER.getX() + 1.0, DISPENSER.getY() + 0.5, DISPENSER.getZ() + 0.5);
			long sparkle = particles.stream().filter(p -> p.what().equals("SuspendedTownParticle") && p.local().distanceTo(crop) < 1.0).count();
			long smoke = particles.stream().filter(p -> p.what().equals("SmokeParticle") && p.local().distanceTo(mouth) < 1.5).count();
			r.metric("dose1.growthParticlesAtTheCrop", sparkle);
			r.metric("dose1.smokeParticlesAtTheDispenser", smoke);
			r.note("first dose: %d particles, kinds %s; sounds %s", particles.size(), particles.stream().map(Effects.Seen::what).distinct().toList(), sounds);
			Check.that(sparkle >= 5, "bone meal's green sparkle is not at the crop on the vessel: %s", particles);
			Check.that(smoke >= 1, "the dispenser's smoke is not at the dispenser on the vessel: %s", particles);
			Check.that(sounds.stream().anyMatch(s -> s.what().equals("minecraft:block.dispenser.dispense") && s.local().distanceTo(Vec3.atCenterOf(DISPENSER)) < 0.75),
				"the dispenser's click was not played at the dispenser on the vessel: %s", sounds);
			Check.that(sounds.stream().anyMatch(s -> s.what().equals("minecraft:item.bone_meal.use") && s.local().distanceTo(crop) < 0.75),
				"the bone meal's sound was not played at the crop on the vessel: %s", sounds);
			Check.that(particles.stream().noneMatch(Effects.Seen::inPlot) && sounds.stream().noneMatch(Effects.Seen::inPlot),
				"particles or sounds were left in the vessel's plot: %s %s", particles.stream().filter(Effects.Seen::inPlot).toList(), sounds.stream().filter(Effects.Seen::inPlot).toList());

			// More doses until the wheat is ripe; the client follows every stage.
			int doses = 1;
			int age = grown;
			while (age < CropBlock.MAX_AGE && doses < 8) {
				flipLever(ctx, id);
				ctx.waitTicks(3);
				flipLever(ctx, id);
				int before = age;
				server.waitFor(s -> age(s.overworld().getBlockState(plotWheat)) > before, 40);
				age = server.computeOnServer(s -> age(s.overworld().getBlockState(plotWheat)));
				int now = age;
				ctx.waitFor(mc -> age(mc.level.getBlockState(plotWheat)) == now, 40);
				doses++;
			}
			r.metric("doses.untilRipe", doses);
			Check.equal("age of the wheat after the doses", age, CropBlock.MAX_AGE);
			Game.waitClientComplete(ctx, id, 200);
			Shots.take(ctx, r, "02-ripe");

			Flight.Sample end = Flight.sample(server, id);
			r.metric("flight.distance", Flight.horizontalDistance(start, end));
			r.metric("flight.speed", end.velocity().length());
			Check.atLeast("distance the vessel flew during the scenario", Flight.horizontalDistance(start, end), 3.0);
			Check.atLeast("vessel speed at the end", end.velocity().length(), 1.0);
		}
	}
}