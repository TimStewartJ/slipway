package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.config.SlipwayConfig;
import dev.timstewart.slipway.math.VesselPose;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Moves real blocks between the world and a vessel's plot. Both directions copy every block state and the full
 * saved data of every block entity, then clear the source without drops or block-entity side effects (a chest
 * does not spill), and finally let the surrounding world react to the change once.
 */
public final class VesselAssembly {
	/** Place copies silently: no shape updates, no drops, no block-entity side effects, no on-place logic. */
	static final int COPY_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private VesselAssembly() {
	}

	/** A block copied out of the world or a plot: its state and, if it has one, its block entity's full data. */
	record Snapshot(BlockPos from, BlockState state, @Nullable CompoundTag blockEntity) {
	}

	public record Outcome(boolean success, Component message, @Nullable VesselRecord record) {
		static Outcome fail(Component message) {
			return new Outcome(false, message, null);
		}
	}

	/** Collects the structure connected to a helm and moves it into a new plot. */
	public static Outcome assemble(ServerLevel level, VesselRegistry registry, BlockPos helmPos, SlipwayConfig config) {
		BlockState helmState = level.getBlockState(helmPos);
		if (!(helmState.getBlock() instanceof HelmBlock)) {
			return Outcome.fail(Component.translatable("slipway.assemble.no_helm"));
		}
		AssemblyRules rules = new AssemblyRules(config);
		StructureScan.Result scan = StructureScan.scan(helmPos, rules.grid(level, helmPos), config.maxVesselBlocks, config.maxVesselSpan);
		switch (scan.failure()) {
			case TOO_MANY_BLOCKS -> {
				return Outcome.fail(Component.translatable("slipway.assemble.too_many", config.maxVesselBlocks));
			}
			case TOO_LARGE -> {
				return Outcome.fail(Component.translatable("slipway.assemble.too_large", config.maxVesselSpan));
			}
			case UNLOADED -> {
				return Outcome.fail(Component.translatable("slipway.assemble.unloaded"));
			}
			case NOTHING -> {
				return Outcome.fail(Component.translatable("slipway.assemble.no_helm"));
			}
			case NONE -> {
			}
		}
		BlockPos localMin = scan.min().subtract(helmPos);
		BlockPos localMax = scan.max().subtract(helmPos);
		if (!VesselRegion.fitsInPlot(localMin.getX(), localMin.getZ()) || !VesselRegion.fitsInPlot(localMax.getX(), localMax.getZ())) {
			return Outcome.fail(Component.translatable("slipway.assemble.too_large", config.maxVesselSpan));
		}

		long id = registry.allocateId();
		int plot = registry.allocatePlot();
		BlockPos anchor = VesselRegion.anchor(plot, helmPos.getY());
		loadPlotChunks(level, anchor.offset(localMin), anchor.offset(localMax));

		List<Snapshot> snapshots = new ArrayList<>(scan.size());
		for (long key : scan.positions()) {
			snapshots.add(snapshot(level, BlockPos.of(key)));
		}
		for (Snapshot s : snapshots) {
			place(level, anchor.offset(s.from().subtract(helmPos)), s.state(), s.blockEntity());
		}
		clear(level, snapshots);
		updateEdges(level, snapshots);

		Direction facing = helmState.getValue(HelmBlock.FACING);
		VesselRecord record = new VesselRecord(
			id, plot, anchor, localMin, localMax, BlockPos.ZERO, facing,
			VesselPose.at(helmPos.getX(), helmPos.getY(), helmPos.getZ()), Vec3.ZERO, Vec3.ZERO,
			true, true, snapshots.size()
		);
		registry.add(record);
		Slipway.LOGGER.info("Assembled vessel {} from {} blocks at {} into plot {} (anchor {})", id, snapshots.size(), helmPos.toShortString(), plot, anchor.toShortString());
		return new Outcome(true, Component.translatable("slipway.assemble.done", snapshots.size()), record);
	}

	/** Where each vessel block would land if disassembled now, or why it cannot be. */
	public record Placement(int quarterTurns, BlockPos worldAnchor, @Nullable Component refusal) {
	}

