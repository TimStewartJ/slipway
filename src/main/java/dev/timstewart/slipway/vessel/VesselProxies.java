package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.net.SlipwayPayloads;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

/**
 * Long-range vessel proxies for clients with Distant Horizons: the exposed blocks of every vessel with their map
 * colours (kept in the saved record, so parked vessels whose area is unloaded still have one), sent once per
 * revision to players within {@code proxyRange}, and the vessel's pose a few times a second while it moves.
 */
final class VesselProxies {
	private static final int POSE_INTERVAL_TICKS = 5;
	private static final int REBUILD_INTERVAL_TICKS = 20;

	private final VesselManager manager;
	/** Per player: the proxy revision last sent for each vessel. */
	private final Map<UUID, Long2IntOpenHashMap> sentRevision = new HashMap<>();
	/** Per player: the game time a pose was last sent for each vessel. */
	private final Map<UUID, Long2LongOpenHashMap> sentPose = new HashMap<>();

	VesselProxies(VesselManager manager) {
		this.manager = manager;
	}

	void tick(long gameTime) {
		for (ActiveVessel vessel : this.manager.activeVessels()) {
			if (vessel.proxyDirty && vessel.chunksReady && gameTime - vessel.lastProxyBuild >= REBUILD_INTERVAL_TICKS) {
				vessel.proxyDirty = false;
				vessel.lastProxyBuild = gameTime;
				int[] data = compute(this.manager.level(), vessel.record);
				if (!java.util.Arrays.equals(data, vessel.record.proxy)) {
					vessel.record.proxy = data;
					vessel.record.proxyRevision++;
					this.manager.registry().setDirty();
				}
			}
		}
		if (gameTime % POSE_INTERVAL_TICKS != 0) {
			return;
		}
		int range = SlipwayConfig.get().proxyRange;
		double rangeSq = (double)range * range;
		for (ServerPlayer player : this.manager.level().players()) {
			Long2IntOpenHashMap revisions = this.sentRevision.computeIfAbsent(player.getUUID(), u -> new Long2IntOpenHashMap());
			Long2LongOpenHashMap poses = this.sentPose.computeIfAbsent(player.getUUID(), u -> new Long2LongOpenHashMap());
			for (VesselRecord record : this.manager.registry().all()) {
				Vec3 centre = VesselManager.worldCentre(record);
				double dx = centre.x - player.getX();
				double dz = centre.z - player.getZ();
				boolean inRange = range > 0 && dx * dx + dz * dz <= rangeSq;
				if (!inRange) {
					if (revisions.containsKey(record.id)) {
						revisions.remove(record.id);
						poses.remove(record.id);
						ServerPlayNetworking.send(player, new SlipwayPayloads.VesselGone(record.id));
					}
					continue;
				}
				if (record.proxy.length == 0) {
					continue;
				}
				if (!revisions.containsKey(record.id) || revisions.get(record.id) != record.proxyRevision) {
					revisions.put(record.id, record.proxyRevision);
					poses.put(record.id, gameTime);
					ServerPlayNetworking.send(player, payload(record));
					continue;
				}
				ActiveVessel active = this.manager.active(record.id);
				boolean moving = active != null && active.hasBody
					&& (record.linearVelocity.lengthSqr() > 1.0e-4 || record.angularVelocity.lengthSqr() > 1.0e-6);
				boolean tracked = active != null && active.entity != null && PlayerLookup.tracking(active.entity).contains(player);
				if (moving && !tracked) {
					poses.put(record.id, gameTime);
					ServerPlayNetworking.send(player, new SlipwayPayloads.ProxyPose(record.id, SlipwayPayloads.VesselPoseData.of(record.pose)));
				}
			}
		}
		this.sentRevision.keySet().removeIf(uuid -> this.manager.level().getPlayerByUUID(uuid) == null);
		this.sentPose.keySet().removeIf(uuid -> this.manager.level().getPlayerByUUID(uuid) == null);
	}

	void forget(long vesselId) {
		for (Long2IntOpenHashMap revisions : this.sentRevision.values()) {
			revisions.remove(vesselId);
		}
		for (Long2LongOpenHashMap poses : this.sentPose.values()) {
			poses.remove(vesselId);
		}
	}

	static SlipwayPayloads.VesselProxy payload(VesselRecord record) {
		int count = Math.min(record.proxy.length / 2, SlipwayPayloads.VesselProxy.MAX_ENTRIES);
		short[] blocks = new short[count * 3];
		int[] colors = new int[count];
		for (int i = 0; i < count; i++) {
			int packed = record.proxy[i * 2];
			blocks[i * 3] = (short)VesselRecord.unpackProxyX(packed);
			blocks[i * 3 + 1] = (short)VesselRecord.unpackProxyY(packed);
			blocks[i * 3 + 2] = (short)VesselRecord.unpackProxyZ(packed);
			colors[i] = record.proxy[i * 2 + 1];
		}
		return new SlipwayPayloads.VesselProxy(record.id, record.proxyRevision, SlipwayPayloads.VesselPoseData.of(record.pose), blocks, colors);
	}

	/** Exposed, visible blocks of a vessel with their map colours; bounded by the vessel's bounds and the entry cap. */
	static int[] compute(ServerLevel level, VesselRecord record) {
		IntArrayList out = new IntArrayList();
		BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
		for (BlockPos plotPos : BlockPos.betweenClosed(record.plotMin(), record.plotMax())) {
			BlockState state = level.getBlockState(plotPos);
			if (state.isAir()) {
				continue;
			}
			MapColor color = state.getMapColor(level, plotPos);
			if (color == MapColor.NONE) {
				continue;
			}
			boolean exposed = false;
			for (Direction direction : Direction.values()) {
				neighbour.setWithOffset(plotPos, direction);
				BlockState other = level.getBlockState(neighbour);
				if (other.isAir() || !other.canOcclude()) {
					exposed = true;
					break;
				}
			}
			if (!exposed) {
				continue;
			}
			BlockPos local = record.toLocal(plotPos);
			if (local.getX() < -512 || local.getX() > 511 || local.getY() < -512 || local.getY() > 511 || local.getZ() < -512 || local.getZ() > 511) {
				continue;
			}
			out.add(VesselRecord.packProxyPos(local.getX(), local.getY(), local.getZ()));
			out.add(color.col);
			if (out.size() >= SlipwayPayloads.VesselProxy.MAX_ENTRIES * 2) {
				break;
			}
		}
		return out.toIntArray();
	}
}
