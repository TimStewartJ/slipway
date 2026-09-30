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
		private org.joml.Quaternionf rotation() {
			return new org.joml.Quaternionf().rotationYXZ((float)Math.PI - (float)Math.toRadians(this.yaw), -(float)Math.toRadians(this.pitch),
				-(float)Math.toRadians(this.roll));
		}

		public Vec3 forward() {
			org.joml.Vector3f v = this.rotation().transform(new org.joml.Vector3f(0, 0, -1));
			return new Vec3(v.x, v.y, v.z);
		}

		public Vec3 up() {
			org.joml.Vector3f v = this.rotation().transform(new org.joml.Vector3f(0, 1, 0));
			return new Vec3(v.x, v.y, v.z);
		}

		/** A frame at {@code from} looking at {@code target}. */
		public static Frame lookAt(Vec3 from, Vec3 target, float roll) {
			Vec3 d = target.subtract(from);
			double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
			float yaw = (float)Math.toDegrees(Math.atan2(-d.x, d.z));
			float pitch = (float)Math.toDegrees(-Math.atan2(d.y, horizontal));
			return new Frame(from, yaw, pitch, roll);
		}

		/** A frame at {@code from} looking along {@code forward} with the image's up as close to {@code up} as it can be. */
		public static Frame basis(Vec3 from, Vec3 forward, Vec3 up) {
			Frame noRoll = lookAt(from, from.add(forward), 0);
			org.joml.Quaternionf q = new org.joml.Quaternionf().rotationYXZ((float)Math.PI - (float)Math.toRadians(noRoll.yaw()), -(float)Math.toRadians(noRoll.pitch()), 0);
			org.joml.Vector3f u0 = q.transform(new org.joml.Vector3f(0, 1, 0));
			org.joml.Vector3f l0 = q.transform(new org.joml.Vector3f(-1, 0, 0));
			double r = Math.toDegrees(Math.atan2(-(up.x * l0.x + up.y * l0.y + up.z * l0.z), up.x * u0.x + up.y * u0.y + up.z * u0.z));
			// the camera mixin's roll convention, checked numerically rather than trusted
			org.joml.Quaternionf test = new org.joml.Quaternionf().rotationYXZ((float)Math.PI - (float)Math.toRadians(noRoll.yaw()), -(float)Math.toRadians(noRoll.pitch()),
				-(float)Math.toRadians(r));
			org.joml.Vector3f u = test.transform(new org.joml.Vector3f(0, 1, 0));
			if (u.x * up.x + u.y * up.y + u.z * up.z < 0.9 * up.length()) {
				r = -r;
			}
			return new Frame(from, noRoll.yaw(), noRoll.pitch(), (float)r);
		}
	}

	/** Smootherstep of {@code t} clamped to [0, 1]. */
	public static double ease(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	/** Ease of where {@code t} is between {@code a} and {@code b}. */
	public static double ease(double t, double a, double b) {
		return ease((t - a) / (b - a));
	}

	public static Vec3 lerp(Vec3 a, Vec3 b, double t) {
		return a.add(b.subtract(a).scale(t));
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** Blends two frames (position, and look direction and up via their bases). */
	public static Frame blend(Frame a, Frame b, double t) {
		if (t <= 0) {
			return a;
		}
		if (t >= 1) {
			return b;
		}
		Vec3 fa = a.forward(), fb = b.forward(), ua = a.up(), ub = b.up();
		return Frame.basis(lerp(a.position(), b.position(), t), lerp(fa, fb, t).normalize(), lerp(ua, ub, t).normalize());
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
