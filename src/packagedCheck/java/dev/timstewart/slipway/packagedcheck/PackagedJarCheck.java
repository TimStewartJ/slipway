package dev.timstewart.slipway.packagedcheck;

import com.google.gson.GsonBuilder;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * The packaged-jar check: the release jar with the exact play-stack jars in production Minecraft. Every mixin of every
 * mod must apply (Mixin's audit loads every target class), the expected mods and versions must be present, Mixin and
 * Fabric Loader must report no error, and Slipway must work: a world opens, a ship assembles and the client shows it,
 * a block event of a vessel block reaches the client (a chest's lid opens), and a vessel set loose falls on both
 * sides. The result is written as JSON to {@code slipway.packagedCheck.report}.
 */
public final class PackagedJarCheck implements FabricClientGameTest {
	private static final BlockPos CHEST = new BlockPos(1, 0, 1);
	private static final List<String> EXPECTED = modList("slipway.packagedCheck.expectedMods", "slipway,fabric-api,sodium,iris,distanthorizons");
	private static final List<String> ABSENT = modList("slipway.packagedCheck.absentMods", "");

	private static List<String> modList(String property, String fallback) {
		return Arrays.stream(System.getProperty(property, fallback).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		Map<String, Object> report = new LinkedHashMap<>();
		try {
			Map<String, String> mods = new LinkedHashMap<>();
			for (String id : EXPECTED) {
				ModContainer mod = FabricLoader.getInstance().getModContainer(id).orElseThrow(() -> new AssertionError(id + " is not loaded"));
				mods.put(id, mod.getMetadata().getVersion().getFriendlyString());
			}
			for (String id : ABSENT) {
				if (FabricLoader.getInstance().isModLoaded(id)) {
					throw new AssertionError(id + " is loaded but this run checks Slipway without it");
				}
			}
			report.put("mods", mods);
			report.put("absentMods", ABSENT);
			long start = System.nanoTime();
			ctx.runOnClient(mc -> MixinEnvironment.getCurrentEnvironment().audit());
			report.put("mixinAuditSeconds", Math.round((System.nanoTime() - start) / 1.0e8) / 10.0);

			try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
				BlockPos helm = new BlockPos(0, -50, 16);
				long id = sp.getServer().computeOnServer(server -> {
					var level = server.overworld();
					for (int x = -2; x <= 2; x++) {
						for (int z = -2; z <= 2; z++) {
							level.setBlock(helm.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState(), 2 | 16);
						}
					}
					level.setBlock(helm, SlipwayRegistry.HELM.defaultBlockState().setValue(HelmBlock.FACING, Direction.NORTH), 2 | 16);
					level.setBlock(helm.offset(CHEST), Blocks.CHEST.defaultBlockState(), 2 | 16);
					VesselAssembly.Outcome outcome = VesselManager.get(level).assemble(helm, null);
					if (!outcome.success() || outcome.record() == null) {
						throw new AssertionError("assembly failed in production: " + outcome.message().getString());
					}
					return outcome.record().id;
				});
				ctx.waitFor(mc -> ClientVessels.get(id) != null && ClientVessels.get(id).ready(), 400);
				report.put("vesselAssembled", id);

				// A block event at a vessel block: the server says "lid open", the client's chest in the plot opens.
				BlockPos plotChest = sp.getServer().computeOnServer(server -> {
					var level = server.overworld();
					BlockPos chest = VesselManager.get(level).active(id).record.toPlot(CHEST);
					level.blockEvent(chest, Blocks.CHEST, 1, 1);
					return chest;
				});
				ctx.waitFor(mc -> mc.level.getBlockEntity(plotChest) instanceof ChestBlockEntity chest && chest.getOpenNess(1f) > 0.5f, 200);
				report.put("chestLidOpenedByBlockEvent", true);

				// Loose: no hover any more, so the vessel falls, and the client is told and follows.
				double startY = ctx.computeOnClient(mc -> ClientVessels.get(id).tickPose().y());
				sp.getServer().runOnServer(server -> VesselManager.get(server.overworld()).active(id).record.loose = true);
				ctx.waitFor(mc -> ClientVessels.get(id).loose && ClientVessels.get(id).tickPose().y() < startY - 1.0, 200);
				report.put("looseVesselFellBlocksOnTheClient", Math.round((startY - ctx.computeOnClient(mc -> ClientVessels.get(id).tickPose().y())) * 100.0) / 100.0);
			}

			report.put("mixinOrLoaderErrors", PackagedCheckPreLaunch.PROBLEMS);
			report.put("mixinOrLoaderWarnings", PackagedCheckPreLaunch.WARNINGS);
			if (!PackagedCheckPreLaunch.PROBLEMS.isEmpty()) {
				throw new AssertionError("Mixin or Fabric Loader reported errors: " + PackagedCheckPreLaunch.PROBLEMS);
			}
			report.put("result", "pass");
		} catch (Throwable t) {
			report.put("result", "fail");
			report.put("failure", t.toString());
			throw t;
		} finally {
			write(report);
		}
	}

	private static void write(Map<String, Object> report) {
		String path = System.getProperty("slipway.packagedCheck.report");
		if (path == null) {
			return;
		}
		try {
			Path file = Path.of(path);
			Files.createDirectories(file.getParent());
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new AssertionError("cannot write the packaged-jar check report", e);
		}
	}
}
