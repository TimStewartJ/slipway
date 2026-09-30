package dev.timstewart.slipway.film;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The film camera: while a path is set, every rendered frame places the camera on it (FilmCameraMixin), whatever the
 * player entity does. Time is in ticks: after the film's n-th tick, a frame rendered with partial tick d shows time
 * n - 1 + d, the same instant vessels are drawn at (they interpolate from the previous tick's pose to the current one).
 */
public final class FilmCamera {
	private FilmCamera() {
	}

	/** Where the camera is and where it looks; roll in degrees, positive to the right. */
	public record Frame(Vec3 position, float yaw, float pitch, float roll) {
		/** A frame at {@code from} looking at {@code target}. */
		public static Frame lookAt(Vec3 from, Vec3 target, float roll) {
			Vec3 d = target.subtract(from);
			double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
			float yaw = (float)Math.toDegrees(Math.atan2(-d.x, d.z));
			float pitch = (float)Math.toDegrees(-Math.atan2(d.y, horizontal));
			return new Frame(from, yaw, pitch, roll);
		}
	}

	/** A camera path: the frame at a film time (ticks), given the partial tick the frame is rendered with. */
	public interface Path {
		Frame at(double time, float partialTick);
	}

	@Nullable
	private static volatile Path path;
	private static volatile long ticks;

	public static void set(@Nullable Path newPath) {
		path = newPath;
	}

	/** Called by the film after each tick it waits. */
	public static void tick() {
		ticks++;
	}

	public static void resetClock() {
		ticks = 0;
	}

	public static long ticks() {
		return ticks;
	}

	public static double time(float partialTick) {
		return ticks - 1 + partialTick;
	}

	@Nullable
	public static Frame frame(float partialTick) {
		Path p = path;
		return p == null ? null : p.at(time(partialTick), partialTick);
	}
}
