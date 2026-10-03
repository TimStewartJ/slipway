package dev.timstewart.slipway.client;

import dev.timstewart.slipway.client.dh.DhCullRepair;
import dev.timstewart.slipway.client.dh.DhProxies;
import dev.timstewart.slipway.net.SlipwayPayloads;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Keeps Distant Horizons strictly optional: the DH integration classes are only touched when the mod is loaded.
 */
public final class DhProxyBridge {
	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("distanthorizons");

	private DhProxyBridge() {
	}

	public static boolean present() {
		return PRESENT;
	}

	static void init() {
		if (PRESENT && FabricLoader.getInstance().isModLoaded("iris")) {
			DhCullRepair.register();
		}
	}

	static void onProxy(SlipwayPayloads.VesselProxy proxy) {
		if (PRESENT) {
			DhProxies.onProxy(proxy);
		}
	}

	static void onProxyPose(SlipwayPayloads.ProxyPose pose) {
		if (PRESENT) {
			DhProxies.onPose(pose);
		}
	}

	static void remove(long id) {
		if (PRESENT) {
			DhProxies.remove(id);
		}
	}

	static void tick() {
		if (PRESENT) {
			DhProxies.tick();
		}
	}

	static void clearAll() {
		if (PRESENT) {
			DhProxies.clear();
		}
	}

	/** Asks Distant Horizons to rebuild the LODs of the loaded world chunks under a box (blocks there changed). */
	static void refreshWorld(net.minecraft.world.phys.AABB box) {
		if (PRESENT) {
			DhProxies.queueChunkRefresh(box);
		}
	}
}
