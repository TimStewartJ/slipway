# Changelog

What changed for people who play or run Slipway. The development history, with every test run, is in `WORKLOG.md`
and `validation.json`; the reasons behind the design are in `DESIGN.md`.

## Unreleased

### Added

- **Vessels float.** Water (and lava) now acts on every vessel that does not hover: it lifts what the vessel
  displaces and resists its movement. A raft of planks floats with seven tenths of its thickness under water (one
  of slabs half as deep), a block of stone sinks, and a vessel comes to rest where it displaces its own weight, bobbing a few times first.
  What counts is the whole hull, not only its blocks: **the air inside an open hull is kept dry by its walls and
  displaces water too**, so a hull of planks carries far more than a raft of the same planks, and a hull of stone or
  iron floats if it is large enough. A hull that heels is pushed back upright by the water (with level off, too).
- **Sinking.** Load a hull beyond what it can displace, or heel it until its rim dips, and the water runs in over
  the rim: the air inside stops counting and the vessel goes down. A hull of wood comes back up when its rim is clear
  again; one of stone stays on the bottom. Nothing is remembered about water taken in: a hull whose rim is above the
  water is dry.
- **Boats.** With hover off the helm drives a floating vessel as it flies one: thrust, turn, and the water gives the
  boat a keel, so it runs straight and turns instead of sliding, about 9 blocks a second flat out. Turn hover off
  over the sea and the ship drops in with a splash and floats; moving, it throws spray along its waterline.
- **Submarines.** Air with no way out (a closed cabin) is always dry and always displaces. A hovering vessel is left
  alone by the water, as by gravity, and flies under water as it does above it; without hover a closed hull is as
  buoyant as its air makes it, and dives against that with the descend key.
- **Dry inside.** In air that a hull keeps the water out of, below the waterline of a floating hull or in the cabin
  of a submerged one, players, mobs and items are not in the water: no swimming, no slowing, no drowning, no water
  fog, although the world still has its water blocks there. The water's surface is not drawn across the inside of
  the hull either. That holds at any speed: who stands by the aft wall of a hull under way is not left in the sea
  behind it. When the hull floods, all of that ends.
- **Docking stays dry.** Disassembling a vessel in the water puts its blocks into the world and takes the water out
  of the air its hull kept dry, so a docked boat's hold and a submarine's cabin are not full of water.
- The pilot's display has a line for the hull: how much of its weight it can displace (above 100% it floats), and
  whether it floats, sinks, takes in water or is held by hover. `/slipway info` shows the same numbers.
- Settings (`config/slipway.json`): `buoyancy` (off, water is nothing to a vessel, as before) and `waterDrag`.
  Which blocks let water through although one can stand on them is the block tag `slipway:not_watertight` (fences,
  walls, bars, chains, ladders, banners, lanterns); every other block with a collision shape keeps water out, doors
  and trapdoors too, open or shut.

### Changed

- A vessel without hover no longer falls through water, and a loose one neither: both float or sink by their weight.
  A hovering vessel behaves as before.
- The pose packet carries two more flag bits (in the water, flooding); its format is unchanged. Use the same version
  on client and server: an older client does not know which air a hull keeps dry.

### Fixed

- **The pilot's view turns smoothly with the ship.** At the helm, every turn (A/D) made the picture judder: the view
  was turned once a game tick, twenty times a second, while the ship was drawn turning in every frame. Between ticks
  the ship swung against the view, by up to 2.6 degrees at the full turn rate, and the world turned in steps. The
  view now turns in every frame. This was in every version so far. Pitch and roll were not affected: the pilot's view
  does not follow them.

## 0.1.3 (2026-10-01)

Fixes only; flying, building and the controls are as in 0.1.2.

### Fixed

- **A storage area is empty when its vessel is gone** (the known issue of 0.1.2). A block that ended up outside a
  vessel's size limit by other means than a piston or a placed block item (a bucket of water, a tree growing, the
  far half of a bed, a command) stayed in the vessel's storage area when the vessel was disassembled or removed, and
  could turn up as part of the next vessel assembled there. Such a block is now deleted with the vessel. As before it
  is not part of the vessel and is not put into the world at disassembly.
- **No black sides after loading a world.** In 0.1.2 a side of a vessel that lies on a chunk border of its storage
  area (often the west or north side of a small build that begins at its helm) could still come out black after a
  world was loaded, now and then, and stayed black until the world was loaded again. The cause is a fault in the
  game's own light code for chunk columns without any block, which the columns around a vessel are. Right after
  joining a world such a side can still be dark for a moment, until the chunk columns around the vessel's storage
  area have arrived.

### Build

The jar is reproducible: building the same commit again gives the same file (with the same JDK and the same line
endings in the checkout).

### Saves

0.1.0, 0.1.1 and 0.1.2 worlds load; nothing in the save format changed.
## 0.1.2 (2026-10-01)

### New

