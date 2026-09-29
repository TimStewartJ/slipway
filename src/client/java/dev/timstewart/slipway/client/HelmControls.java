package dev.timstewart.slipway.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.vessel.VesselEntity;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Reads the pilot's keys while they ride a vessel's helm and sends them to the server every tick the input
 * changes (and at least twice a second). Movement keys are the player's own: W/S thrust, A/D turn, jump rises;
 * Slipway adds rebindable keys (category "Slipway") for descending, strafing, pitch, roll and the two toggles.
 */
public final class HelmControls {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Slipway.id("controls"));
	static KeyMapping descend;
	static KeyMapping pitchUp;
	static KeyMapping pitchDown;
	static KeyMapping rollLeft;
	static KeyMapping rollRight;
	static KeyMapping strafeLeft;
	static KeyMapping strafeRight;
	static KeyMapping toggleHover;
	static KeyMapping toggleLevel;

	private static float[] lastSent = new float[6];
	private static int sequence;
	private static int ticksSinceSend;
	/** Overrides from the test agent: axes forced for a number of ticks. */
	private static float[] scripted;
	private static int scriptedTicks;

	private HelmControls() {
	}

	static void register() {
		descend = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.descend", InputConstants.KEY_Z, CATEGORY));
		pitchUp = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.pitch_up", InputConstants.KEY_UP, CATEGORY));
		pitchDown = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.pitch_down", InputConstants.KEY_DOWN, CATEGORY));
		rollLeft = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.roll_left", InputConstants.KEY_LEFT, CATEGORY));
		rollRight = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.roll_right", InputConstants.KEY_RIGHT, CATEGORY));
		strafeLeft = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.strafe_left", InputConstants.KEY_N, CATEGORY));
		strafeRight = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.strafe_right", InputConstants.KEY_M, CATEGORY));
		toggleHover = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.toggle_hover", InputConstants.KEY_H, CATEGORY));
		toggleLevel = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slipway.toggle_level", InputConstants.KEY_B, CATEGORY));
	}

	/** Forces the six axes for some ticks (used by the end-to-end test agent to fly like a pilot would). */
	public static void script(float[] axes, int ticks) {
		scripted = axes.clone();
		scriptedTicks = ticks;
	}

	/** The vessel entity the local player is piloting, or null. */
	public static VesselEntity pilotedVessel() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && player.getVehicle() instanceof VesselEntity vessel ? vessel : null;
	}

	static void tick(Minecraft mc) {
		VesselEntity vessel = pilotedVessel();
		if (vessel == null) {
			while (toggleHover.consumeClick()) {
			}
			while (toggleLevel.consumeClick()) {
			}
			scriptedTicks = 0;
			return;
		}
		float[] axes;
		if (scriptedTicks > 0) {
			scriptedTicks--;
			axes = scripted;
		} else if (mc.gui.screen() == null) {
			axes = new float[] {
				axis(mc.options.keyUp, mc.options.keyDown),
				axis(strafeRight, strafeLeft),
				axis(mc.options.keyJump, descend),
				axis(pitchUp, pitchDown),
				axis(mc.options.keyRight, mc.options.keyLeft),
				axis(rollRight, rollLeft)
			};
		} else {
			axes = new float[6];
		}
		byte toggles = 0;
		while (toggleHover.consumeClick()) {
			toggles ^= SlipwayPayloads.HelmControl.TOGGLE_HOVER;
		}
		while (toggleLevel.consumeClick()) {
			toggles ^= SlipwayPayloads.HelmControl.TOGGLE_LEVEL;
		}
		ticksSinceSend++;
		boolean changed = toggles != 0 || ticksSinceSend >= 10;
		for (int i = 0; i < 6 && !changed; i++) {
			changed = axes[i] != lastSent[i];
		}
		if (changed) {
			ClientPlayNetworking.send(new SlipwayPayloads.HelmControl(vessel.vesselId(), ++sequence, axes[0], axes[1], axes[2], axes[3], axes[4], axes[5], toggles));
			lastSent = axes;
			ticksSinceSend = 0;
		}
	}

	private static float axis(KeyMapping positive, KeyMapping negative) {
		return (positive.isDown() ? 1f : 0f) - (negative.isDown() ? 1f : 0f);
	}
}
