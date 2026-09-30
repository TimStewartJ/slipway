package dev.timstewart.slipway.film;

import dev.timstewart.slipway.math.VesselPose;
import dev.timstewart.slipway.vessel.ActiveVessel;
import dev.timstewart.slipway.vessel.VesselManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Flies the vessel with helm input, set on the server every tick exactly as {@code /slipway control} sets it (the
 * physics then turns it into forces and torques). Either scripted input, or an autopilot that computes input from
 * the vessel's state like a pilot would: steer to a point and a heading, hold there.
 */
final class FilmPilot {
	private FilmPilot() {
	}

	/** Helm input: each axis -1..1. */
	record Input(double forward, double strafe, double vertical, double pitch, double yaw, double roll) {
		static final Input NONE = new Input(0, 0, 0, 0, 0, 0);
	}

	/** The vessel's server state this tick. */
	record State(VesselPose pose, Vec3 velocity, Vec3 spin, long gameTime) {
		Vec3 position() {
			return this.pose.position();
		}

		/** Heading in degrees, 0 = north (-z), 90 = east. */
		double heading() {
			Vector3d f = this.pose.rotate(0, 0, -1, new Vector3d());
			return Math.toDegrees(Math.atan2(f.x, -f.z));
		}

		double tilt() {
			return this.pose.tiltDegrees();
		}
	}

	static ActiveVessel active(MinecraftServer s, long id) {
		ActiveVessel v = VesselManager.get(s.overworld()).active(id);
		if (v == null) {
			throw new IllegalStateException("vessel " + id + " is not active");
		}
		return v;
	}

	static State state(TestServerContext server, long id) {
		return server.computeOnServer(s -> state(s, id));
	}

	static State state(MinecraftServer s, long id) {
		ActiveVessel v = active(s, id);
		return new State(v.record.pose, v.record.linearVelocity, v.record.angularVelocity, s.overworld().getGameTime());
	}

	/** Sets the input for the next tick (held a few ticks, so a late frame never releases it). */
	static void apply(MinecraftServer s, long id, Input in) {
		ActiveVessel v = active(s, id);
		long now = s.overworld().getGameTime();
		v.input.set((float)in.forward, (float)in.strafe, (float)in.vertical, (float)in.pitch, (float)in.yaw, (float)in.roll, now);
		v.scriptedInputUntil = now + 3;
	}

	static void modes(TestServerContext server, long id, boolean hover, boolean level) {
		server.runOnServer(s -> {
			ActiveVessel v = active(s, id);
			v.record.hover = hover;
			v.record.level = level;
		});
	}

	/**
	 * Autopilot input towards {@code target} (vessel origin, the helm block's corner) and {@code heading}: desired
	 * velocity {@code gain * error} limited to {@code maxSpeed}, turned into thrust per local axis (the physics has
	 * thrust 12 m/s^2 per unit input and drag 0.5/s), and a yaw rate towards the heading. Assumes hover and level.
	 */
	static Input autopilot(State st, Vec3 target, double heading, double gain, double maxSpeed) {
		Vec3 error = target.subtract(st.position());
		Vec3 want = error.scale(gain);
		if (want.length() > maxSpeed) {
			want = want.normalize().scale(maxSpeed);
		}
		Vector3d f = st.pose().rotate(0, 0, -1, new Vector3d());
		Vector3d r = st.pose().rotate(1, 0, 0, new Vector3d());
		Vector3d u = st.pose().rotate(0, 1, 0, new Vector3d());
		double[] in = new double[3];
		Vector3d[] axes = {f, r, u};
		for (int i = 0; i < 3; i++) {
			Vector3d a = axes[i];
			double vw = want.x * a.x + want.y * a.y + want.z * a.z;
			double v = st.velocity().x * a.x + st.velocity().y * a.y + st.velocity().z * a.z;
			in[i] = clamp((0.5 * vw + 2.5 * (vw - v)) / 12.0);
		}
		double dh = wrap(heading - st.heading());
		double yaw = Math.toRadians(dh) * 1.6 / 0.9;
		return new Input(in[0], in[1], in[2], 0, clamp(yaw, 0.7), 0);
	}

	static boolean settled(State st, Vec3 target, double heading, double distance) {
		return st.position().distanceTo(target) < distance && st.velocity().length() < 0.08 && Math.abs(wrap(heading - st.heading())) < 1.0
			&& st.spin().length() < 0.01 && st.tilt() < 1.0;
	}

	static double wrap(double deg) {
		deg %= 360;
		if (deg > 180) {
			deg -= 360;
		}
		if (deg < -180) {
			deg += 360;
		}
		return deg;
	}

	static double clamp(double v) {
		return clamp(v, 1.0);
	}

	static double clamp(double v, double limit) {
		return Math.max(-limit, Math.min(limit, v));
	}
}
