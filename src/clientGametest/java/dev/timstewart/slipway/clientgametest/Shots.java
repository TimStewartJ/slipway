package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Screenshots as evidence and as checks. Every shot is saved under the report directory; comparisons record the mean
 * squared difference (0 = identical, 1 = opposite; per channel, normalised, as Fabric's comparison computes it) and
 * then assert through Fabric's screenshot comparison, either against a stored reference image
 * (src/clientGametest/resources/templates) or against an earlier shot of the same run.
 */
final class Shots {
	private Shots() {
	}

	static Path dir(Report.Result result) {
		return SlipwayClientGameTests.reportDir().resolve("screenshots").resolve(result.name);
	}

	/** Removes the scenario's screenshots from an earlier run, so its folder holds only this run's evidence. */
	static void clear(Report.Result result) throws IOException {
		Path dir = dir(result);
		if (Files.isDirectory(dir)) {
			try (Stream<Path> paths = Files.walk(dir)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(path);
				}
			}
		}
	}

	/** Takes and keeps a screenshot as evidence. */
	static Path take(ClientGameTestContext ctx, Report.Result result, String name) {
		Path path = ctx.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(dir(result)));
		result.evidence(path);
		return path;
	}

	static NativeImage load(Path path) {
		try (InputStream in = Files.newInputStream(path)) {
			return NativeImage.read(in);
		} catch (IOException e) {
			throw new AssertionError("cannot read screenshot " + path, e);
		}
	}

	/** A stored reference image of the test mod, if it exists. */
	static Optional<NativeImage> template(String name) {
		Optional<Path> path = FabricLoader.getInstance().getModContainer("slipway_client_gametest").orElseThrow().findPath("templates/" + name + ".png");
		return path.filter(Files::isRegularFile).map(Shots::load);
	}

	/** Mean squared difference over a region (whole images when region is null), normalised to 0..1. */
	static double msd(NativeImage a, NativeImage b, int[] region) {
		Check.that(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "image sizes differ: %dx%d vs %dx%d", a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight());
		int x0 = region == null ? 0 : region[0];
		int y0 = region == null ? 0 : region[1];
		int w = region == null ? a.getWidth() : region[2];
		int h = region == null ? a.getHeight() : region[3];
		double sum = 0.0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int p = a.getPixel(x, y);
				int q = b.getPixel(x, y);
				int dr = ((p >> 16) & 0xFF) - ((q >> 16) & 0xFF);
				int dg = ((p >> 8) & 0xFF) - ((q >> 8) & 0xFF);
				int db = (p & 0xFF) - (q & 0xFF);
				sum += dr * dr + dg * dg + db * db;
			}
		}
		return sum / ((double)w * h * 3.0 * 255.0 * 255.0);
	}

	/** Mean red, green, blue (0..255) and luminance of a region. */
	static double[] mean(NativeImage image, int x0, int y0, int w, int h) {
		double r = 0, g = 0, b = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int p = image.getPixel(x, y);
				r += (p >> 16) & 0xFF;
				g += (p >> 8) & 0xFF;
				b += p & 0xFF;
			}
		}
		double n = (double)w * h;
		r /= n;
		g /= n;
		b /= n;
		return new double[] {r, g, b, 0.2126 * r + 0.7152 * g + 0.0722 * b};
	}

	/**
	 * Compares the current view with a stored reference image: records the difference and asserts it on the same saved
	 * picture. A missing reference fails, or is recorded through Fabric's comparison when reference recording is on
	 * (-PslipwayRecordTemplates=true).
	 */
	static double matchTemplate(ClientGameTestContext ctx, Report.Result result, String name, double maxMsd) {
		Optional<NativeImage> template = template(name);
		if (template.isEmpty()) {
			take(ctx, result, name);
			ctx.assertScreenshotEquals(TestScreenshotComparisonOptions.of(name).withAlgorithm(TestScreenshotComparisonAlgorithm.meanSquaredDifference((float)maxMsd)));
			result.note("reference image %s was missing and has been recorded; review it before committing", name);
			return Double.NaN;
		}
		Path shot = take(ctx, result, name);
		double value;
		try (NativeImage actual = load(shot); NativeImage expected = template.get()) {
			value = msd(actual, expected, null);
		}
		result.metric("msd." + name, value);
		Check.atMost("mean squared difference of " + name + " from its reference image", value, maxMsd);
		return value;
	}

	/** Compares the current view with an earlier shot of this run (same camera): records and asserts the difference. */
	static double matchShot(ClientGameTestContext ctx, Report.Result result, String name, Path earlier, double maxMsd) {
		Path shot = take(ctx, result, name);
		double value;
		try (NativeImage actual = load(shot); NativeImage expected = load(earlier)) {
			value = msd(actual, expected, null);
		}
		result.metric("msd." + name, value);
		Check.atMost("mean squared difference of " + name + " from " + earlier.getFileName(), value, maxMsd);
		return value;
	}

	/** Ticks between the two pictures waitStill compares. */
	static final int STILL_STEP = 10;
	/** Largest difference between two pictures of a still view (identical frames differ by 0). */
	static final double STILL_MSD = 1.0e-6;

	/**
	 * Waits until the view is still: two pictures taken {@link #STILL_STEP} ticks apart are the same. In a new world,
	 * terrain beyond the render distance (Distant Horizons' LODs) keeps arriving for a while, so a picture taken too
	 * early can differ from one taken a few ticks later. Records how long it took and the differences seen; fails if
	 * the view does not settle within the timeout. Only for views without moving parts (no shaders, clouds or players).
	 */
	static int waitStill(ClientGameTestContext ctx, Report.Result result, String label, int timeoutTicks) {
		Path dir = SlipwayClientGameTests.reportDir().resolve("still");
		List<String> steps = new ArrayList<>();
		NativeImage previous = null;
		Path previousShot = null;
		try {
			for (int waited = 0; waited <= timeoutTicks; waited += STILL_STEP) {
				Path shot = ctx.takeScreenshot(TestScreenshotOptions.of("still-" + (waited / STILL_STEP) % 2).disableCounterPrefix().withDestinationDir(dir));
				NativeImage current = load(shot);
				double difference = previous == null ? Double.NaN : msd(current, previous, null);
				if (previous != null) {
					previous.close();
					steps.add(String.format(Locale.ROOT, "%.6f", difference));
				}
				previous = current;
				if (difference <= STILL_MSD) {
					result.metric("still." + label + ".ticks", waited);
					result.note("%s: the view was still after %d ticks (differences between pictures %d ticks apart: %s)", label, waited, STILL_STEP, steps);
					return waited;
				}
				if (waited + STILL_STEP > timeoutTicks && previousShot != null) {
					// Keep the last two pictures as evidence of what kept changing.
					Files.createDirectories(dir(result));
					Files.copy(previousShot, dir(result).resolve(label + "-not-still-a.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					Files.copy(shot, dir(result).resolve(label + "-not-still-b.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				previousShot = shot;
				ctx.waitTicks(STILL_STEP);
			}
		} catch (IOException e) {
			throw new AssertionError("cannot keep the pictures of a view that did not settle", e);
		} finally {
			if (previous != null) {
				previous.close();
			}
		}
		throw new AssertionError(String.format(Locale.ROOT, "%s: the view did not settle within %d ticks; differences between pictures %d ticks apart: %s",
			label, timeoutTicks, STILL_STEP, steps));
	}
}
