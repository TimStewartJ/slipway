package dev.timstewart.slipway.client;

import dev.timstewart.slipway.client.dh.DhProxies;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.SlipwayPayloads;
import dev.timstewart.slipway.vessel.VesselCollisions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Read-only introspection (and scripted piloting) for the end-to-end test agent, which calls these methods by
 * reflection. Nothing here grants anything a normal client could not already do.
 */
public final class SlipwayDebug {
	private SlipwayDebug() {
	}

	/** One line per known vessel: id, readiness, pose and attitude, mesh size, modes. */
	public static String vessels() {
		StringBuilder out = new StringBuilder();
		for (ClientVessel vessel : ClientVessels.all()) {
			VesselPose pose = vessel.tickPose();
			if (out.length() > 0) {
				out.append(" | ");
			}
			if (pose == null) {
				out.append("#").append(vessel.id).append(" pending");
				continue;
			}
			double[] a = pose.attitudeDegrees();
			Vec3 centre = vessel.worldCentre(pose);
			out.append(String.format(Locale.ROOT, "#%d ready=%s centre=%.3f,%.3f,%.3f pos=%.3f,%.3f,%.3f pitch=%.2f yaw=%.2f roll=%.2f tilt=%.2f vertices=%d blocks=%d speed=%.3f hover=%s level=%s loose=%s body=%s",
				vessel.id, vessel.ready(), centre.x, centre.y, centre.z, pose.x(), pose.y(), pose.z(), a[0], a[1], a[2], pose.tiltDegrees(),
				vessel.mesh.vertexCount(), vessel.blocks, vessel.velocity.length(), vessel.hover, vessel.level, vessel.loose, vessel.hasBody));
		}
		return out.length() == 0 ? "none" : out.toString();
	}

