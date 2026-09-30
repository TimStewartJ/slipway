package dev.timstewart.slipway.client.render;

import dev.timstewart.slipway.Slipway;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Whether Iris is drawing its shadow map right now, through Iris's public API when Iris is loaded (always false
 * without it). Vessel outlines and breaking overlays belong to the main pass only, like vanilla's: Iris has no
 * shadow program for outline lines.
 */
final class IrisShadowPass {
	@Nullable
	private static final MethodHandle ACTIVE = find();

	private IrisShadowPass() {
	}

	private static @Nullable MethodHandle find() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return null;
		}
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object instance = api.getMethod("getInstance").invoke(null);
			return MethodHandles.publicLookup().findVirtual(api, "isRenderingShadowPass", MethodType.methodType(boolean.class)).bindTo(instance);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
			Slipway.LOGGER.warn("Iris is loaded but its API is not usable; vessel outlines may reach its shadow pass", error);
			return null;
		}
	}

	static boolean active() {
		if (ACTIVE == null) {
			return false;
		}
		try {
			return (boolean)ACTIVE.invokeExact();
		} catch (Throwable error) {
			return false;
		}
	}
}
