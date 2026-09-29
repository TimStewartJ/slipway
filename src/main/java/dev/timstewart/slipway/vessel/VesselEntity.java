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
	private BlockPos helmLocal = BlockPos.ZERO;
	private Direction helmFacing = Direction.NORTH;

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

	/** Called every tick by the vessel's owner (server manager or client state). */
	public void updateFrom(VesselPose pose, BlockPos helmLocal, Direction helmFacing, Vec3 worldCenter) {
		VesselPose previous = this.pose;
		this.pose = pose;
		this.helmLocal = helmLocal;
		this.helmFacing = helmFacing;
		this.setPos(worldCenter);
		if (previous != null) {
			// Keep riders facing the same way relative to the vessel while it turns.
			double delta = pose.headingDegrees() - previous.headingDegrees();
			if (Math.abs(delta) > 1.0e-6) {
				for (Entity passenger : this.getPassengers()) {
					float yaw = (float)(passenger.getYRot() - delta);
					passenger.setYRot(yaw);
					passenger.setYHeadRot(yaw);
				}
			}
		}
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
			VesselManager.get(serverLevel).tickEntity(this);
		} else if (ClientHooks.instance != null) {
			ClientHooks.instance.tickVesselEntity(this);
		}
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().isEmpty() && passenger instanceof Player;
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

	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
		if (this.pose == null) {
			super.positionRider(passenger, moveFunction);
			return;
		}
		Vec3 local = this.pilotLocalPosition();
		Vector3d world = this.pose.localToWorld(local.x, local.y, local.z, new Vector3d());
		moveFunction.accept(passenger, world.x, world.y, world.z);
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
