package dev.timstewart.slipway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlipwayConfigTest {
	@Test
	void defaultsMatchTheSpecification() {
		SlipwayConfig c = new SlipwayConfig().sanitized();
		assertEquals(4096, c.maxVesselBlocks);
		assertEquals(20.0, c.disassemblyTiltDegrees);
		assertEquals(true, c.survivalRules);
		assertEquals(1.5, c.helmAcceleration);
		assertEquals(50000.0, c.sailThrust);
		assertEquals(500.0, c.hotAirLift);
		assertEquals(100.0, c.burnerVolume);
		assertEquals(0.3, c.ballastTrim);
		// The lift goes to the physics in newtons.
		assertEquals(500.0 * 9.81, c.rigRules().hotAirLift(), 1e-9);
	}

	@Test
	void theSurvivalRulesNumbersAreClamped() {
		SlipwayConfig c = new SlipwayConfig();
		c.survivalRules = false;
		c.helmAcceleration = -1;
		c.sailThrust = Double.NaN;
		c.hotAirLift = Double.POSITIVE_INFINITY;
		c.burnerVolume = -5;
		c.ballastTrim = 7;
		SlipwayConfig s = c.sanitized();
		assertEquals(false, s.survivalRules);
		assertEquals(0.0, s.helmAcceleration);
		assertEquals(50000.0, s.sailThrust);
		assertEquals(500.0, s.hotAirLift);
		assertEquals(0.0, s.burnerVolume);
		assertEquals(1.0, s.ballastTrim);
	}

	@Test
	void hostileValuesAreClamped() {
		SlipwayConfig c = new SlipwayConfig();
		c.maxVesselBlocks = -5;
		c.maxVesselSpan = Integer.MAX_VALUE;
		c.disassemblyTiltDegrees = Double.NaN;
		c.thrustAcceleration = Double.POSITIVE_INFINITY;
		c.maxSpeed = -1;
		c.angularAcceleration = 1e9;
		c.maxTurnRate = 0;
		c.levelStrength = Double.NEGATIVE_INFINITY;
		c.proxyRange = -100;
		c.extraDeniedBlocks = new ArrayList<>(List.of("minecraft:stone", "not an id", "../../etc", "slipway:helm"));
		SlipwayConfig s = c.sanitized();
		assertEquals(1, s.maxVesselBlocks);
		assertEquals(2000, s.maxVesselSpan);
		assertEquals(20.0, s.disassemblyTiltDegrees);
		assertEquals(12.0, s.thrustAcceleration);
		assertEquals(0.5, s.maxSpeed);
		assertEquals(20.0, s.angularAcceleration);
		assertEquals(0.05, s.maxTurnRate);
		assertEquals(1.5, s.levelStrength);
		assertEquals(0, s.proxyRange);
		assertEquals(List.of("minecraft:stone", "slipway:helm"), s.extraDeniedBlocks);
	}

	@Test
	void missingListBecomesEmpty() {
		SlipwayConfig c = new SlipwayConfig();
		c.extraDeniedBlocks = null;
		assertEquals(List.of(), c.sanitized().extraDeniedBlocks);
	}
}
