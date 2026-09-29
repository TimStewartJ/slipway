package dev.timstewart.slipway;

import dev.timstewart.slipway.command.SlipwayCommands;
import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.net.ServerPackets;
import dev.timstewart.slipway.physics.jolt.JoltRuntime;
import dev.timstewart.slipway.physics.jolt.JoltSelfTest;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.VesselEntity;
import dev.timstewart.slipway.vessel.VesselManager;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Slipway implements ModInitializer {
	public static final String MOD_ID = "slipway";
	public static final Logger LOGGER = LoggerFactory.getLogger("Slipway");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Where native libraries are extracted: one folder per content hash under the game directory. */
	public static Path nativeCache() {
		return FabricLoader.getInstance().getGameDir().resolve(".slipway").resolve("natives");
	}

	@Override
	public void onInitialize() {
		JoltRuntime.ensureLoaded(nativeCache());
		SlipwayConfig.load(FabricLoader.getInstance().getConfigDir().resolve("slipway.json"));
		SlipwayRegistry.register();
		ServerPackets.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> SlipwayCommands.register(dispatcher));

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof VesselEntity vessel) {
				VesselManager.get(level).tickEntity(vessel);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof VesselEntity vessel) {
				VesselManager manager = VesselManager.getIfPresent(level);
				if (manager != null) {
					manager.onEntityUnloaded(vessel);
				}
			}
		});
		ServerTickEvents.START_LEVEL_TICK.register(level -> {
			VesselManager manager = VesselManager.getIfPresent(level);
			if (manager != null) {
				manager.tickStart();
			}
		});
		// Created with the level so parked vessels are known (and shown as proxies) before any of them loads.
		ServerLevelEvents.LOAD.register((server, level) -> VesselManager.get(level));
		ServerLevelEvents.UNLOAD.register((server, level) -> VesselManager.onLevelUnload(level));
		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> VesselManager.beforeSave(!server.isRunning()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> VesselManager.onServerStopped());

		if (Boolean.getBoolean("slipway.selftest")) {
			ServerLifecycleEvents.SERVER_STARTED.register(server -> {
				JoltSelfTest.Result result = JoltSelfTest.run();
				LOGGER.info("Slipway self-test: Jolt {} body came to rest at y={} (expected {}) after {} steps in {} ms: {}",
					JoltRuntime.info().doublePrecision() ? "double-precision" : "single-precision",
					String.format(Locale.ROOT, "%.4f", result.restY()), result.expectedRestY(), result.steps(),
					result.nanos() / 1_000_000, result.passed() ? "PASS" : "FAIL");
				if (Boolean.getBoolean("slipway.selftest.stop")) {
					server.halt(false);
				}
			});
		}
	}
}
