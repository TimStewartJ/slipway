package dev.timstewart.slipway.vessel;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * How far a player on a vessel has fallen, as the deck saw it. The server adds up a player's fall from the moves the
 * player's client reports, in the world: on a ship that is going down, every jump is a fall of the jump and of all the
 * ship sank meanwhile, and the player lands hurt (and the hurt sends the client the server's idea of the player's
 * speed, which cuts the next jump short). So for a player within a vessel's bounds the vertical part of a move is
 * taken against where the vessel would have carried the player's last place.
 *
 * <p>One instance per player connection, used on the server thread.
 */
public final class DeckFall {
	/** A move that differs from the reported one by more than this is not the vessel's doing (it was teleported). */
	private static final double LARGEST_CARRY = 8.0;

	private long vessel = -1;
	private final Vector3d local = new Vector3d();

	/**
	 * The vertical part of the move that brought the player to where it is now, against the deck.
	 *
	 * @param dy the vertical part in the world
	 */
	public double against(Entity player, double dy) {
		Vec3 pos = player.position();
		VesselLookup.View aboard = null;
		for (VesselLookup.View view : VesselLookup.near(player.level(), player.getBoundingBox().inflate(1.0, 3.0, 1.0))) {
			if (view.worldBounds().inflate(1.0, 3.0, 1.0).contains(pos)) {
				aboard = view;
				if (view.id() == this.vessel) {
					break;
				}
			}
		}
		if (aboard == null) {
			this.vessel = -1;
			return dy;
		}
		double against = dy;
		if (aboard.id() == this.vessel) {
			Vector3d carried = aboard.pose().localToWorld(this.local.x, this.local.y, this.local.z, new Vector3d());
			double relative = pos.y - carried.y;
			if (Double.isFinite(relative) && Math.abs(relative - dy) <= LARGEST_CARRY) {
				against = relative;
			}
		}
		this.vessel = aboard.id();
		aboard.pose().worldToLocal(pos.x, pos.y, pos.z, this.local);
		return against;
	}
}
