package dev.timstewart.slipway.film;

import java.awt.GraphicsEnvironment;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.sdl.SDLHints;

/** Before the window exists: nothing may take focus (same as the client GameTests' ClientGametestPreLaunch). */
public final class FilmPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED, "0");
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FORCE_RAISEWINDOW, "0");
		System.setProperty("java.awt.headless", "true");
		GraphicsEnvironment.isHeadless();
	}
}
