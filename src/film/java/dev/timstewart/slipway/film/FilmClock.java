package dev.timstewart.slipway.film;

/** Film-wide render state shared with the mixins. */
public final class FilmClock {
	private FilmClock() {
	}

	/** While true the game's own render loop draws nothing (FilmLoopMixin). */
	public static volatile boolean holdLoop;
	/** True while a film frame renders; the shader clock then reads {@link #seconds}. */
	public static volatile boolean frameActive;
	/** Scene time of the frame being rendered, in seconds (ticks / 20), plus a fixed offset. */
	public static volatile double seconds;
	/** Scene time one film frame spans, in seconds. */
	public static volatile float frameSeconds;
	/** Offset added to scene time for the shader clock (picks a pleasant cloud layout). */
	public static volatile double offsetSeconds = 600.0;
}
