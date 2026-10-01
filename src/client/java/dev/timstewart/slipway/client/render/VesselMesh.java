package dev.timstewart.slipway.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.timstewart.slipway.client.ClientVessel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The cached geometry of a vessel, one mesh per plot chunk section, built from the real blocks of the plot with
 * vanilla's block and fluid renderers (so models, tints, ambient occlusion and light match terrain) and kept in
 * vessel-local coordinates. Every frame the renderer streams it through the vessel's transform; sections are
 * rebuilt only when their blocks or light change, within a time budget.
 */
public final class VesselMesh {
	static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	/** Ints per vertex: x, y, z, colour, u, v, light, packed normal. */
	static final int STRIDE = 8;

	private final ClientVessel vessel;
	private final Long2ObjectMap<SectionMesh> sections = new Long2ObjectOpenHashMap<>();
	private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
	private boolean allDirty = true;
	private boolean frozen;
	private int vertexCount;

	public VesselMesh(ClientVessel vessel) {
		this.vessel = vessel;
	}

	public void markAllDirty() {
		this.allDirty = !this.frozen;
	}

	/**
	 * Builds what is still to build and keeps the mesh as it is from then on, whatever happens to the plot's blocks:
	 * the picture of a vessel that is gone.
	 */
	public void freeze(ClientLevel level) {
		this.rebuild(level, Long.MAX_VALUE / 2);
		this.frozen = true;
		this.allDirty = false;
		this.dirty.clear();
	}

	private int minSectionY() {
		return this.vessel.anchor.getY() + this.vessel.localMin.getY() >> 4;
	}

	private int maxSectionY() {
		return this.vessel.anchor.getY() + this.vessel.localMax.getY() >> 4;
	}

	public void markColumnDirty(int chunkX, int chunkZ) {
		for (int sy = this.minSectionY(); sy <= this.maxSectionY(); sy++) {
			this.markSectionAndNeighboursDirty(chunkX, sy, chunkZ);
		}
	}

