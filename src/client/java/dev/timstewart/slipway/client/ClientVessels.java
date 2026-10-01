package dev.timstewart.slipway.client;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.vessel.VesselEntity;
import dev.timstewart.slipway.vessel.VesselRegion;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
	/** Vessels assembled moments ago whose world LODs still need refreshing (once their pose is known). */
	private static final LongOpenHashSet JUST_ASSEMBLED = new LongOpenHashSet();
	/**
	 * Vessels that are gone on the server (disassembled or removed) and still drawn. The blocks of a disassembled
	 * vessel arrive as world blocks in the same tick, but the terrain renderer shows them a little later (it rebuilds
	 * the sections off the render thread); without this the ship blinked out for about two ticks. The vessel's picture
	 * is kept until the terrain renderer has nothing left to build, at least {@link #GONE_MIN_TICKS} and at most
	 * {@link #GONE_MAX_TICKS} ticks; the server keeps the vessel's entity, which the picture is drawn with, that long.
	 */
	private static final Long2ObjectMap<ClientVessel> GONE = new Long2ObjectLinkedOpenHashMap<>();
	/** Ticks a gone vessel is drawn at least: the terrain renderer only starts on the new blocks in the next frames. */
	static final int GONE_MIN_TICKS = 2;
	static final int GONE_MAX_TICKS = dev.timstewart.slipway.vessel.VesselManager.RETIRED_ENTITY_TICKS;
	/** Turned off by a test to measure the blink this prevents. */
	public static boolean keepGoneVessels = true;
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

	/** The vessel to draw for an entity: the live one, or the kept picture of one that is gone while it is still due. */
	@Nullable
	public static ClientVessel drawn(long id) {
		ClientVessel vessel = VESSELS.get(id);
		if (vessel != null) {
			return vessel;
		}
		vessel = GONE.get(id);
		return vessel != null && !goneLongEnough(vessel) ? vessel : null;
	}

	/** Whether the terrain has taken over from a gone vessel's picture (asked every frame, so both are never drawn for long). */
	private static boolean goneLongEnough(ClientVessel vessel) {
		long age = clientTicks - vessel.goneAtTick;
		return age >= GONE_MAX_TICKS || age >= GONE_MIN_TICKS && TerrainProgress.complete(Minecraft.getInstance());
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
		for (ClientVessel vessel : GONE.values()) {
			vessel.close();
		}
		VESSELS.clear();
		GONE.clear();
		BY_PLOT.clear();
		JUST_ASSEMBLED.clear();
		level = null;
		DhProxyBridge.clearAll();
	}

	static void onInfo(SlipwayPayloads.VesselInfo info) {
		ClientVessel vessel = getOrCreate(info.vesselId());
		vessel.applyInfo(info);
		BY_PLOT.put(vessel.plot(), vessel.id);
		if (info.assembled()) {
			JUST_ASSEMBLED.add(info.vesselId());
		}
	}

	static void onPose(SlipwayPayloads.PoseUpdate update) {
		getOrCreate(update.vesselId()).applyPose(update);
	}

	/** The server stopped showing a vessel up close ({@code keepProxy}) or removed it entirely. */
	static void onGone(long id, boolean keepProxy) {
		checkLevel();
		ClientVessel vessel = VESSELS.remove(id);
		if (vessel != null) {
			BY_PLOT.remove(vessel.plot());
			if (!keepProxy && vessel.ready()) {
				// Disassembled (its blocks are back in the world) or removed: refresh the world's LODs there.
				DhProxyBridge.refreshWorld(vessel.worldBounds(vessel.tickPose()).inflate(2.0));
			}
			if (!keepProxy && vessel.ready() && keepGoneVessels && level != null) {
				// Its plot chunks are still here (they are dropped by the packets that follow this one).
				vessel.keepPicture(level, clientTicks);
				GONE.put(id, vessel);
			} else {
				vessel.close();
			}
		}
		JUST_ASSEMBLED.remove(id);
		if (!keepProxy) {
			DhProxyBridge.remove(id);
		}
	}

	/** Start of every client tick: advance pose playback before entities tick. */
	static void tick() {
		checkLevel();
		clientTicks++;
		for (ClientVessel vessel : VESSELS.values()) {
			vessel.tick();
		}
		if (!GONE.isEmpty()) {
			var gone = GONE.values().iterator();
			while (gone.hasNext()) {
				ClientVessel vessel = gone.next();
				if (goneLongEnough(vessel)) {
					vessel.close();
					gone.remove();
				} else {
					// Its last poses are still played out.
					vessel.tick();
				}
			}
		}
		if (!JUST_ASSEMBLED.isEmpty()) {
			// The blocks of a just-assembled vessel left the world where it stands: refresh the world's LODs there.
			var ids = JUST_ASSEMBLED.iterator();
			while (ids.hasNext()) {
				ClientVessel vessel = VESSELS.get(ids.nextLong());
				if (vessel == null) {
					ids.remove();
				} else if (vessel.ready()) {
					DhProxyBridge.refreshWorld(vessel.worldBounds(vessel.tickPose()).inflate(1.0));
					ids.remove();
				}
			}
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
		ClientVessel vessel = drawn(entity.vesselId());
		if (vessel == null || !vessel.ready()) {
			return;
		}
		VesselPose pose = vessel.tickPose();
		Vec3 centre = vessel.worldCentre(pose);
		entity.updateFrom(pose, vessel.helm, vessel.helmFacing, centre);
	}
}
