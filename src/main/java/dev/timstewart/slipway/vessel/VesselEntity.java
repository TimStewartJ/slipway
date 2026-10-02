package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.math.VesselPose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * A vessel's presence in the world: it sits at the centre of the vessel, carries the vessel id, is tracked by
 * players like any entity (which decides who receives the vessel's chunks and poses), draws the vessel on the
 * client and is the vehicle the pilot rides at the helm. It never moves by itself; the server's
 * {@link VesselManager} and the client's vessel state place it every tick.
 */
public class VesselEntity extends Entity {
	private static final EntityDataAccessor<Long> DATA_VESSEL_ID = SynchedEntityData.defineId(VesselEntity.class, EntityDataSerializers.LONG);

	/** Pose used for riders this tick; set by the side that owns the vessel state. */
	@Nullable
	private VesselPose pose;
	/** The pose the rider's facing was last turned to (a vessel has at most one rider); see {@link #turnRider}. */
	@Nullable
	private VesselPose riderPose;
	private BlockPos helmLocal = BlockPos.ZERO;
	private Direction helmFacing = Direction.NORTH;
	/** Its vessel is gone (disassembled or removed); the manager discards it a few ticks later. Not saved. */
	private boolean retired;

	public VesselEntity(EntityType<? extends VesselEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	public long vesselId() {
		return this.entityData.get(DATA_VESSEL_ID);
	}

	public void setVesselId(long id) {
		this.entityData.set(DATA_VESSEL_ID, id);
	}

	@Nullable
	public VesselPose pose() {
		return this.pose;
	}

	/** The vessel is gone; this entity only stays so that clients can draw the vessel a moment longer. */
	void retire() {
		this.retired = true;
	}

	/**
	 * Called every tick by the vessel's owner (server manager or client state). Riders are placed, and turned with
	 * the vessel, by {@link #positionRider} in their own tick.
	 */
	public void updateFrom(VesselPose pose, BlockPos helmLocal, Direction helmFacing, Vec3 worldCenter) {
		this.pose = pose;
		this.helmLocal = helmLocal;
		this.helmFacing = helmFacing;
		this.setPos(worldCenter);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_VESSEL_ID, -1L);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.setVesselId(input.getLongOr("vessel", -1L));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putLong("vessel", this.vesselId());
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public void tick() {
		if (this.level() instanceof ServerLevel serverLevel) {
			if (!this.retired) {
				VesselManager.get(serverLevel).tickEntity(this);
			}
		} else if (ClientHooks.instance != null) {
			ClientHooks.instance.tickVesselEntity(this);
		}
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return !this.retired && this.getPassengers().isEmpty() && passenger instanceof Player;
	}

	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);
		// The new rider turns with the vessel from the pose it has now.
		this.riderPose = this.pose;
	}

	@Override
	@Nullable
	public LivingEntity getControllingPassenger() {
		return null;
	}

	/** Local position of the pilot's feet: centred on the block the helm's wheel faces, at the helm's height. */
	public Vec3 pilotLocalPosition() {
		BlockPos stand = this.helmLocal.relative(this.helmFacing);
		return new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
	}

	/**
	 * Places the pilot so that their eyes (not their feet) sit where a standing pilot's eyes would be in vessel space.
	 * The camera cannot roll, so this keeps the view at the right point of the ship at any attitude instead of pushing
	 * it through the deck when the vessel banks or flies inverted.
	 */
	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
		if (this.pose == null) {
			super.positionRider(passenger, moveFunction);
			return;
		}
		this.turnRider(passenger);
		Vec3 local = this.pilotLocalPosition();
		double eye = passenger.getEyeHeight();
		Vector3d world = this.pose.localToWorld(local.x, local.y + eye, local.z, new Vector3d());
		moveFunction.accept(passenger, world.x, world.y - eye, world.z);
	}

	/**
	 * Keeps the rider facing the same way relative to the vessel while it turns: turns the rider as far as the vessel
	 * has turned about the vertical since the rider was last turned.
	 *
	 * <p>This is done where the rider is placed, which vanilla does at the end of the rider's own tick
	 * ({@code Entity.rideTick}), and not where the vessel's pose arrives ({@link #updateFrom}), which is before the
	 * rider's tick. A tick begins by keeping the entity's rotation as its old one, and the view of a frame is
	 * interpolated from the old rotation to the new one. A turn made before the tick is in both: up to 0.1.3 the
	 * pilot's view stood still between ticks and jumped at each of them, twenty times a second, while the vessel was
	 * drawn turning in every frame. The place needs nothing like it: the rider's old position is the one of the
	 * tick before either way.
	 */
	private void turnRider(Entity passenger) {
		VesselPose from = this.riderPose;
		if (from == null) {
			this.riderPose = this.pose;
			return;
		}
		double delta = this.pose.yawTurnSinceDegrees(from);
		if (Math.abs(delta) > 1.0e-6) {
			float yaw = (float)(passenger.getYRot() - delta);
			passenger.setYRot(yaw);
			passenger.setYHeadRot(yaw);
			this.riderPose = this.pose;
		}
	}

	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		if (this.pose == null) {
			return super.getDismountLocationForPassenger(passenger);
		}
		Vec3 local = this.pilotLocalPosition();
		Vector3d world = this.pose.localToWorld(local.x, local.y + 0.05, local.z, new Vector3d());
		return new Vec3(world.x, world.y, world.z);
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return true;
	}

	/** The rotation from local to world, for callers that need it as a quaternion. */
	public Quaterniond rotation() {
		return this.pose == null ? new Quaterniond() : this.pose.rotation();
	}

	/** Client-side behaviour installed by the client entrypoint, so this class stays loadable on servers. */
	public abstract static class ClientHooks {
		@Nullable
		public static ClientHooks instance;

		public abstract void tickVesselEntity(VesselEntity entity);
	}
}
