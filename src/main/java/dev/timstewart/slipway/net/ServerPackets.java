package dev.timstewart.slipway.net;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselEntity;
import dev.timstewart.slipway.vessel.VesselManager;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Registers Slipway's payloads and handles the serverbound one. A helm control packet is accepted only from the
 * player who is riding the named vessel's helm right now; every axis is checked for NaN and infinity and clamped
 * to [-1, 1], and a sender may not exceed 40 packets a second. Rejections are counted and logged (rate limited).
 */
public final class ServerPackets {
	public static final int MAX_CONTROL_PACKETS_PER_SECOND = 40;
	private static final AtomicLong REJECTED = new AtomicLong();
	private static long lastRejectLog;

	private ServerPackets() {
	}

	public static long rejectedCount() {
		return REJECTED.get();
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(SlipwayPayloads.PoseUpdate.TYPE, SlipwayPayloads.PoseUpdate.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SlipwayPayloads.VesselInfo.TYPE, SlipwayPayloads.VesselInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SlipwayPayloads.VesselGone.TYPE, SlipwayPayloads.VesselGone.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(SlipwayPayloads.VesselProxy.TYPE, SlipwayPayloads.VesselProxy.CODEC, 1 << 20);
		PayloadTypeRegistry.clientboundPlay().register(SlipwayPayloads.ProxyPose.TYPE, SlipwayPayloads.ProxyPose.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SlipwayPayloads.HelmControl.TYPE, SlipwayPayloads.HelmControl.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SlipwayPayloads.HelmControl.TYPE, (payload, context) -> handleHelmControl(context.player(), payload));
	}

	/** Why a helm control packet was refused, or null when it was applied. */
	@Nullable
	public static String handleHelmControl(ServerPlayer player, SlipwayPayloads.HelmControl payload) {
		String refusal = applyHelmControl(player, payload);
		if (refusal != null) {
			REJECTED.incrementAndGet();
			long now = System.currentTimeMillis();
			if (now - lastRejectLog > 1000) {
				lastRejectLog = now;
				Slipway.LOGGER.info("Rejected helm control from {}: {}", player.getName().getString(), refusal);
			}
		}
		return refusal;
	}

	@Nullable
	private static String applyHelmControl(ServerPlayer player, SlipwayPayloads.HelmControl payload) {
		if (!(player.getVehicle() instanceof VesselEntity vesselEntity)) {
			return "not at a helm";
		}
		if (vesselEntity.vesselId() != payload.vesselId()) {
			return "pilots vessel " + vesselEntity.vesselId() + ", not " + payload.vesselId();
		}
		VesselManager manager = VesselManager.get(player.level());
		ActiveVessel vessel = manager.active(payload.vesselId());
		if (vessel == null || vessel.entity != vesselEntity) {
			return "vessel " + payload.vesselId() + " is not active";
		}
		if (player.distanceToSqr(vesselEntity) > 512 * 512) {
			return "too far from the vessel";
		}
		long gameTime = player.level().getGameTime();
		if (!vessel.controlRate.tryAcquire(gameTime, MAX_CONTROL_PACKETS_PER_SECOND)) {
			return "too many control packets";
		}
		float[] axes = {payload.forward(), payload.strafe(), payload.vertical(), payload.pitch(), payload.yaw(), payload.roll()};
		for (float axis : axes) {
			if (!Float.isFinite(axis)) {
				return "non-finite axis";
			}
		}
		vessel.input.set(payload.forward(), payload.strafe(), payload.vertical(), payload.pitch(), payload.yaw(), payload.roll(), gameTime);
		if ((payload.toggles() & SlipwayPayloads.HelmControl.TOGGLE_HOVER) != 0) {
			vessel.record.hover = !vessel.record.hover;
			player.sendOverlayMessage(Component.translatable(vessel.record.hover ? "slipway.helm.hover_on" : "slipway.helm.hover_off"));
		}
		if ((payload.toggles() & SlipwayPayloads.HelmControl.TOGGLE_LEVEL) != 0) {
			vessel.record.level = !vessel.record.level;
			player.sendOverlayMessage(Component.translatable(vessel.record.level ? "slipway.helm.level_on" : "slipway.helm.level_off"));
		}
		if ((payload.toggles() & SlipwayPayloads.HelmControl.TOGGLE_LOOSE) != 0) {
			vessel.record.loose = !vessel.record.loose;
			player.sendOverlayMessage(Component.translatable(vessel.record.loose ? "slipway.helm.loose_on" : "slipway.helm.loose_off"));
		}
		return null;
	}
}
