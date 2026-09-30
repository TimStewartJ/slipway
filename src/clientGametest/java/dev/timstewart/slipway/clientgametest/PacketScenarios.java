package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.net.ServerPackets;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.vessel.HelmInput;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * forged-packets: a client sends what a modified client could. Helm control from a player not at that helm, for
 * another vessel or for no vessel, with NaN or infinite axes, and in a 200-packet burst is refused (counted, input
 * untouched, rate limited to 40 a second); out-of-range axes are clamped. Block use and break packets aimed at a
 * vessel out of reach do nothing, while the same packets within reach work. The client stays connected throughout.
 */
final class PacketScenarios {
	private PacketScenarios() {
	}

	static void forgedPackets(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos helmA = new BlockPos(0, Game.GROUND_Y + 20, 40);
			BlockPos helmB = new BlockPos(40, Game.GROUND_Y + 20, 40);
			Map<BlockPos, BlockState> deck = Ships.deck(3, Blocks.OAK_PLANKS.defaultBlockState());
			deck.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
			server.runOnServer(s -> {
				Ships.build(s.overworld(), helmA, deck);
				Ships.build(s.overworld(), helmB, deck);
			});
			VesselRecord a = server.computeOnServer(s -> Ships.assemble(s.overworld(), helmA));
			VesselRecord b = server.computeOnServer(s -> Ships.assemble(s.overworld(), helmB));
			Game.waitClientReady(ctx, a.id, 100);
			DeckScenarios.placeRider(ctx, sp, server, a.id, new Vec3(-1.5, 0.3, 1.5));

			// Not at a helm.
			long base = rejected(server);
			send(ctx, sp, new SlipwayPayloads.HelmControl(a.id, 1, 1f, 0f, 0f, 0f, 0f, 0f, (byte)0));
			Check.equal("refusals after helm control from a player not at the helm", rejected(server) - base, 1L);
			Check.that(input(server, a.id).isIdle(), "vessel A took input from a player not at its helm");

			// At A's helm: another vessel, and a vessel that does not exist.
			Flight.takeHelm(ctx, server, a.id);
			base = rejected(server);
			send(ctx, sp, new SlipwayPayloads.HelmControl(b.id, 2, 1f, 0f, 0f, 0f, 0f, 0f, (byte)0));
			send(ctx, sp, new SlipwayPayloads.HelmControl(987654L, 3, 1f, 0f, 0f, 0f, 0f, 0f, (byte)0));
			Check.equal("refusals after control for another vessel and for no vessel", rejected(server) - base, 2L);
			Check.that(input(server, b.id).isIdle(), "vessel B took input from A's pilot");

			// Non-finite axes are refused; out-of-range axes are clamped.
			base = rejected(server);
			send(ctx, sp, new SlipwayPayloads.HelmControl(a.id, 4, Float.NaN, 0f, 0f, 0f, 0f, 0f, (byte)0));
			send(ctx, sp, new SlipwayPayloads.HelmControl(a.id, 5, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 0f, (byte)0));
			send(ctx, sp, new SlipwayPayloads.HelmControl(a.id, 6, 0f, 0f, 0f, 0f, 0f, Float.NEGATIVE_INFINITY, (byte)0));
			Check.equal("refusals after NaN, +Infinity and -Infinity axes", rejected(server) - base, 3L);
			base = rejected(server);
			SlipwayPayloads.HelmControl outOfRange = new SlipwayPayloads.HelmControl(a.id, 7, 5f, -7f, 0.5f, 1.0e6f, -3f, 0f, (byte)0);
			send(ctx, sp, outOfRange);
			Check.equal("refusals after out-of-range axes", rejected(server) - base, 0L);
			// What it sets, read in the same server step that handles it (the pilot's own client resends its idle
			// axes every 10 ticks, which would otherwise race this read).
			HelmInput clamped = server.computeOnServer(s -> {
				String refusal = ServerPackets.handleHelmControl(Game.player(s), outOfRange);
				Check.that(refusal == null, "out-of-range axes were refused: %s", refusal);
				return Game.active(s, a.id).input.copy();
			});
			String axes = String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f", clamped.forward, clamped.strafe, clamped.vertical, clamped.pitch,
				clamped.yaw, clamped.roll);
			Check.equal("clamped axes", axes, "1.00,-1.00,0.50,1.00,-1.00,0.00");
			send(ctx, sp, new SlipwayPayloads.HelmControl(a.id, 8, 0f, 0f, 0f, 0f, 0f, 0f, (byte)0));

