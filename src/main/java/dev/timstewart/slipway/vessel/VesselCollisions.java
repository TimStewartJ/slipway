package dev.timstewart.slipway.vessel;

import dev.timstewart.slipway.math.VesselPose;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Entities against vessels, on whichever side simulates the entity (the client for its own player, the server for
 * everything else).
 *
 * <p><b>Collision.</b> After vanilla has collided a movement with the world, it is collided with every nearby vessel
 * in that vessel's own frame: the entity's box is rotated into plot space and replaced by the axis-aligned box that
 * encloses it, the movement is rotated too, and vanilla's axis-by-axis {@link Shapes#collide} runs against the
 * vessel's real block collision shapes. For a flat deck this "fat box" gives exactly the height an upright box rests
 * at on a tilted plane (its support along the deck normal is the same); it is conservative against walls. The
 * clipped movement is rotated back. Each clipped local axis is a contact whose world normal decides the outcome:
 * within {@value #WALKABLE_DEGREES} degrees of up it is ground, steeper it is a slope the entity slides down, else a
 * wall. This is the collide-and-slide idea (Fauerby, "Improved Collision detection and Response", 2003) on voxel
 * shapes.
 *
 * <p><b>Riding the deck.</b> An entity that touched walkable vessel ground is carried: at the start of its next
 * move it is moved by the vessel's motion since the previous tick (the rigid transform from the previous pose to
 * the current one) and turned by the vessel's change of heading. Carrying continues through jumps (up to a second
 * without contact, or while inside the vessel's bounds); when it ends, the entity keeps the vessel's velocity.
 * On walkable vessel ground gravity pulls along the deck's normal and landing removes the downward velocity, so
 * entities stand still on slopes up to {@value #WALKABLE_DEGREES} degrees instead of creeping down them.
 *
 * <p><b>Overlaps.</b> The enclosing box changes size as the vessel turns, and carrying can leave it slightly inside the
 * deck. Vanilla's sweep ignores faces a box already overlaps, so before sweeping the box is pushed out along the
 * shortest way (up out of floors, sideways out of walls: a minimum translation per overlapping part).
 */
public final class VesselCollisions {
	public static final double WALKABLE_DEGREES = 50.0;
	private static final double WALKABLE_COS = Math.cos(Math.toRadians(WALKABLE_DEGREES));
	/** Ticks without contact after which an entity is no longer carried (unless it is inside the vessel's bounds). */
	private static final int CARRY_GRACE_TICKS = 20;
	/** Largest swept box (in blocks) searched for vessel shapes per move. */
	private static final double MAX_SWEEP_VOLUME = 4096.0;
	/** Deepest overlap with a floor that is corrected by lifting the entity out (deeper means inside a wall). */
	private static final double MAX_LIFT = 0.55;
	/** Deepest sideways overlap with a wall that is corrected by pushing the entity out. */
	private static final double MAX_SIDE_PUSH = 0.6;
	/** Overlaps up to this are touching, not penetrating. */
	private static final double OVERLAP_EPSILON = 1.0e-9;

	private VesselCollisions() {
	}

	/** Per-entity state, implemented on {@link Entity} by a mixin. */
	public interface Rider {
		Contact slipway$contact();

		long slipway$carrier();

		void slipway$setCarrier(long vesselId);

		int slipway$lastContactTick();

		void slipway$setLastContactTick(int tick);

		int slipway$lastCarryTick();

		void slipway$setLastCarryTick(int tick);

		Vec3 slipway$groundNormal();

		void slipway$setGroundNormal(Vec3 normal);
	}

	/** What the last move touched on vessels. */
	public static final class Contact {
		public boolean touched;
		public boolean ground;
		public boolean steep;
		public boolean wall;
		public long vessel = -1;
		/** How far the box was lifted out of a floor it had sunk into. */
		public double lift;
		/**
		 * Diagnostics: how far the last collision got. 0 not simulated, 1 no vessel near, 2 sweep too large, 3 no shapes
		 * in the sweep, 4 collided.
		 */
		public int stage;
		/** Diagnostics: plot-space bottom of the entity's box in the last vessel collided with, before any lift. */
		public double boxMinY;
		public final Vector3d groundNormal = new Vector3d();
		public final Vector3d steepNormal = new Vector3d();
		public final Vector3d wallNormal = new Vector3d();

		public void reset() {
			this.touched = this.ground = this.steep = this.wall = false;
			this.vessel = -1;
			this.lift = 0.0;
			this.stage = 0;
		}
	}

	static boolean simulates(Entity entity) {
		return entity.isLocalInstanceAuthoritative() && !entity.noPhysics && !entity.isPassenger() && !(entity instanceof VesselEntity);
	}

	// -------------------------------------------------------------------------------------------------------------
	// Collision
	// -------------------------------------------------------------------------------------------------------------

	/** Hook: after vanilla collided {@code requested} with the world into {@code afterWorld}. */
	public static Vec3 collide(Entity entity, Vec3 requested, Vec3 afterWorld) {
		Contact contact = ((Rider)entity).slipway$contact();
		contact.reset();
		if (!simulates(entity)) {
			return afterWorld;
		}
		AABB box = entity.getBoundingBox();
		List<VesselLookup.View> vessels = VesselLookup.near(entity.level(), box.expandTowards(afterWorld).inflate(1.0));
		contact.stage = 1;
		Vec3 movement = afterWorld;
		for (VesselLookup.View vessel : vessels) {
			movement = collideWith(entity, vessel, box, movement, contact);
		}
		return movement;
	}

	private static Vec3 collideWith(Entity entity, VesselLookup.View vessel, AABB box, Vec3 movement, Contact contact) {
		Rider rider = (Rider)entity;
		Quaterniond rotation = vessel.pose().rotation();
		// On walkable deck, gravity (the downward part of the movement) acts along the deck normal.
		if (rider.slipway$carrier() == vessel.id() && movement.y < 0 && entity.tickCount - rider.slipway$lastContactTick() <= 1) {
			Vec3 n = rider.slipway$groundNormal();
			double down = -movement.y;
			movement = new Vec3(movement.x - n.x * down, 0.0 - n.y * down, movement.z - n.z * down);
		}
		Vec3 centre = box.getCenter();
		Vec3 plotCentre = vessel.worldToPlot(centre);
		double hx = box.getXsize() / 2, hy = box.getYsize() / 2, hz = box.getZsize() / 2;
		// Columns of R are the local axes in world space; the local half extent along axis i is sum_j |R_ji| h_j.
		Vector3d ax = rotation.transform(new Vector3d(1, 0, 0));
		Vector3d ay = rotation.transform(new Vector3d(0, 1, 0));
		Vector3d az = rotation.transform(new Vector3d(0, 0, 1));
		double lx = Math.abs(ax.x) * hx + Math.abs(ax.y) * hy + Math.abs(ax.z) * hz;
		double ly = Math.abs(ay.x) * hx + Math.abs(ay.y) * hy + Math.abs(ay.z) * hz;
		double lz = Math.abs(az.x) * hx + Math.abs(az.y) * hy + Math.abs(az.z) * hz;
		AABB local = new AABB(plotCentre.x - lx, plotCentre.y - ly, plotCentre.z - lz, plotCentre.x + lx, plotCentre.y + ly, plotCentre.z + lz);
		contact.boxMinY = local.minY;
		// Turning decks, carrying and teleports (a dismount) can leave the box slightly inside the deck or a wall (the
		// enclosing box grows as the vessel turns), and a sweep ignores shapes the box already overlaps by more than
		// 1e-7: first push it out of whatever it overlaps.
		Vector3d push = depenetration(local, entity.level().getBlockCollisions(null, local));
		boolean pushed = push.x != 0.0 || push.y != 0.0 || push.z != 0.0;
		if (pushed) {
			local = local.move(push.x, push.y, push.z);
			contact.lift = Math.max(contact.lift, push.y);
		}
		Vector3d m = rotation.transformInverse(new Vector3d(movement.x, movement.y, movement.z));
		Vec3 localMove = new Vec3(m.x, m.y, m.z);
		AABB swept = local.expandTowards(localMove).inflate(1.0e-7);
		if (swept.getXsize() * swept.getYsize() * swept.getZsize() > MAX_SWEEP_VOLUME) {
			contact.stage = Math.max(contact.stage, 2);
			return movement;
		}
		Iterable<VoxelShape> shapes = entity.level().getBlockCollisions(null, swept);
		if (!shapes.iterator().hasNext()) {
			contact.stage = Math.max(contact.stage, 3);
			return pushed ? pushedMovement(contact, vessel.id(), rotation, localMove, push) : movement;
		}
		contact.stage = 4;
		Vec3 resolved = collideAxes(localMove, local, shapes);
		// Step up in the vessel's frame, like vanilla does on terrain, when the deck is roughly upright.
		boolean grounded = rider.slipway$carrier() == vessel.id() || (localMove.y < 0 && resolved.y != localMove.y);
		if (entity.maxUpStep() > 0 && grounded && ay.y > WALKABLE_COS && (resolved.x != localMove.x || resolved.z != localMove.z)) {
			Vec3 stepped = tryStep(entity, local, localMove, resolved);
			if (stepped != null) {
				resolved = stepped;
			}
		}
		recordContacts(contact, vessel.id(), rotation, localMove, resolved);
		if (pushed) {
			recordPush(contact, vessel.id(), rotation, push);
			resolved = resolved.add(push.x, push.y, push.z);
		}
		Vector3d w = rotation.transform(new Vector3d(resolved.x, resolved.y, resolved.z));
		return new Vec3(w.x, w.y, w.z);
	}

	/**
	 * The smallest push that takes a box out of the shapes it overlaps, per shape part along the shortest way out:
	 * up out of a floor (at most {@value #MAX_LIFT} blocks) or sideways out of a wall (at most {@value #MAX_SIDE_PUSH}),
	 * never down; deeper overlaps are left alone (the box is inside a wall, not touching it). Pushes from several parts
	 * combine per direction; opposite pushes cancel. Zero when nothing overlaps.
	 */
	static Vector3d depenetration(AABB box, Iterable<VoxelShape> shapes) {
		double up = 0.0, east = 0.0, west = 0.0, south = 0.0, north = 0.0;
		for (VoxelShape shape : shapes) {
			for (AABB part : shape.toAabbs()) {
				if (Math.min(box.maxX, part.maxX) - Math.max(box.minX, part.minX) <= OVERLAP_EPSILON
					|| Math.min(box.maxY, part.maxY) - Math.max(box.minY, part.minY) <= OVERLAP_EPSILON
					|| Math.min(box.maxZ, part.maxZ) - Math.max(box.minZ, part.minZ) <= OVERLAP_EPSILON) {
					continue;
				}
				double lift = part.maxY - box.minY;
				double toEast = part.maxX - box.minX;
				double toWest = box.maxX - part.minX;
				double toSouth = part.maxZ - box.minZ;
				double toNorth = box.maxZ - part.minZ;
				double side = Math.min(Math.min(toEast, toWest), Math.min(toSouth, toNorth));
				if (lift <= MAX_LIFT && lift <= side) {
					up = Math.max(up, lift);
				} else if (side <= MAX_SIDE_PUSH) {
					if (side == toEast) {
						east = Math.max(east, toEast);
					} else if (side == toWest) {
						west = Math.max(west, toWest);
					} else if (side == toSouth) {
						south = Math.max(south, toSouth);
					} else {
						north = Math.max(north, toNorth);
					}
				}
			}
		}
		return new Vector3d(east - west, up, south - north);
	}

	/** The movement when the box was pushed out of the vessel's blocks and nothing else is in the way. */
	private static Vec3 pushedMovement(Contact contact, long vesselId, Quaterniond rotation, Vec3 localMove, Vector3d push) {
		recordPush(contact, vesselId, rotation, push);
		double y = push.y > 0.0 ? Math.max(0.0, localMove.y) + push.y : localMove.y;
		Vector3d w = rotation.transform(new Vector3d(localMove.x + push.x, y, localMove.z + push.z));
		return new Vec3(w.x, w.y, w.z);
	}

	/** Being pushed out of a floor is standing on it; out of a wall, touching it. */
	private static void recordPush(Contact contact, long vesselId, Quaterniond rotation, Vector3d push) {
		if (push.y > 0.0) {
			recordNormal(contact, vesselId, rotation.transform(new Vector3d(0, 1, 0)));
		}
		if (push.x != 0.0 || push.z != 0.0) {
			recordNormal(contact, vesselId, rotation.transform(new Vector3d(push.x, 0, push.z).normalize()));
		}
	}

	/** Classifies a touched surface by its world normal: ground, steep slope or wall. */
	private static void recordNormal(Contact contact, long vesselId, Vector3d n) {
		contact.touched = true;
		contact.vessel = vesselId;
		if (n.y >= WALKABLE_COS) {
			contact.ground = true;
			contact.groundNormal.set(n);
		} else if (n.y > 0.05) {
			contact.steep = true;
			contact.steepNormal.set(n);
		} else {
			contact.wall = true;
			contact.wallNormal.set(n);
		}
	}

	/** Vanilla's axis order: the largest movement last, Y first. */
	static Vec3 collideAxes(Vec3 movement, AABB box, Iterable<VoxelShape> shapes) {
		Vec3 resolved = Vec3.ZERO;
		for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
			double along = movement.get(axis);
			if (along != 0.0) {
				double allowed = Shapes.collide(axis, box.move(resolved), shapes, along);
				resolved = resolved.with(axis, allowed);
			}
		}
		return resolved;
	}

	@Nullable
	private static Vec3 tryStep(Entity entity, AABB local, Vec3 localMove, Vec3 resolved) {
		double step = entity.maxUpStep();
		AABB grounded = local.move(0, Math.min(0, resolved.y), 0);
		AABB stepBox = grounded.expandTowards(localMove.x, step, localMove.z);
		Iterable<VoxelShape> shapes = entity.level().getBlockCollisions(null, stepBox.inflate(1.0e-7));
		double up = Shapes.collide(Direction.Axis.Y, grounded, shapes, step);
		AABB raised = grounded.move(0, up, 0);
		double sx = Shapes.collide(Direction.Axis.X, raised, shapes, localMove.x);
		raised = raised.move(sx, 0, 0);
		double sz = Shapes.collide(Direction.Axis.Z, raised, shapes, localMove.z);
		raised = raised.move(0, 0, sz);
		double down = Shapes.collide(Direction.Axis.Y, raised, shapes, -up);
		if (sx * sx + sz * sz <= resolved.x * resolved.x + resolved.z * resolved.z + 1.0e-7) {
			return null;
		}
		return new Vec3(sx, Math.min(0, resolved.y) + up + down, sz);
	}

	private static void recordContacts(Contact contact, long vesselId, Quaterniond rotation, Vec3 localMove, Vec3 resolved) {
		for (Direction.Axis axis : Direction.Axis.values()) {
			double wanted = localMove.get(axis);
			if (wanted == 0.0 || Math.abs(resolved.get(axis) - wanted) < 1.0e-7) {
				continue;
			}
			// Blocked moving towards -axis means the surface faces +axis.
			Vector3d n = new Vector3d(axis == Direction.Axis.X ? 1 : 0, axis == Direction.Axis.Y ? 1 : 0, axis == Direction.Axis.Z ? 1 : 0);
			if (wanted > 0) {
				n.negate();
			}
			recordNormal(contact, vesselId, rotation.transform(n));
		}
	}

	/**
	 * Hook at the end of {@code Entity.move}: sets ground and collision flags from vessel contacts and removes the
	 * velocity going into the touched surfaces (which is what makes steep decks slide).
	 */
	public static void afterMove(Entity entity, Vec3 requested, Vec3 velocityBefore, boolean worldCollided) {
		Contact contact = ((Rider)entity).slipway$contact();
		if (!contact.touched) {
			return;
		}
		Rider rider = (Rider)entity;
		Vec3 velocity = velocityBefore;
		if (contact.ground) {
			// Walkable deck stops a fall the way flat ground does: the downward velocity goes, not just the part of it
			// that points into the deck (whose remainder would slide the entity down any slope).
			velocity = removeInto(new Vec3(velocity.x, Math.max(0.0, velocity.y), velocity.z), contact.groundNormal);
			rider.slipway$setCarrier(contact.vessel);
			rider.slipway$setLastContactTick(entity.tickCount);
			rider.slipway$setGroundNormal(new Vec3(contact.groundNormal.x, contact.groundNormal.y, contact.groundNormal.z));
		}
		if (contact.steep) {
			velocity = removeInto(velocity, contact.steepNormal);
		}
		if (contact.wall) {
			velocity = removeInto(velocity, contact.wallNormal);
		}
		if (!worldCollided) {
			entity.setDeltaMovement(velocity);
			entity.horizontalCollision = contact.wall;
			entity.verticalCollision = contact.ground || contact.steep;
			entity.verticalCollisionBelow = contact.ground;
			entity.setOnGroundWithMovement(contact.ground, contact.wall, requested);
			if (contact.ground) {
				entity.resetFallDistance();
			}
		} else if (contact.ground) {
			entity.setOnGroundWithMovement(true, entity.horizontalCollision, requested);
			entity.resetFallDistance();
		}
	}

	private static Vec3 removeInto(Vec3 velocity, Vector3d normal) {
		double into = velocity.x * normal.x + velocity.y * normal.y + velocity.z * normal.z;
		return into >= 0 ? velocity : new Vec3(velocity.x - into * normal.x, velocity.y - into * normal.y, velocity.z - into * normal.z);
	}

	// -------------------------------------------------------------------------------------------------------------
	// Carrying
	// -------------------------------------------------------------------------------------------------------------

	/** Hook at the start of {@code Entity.move}: moves an entity standing on a vessel with it, once per tick. */
	public static void carry(Entity entity) {
		Rider rider = (Rider)entity;
		long id = rider.slipway$carrier();
		if (id < 0 || rider.slipway$lastCarryTick() == entity.tickCount) {
			return;
		}
		rider.slipway$setLastCarryTick(entity.tickCount);
		if (!simulates(entity)) {
			rider.slipway$setCarrier(-1);
			return;
		}
		VesselLookup.View vessel = byId(entity.level(), entity.getBoundingBox().inflate(4.0), id);
		if (vessel == null) {
			rider.slipway$setCarrier(-1);
			return;
		}
		VesselPose previous = vessel.previousPose();
		VesselPose current = vessel.pose();
		boolean inside = vessel.worldBounds().inflate(1.0, 3.0, 1.0).contains(entity.position());
		if (entity.tickCount - rider.slipway$lastContactTick() > CARRY_GRACE_TICKS && !inside) {
			// Leaving the deck: keep the vessel's velocity (blocks per second to blocks per tick), bounded.
			Vec3 inherit = vessel.velocity().scale(0.05);
			if (inherit.lengthSqr() > 4.0) {
				inherit = inherit.normalize().scale(2.0);
			}
			entity.setDeltaMovement(entity.getDeltaMovement().add(inherit));
			rider.slipway$setCarrier(-1);
			return;
		}
		if (previous.equals(current)) {
			return;
		}
		Vec3 position = entity.position();
		Vector3d local = previous.worldToLocal(position.x, position.y, position.z, new Vector3d());
		Vector3d moved = current.localToWorld(local.x, local.y, local.z, new Vector3d());
		if (!moved.isFinite() || moved.distanceSquared(position.x, position.y, position.z) > 64.0) {
			return;
		}
		entity.setPos(moved.x, moved.y, moved.z);
		float turn = (float)current.yawTurnSinceDegrees(previous);
		if (turn != 0f) {
			entity.setYRot(entity.getYRot() - turn);
			if (entity instanceof LivingEntity living) {
				living.yBodyRot -= turn;
				living.yHeadRot -= turn;
			}
		}
	}

	private static VesselLookup.@Nullable View byId(Level level, AABB around, long id) {
		for (VesselLookup.View view : VesselLookup.near(level, around.inflate(64.0))) {
			if (view.id() == id) {
				return view;
			}
		}
		return null;
	}

	/** True when a player stands on or near a vessel (the server relaxes its "floating" check for them). */
	public static boolean isNearVessel(Player player) {
		return !VesselLookup.near(player.level(), player.getBoundingBox().inflate(2.0)).isEmpty();
	}
}
