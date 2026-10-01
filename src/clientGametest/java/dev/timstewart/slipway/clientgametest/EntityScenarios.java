package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselEntity;
import dev.timstewart.slipway.vessel.VesselManager;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * vessel-entity: nothing takes a vessel from the player who is at it. A vessel's entity sits at the middle of its
 * bounds, and where it is decides whether the vessel is loaded and whether the player is shown it. A block set forty
 * blocks out in the plot by a command moves the middle twenty blocks, and the entity goes there on the server and on
 * the client. A block four hundred blocks out would move it two hundred, to where nothing is loaded and out of the
 * player's range: the entity stays, the client keeps its own there too, and the vessel is still drawn, flies and is
 * followed. Then <code>/kill @e</code> and <code>/tp @e</code> leave it as it is.
 */
final class EntityScenarios {
	private EntityScenarios() {
	}

	/** The vessel on the server: null fields when it is not active. */
	record OnServer(boolean active, boolean hasBody, boolean held, Vec3 entityLocal, Vec3 entityAt, Vec3 middle, boolean viewed, boolean tracked, int entityId,
		boolean entitiesTickAtMiddle) {
	}

	/** The vessel on the client; {@code entityAt} is null when the client has no entity for it. */
	record OnClient(int identity, int vertices, Vec3 entityLocal, Vec3 entityAt, Vec3 place, Vec3 middle, boolean entityTicked, boolean hasChunkAtMiddle) {
	}

	static OnServer onServer(TestServerContext server, long id) {
		return server.computeOnServer(s -> {
			ActiveVessel v = Game.manager(s).active(id);
			if (v == null || v.entity == null) {
				return new OnServer(false, false, false, null, null, null, false, false, -1, false);
			}
			ServerPlayer player = Game.player(s);
			Vec3 middle = VesselManager.worldCentre(v.record);
			return new OnServer(true, v.hasBody, v.entityHeld, v.entityLocal, v.entity.position(), middle, v.viewers.contains(player),
				PlayerLookup.tracking(v.entity).contains(player), v.entity.getId(), s.overworld().isPositionEntityTicking(BlockPos.containing(middle)));
		});
	}

