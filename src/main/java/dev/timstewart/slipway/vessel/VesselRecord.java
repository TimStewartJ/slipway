package dev.timstewart.slipway.vessel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.timstewart.slipway.math.VesselPose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * The durable description of one vessel, saved in {@link VesselRegistry}. Blocks and block entities live in
 * real chunks of the vessel's plot and are saved with those chunks; this record only holds what maps them to
 * the world. Mutable on the server thread only.
 */
public final class VesselRecord {
	public static final Codec<VesselRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.LONG.fieldOf("id").forGetter(r -> r.id),
		Codec.INT.fieldOf("plot").forGetter(r -> r.plot),
		BlockPos.CODEC.fieldOf("anchor").forGetter(r -> r.anchor),
		BlockPos.CODEC.fieldOf("min").forGetter(r -> r.localMin),
		BlockPos.CODEC.fieldOf("max").forGetter(r -> r.localMax),
		BlockPos.CODEC.fieldOf("helm").forGetter(r -> r.helm),
		Direction.CODEC.fieldOf("helm_facing").forGetter(r -> r.helmFacing),
		VesselPose.CODEC.fieldOf("pose").forGetter(r -> r.pose),
		Vec3.CODEC.fieldOf("velocity").forGetter(r -> r.linearVelocity),
		Vec3.CODEC.fieldOf("angular_velocity").forGetter(r -> r.angularVelocity),
		Codec.BOOL.fieldOf("hover").forGetter(r -> r.hover),
		Codec.BOOL.fieldOf("level").forGetter(r -> r.level),
		Codec.INT.fieldOf("blocks").forGetter(r -> r.blockCount)
	).apply(i, VesselRecord::new));

	public final long id;
	public final int plot;
	/** Plot position of the local origin; local coordinates are plot coordinates minus this. */
	public final BlockPos anchor;
	/** Inclusive local bounds of the vessel's blocks. */
	public BlockPos localMin;
	public BlockPos localMax;
	/** Local position of the helm block. */
	public BlockPos helm;
	/** Facing of the helm block in local space; the vessel's forward direction. */
	public Direction helmFacing;
	public VesselPose pose;
	public Vec3 linearVelocity;
	public Vec3 angularVelocity;
	public boolean hover;
	public boolean level;
	public int blockCount;

	public VesselRecord(long id, int plot, BlockPos anchor, BlockPos localMin, BlockPos localMax, BlockPos helm, Direction helmFacing,
		VesselPose pose, Vec3 linearVelocity, Vec3 angularVelocity, boolean hover, boolean level, int blockCount) {
		this.id = id;
		this.plot = plot;
		this.anchor = anchor;
		this.localMin = localMin;
		this.localMax = localMax;
		this.helm = helm;
		this.helmFacing = helmFacing.getAxis().isHorizontal() ? helmFacing : Direction.NORTH;
		this.pose = pose;
		this.linearVelocity = finiteOrZero(linearVelocity);
		this.angularVelocity = finiteOrZero(angularVelocity);
		this.hover = hover;
		this.level = level;
		this.blockCount = blockCount;
	}

	/** Plot position of a local block position. */
	public BlockPos toPlot(BlockPos local) {
		return this.anchor.offset(local);
	}

	/** Local position of a plot block position. */
	public BlockPos toLocal(BlockPos plotPos) {
		return plotPos.subtract(this.anchor);
	}

	public BlockPos plotMin() {
		return this.anchor.offset(this.localMin);
	}

	public BlockPos plotMax() {
		return this.anchor.offset(this.localMax);
	}

	public boolean containsPlotPos(BlockPos plotPos) {
		return VesselRegion.plotAt(plotPos.getX(), plotPos.getZ()) == this.plot;
	}

	/** Grows the local bounds to include a local position. */
	public void include(BlockPos local) {
		this.localMin = new BlockPos(Math.min(this.localMin.getX(), local.getX()), Math.min(this.localMin.getY(), local.getY()), Math.min(this.localMin.getZ(), local.getZ()));
		this.localMax = new BlockPos(Math.max(this.localMax.getX(), local.getX()), Math.max(this.localMax.getY(), local.getY()), Math.max(this.localMax.getZ(), local.getZ()));
	}

	/** Local centre of the block bounds, used as the vessel entity's position. */
	public Vec3 localCenter() {
		return new Vec3((this.localMin.getX() + this.localMax.getX() + 1) / 2.0, (this.localMin.getY() + this.localMax.getY() + 1) / 2.0,
			(this.localMin.getZ() + this.localMax.getZ() + 1) / 2.0);
	}

	private static Vec3 finiteOrZero(Vec3 v) {
		return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z) ? v : Vec3.ZERO;
	}

	@Override
	public String toString() {
		return "Vessel#" + this.id + "[plot=" + this.plot + ", blocks=" + this.blockCount + ", pose=" + this.pose + "]";
	}
}
