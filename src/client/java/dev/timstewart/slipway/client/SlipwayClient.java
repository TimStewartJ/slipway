package dev.timstewart.slipway.client;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.client.render.VesselRenderer;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.registry.SlipwayRegistry;
import dev.timstewart.slipway.vessel.BlockChangeListener;
import dev.timstewart.slipway.vessel.VesselEntity;
import dev.timstewart.slipway.vessel.VesselLookup;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public final class SlipwayClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(SlipwayRegistry.VESSEL, VesselRenderer::new);
		HelmControls.register();
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Slipway.id("helm"), HelmHud::extract);

		ClientPlayNetworking.registerGlobalReceiver(SlipwayPayloads.VesselInfo.TYPE, (payload, context) -> ClientVessels.onInfo(payload));
		ClientPlayNetworking.registerGlobalReceiver(SlipwayPayloads.PoseUpdate.TYPE, (payload, context) -> ClientVessels.onPose(payload));
		ClientPlayNetworking.registerGlobalReceiver(SlipwayPayloads.VesselGone.TYPE, (payload, context) -> ClientVessels.onGone(payload.vesselId()));
		ClientPlayNetworking.registerGlobalReceiver(SlipwayPayloads.VesselProxy.TYPE, (payload, context) -> DhProxyBridge.onProxy(payload));
		ClientPlayNetworking.registerGlobalReceiver(SlipwayPayloads.ProxyPose.TYPE, (payload, context) -> DhProxyBridge.onProxyPose(payload));

		ClientTickEvents.START_CLIENT_TICK.register(mc -> ClientVessels.tick());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ClientVessels.endTick();
			HelmControls.tick(mc);
			DhProxyBridge.tick();
			SlipwayDebug.riderTraceTick();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(ClientVessels::clear));

		BlockChangeListener.client = (level, pos, state) -> ClientVessels.onPlotBlockChanged(pos);
		VesselLookup.client = new VesselLookup.ClientSource() {
			@Override
			public VesselLookup.View atPlotPos(BlockPos plotPos) {
				ClientVessel vessel = ClientVessels.atPlotPos(plotPos);
				return vessel != null && vessel.ready() ? vessel.view() : null;
			}

			@Override
			public void collectNear(AABB box, List<VesselLookup.View> out) {
				for (ClientVessel vessel : ClientVessels.all()) {
					if (vessel.ready() && vessel.view().worldBounds().intersects(box)) {
						out.add(vessel.view());
					}
				}
			}
		};
		VesselEntity.ClientHooks.instance = new VesselEntity.ClientHooks() {
			@Override
			public void tickVesselEntity(VesselEntity entity) {
				ClientVessels.tickEntity(entity);
			}
		};
	}
}
