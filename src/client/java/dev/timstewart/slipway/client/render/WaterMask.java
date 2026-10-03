package dev.timstewart.slipway.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.math.CubeCut;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.physics.FluidCells;
import dev.timstewart.slipway.physics.Hull;
import dev.timstewart.slipway.vessel.Shelter;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the sea out of a hull for the eye. A vessel's blocks are drawn where the vessel is, but the world's water is
 * still there, so the water's surface would be drawn right through a floating hull: a sheet of water across the
 * hold. Vanilla has the same problem with its boats and solves it with a "water mask": a patch drawn into the depth
 * buffer only, just before the water, so that the water behind it fails the depth test. This draws such patches
 * with the same render type, for any hull: wherever the water's surface cuts through air the hull keeps dry (see
 * {@link Hull}), the piece of the surface inside that cell is covered, a hair above it for the view from above and
 * a hair below it for the view from inside the hull under the waterline.
 *
 * <p>Once a client tick the cells near a surface are picked ({@link #prepare}); every frame their cut with the
 * surface is worked out for the vessel's pose in that frame ({@link #quads}). Like vanilla's patch it hides
 * everything translucent behind it, not only the water.
 */
public final class WaterMask {
	/** How far above and below the water's surface the patches lie. */
	private static final float LIFT = 0.03f;
	/** Half the diagonal of a block: further from a cell's centre than this, a surface does not cut the cell. */
	private static final double REACH = 0.8661;
	/** Columns per side of the vessel's bounds in which the level is searched for water surfaces. */
	private static final int SAMPLES = 4;
	/** Off, hulls are drawn without it (a system property, or a test that compares the two pictures). */
	public static volatile boolean enabled = !Boolean.getBoolean("slipway.noWaterMask");

	private final ClientVessel vessel;
	/** Per picked cell: its centre in the vessel's frame and the height of the surface there. */
	private float[] cells = new float[0];
	private int count;
	/** The hull {@link #dry} was listed for. */
	private Hull listedFor = Hull.EMPTY;
	/** Local block positions (x, y, z, class) of the hull's dry cells. */
	private int[] dry = new int[0];
	private int lastQuads;
	private final CubeCut cut = new CubeCut();

	public WaterMask(ClientVessel vessel) {
		this.vessel = vessel;
	}

	/** Quads drawn for this vessel in the last frame (each is drawn above and below the surface, facing both ways). */
	public int lastQuads() {
		return this.lastQuads;
	}

	/** Cells picked at the last tick. */
	public int pickedCells() {
		return this.count;
	}

	public void clear() {
		this.count = 0;
		this.lastQuads = 0;
	}

	/** Picks the dry cells of the hull that a fluid's surface may cut, for the frames until the next tick. */
	public void prepare(ClientLevel level) {
		this.count = 0;
		Hull hull = this.vessel.hull;
		VesselPose pose = this.vessel.tickPose();
		if (!enabled || !this.vessel.inFluid || pose == null || hull.shelteredVolume() + hull.sealedVolume() <= 0 || this.vessel.gone()) {
			return;
		}
		if (hull != this.listedFor) {
			this.dry = hull.dryCells();
			this.listedFor = hull;
		}
		// Where surfaces are at all: looked for in a few columns of the vessel's bounds, so that a tall vessel does not
		// ask the level about every cell of its cabins.
		FluidCells fluids = Shelter.fluids(level);
		AABB bounds = this.vessel.worldBounds(pose);
		double lowest = Double.MAX_VALUE, highest = -Double.MAX_VALUE;
		int top = (int)Math.floor(bounds.maxY) + 1;
		int bottom = Math.max((int)Math.floor(bounds.minY) - 1, top - 96);
		for (int i = 0; i < SAMPLES; i++) {
			for (int j = 0; j < SAMPLES; j++) {
				double x = bounds.minX + (bounds.maxX - bounds.minX) * (i + 0.5) / SAMPLES;
				double z = bounds.minZ + (bounds.maxZ - bounds.minZ) * (j + 0.5) / SAMPLES;
				for (int y = top; y >= bottom; y -= 2) {
					double surface = Shelter.surfaceNear(fluids, x, y + 0.5, z);
					if (surface == surface) {
						lowest = Math.min(lowest, surface);
						highest = Math.max(highest, surface);
					}
				}
			}
		}
		if (lowest > highest) {
			return;
		}
		lowest -= REACH + 1.0;
		highest += REACH + 1.0;
		byte[] flooded = new byte[hull.pourCount()];
		Vector3d world = new Vector3d();
		for (int i = 0; i < this.dry.length; i += 4) {
			float lx = this.dry[i] + 0.5f, ly = this.dry[i + 1] + 0.5f, lz = this.dry[i + 2] + 0.5f;
			pose.localToWorld(lx, ly, lz, world);
			if (world.y < lowest || world.y > highest) {
				continue;
			}
			double surface = Shelter.surfaceNear(fluids, world.x, world.y, world.z);
			if (surface != surface || Math.abs(surface - world.y) > REACH + 0.25) {
				continue;
			}
			int pour = this.dry[i + 3];
			if (pour >= 0) {
				if (flooded[pour] == 0) {
					flooded[pour] = (byte)(Shelter.flooded(fluids, hull, pose, pour) ? 2 : 1);
				}
				if (flooded[pour] == 2) {
					continue;
				}
			}
			if ((this.count + 1) * 4 > this.cells.length) {
				this.cells = Arrays.copyOf(this.cells, Math.max(64, this.cells.length * 2));
			}
			int at = this.count++ * 4;
			this.cells[at] = lx;
			this.cells[at + 1] = ly;
			this.cells[at + 2] = lz;
			this.cells[at + 3] = (float)surface;
		}
	}

	/**
	 * The patches for a frame: for every picked cell the piece of the fluid's surface inside it at the given pose, as
	 * quads of four corners (x, y, z each) relative to (ox, oy, oz); null when there is none.
	 */
	public float @Nullable [] quads(VesselPose pose, double ox, double oy, double oz) {
		this.lastQuads = 0;
		if (this.count == 0) {
			return null;
		}
		Vector3d c = new Vector3d();
		Vector3d ax = pose.rotate(0.5, 0, 0, new Vector3d());
		Vector3d ay = pose.rotate(0, 0.5, 0, new Vector3d());
		Vector3d az = pose.rotate(0, 0, 0.5, new Vector3d());
		float[] out = new float[this.count * CubeCut.MAX_FLOATS];
		int n = 0;
		for (int i = 0; i < this.count; i++) {
			pose.localToWorld(this.cells[i * 4], this.cells[i * 4 + 1], this.cells[i * 4 + 2], c);
			double h = this.cells[i * 4 + 3];
			if (Math.abs(h - c.y) <= REACH) {
				n = this.cut.quads(c, ax, ay, az, h, ox, oy, oz, out, n);
			}
		}
		this.lastQuads = n / 12;
		return n == 0 ? null : n == out.length ? out : Arrays.copyOf(out, n);
	}

	/**
	 * Hands the patches to the renderer in vanilla's water-mask phase, which is drawn before the water in both of
	 * vanilla's transparency modes. {@code poseStack} stands at the point the quads are relative to, unrotated.
	 */
	public static void submit(SubmitNodeCollector collector, PoseStack poseStack, float[] quads) {
		RenderType type = RenderTypes.waterMask();
		SubmitNodeCollector.CustomGeometryRenderer renderer = (pose, buffer) -> emit(pose, buffer, quads);
		SubmitNodeCollection collection = collector instanceof SubmitNodeStorage storage ? storage.order(0)
			: collector instanceof SubmitNodeCollection direct ? direct : null;
		if (collection != null) {
			collection.waterMask.submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), type, renderer));
		} else {
			// A collector of another kind (wrapped by another mod): as custom geometry it is drawn before the water as
			// well, but for vanilla's "improved transparency".
			collector.submitCustomGeometry(poseStack, type, renderer);
		}
	}

	private static void emit(PoseStack.Pose pose, VertexConsumer buffer, float[] quads) {
		Matrix4f m = pose.pose();
		float[] t = new float[12];
		for (int q = 0; q < quads.length; q += 12) {
			for (int side = 0; side < 2; side++) {
				float lift = side == 0 ? LIFT : -LIFT;
				for (int v = 0; v < 4; v++) {
					float x = quads[q + v * 3], y = quads[q + v * 3 + 1] + lift, z = quads[q + v * 3 + 2];
					t[v * 3] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
					t[v * 3 + 1] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
					t[v * 3 + 2] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
				}
				// Both ways round: whichever side the pipeline culls, one of the two faces the camera.
				for (int v = 0; v < 4; v++) {
					vertex(buffer, t, v);
				}
				for (int v = 3; v >= 0; v--) {
					vertex(buffer, t, v);
				}
			}
		}
	}

	private static void vertex(VertexConsumer buffer, float[] t, int v) {
		buffer.addVertex(t[v * 3], t[v * 3 + 1], t[v * 3 + 2], -1, 0f, 0f, OverlayTexture.NO_OVERLAY, 0, 0f, 1f, 0f);
	}
}
