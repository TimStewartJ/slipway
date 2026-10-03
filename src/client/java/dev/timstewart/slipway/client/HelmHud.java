package dev.timstewart.slipway.client;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** The pilot's display: speed, altitude, attitude and modes, drawn while riding a vessel's helm. */
final class HelmHud {
	private HelmHud() {
	}

	static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		VesselEntity entity = HelmControls.pilotedVessel();
		if (entity == null || mc.gui.hud.isHidden()) {
			return;
		}
		ClientVessel vessel = ClientVessels.get(entity.vesselId());
		if (vessel == null || !vessel.ready()) {
			return;
		}
		VesselPose pose = vessel.renderPose(deltaTracker.getGameTimeDeltaPartialTick(true));
		double[] attitude = pose.attitudeDegrees();
		Vec3 centre = vessel.worldCentre(pose);
		double heading = (attitude[1] + 360.0) % 360.0;
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(String.format(Locale.ROOT, "Vessel #%d  %d blocks  %.1f t", vessel.id, vessel.blocks, vessel.mass / 1000.0)));
		lines.add(Component.translatable("slipway.hud.speed", String.format(Locale.ROOT, "%.1f", vessel.velocity.length())));
		lines.add(Component.translatable("slipway.hud.altitude", String.format(Locale.ROOT, "%.1f", centre.y)));
		lines.add(Component.translatable("slipway.hud.attitude", String.format(Locale.ROOT, "%+.0f", attitude[0]), String.format(Locale.ROOT, "%+.0f", attitude[2]),
			String.format(Locale.ROOT, "%03.0f", heading)));
		lines.add(Component.translatable("slipway.hud.modes", Component.literal(vessel.hover ? "ON" : "off"), Component.literal(vessel.level ? "ON" : "off"),
			Component.literal(vessel.loose ? "ON" : "off"), String.format(Locale.ROOT, "%.1f", vessel.mass / 1000.0)));
		double reserve = vessel.buoyancyReserve();
		if (reserve > 0) {
			// What the hull can carry, and what it is doing in the water now.
			String state = !vessel.inFluid ? reserve > 1.0 ? "floats" : "sinks"
				: vessel.hover && !vessel.loose ? "hover" : vessel.flooding ? "flooding" : reserve > 1.0 ? "afloat" : "sinking";
			lines.add(Component.translatable("slipway.hud.hull", String.format(Locale.ROOT, "%.0f", reserve * 100.0), Component.translatable("slipway.hud.hull." + state)));
		}
		int width = 0;
		for (Component line : lines) {
			width = Math.max(width, mc.font.width(line));
		}
		int x = 6;
		int y = 6;
		graphics.fill(x - 3, y - 3, x + width + 3, y + lines.size() * 10 + 1, 0x80000000);
		for (Component line : lines) {
			graphics.text(mc.font, line, x, y, 0xFFE8E8E8, true);
			y += 10;
		}
	}
}
