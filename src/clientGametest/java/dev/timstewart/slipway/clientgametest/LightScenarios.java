package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.SlipwayDebug;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * small-vessel-light: small vessels are lit on every side. A vessel's helm stands on a chunk corner of its plot, and
 * a face is lit by the block next to it: for a build that begins at its helm towards +x and +z, the blocks next to
 * its west and north faces lie in plot columns that hold none of its blocks. Viewers get those columns too (the ring
 * round the vessel's own); as long as they did not, such faces were drawn black.
 *
 * <p>Three vessels in daylight, without shaders: a two by two by two crate of white wool under its helm towards +x
 * and +z; the same crate round its helm (in all four columns at the corner), which was never affected; and a build at
 * the corner with glowstone and a chest. Checked: the crate's west and east sides, and its north and south sides,
 * are equally bright in pictures taken square on from both sides; no face of any of them that looks sideways or up
 * has less than full sky light at any vertex (with smooth lighting a vertex blends the light of four blocks, the
 * corner ones from the column diagonally across); the glowstone's light reaches the west and north faces beside it
 * (through the neighbouring columns: they hold real light, not a stand-in); the chest at the edge is drawn lit; and
 * when a vessel grows to another chunk border, by a block set at the end of its own column, by one a column further
 * out, and by a piston that pushes a block to the end of its column, the faces there are lit as well.
 */
final class LightScenarios {
	private LightScenarios() {
	}

	private static final Direction[] SIDES_AND_TOP = {Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.UP};

	private static BlockState wool() {
		return Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState();
	}

	/** A crate of wool two blocks each way under a helm, from the given corner (relative to the helm). */
	private static Map<BlockPos, BlockState> crate(int fromX, int fromZ) {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		for (BlockPos p : BlockPos.betweenClosed(fromX, -2, fromZ, fromX + 1, -1, fromZ + 1)) {
			blocks.put(p.immutable(), wool());
		}
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}

	static final BlockPos LIT_CHEST = new BlockPos(0, 0, 1);

	/** A build at the corner with a lamp in it: wool and glowstone under the helm, a chest beside the helm on the glowstone. */
	private static Map<BlockPos, BlockState> lit() {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		blocks.put(new BlockPos(0, -1, 0), wool());
		blocks.put(new BlockPos(0, -1, 1), Blocks.GLOWSTONE.defaultBlockState());
		blocks.put(new BlockPos(1, -1, 0), wool());
		blocks.put(new BlockPos(1, -1, 1), wool());
		blocks.put(LIT_CHEST, Blocks.CHEST.defaultBlockState());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}

	/**
	 * A row of wool from under the helm to x 12, a piston at x 13 that looks east and a block of wool before it at
	 * x 14: one push takes that block to x 15, the last of the chunk column.
	 */
	private static Map<BlockPos, BlockState> pusher() {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		for (int x = 0; x <= 12; x++) {
			blocks.put(new BlockPos(x, -1, 0), wool());
		}
		blocks.put(new BlockPos(13, -1, 0), Blocks.PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, Direction.EAST));
		blocks.put(new BlockPos(14, -1, 0), wool());
		blocks.put(BlockPos.ZERO, Ships.helm(Direction.NORTH));
		return blocks;
	}

	/** The lowest sky light and block light baked into the faces of a vessel that look each way: "west 15/0, ...". */
	private static String meshLight(ClientGameTestContext ctx, long id) {
		return ctx.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder();
			for (Direction side : SIDES_AND_TOP) {
				var mesh = ClientVessels.get(id).mesh;
				out.append(out.isEmpty() ? "" : ", ").append(side.getName()).append(' ').append(mesh.minSkyLight(side)).append('/').append(mesh.minBlockLight(side));
			}
			return out.toString();
		});
	}

	private static void expectSkyLit(ClientGameTestContext ctx, Report.Result r, long id, String what) {
		r.note("%s: lowest sky/block light of the faces looking %s", what, meshLight(ctx, id));
		for (Direction side : SIDES_AND_TOP) {
			int sky = ctx.computeOnClient(mc -> ClientVessels.get(id).mesh.minSkyLight(side));
			Check.that(sky == 15, "%s: a face looking %s is drawn with sky light %d at a vertex (15 under the open sky; -1: no such face)", what, side.getName(), sky);
		}
	}

	/** One block of a vessel: its faces that look the given ways are in the mesh and have full sky light at every vertex. */
	private static void expectBlockSkyLit(ClientGameTestContext ctx, long id, BlockPos local, String what, Direction... sides) {
		for (Direction side : sides) {
			int sky = ctx.computeOnClient(mc -> ClientVessels.get(id).mesh.minSkyLight(side, local));
			Check.that(sky == 15, "%s: its face looking %s is drawn with sky light %d at a vertex (15 under the open sky; -1: no such face)", what, side.getName(), sky);
		}
	}

	/** The crate seen square on from one side, 6.5 blocks from that side: the mean luminance (0 to 255) of the middle of the picture. */
	private static double brightnessFrom(ClientGameTestContext ctx, TestSingleplayerContext sp, Report.Result r, BlockPos helm, Direction side, String name) {
		Vec3 middle = new Vec3(helm.getX() + 1.0, helm.getY() - 1.0, helm.getZ() + 1.0);
		LooseScenarios.watch(ctx, sp, middle.add(side.getStepX() * 7.5, -1.62, side.getStepZ() * 7.5), middle);
		Shots.waitStill(ctx, r, name, 1200);
		Path shot = Shots.take(ctx, r, name);
		try (NativeImage image = Shots.load(shot)) {
			// The side is about 105 pixels high in a picture 480 high: a twentieth of the width round the middle is well inside it.
			int w = image.getWidth() / 20;
			int h = image.getHeight() / 12;
			double luminance = Shots.mean(image, (image.getWidth() - w) / 2, (image.getHeight() - h) / 2, w, h)[3];
			r.metric("picture.luminance." + side.getName(), luminance);
			return luminance;
		}
	}

	private static int westSky(ClientGameTestContext ctx, long id) {
		return ctx.computeOnClient(mc -> ClientVessels.get(id).mesh.minSkyLight(Direction.WEST));
	}

	static void smallVesselLight(ClientGameTestContext ctx, Report.Result r) {
		try (TestSingleplayerContext sp = Game.creativeWorld(ctx)) {
			TestServerContext server = sp.getServer();
			BlockPos crateHelm = new BlockPos(0, Game.GROUND_Y + 30, 40);
			BlockPos controlHelm = crateHelm.offset(12, 0, 0);
			BlockPos litHelm = crateHelm.offset(0, 0, 12);
			BlockPos pusherHelm = crateHelm.offset(0, 0, 24);
			server.runOnServer(s -> {
				Ships.build(s.overworld(), crateHelm, crate(0, 0));
				Ships.build(s.overworld(), controlHelm, crate(-1, -1));
				Ships.build(s.overworld(), litHelm, lit());
				Ships.build(s.overworld(), pusherHelm, pusher());
			});
			long crate = server.computeOnServer(s -> Ships.assemble(s.overworld(), crateHelm).id);
			long control = server.computeOnServer(s -> Ships.assemble(s.overworld(), controlHelm).id);
			VesselRecord litRecord = server.computeOnServer(s -> Ships.assemble(s.overworld(), litHelm));
			long lit = litRecord.id;
			VesselRecord pusherRecord = server.computeOnServer(s -> Ships.assemble(s.overworld(), pusherHelm));
			long pusher = pusherRecord.id;
			Game.hud(ctx, false);
			// All of them in view from the north-west: a vessel's mesh is built when it is drawn.
			LooseScenarios.watch(ctx, sp, new Vec3(crateHelm.getX() - 7.5, crateHelm.getY() + 4.0, crateHelm.getZ() - 7.5),
				new Vec3(crateHelm.getX() + 6.0, crateHelm.getY() - 1.0, crateHelm.getZ() + 6.0));
			for (long id : new long[] {crate, control, lit, pusher}) {
				Game.waitClientReady(ctx, id, 200);
				Game.waitClientComplete(ctx, id, 400);
			}
			ctx.runOnClient(mc -> SlipwayDebug.blockEntitiesStart(lit));
			Shots.waitStill(ctx, r, "overview", 1200);
			Shots.take(ctx, r, "01-three-small-vessels");
			Map<BlockPos, Integer> blockEntityLight = ctx.computeOnClient(mc -> {
				Map<BlockPos, Integer> light = SlipwayDebug.blockEntityLight();
				SlipwayDebug.blockEntitiesStop();
				return light;
			});

			// The picture: opposite sides of the crate are shaded alike by the game (west and east, north and south),
			// so with the same light they are equally bright. A side lit with sky light 0 is nearly black.
			double west = brightnessFrom(ctx, sp, r, crateHelm, Direction.WEST, "02-crate-from-the-west");
			double east = brightnessFrom(ctx, sp, r, crateHelm, Direction.EAST, "03-crate-from-the-east");
			double north = brightnessFrom(ctx, sp, r, crateHelm, Direction.NORTH, "04-crate-from-the-north");
			double south = brightnessFrom(ctx, sp, r, crateHelm, Direction.SOUTH, "05-crate-from-the-south");
			r.metric("picture.westOverEast", west / east);
			r.metric("picture.northOverSouth", north / south);
			Check.that(east > 60 && south > 60, "the crate's east and south sides are not bright in daylight: luminance %.1f and %.1f of 255", east, south);
			Check.near("brightness of the crate's west side as a share of its east side's", west / east, 1.0, 0.1);
			Check.near("brightness of the crate's north side as a share of its south side's", north / south, 1.0, 0.1);

			// The mesh: full sky light at every vertex of every face that looks sideways or up.
			LooseScenarios.watch(ctx, sp, new Vec3(crateHelm.getX() - 7.5, crateHelm.getY() + 4.0, crateHelm.getZ() - 7.5),
				new Vec3(crateHelm.getX() + 6.0, crateHelm.getY() - 1.0, crateHelm.getZ() + 6.0));
			expectSkyLit(ctx, r, crate, "crate from its helm towards +x and +z");
			expectSkyLit(ctx, r, control, "crate round its helm");
			expectSkyLit(ctx, r, lit, "build with glowstone at the corner");
			// The glowstone's light on the faces beside it at the edge. The blocks next to those faces are in the
			// neighbouring columns: the wool's west face is lit by a block two steps from the glowstone (13), its
			// north face by one four steps away (11), and smooth lighting blends in blocks one step further.
			int westBlock = ctx.computeOnClient(mc -> ClientVessels.get(lit).mesh.minBlockLight(Direction.WEST));
			int northBlock = ctx.computeOnClient(mc -> ClientVessels.get(lit).mesh.minBlockLight(Direction.NORTH));
			r.metric("lit.lowestBlockLightOfWestFaces", westBlock);
			r.metric("lit.lowestBlockLightOfNorthFaces", northBlock);
			Check.atLeast("lowest block light on the west faces of the build with glowstone", westBlock, 10);
			Check.atLeast("lowest block light on the north faces of the build with glowstone", northBlock, 9);
			// A block entity is lit by its own place, which is in the vessel's own column.
			Integer chest = Check.notNull(blockEntityLight.get(LIT_CHEST), "the chest at the edge was not drawn (drawn: %s)", blockEntityLight.keySet());
			r.metric("lit.chestSkyLight", LightCoordsUtil.sky(chest));
			r.metric("lit.chestBlockLight", LightCoordsUtil.block(chest));
			Check.that(LightCoordsUtil.sky(chest) == 15 && LightCoordsUtil.block(chest) >= 13, "the chest at the edge is drawn with sky light %d and block light %d",
				LightCoordsUtil.sky(chest), LightCoordsUtil.block(chest));

			// Growing to another chunk border. A block at the east end of the vessel's own column: the block next to
			// its east face is in a column viewers have had from the start.
			BlockPos eastEdge = new BlockPos(15, -1, 0);
			server.runOnServer(s -> s.overworld().setBlock(litRecord.toPlot(eastEdge), wool(), 3));
			Game.waitClientComplete(ctx, lit, 200);
			Check.that(ctx.computeOnClient(mc -> mc.level.getBlockState(litRecord.toPlot(eastEdge)).is(wool().getBlock())), "the client lacks the block at the east end of the column");
			expectSkyLit(ctx, r, lit, "the same after a block at the east end of its column");
			expectBlockSkyLit(ctx, lit, eastEdge, "the block at the east end of the column", Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.UP);
			// A block at the west end of the next column: the vessel now has that column, and the block next to the
			// new block's west face is in a column that is new to viewers and comes when it has loaded.
			BlockPos westEdge = new BlockPos(-16, -1, 0);
			server.runOnServer(s -> s.overworld().setBlock(litRecord.toPlot(westEdge), wool(), 3));
			ctx.waitFor(mc -> mc.level.getBlockState(litRecord.toPlot(westEdge)).is(wool().getBlock()), 200);
			Game.waitClientComplete(ctx, lit, 200);
			int waited = 0;
			for (int sky = westSky(ctx, lit); sky < 15; sky = westSky(ctx, lit)) {
				Check.that(waited++ < 100, "after a block at the west end of the next column the west faces still have sky light %d", sky);
				ctx.waitTick();
			}
			r.metric("growth.ticksUntilTheFarWestFaceWasLit", waited);
			Check.equal("local x the vessel's bounds reach in the west", server.computeOnServer(s -> Game.active(s, lit).record.localMin.getX()), -16);
			expectSkyLit(ctx, r, lit, "the same after a block at the west end of the next column");
			expectBlockSkyLit(ctx, lit, westEdge, "the block at the west end of the next column", Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.UP);
			// A piston's push: the block of wool before it goes to the east end of the column.
			expectSkyLit(ctx, r, pusher, "row with a piston");
			BlockPos pushed = new BlockPos(15, -1, 0);
			server.runOnServer(s -> s.overworld().setBlock(pusherRecord.toPlot(new BlockPos(13, 0, 0)), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
			server.waitFor(s -> s.overworld().getBlockState(pusherRecord.toPlot(pushed)).is(wool().getBlock()), 60);
			ctx.waitFor(mc -> mc.level.getBlockState(pusherRecord.toPlot(pushed)).is(wool().getBlock()), 60);
			Game.waitClientComplete(ctx, pusher, 200);
			Check.equal("local x the pushed block took the vessel's bounds to", server.computeOnServer(s -> Game.active(s, pusher).record.localMax.getX()), 15);
			// The pushed block's west face is behind the piston's head. (The extended piston itself is no full block
			// and is lit by its own place, as it is in the world: sky light 14 under the redstone block.)
			r.note("row with a piston after the push: lowest sky/block light of the faces looking %s", meshLight(ctx, pusher));
			expectBlockSkyLit(ctx, pusher, pushed, "the block a piston pushed to the east end of its column", Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.UP);
			Shots.take(ctx, r, "06-grown-to-chunk-borders");
			Game.hud(ctx, true);
		}
	}
}
