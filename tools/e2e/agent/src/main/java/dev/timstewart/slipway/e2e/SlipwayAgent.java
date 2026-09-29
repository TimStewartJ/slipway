package dev.timstewart.slipway.e2e;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-only client agent for Slipway's end-to-end harness. Lines appended to
 * {@code <gameDir>/slipway-agent/commands.txt} as {@code <id> <verb> [arguments]} run on the client thread; each
 * answer is appended to {@code results.txt} as {@code <id> ok|error <detail>}. Only lines written after the game
 * started run. Everything works with the game window in the background.
 *
 * <p>Verbs: session, screen, state, screenshot FILE, hud hide|show, chat TEXT, connect HOST:PORT, disconnect,
 * key NAME, hold NAME TICKS, look YAW PITCH, lookat X Y Z, select SLOT, use, useitem, destroy TICKS, close,
 * iris on|off|status, fly on|off, perspective first|back|front, fps, perf start|stop, forgeuse X Y Z FACE,
 * forgebreak X Y Z (raw packets for the validation scenario), slipway METHOD [ARGS...]
 * (calls Slipway's client debug API).
 */
public final class SlipwayAgent implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("slipway-e2e-agent");
	private Path commands;
	private Path results;
	private long offset;
	private final Map<InputConstants.Key, Integer> held = new HashMap<>();
	private int destroyTicks;
	private BlockPos destroyPos;
	private Direction destroyFace;
	private String destroyId;
	private boolean perfRunning;
	private final List<Integer> perfFps = new ArrayList<>();
	private long perfStart;

	@Override
	public void onInitializeClient() {
		Path directory = FabricLoader.getInstance().getGameDir().resolve("slipway-agent");
		this.commands = directory.resolve("commands.txt");
		this.results = directory.resolve("results.txt");
		try {
			Files.createDirectories(directory);
			if (!Files.exists(this.commands)) {
				Files.createFile(this.commands);
			}
			this.offset = Files.size(this.commands);
		} catch (IOException error) {
			LOGGER.error("The Slipway e2e agent cannot use {}", directory, error);
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		LOGGER.info("Slipway e2e agent is reading {}", this.commands);
	}

	private void tick(Minecraft mc) {
		this.held.entrySet().removeIf(entry -> {
			if (entry.getValue() <= 0) {
				KeyMapping.set(entry.getKey(), false);
				return true;
			}
			KeyMapping.set(entry.getKey(), true);
			entry.setValue(entry.getValue() - 1);
			return false;
		});
		this.tickDestroy(mc);
		if (this.perfRunning) {
			this.perfFps.add(mc.getFps());
		}
		String appended;
		try {
			long length = Files.size(this.commands);
			if (length == this.offset) {
				return;
			}
			if (length < this.offset) {
				this.offset = 0L;
			}
			try (RandomAccessFile file = new RandomAccessFile(this.commands.toFile(), "r")) {
				byte[] bytes = new byte[(int)Math.min(Integer.MAX_VALUE, length - this.offset)];
				file.seek(this.offset);
				file.readFully(bytes);
				appended = new String(bytes, StandardCharsets.UTF_8);
			}
		} catch (IOException error) {
			return;
		}
		String complete = appended.substring(0, appended.lastIndexOf('\n') + 1);
		this.offset += complete.getBytes(StandardCharsets.UTF_8).length;
		for (String line : complete.split("\r?\n")) {
			if (!line.isBlank()) {
				this.run(mc, line.strip());
			}
		}
	}

	private void tickDestroy(Minecraft mc) {
		if (this.destroyTicks <= 0 || mc.gameMode == null || mc.player == null) {
			return;
		}
		this.destroyTicks--;
		boolean gone = mc.level == null || mc.level.getBlockState(this.destroyPos).isAir();
		if (!gone) {
			mc.gameMode.continueDestroyBlock(this.destroyPos, this.destroyFace);
			gone = mc.level.getBlockState(this.destroyPos).isAir();
		}
		if (gone || this.destroyTicks == 0) {
			mc.gameMode.stopDestroyBlock();
			this.report(this.destroyId, "ok", (gone ? "destroyed " : "still there ") + this.destroyPos.toShortString());
			this.destroyTicks = 0;
		}
	}

	private void run(Minecraft mc, String line) {
		String[] parts = line.split(" ", 3);
		String id = parts[0];
		String verb = parts.length > 1 ? parts[1] : "";
		String argument = parts.length > 2 ? parts[2] : "";
		String[] words = argument.isEmpty() ? new String[0] : argument.split(" ");
		try {
			switch (verb) {
				case "session" -> this.report(id, "ok", session(mc));
				case "screen" -> this.report(id, "ok", mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName() + " | " + mc.gui.screen().getTitle().getString());
				case "state" -> this.report(id, "ok", state(mc));
				case "screenshot" -> Screenshot.grab(mc.gameDirectory, argument, mc.gameRenderer.mainRenderTarget(), 1,
					message -> this.report(id, "ok", message.getString()));
				case "hud" -> {
					if (mc.gui.hud.isHidden() != argument.equals("hide")) {
						mc.gui.hud.toggle();
					}
					this.report(id, "ok", mc.gui.hud.isHidden() ? "hidden" : "shown");
				}
				case "chat" -> {
					if (mc.player == null) {
						throw new IllegalStateException("no player");
					}
					if (argument.startsWith("/")) {
						mc.player.connection.sendCommand(argument.substring(1));
					} else {
						mc.player.connection.sendChat(argument);
					}
					this.report(id, "ok", "sent");
				}
				case "connect" -> {
					if (mc.level != null) {
						throw new IllegalStateException("already in a world");
					}
					ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(argument),
						new ServerData("Slipway e2e", argument, ServerData.Type.OTHER), false, null);
					this.report(id, "ok", "connecting to " + argument);
				}
				case "disconnect" -> {
					if (mc.level != null) {
						mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
					}
					mc.gui.setScreen(new TitleScreen());
					this.report(id, "ok", session(mc));
				}
				case "key" -> {
					KeyMapping.click(InputConstants.getKey(argument));
					this.report(id, "ok", "clicked " + argument);
				}
				case "hold" -> {
					InputConstants.Key key = InputConstants.getKey(words[0]);
					int ticks = words.length > 1 ? Integer.parseInt(words[1]) : 20;
					this.held.put(key, ticks);
					this.report(id, "ok", "holding " + words[0] + " for " + ticks + " ticks");
				}
				case "look" -> {
					float yaw = Float.parseFloat(words[0]);
					float pitch = Float.parseFloat(words[1]);
					look(mc, yaw, pitch);
					this.report(id, "ok", "looking " + yaw + " " + pitch);
				}
				case "lookat" -> {
					Vec3 eye = mc.player.getEyePosition();
					double dx = Double.parseDouble(words[0]) - eye.x;
					double dy = Double.parseDouble(words[1]) - eye.y;
					double dz = Double.parseDouble(words[2]) - eye.z;
					float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
					float pitch = (float)-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
					look(mc, yaw, pitch);
					this.report(id, "ok", String.format(Locale.ROOT, "looking %.2f %.2f", yaw, pitch));
				}
				case "select" -> {
					mc.player.getInventory().setSelectedSlot(Integer.parseInt(argument));
					this.report(id, "ok", "slot " + argument);
				}
				case "use" -> {
					if (mc.hitResult instanceof EntityHitResult onEntity) {
						InteractionResult result = mc.gameMode.interact(mc.player, onEntity.getEntity(), onEntity, InteractionHand.MAIN_HAND);
						this.report(id, "ok", result + " on " + onEntity.getEntity().getType().toShortString());
					} else if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
						InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
						this.report(id, "ok", result + " at " + hit.getBlockPos().toShortString() + " face " + hit.getDirection());
					} else {
						throw new IllegalStateException("the crosshair is on neither a block nor an entity");
					}
				}
				case "useitem" -> this.report(id, "ok", String.valueOf(mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND)));
				case "destroy" -> {
					if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
						throw new IllegalStateException("the crosshair is not on a block");
					}
					this.destroyPos = hit.getBlockPos();
					this.destroyFace = hit.getDirection();
					this.destroyId = id;
					this.destroyTicks = words.length > 0 ? Integer.parseInt(words[0]) : 100;
					mc.gameMode.startDestroyBlock(this.destroyPos, this.destroyFace);
							// The answer comes when the block is gone or the time is up.
				}
				case "close" -> {
					if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
						mc.player.closeContainer();
					}
					mc.gui.setScreen(null);
					this.report(id, "ok", "closed");
				}
				case "iris" -> this.report(id, "ok", iris(argument));
				case "forgeuse" -> {
					BlockPos pos = new BlockPos(Integer.parseInt(words[0]), Integer.parseInt(words[1]), Integer.parseInt(words[2]));
					Direction face = Direction.byName(words[3]);
					BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
					mc.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
					this.report(id, "ok", "sent use on " + pos.toShortString() + " " + face);
				}
				case "forgebreak" -> {
					BlockPos pos = new BlockPos(Integer.parseInt(words[0]), Integer.parseInt(words[1]), Integer.parseInt(words[2]));
					mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP, 0));
					this.report(id, "ok", "sent break of " + pos.toShortString());
				}
				case "fly" -> {
					mc.player.getAbilities().flying = argument.equals("on") && mc.player.getAbilities().mayfly;
					mc.player.onUpdateAbilities();
					this.report(id, "ok", "flying=" + mc.player.getAbilities().flying);
				}
				case "perspective" -> {
					mc.options.setCameraType(switch (argument) {
						case "back" -> CameraType.THIRD_PERSON_BACK;
						case "front" -> CameraType.THIRD_PERSON_FRONT;
						default -> CameraType.FIRST_PERSON;
					});
					this.report(id, "ok", mc.options.getCameraType().name());
				}
				case "fps" -> this.report(id, "ok", String.valueOf(mc.getFps()));
				case "fpscap" -> {
					mc.options.framerateLimit().set(Integer.parseInt(argument));
					// The harness sends no real input, so the "AFK" limit (30 fps after a minute) would cap measurements.
					mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
					this.report(id, "ok", "framerate limit " + mc.options.framerateLimit().get() + ", inactivity limit " + mc.options.inactivityFpsLimit().get());
				}
				case "openworld" -> {
					if (mc.level != null) {
						throw new IllegalStateException("already in a world");
					}
					mc.createWorldOpenFlows().openWorld(argument, () -> mc.gui.setScreen(new TitleScreen()));
					this.report(id, "ok", "opening " + argument);
				}
				case "mem" -> {
					System.gc();
					java.lang.management.MemoryUsage heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
					java.lang.management.MemoryUsage nonHeap = java.lang.management.ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
					long direct = 0;
					for (java.lang.management.BufferPoolMXBean pool : java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
						direct += pool.getMemoryUsed();
					}
					this.report(id, "ok", String.format(Locale.ROOT, "heapUsedMb=%.1f heapCommittedMb=%.1f nonHeapMb=%.1f directMb=%.1f",
						heap.getUsed() / 1048576.0, heap.getCommitted() / 1048576.0, nonHeap.getUsed() / 1048576.0, direct / 1048576.0));
				}
				case "perf" -> {
					if (argument.equals("start")) {
						this.perfFps.clear();
						this.perfRunning = true;
						this.perfStart = System.nanoTime();
						this.report(id, "ok", "recording");
					} else {
						this.perfRunning = false;
						double avg = this.perfFps.stream().mapToInt(Integer::intValue).average().orElse(0);
						int min = this.perfFps.stream().mapToInt(Integer::intValue).min().orElse(0);
						List<Integer> sorted = new ArrayList<>(this.perfFps);
						sorted.sort(null);
						int p5 = sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int)(sorted.size() * 0.05) - 1));
						this.report(id, "ok", String.format(Locale.ROOT, "samples=%d seconds=%.1f avgFps=%.1f minFps=%d p5Fps=%d", this.perfFps.size(),
							(System.nanoTime() - this.perfStart) / 1e9, avg, min, p5));
					}
				}
				case "slipway" -> this.report(id, "ok", slipway(words));
				default -> this.report(id, "error", "unknown verb " + verb);
			}
		} catch (IllegalStateException | IllegalArgumentException refusal) {
			// A command that does not apply right now (nothing under the crosshair, a bad argument): the harness gets
			// the reason; it is not a fault of the game under test.
			LOGGER.info("agent command refused: {}: {}", line, refusal.getMessage());
			this.report(id, "error", "refused: " + refusal.getMessage());
		} catch (Throwable error) {
			LOGGER.error("agent command failed: {}", line, error);
			this.report(id, "error", String.valueOf(error));
		}
	}

	private static void look(Minecraft mc, float yaw, float pitch) {
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO = yaw;
		mc.player.xRotO = pitch;
		mc.player.setYHeadRot(yaw);
		mc.player.yBodyRot = yaw;
	}

	private static String session(Minecraft mc) {
		if (mc.level == null) {
			return "title screen=" + (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())
				+ " overlay=" + (mc.gui.overlay() == null ? "none" : mc.gui.overlay().getClass().getSimpleName());
		}
		ServerData server = mc.getCurrentServer();
		return "world " + (server == null ? "singleplayer" : server.ip) + " player=" + (mc.player == null ? "none" : mc.player.getName().getString());
	}

	private static String state(Minecraft mc) {
		if (mc.player == null) {
			return "no player";
		}
		var p = mc.player;
		return String.format(Locale.ROOT, "pos=%.3f,%.3f,%.3f yaw=%.2f pitch=%.2f onGround=%s vehicle=%s held=%s screen=%s tick=%d gamemode=%s",
			p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround(), p.getVehicle() == null ? "none" : p.getVehicle().getType().toShortString(),
			p.getMainHandItem().getItem(), mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName(), p.tickCount,
			mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode());
	}

	private static String iris(String argument) throws ReflectiveOperationException {
		Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
		Object instance = api.getMethod("getInstance").invoke(null);
		if (argument.equals("on") || argument.equals("off")) {
			Object config = api.getMethod("getConfig").invoke(instance);
			config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, argument.equals("on"));
		}
		return "shaderPackInUse=" + api.getMethod("isShaderPackInUse").invoke(instance);
	}

	private static String slipway(String[] words) throws ReflectiveOperationException {
		Class<?> debug = Class.forName("dev.timstewart.slipway.client.SlipwayDebug");
		for (Method method : debug.getMethods()) {
			if (!method.getName().equals(words[0]) || method.getParameterCount() != words.length - 1) {
				continue;
			}
			Object[] args = new Object[method.getParameterCount()];
			Class<?>[] types = method.getParameterTypes();
			for (int i = 0; i < args.length; i++) {
				String w = words[i + 1];
				args[i] = types[i] == float.class ? (Object)Float.parseFloat(w) : types[i] == int.class ? (Object)Integer.parseInt(w)
					: types[i] == long.class ? (Object)Long.parseLong(w) : types[i] == double.class ? (Object)Double.parseDouble(w) : w;
			}
			return String.valueOf(method.invoke(null, args));
		}
		throw new NoSuchMethodException("SlipwayDebug." + words[0] + " with " + (words.length - 1) + " arguments");
	}

	private void report(String id, String status, String detail) {
		String line = id + " " + status + " " + detail.replace('\n', ' ') + System.lineSeparator();
		try {
			Files.writeString(this.results, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException error) {
			LOGGER.error("The Slipway e2e agent cannot write {}", this.results, error);
		}
	}
}
