package dev.timstewart.slipway.client.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.SlipwayPayloads;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Distant Horizons proxies for vessels, through DH's public generic-rendering API: one box group per vessel with a
 * box for every exposed block. DH only draws axis-aligned boxes, so each block's box sits at the block's rotated
 * centre; at LOD distances a block is a pixel or less and the difference does not show. Every frame the group's
 * origin follows the vessel's current pose, and when the vessel has turned by more than a degree the boxes are
 * laid out again. Near the camera, where the real vessel is drawn, the proxy is switched off.
 */
public final class DhProxies {
	private static final Long2ObjectOpenHashMap<Proxy> PROXIES = new Long2ObjectOpenHashMap<>();
	private static final double RELAYOUT_DEGREES = 1.0;

	private DhProxies() {
	}

	private static final class Proxy {
		final long id;
		int revision = -1;
		short[] blocks = new short[0];
		int[] colors = new int[0];
		@Nullable
		VesselPose serverPose;
		@Nullable
		IDhApiRenderableBoxGroup group;
		@Nullable
		IDhApiCustomRenderRegister register;
		@Nullable
		Quaterniond laidOutRotation;

		Proxy(long id) {
			this.id = id;
		}
	}

	public static void onProxy(SlipwayPayloads.VesselProxy payload) {
		if (!payload.pose().isFinite()) {
			return;
		}
		Proxy proxy = PROXIES.computeIfAbsent(payload.vesselId(), Proxy::new);
		proxy.serverPose = payload.pose().toPose();
		if (payload.revision() != proxy.revision) {
			proxy.revision = payload.revision();
			proxy.blocks = payload.blocks();
			proxy.colors = payload.colors();
			unregister(proxy);
		}
	}

	public static void onPose(SlipwayPayloads.ProxyPose payload) {
		Proxy proxy = PROXIES.get(payload.vesselId());
		if (proxy != null && payload.pose().isFinite()) {
			proxy.serverPose = payload.pose().toPose();
		}
	}

	public static void remove(long id) {
		Proxy proxy = PROXIES.remove(id);
		if (proxy != null) {
			unregister(proxy);
		}
	}

	public static void clear() {
		for (Proxy proxy : PROXIES.values()) {
			unregister(proxy);
		}
		PROXIES.clear();
		REFRESH_DUE.clear();
		LOD_QUERIES.clear();
		LOD_RESULTS.clear();
	}

	/**
	 * Diagnostics: what Distant Horizons' LOD data holds at a block (for checking stale LODs). DH's terrain getters
	 * block on a future that must not be awaited on the render thread, so queries run on a worker: each call answers
	 * with the latest finished query for that block ("pending" before the first) and starts a new one if none runs.
	 */
	public static String lodBlockAt(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		CompletableFuture<String> running = LOD_QUERIES.get(key);
		if (running != null && !running.isDone()) {
			return LOD_RESULTS.getOrDefault(key, "pending");
		}
		if (running != null) {
			LOD_RESULTS.put(key, running.join());
		}
		IDhApiLevelWrapper level;
		try {
			level = DhApi.Delayed.worldProxy != null && DhApi.Delayed.worldProxy.worldLoaded() ? clientLevel() : null;
		} catch (RuntimeException error) {
			return "error " + error;
		}
		if (level == null || DhApi.Delayed.terrainRepo == null) {
			return "no DH level";
		}
		LOD_QUERIES.put(key, CompletableFuture.supplyAsync(() -> queryLod(level, x, y, z)).completeOnTimeout("no data: timed out", 10, TimeUnit.SECONDS));
		return LOD_RESULTS.getOrDefault(key, "pending");
	}

	private static final Long2ObjectOpenHashMap<CompletableFuture<String>> LOD_QUERIES = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<String> LOD_RESULTS = new Long2ObjectOpenHashMap<>();

	private static String queryLod(IDhApiLevelWrapper level, int x, int y, int z) {
		try {
			var result = DhApi.Delayed.terrainRepo.getSingleDataPointAtBlockPos(level, x, y, z, DhApi.Delayed.terrainRepo.createSoftCache());
			if (!result.success || result.payload == null) {
				return "no data: " + result.message;
			}
			var point = result.payload;
			return (point.blockStateWrapper == null || point.blockStateWrapper.isAir() ? "air" : String.valueOf(point.blockStateWrapper.getSerialString()))
				+ " y" + point.bottomYBlockPos + ".." + point.topYBlockPos + " detail " + point.detailLevel;
		} catch (RuntimeException error) {
			return "no data: " + error;
		}
	}

	/** Number of registered proxy groups and their boxes, for the debug overlay and tests. */
	public static int[] stats() {
		int groups = 0;
		int boxes = 0;
		for (Proxy proxy : PROXIES.values()) {
			if (proxy.group != null) {
				groups++;
				boxes += proxy.group.size();
			}
		}
		return new int[] {groups, boxes};
	}

