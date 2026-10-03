package dev.timstewart.slipway.net;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.math.VesselPose;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Slipway's network payloads. Every serverbound payload is validated by {@link ServerPackets}. */
public final class SlipwayPayloads {
	private SlipwayPayloads() {
	}

	/**
	 * Server to client, every tick to players tracking the vessel: the authoritative pose and velocities, and the
	 * vessel's modes as flag bits.
	 */
	public record PoseUpdate(long vesselId, int entityId, long gameTime, VesselPoseData pose, Vec3 velocity, Vec3 angularVelocity, byte flags)
		implements CustomPacketPayload {
		public static final byte FLAG_HOVER = 1;
		public static final byte FLAG_LEVEL = 2;
		/** The vessel has a physics body. */
		public static final byte FLAG_BODY = 4;
		public static final byte FLAG_LOOSE = 8;
		/** The vessel lies in water or lava (it displaces some). */
		public static final byte FLAG_IN_FLUID = 16;
		/** Water is running over a rim of the hull into air it kept dry. */
		public static final byte FLAG_FLOODING = 32;
		public static final Type<PoseUpdate> TYPE = new Type<>(Slipway.id("vessel_pose"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PoseUpdate> CODEC = CustomPacketPayload.codec(PoseUpdate::write, PoseUpdate::new);

		private PoseUpdate(RegistryFriendlyByteBuf buf) {
			this(buf.readVarLong(), buf.readVarInt(), buf.readLong(), VesselPoseData.read(buf), readVec(buf), readVec(buf), buf.readByte());
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeVarLong(this.vesselId);
			buf.writeVarInt(this.entityId);
			buf.writeLong(this.gameTime);
			this.pose.write(buf);
			writeVec(buf, this.velocity);
			writeVec(buf, this.angularVelocity);
			buf.writeByte(this.flags);
		}

		@Override
		public Type<PoseUpdate> type() {
			return TYPE;
		}
	}

	/**
	 * Server to client when a player starts tracking a vessel or its bounds change: how plot space maps to the world.
	 * {@code assembled} is set on the one sent as the vessel is assembled (its blocks just left the world there).
	 */
	public record VesselInfo(long vesselId, int entityId, BlockPos anchor, BlockPos localMin, BlockPos localMax, BlockPos helm, Direction helmFacing,
		int blocks, float mass, boolean assembled) implements CustomPacketPayload {
		public static final Type<VesselInfo> TYPE = new Type<>(Slipway.id("vessel_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, VesselInfo> CODEC = CustomPacketPayload.codec(VesselInfo::write, VesselInfo::new);

		private VesselInfo(RegistryFriendlyByteBuf buf) {
			this(buf.readVarLong(), buf.readVarInt(), buf.readBlockPos(), buf.readBlockPos(), buf.readBlockPos(), buf.readBlockPos(),
				buf.readEnum(Direction.class), buf.readVarInt(), buf.readFloat(), buf.readBoolean());
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeVarLong(this.vesselId);
			buf.writeVarInt(this.entityId);
			buf.writeBlockPos(this.anchor);
			buf.writeBlockPos(this.localMin);
			buf.writeBlockPos(this.localMax);
			buf.writeBlockPos(this.helm);
			buf.writeEnum(this.helmFacing);
			buf.writeVarInt(this.blocks);
			buf.writeFloat(this.mass);
			buf.writeBoolean(this.assembled);
		}

		@Override
		public Type<VesselInfo> type() {
			return TYPE;
		}
	}

	/**
	 * Server to client: forget a vessel. With {@code keepProxy} only the near view goes (the player stopped viewing it:
	 * its mesh and pose playback are dropped, the Distant Horizons proxy stays); otherwise the vessel is gone entirely
	 * (removed, disassembled, or out of proxy range).
	 */
	public record VesselGone(long vesselId, boolean keepProxy) implements CustomPacketPayload {
		public static final Type<VesselGone> TYPE = new Type<>(Slipway.id("vessel_gone"));
		public static final StreamCodec<RegistryFriendlyByteBuf, VesselGone> CODEC = CustomPacketPayload.codec(
			(p, buf) -> {
				buf.writeVarLong(p.vesselId);
				buf.writeBoolean(p.keepProxy);
			}, buf -> new VesselGone(buf.readVarLong(), buf.readBoolean()));

		@Override
		public Type<VesselGone> type() {
			return TYPE;
		}
	}

	/**
	 * Server to client for vessels beyond entity-tracking range but inside the proxy range: a coarse picture for
	 * Distant Horizons. {@code blocks} packs a local position per entry (x, y, z as shorts) and {@code colors} an
	 * ARGB colour per entry; only blocks with an exposed face are listed.
	 */
	public record VesselProxy(long vesselId, int revision, VesselPoseData pose, short[] blocks, int[] colors) implements CustomPacketPayload {
		public static final int MAX_ENTRIES = 16384;
		public static final Type<VesselProxy> TYPE = new Type<>(Slipway.id("vessel_proxy"));
		public static final StreamCodec<RegistryFriendlyByteBuf, VesselProxy> CODEC = CustomPacketPayload.codec(VesselProxy::write, VesselProxy::read);

		private static VesselProxy read(RegistryFriendlyByteBuf buf) {
			long id = buf.readVarLong();
			int revision = buf.readVarInt();
			VesselPoseData pose = VesselPoseData.read(buf);
			int count = buf.readVarInt();
			if (count < 0 || count > MAX_ENTRIES) {
				throw new IllegalArgumentException("vessel proxy with " + count + " entries");
			}
			short[] blocks = new short[count * 3];
			int[] colors = new int[count];
			for (int i = 0; i < count; i++) {
				blocks[i * 3] = buf.readShort();
				blocks[i * 3 + 1] = buf.readShort();
				blocks[i * 3 + 2] = buf.readShort();
				colors[i] = buf.readInt();
			}
			return new VesselProxy(id, revision, pose, blocks, colors);
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeVarLong(this.vesselId);
			buf.writeVarInt(this.revision);
			this.pose.write(buf);
			buf.writeVarInt(this.colors.length);
			for (int i = 0; i < this.colors.length; i++) {
				buf.writeShort(this.blocks[i * 3]);
				buf.writeShort(this.blocks[i * 3 + 1]);
				buf.writeShort(this.blocks[i * 3 + 2]);
				buf.writeInt(this.colors[i]);
			}
		}

		@Override
		public Type<VesselProxy> type() {
			return TYPE;
		}
	}

	/** Server to client: the pose of a vessel shown only as a proxy, a few times a second. */
	public record ProxyPose(long vesselId, VesselPoseData pose) implements CustomPacketPayload {
		public static final Type<ProxyPose> TYPE = new Type<>(Slipway.id("proxy_pose"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ProxyPose> CODEC = CustomPacketPayload.codec(
			(p, buf) -> {
				buf.writeVarLong(p.vesselId);
				p.pose.write(buf);
			}, buf -> new ProxyPose(buf.readVarLong(), VesselPoseData.read(buf)));

		@Override
		public Type<ProxyPose> type() {
			return TYPE;
		}
	}

	/**
	 * Client to server while piloting: control axes in [-1, 1] and edge-triggered toggles (bit 0 hover, bit 1 level,
	 * bit 2 loose). The server checks that the sender pilots the named vessel and sanitises every number.
	 */
	public record HelmControl(long vesselId, int sequence, float forward, float strafe, float vertical, float pitch, float yaw, float roll, byte toggles)
		implements CustomPacketPayload {
		public static final byte TOGGLE_HOVER = 1;
		public static final byte TOGGLE_LEVEL = 2;
		public static final byte TOGGLE_LOOSE = 4;
		public static final Type<HelmControl> TYPE = new Type<>(Slipway.id("helm_control"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HelmControl> CODEC = CustomPacketPayload.codec(HelmControl::write, HelmControl::new);

		private HelmControl(RegistryFriendlyByteBuf buf) {
			this(buf.readVarLong(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
				buf.readByte());
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeVarLong(this.vesselId);
			buf.writeVarInt(this.sequence);
			buf.writeFloat(this.forward);
			buf.writeFloat(this.strafe);
			buf.writeFloat(this.vertical);
			buf.writeFloat(this.pitch);
			buf.writeFloat(this.yaw);
			buf.writeFloat(this.roll);
			buf.writeByte(this.toggles);
		}

		@Override
		public Type<HelmControl> type() {
			return TYPE;
		}
	}

	/** A pose on the wire: doubles for the position, floats for the unit quaternion. */
	public record VesselPoseData(double x, double y, double z, float qx, float qy, float qz, float qw) {
		public static VesselPoseData of(VesselPose pose) {
			return new VesselPoseData(pose.x(), pose.y(), pose.z(), (float)pose.qx(), (float)pose.qy(), (float)pose.qz(), (float)pose.qw());
		}

		static VesselPoseData read(FriendlyByteBuf buf) {
			return new VesselPoseData(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
		}

		void write(FriendlyByteBuf buf) {
			buf.writeDouble(this.x);
			buf.writeDouble(this.y);
			buf.writeDouble(this.z);
			buf.writeFloat(this.qx);
			buf.writeFloat(this.qy);
			buf.writeFloat(this.qz);
			buf.writeFloat(this.qw);
		}

		public boolean isFinite() {
			return Double.isFinite(this.x) && Double.isFinite(this.y) && Double.isFinite(this.z) && Float.isFinite(this.qx) && Float.isFinite(this.qy)
				&& Float.isFinite(this.qz) && Float.isFinite(this.qw);
		}

		public VesselPose toPose() {
			return new VesselPose(this.x, this.y, this.z, this.qx, this.qy, this.qz, this.qw).normalized();
		}
	}

	static Vec3 readVec(ByteBuf buf) {
		return new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
	}

	static void writeVec(ByteBuf buf, Vec3 v) {
		buf.writeFloat((float)v.x);
		buf.writeFloat((float)v.y);
		buf.writeFloat((float)v.z);
	}
}
