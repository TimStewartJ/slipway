package dev.timstewart.slipway.clientgametest;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.apache.commons.io.FileUtils;

/**
 * A second real client for the multiplayer test: this same game, started again in its own process (same classpath,
 * mods and JVM options) with its own game directory and name, joining the test's server. It runs this test mod as a
 * "watcher" (see {@link Watcher}) and takes commands through files: cmd-N.properties in, reply-N.properties out.
 */
final class WatcherProcess implements AutoCloseable {
	static final String NAME = "SlipwayWatcher";
	private final Process process;
	final Path dir;
	private int next = 1;

	private WatcherProcess(Process process, Path dir) {
		this.process = process;
		this.dir = dir;
	}

	static WatcherProcess start(int serverPort) throws IOException {
		Path dir = SlipwayClientGameTests.reportDir().getParent().resolve("watcher");
		FileUtils.deleteDirectory(dir.resolve("commands").toFile());
		Files.createDirectories(dir.resolve("commands"));
		List<String> args = new ArrayList<>();
		for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (arg.startsWith("-Dslipway.clientGametest.") || arg.startsWith("-Dfabric.client.gametest.testModResourcesPath") || arg.startsWith("-Xmx")
				|| arg.startsWith("-agentlib:jdwp") || arg.startsWith("-XX:NativeMemoryTracking")) {
				continue;
			}
			args.add(arg);
		}
		args.add("-Xmx4G");
		args.add("-Dslipway.clientGametest.role=watcher");
		args.add("-Dslipway.clientGametest.watcherDir=" + dir.resolve("commands"));
		args.add("-Dslipway.clientGametest.watcherServer=localhost:" + serverPort);
		args.add("-Dslipway.clientGametest.reportDir=" + dir.resolve("report"));
		args.add("-cp");
		args.add(System.getProperty("java.class.path"));
		String[] command = System.getProperty("sun.java.command").trim().split("\\s+");
		args.add(command[0]);
		for (int i = 1; i < command.length; i++) {
			if (command[i].equals("--username") && i + 1 < command.length) {
				i++;
				continue;
			}
			args.add(command[i]);
		}
		args.add("--username");
		args.add(NAME);
		// Everything goes through a java @argfile: the classpath is far longer than a Windows command line allows.
		Path argFile = dir.resolve("watcher.args");
		try (Writer out = Files.newBufferedWriter(argFile, StandardCharsets.UTF_8)) {
			for (String arg : args) {
				out.write('"');
				out.write(arg.replace('\\', '/').replace("\"", "\\\""));
				out.write("\"\n");
			}
		}
		String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		ProcessBuilder builder = new ProcessBuilder(java, "@" + argFile.toAbsolutePath()).directory(dir.toFile())
			.redirectErrorStream(true).redirectOutput(dir.resolve("watcher-output.log").toFile());
		SlipwayClientGameTests.LOG.info("Starting the watcher client: {} @{}", java, argFile);
		return new WatcherProcess(builder.start(), dir);
	}

	boolean alive() {
		return this.process.isAlive();
	}

	/** Sends a command and waits (game ticks, so the server keeps running) for the watcher's reply. */
	Properties call(ClientGameTestContext ctx, int timeoutTicks, String verb, String... args) {
		int n = this.next++;
		Properties command = new Properties();
		command.setProperty("verb", verb);
		for (int i = 0; i < args.length; i++) {
			command.setProperty("arg" + i, args[i]);
		}
		Path commands = this.dir.resolve("commands");
		write(commands.resolve("cmd-" + n + ".properties"), command);
		Path reply = commands.resolve("reply-" + n + ".properties");
		for (int t = 0; t < timeoutTicks; t++) {
			if (Files.isRegularFile(reply)) {
				Properties answer = read(reply);
				Check.that(!"error".equals(answer.getProperty("status")), "the watcher failed '%s': %s", verb, answer.getProperty("message"));
				return answer;
			}
			Check.that(this.process.isAlive(), "the watcher client exited (code %s) while running '%s'; see %s", this.exitCode(), verb, this.dir.resolve("watcher-output.log"));
			ctx.waitTick();
		}
		throw new AssertionError("the watcher did not answer '" + verb + "' within " + timeoutTicks + " ticks");
	}

	/** Waits for the watcher's first reply (it writes reply-0 once connected to the server). */
	Properties awaitReady(ClientGameTestContext ctx, int timeoutTicks) {
		Path reply = this.dir.resolve("commands").resolve("reply-0.properties");
		for (int t = 0; t < timeoutTicks; t++) {
			if (Files.isRegularFile(reply)) {
				return read(reply);
			}
			Check.that(this.process.isAlive(), "the watcher client exited (code %s) before connecting; see %s", this.exitCode(), this.dir.resolve("watcher-output.log"));
			ctx.waitTick();
		}
		throw new AssertionError("the watcher client did not connect within " + timeoutTicks + " ticks; see " + this.dir.resolve("watcher-output.log"));
	}

	private String exitCode() {
		return this.process.isAlive() ? "running" : String.valueOf(this.process.exitValue());
	}

	static void write(Path file, Properties properties) {
		try {
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				properties.store(out, null);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new AssertionError("cannot write " + file, e);
		}
	}

	static Properties read(Path file) {
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(in);
		} catch (IOException e) {
			throw new AssertionError("cannot read " + file, e);
		}
		return properties;
	}

	/** Ends the watcher: asks it to quit (it disconnects and closes its game), then makes sure the process is gone. */
	@Override
	public void close() {
		if (this.process.isAlive()) {
			Properties quit = new Properties();
			quit.setProperty("verb", "quit");
			write(this.dir.resolve("commands").resolve("cmd-" + this.next++ + ".properties"), quit);
			try {
				if (!this.process.waitFor(90, TimeUnit.SECONDS)) {
					this.process.destroy();
					if (!this.process.waitFor(30, TimeUnit.SECONDS)) {
						this.process.destroyForcibly();
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				this.process.destroyForcibly();
			}
		}
	}
}
