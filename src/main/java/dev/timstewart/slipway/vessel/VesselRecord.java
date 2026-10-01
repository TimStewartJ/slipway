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
		Codec.INT.fieldOf("blocks").forGetter(r -> r.blockCount),
		Codec.INT.optionalFieldOf("proxy_revision", 0).forGetter(r -> r.proxyRevision),
		Codec.INT_STREAM.xmap(java.util.stream.IntStream::toArray, java.util.Arrays::stream).optionalFieldOf("proxy", new int[0]).forGetter(r -> r.proxy),
		// Since 0.1.2; absent in older saves, which load as not loose.
		Codec.BOOL.optionalFieldOf("loose", false).forGetter(r -> r.loose)
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
	/**
	 * Loose: the vessel is a plain rigid body. Nothing controls it (no hover, levelling, drag, spin brake or thrust,
	 * and helm input is ignored); gravity and contacts move it. {@link #hover} and {@link #level} keep their values
	 * and apply again when this is turned off.
	 */
	public boolean loose;
	public int blockCount;
	/** Bumped whenever {@link #proxy} is recomputed. */
	public int proxyRevision;
	/**
	 * The vessel's exposed blocks for long-range proxies, two ints per block: the local position packed as three
	 * signed 10-bit fields ({@link #packProxyPos}) and the block's map colour as RGB.
	 */
	public int[] proxy;

	public VesselRecord(long id, int plot, BlockPos anchor, BlockPos localMin, BlockPos localMax, BlockPos helm, Direction helmFacing,
		VesselPose pose, Vec3 linearVelocity, Vec3 angularVelocity, boolean hover, boolean level, int blockCount) {
		this(id, plot, anchor, localMin, localMax, helm, helmFacing, pose, linearVelocity, angularVelocity, hover, level, blockCount, 0, new int[0], false);
	}

	public VesselRecord(long id, int plot, BlockPos anchor, BlockPos localMin, BlockPos localMax, BlockPos helm, Direction helmFacing,
		VesselPose pose, Vec3 linearVelocity, Vec3 angularVelocity, boolean hover, boolean level, int blockCount, int proxyRevision, int[] proxy,
		boolean loose) {
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
		this.loose = loose;
		this.blockCount = blockCount;
		this.proxyRevision = proxyRevision;
		this.proxy = proxy == null || proxy.length % 2 != 0 ? new int[0] : proxy;
	}

	/** Packs a local position (each coordinate within [-512, 511]) into one int. */
	public static int packProxyPos(int x, int y, int z) {
		return (x & 0x3FF) | (y & 0x3FF) << 10 | (z & 0x3FF) << 20;
	}

	public static int unpackProxyX(int packed) {
		return (packed << 22) >> 22;
	}

	public static int unpackProxyY(int packed) {
		return (packed << 12) >> 22;
	}

	public static int unpackProxyZ(int packed) {
		return (packed << 2) >> 22;
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

	/**
	 * Whether the bounds may grow to take in a local position: along every axis they would grow on, they still span
	 * at most {@code maxSpan} blocks, the rule assembly applies to a structure ({@code StructureScan}). A position
	 * inside the bounds always fits, also in a vessel that is larger than the limit (assembled under a larger one, or
	 * grown by a machine before 0.1.2): such a vessel keeps working and cannot grow.
	 */
	public boolean fitsSpan(BlockPos local, int maxSpan) {
		return fitsSpan(this.localMin.getX(), this.localMax.getX(), local.getX(), maxSpan)
			&& fitsSpan(this.localMin.getY(), this.localMax.getY(), local.getY(), maxSpan)
			&& fitsSpan(this.localMin.getZ(), this.localMax.getZ(), local.getZ(), maxSpan);
	}

	private static boolean fitsSpan(int min, int max, int value, int maxSpan) {
		return value >= min && value <= max || Math.max(max, value) - Math.min(min, value) < maxSpan;
	}

	/**
	 * Whether a block at a plot position can become part of this vessel: it lies in the usable part of the plot and
	 * within the largest span.
	 */
	public boolean canTakeIn(BlockPos plotPos, int maxSpan) {
		return VesselRegion.isUsable(plotPos) && this.fitsSpan(this.toLocal(plotPos), maxSpan);
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
