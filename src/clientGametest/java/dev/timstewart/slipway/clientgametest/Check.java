package dev.timstewart.slipway.clientgametest;

import java.util.Locale;

/** Assertions for the client GameTests; every failure message says what was expected and what was seen. */
final class Check {
	private Check() {
	}

	static void that(boolean condition, String format, Object... args) {
		if (!condition) {
			throw new AssertionError(String.format(Locale.ROOT, format, args));
		}
	}

	static void finite(String what, double... values) {
		for (double value : values) {
			that(Double.isFinite(value), "%s is not finite: %s", what, java.util.Arrays.toString(values));
		}
	}

	static void near(String what, double actual, double expected, double tolerance) {
		that(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, "%s: expected %.4f +/- %.4f, got %.4f", what, expected, tolerance, actual);
	}

	static void atMost(String what, double actual, double limit) {
		that(Double.isFinite(actual) && actual <= limit, "%s: expected at most %.4f, got %.4f", what, limit, actual);
	}

	static void atLeast(String what, double actual, double limit) {
		that(Double.isFinite(actual) && actual >= limit, "%s: expected at least %.4f, got %.4f", what, limit, actual);
	}

	static <T> T notNull(T value, String format, Object... args) {
		that(value != null, format, args);
		return value;
	}

	static void equal(String what, Object actual, Object expected) {
		that(java.util.Objects.equals(actual, expected), "%s: expected %s, got %s", what, expected, actual);
	}
}
