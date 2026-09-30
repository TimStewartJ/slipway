package dev.timstewart.slipway.packagedcheck;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.lwjgl.sdl.SDLHints;

/**
 * Before the game window exists: no focus stealing (SDL hints, headless AWT), and a log listener that collects every
 * error and warning Mixin or Fabric Loader reports while the mods' classes load and transform.
 */
public final class PackagedCheckPreLaunch implements PreLaunchEntrypoint {
	static final List<String> PROBLEMS = new CopyOnWriteArrayList<>();
	static final List<String> WARNINGS = new CopyOnWriteArrayList<>();

	@Override
	public void onPreLaunch() {
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED, "0");
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FORCE_RAISEWINDOW, "0");
		System.setProperty("java.awt.headless", "true");
		GraphicsEnvironment.isHeadless();
		LoggerContext context = (LoggerContext)LogManager.getContext(false);
		AbstractAppender appender = new AbstractAppender("SlipwayPackagedCheck", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
				boolean loaderOrMixin = logger.toLowerCase(java.util.Locale.ROOT).contains("mixin") || logger.startsWith("FabricLoader");
				if (!loaderOrMixin) {
					return;
				}
				String line = "[" + event.getLevel() + "] " + logger + ": " + (event.getMessage() == null ? "" : event.getMessage().getFormattedMessage());
				if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
					PROBLEMS.add(line);
				} else if (event.getLevel() == Level.WARN) {
					WARNINGS.add(line);
				}
			}
		};
		appender.start();
		context.getConfiguration().getRootLogger().addAppender(appender, Level.WARN, null);
		context.updateLoggers();
	}
}
