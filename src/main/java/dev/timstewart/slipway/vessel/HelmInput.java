package dev.timstewart.slipway.vessel;

/**
 * The pilot's control axes, each in [-1, 1], plus the hover and level switches. Values arrive from the network,
 * so every setter rejects NaN and infinities and clamps the range.
 */
public final class HelmInput {
	public float forward;
	public float strafe;
	public float vertical;
	public float pitch;
	public float yaw;
	public float roll;
	/** Game time of the last accepted update; input older than a second is treated as released. */
	public long lastUpdate = Long.MIN_VALUE;

	public static float sanitize(float value) {
		if (!Float.isFinite(value)) {
			return 0.0F;
		}
		return Math.max(-1.0F, Math.min(1.0F, value));
	}

	public void set(float forward, float strafe, float vertical, float pitch, float yaw, float roll, long gameTime) {
		this.forward = sanitize(forward);
		this.strafe = sanitize(strafe);
		this.vertical = sanitize(vertical);
		this.pitch = sanitize(pitch);
		this.yaw = sanitize(yaw);
		this.roll = sanitize(roll);
		this.lastUpdate = gameTime;
	}

	public void clear() {
		this.forward = this.strafe = this.vertical = this.pitch = this.yaw = this.roll = 0.0F;
	}

	public boolean isIdle() {
		return this.forward == 0 && this.strafe == 0 && this.vertical == 0 && this.pitch == 0 && this.yaw == 0 && this.roll == 0;
	}

	public HelmInput copy() {
		HelmInput copy = new HelmInput();
		copy.forward = this.forward;
		copy.strafe = this.strafe;
		copy.vertical = this.vertical;
		copy.pitch = this.pitch;
		copy.yaw = this.yaw;
		copy.roll = this.roll;
		copy.lastUpdate = this.lastUpdate;
		return copy;
	}
}
