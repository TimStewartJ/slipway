package dev.timstewart.slipway.physics;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jspecify.annotations.Nullable;

/**
 * What a vessel has to move and lift itself with, read off its blocks: its sails, and the hot air it holds over its
 * burners. A vessel that follows the survival rules is driven by these instead of by a thrust and a hover that every
 * vessel gets for nothing (see {@link #rating}); a free vessel ignores them.
 *
 * <p><b>Sails.</b> A sail block (wool, by the {@code slipway:sails} tag) with open air on two opposite sides, east
 * and west or north and south, is cloth the wind blows through the rigging at: it counts. Wool laid as a deck, walled
 * in, or forming the skin of a balloon has air on one side at most and does not. Every sail adds the same push, in
 * whatever direction the helm asks for; there is no wind to trim them to. Open water counts as open air: a fin of wool
 * on a submarine's stern is its screw.
 *
 * <p><b>Hot air.</b> The air a vessel's blocks hold from rising away is its envelope: turn the vessel upside down and
 * it is the air {@link Hull} would call sheltered (below a rim, here the mouth of a balloon or the top of a doorway)
 * or sealed. It is found with the same flood on the same blocks with up and down swapped. A burner (a lit campfire,
 * by the {@code slipway:burners} tag) heats such air when there is nothing but air between it and the envelope
 * straight above, within {@value #BURNER_REACH} blocks. Each burner heats so much air; the air that is both held and
 * heated lifts.
 *
 * <p>Plain Java: the same numbers from the same blocks anywhere, and testable without the game.
 *
 * @param sails sail blocks that count
 * @param burners burners with envelope above them
 * @param envelope air the vessel holds from rising away, m^3
 */
public record Rig(int sails, int burners, double envelope) {
	public static final Rig NONE = new Rig(0, 0, 0);
	/** How far above itself a burner heats. */
	public static final int BURNER_REACH = 16;
	/** The least a vessel that flies at all can climb with, m/s^2: one with exactly its weight in lift still gets off the ground. */
	public static final double LEAST_CLIMB = 0.5;
	/** Letting hot air out is free: a vessel that flies can always come down at half a g. */
	public static final double VENT = 0.5 * VesselController.GRAVITY;
	/** The least share of the full turn rate: the heaviest hulk still answers its helm. */
	public static final double LEAST_TURN = 0.3;

	/**
	 * The numbers the rules are made of (the server's settings).
	 *
	 * @param helmAcceleration what every helm gives whatever the vessel weighs, m/s^2: oars and sweeps, as many as it has room for
	 * @param sailThrust what one sail block adds, N
	 * @param hotAirLift what a cubic metre of heated air lifts, N
	 * @param burnerVolume how much air one burner heats, m^3
	 * @param ballastTrim how hard a vessel in water can push itself up or down, as a share of its weight
	 */
	public record Rules(double helmAcceleration, double sailThrust, double hotAirLift, double burnerVolume, double ballastTrim) {
	}

	/** The push of helm and sails on a vessel of {@code mass} kg, N. */
	public double thrust(Rules rules, double mass) {
		return rules.helmAcceleration() * mass + this.sails * rules.sailThrust();
	}

	/** The air that is both held and heated, m^3. */
	public double hotAir(Rules rules) {
		return Math.min(this.envelope, this.burners * rules.burnerVolume());
	}

	/** The lift of the hot air, N. */
	public double lift(Rules rules) {
		return this.hotAir(rules) * rules.hotAirLift();
	}

	/** Lift as a share of the weight of {@code mass} kg: from 1 on the vessel flies. */
	public double liftRatio(Rules rules, double mass) {
		return mass > 0 ? this.lift(rules) / (mass * VesselController.GRAVITY) : 0;
	}

	/** The speed thrust and drag settle at in the air, given the speed full thrust acceleration settles at. */
	public double topSpeed(Rules rules, double mass, double thrustAcceleration, double maxSpeed) {
		if (!(mass > 0) || !(thrustAcceleration > 0)) {
			return 0;
		}
		return maxSpeed * Math.min(1.0, this.thrust(rules, mass) / mass / thrustAcceleration);
	}