	/** Diagnostics: what the proxies know and what DH offers. */
	public static String describe() {
		int withData = 0;
		for (Proxy proxy : PROXIES.values()) {
			if (proxy.blocks.length > 0 && proxy.serverPose != null) {
				withData++;
			}
		}
		StringBuilder out = new StringBuilder("received=" + PROXIES.size() + " withData=" + withData);
		for (Proxy proxy : PROXIES.values()) {
			if (proxy.group != null) {
				DhApiVec3d origin = proxy.group.getOriginBlockPos();
				out.append(String.format(java.util.Locale.ROOT, " proxy#%d@%.1f,%.1f,%.1f%s", proxy.id, origin.x, origin.y, origin.z,
					proxy.group.isActive() ? "" : "(inactive)"));
			}
		}
		try {
			boolean loaded = DhApi.Delayed.worldProxy != null && DhApi.Delayed.worldProxy.worldLoaded();
			out.append(" dhWorld=").append(loaded);
			if (loaded) {
				int levels = 0;
				for (IDhApiLevelWrapper level : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
					levels++;
					out.append(" level[").append(level.getLevelType()).append(' ').append(level.getDimensionName()).append(' ')
						.append(level.getWrappedMcObject() == Minecraft.getInstance().level ? "current" : "other").append(']');
				}
				out.append(" levels=").append(levels).append(" clientLevel=").append(clientLevel() != null);
			}
		} catch (RuntimeException error) {
			out.append(" error=").append(error);
		}
		return out.toString();
	}

	/** Client tick: registers groups for proxies that have data but no group yet. */
	public static void tick() {
		refreshDueChunks();
		if (PROXIES.isEmpty() || DhApi.Delayed.worldProxy == null || DhApi.Delayed.customRenderObjectFactory == null) {
			return;
		}
		try {
			if (!DhApi.Delayed.worldProxy.worldLoaded()) {
				return;
			}
			IDhApiLevelWrapper level = clientLevel();
			if (level == null) {
				return;
			}
			for (Proxy proxy : PROXIES.values()) {
				if (proxy.group == null && proxy.blocks.length > 0 && proxy.serverPose != null) {
					IDhApiCustomRenderRegister register = level.getRenderRegister();
					if (register == null) {
						// DH has the level but not its renderer yet (just joined); try again next tick.
						return;
					}
					register(proxy, register);
				}
			}
		} catch (RuntimeException error) {
			Slipway.LOGGER.warn("Distant Horizons proxy update failed; proxies are disabled", error);
			clear();
		}
	}

	/**
	 * DH's level for the client's current level. {@code getSinglePlayerLevel} only answers in singleplayer; on a server
	 * the client level is found among the loaded levels by the Minecraft level it wraps.
	 */
	@Nullable
	private static IDhApiLevelWrapper clientLevel() {
		IDhApiLevelWrapper single = DhApi.Delayed.worldProxy.getSinglePlayerLevel();
		if (single != null) {
			return single;
		}
		Object current = Minecraft.getInstance().level;
		if (current == null) {
			return null;
		}
		for (IDhApiLevelWrapper level : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
			if (level.getWrappedMcObject() == current) {
				return level;
			}
		}
		return null;
	}

	private static void register(Proxy proxy, IDhApiCustomRenderRegister register) {
		VesselPose pose = currentPose(proxy, 0f);
		if (pose == null) {
			return;
		}
		IDhApiRenderableBoxGroup group = DhApi.Delayed.customRenderObjectFactory.createRelativePositionedGroup(
			Slipway.MOD_ID + ":vessel_" + proxy.id, new DhApiVec3d(pose.x(), pose.y(), pose.z()), layout(proxy, pose));
		group.setSkyLight(15);
		group.setBlockLight(0);
		group.setSsaoEnabled(true);
		proxy.laidOutRotation = pose.rotation();
		group.setPreRenderFunc(param -> beforeRender(proxy, param.partialTicks));
		register.add(group);
		proxy.group = group;
		proxy.register = register;
	}

	private static void unregister(Proxy proxy) {
		if (proxy.group != null && proxy.register != null) {
			try {
				proxy.register.remove(proxy.group.getId());
			} catch (RuntimeException ignored) {
				// DH may already have dropped the level.
			}
		}
		proxy.group = null;
		proxy.register = null;
	}

