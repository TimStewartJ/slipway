package dev.timstewart.slipway.physics.jolt;

import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.NativeLibraryLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts the bundled jolt-jni native library for this platform, verifies its SHA-256 against the manifest the
 * build wrote, loads it through jolt-jni's own loader (so the library binds to jolt-jni's class loader) and
 * performs Jolt's one-time global initialisation. Safe to call from several threads; the work happens once.
 */
public final class JoltRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("Slipway/Jolt");
	/** Resource folder with the natives; unit tests point it at the Debug build. */
	private static final String RESOURCE_ROOT = System.getProperty("slipway.natives.root", "/slipway-natives/");
	private static volatile Info info;

	private JoltRuntime() {
	}

	/** What was loaded, for logs and the debug command. */
	public record Info(String platform, String libraryFile, String sha256, String joltVersion, String buildType, boolean doublePrecision, String configuration) {
	}

	public static Info info() {
		return info;
	}

	public static boolean isLoaded() {
		return info != null;
	}

	/**
	 * Loads and initialises Jolt if that has not happened yet.
	 *
	 * @param cacheDirectory where native libraries are extracted, one sub-directory per content hash
	 * @throws IllegalStateException when this platform has no bundled library or loading fails
	 */
	public static synchronized Info ensureLoaded(Path cacheDirectory) {
		if (info != null) {
			return info;
		}
		String platform = platform();
		Properties manifest = readManifest();
		String entry = manifest.getProperty(platform);
		if (entry == null) {
			throw new IllegalStateException("Slipway has no Jolt native library for " + platform + " (bundled: " + manifest.stringPropertyNames() + ")");
		}
		String[] parts = entry.trim().split(" ");
		String fileName = parts[0];
		String sha256 = parts[1];
		Path library;
		try {
			library = extract(cacheDirectory, platform, fileName, sha256);
		} catch (IOException error) {
			throw new IllegalStateException("Could not extract the Jolt native library to " + cacheDirectory, error);
		}
		if (!NativeLibraryLoader.loadLibrary(library.toAbsolutePath().toString())) {
			throw new IllegalStateException("The Jolt native library at " + library + " failed to load; see stderr");
		}
		Jolt.registerDefaultAllocator();
		Jolt.installJavaTraceCallback(new PrintStream(new LoggerStream(), true, StandardCharsets.UTF_8));
		Jolt.installIgnoreAssertCallback();
		if (!Jolt.newFactory()) {
			throw new IllegalStateException("Jolt could not create its factory");
		}
		Jolt.registerTypes();
		info = new Info(platform, library.toString(), sha256, Jolt.versionString(), Jolt.buildType(), Jolt.isDoublePrecision(), Jolt.getConfigurationString());
		LOGGER.info("jolt-jni {} (Jolt Physics) loaded: {} {} build, {} precision, from {}", info.joltVersion(), platform, info.buildType(),
			info.doublePrecision() ? "double" : "single", library);
		return info;
	}

	/** {@code <os>-<arch>} as used in the bundled resource tree, e.g. {@code windows-x86_64}. */
	public static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String osPart;
		if (os.contains("win")) {
			osPart = "windows";
		} else if (os.contains("mac") || os.contains("darwin")) {
			osPart = "macos";
		} else if (os.contains("linux")) {
			osPart = "linux";
		} else {
			osPart = os.replace(' ', '_');
		}
		String archPart = switch (arch) {
			case "amd64", "x86_64", "x64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> arch;
		};
		return osPart + "-" + archPart;
	}

	private static Properties readManifest() {
		Properties manifest = new Properties();
		try (InputStream in = JoltRuntime.class.getResourceAsStream(RESOURCE_ROOT + "natives.properties")) {
			if (in == null) {
				throw new IllegalStateException("The Slipway jar has no " + RESOURCE_ROOT + "natives.properties");
			}
			manifest.load(in);
		} catch (IOException error) {
			throw new IllegalStateException("Could not read the native library manifest", error);
		}
		return manifest;
	}

	private static Path extract(Path cacheDirectory, String platform, String fileName, String sha256) throws IOException {
		Path directory = cacheDirectory.resolve(platform + "-" + sha256.substring(0, 16));
		Path target = directory.resolve(fileName);
		if (Files.isRegularFile(target) && sha256.equals(sha256(Files.readAllBytes(target)))) {
			return target;
		}
		Files.createDirectories(directory);
		byte[] bytes;
		try (InputStream in = JoltRuntime.class.getResourceAsStream(RESOURCE_ROOT + platform + "/" + fileName)) {
			if (in == null) {
				throw new IOException("Missing resource " + RESOURCE_ROOT + platform + "/" + fileName);
			}
			bytes = in.readAllBytes();
		}
		String actual = sha256(bytes);
		if (!actual.equals(sha256)) {
			throw new IOException("The bundled " + fileName + " for " + platform + " is corrupt (sha256 " + actual + ", expected " + sha256 + ")");
		}
		Path temp = Files.createTempFile(directory, fileName, ".part");
		Files.write(temp, bytes);
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException error) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException error) {
			// Another game instance may hold the file open; its content is identical.
			Files.deleteIfExists(temp);
			if (!Files.isRegularFile(target) || !sha256.equals(sha256(Files.readAllBytes(target)))) {
				throw error;
			}
		}
		return target;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	/** Routes Jolt's trace output to the game log, one line at a time. */
	private static final class LoggerStream extends java.io.OutputStream {
		private final StringBuilder line = new StringBuilder();

		@Override
		public synchronized void write(int b) {
			if (b == '\n') {
				flushLine();
			} else if (b != '\r') {
				this.line.append((char)b);
			}
		}

		private void flushLine() {
			if (!this.line.isEmpty()) {
				LOGGER.info("[jolt] {}", this.line);
				this.line.setLength(0);
			}
		}

		@Override
		public synchronized void flush() {
			flushLine();
		}
	}
}