	/** What the crosshair is on, with the vessel-local position when it is a vessel block. */
	public static String hit() {
		Minecraft mc = Minecraft.getInstance();
		HitResult hit = mc.hitResult;
		if (hit == null) {
			return "none";
		}
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = block.getBlockPos();
			ClientVessel vessel = ClientVessels.atPlotPos(pos);
			String state = mc.level == null ? "?" : mc.level.getBlockState(pos).toString();
			if (vessel != null) {
				BlockPos local = pos.subtract(vessel.anchor);
				return "vessel " + vessel.id + " local " + local.toShortString() + " face " + block.getDirection() + " " + state;
			}
			return "block " + pos.toShortString() + " face " + block.getDirection() + " " + state;
		}
		return hit.getType().toString();
	}

	/** Deck state of the local player. */
	public static String rider() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return "no player";
		}
		VesselCollisions.Rider rider = (VesselCollisions.Rider)mc.player;
		String local = "";
		ClientVessel vessel = ClientVessels.get(rider.slipway$carrier());
		if (vessel != null && vessel.ready()) {
			Vec3 l = vessel.tickPose().worldToLocal(mc.player.position());
			local = String.format(Locale.ROOT, " local=%.3f,%.3f,%.3f", l.x, l.y, l.z);
		}
		return String.format(Locale.ROOT, "carrier=%d onGround=%s lastContact=%d tick=%d pos=%.3f,%.3f,%.3f vehicle=%s%s", rider.slipway$carrier(),
			mc.player.onGround(), rider.slipway$lastContactTick(), mc.player.tickCount, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
			mc.player.getVehicle() == null ? "none" : mc.player.getVehicle().getType().toShortString(), local);
	}

	/** Pilot for some ticks with fixed axes: forward, strafe, vertical, pitch, yaw, roll. */
	public static String helm(float forward, float strafe, float vertical, float pitch, float yaw, float roll, int ticks) {
		HelmControls.script(new float[] {forward, strafe, vertical, pitch, yaw, roll}, ticks);
		return HelmControls.pilotedVessel() == null ? "not piloting" : "piloting for " + ticks + " ticks";
	}

	/** Turns the local player's view to a point given in a vessel's local coordinates; reports the world point. */
	public static String lookAtLocal(long id, double x, double y, double z) {
		Minecraft mc = Minecraft.getInstance();
		ClientVessel vessel = ClientVessels.get(id);
		if (mc.player == null || vessel == null || !vessel.ready()) {
			return "no such vessel";
		}
		Vec3 world = vessel.tickPose().localToWorld(new Vec3(x, y, z));
		Vec3 eye = mc.player.getEyePosition();
		double dx = world.x - eye.x;
		double dy = world.y - eye.y;
		double dz = world.z - eye.z;
		float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float)-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO = yaw;
		mc.player.xRotO = pitch;
		mc.player.setYHeadRot(yaw);
		return String.format(Locale.ROOT, "looking %.2f %.2f at %.3f,%.3f,%.3f distance %.2f", yaw, pitch, world.x, world.y, world.z, world.distanceTo(eye));
	}

	/** World position of a vessel-local point, as "x y z". */
	public static String worldOf(long id, double x, double y, double z) {
		ClientVessel vessel = ClientVessels.get(id);
		if (vessel == null || !vessel.ready()) {
			return "no such vessel";
		}
		Vec3 world = vessel.tickPose().localToWorld(new Vec3(x, y, z));
		return String.format(Locale.ROOT, "%.4f %.4f %.4f", world.x, world.y, world.z);
	}

	/** Sends a raw helm control packet, whatever the local state (used to prove the server refuses forged input). */
	public static String forgeHelm(long vesselId, float forward, float strafe, float vertical, float pitch, float yaw, float roll, int toggles) {
		ClientPlayNetworking.send(new SlipwayPayloads.HelmControl(vesselId, 424242, forward, strafe, vertical, pitch, yaw, roll, (byte)toggles));
		return "sent";
	}

	/** Sends many helm control packets in one tick (to prove the server's rate limit). */
	public static String forgeHelmBurst(long vesselId, int count) {
		int n = Math.max(0, Math.min(1000, count));
		for (int i = 0; i < n; i++) {
			ClientPlayNetworking.send(new SlipwayPayloads.HelmControl(vesselId, 500000 + i, 0.1f, 0f, 0f, 0f, 0f, 0f, (byte)0));
		}
		return "sent " + n;
	}

	/** Distant Horizons proxy groups and boxes registered. */
	public static String dh() {
		if (!DhProxyBridge.present()) {
			return "distant horizons absent";
		}
		int[] stats = DhProxies.stats();
		return "groups=" + stats[0] + " boxes=" + stats[1] + " " + DhProxies.describe();
	}

	/** What Distant Horizons' LOD data holds at a world block (to check that no stale LOD is left behind). */
	public static String dhBlockAt(int x, int y, int z) {
		return DhProxyBridge.present() ? DhProxies.lodBlockAt(x, y, z) : "distant horizons absent";
	}

	/** The client's block state at a world position (and whether that chunk is loaded on the client). */
	public static String blockAt(int x, int y, int z) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return "no level";
		}
		BlockPos pos = new BlockPos(x, y, z);
		boolean loaded = mc.level.getChunkSource().getChunk(x >> 4, z >> 4, false) != null;
		return (loaded ? "" : "unloaded ") + mc.level.getBlockState(pos);
	}

	/** What the local player's collision sees just below its feet, and the movement state that decides a fall. */
	public static String footing() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return "no player";
		}
		net.minecraft.world.phys.AABB box = mc.player.getBoundingBox();
		net.minecraft.world.phys.AABB below = box.expandTowards(0.0, -0.2, 0.0);
		StringBuilder shapes = new StringBuilder();
		int count = 0;
		for (net.minecraft.world.phys.shapes.VoxelShape shape : mc.level.getBlockCollisions(mc.player, below)) {
			if (count++ < 4) {
				shapes.append(' ').append(shape.bounds());
			}
		}
		int cx = net.minecraft.util.Mth.floor(mc.player.getX()) >> 4, cz = net.minecraft.util.Mth.floor(mc.player.getZ()) >> 4;
		Object chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
		Object forCollisions = mc.level.getChunkForCollisions(cx, cz);
		Vec3 v = mc.player.getDeltaMovement();
		return String.format(Locale.ROOT, "y=%.4f vel=%.4f,%.4f,%.4f onGround=%s flying=%s noPhysics=%s collisions=%d%s chunk=%s collisionChunk=%s",
			mc.player.getY(), v.x, v.y, v.z, mc.player.onGround(), mc.player.getAbilities().flying, mc.player.noPhysics, count, shapes,
			chunk == null ? "null" : chunk.getClass().getSimpleName(), forCollisions == null ? "null" : forCollisions.getClass().getSimpleName());
	}

	/** Live Jolt engines and bodies in this process (an integrated server's physics runs here too), and Slipway's managers. */
	public static String natives() {
		return "engines=" + dev.timstewart.slipway.physics.jolt.JoltEngine.liveEngines() + " bodies=" + dev.timstewart.slipway.physics.jolt.JoltEngine.liveBodies()
			+ " managers=" + dev.timstewart.slipway.vessel.VesselManager.managerCount() + " clientVessels=" + ClientVessels.all().size();
	}

	private static final int TRACE_LIMIT = 20_000;
	private static long traceId = -1;
	private static final List<double[]> TRACE = new ArrayList<>();
	private static long blockEntitiesId = -1;
	private static final java.util.Set<String> DRAWN_BLOCK_ENTITIES = new java.util.LinkedHashSet<>();
	/** Vessel-local position of a watched block entity to the light it was last drawn with while its vessel was there. */
	private static final java.util.Map<BlockPos, Integer> LIVE_BLOCK_ENTITY_LIGHT = new java.util.HashMap<>();
	private static final java.util.Map<BlockPos, KeptLight> KEPT_BLOCK_ENTITY_LIGHT = new java.util.LinkedHashMap<>();
	private static final int RIDER_TRACE_LIMIT = 4_000;
	private static long riderTraceId = -1;
	private static final List<String> RIDER_TRACE = new ArrayList<>();
	private static final List<Double> RIDER_TRACE_Y = new ArrayList<>();

	/** Starts recording the local player's deck state every client tick, relative to one vessel. */
	public static String riderTraceStart(long id) {
		RIDER_TRACE.clear();
		RIDER_TRACE_Y.clear();
		riderTraceId = id;
		return "rider trace " + id;
	}

	/** End of every client tick while a rider trace runs. */
	static void riderTraceTick() {
		Minecraft mc = Minecraft.getInstance();
		if (riderTraceId < 0 || mc.player == null || RIDER_TRACE.size() >= RIDER_TRACE_LIMIT) {
			return;
		}
		ClientVessel vessel = ClientVessels.get(riderTraceId);
		if (vessel == null || !vessel.ready()) {
			return;
		}
		VesselPose pose = vessel.tickPose();
		VesselPose previous = vessel.previousTickPose() == null ? pose : vessel.previousTickPose();
		Vec3 l = pose.worldToLocal(mc.player.position());
		VesselCollisions.Rider rider = (VesselCollisions.Rider)mc.player;
		VesselCollisions.Contact c = rider.slipway$contact();
		Vec3 v = mc.player.getDeltaMovement();
		double[] a = pose.attitudeDegrees();
		RIDER_TRACE_Y.add(l.y);
		RIDER_TRACE.add(String.format(Locale.ROOT,
			"t=%d local=%.3f,%.3f,%.3f ground=%s carrier=%d carried=%s contact=%s%s%s%s stage=%d boxMinY=%.9f lift=%.3f vel=%.3f,%.3f,%.3f pose.y=%.3f dy=%.4f pitch=%.1f yaw=%.1f roll=%.1f",
			mc.level.getGameTime(), l.x, l.y, l.z, mc.player.onGround(), rider.slipway$carrier(), rider.slipway$lastCarryTick() == mc.player.tickCount,
			c.touched ? "T" : "-", c.ground ? "G" : "-", c.steep ? "S" : "-", c.wall ? "W" : "-", c.stage, c.boxMinY, c.lift, v.x, v.y, v.z, pose.y(),
			pose.y() - previous.y(), a[0], a[1], a[2]));
	}

	/** Stops the rider trace: the lowest local y and the ticks around it. */
	public static String riderTraceStop() {
		riderTraceId = -1;
		if (RIDER_TRACE.isEmpty()) {
			return "samples=0";
		}
		int worst = 0;
		for (int i = 1; i < RIDER_TRACE_Y.size(); i++) {
			if (RIDER_TRACE_Y.get(i) < RIDER_TRACE_Y.get(worst)) {
				worst = i;
			}
		}
		StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "samples=%d minY=%.4f at %d", RIDER_TRACE.size(), RIDER_TRACE_Y.get(worst), worst));
		for (int i = Math.max(0, worst - 6); i <= Math.min(RIDER_TRACE.size() - 1, worst + 3); i++) {
			out.append(" || ").append(RIDER_TRACE.get(i));
		}
		return out.toString();
	}

	/**
	 * How a block entity was lit in the kept picture of a vessel that is gone: how often it was drawn, the range of sky
	 * and block light (0 to 15) it was drawn with, and how often that was not the light of its last draw while the
	 * vessel was there (every time, when it was never drawn then).
	 */
	public record KeptLight(int draws, int darkestSky, int brightestSky, int darkestBlock, int brightestBlock, int changed) {
		KeptLight drawn(int lightCoords, @org.jspecify.annotations.Nullable Integer before) {
			int sky = net.minecraft.util.LightCoordsUtil.sky(lightCoords);
			int block = net.minecraft.util.LightCoordsUtil.block(lightCoords);
			return new KeptLight(this.draws + 1, Math.min(this.darkestSky, sky), Math.max(this.brightestSky, sky), Math.min(this.darkestBlock, block),
				Math.max(this.brightestBlock, block), this.changed + (before != null && before == lightCoords ? 0 : 1));
		}
	}

	/** Starts collecting which block entities of a vessel the renderer draws, and with what light. */
	public static String blockEntitiesStart(long id) {
		DRAWN_BLOCK_ENTITIES.clear();
		LIVE_BLOCK_ENTITY_LIGHT.clear();
		KEPT_BLOCK_ENTITY_LIGHT.clear();
		blockEntitiesId = id;
		return "watching block entities of " + id;
	}

	/** Called by the renderer for every block entity it has a render state for. */
	public static void blockEntityDrawn(long id, net.minecraft.world.level.block.entity.BlockEntity blockEntity, BlockPos local, int lightCoords, boolean kept) {
		if (id != blockEntitiesId) {
			return;
		}
		if (DRAWN_BLOCK_ENTITIES.size() < TRACE_LIMIT) {
			DRAWN_BLOCK_ENTITIES.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()) + "@" + local.toShortString());
		}
		if (kept) {
			KEPT_BLOCK_ENTITY_LIGHT.put(local, KEPT_BLOCK_ENTITY_LIGHT.getOrDefault(local, new KeptLight(0, 15, 0, 15, 0, 0)).drawn(lightCoords, LIVE_BLOCK_ENTITY_LIGHT.get(local)));
		} else {
			LIVE_BLOCK_ENTITY_LIGHT.put(local, lightCoords);
		}
	}

	/** The packed light each block entity of the watched vessel was last drawn with while the vessel was there, by vessel-local position. */
	public static java.util.Map<BlockPos, Integer> blockEntityLight() {
		return new java.util.HashMap<>(LIVE_BLOCK_ENTITY_LIGHT);
	}

	/** Since the start: how each block entity of the watched vessel was lit in the kept picture, by vessel-local position. */
	public static java.util.Map<BlockPos, KeptLight> keptBlockEntityLight() {
		return new java.util.LinkedHashMap<>(KEPT_BLOCK_ENTITY_LIGHT);
	}

	/** Stops collecting: every block entity drawn since the start, as "type@x, y, z" (vessel-local), one per line. */
	public static String blockEntitiesStop() {
		blockEntitiesId = -1;
		String out = String.join("\n", DRAWN_BLOCK_ENTITIES);
		DRAWN_BLOCK_ENTITIES.clear();
		return out;
	}

	/** Starts recording the rendered pose of a vessel every frame (for the smoothness check). */
	public static String traceStart(long id) {
		TRACE.clear();
		traceId = id;
		return "tracing " + id;
	}

	/** Called by the renderer with the pose it draws; records one sample per distinct frame time. */
	public static void traceFrame(long id, VesselPose pose, float partialTicks) {
		if (id != traceId || TRACE.size() >= TRACE_LIMIT) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		double time = ClientVessels.clientTicks() + partialTicks;
		if (!TRACE.isEmpty() && TRACE.getLast()[0] == time) {
			return;
		}
		TRACE.add(new double[] {time, pose.x(), pose.y(), pose.z(), pose.qx(), pose.qy(), pose.qz(), pose.qw()});
	}

	/**
	 * Stops recording and summarises motion between frames: speed in blocks per tick, the largest change of velocity
	 * between consecutive frames (0 for perfectly smooth constant motion), direction reversals while moving and the
	 * largest rotation between frames. Gaps of more than {@value #STALL_TICKS} ticks between frames are the client
	 * stalling (not the vessel jumping): they are counted and reported, and velocity is not compared across them.
	 */
	public static String traceStop() {
		traceId = -1;
		int frames = TRACE.size();
		if (frames < 3) {
			return "frames=" + frames;
		}
		double maxDeltaV = 0.0;
		double maxStep = 0.0;
		double maxTurn = 0.0;
		double speedSum = 0.0;
		double maxGap = 0.0;
		int reversals = 0;
		int intervals = 0;
		int stalls = 0;
		double[] previousVelocity = null;
		for (int i = 1; i < frames; i++) {
			double[] a = TRACE.get(i - 1);
			double[] b = TRACE.get(i);
			double dt = b[0] - a[0];
			if (dt <= 1.0e-6) {
				continue;
			}
			maxGap = Math.max(maxGap, dt);
			if (dt > STALL_TICKS) {
				stalls++;
				previousVelocity = null;
				continue;
			}
			double[] v = {(b[1] - a[1]) / dt, (b[2] - a[2]) / dt, (b[3] - a[3]) / dt};
			double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
			speedSum += speed;
			intervals++;
			maxStep = Math.max(maxStep, speed * dt);
			double dot = Math.abs(a[4] * b[4] + a[5] * b[5] + a[6] * b[6] + a[7] * b[7]);
			maxTurn = Math.max(maxTurn, Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot))));
			if (previousVelocity != null) {
				double dx = v[0] - previousVelocity[0];
				double dy = v[1] - previousVelocity[1];
				double dz = v[2] - previousVelocity[2];
				maxDeltaV = Math.max(maxDeltaV, Math.sqrt(dx * dx + dy * dy + dz * dz));
				double previousSpeed = Math.sqrt(previousVelocity[0] * previousVelocity[0] + previousVelocity[1] * previousVelocity[1] + previousVelocity[2] * previousVelocity[2]);
				if (speed > 0.01 && previousSpeed > 0.01 && v[0] * previousVelocity[0] + v[1] * previousVelocity[1] + v[2] * previousVelocity[2] < 0) {
					reversals++;
				}
			}
			previousVelocity = v;
		}
		double duration = TRACE.getLast()[0] - TRACE.getFirst()[0];
		return String.format(Locale.ROOT, "frames=%d ticks=%.2f meanSpeed=%.4f maxDeltaV=%.4f maxStep=%.4f reversals=%d maxTurnDeg=%.3f stalls=%d maxGapTicks=%.2f",
			frames, duration, intervals == 0 ? 0.0 : speedSum / intervals, maxDeltaV, maxStep, reversals, maxTurn, stalls, maxGap);
	}

	/** A gap between rendered frames longer than this many ticks is a client stall. */
	private static final double STALL_TICKS = 3.0;

	private static long viewTraceId = -1;
	private static final List<ViewSample> VIEW_TRACE = new ArrayList<>();

	private record ViewSample(double time, float partialTicks, float cameraYaw, VesselPose pose, double tickTurn) {
	}

	/**
	 * What a view trace found, angles in degrees: the frames recorded and how many of them were drawn in the first
	 * half of a tick, how far the vessel turned about the vertical as it was drawn, its largest turn in one tick, and
	 * {@code slip}: how far, at the most, the camera's yaw and the drawn vessel turned apart between any two frames.
	 * For a view that keeps its place on the vessel in every frame the slip is 0; for a view that is turned once a
	 * tick it is a tick's turn of the vessel.
	 */
	public record ViewTrace(int frames, int earlyFrames, double turned, double largestTickTurn, double slip) {
	}

	/** Starts recording, in every frame a vessel is drawn in, the camera's yaw and the pose the vessel is drawn with. */
	public static String viewTraceStart(long id) {
		VIEW_TRACE.clear();
		viewTraceId = id;
		return "tracing the view of " + id;
	}

	/** Called by the renderer with the pose it draws; records one sample per distinct frame time. */
	public static void viewFrame(ClientVessel vessel, VesselPose pose, float partialTicks) {
		if (vessel.id != viewTraceId || VIEW_TRACE.size() >= TRACE_LIMIT) {
			return;
		}
		double time = ClientVessels.clientTicks() + partialTicks;
		if (!VIEW_TRACE.isEmpty() && VIEW_TRACE.getLast().time() == time) {
			return;
		}
		VesselPose from = vessel.previousTickPose();
		VesselPose to = vessel.tickPose();
		double tickTurn = from == null || to == null ? 0.0 : to.yawTurnSinceDegrees(from);
		VIEW_TRACE.add(new ViewSample(time, partialTicks, Minecraft.getInstance().gameRenderer.mainCamera().yRot(), pose, tickTurn));
	}

	/**
	 * Stops the view trace. A view that keeps its place on a vessel (the pilot's, or that of someone standing on the
	 * deck) turns as far as the vessel does, the other way round in Minecraft's yaw: from frame to frame the two
	 * changes cancel. What is left over is summed, and the slip is the span of that sum.
	 */
	public static ViewTrace viewTraceStop() {
		viewTraceId = -1;
		int early = 0;
		double turned = 0.0;
		double largest = 0.0;
		double apart = 0.0;
		double least = 0.0;
		double most = 0.0;
		for (int i = 0; i < VIEW_TRACE.size(); i++) {
			ViewSample sample = VIEW_TRACE.get(i);
			if (sample.partialTicks() < 0.5F) {
				early++;
			}
			largest = Math.max(largest, Math.abs(sample.tickTurn()));
			if (i > 0) {
				ViewSample before = VIEW_TRACE.get(i - 1);
				double turn = sample.pose().yawTurnSinceDegrees(before.pose());
				turned += turn;
				apart += net.minecraft.util.Mth.wrapDegrees((double)sample.cameraYaw() - before.cameraYaw() + turn);
				least = Math.min(least, apart);
				most = Math.max(most, apart);
			}
		}
		ViewTrace result = new ViewTrace(VIEW_TRACE.size(), early, turned, largest, most - least);
		VIEW_TRACE.clear();
		return result;
	}
}
