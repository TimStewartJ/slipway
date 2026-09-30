package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.phys.Vec3;

/**
 * Server lifecycle hooks for the tests: the vessel records exactly as the last save of a stopping server wrote them,
 * and exactly as a starting server loaded them (before its first tick moves anything).
 */
final class Lifecycle {
	record Snapshot(long id, VesselPose pose, Vec3 velocity, Vec3 angularVelocity, boolean hover, boolean level, int blocks) {
		static Snapshot of(VesselRecord r) {
			return new Snapshot(r.id, r.pose, r.linearVelocity, r.angularVelocity, r.hover, r.level, r.blockCount);
		}
	}

	static final Map<Long, Snapshot> SAVED = new ConcurrentHashMap<>();
	static final Map<Long, Snapshot> LOADED = new ConcurrentHashMap<>();
	/** Each vessel's record at the end of the first tick its physics body existed (before any step result applies). */
	static final Map<Long, Snapshot> FIRST_BODY = new ConcurrentHashMap<>();
	private static boolean installed;

	private Lifecycle() {
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
			if (!server.isRunning()) {
				SAVED.clear();
				for (var level : server.getAllLevels()) {
					VesselManager manager = VesselManager.getIfPresent(level);
					if (manager != null) {
						manager.registry().all().forEach(r -> SAVED.put(r.id, Snapshot.of(r)));
					}
				}
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOADED.clear();
			FIRST_BODY.clear();
			for (var level : server.getAllLevels()) {
				VesselManager.get(level).registry().all().forEach(r -> LOADED.put(r.id, Snapshot.of(r)));
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(level -> {
			VesselManager manager = VesselManager.getIfPresent(level);
			if (manager != null) {
				for (var vessel : manager.activeVessels()) {
					if (vessel.hasBody) {
						FIRST_BODY.putIfAbsent(vessel.record.id, Snapshot.of(vessel.record));
					}
				}
			}
		});
	}
}
