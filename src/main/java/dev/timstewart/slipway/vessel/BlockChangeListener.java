package dev.timstewart.slipway.vessel;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Routes block changes (from the chunk hook) to the server's vessel manager or the client's vessel state. */
public final class BlockChangeListener {
	/** Installed by the client entrypoint. */
	@Nullable
	public static volatile ClientSink client;

	private BlockChangeListener() {
	}

	public interface ClientSink {
		void onBlockChanged(Level level, BlockPos pos, BlockState state);
	}

	public static void onBlockChanged(Level level, BlockPos pos, BlockState state) {
		if (level instanceof ServerLevel serverLevel) {
			VesselManager manager = VesselManager.getIfPresent(serverLevel);
			if (manager == null) {
				return;
			}
			if (VesselRegion.isReserved(pos)) {
				manager.onPlotBlockChanged(pos, state);
			} else {
				manager.physics().onTerrainChanged(pos);
			}
		} else {
			ClientSink sink = client;
			if (sink != null && VesselRegion.isReserved(pos)) {
				sink.onBlockChanged(level, pos, state);
			}
		}
	}
}
