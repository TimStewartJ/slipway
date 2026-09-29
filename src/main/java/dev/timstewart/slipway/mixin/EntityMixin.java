package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.timstewart.slipway.vessel.VesselCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entities collide with vessels and ride their decks: see {@link VesselCollisions}. The carry runs at the start of
 * a move, vessel collision after vanilla's world collision, and the contact bookkeeping at the end of the move.
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements VesselCollisions.Rider {
	@Unique
	private final VesselCollisions.Contact slipway$contact = new VesselCollisions.Contact();
	@Unique
	private long slipway$carrier = -1;
	@Unique
	private int slipway$lastContactTick = Integer.MIN_VALUE / 2;
	@Unique
	private int slipway$lastCarryTick = Integer.MIN_VALUE;
	@Unique
	private Vec3 slipway$groundNormal = new Vec3(0, 1, 0);
	@Unique
	private Vec3 slipway$velocityBeforeMove = Vec3.ZERO;
	@Unique
	private boolean slipway$worldCollided;

	@Shadow
	public abstract Vec3 getDeltaMovement();

	@Inject(method = "move", at = @At("HEAD"))
	private void slipway$beforeMove(MoverType type, Vec3 delta, CallbackInfo ci) {
		if (type == MoverType.SELF || type == MoverType.PLAYER) {
			VesselCollisions.carry((Entity)(Object)this);
		}
		this.slipway$velocityBeforeMove = this.getDeltaMovement();
		this.slipway$contact.reset();
		this.slipway$worldCollided = false;
	}

	@ModifyReturnValue(method = "collide", at = @At("RETURN"))
	private Vec3 slipway$collideWithVessels(Vec3 afterWorld, Vec3 movement) {
		this.slipway$worldCollided = !afterWorld.equals(movement);
		return VesselCollisions.collide((Entity)(Object)this, movement, afterWorld);
	}

	@Inject(method = "move", at = @At("TAIL"))
	private void slipway$afterMove(MoverType type, Vec3 delta, CallbackInfo ci) {
		VesselCollisions.afterMove((Entity)(Object)this, delta, this.slipway$velocityBeforeMove, this.slipway$worldCollided);
	}

	@Override
	public VesselCollisions.Contact slipway$contact() {
		return this.slipway$contact;
	}

	@Override
	public long slipway$carrier() {
		return this.slipway$carrier;
	}

	@Override
	public void slipway$setCarrier(long vesselId) {
		this.slipway$carrier = vesselId;
	}

	@Override
	public int slipway$lastContactTick() {
		return this.slipway$lastContactTick;
	}

	@Override
	public void slipway$setLastContactTick(int tick) {
		this.slipway$lastContactTick = tick;
	}

	@Override
	public int slipway$lastCarryTick() {
		return this.slipway$lastCarryTick;
	}

	@Override
	public void slipway$setLastCarryTick(int tick) {
		this.slipway$lastCarryTick = tick;
	}

	@Override
	public Vec3 slipway$groundNormal() {
		return this.slipway$groundNormal;
	}

	@Override
	public void slipway$setGroundNormal(Vec3 normal) {
		this.slipway$groundNormal = normal;
	}
}