	private static void beforeRender(Proxy proxy, float partialTicks) {
		IDhApiRenderableBoxGroup group = proxy.group;
		VesselPose pose = currentPose(proxy, partialTicks);
		if (group == null || pose == null) {
			return;
		}
		// The real vessel is drawn near the camera; the proxy only fills in beyond vanilla's render distance.
		Minecraft mc = Minecraft.getInstance();
		Vec3 camera = mc.gameRenderer.mainCamera().position();
		double near = Math.max(64.0, (mc.options.getEffectiveRenderDistance() - 1) * 16.0);
		boolean far = camera.distanceToSqr(pose.x(), pose.y(), pose.z()) > near * near;
		group.setActive(far);
		if (!far) {
			return;
		}
		group.setOriginBlockPos(new DhApiVec3d(pose.x(), pose.y(), pose.z()));
		Quaterniond rotation = pose.rotation();
		if (proxy.laidOutRotation == null || Math.toDegrees(rotation.difference(proxy.laidOutRotation, new Quaterniond()).angle()) > RELAYOUT_DEGREES) {
			List<DhApiRenderableBox> boxes = layout(proxy, pose);
			group.clear();
			group.addAll(boxes);
			group.triggerBoxChange();
			proxy.laidOutRotation = rotation;
		}
	}

	@Nullable
	private static VesselPose currentPose(Proxy proxy, float partialTicks) {
		ClientVessel vessel = ClientVessels.get(proxy.id);
		if (vessel != null && vessel.ready()) {
			return vessel.renderPose(partialTicks);
		}
		return proxy.serverPose;
	}

	// -------------------------------------------------------------------------------------------------------------
	// World LOD refresh
	// -------------------------------------------------------------------------------------------------------------

	/** World chunk (packed position) to the client tick when its LOD should be rebuilt. */
	private static final Long2LongOpenHashMap REFRESH_DUE = new Long2LongOpenHashMap();
	/** Delay before a refresh, so the block updates that caused it have arrived. */
	private static final int REFRESH_DELAY_TICKS = 20;
	private static final int MAX_REFRESH_CHUNKS = 256;

	/**
	 * Queues Distant Horizons LOD rebuilds for the world chunks under a box. Assembly takes blocks out of the world and
	 * disassembly puts them back, both on the server; on a client connected to a server DH only rebuilds a chunk's LOD
	 * when the chunk loads or the local player breaks or places a block in it, so without this it would keep drawing the
	 * ship where it was built.
	 */
	public static void queueChunkRefresh(AABB box) {
		int minX = Mth.floor(box.minX) >> 4, maxX = Mth.floor(box.maxX) >> 4;
		int minZ = Mth.floor(box.minZ) >> 4, maxZ = Mth.floor(box.maxZ) >> 4;
		if ((long)(maxX - minX + 1) * (maxZ - minZ + 1) > MAX_REFRESH_CHUNKS) {
			return;
		}
		long due = ClientVessels.clientTicks() + REFRESH_DELAY_TICKS;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				REFRESH_DUE.put(ChunkPos.pack(x, z), due);
			}
		}
	}

	private static void refreshDueChunks() {
		if (REFRESH_DUE.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || DhApi.Delayed.terrainRepo == null || DhApi.Delayed.worldProxy == null) {
			REFRESH_DUE.clear();
			return;
		}
		long now = ClientVessels.clientTicks();
		try {
			if (!DhApi.Delayed.worldProxy.worldLoaded()) {
				return;
			}
			IDhApiLevelWrapper level = clientLevel();
			if (level == null) {
				return;
			}
			var entries = REFRESH_DUE.long2LongEntrySet().iterator();
			while (entries.hasNext()) {
				var entry = entries.next();
				if (entry.getLongValue() > now) {
					continue;
				}
				long pos = entry.getLongKey();
				entries.remove();
				LevelChunk chunk = mc.level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos), false);
				if (chunk != null) {
					DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(level, new Object[] {chunk, mc.level});
				}
			}
		} catch (RuntimeException error) {
			Slipway.LOGGER.warn("Distant Horizons LOD refresh failed", error);
			REFRESH_DUE.clear();
		}
	}

	/** One unit box per exposed block, centred on the block's rotated centre, relative to the vessel origin. */
	private static List<DhApiRenderableBox> layout(Proxy proxy, VesselPose pose) {
		List<DhApiRenderableBox> boxes = new ArrayList<>(proxy.colors.length);
		Vector3d centre = new Vector3d();
		for (int i = 0; i < proxy.colors.length; i++) {
			pose.rotate(proxy.blocks[i * 3] + 0.5, proxy.blocks[i * 3 + 1] + 0.5, proxy.blocks[i * 3 + 2] + 0.5, centre);
			int argb = proxy.colors[i];
			boxes.add(new DhApiRenderableBox(new DhApiVec3d(centre.x - 0.5, centre.y - 0.5, centre.z - 0.5),
				new DhApiVec3d(centre.x + 0.5, centre.y + 0.5, centre.z + 0.5),
				new Color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, 255), EDhApiBlockMaterial.WOOD));
		}
		return boxes;
	}
}
