package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselRecord;
import dev.timstewart.slipway.vessel.VesselRegion;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * interaction: on a vessel turned 15 degrees in yaw and 30 in pitch and roll, the player places blocks on the faces
 * it aims at (checked in vessel-local space, logs with the matching axis), places and mines in survival (item used,
 * drop in the world, none in the vessel's plot; a block where the vessel may not grow to is refused by the server
 * and the client ends without the block and with its item back), opens the chest (its items shown), pulls the lever
 * (lamp lit), opens the door, and flips the lever twice while the vessel flies (lamp follows).
 */
final class InteractionScenarios {
	private InteractionScenarios() {
	}

	static final BlockPos CHEST = new BlockPos(-3, 0, 3);
	static final BlockPos LAMP = new BlockPos(3, 0, 3);
	static final BlockPos LEVER = new BlockPos(3, 1, 3);
	static final BlockPos DOOR = new BlockPos(-2, 0, 0);

	static Map<BlockPos, BlockState> ship() {
		Map<BlockPos, BlockState> blocks = Ships.deck(5, Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		blocks.put(CHEST, Blocks.CHEST.defaultBlockState());
		blocks.put(LAMP, Blocks.REDSTONE_LAMP.defaultBlockState());
		blocks.put(LEVER, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH));
		blocks.put(DOOR, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.FACING, Direction.EAST));
		blocks.put(DOOR.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.EAST));
		return blocks;
	}

	static void interaction(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 30, 40);
			server.runOnServer(s -> {
				Ships.build(s.overworld(), helm, ship());
				ChestBlockEntity chest = (ChestBlockEntity)s.overworld().getBlockEntity(helm.offset(CHEST));
				chest.setItem(0, new ItemStack(Items.EMERALD, 12));
				chest.setChanged();
			});
			VesselRecord record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
			long id = record.id;
			server.runCommand("slipway mode " + id + " level false");
			server.runCommand("slipway rotate " + id + " 15 30 30");
			ctx.waitTicks(40);
			double[] attitude = Flight.sample(server, id).pose().attitudeDegrees();
			double tilt = Flight.sample(server, id).tilt();
			r.metric("attitude.pitch", attitude[0]);
			r.metric("attitude.roll", attitude[2]);
			r.metric("attitude.tilt", tilt);
			Check.that(Math.abs(attitude[0]) > 20.0 && Math.abs(attitude[2]) > 20.0 && tilt < 50.0,
				"the vessel is not turned about two axes within the walkable limit: pitch %.1f, roll %.1f, tilt %.1f", attitude[0], attitude[2], tilt);
			Game.waitClientReady(ctx, id, 100);

			server.runOnServer(s -> {
				var inv = Game.player(s).getInventory();
				inv.setItem(0, new ItemStack(Items.STONE, 16));
				inv.setItem(1, new ItemStack(Items.OAK_LOG, 16));
				inv.setItem(2, new ItemStack(Items.DIAMOND_PICKAXE));
				inv.setItem(3, ItemStack.EMPTY);
			});
			DeckScenarios.placeRider(ctx, sp, server, id, new Vec3(3.5, 0.3, 2.0));
			Flight.Rider rider = Flight.rider(ctx, id);
			Check.that(rider.carrier() == id && rider.local().y > -0.05, "the player is not standing on the tilted deck: %s", rider);

			// Place on the faces aimed at, in vessel-local space.
			select(ctx, 0);
			useOn(ctx, id, new BlockPos(1, -1, 0), Direction.UP);
			expect(server, record, new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), "stone placed on the top face of the deck");
			useOn(ctx, id, new BlockPos(1, 0, 0), Direction.EAST);
			expect(server, record, new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(), "stone placed on the east face of the first stone");
			select(ctx, 1);
			useOn(ctx, id, new BlockPos(1, 0, 0), Direction.UP);
			expect(server, record, new BlockPos(1, 1, 0), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y),
				"log placed on a top face stands upright (local axis y)");
			useOn(ctx, id, new BlockPos(2, 0, 0), Direction.SOUTH);
			expect(server, record, new BlockPos(2, 0, 1), Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z),
				"log placed on a south face lies along local z");
			Shots.take(ctx, r, "01-placed");

			// Survival: placing uses the item; mining drops the block into the world, never into the plot.
			DeckScenarios.placeRider(ctx, sp, server, id, new Vec3(0.5, 0.3, 2.0));
			server.runCommand("gamemode survival @a");
			ctx.waitFor(mc -> !mc.player.getAbilities().instabuild, 20);
			select(ctx, 0);
			int stoneBefore = server.computeOnServer(s -> Game.player(s).getInventory().countItem(Items.STONE));
			BlockPos target = new BlockPos(2, 0, 2);
			useOn(ctx, id, target.below(), Direction.UP);
			expect(server, record, target, Blocks.STONE.defaultBlockState(), "stone placed in survival");
			Check.equal("stone left after placing one in survival", server.computeOnServer(s -> Game.player(s).getInventory().countItem(Items.STONE)), stoneBefore - 1);
			select(ctx, 2);
			Vec3 targetWorld = server.computeOnServer(s -> Game.active(s, id).record.pose.localToWorld(Vec3.atCenterOf(target)));
			// mined stone drops cobblestone
			int dropBeforeMining = server.computeOnServer(s -> Game.player(s).getInventory().countItem(Items.COBBLESTONE));
			Flight.aimAtLocal(ctx, id, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, target);
			ctx.getInput().holdKey(o -> o.keyAttack);
			try {
				server.waitFor(s -> s.overworld().getBlockState(record.toPlot(target)).isAir(), 100);
			} finally {
				ctx.getInput().releaseKey(o -> o.keyAttack);
			}
			// The drop appears where the block is in the world (it may then fall or be picked up), never in the plot.
			List<ItemEntity> dropsNear = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(targetWorld, targetWorld).inflate(2.0),
				e -> e.getItem().is(Items.COBBLESTONE)));
			long inPlot = server.computeOnServer(s -> plotItems(s.overworld(), record));
			ctx.waitTicks(30);
			int dropAfterMining = server.computeOnServer(s -> Game.player(s).getInventory().countItem(Items.COBBLESTONE));
			r.metric("mining.dropsAtBlock", dropsNear.size());
			r.metric("mining.pickedUp", dropAfterMining - dropBeforeMining);
			Check.equal("item entities in the vessel's plot", inPlot, 0L);
			Check.that(!dropsNear.isEmpty() || dropAfterMining == dropBeforeMining + 1,
				"mining the vessel's stone neither dropped cobblestone where the block is in the world nor gave it to the player");
			refusedBlock(ctx, server, record, id);
			server.runCommand("gamemode creative @a");
			ctx.waitFor(mc -> mc.player.getAbilities().instabuild, 20);

			// Open the chest: its items are shown.
			select(ctx, 3);
			useOn(ctx, id, CHEST, null);
			ctx.waitForScreen(ContainerScreen.class);
			ItemStack shown = ctx.computeOnClient(mc -> ((ContainerScreen)mc.gui.screen()).getMenu().getSlot(0).getItem().copy());
			Check.that(shown.is(Items.EMERALD) && shown.getCount() == 12, "the chest on the vessel shows %s in its first slot, expected 12 emeralds", shown);
			Shots.take(ctx, r, "02-chest-open");
			ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
			ctx.waitForScreen(null);

			// Lever lights the lamp; the door opens.
			useLever(ctx, id);
			server.waitFor(s -> lit(s.overworld(), record) && s.overworld().getBlockState(record.toPlot(LEVER)).getValue(LeverBlock.POWERED), 20);
			useOn(ctx, id, DOOR, null);
			server.waitFor(s -> s.overworld().getBlockState(record.toPlot(DOOR)).getValue(DoorBlock.OPEN)
				&& s.overworld().getBlockState(record.toPlot(DOOR.above())).getValue(DoorBlock.OPEN), 20);
			Shots.take(ctx, r, "03-lamp-lit");

			// While flying: flip the lever twice; the lamp follows, the player stays aboard.
			server.runCommand("slipway control " + id + " 0.4 0 0 0 0 0 200");
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() > 0.3, 100);
			useLever(ctx, id);
			server.waitFor(s -> !lit(s.overworld(), record), 20);
			useLever(ctx, id);
			server.waitFor(s -> lit(s.overworld(), record), 20);
			double speed = server.computeOnServer(s -> Game.active(s, id).record.linearVelocity.length());
			r.metric("flight.speed", speed);
			Check.atLeast("vessel speed while the lever is used", speed, 0.3);
			Flight.Rider aboard = Flight.rider(ctx, id);
			Check.equal("the player is still carried by the flying vessel", aboard.carrier(), id);
			Shots.take(ctx, r, "04-flying-tilted");
		}
	}

	/**
	 * In survival: a block where the vessel may not grow to. The server refuses it; the client has placed it by itself
	 * and taken the item from the stack, and must end with no block there and the item back.
	 */
	private static void refusedBlock(ClientGameTestContext ctx, TestServerContext server, VesselRecord record, long id) {
		// The vessel is three blocks high (the deck, what stands on it, the upright log on a stone). With a largest
		// span of three it cannot grow upwards. The player stands on the other stone and looks at the log's top.
		BlockPos top = new BlockPos(1, 1, 0);
		BlockPos refused = top.above();
		Check.that(record.localMin.getY() == -1 && record.localMax.getY() == 1, "the vessel is not three blocks high: %s to %s", record.localMin, record.localMax);
		DeckScenarios.placeRider(ctx, server, id, new Vec3(2.5, 1.3, 0.9));
		select(ctx, 0);
		SlipwayConfig configured = server.computeOnServer(s -> SlipwayConfig.get());
		server.runOnServer(s -> {
			SlipwayConfig small = configured.sanitized();
			small.maxVesselSpan = 3;
			SlipwayConfig.set(small);
		});
		try {
			int before = server.computeOnServer(s -> Game.player(s).getInventory().countItem(Items.STONE));
			Check.equal("stone the client shows before the refused block", ctx.computeOnClient(mc -> mc.player.getInventory().countItem(Items.STONE)), before);
			useOn(ctx, id, top, Direction.UP);
			ctx.waitTicks(10);
			Check.that(server.computeOnServer(s -> s.overworld().getBlockState(record.toPlot(refused)).isAir()), "the server placed a block past the largest span");
			Check.equal("stone left on the server after the refused block", server.computeOnServer(s -> Game.player(s).getInventory().countItem(Items.STONE)), before);
			BlockState shown = ctx.computeOnClient(mc -> mc.level.getBlockState(record.toPlot(refused)));
			Check.that(shown.isAir(), "the client still shows the refused block: %s", shown);
			Check.equal("stone the client shows after the refused block", ctx.computeOnClient(mc -> mc.player.getInventory().countItem(Items.STONE)), before);
			Check.equal("the vessel's height after the refused block", server.computeOnServer(s -> Game.active(s, id).record.localMax.getY()), 1);
		} finally {
			server.runOnServer(s -> SlipwayConfig.set(configured));
		}
		// back to where the rest of the scenario looks from
		DeckScenarios.placeRider(ctx, server, id, new Vec3(0.5, 0.3, 2.0));
	}

	static boolean lit(ServerLevel level, VesselRecord record) {
		return level.getBlockState(record.toPlot(LAMP)).getValue(RedstoneLampBlock.LIT);
	}

	static long plotItems(ServerLevel level, VesselRecord record) {
		BlockPos min = record.plotMin();
		BlockPos max = record.plotMax();
		return level.getEntitiesOfClass(ItemEntity.class, AABB.encapsulatingFullBlocks(min, max).inflate(8.0)).size();
	}

	static void select(ClientGameTestContext ctx, int slot) {
		ctx.getInput().pressKey(o -> o.keyHotbarSlots[slot]);
		ctx.waitFor(mc -> mc.player.getInventory().getSelectedSlot() == slot, 10);
	}

	/**
	 * Aims at a face of a vessel block (its centre when face is null) and right-clicks it. The crosshair must be on
	 * that block and, when given, that face (vessel-local) before the click.
	 */
	static void useOn(ClientGameTestContext ctx, long id, BlockPos local, Direction face) {
		// a point just inside the face, so the ray enters the block through it
		double x = local.getX() + 0.5 + (face == null ? 0 : face.getStepX() * 0.45);
		double y = local.getY() + 0.5 + (face == null ? 0 : face.getStepY() * 0.45);
		double z = local.getZ() + 0.5 + (face == null ? 0 : face.getStepZ() * 0.45);
		useAt(ctx, id, local, face, new Vec3(x, y, z));
	}

	/** Right-clicks a vessel block at a vessel-local point (for blocks whose shape does not fill the centre). */
	static void useAt(ClientGameTestContext ctx, long id, BlockPos local, Direction face, Vec3 point) {
		Flight.Hit hit = Flight.aimAtLocal(ctx, id, point.x, point.y, point.z, local);
		if (face != null) {
			Check.equal("face under the crosshair on local " + local.toShortString(), hit.face(), face);
		}
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(2);
	}

	/** The lever lies on the lamp's top: aim low in its block. */
	static void useLever(ClientGameTestContext ctx, long id) {
		useAt(ctx, id, LEVER, null, new Vec3(LEVER.getX() + 0.5, LEVER.getY() + 0.15, LEVER.getZ() + 0.5));
	}

	static void expect(TestServerContext server, VesselRecord record, BlockPos local, BlockState expected, String what) {
		try {
			server.waitFor(s -> s.overworld().getBlockState(record.toPlot(local)) == expected, 20);
		} catch (AssertionError timeout) {
			throw new AssertionError(what + ": expected " + expected + " at local " + local.toShortString() + ", the vessel has "
				+ server.computeOnServer(s -> s.overworld().getBlockState(record.toPlot(local))));
		}
	}

	static boolean isPlot(Vec3 pos) {
		return VesselRegion.isReserved(pos.x, pos.z);
	}

	static Vec3 world(VesselPose pose, BlockPos local) {
		return pose.localToWorld(Vec3.atCenterOf(local));
	}
}
