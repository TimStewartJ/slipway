package dev.timstewart.slipway.clientgametest;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Counts the server's movement corrections and kicks as they are logged in this process (the test's server runs
 * in-process). Vanilla has no other signal for "moved wrongly" corrections; this reads the logging events themselves,
 * not log files.
 */
final class ServerWarnings extends AbstractAppender {
	static final AtomicInteger MOVEMENT_CORRECTIONS = new AtomicInteger();
	static final AtomicInteger KICKS = new AtomicInteger();
	private static boolean installed;

	private ServerWarnings() {
		super("SlipwayServerWarnings", null, null, true, Property.EMPTY_ARRAY);
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		LoggerContext context = (LoggerContext)LogManager.getContext(false);
		ServerWarnings appender = new ServerWarnings();
		appender.start();
		context.getConfiguration().getRootLogger().addAppender(appender, Level.INFO, null);
		context.updateLoggers();
	}

	static void reset() {
		MOVEMENT_CORRECTIONS.set(0);
		KICKS.set(0);
	}

	@Override
	public void append(LogEvent event) {
		String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
		if (message.contains("moved wrongly") || message.contains("moved too quickly")) {
			MOVEMENT_CORRECTIONS.incrementAndGet();
		}
		if (message.contains("lost connection: Kicked") || message.contains("was kicked") || message.contains("Flying is not enabled")) {
			KICKS.incrementAndGet();
		}
	}
}
