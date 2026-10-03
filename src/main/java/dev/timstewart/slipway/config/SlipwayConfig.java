package dev.timstewart.slipway.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side settings, read from {@code config/slipway.json}. Every value is clamped to a safe range when loaded,
 * so a hand-edited file can never feed NaN, negative or huge numbers into assembly or physics.
 */
public final class SlipwayConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("Slipway/Config");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static volatile SlipwayConfig current = new SlipwayConfig().sanitized();

	/** Most blocks a single assembly may collect. */
	public int maxVesselBlocks = 4096;
	/** Largest extent of a vessel along any axis, in blocks. */
	public int maxVesselSpan = 512;
	/** Disassembly refuses when the vessel's up axis is further than this from vertical. */
	public double disassemblyTiltDegrees = 20.0;
	/** Thrust acceleration at full input, m/s^2; the force scales with the vessel's mass. */
	public double thrustAcceleration = 12.0;
	/** Speed at which drag cancels full thrust, m/s. */
	public double maxSpeed = 24.0;
	/** Angular acceleration at full input, rad/s^2; torque scales with the vessel's inertia. */
	public double angularAcceleration = 1.6;
	/** Turn rate at full input, rad/s. */
	public double maxTurnRate = 0.9;
	/** How strongly level mode rights the vessel, 1/s. */
	public double levelStrength = 1.5;
	/**
	 * Whether water and lava act on vessels: lift by what the hull displaces, and resistance. Off, vessels pass through
	 * fluids as through air, as up to 0.1.3.
	 */
	public boolean buoyancy = true;
	/** Scale of the resistance of water and lava to a vessel's movement and turning; 1 is the default, 0 none. */
	public double waterDrag = 1.0;
	/** Blocks beyond which vessels are shown to clients only as Distant Horizons proxies. */
	public int proxyRange = 4096;
	/** Extra block ids (namespace:path) that never become part of a vessel, on top of the slipway:assembly_deny tag. */
	public List<String> extraDeniedBlocks = new ArrayList<>();

	public static SlipwayConfig get() {
		return current;
	}

	public static void set(SlipwayConfig config) {
		current = config.sanitized();
	}

	/** Loads the file (creating it with defaults when missing) and makes it current. */
	public static SlipwayConfig load(Path file) {
		SlipwayConfig config = new SlipwayConfig();
		if (Files.isRegularFile(file)) {
			try {
				SlipwayConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SlipwayConfig.class);
				if (read != null) {
					config = read;
				}
			} catch (IOException | JsonParseException error) {
				LOGGER.error("Could not read {}; using defaults", file, error);
			}
		}
		config = config.sanitized();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
		} catch (IOException error) {
			LOGGER.warn("Could not write {}", file, error);
		}
		current = config;
		return config;
	}

	/** A copy with every value forced into its allowed range. */
	public SlipwayConfig sanitized() {
		SlipwayConfig c = new SlipwayConfig();
		c.maxVesselBlocks = clamp(this.maxVesselBlocks, 1, 65536);
		c.maxVesselSpan = clamp(this.maxVesselSpan, 1, 2000);
		c.disassemblyTiltDegrees = clamp(this.disassemblyTiltDegrees, 0.0, 90.0, 20.0);
		c.thrustAcceleration = clamp(this.thrustAcceleration, 0.0, 100.0, 12.0);
		c.maxSpeed = clamp(this.maxSpeed, 0.5, 200.0, 24.0);
		c.angularAcceleration = clamp(this.angularAcceleration, 0.0, 20.0, 1.6);
		c.maxTurnRate = clamp(this.maxTurnRate, 0.05, 6.0, 0.9);
		c.levelStrength = clamp(this.levelStrength, 0.0, 20.0, 1.5);
		c.buoyancy = this.buoyancy;
		c.waterDrag = clamp(this.waterDrag, 0.0, 10.0, 1.0);
		c.proxyRange = clamp(this.proxyRange, 0, 65536);
		c.extraDeniedBlocks = this.extraDeniedBlocks == null ? new ArrayList<>() : new ArrayList<>(this.extraDeniedBlocks.stream()
			.filter(s -> s != null && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
			.limit(1024)
			.toList());
		return c;
	}

	static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	static double clamp(double value, double min, double max, double fallback) {
		if (!Double.isFinite(value)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}
}
