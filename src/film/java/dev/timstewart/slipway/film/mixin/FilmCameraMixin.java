package dev.timstewart.slipway.film.mixin;

import dev.timstewart.slipway.film.FilmCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places the camera on the film's path (FilmCamera) after vanilla aligned it with the camera entity; adds roll. */
@Mixin(Camera.class)
abstract class FilmCameraMixin {
	@Shadow
	@Final
	private static Vector3fc FORWARDS;
	@Shadow
	@Final
	private static Vector3fc UP;
	@Shadow
	@Final
	private static Vector3fc LEFT;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	@Shadow
	private int matrixPropertiesDirty;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void slipwayFilm$placeCamera(float partialTicks, CallbackInfo ci) {
		FilmCamera.Frame frame = FilmCamera.frame(partialTicks);
		if (frame == null) {
			return;
		}
		this.xRot = frame.pitch();
		this.yRot = frame.yaw();
		this.rotation.rotationYXZ((float)Math.PI - (float)Math.toRadians(frame.yaw()), -(float)Math.toRadians(frame.pitch()), -(float)Math.toRadians(frame.roll()));
		FORWARDS.rotate(this.rotation, this.forwards);
		UP.rotate(this.rotation, this.up);
		LEFT.rotate(this.rotation, this.left);
		this.matrixPropertiesDirty |= 3;
		this.setPosition(frame.position());
	}
}
