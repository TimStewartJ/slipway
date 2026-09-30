package dev.timstewart.slipway.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
	 * Compares the current view with a stored reference image: records the difference, then asserts through Fabric's
	 * comparison. A missing reference fails unless reference recording is on (-PslipwayRecordTemplates=true).
	 */
	static double matchTemplate(ClientGameTestContext ctx, Report.Result result, String name, double maxMsd) {
		Path shot = take(ctx, result, name);
		Optional<NativeImage> template = template(name);
		double value = Double.NaN;
		if (template.isPresent()) {
			try (NativeImage actual = load(shot); NativeImage expected = template.get()) {
				value = msd(actual, expected, null);
			}
			result.metric("msd." + name, value);
		}
		ctx.assertScreenshotEquals(TestScreenshotComparisonOptions.of(name).withAlgorithm(TestScreenshotComparisonAlgorithm.meanSquaredDifference((float)maxMsd)));
		if (template.isEmpty()) {
			result.note("reference image %s was missing and has been recorded; review it before committing", name);
		}
		return value;
	}

	/** Compares the current view with an earlier shot of this run (same camera): records and asserts the difference. */
	static double matchShot(ClientGameTestContext ctx, Report.Result result, String name, Path earlier, double maxMsd) {
		Path shot = take(ctx, result, name);
		double value;
		try (NativeImage actual = load(shot); NativeImage expected = load(earlier)) {
			value = msd(actual, expected, null);
			result.metric("msd." + name, value);
			ctx.assertScreenshotEquals(TestScreenshotComparisonOptions.of(expected).withAlgorithm(TestScreenshotComparisonAlgorithm.meanSquaredDifference((float)maxMsd)));
		}
		return value;
	}
}
