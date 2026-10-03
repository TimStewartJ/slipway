package dev.timstewart.slipway.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.timstewart.slipway.vessel.Shelter;
import dev.timstewart.slipway.vessel.VesselCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
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
 * An entity inside a hull that keeps the water out is not in the water: see {@link Shelter}.
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
	/** Set while the fluids around this entity are looked at again for an entity a vessel's hull keeps dry. */
	@Unique
	private boolean slipway$dry;

	@Shadow
	public abstract Vec3 getDeltaMovement();

	/**
	 * An entity in air that a vessel keeps the water out of (below the waterline in a floating hull, in the cabin of a
	 * submerged one) is not in the water blocks the world still has there: vanilla's look at the fluids around it is
	 * done again with nothing to look at, which leaves it as an entity in the air (see {@link Shelter}).
	 */
	@WrapOperation(method = "updateFluidInteraction", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/EntityFluidInteraction;update(Lnet/minecraft/world/entity/Entity;Z)Z"))
	private boolean slipway$dryInsideAHull(EntityFluidInteraction interaction, Entity entity, boolean ignoreCurrent, Operation<Boolean> original) {
		boolean inFluid = original.call(interaction, entity, ignoreCurrent);
		if (inFluid && Shelter.isDry(entity)) {
			this.slipway$dry = true;
			try {
				inFluid = original.call(interaction, entity, ignoreCurrent);
			} finally {
				this.slipway$dry = false;
			}
		}
		return inFluid;
	}

	@ModifyReturnValue(method = "getFluidInteractionBox", at = @At("RETURN"))
	private AABB slipway$noFluidsWhileDry(AABB box) {
		return this.slipway$dry ? null : box;
	}

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
