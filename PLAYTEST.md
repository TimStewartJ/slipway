# Slipway playtest guide (first playable, 0.1.0)

Slipway turns anything you build into a ship you can fly: place a **Slipway Helm** on a structure, use it, and the
structure becomes a vessel that moves and rotates freely (pitch, yaw and roll) while every block stays a real block.
Chests keep their items, doors open, redstone runs, you can walk on the deck while it flies and build on it at any
angle.

## Where to play

- Prism instance **Slipway - Minecraft 26.3 (Fabric)** (`Slipway-MC-26.3-Fabric`): Minecraft 26.3, Fabric loader
  0.19.5, Fabric API, Sodium, Iris (Bliss shaders on), Distant Horizons, Slipway. Your Tellus 26.3 options and key
  layout were copied over.
- World **Slipway Sandbox** (creative, cheats on). Near spawn: a demo ship that is already a vessel, and an identical
  copy that is still plain blocks, so you can assemble it yourself. See "The sandbox" below.

## Controls

| Action | How |
| --- | --- |
| Assemble a structure | Build it (any blocks, connected face to face), place a Slipway Helm on it, use (right-click) the helm |
| Take the helm | Use the helm of a vessel. Chat shows the controls with your own key bindings |
| Leave the helm | Sneak |
| Thrust forward / back | Forward / back keys (W / S) |
| Turn (yaw) | Left / right keys (A / D) |
| Up / down | Jump (Space) / **Descend** |
| Pitch nose up / down | Up / down arrow |
| Roll left / right | Left / right arrow |
| Strafe left / right | N / M |
| Hover on/off | **Toggle hover** (hover on: the ship holds its position; off: gravity applies) |
| Level on/off | **Toggle level** (level on: the ship rights itself; off: it holds any attitude, e.g. inverted) |
| Disassemble | Level the ship (within 20° of level), leave the helm, then sneak and use the helm |

Slipway's keys are under Options > Controls > Key Binds > **Slipway** and can be changed. Their defaults are Z
(descend), H (hover) and B (level); in your play instance they are **Left Alt**, **Y** and **J**, because your layout
uses Z for drop, B for hotbar slot 9 and H for quick actions.

The HUD (top left while piloting) shows the vessel id and block count, speed, altitude, pitch, roll and heading,
hover and level, and mass. Forces scale with mass, so small and large ships handle alike (up to about 24 m/s and
about 50°/s of turn).

Operator commands (useful while testing): `/slipway list`, `/slipway info <id>`, `/slipway stats`,
`/slipway mode <id> hover|level true|false`, `/slipway disassemble <id>`, `/slipway assemble <x y z of a helm>`, and
`/slipway control <id> <forward> <strafe> <vertical> <pitch> <yaw> <roll> <ticks>` (each axis -1..1), which flies a
vessel with nobody at the helm for that many ticks, so you can walk its deck while it moves, e.g.
`/slipway control 1 0.4 0 0 0 0.3 0 400` for a slow 20-second turn. `/slipway remove <id>` deletes a vessel and its
blocks.

## The sandbox

You spawn on a small glass platform (0 85 -12) facing south, with two identical skiffs floating in front of you
(366 blocks each: oak deck, spruce hull, dark oak keel, railings, a mast with a sail and a red flag, a doorway with
a door, a chest with spare helms and blocks, a redstone lamp with a lever, a sign and a lantern):

- **Left: vessel #1**, already assembled. Walk or fly onto its stern (the open end facing you), use the helm to take
  it, and fly. `/slipway info 1` shows its state.
- **Right: the plain copy** (helm at 8 85 0). Use its helm to assemble it yourself; it becomes vessel #2.

The world is creative with cheats on, difficulty peaceful, and the ground is about 20 blocks below. The chest on each
skiff holds four more Slipway Helms (also in the creative inventory under Functional Blocks, `/give @s slipway:helm`,
or crafted in survival from two sticks on top, a compass in the middle and three planks below) for your own builds:
anything face-connected to the helm becomes part of the vessel, so build ships in the air or on a temporary platform
you remove, not touching the ground.

## What to try

1. **Assemble** the plain copy of the demo ship: use its helm. Try your own builds too, mixing block types: chests
   with items, furnaces, signs, doors, trapdoors, redstone (lamps, levers, repeaters), stairs, slabs, glass, fences,
   waterlogged blocks. Everything should look and behave the same after assembly.
2. **Fly**: thrust, turn, climb, descend, strafe. Turn **level off** and pitch or roll through vertical and upside
   down; turn it back on and watch the ship right itself. Try hover off (it falls) and back on.
3. **Walk on the deck** while it moves (`/slipway control`, see above, or a second player at the helm): walk during
   turns, jump, stand on a banked deck (walkable up to 50°; steeper decks make you slide off).
4. **Build on the ship** at odd angles: place and break blocks on a tilted, turning ship; logs and stairs should
   orient relative to the ship. Open chests, use doors and levers, and flip a lever that powers a lamp while flying.
5. **Collide**: fly into terrain, land on the ground with hover off, ram another vessel.
6. **Disassemble** when level (it refuses when tilted more than 20° or when something is in the way). The ship lands
   on the block grid, turned to the nearest quarter turn, with everything intact.
7. **Save and quit** with a ship in flight, reopen: the ship, its blocks, chest contents and motion are back.
8. **Shaders and distance**: with Bliss on the ship is lit and casts shadows; fly far away (beyond your render
   distance) and it stays visible through Distant Horizons as a coarse model.
9. **Multiplayer** (LAN or a server with Slipway): two players, one flying, one riding on deck.

## Known limitations of this build

- The pilot's camera does not roll or pitch with the ship; your view stays upright while the ship rotates around you.
- Only blocks become part of a vessel. Entities (item frames, paintings, armor stands, minecarts, mobs) are not
  assembled: standing entities on the deck ride along, but hanging entities stay where they were.
- Water and lava blocks are not assembled (a ship built on the sea does not take the sea with it); waterlogged blocks
  keep their water.
- Vessel blocks are lit by their own storage area, which is open sky: without shaders, a ship inside a cave or under a
  roof still looks sky-lit (day and night still apply). With shaders, the shader's shadows darken it as expected.
- The Distant Horizons view of a far vessel is one coloured box per visible block, not the real models.
- Very large ships: the cap is 4,096 blocks (configurable in `config/slipway.json`); larger ships cost more.
- Mobs do not path-find onto moving decks. Players and entities on decks steeper than 50° slide.
- Leaving and reopening worlds many times in one game session slowly grows memory. This was measured at about
  175 MB per reopen, and the heap analysis traces the retention to Distant Horizons, not Slipway. Restart the game
  after many world switches.
- Pre-1.0: saves from this version may not load in later versions.

## Reporting issues

For each problem, note: what you did, what you expected, what happened, and the vessel id (`/slipway info <id>` or
the HUD). Take a screenshot (F2) and keep `logs/latest.log` (and any file in `crash-reports/`) from the instance
folder `%APPDATA%\PrismLauncher\instances\Slipway-MC-26.3-Fabric\.minecraft`. Then tell the assistant in the Slipway
Bridge task (or in chat), attaching or pointing to those files. `/slipway stats` output helps with performance issues.
