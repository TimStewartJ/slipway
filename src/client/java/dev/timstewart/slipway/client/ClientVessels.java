package dev.timstewart.slipway.client;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.vessel.VesselEntity;
import dev.timstewart.slipway.vessel.VesselRegion;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** All vessels the client knows about in its current level. Client thread only. */
public final class ClientVessels {
	private static final Long2ObjectMap<ClientVessel> VESSELS = new Long2ObjectLinkedOpenHashMap<>();
	private static final Int2LongOpenHashMap BY_PLOT = new Int2LongOpenHashMap();
	@Nullable
	private static ClientLevel level;
	/** Between the start and the end of a client tick. */
	private static boolean tickInProgress;
	private static long clientTicks;

	static {
		BY_PLOT.defaultReturnValue(-1L);
	}

	private ClientVessels() {
	}

	public static Collection<ClientVessel> all() {
		return VESSELS.values();
	}

	@Nullable
	public static ClientVessel get(long id) {
		return VESSELS.get(id);
	}

	@Nullable
	public static ClientVessel atPlot(int plot) {
		long id = BY_PLOT.get(plot);
		return id < 0 ? null : VESSELS.get(id);
	}

	@Nullable
	public static ClientVessel atPlotPos(BlockPos plotPos) {
		return VesselRegion.isReserved(plotPos) ? atPlot(VesselRegion.plotAt(plotPos.getX(), plotPos.getZ())) : null;
	}

	private static ClientVessel getOrCreate(long id) {
		checkLevel();
		return VESSELS.computeIfAbsent(id, ClientVessel::new);
	}

	/** Forgets everything when the client changes level or disconnects. */
	private static void checkLevel() {
		ClientLevel current = Minecraft.getInstance().level;
		if (current != level) {
			clear();
			level = current;
		}
	}

	public static void clear() {
		for (ClientVessel vessel : VESSELS.values()) {
			vessel.close();
		}
		VESSELS.clear();
		BY_PLOT.clear();
		DhProxyBridge.clearAll();
	}

	static void onInfo(SlipwayPayloads.VesselInfo info) {
		ClientVessel vessel = getOrCreate(info.vesselId());
		vessel.applyInfo(info);
		BY_PLOT.put(vessel.plot(), vessel.id);
	}

	static void onPose(SlipwayPayloads.PoseUpdate update) {
		getOrCreate(update.vesselId()).applyPose(update);
	}

	static void onGone(long id) {
		checkLevel();
		ClientVessel vessel = VESSELS.remove(id);
		if (vessel != null) {
			BY_PLOT.remove(vessel.plot());
			vessel.close();
		}
		DhProxyBridge.remove(id);
	}

	/** Start of every client tick: advance pose playback before entities tick. */
	static void tick() {
		checkLevel();
		clientTicks++;
		for (ClientVessel vessel : VESSELS.values()) {
			vessel.tick();
		}
		tickInProgress = true;
	}

	/** Client ticks since start (monotonic, unlike the level's game time, which the server corrects). */
	public static long clientTicks() {
		return clientTicks;
	}

	/** End of every client tick. */
	static void endTick() {
		tickInProgress = false;
	}

	/**
	 * The pose that matches where entities are right now. During a client tick, before entities have moved, vessels
	 * have already advanced to this tick's pose while every entity still stands where the previous tick left it (and
	 * vanilla picks the crosshair target at exactly that moment, before handling clicks): the previous tick's pose
	 * matches. Between ticks (render frames) it is the interpolated pose.
	 */
	@Nullable
	public static VesselPose poseMatchingEntities(ClientVessel vessel, float partialTicks) {
		if (tickInProgress && vessel.previousTickPose() != null) {
			return vessel.previousTickPose();
		}
		return vessel.renderPose(partialTicks);
	}

	/** A plot chunk arrived or left: its vessel's mesh must be rebuilt there. */
	public static void onPlotChunkChanged(int chunkX, int chunkZ) {
		ClientVessel vessel = atPlot(VesselRegion.plotAtChunk(chunkX, chunkZ));
		if (vessel != null) {
			vessel.mesh.markColumnDirty(chunkX, chunkZ);
		}
	}

	public static void onPlotSectionChanged(int sectionX, int sectionY, int sectionZ) {
		ClientVessel vessel = atPlot(VesselRegion.plotAtChunk(sectionX, sectionZ));
		if (vessel != null) {
			vessel.mesh.markSectionAndNeighboursDirty(sectionX, sectionY, sectionZ);
		}
	}

	static void onPlotBlockChanged(BlockPos pos) {
		ClientVessel vessel = atPlotPos(pos);
		if (vessel != null) {
			vessel.mesh.markSectionAndNeighboursDirty(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
				SectionPos.blockToSectionCoord(pos.getZ()));
		}
	}

	/** Places a vessel entity at this tick's pose (called from the entity's own tick). */
	static void tickEntity(VesselEntity entity) {
		ClientVessel vessel = VESSELS.get(entity.vesselId());
		if (vessel == null || !vessel.ready()) {
			return;
		}
		VesselPose pose = vessel.tickPose();
		Vec3 centre = vessel.worldCentre(pose);
		entity.updateFrom(pose, vessel.helm, vessel.helmFacing, centre);
	}
}