	/**
	 * What this rig allows a vessel of {@code mass} kg in one step.
	 *
	 * <ul>
	 * <li>Along the deck it accelerates by what the helm gives every vessel plus the sails' push over its mass, at
	 * most by {@code thrustAcceleration}. Drag is as for every vessel, so the top speed falls in step: without sails
	 * every vessel crawls at the same pace, and a sail makes a light vessel fast and a heavy one a little faster.</li>
	 * <li>It hovers when its hot air lifts at least its weight. Then it climbs with the lift it has to spare (at
	 * least {@link #LEAST_CLIMB}) and comes down by letting air out ({@link #VENT}, or as fast as it climbs).
	 * Otherwise the lift only makes it lighter, and it is not held up.</li>
	 * <li>It turns at a share of the full rate that falls with the square root of its acceleration.</li>
	 * <li>In water it can push itself up or down by a share of its weight (ballast), in proportion to how much of
	 * its weight it displaces: a hull trimmed close to neutral dives and surfaces, a raft dips, and nothing leaves
	 * the water this way.</li>
	 * </ul>
	 *
	 * @param displaced the mass of the water the vessel displaced at the last step, kg
	 */
	public VesselController.Rating rating(Rules rules, double mass, double thrustAcceleration, double displaced) {
		if (!(mass > 0)) {
			return new VesselController.Rating(0, 0, 0, LEAST_TURN, false, 0, 0);
		}
		double thrust = Math.min(thrustAcceleration, this.thrust(rules, mass) / mass);
		double lift = this.lift(rules) / mass;
		boolean airworthy = lift >= VesselController.GRAVITY;
		double climb = airworthy ? Math.min(thrustAcceleration, Math.max(LEAST_CLIMB, lift - VesselController.GRAVITY)) : 0;
		double sink = airworthy ? Math.min(thrustAcceleration, Math.max(climb, VENT)) : 0;
		double turn = thrustAcceleration > 0 ? Math.max(LEAST_TURN, Math.min(1.0, Math.sqrt(thrust / thrustAcceleration))) : 1.0;
		double immersion = Math.max(0.0, Math.min(1.0, displaced / mass));
		return new VesselController.Rating(thrust, climb, sink, turn, airworthy, airworthy ? 0 : lift, rules.ballastTrim() * VesselController.GRAVITY * immersion);
	}

	/** Collects what a rig is read from while a vessel's blocks are gone through. */
	public static final class Builder {
		private final LongOpenHashSet solid = new LongOpenHashSet();
		private final LongArrayList sails = new LongArrayList();
		private final LongArrayList burners = new LongArrayList();
		/** The vessel's blocks upside down: what it would keep water out of that way up is the air it keeps from rising. */
		private final Hull.Builder upsideDown = new Hull.Builder();

		/** A block with a collision shape, as {@link Hull.Builder#block(int, int, int, float, boolean, float, float)} takes it. */
		public void block(int x, int y, int z, float volume, boolean airtight, float bottom, float top) {
			this.solid.add(pack(x, y, z));
			this.upsideDown.block(x, -y, z, volume, airtight, 1f - top, 1f - bottom);
		}

		public void sail(int x, int y, int z) {
			this.sails.add(pack(x, y, z));
		}

		public void burner(int x, int y, int z) {
			this.burners.add(pack(x, y, z));
		}

		public boolean hasBurners() {
			return !this.burners.isEmpty();
		}

		/** Changes whenever the blocks given so far do: to skip the flood for the same blocks. */
		public long fingerprint() {
			return this.upsideDown.fingerprint();
		}

		/** The flood for the envelope; only worth running for a vessel with a burner. */
		public Hull envelope() {
			return this.upsideDown.build();
		}

		/**
		 * @param hull the vessel's hull (for which cells are open to the outside)
		 * @param envelope the result of {@link #envelope()}, or null for a vessel without burners
		 */
		public Rig build(Hull hull, @Nullable Hull envelope) {
			int sails = 0;
			for (int i = 0; i < this.sails.size(); i++) {
				long at = this.sails.getLong(i);
				int x = x(at), y = y(at), z = z(at);
				if (this.open(hull, envelope, x - 1, y, z) && this.open(hull, envelope, x + 1, y, z)
					|| this.open(hull, envelope, x, y, z - 1) && this.open(hull, envelope, x, y, z + 1)) {
					sails++;
				}
			}
			if (envelope == null) {
				return new Rig(sails, 0, 0);
			}
			int burners = 0;
			for (int i = 0; i < this.burners.size(); i++) {
				long at = this.burners.getLong(i);
				int x = x(at), y = y(at), z = z(at);
				for (int up = 1; up <= BURNER_REACH; up++) {
					int held = envelope.cellClass(x, -(y + up), z);
					if (held == Hull.WATERTIGHT) {
						break;
					}
					if (held != Hull.OPEN) {
						burners++;
						break;
					}
				}
			}
			return new Rig(sails, burners, envelope.shelteredVolume() + envelope.sealedVolume());
		}

		/** Air the wind gets at: no block, not below a hull's rim, not sealed in, not inside an envelope. */
		private boolean open(Hull hull, @Nullable Hull envelope, int x, int y, int z) {
			return !this.solid.contains(pack(x, y, z)) && hull.cellClass(x, y, z) == Hull.OPEN && (envelope == null || envelope.cellClass(x, -y, z) == Hull.OPEN);
		}

		private static long pack(int x, int y, int z) {
			return (x & 0x1FFFFFL) << 42 | (y & 0x1FFFFFL) << 21 | z & 0x1FFFFFL;
		}

		private static int x(long packed) {
			return sign((int)(packed >> 42 & 0x1FFFFF));
		}

		private static int y(long packed) {
			return sign((int)(packed >> 21 & 0x1FFFFF));
		}

		private static int z(long packed) {
			return sign((int)(packed & 0x1FFFFF));
		}

		private static int sign(int twentyOneBits) {
			return twentyOneBits << 11 >> 11;
		}
	}
}
