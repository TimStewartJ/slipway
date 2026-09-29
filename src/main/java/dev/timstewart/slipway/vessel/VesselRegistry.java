package dev.timstewart.slipway.vessel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.timstewart.slipway.Slipway;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Per-level saved registry of vessels and plot allocation ({@code data/slipway_vessels.dat} of each dimension).
 */
public final class VesselRegistry extends SavedData {
	public static final Codec<VesselRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.LONG.fieldOf("next_id").forGetter(r -> r.nextId),
		Codec.INT.fieldOf("next_plot").forGetter(r -> r.nextPlot),
		Codec.INT.listOf().fieldOf("free_plots").forGetter(r -> List.copyOf(r.freePlots)),
		VesselRecord.CODEC.listOf().fieldOf("vessels").forGetter(r -> List.copyOf(r.vessels.values()))
	).apply(i, VesselRegistry::new));

	// The data version always matches the running game, so no fixer ever runs on this data.
	public static final SavedDataType<VesselRegistry> TYPE = new SavedDataType<>(
		Slipway.id("vessels"), VesselRegistry::new, CODEC, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES
	);

	private long nextId;
	private int nextPlot;
	private final IntList freePlots;
	private final Long2ObjectMap<VesselRecord> vessels = new Long2ObjectLinkedOpenHashMap<>();

	public VesselRegistry() {
		this(1L, 0, List.of(), List.of());
	}

	private VesselRegistry(long nextId, int nextPlot, List<Integer> freePlots, List<VesselRecord> vessels) {
		this.nextId = Math.max(1L, nextId);
		this.nextPlot = Math.max(0, nextPlot);
		this.freePlots = new IntArrayList();
		for (int plot : freePlots) {
			if (plot >= 0 && plot < VesselRegion.MAX_PLOTS && !this.freePlots.contains(plot)) {
				this.freePlots.add(plot);
			}
		}
		for (VesselRecord record : vessels) {
			this.vessels.put(record.id, record);
			this.nextId = Math.max(this.nextId, record.id + 1);
			this.nextPlot = Math.max(this.nextPlot, record.plot + 1);
			this.freePlots.rem(record.plot);
		}
	}

	public long allocateId() {
		this.setDirty();
		return this.nextId++;
	}

	/** Reuses a freed plot when one exists, otherwise takes the next unused one. */
	public int allocatePlot() {
		this.setDirty();
		if (!this.freePlots.isEmpty()) {
			return this.freePlots.removeInt(this.freePlots.size() - 1);
		}
		if (this.nextPlot >= VesselRegion.MAX_PLOTS) {
			throw new IllegalStateException("All " + VesselRegion.MAX_PLOTS + " vessel plots are in use");
		}
		return this.nextPlot++;
	}

	public void freePlot(int plot) {
		if (plot >= 0 && plot < VesselRegion.MAX_PLOTS && !this.freePlots.contains(plot)) {
			this.freePlots.add(plot);
			this.setDirty();
		}
	}

	public void add(VesselRecord record) {
		this.vessels.put(record.id, record);
		this.setDirty();
	}

	@Nullable
	public VesselRecord remove(long id) {
		VesselRecord removed = this.vessels.remove(id);
		if (removed != null) {
			this.setDirty();
		}
		return removed;
	}

	@Nullable
	public VesselRecord get(long id) {
		return this.vessels.get(id);
	}

	@Nullable
	public VesselRecord byPlot(int plot) {
		for (VesselRecord record : this.vessels.values()) {
			if (record.plot == plot) {
				return record;
			}
		}
		return null;
	}

	public Collection<VesselRecord> all() {
		return Collections.unmodifiableCollection(this.vessels.values());
	}

	public int size() {
		return this.vessels.size();
	}

	public List<Integer> freePlots() {
		return new ArrayList<>(this.freePlots);
	}

	public long peekNextId() {
		return this.nextId;
	}
}
