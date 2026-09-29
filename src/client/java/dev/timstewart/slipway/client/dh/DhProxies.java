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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
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

	/** Client tick: registers groups for proxies that have data but no group yet. */
	public static void tick() {
		if (PROXIES.isEmpty() || DhApi.Delayed.worldProxy == null || DhApi.Delayed.customRenderObjectFactory == null) {
			return;
		}
		try {
			if (!DhApi.Delayed.worldProxy.worldLoaded()) {
				return;
			}
			IDhApiLevelWrapper level = DhApi.Delayed.worldProxy.getSinglePlayerLevel();
			if (level == null) {
				return;
			}
			for (Proxy proxy : PROXIES.values()) {
				if (proxy.group == null && proxy.blocks.length > 0 && proxy.serverPose != null) {
					register(proxy, level.getRenderRegister());
				}
			}
		} catch (RuntimeException error) {
			Slipway.LOGGER.warn("Distant Horizons proxy update failed; proxies are disabled", error);
			clear();
		}
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
