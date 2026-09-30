package dev.timstewart.slipway.clientgametest;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

/**
 * Minecraft 26.2+ caches blending and the colour write mask per draw buffer (8 of them) in {@link GlStateManager} and
 * only calls GL when its cache differs. Code that changes that state with plain GL calls leaves the cache stale, and
 * later draws into Iris's G-buffers then blend (or mask) when they should not: Distant Horizons before 3.3.3 did this
 * with {@code glEnable/glDisable(GL_BLEND)} while drawing its LODs at the start of the main pass, and the opaque
 * terrain drawn next came out darkened in blotches under shaders. The stale state lasts only until later passes toggle
 * those buffers again, so this check samples the state inside the frame (at Fabric's level render events around the
 * terrain) as well as between frames.
 */
final class GlStateCheck {
	private static final int DRAW_BUFFERS = 8;
	private static final Field BLEND_ENABLE = field("BLEND_ENABLE");
	private static final Field COLOR_MASK = field("COLOR_MASK");
	private static final Map<String, Point> POINTS = new LinkedHashMap<>();
	private static volatile boolean recording;
	private static boolean registered;

	private GlStateCheck() {
	}

	private static final class Point {
		final AtomicInteger samples = new AtomicInteger();
		final AtomicInteger outOfSync = new AtomicInteger();
		volatile String first;

		void sample() {
			samples.incrementAndGet();
			String mismatch = mismatch();
			if (mismatch != null && outOfSync.getAndIncrement() == 0) {
				first = mismatch;
			}
		}
	}

	/**
	 * Fails when, during {@code frames} frames, a draw buffer's GL blend enable or colour write mask differs from
	 * GlStateManager's cache at any sampled point, or when a sampled point never ran.
	 */
	static void assertInSync(ClientGameTestContext ctx, Report.Result r, String when, int frames) {
		ctx.runOnClient(mc -> register());
		synchronized (POINTS) {
			POINTS.replaceAll((name, p) -> new Point());
		}
		Point between = new Point();
		recording = true;
		try {
			for (int frame = 0; frame < frames; frame++) {
				ctx.runOnClient(mc -> between.sample());
				ctx.waitTick();
			}
		} finally {
			recording = false;
		}
		Map<String, Point> all = new LinkedHashMap<>();
		synchronized (POINTS) {
			all.putAll(POINTS);
		}
		all.put("betweenFrames", between);
		StringBuilder summary = new StringBuilder();
		String firstBad = null;
		int bad = 0;
		for (Map.Entry<String, Point> e : all.entrySet()) {
			Point p = e.getValue();
			summary.append(String.format("%s %d/%d; ", e.getKey(), p.outOfSync.get(), p.samples.get()));
			bad += p.outOfSync.get();
			if (firstBad == null && p.first != null) {
				firstBad = e.getKey() + ": " + p.first;
			}
		}
		r.metric("glStateCache." + when, "out of sync/samples: " + summary.toString().trim() + (firstBad == null ? "" : " first: " + firstBad));
		for (Map.Entry<String, Point> e : all.entrySet()) {
			Check.atLeast(when + ": samples at " + e.getKey() + " (the check ran inside the frame)", e.getValue().samples.get(), frames / 2.0);
		}
		Check.that(bad == 0, "%s: Minecraft's per-draw-buffer GL state cache disagreed with GL %d times (%s); first: %s", when, bad,
			summary.toString().trim(), firstBad);
	}

	private static void register() {
		if (registered) {
			return;
		}
		registered = true;
		for (String name : new String[] {"startMain", "afterOpaqueTerrain", "afterSolidFeatures", "beforeTranslucentTerrain", "endMain"}) {
			synchronized (POINTS) {
				POINTS.computeIfAbsent(name, n -> new Point());
			}
		}
		LevelRenderEvents.START_MAIN.register(c -> sample("startMain"));
		LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(c -> sample("afterOpaqueTerrain"));
		LevelRenderEvents.AFTER_SOLID_FEATURES.register(c -> sample("afterSolidFeatures"));
		LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(c -> sample("beforeTranslucentTerrain"));
		LevelRenderEvents.END_MAIN.register(c -> sample("endMain"));
	}

	private static void sample(String name) {
		if (!recording) {
			return;
		}
		Point p;
		synchronized (POINTS) {
			p = POINTS.get(name);
		}
		if (p != null) {
			p.sample();
		}
	}

	/** The draw buffers whose GL blend enable or colour write mask differ from GlStateManager's cache, or null. */
	private static String mismatch() {
		boolean[] blend;
		int[] mask;
		try {
			blend = (boolean[])BLEND_ENABLE.get(null);
			mask = (int[])COLOR_MASK.get(null);
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
		StringBuilder out = new StringBuilder();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer rgba = stack.malloc(4);
			for (int i = 0; i < DRAW_BUFFERS; i++) {
				boolean glBlend = GL30C.glIsEnabledi(GL11C.GL_BLEND, i);
				if (glBlend != blend[i]) {
					out.append(String.format("buffer %d blend: GL %s, cache %s; ", i, glBlend ? "on" : "off", blend[i] ? "on" : "off"));
				}
				GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, i, rgba);
				int glMask = (rgba.get(0) != 0 ? 1 : 0) | (rgba.get(1) != 0 ? 2 : 0) | (rgba.get(2) != 0 ? 4 : 0) | (rgba.get(3) != 0 ? 8 : 0);
				// Only the low four bits (red, green, blue, alpha) reach GL; callers may pass -1 for "all".
				if (glMask != (mask[i] & 0xF)) {
					out.append(String.format("buffer %d write mask: GL %s, cache %s; ", i, Integer.toBinaryString(glMask), Integer.toBinaryString(mask[i] & 0xF)));
				}
			}
		}
		return out.isEmpty() ? null : out.toString().trim();
	}

	private static Field field(String name) {
		try {
			Field f = GlStateManager.class.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (NoSuchFieldException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
