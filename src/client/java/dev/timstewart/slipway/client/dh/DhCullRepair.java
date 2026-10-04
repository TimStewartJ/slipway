package dev.timstewart.slipway.client.dh;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderCleanupEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

/**
 * Puts back-face culling back after Distant Horizons has drawn its LODs under an Iris shader pack.
 *
 * <p>Iris turns culling off with a plain GL call before each Distant Horizons render pass. Distant Horizons turns it
 * back on, but on Minecraft 26.2+ with a shader pack in use it only does so through Minecraft's GL state cache, which
 * still says "on" and therefore does nothing. Culling then stays off in GL for the rest of the frame while the cache
 * says it is on. The translucent terrain is drawn next: wherever a chunk section's water also has side faces (water
 * against glass, such as a submarine's windows), the renderer draws that section's faces of all directions together,
 * the water surface's underside is no longer culled, and the shader pack lights it as a mirror of the sky. The result
 * is a bright, chunk-shaped patch of sea around such a vessel.
 *
 * <p>Turning culling off and on again through the cache makes GL and the cache agree, whatever either held. Every
 * later draw sets the culling its pipeline needs through the same cache, so "on" is a safe state to leave.
 *
 * <p>All of this is OpenGL. The game can also draw with another graphics backend, where there is no GL context on
 * the render thread and a GL call fails: the first event asks whether there is one, and without one nothing is done.
 */
public final class DhCullRepair {
	/** For the diagnostic scenario that shows the patch with and without the repair. */
	public static volatile boolean enabled = true;

	private DhCullRepair() {
	}

	public static void register() {
		DhApi.events.bind(DhApiAfterRenderEvent.class, new DhApiAfterRenderEvent() {
			@Override
			public void afterRender(DhApiEventParam<Void> event) {
				repair();
			}
		});
		DhApi.events.bind(DhApiBeforeRenderCleanupEvent.class, new DhApiBeforeRenderCleanupEvent() {
			@Override
			public void beforeCleanup(DhApiEventParam<DhApiRenderParam> event) {
				repair();
			}
		});
	}

	/** Whether the render thread has an OpenGL context; asked once, on that thread. */
	private static Boolean openGl;

	private static void repair() {
		if (!enabled) {
			return;
		}
		if (openGl == null) {
			try {
				org.lwjgl.opengl.GL.getCapabilities();
				openGl = true;
			} catch (IllegalStateException noContext) {
				openGl = false;
			}
		}
		if (openGl) {
			GlStateManager._disableCull();
			GlStateManager._enableCull();
		}
	}
}
