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
		that(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, "%s: expected %s +/- %s, got %s", what, num(expected), num(tolerance), num(actual));
	}

	static void atMost(String what, double actual, double limit) {
		that(Double.isFinite(actual) && actual <= limit, "%s: expected at most %s, got %s", what, num(limit), num(actual));
	}

	static void atLeast(String what, double actual, double limit) {
		that(Double.isFinite(actual) && actual >= limit, "%s: expected at least %s, got %s", what, num(limit), num(actual));
	}

	/** Four decimals, or four significant digits for small values (a limit of 2.5e-4 must not print as 0.0003). */
	private static String num(double value) {
		return value != 0 && Math.abs(value) < 0.01 ? String.format(Locale.ROOT, "%.4g", value) : String.format(Locale.ROOT, "%.4f", value);
	}

	static <T> T notNull(T value, String format, Object... args) {
		that(value != null, format, args);
		return value;
	}

	static void equal(String what, Object actual, Object expected) {
		that(java.util.Objects.equals(actual, expected), "%s: expected %s, got %s", what, expected, actual);
	}
}
