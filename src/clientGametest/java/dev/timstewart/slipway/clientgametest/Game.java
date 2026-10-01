package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselManager;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Shared steps for the client GameTests: worlds, options, keys, and reading vessels on the server and the client. */
final class Game {
	/** The superflat test world's grass surface: players stand at this y. */
	static final int GROUND_Y = -60;

	private Game() {
	}

	/**
	 * Test options that keep runs repeatable and light: no clouds (they drift; Distant Horizons draws its own LOD
	 * clouds whatever vanilla's option says), no chat in shots, capped frame rate.
	 */
	static void applyTestOptions(ClientGameTestContext ctx) {
		ctx.restoreDefaultGameOptions();
		clientName = ctx.computeOnClient(mc -> mc.getUser().getName());
		options(ctx, o -> {
			o.cloudStatus().set(CloudStatus.OFF);
			o.framerateLimit().set(60);
			o.enableVsync().set(false);
			o.pauseOnLostFocus = false;
			o.renderDistance().set(8);
			o.simulationDistance().set(8);
			o.chatVisibility().set(ChatVisiblity.HIDDEN);
			// the tests give no input for minutes at a time: the game must not drop to its idle frame rate
			o.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
		});
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			ctx.runOnClient(mc -> DhTestOptions.noClouds());
		}
		hud(ctx, true);
	}

	/** Kept apart so Distant Horizons' classes load only when it is present. */
	private static final class DhTestOptions {
		static void noClouds() {
			var configs = Check.notNull(com.seibel.distanthorizons.api.DhApi.Delayed.configs, "Distant Horizons' API configs are not available");
			var clouds = configs.graphics().genericRendering().cloudRenderingEnabled();
			if (clouds.getValue()) {
				Check.that(clouds.setValue(false), "Distant Horizons refused to turn its clouds off");
			}
		}
	}

	/** Shows or hides the HUD (what F1 toggles). */
	static void hud(ClientGameTestContext ctx, boolean visible) {
		ctx.runOnClient(mc -> {
			if (mc.gui.hud.isHidden() == visible) {
				mc.gui.hud.toggle();
			}
		});
	}

	static void options(ClientGameTestContext ctx, Consumer<Options> change) {
		ctx.runOnClient(mc -> {
			change.accept(mc.options);
			mc.options.save();
		});
	}

	/** A new creative superflat world (the API's consistent settings: seed 1, no structures, time and weather frozen). */
	static TestSingleplayerContext creativeWorld(ClientGameTestContext ctx) {
		return ctx.worldBuilder().adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create();
	}

	/** A new survival superflat world with commands allowed. */
	static TestSingleplayerContext survivalWorld(ClientGameTestContext ctx) {
		return ctx.worldBuilder().adjustSettings(s -> {
			s.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
			s.setAllowCommands(true);
		}).create();
	}

	/** The name of this test client's player (set when a scenario starts), to tell it apart from other players. */
	static volatile String clientName;

	/** This client's player on the server (the only player in singleplayer). */
	static ServerPlayer player(MinecraftServer server) {
		ServerPlayer own = clientName == null ? null : server.getPlayerList().getPlayerByName(clientName);
		if (own != null) {
			return own;
		}
		return Check.notNull(server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst(), "no player on the server");
	}

	static ServerPlayer player(MinecraftServer server, String name) {
		return Check.notNull(server.getPlayerList().getPlayerByName(name), "player %s is not on the server", name);
	}

	/** Teleports the (only) player and waits until the client has the new position and the terrain around it is drawn. */
	static void teleport(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double y, double z, float yaw, float pitch) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.stopRiding();
			p.teleportTo(server.overworld(), x, y, z, java.util.Set.of(), yaw, pitch, true);
		});
		ctx.waitFor(mc -> mc.player != null && mc.player.position().distanceToSqr(x, y, z) < 0.01, 100);
		waitChunks(ctx, 1200);
		waitTerrain(ctx, 1200);
	}

	/**
	 * Waits until the client has every chunk within the view circle around the player. (Fabric's waitForChunksDownload
	 * asks for the whole square around the view centre, but servers send a circle, so its corners may never come.)
	 */
	static void waitChunks(ClientGameTestContext ctx, int timeout) {
		ctx.waitFor(mc -> {
			if (mc.level == null || mc.player == null) {
				return false;
			}
			int r = Math.max(1, mc.options.getEffectiveRenderDistance() - 1);
			int cx = net.minecraft.core.SectionPos.blockToSectionCoord(mc.player.getBlockX());
			int cz = net.minecraft.core.SectionPos.blockToSectionCoord(mc.player.getBlockZ());
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx * dx + dz * dz <= r * r
						&& mc.level.getChunkSource().getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) {
						return false;
					}
				}
			}
			return true;
		}, timeout);
	}

	/**
	 * Waits until the terrain renderer has built everything in view. Fabric's waitForChunksRender asks vanilla's
	 * renderer, which Sodium replaces (it would never report done), so this asks Sodium when it is present.
	 */
	static void waitTerrain(ClientGameTestContext ctx, int timeout) {
		boolean sodium = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
		ctx.waitFor(mc -> mc.level != null && (sodium ? SodiumTerrain.complete() : mc.levelRenderer.hasRenderedAllSections()), timeout);
	}

	/** Kept apart so Sodium's classes load only when Sodium is present. */
	private static final class SodiumTerrain {
		static boolean complete() {
			var renderer = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
			return renderer != null && renderer.isTerrainRenderComplete();
		}
	}

	static <T> T onServer(TestServerContext server, Function<MinecraftServer, T> function) {
		return server.computeOnServer(function::apply);
	}

	static VesselManager manager(MinecraftServer server) {
		return VesselManager.get(server.overworld());
	}

	static ActiveVessel active(MinecraftServer server, long id) {
		return Check.notNull(manager(server).active(id), "vessel %d is not active on the server", id);
	}

	/** The server's pose of a vessel. */
	static VesselPose serverPose(TestServerContext server, long id) {
		return server.computeOnServer(s -> active(s, id).record.pose);
	}

	/** Snapshot of what the client knows about a vessel (null when it does not know it or it is not ready). */
	record ClientView(long id, VesselPose pose, int blocks, int vertices, boolean hover, boolean level, boolean hasBody, Vec3 velocity) {
		double[] attitude() {
			return this.pose.attitudeDegrees();
		}
	}

	static ClientView clientView(ClientGameTestContext ctx, long id) {
		return ctx.computeOnClient(mc -> {
			ClientVessel v = ClientVessels.get(id);
			if (v == null || !v.ready()) {
				return null;
			}
			return new ClientView(id, v.tickPose(), v.blocks, v.mesh.vertexCount(), v.hover, v.level, v.hasBody, v.velocity);
		});
	}

	/** Waits until the client knows the vessel and has its pose (its mesh is built when it is first drawn). */
	static void waitClientReady(ClientGameTestContext ctx, long id, int timeout) {
		ctx.waitFor(mc -> {
			ClientVessel v = ClientVessels.get(id);
			return v != null && v.ready();
		}, timeout);
	}

	/**
	 * The lowest sky light (0 to 15) baked into the faces of a vessel's mesh that look sideways or up, or -1 while it
	 * has no such face. For a vessel under the open sky of its plot that is 15, unless the client lacks the plot
	 * columns next to the vessel's own (a face is lit by the block next to it).
	 */
	static int darkestOuterSkyLight(ClientGameTestContext ctx, long id) {
		return ctx.computeOnClient(mc -> {
			ClientVessel v = ClientVessels.get(id);
			if (v == null || !v.ready()) {
				return -1;
			}
			int darkest = 15;
			for (net.minecraft.core.Direction side : new net.minecraft.core.Direction[] {net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST,
				net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.UP}) {
				darkest = Math.min(darkest, v.mesh.minSkyLight(side));
			}
			return darkest;
		});
	}

	/** Waits until the vessel's mesh has been built (it must be in view). */
	static void waitClientMesh(ClientGameTestContext ctx, long id, int timeout) {
		ctx.waitFor(mc -> {
			ClientVessel v = ClientVessels.get(id);
			return v != null && v.ready() && v.mesh.vertexCount() > 0;
		}, timeout);
	}

	/**
	 * Waits until the client draws the whole vessel: every one of its blocks has arrived in the client's plot, the mesh
	 * has nothing left to build, and that holds for five ticks in a row (light data can arrive a little later and
	 * rebuild sections). The vessel must be in view (meshes are built when drawn).
	 */
	static void waitClientComplete(ClientGameTestContext ctx, long id, int timeout) {
		int[] stable = {0};
		ctx.waitFor(mc -> {
			ClientVessel v = ClientVessels.get(id);
			boolean complete = v != null && v.ready() && v.mesh.vertexCount() > 0 && !v.mesh.hasPendingWork() && plotBlocks(mc, v) == v.blocks;
			stable[0] = complete ? stable[0] + 1 : 0;
			return stable[0] >= 5;
		}, timeout);
	}

	private static int plotBlocks(Minecraft mc, ClientVessel v) {
		int count = 0;
		for (net.minecraft.core.BlockPos local : net.minecraft.core.BlockPos.betweenClosed(v.localMin, v.localMax)) {
			if (!mc.level.getBlockState(v.anchor.offset(local)).isAir()) {
				count++;
			}
		}
		return count;
	}

	/** A key binding by its translation name, including mod bindings such as "key.slipway.pitch_up". */
	static KeyMapping key(ClientGameTestContext ctx, String name) {
		return ctx.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings).filter(k -> k.getName().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("no key binding " + name)));
	}

	/** Holds a key for some ticks, then releases it (a real keyboard event path, as a player would press it). */
	static void holdKey(ClientGameTestContext ctx, KeyMapping key, int ticks) {
		ctx.getInput().holdKeyFor(key, ticks);
	}

	/** Whether the game is back on the title screen with no world or server (the state every scenario must end in). */
	static boolean clean(ClientGameTestContext ctx) {
		return !ThreadingImpl.isServerRunning && ctx.computeOnClient(mc -> mc.level == null && mc.gui.screen() instanceof TitleScreen);
	}

	/** Best effort to get back to the title screen after a failed scenario. */
	static boolean recover(ClientGameTestContext ctx) {
		try {
			ctx.runOnClient(mc -> {
				for (KeyMapping k : mc.options.keyMappings) {
					k.setDown(false);
				}
				if (mc.level != null) {
					mc.disconnectFromWorld(Component.literal("test recovery"));
				}
			});
			ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
			ctx.setScreen(TitleScreen::new);
			ctx.waitTicks(2);
			return clean(ctx);
		} catch (Throwable t) {
			SlipwayClientGameTests.LOG.error("Could not recover after a failed scenario", t);
			return false;
		}
	}

	/** The overworld of a server. */
	static ServerLevel overworld(MinecraftServer server) {
		return server.overworld();
	}
}
