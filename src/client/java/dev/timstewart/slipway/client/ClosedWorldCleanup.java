package dev.timstewart.slipway.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.timstewart.slipway.Slipway;
import java.lang.reflect.Field;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Releases references that other code keeps to a closed client world, once no world is loaded. Found with the leak
 * isolation matrix and heap dumps (DESIGN.md, "World retention after closing a world"); each step does nothing when
 * its target is absent.
 */
public final class ClosedWorldCleanup {
	/** Iris's pipeline override cache: {@code RenderSystem.iris$overrides}, added by its MixinShaderManager_Overrides. */
	// slipway-hook: iris-overrides-cache (registered in patches.json)
	private static final String IRIS_OVERRIDES_FIELD = "iris$overrides";
	@Nullable
	private static final Map<?, ?> IRIS_OVERRIDES = findIrisOverrides();

	private static boolean hadLevel;
	private static int releases;

	private ClosedWorldCleanup() {
	}

	/** End of every client tick: runs {@link #release} once when the world has just closed. */
	static void tick(Minecraft mc) {
		boolean hasLevel = mc.level != null;
		if (hadLevel && !hasLevel) {
			release(mc);
		}
		hadLevel = hasLevel;
	}

	/** How many times the cleanup has run (for tests). */
	public static int releases() {
		return releases;
	}

	/** Whether Iris's pipeline override cache was found (for tests). */
	public static boolean irisCacheFound() {
		return IRIS_OVERRIDES != null;
	}

	static void release(Minecraft mc) {
		releases++;
		// Vanilla clears its visible-section list only in the next level extraction, i.e. when the next world renders;
		// until then its render sections' last compile tasks keep the closed ClientLevel (and its chunks) reachable.
		mc.levelRenderer.clearVisibleSections();
		// Iris caches a pipeline override per shader program and never drops the programs of a destroyed pipeline;
		// their custom uniforms capture the ClientLevel they were built for (IrisExclusiveUniforms.WorldInfoUniforms),
		// so every world opened with a shader pack stayed reachable. With no world loaded Iris overrides nothing, and
		// it rebuilds the entries it needs on demand.
		if (IRIS_OVERRIDES != null) {
			try {
				IRIS_OVERRIDES.clear();
			} catch (RuntimeException error) {
				Slipway.LOGGER.debug("Could not clear Iris's pipeline override cache", error);
			}
		}
	}

	private static @Nullable Map<?, ?> findIrisOverrides() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return null;
		}
		try {
			Field field = RenderSystem.class.getDeclaredField(IRIS_OVERRIDES_FIELD);
			field.setAccessible(true);
			return field.get(null) instanceof Map<?, ?> map ? map : null;
		} catch (ReflectiveOperationException | RuntimeException error) {
			Slipway.LOGGER.debug("Iris's pipeline override cache was not found; nothing to release", error);
			return null;
		}
	}
}
