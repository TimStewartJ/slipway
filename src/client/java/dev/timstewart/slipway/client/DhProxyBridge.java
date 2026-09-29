package dev.timstewart.slipway.client;

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
}
