package dev.timstewart.slipway.client;

import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Whether the terrain renderer has built everything it has been asked to build: vanilla's own answer, or Sodium's
 * when Sodium replaces the terrain renderer (vanilla's then never says yes).
 */
final class TerrainProgress {
	// slipway-hook: sodium-terrain-complete
	private static final String SODIUM_RENDERER = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
	@Nullable
	private static final Method SODIUM_INSTANCE;
	@Nullable
	private static final Method SODIUM_COMPLETE;

	static {
		Method instance = null;
		Method complete = null;
		try {
			Class<?> renderer = Class.forName(SODIUM_RENDERER);
			instance = renderer.getMethod("instanceNullable");
			complete = renderer.getMethod("isTerrainRenderComplete");
		} catch (ReflectiveOperationException | LinkageError absentOrChanged) {
			instance = null;
			complete = null;
		}
		SODIUM_INSTANCE = instance;
		SODIUM_COMPLETE = complete;
	}

	private TerrainProgress() {
	}

	static boolean usesSodium() {
		return SODIUM_COMPLETE != null;
	}

	/** True when nothing is left to build (also when that cannot be found out: nothing then waits for it). */
	static boolean complete(Minecraft mc) {
		if (SODIUM_INSTANCE != null && SODIUM_COMPLETE != null) {
			try {
				Object renderer = SODIUM_INSTANCE.invoke(null);
				return renderer == null || (Boolean)SODIUM_COMPLETE.invoke(renderer);
			} catch (ReflectiveOperationException | RuntimeException failed) {
				return true;
			}
		}
		return mc.levelRenderer == null || mc.levelRenderer.hasRenderedAllSections();
	}
}