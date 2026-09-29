package dev.timstewart.slipway.net;

/** At most {@code limit} acquisitions per window of 20 game ticks (one second). */
public final class RateLimiter {
	private boolean started;
	private long windowStart;
	private int count;

	public boolean tryAcquire(long gameTime, int limit) {
		if (!this.started || gameTime - this.windowStart >= 20 || gameTime < this.windowStart) {
			this.started = true;
			this.windowStart = gameTime;
			this.count = 0;
		}
		if (this.count >= limit) {
			return false;
		}
		this.count++;
		return true;
	}
}