			// A burst: at most 40 control packets a second are accepted (the pilot's own idle resends share the window).
			ctx.waitTicks(25);
			base = rejected(server);
			ctx.runOnClient(mc -> {
				for (int i = 0; i < 200; i++) {
					ClientPlayNetworking.send(new SlipwayPayloads.HelmControl(a.id, 1000 + i, 0.1f, 0f, 0f, 0f, 0f, 0f, (byte)0));
				}
			});
			sp.getConnection().waitForServerboundPackets();
			long burst = rejected(server) - base;
			r.metric("burst.refused", burst);
			Check.that(burst >= 160 && burst <= 205, "a burst of 200 control packets had %d refused; expected at least 160 (40 a second allowed)", burst);
			send(ctx, sp, new SlipwayPayloads.HelmControl(a.id, 2000, 0f, 0f, 0f, 0f, 0f, 0f, (byte)0));

			// Block use and break aimed at vessel B, 40 blocks away, are refused; the same at A (within reach) work.
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitFor(mc -> Flight.ridingVessel(mc) == -1, 40);
			ctx.getInput().releaseKey(o -> o.keyShift);
			server.runOnServer(s -> Game.player(s).getInventory().setItem(Game.player(s).getInventory().getSelectedSlot(), new ItemStack(Items.STONE, 16)));
			ctx.waitTicks(2);
			BlockPos farDeck = b.toPlot(new BlockPos(2, -1, 2));
			BlockPos farAbove = farDeck.above();
			forgeUse(ctx, sp, farDeck, Direction.UP);
			Check.that(server.computeOnServer(s -> s.overworld().getBlockState(farAbove).isAir()), "a forged use on vessel B out of reach placed a block");
			forgeBreak(ctx, sp, farDeck);
			Check.that(server.computeOnServer(s -> s.overworld().getBlockState(farDeck).is(Blocks.OAK_PLANKS)), "a forged break on vessel B out of reach broke its deck");
			BlockPos nearDeck = a.toPlot(new BlockPos(1, -1, -2));
			forgeUse(ctx, sp, nearDeck, Direction.UP);
			Check.that(server.computeOnServer(s -> s.overworld().getBlockState(nearDeck.above()).is(Blocks.STONE)), "the same use packet within reach (vessel A) did not place stone");
			forgeBreak(ctx, sp, nearDeck.above());
			Check.that(server.computeOnServer(s -> s.overworld().getBlockState(nearDeck.above()).isAir()), "the same break packet within reach (vessel A) did not break the stone");

			Check.that(ctx.computeOnClient(mc -> mc.level != null && mc.getConnection() != null && mc.getConnection().getConnection().isConnected()),
				"the client was disconnected by the forged packets");
			r.metric("refusedTotal", rejected(server));
		}
	}

	static long rejected(TestServerContext server) {
		return server.computeOnServer(s -> ServerPackets.rejectedCount());
	}

	static HelmInput input(TestServerContext server, long id) {
		return server.computeOnServer(s -> Game.active(s, id).input.copy());
	}

	static void send(ClientGameTestContext ctx, TestSingleplayerContext sp, SlipwayPayloads.HelmControl payload) {
		ctx.runOnClient(mc -> ClientPlayNetworking.send(payload));
		sp.getConnection().waitForServerboundPackets();
	}

	static void forgeUse(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos pos, Direction face) {
		Vec3 location = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(location, face, pos, false), 900)));
		sp.getConnection().waitForServerboundPackets();
		ctx.waitTicks(2);
	}

	static void forgeBreak(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos pos) {
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, 901)));
		sp.getConnection().waitForServerboundPackets();
		ctx.waitTicks(2);
	}
}
