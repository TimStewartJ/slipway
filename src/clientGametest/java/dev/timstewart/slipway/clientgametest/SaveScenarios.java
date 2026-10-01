package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * save-reload: a moving, turned vessel full of blocks and block entities is saved by quitting the world and loaded
 * by reopening it. What loads is exactly what was saved (pose, velocities, modes, block count), the physics body
 * resumes with the saved velocity, and every block and block-entity value is still on the vessel. A second, small
 * vessel that begins at its helm is drawn lit on every side after loading (its west and north faces lie on the edge
 * of its plot columns and are lit by the columns around, which load after the vessel's own).
 */
final class SaveScenarios {
	private SaveScenarios() {
	}

	static void saveReload(ClientGameTestContext ctx, Report.Result r) {
		Lifecycle.install();
		BlockPos helm = new BlockPos(0, Game.GROUND_Y + 25, 40);
		Map<BlockPos, BlockState> spec = Ships.mixedShip();
		// Behind the ship and to the side: the ship flies off towards +z.
		BlockPos crateHelm = helm.offset(-20, 0, -16);
		Map<BlockPos, BlockState> crateSpec = new java.util.LinkedHashMap<>();
		for (int x = 0; x <= 1; x++) {
			for (int z = 0; z <= 1; z++) {
				crateSpec.put(new BlockPos(x, -1, z), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
			}
		}
		crateSpec.put(BlockPos.ZERO, Ships.helm(net.minecraft.core.Direction.NORTH));
		TestWorldSave save;
		long id;
		long crate;
		Map<BlockPos, CompoundTag> data;
		VesselRecord record;
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			save = sp.getWorldSave();
			server.runOnServer(s -> {
				Ships.build(s.overworld(), helm, spec);
				Ships.fillMixed(s.overworld(), helm);
			});
			record = server.computeOnServer(s -> Ships.assemble(s.overworld(), helm));
			id = record.id;
			crate = server.computeOnServer(s -> {
				Ships.build(s.overworld(), crateHelm, crateSpec);
				return Ships.assemble(s.overworld(), crateHelm).id;
			});
			data = server.computeOnServer(s -> Ships.plotBlockEntityData(s.overworld(), record, spec.keySet()));
			Check.equal("block entities on the vessel", data.size(), 3);
			// The player stands by the crate and looks at it, and is there again when the world is reopened: a vessel
			// is meshed only while it is in view, and this one is to be meshed from the first frame after loading.
			LooseScenarios.watch(ctx, sp, new net.minecraft.world.phys.Vec3(crateHelm.getX() + 5.5, crateHelm.getY() + 2, crateHelm.getZ() + 5.5),
				net.minecraft.world.phys.Vec3.atCenterOf(crateHelm));
			Game.waitClientComplete(ctx, crate, 400);
			Check.atLeast("sky light of the crate's darkest side or top face before quitting", Game.darkestOuterSkyLight(ctx, crate), 14);
			// Hover and level off: after loading, only gravity acts on the vessel, so its horizontal and angular
			// velocity must carry on from exactly what was saved.
			server.runCommand("slipway mode " + id + " level false");
			server.runCommand("slipway rotate " + id + " 20 10 15");
			ctx.waitTicks(20);
			server.runCommand("slipway mode " + id + " hover false");
			server.runCommand("slipway control " + id + " 1 0 0.3 0 0.2 0 200");
			server.waitFor(s -> Game.active(s, id).record.linearVelocity.length() > 1.0, 200);
			ctx.waitTicks(10);
			Shots.take(ctx, r, "01-before-quit");
			Lifecycle.SAVED.clear();
		}

		Lifecycle.Snapshot saved = Check.notNull(Lifecycle.SAVED.get(id), "the last save while quitting did not include vessel %d", id);
		r.metric("saved.speed", saved.velocity().length());
		r.metric("saved.spin", saved.angularVelocity().length());
		Check.atLeast("speed of the vessel when it was saved", saved.velocity().length(), 1.0);
		Lifecycle.LOADED.clear();

		try (TestSingleplayerContext sp = save.open()) {
			TestServerContext server = sp.getServer();
			Lifecycle.Snapshot loaded = Check.notNull(Lifecycle.LOADED.get(id), "vessel %d did not load", id);
			double position = loaded.pose().position().distanceTo(saved.pose().position());
			double dot = Math.abs(loaded.pose().qx() * saved.pose().qx() + loaded.pose().qy() * saved.pose().qy() + loaded.pose().qz() * saved.pose().qz()
				+ loaded.pose().qw() * saved.pose().qw());
			double velocity = loaded.velocity().distanceTo(saved.velocity());
			double spin = loaded.angularVelocity().distanceTo(saved.angularVelocity());
			r.metric("loaded.positionDelta", position);
			r.metric("loaded.rotationDot", dot);
			r.metric("loaded.velocityDelta", velocity);
			Check.atMost("position loaded vs saved", position, 0.002);
			Check.atLeast("rotation loaded vs saved (|quaternion dot|)", dot, 0.999999);
			Check.atMost("velocity loaded vs saved", velocity, 0.002);
			Check.atMost("angular velocity loaded vs saved", spin, 0.002);
			Check.equal("modes loaded (hover, level)", loaded.hover() + "," + loaded.level(), saved.hover() + "," + saved.level());
			Check.equal("block count loaded", loaded.blocks(), saved.blocks());

			// The physics body resumes with the saved motion. With hover and level off only gravity (and the body's
			// small damping) acts; at the end of the first tick the body exists its record has not yet taken a step.
			server.waitFor(s -> Game.manager(s).active(id) != null && Game.manager(s).active(id).hasBody, 400);
			Lifecycle.Snapshot first = Check.notNull(Lifecycle.FIRST_BODY.get(id), "vessel %d's first physics tick was not seen", id);
			double horizontal = Math.hypot(first.velocity().x - saved.velocity().x, first.velocity().z - saved.velocity().z);
			double savedHorizontal = Math.hypot(saved.velocity().x, saved.velocity().z);
			double angular = first.angularVelocity().distanceTo(saved.angularVelocity());
			r.metric("firstBody.horizontalVelocityDelta", horizontal);
			r.metric("firstBody.verticalVelocityDelta", first.velocity().y - saved.velocity().y);
			r.metric("firstBody.angularVelocityDelta", angular);
			Check.atMost("horizontal velocity at the body's first tick vs saved (blocks/s; at most one tick of damping)", horizontal, 0.05 * savedHorizontal + 0.05);
			Check.atMost("angular velocity at the body's first tick vs saved (rad/s)", angular, 0.05 * saved.angularVelocity().length() + 0.02);

			Game.waitClientReady(ctx, id, 400);
			Game.ClientView view = Game.clientView(ctx, id);
			Check.equal("blocks the client shows", view.blocks(), spec.size());
			List<String> blocks = server.computeOnServer(s -> Ships.plotMismatches(s.overworld(), record, spec));
			Check.that(blocks.isEmpty(), "blocks changed across save and reload: %s", blocks);
			Check.equal("block entities after reload", server.computeOnServer(s -> Ships.plotBlockEntityData(s.overworld(), record, spec.keySet())), data);
			// The crate hovers where it was. Loaded from the save, its own plot column can reach the client before the
			// columns around it: the server sends a column once the columns next to it are loaded too, and the join has
			// just sent some hundred chunks, after which vanilla's chunk sender waits before its next batch. Until the
			// columns around are there, the crate's west and north sides are lit by nothing: bright at first, while the
			// client has applied no light data for the plot at all (it applies what it was sent a few chunks a frame),
			// then black, then lit for good. A side that is lit therefore says nothing by itself. What must hold: within
			// twenty seconds the client has every column and has applied all light it was sent, no side is dark then,
			// and it stays so.
			Game.waitClientReady(ctx, crate, 400);
			List<Long> columns = server.computeOnServer(s -> dev.timstewart.slipway.vessel.VesselManager.plotChunks(Game.manager(s).registry().get(crate), 1));
			Check.equal("plot columns the crate's viewers get", columns.size(), 9);
			int ticks = 0;
			int dark = 0;
			int settled = 0;
			while (settled < 20) {
				int light = Game.darkestOuterSkyLight(ctx, crate);
				boolean arrived = ctx.computeOnClient(mc -> columns.stream().allMatch(c -> mc.level.getChunkSource().getChunk(net.minecraft.world.level.ChunkPos.getX(c),
					net.minecraft.world.level.ChunkPos.getZ(c), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null)) && Game.lightUpdatesQueued(ctx) == 0;
				if (arrived) {
					Check.that(settled == 0 || light >= 14, "the crate has side or top faces with sky light %d, %d ticks after every column and all light had arrived", light, settled);
					settled = light >= 14 ? settled + 1 : 0;
				} else {
					settled = 0;
				}
				if (light >= 0 && light < 14) {
					dark++;
				}
				if (ticks++ >= 400) {
					// What the light is where the dark face looks, on both sides, and whether meshing again helps.
					BlockPos anchor = server.computeOnServer(s -> Game.manager(s).registry().get(crate).anchor);
					BlockPos west = anchor.offset(-1, -1, 0);
					BlockPos north = anchor.offset(0, -1, -1);
					String client = ctx.computeOnClient(mc -> {
						ClientVessel v = ClientVessels.get(crate);
						StringBuilder out = new StringBuilder("mesh west/east/north/south/up ");
						for (net.minecraft.core.Direction side : new net.minecraft.core.Direction[] {net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST,
							net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.UP}) {
							out.append(v.mesh.minSkyLight(side)).append(' ');
						}
						out.append("pending ").append(v.mesh.hasPendingWork());
						for (BlockPos pos : new BlockPos[] {west, north}) {
							net.minecraft.world.level.chunk.DataLayer layer = mc.level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY)
								.getDataLayerData(net.minecraft.core.SectionPos.of(pos));
							out.append("; client sky light at ").append(pos.toShortString()).append(' ').append(mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos))
								.append(" (layer ").append(layer == null ? "none" : layer.isEmpty() ? "empty" : "data").append(')');
						}
						v.mesh.markAllDirty();
						return out.toString();
					});
					String onServer = server.computeOnServer(s -> "server sky light west " + s.overworld().getBrightness(net.minecraft.world.level.LightLayer.SKY, west)
						+ ", north " + s.overworld().getBrightness(net.minecraft.world.level.LightLayer.SKY, north));
					ctx.waitTicks(5);
					throw new AssertionError(String.format(java.util.Locale.ROOT, "400 ticks after loading, the crate's darkest side or top face has sky light %d (-1: no mesh); "
						+ "every column and all light arrived: %s; %s; %s; meshed again: %d", light, arrived, client, onServer, Game.darkestOuterSkyLight(ctx, crate)));
				}
				ctx.waitTick();
			}
			r.metric("crate.ticksUntilEverySideStaysLit", ticks - settled);
			r.metric("crate.darkTicksAfterLoading", dark);
			Shots.take(ctx, r, "02-after-reload");
		}
	}
}
