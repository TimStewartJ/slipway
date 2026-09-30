package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.vessel.VesselRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * assemble-mixed: a ship of mixed blocks (chest with items, furnace, door, sign, lamp and lever, stairs, torch) is
 * assembled by right-clicking its helm, looks exactly like the blocks it came from, is flown and turned from the helm,
 * and is disassembled by sneak-using the helm; every block state and block-entity value survives both ways.
 */
final class AssemblyScenarios {
	private AssemblyScenarios() {
	}

	static void assembleMixed(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 12, 24);
			Map<BlockPos, BlockState> spec = Ships.mixedShip();
			server.runOnServer(s -> {
				Ships.build(s.overworld(), helm, spec);
				Ships.fillMixed(s.overworld(), helm);
			});
			Ships.Landing asBuilt = new Ships.Landing(helm, 0, Rotation.NONE);
			List<String> built = server.computeOnServer(s -> Ships.landedMismatches(s.overworld(), asBuilt, spec));
			Check.that(built.isEmpty(), "the ship was not built as specified: %s", built);
			Map<BlockPos, CompoundTag> before = server.computeOnServer(s -> Ships.blockEntityData(s.overworld(), helm, spec.keySet()));
			Check.equal("block entities in the built ship", before.keySet(), Set.of(Ships.MIXED_CHEST, Ships.MIXED_FURNACE, Ships.MIXED_SIGN));

			// Stand on the deck south of the helm, facing it, and keep a picture of the blocks.
			Game.hud(ctx, false);
			Game.teleport(ctx, sp, helm.getX() + 0.5, helm.getY(), helm.getZ() + 2.5, 180f, 25f);
			ctx.waitFor(mc -> mc.player.onGround(), 40);
			Shots.waitStill(ctx, r, "built", 1200);
			Path builtShot = Shots.take(ctx, r, "01-built");

			// Assemble by right-clicking the helm.
			ctx.getInput().lookAt(helm);
			ctx.waitTick();
			Flight.Hit onHelm = Flight.hit(ctx);
			Check.that(onHelm != null && onHelm.vessel() == -1 && onHelm.pos().equals(helm), "the crosshair is not on the helm: %s", onHelm);
			ctx.getInput().pressKey(o -> o.keyUse);
			server.waitFor(s -> Game.manager(s).registry().size() == 1, 20);
			VesselRecord record = server.computeOnServer(s -> Game.manager(s).registry().all().iterator().next());
			long id = record.id;
			r.metric("vesselId", id);
			Check.equal("assembled block count", record.blockCount, spec.size());
			List<String> left = server.computeOnServer(s -> Ships.notAir(s.overworld(), helm, spec.keySet()));
			Check.that(left.isEmpty(), "assembly left blocks in the world: %s", left);
			List<String> plot = server.computeOnServer(s -> Ships.plotMismatches(s.overworld(), record, spec));
			Check.that(plot.isEmpty(), "the vessel's blocks differ from the ship: %s", plot);
			Map<BlockPos, CompoundTag> onVessel = server.computeOnServer(s -> Ships.plotBlockEntityData(s.overworld(), record, spec.keySet()));
			Check.equal("block-entity data on the vessel", onVessel, before);

			Game.waitClientComplete(ctx, id, 200);
			Game.ClientView view = Game.clientView(ctx, id);
			Check.equal("blocks the client knows", view.blocks(), spec.size());
			r.metric("meshVertices", view.vertices());
			ctx.waitFor(mc -> mc.player.onGround() && ((dev.timstewart.slipway.vessel.VesselCollisions.Rider)mc.player).slipway$carrier() == id, 40);

			// The vessel is drawn exactly where the blocks were: the same view, the same picture.
			ctx.getInput().lookAt(180f, 25f);
			Shots.waitStill(ctx, r, "assembled", 1200);
			double drawnInPlace = Shots.matchShot(ctx, r, "02-assembled", builtShot, 0.004);
			r.note("assembled vessel vs built blocks, same camera: mean squared difference %.5f", drawnInPlace);

			// Fly it from the helm: forward, turning right and rising.
			Game.hud(ctx, true);
			Flight.takeHelm(ctx, server, id);
			Flight.Sample start = Flight.sample(server, id);
			List<Flight.Sample> flight = Flight.hold(ctx, server, id, 60, "key.forward", "key.right", "key.jump");
			Shots.take(ctx, r, "03-piloting");
			Flight.checkContinuous("flight", flight);
			Flight.Sample end = flight.getLast();
			double distance = Flight.horizontalDistance(start, end);
			double turned = Math.abs(Flight.yawChange(start, end));
			double tilt = Flight.maxTilt(flight);
			r.metric("flight.distance", distance);
			r.metric("flight.yawDegrees", turned);
			r.metric("flight.maxTiltDegrees", tilt);
			Check.atLeast("horizontal distance flown in 60 ticks", distance, 4.0);
			Check.atLeast("yaw turned in 60 ticks", turned, 30.0);
			Check.atMost("tilt while flying with level mode on", tilt, 10.0);
			Check.that(flight.stream().allMatch(Flight.Sample::piloted), "the pilot left the helm during the flight");

			// Let it come to rest, then leave the helm and sneak-use it to disassemble.
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() < 0.05 && Game.active(s, id).record.angularVelocity.length() < 0.02, 400);
			Flight.Sample rest = Flight.sample(server, id);
			r.metric("rest.tiltDegrees", rest.tilt());
			Flight.disassembleAsPlayer(ctx, server, id);
			BlockPos around = BlockPos.containing(rest.pose().x(), rest.pose().y(), rest.pose().z());
			Ships.Landing landing = server.computeOnServer(s -> Ships.findLanding(s.overworld(), around, 3, Direction.NORTH));
			r.metric("landing.quarterTurns", landing.quarterTurns());
			r.note("landed with the helm at %s, %d quarter turns (vessel yaw %.1f)", landing.helm().toShortString(), landing.quarterTurns(), rest.pose().attitudeDegrees()[1]);
			List<String> back = server.computeOnServer(s -> Ships.landedMismatches(s.overworld(), landing, spec));
			Check.that(back.isEmpty(), "the disassembled ship differs from the original: %s", back);
			Map<BlockPos, CompoundTag> after = server.computeOnServer(s -> Ships.landedBlockEntityData(s.overworld(), landing, spec.keySet()));
			Check.equal("block-entity data after disassembly", after, before);
			AABB area = new AABB(helm).inflate(24);
			long drops = server.computeOnServer(s -> Ships.itemEntities(s.overworld(), area));
			Check.equal("items dropped by assembly, flight or disassembly", drops, 0L);

			// The player stands on the restored deck.
			ctx.waitFor(mc -> mc.player.onGround(), 40);
			double feet = ctx.computeOnClient(mc -> mc.player.getY());
			r.metric("feetAboveDeck", feet - landing.helm().getY());
			Check.near("player's feet on the restored deck", feet, landing.helm().getY(), 0.01);
			Shots.take(ctx, r, "04-disassembled");
		}
	}
}
