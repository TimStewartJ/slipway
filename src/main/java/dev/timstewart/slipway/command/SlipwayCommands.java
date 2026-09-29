package dev.timstewart.slipway.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.net.ServerPackets;
import dev.timstewart.slipway.physics.PhysicsWorld;
import dev.timstewart.slipway.physics.jolt.JoltEngine;
import dev.timstewart.slipway.physics.jolt.JoltRuntime;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselAssembly;
import dev.timstewart.slipway.vessel.VesselManager;
import dev.timstewart.slipway.vessel.VesselPhysicsBridge;
import dev.timstewart.slipway.vessel.VesselRecord;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /slipway} operator commands: inspection, and the controls the test harness uses to drive vessels without
 * a pilot. All require the game-master permission level.
 */
public final class SlipwayCommands {
	private SlipwayCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("slipway").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
		root.then(Commands.literal("list").executes(SlipwayCommands::list));
		root.then(Commands.literal("info").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(SlipwayCommands::info)));
		root.then(Commands.literal("stats").executes(SlipwayCommands::stats));
		root.then(Commands.literal("assemble").then(Commands.argument("helm", BlockPosArgument.blockPos()).executes(ctx -> {
			BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "helm");
			VesselAssembly.Outcome outcome = manager(ctx).assemble(pos, null);
			return reply(ctx, outcome.success(), outcome.message().getString() + (outcome.record() != null ? " id=" + outcome.record().id : ""));
		})));
		root.then(Commands.literal("disassemble").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx -> {
			VesselAssembly.Outcome outcome = manager(ctx).disassemble(LongArgumentType.getLong(ctx, "id"), null);
			return reply(ctx, outcome.success(), outcome.message().getString());
		})));
		root.then(Commands.literal("remove").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx -> {
			long id = LongArgumentType.getLong(ctx, "id");
			int count = manager(ctx).remove(id);
			return reply(ctx, count >= 0, count >= 0 ? "Removed vessel " + id + " and its " + count + " blocks" : "no vessel " + id);
		})));

		var poseRoll = Commands.argument("roll", DoubleArgumentType.doubleArg(-360, 360)).executes(SlipwayCommands::pose);
		var posePitch = Commands.argument("pitch", DoubleArgumentType.doubleArg(-360, 360)).then(poseRoll);
		var poseYaw = Commands.argument("yaw", DoubleArgumentType.doubleArg(-360, 360)).then(posePitch);
		var poseZ = Commands.argument("z", DoubleArgumentType.doubleArg(-3.0E7, 3.0E7)).then(poseYaw);
		var poseY = Commands.argument("y", DoubleArgumentType.doubleArg(-4096, 4096)).then(poseZ);
		var poseX = Commands.argument("x", DoubleArgumentType.doubleArg(-3.0E7, 3.0E7)).then(poseY);
		root.then(Commands.literal("pose").then(Commands.argument("id", LongArgumentType.longArg(1)).then(poseX)));

		var rotRoll = Commands.argument("roll", DoubleArgumentType.doubleArg(-360, 360)).executes(SlipwayCommands::rotate);
		var rotPitch = Commands.argument("pitch", DoubleArgumentType.doubleArg(-360, 360)).then(rotRoll);
		var rotYaw = Commands.argument("yaw", DoubleArgumentType.doubleArg(-360, 360)).then(rotPitch);
		root.then(Commands.literal("rotate").then(Commands.argument("id", LongArgumentType.longArg(1)).then(rotYaw)));

		var ticks = Commands.argument("ticks", IntegerArgumentType.integer(1, 20 * 600)).executes(SlipwayCommands::control);
		var roll = Commands.argument("roll", FloatArgumentType.floatArg(-1, 1)).then(ticks);
		var yaw = Commands.argument("yaw", FloatArgumentType.floatArg(-1, 1)).then(roll);
		var pitch = Commands.argument("pitch", FloatArgumentType.floatArg(-1, 1)).then(yaw);
		var vertical = Commands.argument("vertical", FloatArgumentType.floatArg(-1, 1)).then(pitch);
		var strafe = Commands.argument("strafe", FloatArgumentType.floatArg(-1, 1)).then(vertical);
		var forward = Commands.argument("forward", FloatArgumentType.floatArg(-1, 1)).then(strafe);
		root.then(Commands.literal("control").then(Commands.argument("id", LongArgumentType.longArg(1)).then(forward)));

		var modeOn = Commands.argument("on", BoolArgumentType.bool()).executes(SlipwayCommands::mode);
		root.then(Commands.literal("mode").then(Commands.argument("id", LongArgumentType.longArg(1)).then(Commands.argument("mode", StringArgumentType.word()).then(modeOn))));

		root.then(Commands.literal("pilot").then(Commands.argument("id", LongArgumentType.longArg(1)).then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
			ActiveVessel vessel = active(ctx);
			ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
			boolean ok = vessel != null && vessel.entity != null && player.startRiding(vessel.entity, true, true);
			return reply(ctx, ok, ok ? player.getName().getString() + " took the helm of vessel " + vessel.record.id : "cannot take the helm");
		}))));
		root.then(Commands.literal("natives").executes(ctx -> {
			JoltRuntime.Info info = JoltRuntime.info();
			return reply(ctx, info != null, info == null ? "Jolt not loaded" : "jolt-jni " + info.joltVersion() + " " + info.platform() + " "
				+ info.buildType() + (info.doublePrecision() ? " double" : " single") + " precision; " + info.libraryFile()
				+ "; live engines=" + JoltEngine.liveEngines() + " bodies=" + JoltEngine.liveBodies());
		}));
		root.then(Commands.literal("packets").executes(ctx -> reply(ctx, true, "rejected serverbound packets: " + ServerPackets.rejectedCount())));
		dispatcher.register(root);
	}

	private static VesselManager manager(CommandContext<CommandSourceStack> ctx) {
		return VesselManager.get(ctx.getSource().getLevel());
	}

	private static ActiveVessel active(CommandContext<CommandSourceStack> ctx) {
		return manager(ctx).active(LongArgumentType.getLong(ctx, "id"));
	}

	private static int reply(CommandContext<CommandSourceStack> ctx, boolean success, String text) {
		if (success) {
			ctx.getSource().sendSuccess(() -> Component.literal(text), false);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal(text));
		return 0;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		VesselManager manager = manager(ctx);
		StringBuilder text = new StringBuilder("Vessels: " + manager.registry().size() + " (" + manager.activeVessels().size() + " active)");
		for (VesselRecord record : manager.registry().all()) {
			ActiveVessel active = manager.active(record.id);
			Vec3 centre = VesselManager.worldCentre(record);
			text.append(String.format(Locale.ROOT, "\n#%d blocks=%d centre=%.1f,%.1f,%.1f %s", record.id, record.blockCount, centre.x, centre.y, centre.z,
				active == null ? "inactive" : active.hasBody ? "flying" : "loading"));
		}
		return reply(ctx, true, text.toString());
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		long id = LongArgumentType.getLong(ctx, "id");
		VesselManager manager = manager(ctx);
		VesselRecord record = manager.registry().get(id);
		if (record == null) {
			return reply(ctx, false, "no vessel " + id);
		}
		ActiveVessel active = manager.active(id);
		VesselPose pose = record.pose;
		double[] attitude = pose.attitudeDegrees();
		Vec3 centre = VesselManager.worldCentre(record);
		String text = String.format(Locale.ROOT,
			"vessel %d: blocks=%d plot=%d anchor=%s bounds=%s..%s pos=%.3f,%.3f,%.3f centre=%.2f,%.2f,%.2f pitch=%.2f yaw=%.2f roll=%.2f tilt=%.2f "
				+ "speed=%.3f spin=%.3f hover=%s level=%s active=%s body=%s mass=%.1f q=%.6f,%.6f,%.6f,%.6f vel=%.3f,%.3f,%.3f plotAnchor=%d,%d,%d input=%s",
			id, record.blockCount, record.plot, record.anchor.toShortString(), record.localMin.toShortString(), record.localMax.toShortString(),
			pose.x(), pose.y(), pose.z(), centre.x, centre.y, centre.z, attitude[0], attitude[1], attitude[2], pose.tiltDegrees(),
			record.linearVelocity.length(), record.angularVelocity.length(), record.hover, record.level, active != null,
			active != null && active.hasBody, active == null || active.mass == null ? 0.0 : active.mass.mass(),
			pose.qx(), pose.qy(), pose.qz(), pose.qw(), record.linearVelocity.x, record.linearVelocity.y, record.linearVelocity.z,
			record.anchor.getX(), record.anchor.getY(), record.anchor.getZ(),
			active == null ? "none" : String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f", active.input.forward, active.input.strafe,
				active.input.vertical, active.input.pitch, active.input.yaw, active.input.roll));
		return reply(ctx, true, text);
	}

	private static int stats(CommandContext<CommandSourceStack> ctx) {
		VesselManager manager = manager(ctx);
		VesselPhysicsBridge bridge = manager.physics();
		PhysicsWorld world = bridge.worldIfStarted();
		double mspt = ctx.getSource().getServer().getAverageTickTimeNanos() / 1.0e6;
		String text = String.format(Locale.ROOT, "mspt=%.2f exchange=%.3fms step=%.3fms vesselBodies=%d terrainBodies=%d active=%d failed=%s liveEngines=%d liveBodies=%d",
			mspt, bridge.lastExchangeNanos() / 1.0e6, world == null ? 0.0 : world.lastStepNanos() / 1.0e6,
			world == null ? 0 : world.vesselBodies(), world == null ? 0 : world.staticBodies(), manager.activeVessels().size(),
			world != null && world.failed(), JoltEngine.liveEngines(), JoltEngine.liveBodies());
		return reply(ctx, true, text);
	}

	private static int pose(CommandContext<CommandSourceStack> ctx) {
		ActiveVessel vessel = active(ctx);
		if (vessel == null) {
			return reply(ctx, false, "vessel is not active");
		}
		VesselPose pose = VesselPose.fromYawPitchRoll(DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"),
			DoubleArgumentType.getDouble(ctx, "z"), DoubleArgumentType.getDouble(ctx, "yaw"), DoubleArgumentType.getDouble(ctx, "pitch"),
			DoubleArgumentType.getDouble(ctx, "roll"));
		manager(ctx).teleport(vessel, pose);
		return reply(ctx, true, "vessel " + vessel.record.id + " moved");
	}

	private static int rotate(CommandContext<CommandSourceStack> ctx) {
		ActiveVessel vessel = active(ctx);
		if (vessel == null) {
			return reply(ctx, false, "vessel is not active");
		}
		VesselPose current = vessel.record.pose;
		// Rotate about the vessel's bounds centre so it turns in place.
		Vec3 centreLocal = vessel.record.localCenter();
		Vec3 centreWorld = VesselManager.worldCentre(vessel.record);
		VesselPose rotated = VesselPose.fromYawPitchRoll(0, 0, 0, DoubleArgumentType.getDouble(ctx, "yaw"), DoubleArgumentType.getDouble(ctx, "pitch"),
			DoubleArgumentType.getDouble(ctx, "roll"));
		org.joml.Vector3d offset = rotated.rotate(centreLocal.x, centreLocal.y, centreLocal.z, new org.joml.Vector3d());
		VesselPose pose = rotated.withPosition(centreWorld.x - offset.x, centreWorld.y - offset.y, centreWorld.z - offset.z);
		manager(ctx).teleport(vessel, pose);
		return reply(ctx, true, String.format(Locale.ROOT, "vessel %d rotated (was yaw %.1f)", vessel.record.id, current.attitudeDegrees()[1]));
	}

	private static int control(CommandContext<CommandSourceStack> ctx) {
		ActiveVessel vessel = active(ctx);
		if (vessel == null) {
			return reply(ctx, false, "vessel is not active");
		}
		long now = ctx.getSource().getLevel().getGameTime();
		vessel.input.set(FloatArgumentType.getFloat(ctx, "forward"), FloatArgumentType.getFloat(ctx, "strafe"), FloatArgumentType.getFloat(ctx, "vertical"),
			FloatArgumentType.getFloat(ctx, "pitch"), FloatArgumentType.getFloat(ctx, "yaw"), FloatArgumentType.getFloat(ctx, "roll"), now);
		vessel.scriptedInputUntil = now + IntegerArgumentType.getInteger(ctx, "ticks");
		return reply(ctx, true, "vessel " + vessel.record.id + " input set for " + IntegerArgumentType.getInteger(ctx, "ticks") + " ticks");
	}

	private static int mode(CommandContext<CommandSourceStack> ctx) {
		ActiveVessel vessel = active(ctx);
		if (vessel == null) {
			return reply(ctx, false, "vessel is not active");
		}
		String mode = StringArgumentType.getString(ctx, "mode");
		boolean on = BoolArgumentType.getBool(ctx, "on");
		switch (mode) {
			case "hover" -> vessel.record.hover = on;
			case "level" -> vessel.record.level = on;
			default -> {
				return reply(ctx, false, "mode is hover or level");
			}
		}
		return reply(ctx, true, "vessel " + vessel.record.id + " " + mode + "=" + on);
	}
}