	public static Placement plan(ServerLevel level, VesselRecord record, SlipwayConfig config) {
		VesselPose pose = record.pose;
		double tilt = pose.tiltDegrees();
		if (tilt > config.disassemblyTiltDegrees) {
			return new Placement(0, BlockPos.ZERO, Component.translatable("slipway.disassemble.tilted",
				String.format(java.util.Locale.ROOT, "%.0f", tilt), String.format(java.util.Locale.ROOT, "%.0f", config.disassemblyTiltDegrees)));
		}
		int turns = pose.nearestQuarterTurns();
		Vector3d anchorCentre = pose.localToWorld(0.5, 0.5, 0.5, new Vector3d());
		BlockPos worldAnchor = BlockPos.containing(anchorCentre.x, anchorCentre.y, anchorCentre.z);
		BlockPos min = record.plotMin();
		BlockPos max = record.plotMax();
		for (BlockPos plotPos : BlockPos.betweenClosed(min, max)) {
			BlockState state = level.getBlockState(plotPos);
			if (state.isAir()) {
				continue;
			}
			BlockPos target = worldTarget(worldAnchor, record.toLocal(plotPos), turns);
			if (level.isOutsideBuildHeight(target.getY()) || VesselRegion.isReserved(target) || !level.getWorldBorder().isWithinBounds(target)) {
				return new Placement(turns, worldAnchor, Component.translatable("slipway.disassemble.outside", target.toShortString()));
			}
			if (level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) == null) {
				return new Placement(turns, worldAnchor, Component.translatable("slipway.disassemble.unloaded", target.toShortString()));
			}
			BlockState existing = level.getBlockState(target);
			if (!existing.isAir() && !(existing.canBeReplaced() && !existing.hasBlockEntity())) {
				return new Placement(turns, worldAnchor, Component.translatable("slipway.disassemble.blocked",
					target.toShortString(), existing.getBlock().getName()));
			}
		}
		return new Placement(turns, worldAnchor, null);
	}

	/**
	 * Lets every piston in the plot finish its stroke now. A block in mid-move is a block entity that knows what it
	 * becomes and which way it goes in the plot's frame; it cannot be turned with the vessel or carried over half way.
	 */
	static void finishPistonMoves(ServerLevel level, VesselRecord record) {
		List<PistonMovingBlockEntity> moving = new ArrayList<>();
		BlockPos min = record.plotMin();
		BlockPos max = record.plotMax();
		for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
			for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof PistonMovingBlockEntity piston) {
						moving.add(piston);
					}
				}
			}
		}
		for (PistonMovingBlockEntity piston : moving) {
			// Two half steps and the step that puts the block down.
			for (int step = 0; step < 4 && !piston.isRemoved(); step++) {
				PistonMovingBlockEntity.tick(level, piston.getBlockPos(), piston.getBlockState(), piston);
			}
		}
	}

	/** Moves the vessel's blocks back into the world; the caller has checked {@link #plan} and removes the record. */
	public static int disassemble(ServerLevel level, VesselRecord record, Placement placement) {
		Rotation rotation = VesselPose.minecraftRotation(placement.quarterTurns());
		List<Snapshot> snapshots = new ArrayList<>();
		for (BlockPos plotPos : BlockPos.betweenClosed(record.plotMin(), record.plotMax())) {
			BlockState state = level.getBlockState(plotPos);
			if (!state.isAir()) {
				snapshots.add(snapshot(level, plotPos.immutable()));
			}
		}
		List<Snapshot> placed = new ArrayList<>(snapshots.size());
		for (Snapshot s : snapshots) {
			BlockPos target = worldTarget(placement.worldAnchor(), record.toLocal(s.from()), placement.quarterTurns());
			BlockState rotated = s.state().rotate(rotation);
			place(level, target, rotated, s.blockEntity());
			placed.add(new Snapshot(target, rotated, null));
		}
		clear(level, snapshots);
		updateEdges(level, placed);
		Slipway.LOGGER.info("Disassembled vessel {} ({} blocks) at {} with {} quarter turns", record.id, snapshots.size(),
			placement.worldAnchor().toShortString(), placement.quarterTurns());
		return snapshots.size();
	}

	/** Deletes every block of a vessel's plot without drops (the admin remove command); returns how many. */
	public static int erase(ServerLevel level, VesselRecord record) {
		loadPlotChunks(level, record.plotMin(), record.plotMax());
		List<Snapshot> blocks = new ArrayList<>();
		for (BlockPos plotPos : BlockPos.betweenClosed(record.plotMin(), record.plotMax())) {
			BlockState state = level.getBlockState(plotPos);
			if (!state.isAir()) {
				blocks.add(new Snapshot(plotPos.immutable(), state, state.hasBlockEntity() ? new CompoundTag() : null));
			}
		}
		clear(level, blocks);
		return blocks.size();
	}

	static BlockPos worldTarget(BlockPos worldAnchor, BlockPos local, int quarterTurns) {
		int[] xz = VesselPose.rotateQuarterTurns(local.getX(), local.getZ(), quarterTurns);
		return worldAnchor.offset(xz[0], local.getY(), xz[1]);
	}

	/**
	 * Loads (generating empty if new) every plot chunk column covering the given plot bounds and the ring of columns
	 * around them, which viewers are sent as well (see {@link ActiveVessel#viewChunks}): loaded here, they go out with
	 * the vessel's own columns the moment it is assembled.
	 */
	static void loadPlotChunks(ServerLevel level, BlockPos plotMin, BlockPos plotMax) {
		for (int cx = (plotMin.getX() >> 4) - 1; cx <= (plotMax.getX() >> 4) + 1; cx++) {
			for (int cz = (plotMin.getZ() >> 4) - 1; cz <= (plotMax.getZ() >> 4) + 1; cz++) {
				level.getChunk(cx, cz);
			}
		}
	}

	static Snapshot snapshot(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		CompoundTag tag = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
		return new Snapshot(pos.immutable(), state, tag);
	}

	static void place(ServerLevel level, BlockPos target, BlockState state, @Nullable CompoundTag blockEntityData) {
		level.setBlock(target, state, COPY_FLAGS);
		if (blockEntityData == null) {
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(target);
		if (blockEntity != null) {
			try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), Slipway.LOGGER)) {
				blockEntity.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), blockEntityData));
			}
			blockEntity.setChanged();
		} else {
			BlockEntity loaded = BlockEntity.loadStatic(target, state, blockEntityData, level.registryAccess());
			if (loaded != null) {
				level.setBlockEntity(loaded);
			}
		}
		// The block entity's data changed after the block reached clients; send it again.
		level.sendBlockUpdated(target, state, state, Block.UPDATE_CLIENTS);
	}

	/** Removes copied source blocks without drops or block-entity side effects. */
	static void clear(ServerLevel level, List<Snapshot> sources) {
		for (Snapshot s : sources) {
			if (s.blockEntity() != null) {
				level.removeBlockEntity(s.from());
			}
		}
		for (Snapshot s : sources) {
			level.setBlock(s.from(), Blocks.AIR.defaultBlockState(), COPY_FLAGS);
		}
	}

	/** Lets blocks around a changed region update their shapes (fences, panes, wires) and notifies neighbours once. */
	static void updateEdges(ServerLevel level, List<Snapshot> changed) {
		java.util.Set<BlockPos> region = new java.util.HashSet<>(changed.size() * 2);
		for (Snapshot s : changed) {
			region.add(s.from());
		}
		for (Snapshot s : changed) {
			for (Direction direction : Direction.values()) {
				BlockPos neighbour = s.from().relative(direction);
				if (region.contains(neighbour) || VesselRegion.isReserved(neighbour)) {
					continue;
				}
				BlockState neighbourState = level.getBlockState(neighbour);
				if (neighbourState.isAir()) {
					continue;
				}
				BlockState updated = Block.updateFromNeighbourShapes(neighbourState, level, neighbour);
				if (updated != neighbourState) {
					level.setBlock(neighbour, updated, Block.UPDATE_ALL);
				}
				level.neighborChanged(neighbour, level.getBlockState(s.from()).getBlock(), null);
			}
		}
	}
}
