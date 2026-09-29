package dev.timstewart.slipway.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.timstewart.slipway.physics.jolt.JoltRuntime;
import dev.timstewart.slipway.physics.jolt.JoltSelfTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JoltNativesTest {
	static JoltRuntime.Info load() {
		return JoltRuntime.ensureLoaded(Path.of(System.getProperty("slipway.test.nativeCache", "build/tmp/test-natives")));
	}

	@Test
	void loadsTheBundledLibraryForThisPlatform() {
		JoltRuntime.Info info = load();
		assertEquals(JoltRuntime.platform(), info.platform());
		assertTrue(info.doublePrecision(), "Slipway bundles the double-precision flavour");
		assertTrue(info.libraryFile().endsWith(info.platform().startsWith("windows") ? ".dll" : info.platform().startsWith("macos") ? ".dylib" : ".so"));
	}

	@Test
	void aCompoundBodyComesToRestFarFromTheOrigin() {
		load();
		JoltSelfTest.Result result = JoltSelfTest.run();
		assertTrue(result.passed(), "rest y " + result.restY() + " expected " + result.expectedRestY());
	}
}