- **Loose vessels.** A third mode next to hover and level: a loose vessel is a plain rigid body. Slipway applies no
  force to it at all (no hover, no levelling, no drag, no spin brake, no thrust; the helm is ignored), so gravity,
  collisions and friction move it: small structures tumble, lie on another vessel's deck, ride along when it flies
  gently and slide off when it rolls past about 31 degrees. Switch it with the new **Toggle loose** key (default U)
  at the helm or with `/slipway mode <id> loose true|false`. Hover and level keep their settings and apply again
  when loose is turned off. The HUD, the helm's chat hint, `/slipway info` and `/slipway list` show it. Loose vessels
  that have come to rest on the ground stop being simulated until something touches them or the ground changes.

### Fixed

- **Chests open.** A chest, barrel or ender chest on a vessel now opens its lid for everyone near the vessel and keeps
  it open while a player has it open (it never opened in 0.1.1).
- **Pistons are animated.** A piston's stroke on a vessel is shown as it is in the world (in 0.1.1 the blocks jumped).
  Pistons, sticky pistons and slime blocks work while the vessel flies; the vessel's shape, mass and bounds follow.
- **Effects appear at the vessel.** Bone meal's green sparkle, a dispenser's smoke, the note above a note block,
  redstone dust at a lever, and the chips and the thud of mining a vessel block are shown and played where the block
  is. In 0.1.1 they were missing.
- **A hovering vessel holds its place under load.** Before, a hovering vessel only had its weight cancelled and its
  speed braked, so anything resting on it pushed it down for as long as it lay there, and with level off, weight off
  centre slowly turned it. Hover now holds the position (and, with level off, the attitude) the vessel stopped at.
  A vessel that nothing pushes flies, turns and stops exactly as in 0.1.1.
- **No blink at disassembly.** A disassembled vessel stays drawn until the terrain shows its blocks (it vanished for
  a moment before).
- **No black sides.** Sides of a vessel that lie on a chunk border of its storage area were drawn black, in any
  light: most often the west and north sides of a small build that begins at its helm (a crate, a keg, a raft with
  the helm on its edge). In 0.1.0 and 0.1.1.
- Placing a block outside a vessel's bounds no longer sends the whole vessel to its viewers again.
- **A vessel stays within the largest size after assembly too** (`maxVesselSpan`, 512 blocks across unless changed).
  A piston does not push a block further out (it does not move, as against obsidian), and a block cannot be placed
  there (the reason is shown, the item is kept). In 0.1.1 the size was only checked at assembly: a slime-block
  flying machine on a vessel could stretch it to the edge of its storage area, 2,016 blocks out, with every chunk
  column in between loaded, ticked, saved and sent to everyone who saw the vessel.
- Disassembling while a piston moves lets the stroke finish first.

### Known limits of the new features

- Loose vessels have no buoyancy (they fall through water like any vessel) and are not pushed by players or mobs.
- A deck passes on at most 0.6 g: when a carrier stops hard or turns sharply, loose cargo slides, and tall thin
  pieces fall over. A piece sliding fast across a deck can catch on the seams between the deck's collision boxes.
- Pistons do not push entities standing on a vessel. Particles do not follow a moving vessel after they appear. A
  jukebox's music stays where the vessel was when the disc started. Torches and furnaces on a vessel show no flame
  or smoke particles.

### Known issues

- A block that ends up outside a vessel's size limit by other means than a piston or a placed block item (a plant
  growing, water flowing, the far half of a bed, a command) is left in the vessel's storage area when the vessel is
  disassembled or removed. Storage areas are reused, so that block can turn up as part of the next vessel assembled
  there. It needs a vessel that already spans `maxVesselSpan`: unlikely at the default of 512 blocks, likely on a
  server with a small limit. Found in review; fixed in 0.1.3.

### Saves

0.1.0 and 0.1.1 worlds load. Vessels saved by 0.1.2 carry one more optional field (`loose`).

## 0.1.1 (2026-09-30)

First public release. Gameplay as 0.1.0.

- Closing a world frees it. In 0.1.0 every world left stayed in memory (130 to 175 MB each): Slipway now clears
  Iris's shader cache when no world is loaded and fully releases each world's physics engine. (The larger part of
  the leak was in Distant Horizons; see `DESIGN.md`, "World retention".)
- The outline of the block under the crosshair on a vessel follows vanilla's rules (hidden with F1 and wherever
  vanilla hides it).
- Riders stay on the deck when a vessel is disassembled under them.
- Checked in production Minecraft with Fabric API only; with Sodium and Iris; with Sodium, Iris and Distant Horizons.

## 0.1.0 (2026-09-29)

First playable build (not published).

- The Slipway Helm: assemble any face-connected structure of blocks into a vessel, fly it with full pitch, yaw and
  roll (Jolt Physics), disassemble it back onto the block grid.
- Blocks stay real blocks in a reserved region of the world: chests, furnaces, doors, levers and redstone keep
  working; players walk and build on the deck at any angle.
- Hover and level modes, the pilot's HUD, key bindings, operator commands.
- Vessel against terrain and vessel against vessel collisions; entities ride on decks.
- Rendering with Sodium and Iris (shaders, shadows); far vessels through Distant Horizons.
- Multiplayer, save and reload, server-side validation of the pilot's packets.
