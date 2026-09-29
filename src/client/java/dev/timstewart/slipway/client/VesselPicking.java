package dev.timstewart.slipway.client;

import dev.timstewart.slipway.math.VesselPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crosshair picking of vessel blocks. The player's eye ray is moved into each nearby vessel's plot space and clipped
 * against the real blocks there with vanilla's {@code clip}; the result stays in plot space (block position, face
 * and hit point), which is what the server's interaction handlers expect. A vessel hit wins when it is nearer than
 * what vanilla picked. Distances are the same in both spaces because the transform is rigid.
 */
public final class VesselPicking {
	private VesselPicking() {
	}

	public static HitResult pick(Entity camera, float partialTicks, double range, HitResult original) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || ClientVessels.all().isEmpty()) {
			return original;
		}
		Vec3 from = camera.getEyePosition(partialTicks);
		Vec3 direction = camera.getViewVector(partialTicks);
		Vec3 to = from.add(direction.scale(range));
		double best = original.getType() == HitResult.Type.MISS ? range : original.getLocation().distanceTo(from);
		HitResult result = original;
		for (ClientVessel vessel : ClientVessels.all()) {
			if (!vessel.ready()) {
				continue;
			}
			VesselPose pose = vessel.renderPose(partialTicks);
			AABB bounds = vessel.worldBounds(pose).inflate(0.5);
			if (!bounds.contains(from) && bounds.clip(from, to).isEmpty()) {
				continue;
			}
			Vec3 plotFrom = vessel.worldToPlot(pose, from);
			Vec3 plotTo = vessel.worldToPlot(pose, to);
			BlockHitResult hit = level.clip(new ClipContext(plotFrom, plotTo, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
			if (hit.getType() != HitResult.Type.BLOCK || !vessel.containsPlotPos(hit.getBlockPos())) {
				continue;
			}
			double distance = hit.getLocation().distanceTo(plotFrom);
			if (distance < best) {
				best = distance;
				result = hit;
			}
		}
		return result;
	}
}
