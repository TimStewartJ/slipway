package dev.timstewart.slipway.film;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The film tool's entrypoint: runs the shot named by {@code slipway.film.shot}. */
public final class FilmMain implements FabricClientGameTest {
	static final Logger LOG = LoggerFactory.getLogger("slipway-film");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		String shot = System.getProperty("slipway.film.shot", "spike");
		LOG.info("=== Slipway film: {} ===", shot);
		switch (shot) {
			case "spike" -> SpikeShot.run(ctx);
			default -> throw new IllegalArgumentException("unknown film shot " + shot);
		}
	}
}
