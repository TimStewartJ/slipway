package dev.timstewart.slipway.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.SlipwayDebug;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.VesselEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Draws a vessel: its cached block mesh through the moving-block render types (terrain pipelines, which Iris
 * shades with its terrain programs and draws into the shadow map), its block entities through their own
 * renderers, the hovered block's outline and any breaking progress, all under the vessel's interpolated transform.
 */
public final class VesselRenderer extends EntityRenderer<VesselEntity, VesselRenderer.State> {
	/** Mesh rebuild budget per frame. */
	private static final long REBUILD_BUDGET_NANOS = 4_000_000L;

	public VesselRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	public static final class State extends EntityRenderState {
		@Nullable
		ClientVessel vessel;
		@Nullable
		VesselPose pose;
		final List<BlockEntityRenderState> blockEntities = new ArrayList<>();
		final List<BlockPos> blockEntityLocal = new ArrayList<>();
		@Nullable
		BlockPos outlineLocal;
		@Nullable
		VoxelShape outlineShape;
		final List<BlockPos> breakingLocal = new ArrayList<>();
		final List<BlockState> breakingStates = new ArrayList<>();
		final List<Integer> breakingProgress = new ArrayList<>();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(VesselEntity entity, float partialTicks) {
		ClientVessel vessel = ClientVessels.drawn(entity.vesselId());
		if (vessel == null || !vessel.ready()) {
			return super.getBoundingBoxForCulling(entity, partialTicks);
		}
		VesselPose pose = vessel.renderPose(partialTicks);
		return vessel.worldBounds(pose).inflate(1.0);
	}

	@Override
	public void extractRenderState(VesselEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.blockEntities.clear();
		state.blockEntityLocal.clear();
		state.breakingLocal.clear();
		state.breakingStates.clear();
		state.breakingProgress.clear();
		state.outlineLocal = null;
		state.outlineShape = null;
		ClientVessel vessel = ClientVessels.drawn(entity.vesselId());
		state.vessel = vessel;
		if (vessel == null || !vessel.ready()) {
			state.pose = null;
			return;
		}
		VesselPose pose = vessel.renderPose(partialTicks);
		state.pose = pose;
		SlipwayDebug.traceFrame(vessel.id, pose, partialTicks);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		vessel.mesh.rebuild(mc.level, REBUILD_BUDGET_NANOS);

		// Block entities, extracted directly (the dispatcher's own distance test would compare plot positions with the camera).
		BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
		Vec3 cameraPlot = vessel.worldToPlot(pose, mc.gameRenderer.mainCamera().position());
		if (vessel.gone()) {
			// The picture of a vessel that is gone: its block entities as they were, and nothing to point at.
			for (BlockEntity blockEntity : vessel.keptBlockEntities) {
				extractBlockEntity(dispatcher, blockEntity, partialTicks, cameraPlot, vessel, state);
			}
			return;
		}
		BlockPos min = vessel.anchor.offset(vessel.localMin);
		BlockPos max = vessel.anchor.offset(vessel.localMax);
		for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
			for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
				LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					extractBlockEntity(dispatcher, blockEntity, partialTicks, cameraPlot, vessel, state);
				}
			}
		}

		// The block under the crosshair, when it belongs to this vessel and vanilla would outline a block now.
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && vessel.containsPlotPos(hit.getBlockPos())
			&& ((dev.timstewart.slipway.client.mixin.GameRendererAccessor)mc.gameRenderer).slipway$shouldRenderBlockOutline()) {
			BlockState hitState = mc.level.getBlockState(hit.getBlockPos());
			if (!hitState.isAir()) {
				state.outlineLocal = hit.getBlockPos().subtract(vessel.anchor);
				state.outlineShape = hitState.getShape(mc.level, hit.getBlockPos(), CollisionContext.of(mc.player));
			}
		}

		// Breaking progress on vessel blocks.
		for (var entry : mc.level.destructionProgress().long2ObjectEntrySet()) {
			BlockPos pos = BlockPos.of(entry.getLongKey());
			SortedSet<BlockDestructionProgress> progress = entry.getValue();
			if (progress != null && !progress.isEmpty() && vessel.containsPlotPos(pos)) {
				state.breakingLocal.add(pos.subtract(vessel.anchor));
				state.breakingStates.add(mc.level.getBlockState(pos));
				state.breakingProgress.add(progress.last().getProgress());
			}
		}
	}

	private static void extractBlockEntity(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTicks, Vec3 cameraPlot, ClientVessel vessel,
		State state) {
		BlockEntityRenderState beState = extractBlockEntity(dispatcher, blockEntity, partialTicks, cameraPlot);
		if (beState != null) {
			BlockPos local = blockEntity.getBlockPos().subtract(vessel.anchor);
			state.blockEntities.add(beState);
			state.blockEntityLocal.add(local);
			SlipwayDebug.blockEntityDrawn(vessel.id, blockEntity, local);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nullable
	private static BlockEntityRenderState extractBlockEntity(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity, float partialTicks, Vec3 cameraPlot) {
		BlockEntityRenderer renderer = dispatcher.getRenderer(blockEntity);
		if (renderer == null || !blockEntity.hasLevel() || !blockEntity.getType().isValid(blockEntity.getBlockState())) {
			return null;
		}
		if (!renderer.shouldRender(blockEntity, cameraPlot)) {
			return null;
		}
		BlockEntityRenderState state = (BlockEntityRenderState)renderer.createRenderState();
		renderer.extractRenderState(blockEntity, state, partialTicks, cameraPlot, null);
		return state;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		ClientVessel vessel = state.vessel;
		VesselPose pose = state.pose;
		if (vessel == null || pose == null) {
			return;
		}
		poseStack.pushPose();
		// The pose stack stands at the entity's interpolated position; move to the vessel's local origin.
		poseStack.translate((float)(pose.x() - state.x), (float)(pose.y() - state.y), (float)(pose.z() - state.z));
		poseStack.rotate(new Quaternionf((float)pose.qx(), (float)pose.qy(), (float)pose.qz(), (float)pose.qw()));

		VesselMesh mesh = vessel.mesh;
		submitLayer(collector, poseStack, mesh, ChunkSectionLayer.SOLID, RenderTypes.solidMovingBlock());
		submitLayer(collector, poseStack, mesh, ChunkSectionLayer.CUTOUT, RenderTypes.cutoutMovingBlock());
		submitLayer(collector, poseStack, mesh, ChunkSectionLayer.TRANSLUCENT, RenderTypes.translucentMovingBlock());

		BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
		for (int i = 0; i < state.blockEntities.size(); i++) {
			BlockPos local = state.blockEntityLocal.get(i);
			poseStack.pushPose();
			poseStack.translate(local.getX(), local.getY(), local.getZ());
			dispatcher.submit(state.blockEntities.get(i), poseStack, collector, camera);
			poseStack.popPose();
		}

		var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		// Breaking progress and the hovered block's outline are main-pass only, like vanilla's (not into shadows).
		boolean shadowPass = IrisShadowPass.active();
		for (int i = 0; !shadowPass && i < state.breakingLocal.size(); i++) {
			BlockPos local = state.breakingLocal.get(i);
			BlockState blockState = state.breakingStates.get(i);
			poseStack.pushPose();
			poseStack.translate(local.getX(), local.getY(), local.getZ());
			List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new ArrayList<>();
			models.get(blockState).collectParts(net.minecraft.util.RandomSource.create(blockState.getSeed(local)), parts);
			collector.submitBreakingBlockModel(poseStack, parts, state.breakingProgress.get(i), false);
			poseStack.popPose();
		}

		if (!shadowPass && state.outlineLocal != null && state.outlineShape != null) {
			poseStack.pushPose();
			poseStack.translate(state.outlineLocal.getX(), state.outlineLocal.getY(), state.outlineLocal.getZ());
			float width = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
			collector.submitShapeOutline(poseStack, state.outlineShape, RenderTypes.linesTranslucent(), ARGB.black(102), width, false);
			poseStack.popPose();
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static void submitLayer(SubmitNodeCollector collector, PoseStack poseStack, VesselMesh mesh, ChunkSectionLayer layer, RenderType type) {
		if (mesh.hasLayer(layer)) {
			collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> mesh.emit(layer, pose, buffer));
		}
	}
}