	public void markSectionAndNeighboursDirty(int sx, int sy, int sz) {
		if (this.frozen) {
			return;
		}
		this.dirty.add(SectionPos.asLong(sx, sy, sz));
		for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
			long key = SectionPos.asLong(sx + d[0], sy + d[1], sz + d[2]);
			if (this.sections.containsKey(key)) {
				this.dirty.add(key);
			}
		}
	}

	public void clear() {
		this.sections.clear();
		this.dirty.clear();
		this.allDirty = true;
		this.frozen = false;
		this.vertexCount = 0;
	}

	public int vertexCount() {
		return this.vertexCount;
	}

	/** How many vertices lie inside a box given in vessel-local coordinates (to check that a block is drawn where it is). */
	public int vertexCountIn(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		int count = 0;
		for (SectionMesh mesh : this.sections.values()) {
			for (MeshBuffer buffer : mesh.layers) {
				if (buffer == null) {
					continue;
				}
				int[] d = buffer.data;
				for (int v = 0, i = 0; v < buffer.vertices; v++, i += STRIDE) {
					float x = Float.intBitsToFloat(d[i]);
					float y = Float.intBitsToFloat(d[i + 1]);
					float z = Float.intBitsToFloat(d[i + 2]);
					if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
						count++;
					}
				}
			}
		}
		return count;
	}

	/**
	 * The lowest sky light (0 to 15) baked into the faces that look along a direction of the vessel's frame, or -1
	 * without such a face (to check that the faces at the edge of the vessel's chunk columns are lit).
	 */
	public int minSkyLight(net.minecraft.core.Direction direction) {
		return this.minLight(direction, true, null);
	}

	/** As {@link #minSkyLight(net.minecraft.core.Direction)}, of the faces of the one block at a place in the vessel's frame. */
	public int minSkyLight(net.minecraft.core.Direction direction, BlockPos local) {
		return this.minLight(direction, true, local);
	}

	/**
	 * The lowest block light (0 to 15) baked into the faces that look along a direction of the vessel's frame, or -1
	 * without such a face (to check that light from the vessel's own lamps reaches the faces at the edge of its
	 * chunk columns: it gets there through the columns next to them).
	 */
	public int minBlockLight(net.minecraft.core.Direction direction) {
		return this.minLight(direction, false, null);
	}

	private int minLight(net.minecraft.core.Direction direction, boolean sky, @org.jspecify.annotations.Nullable BlockPos block) {
		int wanted = packNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ());
		int min = -1;
		for (SectionMesh mesh : this.sections.values()) {
			for (MeshBuffer buffer : mesh.layers) {
				if (buffer == null) {
					continue;
				}
				int[] d = buffer.data;
				for (int v = 0, i = 0; v < buffer.vertices; v++, i += STRIDE) {
					if (d[i + 7] == wanted && (block == null || within(Float.intBitsToFloat(d[i]) - block.getX(), Float.intBitsToFloat(d[i + 1]) - block.getY(),
						Float.intBitsToFloat(d[i + 2]) - block.getZ()))) {
						int light = sky ? net.minecraft.util.LightCoordsUtil.sky(d[i + 6]) : net.minecraft.util.LightCoordsUtil.block(d[i + 6]);
						min = min < 0 ? light : Math.min(min, light);
					}
				}
			}
		}
		return min;
	}

	private static boolean within(float x, float y, float z) {
		return x > -0.001f && x < 1.001f && y > -0.001f && y < 1.001f && z > -0.001f && z < 1.001f;
	}

	public boolean isEmpty() {
		return this.sections.isEmpty();
	}

	public boolean hasPendingWork() {
		return this.allDirty || !this.dirty.isEmpty();
	}

	/** Rebuilds dirty sections until the budget is spent; returns true when nothing is left to build. */
	public boolean rebuild(ClientLevel level, long budgetNanos) {
		if (!this.vessel.hasInfo || this.frozen) {
			return true;
		}
		if (this.allDirty) {
			this.allDirty = false;
			BlockPos min = this.vessel.anchor.offset(this.vessel.localMin);
			BlockPos max = this.vessel.anchor.offset(this.vessel.localMax);
			for (int sx = min.getX() >> 4; sx <= max.getX() >> 4; sx++) {
				for (int sy = min.getY() >> 4; sy <= max.getY() >> 4; sy++) {
					for (int sz = min.getZ() >> 4; sz <= max.getZ() >> 4; sz++) {
						this.dirty.add(SectionPos.asLong(sx, sy, sz));
					}
				}
			}
		}
		if (this.dirty.isEmpty()) {
			return true;
		}
		long deadline = System.nanoTime() + budgetNanos;
		Builder builder = new Builder(level);
		BlockModelLighter.enableCaching();
		try {
			while (!this.dirty.isEmpty() && System.nanoTime() < deadline) {
				long key = this.dirty.removeFirstLong();
				SectionMesh mesh = builder.build(key, this.vessel.anchor);
				if (mesh == null) {
					this.sections.remove(key);
				} else {
					this.sections.put(key, mesh);
				}
			}
		} finally {
			BlockModelLighter.clearCache();
		}
		int count = 0;
		for (SectionMesh mesh : this.sections.values()) {
			for (MeshBuffer buffer : mesh.layers) {
				if (buffer != null) {
					count += buffer.vertices;
				}
			}
		}
		this.vertexCount = count;
		return this.dirty.isEmpty();
	}

	public boolean hasLayer(ChunkSectionLayer layer) {
		for (SectionMesh mesh : this.sections.values()) {
			MeshBuffer buffer = mesh.layers[layer.ordinal()];
			if (buffer != null && buffer.vertices > 0) {
				return true;
			}
		}
		return false;
	}

	/** Streams every vertex of one layer through {@code pose} into {@code out}. */
	public void emit(ChunkSectionLayer layer, PoseStack.Pose pose, VertexConsumer out) {
		Matrix4f m = pose.pose();
		Matrix3f n = pose.normal();
		Vector3f normal = new Vector3f();
		for (SectionMesh mesh : this.sections.values()) {
			MeshBuffer buffer = mesh.layers[layer.ordinal()];
			if (buffer == null) {
				continue;
			}
			int[] d = buffer.data;
			for (int v = 0, i = 0; v < buffer.vertices; v++, i += STRIDE) {
				float x = Float.intBitsToFloat(d[i]);
				float y = Float.intBitsToFloat(d[i + 1]);
				float z = Float.intBitsToFloat(d[i + 2]);
				float tx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
				float ty = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
				float tz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
				int packed = d[i + 7];
				normal.set((byte)packed / 127f, (byte)(packed >> 8) / 127f, (byte)(packed >> 16) / 127f);
				n.transform(normal);
				out.addVertex(tx, ty, tz, d[i + 3], Float.intBitsToFloat(d[i + 4]), Float.intBitsToFloat(d[i + 5]), OverlayTexture.NO_OVERLAY, d[i + 6],
					normal.x, normal.y, normal.z);
			}
		}
	}

	static int packNormal(float x, float y, float z) {
		return (Math.round(x * 127f) & 0xFF) | (Math.round(y * 127f) & 0xFF) << 8 | (Math.round(z * 127f) & 0xFF) << 16;
	}

	static final class SectionMesh {
		final MeshBuffer[] layers = new MeshBuffer[LAYERS.length];

		MeshBuffer layer(ChunkSectionLayer layer) {
			MeshBuffer buffer = this.layers[layer.ordinal()];
			if (buffer == null) {
				buffer = new MeshBuffer();
				this.layers[layer.ordinal()] = buffer;
			}
			return buffer;
		}

		boolean isEmpty() {
			for (MeshBuffer buffer : this.layers) {
				if (buffer != null && buffer.vertices > 0) {
					return false;
				}
			}
			return true;
		}
	}

	static final class MeshBuffer {
		int[] data = new int[STRIDE * 64];
		int vertices;

		void add(float x, float y, float z, int color, float u, float v, int light, int normal) {
			int i = this.vertices * STRIDE;
			if (i + STRIDE > this.data.length) {
				this.data = Arrays.copyOf(this.data, this.data.length * 2);
			}
			this.data[i] = Float.floatToRawIntBits(x);
			this.data[i + 1] = Float.floatToRawIntBits(y);
			this.data[i + 2] = Float.floatToRawIntBits(z);
			this.data[i + 3] = color;
			this.data[i + 4] = Float.floatToRawIntBits(u);
			this.data[i + 5] = Float.floatToRawIntBits(v);
			this.data[i + 6] = light;
			this.data[i + 7] = normal;
			this.vertices++;
		}
	}

	/** Meshes plot sections with vanilla's renderers; one instance per rebuild pass. */
	private static final class Builder {
		private final ClientLevel level;
		private final ModelBlockRenderer blockRenderer;
		private final FluidRenderer fluidRenderer;
		private final boolean cutoutLeaves;
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		Builder(ClientLevel level) {
			Minecraft mc = Minecraft.getInstance();
			this.level = level;
			this.blockRenderer = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), true, mc.getBlockColors());
			this.fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
			this.cutoutLeaves = mc.options.cutoutLeaves().get();
		}

		SectionMesh build(long sectionKey, BlockPos anchor) {
			var chunk = this.level.getChunkSource().getChunk(SectionPos.x(sectionKey), SectionPos.z(sectionKey), false);
			if (chunk == null) {
				return null;
			}
			int sectionIndex = chunk.getSectionIndexFromSectionY(SectionPos.y(sectionKey));
			if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount() || chunk.getSection(sectionIndex).hasOnlyAir()) {
				return null;
			}
			SectionMesh mesh = new SectionMesh();
			int baseX = SectionPos.x(sectionKey) << 4;
			int baseY = SectionPos.y(sectionKey) << 4;
			int baseZ = SectionPos.z(sectionKey) << 4;
			var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
			float offsetX = baseX - anchor.getX(), offsetY = baseY - anchor.getY(), offsetZ = baseZ - anchor.getZ();
			BlockQuadOutput quads = (x, y, z, quad, instance) -> addQuad(mesh.layer(quad.materialInfo().layer()), x, y, z, quad, instance);
			BlockQuadOutput solidQuads = (x, y, z, quad, instance) -> addQuad(mesh.layer(ChunkSectionLayer.SOLID), x, y, z, quad, instance);
			FluidRenderer.Output fluids = layer -> new RecordingConsumer(mesh.layer(layer), offsetX, offsetY, offsetZ);
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						this.cursor.set(baseX + x, baseY + y, baseZ + z);
						BlockState state = chunk.getBlockState(this.cursor);
						if (state.isAir()) {
							continue;
						}
						FluidState fluid = state.getFluidState();
						if (!fluid.isEmpty()) {
							this.fluidRenderer.tesselate(this.level, this.cursor, fluids, state, fluid);
						}
						if (state.getRenderShape() == RenderShape.MODEL) {
							this.blockRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state) ? solidQuads : quads,
								offsetX + x, offsetY + y, offsetZ + z, this.level, this.cursor, state, models.get(state), state.getSeed(this.cursor));
						}
					}
				}
			}
			return mesh.isEmpty() ? null : mesh;
		}

		private static void addQuad(MeshBuffer buffer, float x, float y, float z, BakedQuad quad, QuadInstance instance) {
			Vector3fc normal = quad.direction().getUnitVec3f();
			int packedNormal = packNormal(normal.x(), normal.y(), normal.z());
			int emission = quad.materialInfo().lightEmission();
			for (int v = 0; v < 4; v++) {
				Vector3fc p = quad.position(v);
				long uv = quad.packedUV(v);
				buffer.add(x + p.x(), y + p.y(), z + p.z(), instance.getColor(v), UVPair.unpackU(uv), UVPair.unpackV(uv),
					instance.getLightCoordsWithEmission(v, emission), packedNormal);
			}
		}
	}

	/** Captures the vertices the fluid renderer writes (section-relative) into a mesh buffer in vessel-local space. */
	private static final class RecordingConsumer implements VertexConsumer {
		private final MeshBuffer buffer;
		private final float offsetX, offsetY, offsetZ;
		private float x, y, z, u, v, nx, ny = 1, nz;
		private int color = -1, light;
		private boolean pending;

		RecordingConsumer(MeshBuffer buffer, float offsetX, float offsetY, float offsetZ) {
			this.buffer = buffer;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.offsetZ = offsetZ;
		}

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			this.flush();
			this.buffer.add(this.offsetX + x, this.offsetY + y, this.offsetZ + z, color, u, v, light, packNormal(nx, ny, nz));
		}

		private void flush() {
			if (this.pending) {
				this.pending = false;
				this.buffer.add(this.offsetX + this.x, this.offsetY + this.y, this.offsetZ + this.z, this.color, this.u, this.v, this.light,
					packNormal(this.nx, this.ny, this.nz));
			}
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			this.flush();
			this.x = x;
			this.y = y;
			this.z = z;
			this.pending = true;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			this.color = (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			this.color = color;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			this.light = (u & 0xFFFF) | (v & 0xFFFF) << 16;
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			this.nx = x;
			this.ny = y;
			this.nz = z;
			this.flush();
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}
}
