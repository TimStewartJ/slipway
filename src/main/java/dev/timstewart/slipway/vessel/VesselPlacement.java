package dev.timstewart.slipway.vessel;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Placing blocks on a vessel: vanilla orients a placed block by the player's yaw and pitch (stairs face away from
 * the player, logs follow the look axis, signs turn to the player). For a block placed on a vessel these angles must
 * be the player's look direction in the vessel's frame, so the placement code runs with the player temporarily
 * rotated into that frame.
 */
public final class VesselPlacement {
	private VesselPlacement() {
	}

	/** Runs {@code action} with the player's rotation expressed in the vessel's frame, then restores it. */
	public static <T> T inVesselFrame(Player player, VesselLookup.View vessel, java.util.function.Supplier<T> action) {
		float yRot = player.getYRot();
		float xRot = player.getXRot();
		// Placement code reads yRot and xRot (not the head rotation), so the look vector is built from them too.
		Vec3 look = Vec3.directionFromRotation(xRot, yRot);
		Vector3d local = vessel.pose().inverseRotate(look.x, look.y, look.z, new Vector3d());
		float localYaw = (float)Math.toDegrees(Math.atan2(-local.x, local.z));
		float localPitch = (float)Math.toDegrees(Math.asin(Mth.clamp(-local.y, -1.0, 1.0)));
		player.setYRot(localYaw);
		player.setXRot(localPitch);
		try {
			return action.get();
		} finally {
			player.setYRot(yRot);
			player.setXRot(xRot);
		}
	}
}