	static OnClient onClient(ClientGameTestContext ctx, long id) {
		return ctx.computeOnClient(mc -> {
			ClientVessel v = Check.notNull(ClientVessels.get(id), "the client no longer has vessel %d", id);
			Check.that(v.ready(), "the client's vessel %d is not ready", id);
			Entity entity = mc.level.getEntity(v.entityId);
			Vec3 middle = v.worldCentre(v.tickPose());
			return new OnClient(System.identityHashCode(v), v.mesh.vertexCount(), v.entityLocal, entity == null ? null : entity.position(), v.entityPlace(v.tickPose()), middle,
				entity instanceof VesselEntity own && own.pose() == v.tickPose(),
				mc.level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(middle.x), SectionPos.blockToSectionCoord(middle.z)));
		});
	}

	static void vesselEntity(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helm = new BlockPos(0, Game.GROUND_Y + 4, 80);
			Game.teleport(ctx, sp, 8.5, Game.GROUND_Y, 80.5, 90f, -15f);
			server.runOnServer(s -> {
				Map<BlockPos, BlockState> ship = Ships.deck(2, Blocks.OAK_PLANKS.defaultBlockState());
				ship.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
				Ships.build(s.overworld(), helm, ship);
			});
			long id = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm).id);
			Game.waitClientComplete(ctx, id, 200);
			server.waitFor(s -> Game.active(s, id).hasBody, 100);
			ctx.waitTicks(10);
			BlockPos anchor = server.computeOnServer(s -> Game.active(s, id).record.anchor);
			OnServer start = onServer(server, id);
			OnClient startSeen = onClient(ctx, id);
			Check.that(start.active() && start.tracked() && start.viewed() && !start.held(), "at the start the player does not have the vessel, or its entity is held: %s", start);
			Check.atMost("distance of the entity from the middle of the bounds at the start", start.entityAt().distanceTo(start.middle()), 1.0e-6);
			Check.that(startSeen.entityAt() != null && startSeen.entityLocal() == null, "at the start the client has no entity for the vessel, or holds it: %s", startSeen);

			// Forty blocks out, by a command (the columns two out from the vessel's own are loaded, so it can).
			server.runCommand("setblock " + (anchor.getX() + 40) + " " + anchor.getY() + " " + anchor.getZ() + " minecraft:cobweb");
			ctx.waitTicks(10);
			OnServer near = onServer(server, id);
			OnClient nearSeen = onClient(ctx, id);
			r.metric("near.middleMoved", near.middle().distanceTo(start.middle()));
			Check.near("blocks the middle of the bounds moved with a block forty out", near.middle().distanceTo(start.middle()), 19.0, 0.01);
			Check.that(near.active() && near.tracked() && near.viewed() && !near.held(), "after the near block the player lost the vessel, or its entity is held: %s", near);
			Check.atMost("distance of the server's entity from the new middle", near.entityAt().distanceTo(near.middle()), 1.0e-6);
			Check.that(nearSeen.entityLocal() == null && nearSeen.entityAt() != null, "the client holds the entity though the server does not: %s", nearSeen);
			Check.atMost("distance of the client's entity from the client's middle of the bounds", nearSeen.entityAt().distanceTo(nearSeen.middle()), 1.0e-6);
			Check.atMost("distance between the client's and the server's entity after the near block", nearSeen.entityAt().distanceTo(near.entityAt()), 0.05);

			// Four hundred blocks out: no command reaches there in one step (nothing is loaded), another mod can.
			server.runOnServer(s -> s.overworld().setBlock(anchor.offset(400, 0, 0), Blocks.COBWEB.defaultBlockState(), 2));
			ctx.waitTicks(20);
			OnServer far = onServer(server, id);
			Check.that(far.active() && far.hasBody(), "the vessel unloaded when a block was set far out in its plot");
			OnClient farSeen = onClient(ctx, id);
			r.metric("far.middleMoved", far.middle().distanceTo(near.middle()));
			r.metric("far.serverEntityMoved", far.entityAt().distanceTo(near.entityAt()));
			Check.atLeast("blocks the middle of the bounds moved with a block four hundred out", far.middle().distanceTo(near.middle()), 150.0);
			Check.that(!far.entitiesTickAtMiddle() && !farSeen.hasChunkAtMiddle(),
				"the middle of the bounds is where entities tick (%s) or in a chunk the client has (%s): the test proves nothing", far.entitiesTickAtMiddle(), farSeen.hasChunkAtMiddle());
			Check.that(far.held() && far.tracked() && far.viewed(), "the server does not hold the entity, or the player lost the vessel: %s", far);
			Check.atMost("blocks the server's entity moved", far.entityAt().distanceTo(near.entityAt()), 0.05);
			Check.that(farSeen.identity() == startSeen.identity(), "the client dropped the vessel and got it again");
			Check.that(farSeen.entityLocal() != null && farSeen.entityAt() != null, "the client was not told where the entity is, or has none: %s", farSeen);
			Check.atMost("distance between the server's and the client's place of the entity on the vessel", farSeen.entityLocal().distanceTo(far.entityLocal()), 1.0e-9);
			Check.atMost("distance between the client's and the server's entity after the far block", farSeen.entityAt().distanceTo(far.entityAt()), 0.05);
			Check.atLeast("vertices of the vessel's mesh", farSeen.vertices(), 1);

			// It still flies, and the client's entity goes with it every tick (an entity in a chunk the client does not
			// have is not ticked: its riders and the picture stay behind).
			server.runCommand("slipway control " + id + " 1 0 0 0 0 0 40");
			double flown = 0.0;
			for (int i = 0; i < 40; i++) {
				ctx.waitTick();
				OnClient seen = onClient(ctx, id);
				Check.that(seen.entityAt() != null && seen.entityTicked(), "flying, tick %d: the client's entity was not ticked: %s", i, seen);
				Check.atMost("flying: distance of the client's entity from its place on the vessel", seen.entityAt().distanceTo(seen.place()), 1.0e-6);
				flown = seen.place().distanceTo(farSeen.place());
			}
			ctx.waitTicks(40);
			OnServer flew = onServer(server, id);
			OnClient flewSeen = onClient(ctx, id);
			r.metric("flight.clientEntityMoved", flown);
			Check.atLeast("blocks the client's entity moved in two seconds of thrust", flown, 2.0);
			Check.that(flew.active() && flew.held() && flew.tracked() && flew.viewed(), "after flying the player lost the vessel: %s", flew);
			Check.atMost("distance between the client's and the server's entity after flying", flewSeen.entityAt().distanceTo(flew.entityAt()), 0.5);

			// Commands that are run on every entity.
			server.runCommand("kill @e[type=slipway:vessel]");
			server.runCommand("tp @e[type=slipway:vessel] 0 200 2000");
			ctx.waitTicks(20);
			OnServer after = onServer(server, id);
			Check.that(after.active() && after.hasBody() && after.entityId() == start.entityId() && after.tracked() && after.viewed(),
				"/kill @e or /tp @e took the vessel: %s", after);
			OnClient afterSeen = onClient(ctx, id);
			Check.that(afterSeen.identity() == startSeen.identity() && afterSeen.entityAt() != null && afterSeen.entityTicked(), "after /kill @e and /tp @e the client lost the vessel: %s", afterSeen);
			Check.atMost("blocks the server's entity moved with /tp @e", after.entityAt().distanceTo(flew.entityAt()), 0.5);
			Shots.take(ctx, r, "01-still-there");
			server.runCommand("slipway remove " + id);
			ctx.waitTicks(20);
		}
	}
}