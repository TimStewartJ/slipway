package dev.timstewart.slipway.clientgametest;

import dev.timstewart.slipway.client.ClientVessel;
import dev.timstewart.slipway.client.ClientVessels;
import dev.timstewart.slipway.client.SlipwayDebug;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselCollisions;
import dev.timstewart.slipway.vessel.VesselEntity;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Piloting a vessel the way a player does (real key and mouse input), and sampling vessels tick by tick. */
final class Flight {
	private Flight() {
	}

	/** What the crosshair is on: a vessel block (vessel id >= 0, vessel-local position) or a world block (id -1). */
	record Hit(long vessel, BlockPos pos, Direction face) {
	}

	static Hit hit(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			if (!(mc.hitResult instanceof BlockHitResult block) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
				return null;
			}
			ClientVessel vessel = ClientVessels.atPlotPos(block.getBlockPos());
			if (vessel != null) {
				return new Hit(vessel.id, block.getBlockPos().subtract(vessel.anchor), block.getDirection());
			}
			return new Hit(-1, block.getBlockPos(), block.getDirection());
		});
	}

	/** Turns the view to a vessel-local point and waits until the crosshair is on the expected vessel block. */
	static Hit aimAtLocal(ClientGameTestContext ctx, long id, double x, double y, double z, BlockPos expected) {
		Hit hit = null;
		for (int attempt = 0; attempt < 10; attempt++) {
			ctx.runOnClient(mc -> SlipwayDebug.lookAtLocal(id, x, y, z));
			ctx.waitTick();
			hit = hit(ctx);
			if (hit != null && hit.vessel() == id && hit.pos().equals(expected)) {
				return hit;
			}
		}
		throw new AssertionError(String.format("aiming at vessel %d local (%.2f, %.2f, %.2f): expected the crosshair on local %s, got %s", id, x, y, z,
			expected.toShortString(), hit));
	}

	static long ridingVessel(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player != null && mc.player.getVehicle() instanceof VesselEntity v ? v.vesselId() : -1L);
	}

	/** Right-clicks the helm (vessel-local 0,0,0) and waits until the player rides it on the server and the client. */
	static void takeHelm(ClientGameTestContext ctx, TestServerContext server, long id) {
		ServerUses.install();
		aimAtLocal(ctx, id, 0.5, 0.5, 0.5, BlockPos.ZERO);
		ctx.getInput().pressKey(o -> o.keyUse);
		try {
			server.waitFor(s -> {
				ActiveVessel v = Game.manager(s).active(id);
				ServerPlayer p = Game.player(s);
				return v != null && v.entity != null && p.getVehicle() == v.entity;
			}, 40);
			ctx.waitFor(mc -> ridingVessel(mc) == id, 40);
		} catch (AssertionError timeout) {
			String serverSide = server.computeOnServer(s -> {
				ActiveVessel v = Game.manager(s).active(id);
				ServerPlayer p = Game.player(s);
				return String.format("server: player %s vehicle %s, vessel entity %s, player at %s", p.getName().getString(), p.getVehicle(),
					v == null ? "no active vessel" : v.entity, p.position());
			});
			throw new AssertionError("taking vessel " + id + "'s helm failed; " + serverSide + "; client riding " + ridingVessel(ctx) + ", crosshair " + hit(ctx)
				+ "; block uses the server saw: " + ServerUses.last(), timeout);
		}
	}

	static long ridingVessel(Minecraft mc) {
		return mc.player != null && mc.player.getVehicle() instanceof VesselEntity v ? v.vesselId() : -1L;
	}

	/** Deck state of the local player: carrier vessel (-1 none), on ground, vessel-local position (null off deck). */
	record Rider(long carrier, boolean onGround, Vec3 local, Vec3 world) {
	}

	static Rider rider(ClientGameTestContext ctx, long id) {
		return ctx.computeOnClient(mc -> {
			VesselCollisions.Rider r = (VesselCollisions.Rider)mc.player;
			ClientVessel v = ClientVessels.get(id);
			Vec3 local = v != null && v.ready() ? v.tickPose().worldToLocal(mc.player.position()) : null;
			return new Rider(r.slipway$carrier(), mc.player.onGround(), local, mc.player.position());
		});
	}

	/**
	 * Sneaks off the helm, waits to stand on the deck, then sneak-uses the helm, which disassembles the vessel; waits
	 * until the vessel is gone on the server and the client.
	 */
	static void disassembleAsPlayer(ClientGameTestContext ctx, TestServerContext server, long id) {
		ctx.getInput().holdKey(o -> o.keyShift);
		try {
			ctx.waitFor(mc -> ridingVessel(mc) == -1, 40);
			ctx.waitFor(mc -> mc.player.onGround() && ((VesselCollisions.Rider)mc.player).slipway$carrier() == id, 100);
			aimAtLocal(ctx, id, 0.5, 0.5, 0.5, BlockPos.ZERO);
			ctx.getInput().pressKey(o -> o.keyUse);
			server.waitFor(s -> Game.manager(s).registry().get(id) == null, 40);
			ctx.waitFor(mc -> ClientVessels.get(id) == null, 40);
		} finally {
			ctx.getInput().releaseKey(o -> o.keyShift);
		}
	}

	/** One server tick of a vessel. */
	record Sample(long gameTime, VesselPose pose, Vec3 velocity, Vec3 angularVelocity, boolean piloted) {
		double tilt() {
			return this.pose.tiltDegrees();
		}

		/** The vessel's up axis in the world. */
		Vec3 up() {
			org.joml.Vector3d u = this.pose.rotate(0, 1, 0, new org.joml.Vector3d());
			return new Vec3(u.x, u.y, u.z);
		}
	}

	static Sample sample(TestServerContext server, long id) {
		return server.computeOnServer(s -> {
			ActiveVessel v = Game.active(s, id);
			ServerPlayer p = s.getPlayerList().getPlayers().isEmpty() ? null : Game.player(s);
			return new Sample(s.overworld().getGameTime(), v.record.pose, v.record.linearVelocity, v.record.angularVelocity,
				p != null && v.entity != null && p.getVehicle() == v.entity);
		});
	}

	/** Waits {@code ticks} ticks, sampling the vessel on the server after every tick. */
	static List<Sample> run(ClientGameTestContext ctx, TestServerContext server, long id, int ticks) {
		List<Sample> samples = new ArrayList<>(ticks);
		for (int i = 0; i < ticks; i++) {
			ctx.waitTick();
			samples.add(sample(server, id));
		}
		return samples;
	}

	/** Holds keys for {@code ticks} ticks (sampling every tick), then releases them. */
	static List<Sample> hold(ClientGameTestContext ctx, TestServerContext server, long id, int ticks, String... keys) {
		List<net.minecraft.client.KeyMapping> mappings = new ArrayList<>();
		for (String key : keys) {
			mappings.add(Game.key(ctx, key));
		}
		mappings.forEach(k -> ctx.getInput().holdKey(k));
		try {
			return run(ctx, server, id, ticks);
		} finally {
			mappings.forEach(k -> ctx.getInput().releaseKey(k));
		}
	}

	/**
	 * Checks every sample is finite and moves continuously: each tick's displacement and rotation must be explained by
	 * the vessel's own reported velocity (a snap or teleport is not), and speed and spin stay below runaway limits.
	 */
	static void checkContinuous(String phase, List<Sample> samples) {
		for (int i = 0; i < samples.size(); i++) {
			Sample s = samples.get(i);
			VesselPose p = s.pose();
			Check.finite(phase + " pose at tick " + s.gameTime(), p.x(), p.y(), p.z(), p.qx(), p.qy(), p.qz(), p.qw());
			Check.finite(phase + " velocity at tick " + s.gameTime(), s.velocity().x, s.velocity().y, s.velocity().z);
			Check.finite(phase + " angular velocity at tick " + s.gameTime(), s.angularVelocity().x, s.angularVelocity().y, s.angularVelocity().z);
			double norm = Math.sqrt(p.qx() * p.qx() + p.qy() * p.qy() + p.qz() * p.qz() + p.qw() * p.qw());
			Check.near(phase + " quaternion length at tick " + s.gameTime(), norm, 1.0, 1.0e-3);
			Check.that(s.velocity().length() <= MAX_SPEED, "%s: runaway speed %.1f blocks/s at tick %d", phase, s.velocity().length(), s.gameTime());
			Check.that(Math.toDegrees(s.angularVelocity().length()) <= MAX_SPIN_DEGREES, "%s: runaway spin %.0f degrees/s at tick %d", phase,
				Math.toDegrees(s.angularVelocity().length()), s.gameTime());
			if (i > 0) {
				Sample prev = samples.get(i - 1);
				VesselPose q = prev.pose();
				double dt = Math.max(1, s.gameTime() - prev.gameTime()) / 20.0;
				double step = p.position().distanceTo(q.position());
				double allowedStep = Math.max(prev.velocity().length(), s.velocity().length()) * dt * 1.5 + 0.05;
				double dot = Math.abs(p.qx() * q.qx() + p.qy() * q.qy() + p.qz() * q.qz() + p.qw() * q.qw());
				double turn = Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot)));
				double allowedTurn = Math.toDegrees(Math.max(prev.angularVelocity().length(), s.angularVelocity().length())) * dt * 1.5 + 0.5;
				Check.that(step <= allowedStep, "%s: vessel moved %.3f blocks in one tick at tick %d, more than its speed allows (%.3f): a jump", phase, step,
					s.gameTime(), allowedStep);
				Check.that(turn <= allowedTurn, "%s: vessel turned %.2f degrees in one tick at tick %d, more than its spin allows (%.2f): a snap", phase, turn,
					s.gameTime(), allowedTurn);
			}
		}
	}

	/** Runaway limits: far above anything the helm can command (blocks per second, degrees per second). */
	static final double MAX_SPEED = 80.0;
	static final double MAX_SPIN_DEGREES = 720.0;

	static double maxTilt(List<Sample> samples) {
		return samples.stream().mapToDouble(Sample::tilt).max().orElse(Double.NaN);
	}

	static double yawChange(Sample from, Sample to) {
		return to.pose().yawTurnSinceDegrees(from.pose());
	}

	static double horizontalDistance(Sample from, Sample to) {
		double dx = to.pose().x() - from.pose().x();
		double dz = to.pose().z() - from.pose().z();
		return Math.sqrt(dx * dx + dz * dz);
	}
}
