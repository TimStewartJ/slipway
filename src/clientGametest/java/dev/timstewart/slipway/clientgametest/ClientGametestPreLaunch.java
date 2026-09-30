package dev.timstewart.slipway.clientgametest;

import java.awt.GraphicsEnvironment;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.sdl.SDLHints;

/**
 * Runs before Minecraft creates its window. The tests run on a desktop someone may be using, so nothing may take focus:
 * SDL shows and raises the game window without activating it, and no AWT window may open at all (the in-process
 * dedicated server's GUI, Distant Horizons' dialogs). AWT decides headless mode once, on the first query, so asking here
 * (before Distant Horizons sets it back to false for its dialogs) keeps it for the whole run.
 */
public final class ClientGametestPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED, "0");
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FORCE_RAISEWINDOW, "0");
		System.setProperty("java.awt.headless", "true");
		GraphicsEnvironment.isHeadless();
	}
}
