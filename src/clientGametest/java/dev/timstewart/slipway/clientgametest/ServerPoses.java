package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselManager;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.Level;

/**
 * The server's pose of one vessel for every game tick, keyed by the game time its pose update was sent with. Slipway
 * broadcasts at the start of the level tick (before the game time advances) and does not change the pose again until
 * the next tick, so at the end of the tick the pose is the one sent with game time minus one.
 */
final class ServerPoses {
	private static volatile long target = -1;
	private static final ConcurrentSkipListMap<Long, VesselPose> HISTORY = new ConcurrentSkipListMap<>();
	private static boolean installed;

	private ServerPoses() {
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			long id = target;
			if (id < 0 || level.dimension() != Level.OVERWORLD) {
				return;
			}
			VesselManager manager = VesselManager.getIfPresent(level);
			ActiveVessel vessel = manager == null ? null : manager.active(id);
			if (vessel != null) {
				HISTORY.put(level.getGameTime() - 1, vessel.record.pose);
			}
		});
	}

	static void record(long id) {
		HISTORY.clear();
		target = id;
	}

	static void stop() {
		target = -1;
	}

	/** The server's pose at a (fractional) game time, interpolated like the client does; null outside the record. */
	static VesselPose at(double tick) {
		Map.Entry<Long, VesselPose> before = HISTORY.floorEntry((long)Math.floor(tick));
		Map.Entry<Long, VesselPose> after = HISTORY.ceilingEntry((long)Math.ceil(tick));
		if (before == null || after == null || after.getKey() - before.getKey() > 1) {
			return null;
		}
		if (after.getKey().equals(before.getKey())) {
			return before.getValue();
		}
		return before.getValue().interpolate(after.getValue(), tick - before.getKey());
	}

	static int size() {
		return HISTORY.size();
	}
}
