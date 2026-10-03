# Slipway playtest guide (0.2.0-pre.2)

Slipway turns anything you build into a ship you can fly: place a **Slipway Helm** on a structure, use it, and the
structure becomes a vessel that moves and rotates freely (pitch, yaw and roll) while every block stays a real block.
Chests keep their items, doors open, redstone and pistons run, you can walk on the deck while it flies and build on
it at any angle. A vessel can also be let **loose**: then it is a plain physical object that falls, tumbles and lies
on other vessels.

## Where to play

- Prism instance **Slipway - Minecraft 26.3 (Fabric)** (`Slipway-MC-26.3-Fabric`): Minecraft 26.3, Fabric loader
  0.19.5, Fabric API, Sodium, Iris (Bliss shaders on), Distant Horizons, Slipway. Your Tellus 26.3 options and key
  layout were copied over. Distant Horizons here is `3.3.4-tellus-fork.7`: official Distant Horizons 3.3.4 plus the
  Tellus patches and the fixes from the leak investigation, published at
  https://github.com/TimStewartJ/distant-horizons/releases/tag/3.3.4-tellus-fork.7.
  Your Tellus instances (26.3 and 26.2) have the same build; the 26.2 one needed its Fabric loader raised from 0.19.3
  to 0.19.5 for it.
- World **Slipway Sandbox** (creative, cheats on). Near spawn: a demo ship that is already a vessel, and an identical
  copy that is still plain blocks, so you can assemble it yourself. See "The sandbox" below.
- World **Slipway Harbor 2** (creative, cheats on), for the water and the survival rules: see "The harbour" below.
  **Slipway Harbor**, from the first test build, is kept as you left it.

## What's new in this test build (0.2.0-pre.2): survival rules, ladders, jumping, the water under shaders

Not released; built from the branch `buoyancy`. On top of the water of the first test build:

- **Ships are no longer overpowered.** A new vessel moves and lifts itself with what it is built of:
  - The **helm alone** rows any ship slowly: 3 blocks a second in the air, about 1 on water.
  - **Sails**: every block of wool with open air on two opposite sides adds thrust. Light ship, few sails, fast;
    heavy ship, many sails, still slow. Wool in a deck, a wall or a balloon's skin does not count.
  - **Hover needs hot air.** Build a canopy of wool, open below, over **lit campfires**: each fire heats 100 m³, each
    cubic metre lifts 500 kg. With hover on and too little lift the HUD says "NO LIFT" and the ship floats, sinks or
    falls. Put the fires out (shovel or water bottle) and a balloon comes down.
  - **Submarines dive on ballast**: in water, ascend and descend push a ship up or down by up to 30% of its weight. A
    closed hull that displaces 100% to 130% of its weight (the HUD's hull line) dives while you hold descend and comes
    up when you let go.
  - The HUD has two new lines: "Sails 12  Top speed 20.6 m/s" (the speed in the air; water slows it) and
    "Lift: 2 burners carry 140% of its weight: it flies".
  - **Your existing ships keep flying.** A vessel assembled before this build stays free of the rules. To switch one:
    `/slipway mode <id> free false` (or `true`). To turn the rules off for new ships: `"survivalRules": false` in
    `config/slipway.json`.
- **Ladders work on ships**, and **jumping on a deck is a normal jump**: on a tilted, turned or bobbing ship a jump
  used to stop after one tick, and on a ship going down it counted as a fall and hurt in survival.
- **No more bright patch of water** around the ships with shaders on. It came from the submarine's glass windows in
  the water: a bug between Iris and Distant Horizons, which Slipway now works around.

From the first test build, unchanged:

- A vessel that hover does not hold up floats, sinks or sails by its weight and its hull. A raft of planks floats
  low; an open hull floats high, because the air its walls keep dry counts too.
- **The HUD's last line** tells what the hull can do: "Hull displaces 180% of its weight: afloat". Above 100% it
  floats. Load it past that, or heel it until the rim dips, and it reads "TAKING ON WATER" and the ship goes down.
- **Inside the hull you are dry**, also below the waterline, and the sea is not drawn inside the hull.
- **Disassemble in the water**: the hold stays dry. **Assemble in the water**: the sea closes at once where the hull
  stood.
- Kelp and sea grass never become part of a ship, and a ship can be put down on them.

### The harbour

World **Slipway Harbor 2**: you stand on a pier in a deep sea (near 93 64 -348), in creative, with helms, iron,
planks, wool and campfires in your hotbar. It is a new world, made under the survival rules. The one you played
(**Slipway Harbor**) is still there, untouched; ships you assemble in it now follow the rules too, and its barge and
submarine were built for the old ones (no sails, no screw). Four things are moored north of the pier as plain
blocks, bows away from you, and a balloon hangs south of it, behind you; each has a sign. Use (right-click) a helm to
assemble and take it. A ship assembled in the water floats at once.

| Moored | What it shows | Try |
| --- | --- | --- |
| **Raft** (logs, far left) | The helm alone: oars | W: about 1 block a second. Put a mast and a few blocks of wool on it, assemble again, and watch the HUD's top speed |
| **Boat** (planks, a mast and a sail) | Twelve blocks of sail on 68 t | W and A/D: 7.7 blocks a second, no sideways slide. Walk in the hold: dry below the waterline. Pile iron from the pier's far end into it: it gets slower, then goes down |
| **Submarine** (dark oak and glass, hatch on top, grey fins at the stern) | Ballast and a screw | Climb down the ladder through the hatch. Hold **Descend**: it sinks slowly. Ease off to hang at a depth, W to run (2.5 blocks a second), let go to come up |
| **Stone barge** (far right, two big sails) | 1,400 t under 112 blocks of sail | W: it gathers way slowly to 4 blocks a second. Climb down the ladder into the hold, six blocks deep and dry |
| **Balloon** (south of the pier, wool canopy over two campfires) | Hot air | Hop across into the basket and use the helm. It hangs where it is. **Space** climbs (up to 7.7 blocks a second), W sails south on its four blocks of side sail (12 blocks a second). Put a fire out and it sinks |

To put one back: bring it to rest and level, leave the helm, sneak and use the helm. It lands as blocks where it
is. All five were assembled, tried and moored again by the test that built this world
(`gradlew runClientGametest -PslipwayClientGametestOnly=make-harbour`, which makes the world anew).

## What's new in 0.1.3

Fixes only (the play instance has not been updated by this work: it still runs 0.1.1 until the new jar is installed).

- **No black sides after loading a world.** In 0.1.2 a small build that begins at its helm could still come out with
  a black west or north side after a world was loaded, now and then, and kept it until the world was loaded again.
  Say so if you see a black side that stays. (A side that is dark for a moment right after joining and then lights
  up is known.)
- **A block outside a vessel's size limit is deleted with the vessel.** In 0.1.2 a block that got past the limit
  other than by a piston or by placing it (a bucket of water, a tree, a command) stayed in the vessel's storage area
  and could turn up in the next vessel assembled.

## What's new in 0.1.2

- **Loose vessels** (new key **Toggle loose**, default **U**; `/slipway mode <id> loose true|false`). A loose vessel
  gets nothing from Slipway: no hover, no levelling, no drag, and its helm does nothing. It falls, tumbles, lies on
  the ground or on another vessel's deck, rides along and slides off when that vessel rolls. Hover and level keep
  their settings for when you turn loose off again. The HUD shows "Loose ON".
- **Hover holds.** A hovering ship now stays where it stopped, also with cargo on it or with something leaning on
  it, and with level off it keeps its attitude under an off-centre load. A ship that nothing pushes flies, turns and
  stops exactly as in 0.1.1, wherever its helm stands (tests fly it beside a reference without the hold); say so if
  yours feels different.
- **Chests open their lids**, and stay open while you look inside.
- **Pistons move visibly** (also sticky pistons and slime blocks), while flying too.
- **Effects are at the ship**: bone meal's sparkle on crops, a dispenser's smoke, note block notes, lever dust, the
  chips and the sound of mining a block of the ship.
- **No blink when disassembling**: the ship stays on screen until its blocks are there.
- **No black sides**: small builds that begin at their helm (the helm on a corner or an edge) had black west and
  north sides; they are lit now.

Fixes from 0.1.1 are below.

## What was new in 0.1.1

Gameplay is the same as 0.1.0; this build carries the fixes from the world-retention investigation (`DESIGN.md`,
"World retention after closing a world"):

- Closing a world frees it. In 0.1.0 every world you left stayed in memory (about 130 to 175 MB each), mostly in
  Distant Horizons and, with shaders, in Iris's shader cache. The Distant Horizons build above fixes its part (and the
  race behind its rare startup error), and Slipway now clears Iris's cache and fully releases each world's physics
  engine when a world closes.
- The block outline on vessel blocks follows vanilla's rules: hidden with F1 and wherever vanilla hides it for world
  blocks (for example in adventure mode).
- The sandbox world was rebuilt fresh (same layout; the old one is backed up under
  `E:\slipway-e2e\play-instance-backups`).

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
| Hover on/off | **Toggle hover** (hover on: the ship holds its position, also under cargo, if it is free or has the lift for its weight; off: gravity applies) |
| Level on/off | **Toggle level** (level on: the ship rights itself; off: it holds any attitude, e.g. inverted) |
| Loose on/off | **Toggle loose** (loose on: no hover, no levelling, no drag, the helm does nothing: the ship is a plain physical object; off: hover and level apply again as they were set) |
| Disassemble | Level the ship (within 20° of level), leave the helm, then sneak and use the helm |

Slipway's keys are under Options > Controls > Key Binds > **Slipway** and can be changed. Their defaults are Z
(descend), H (hover), B (level) and U (loose); in your play instance the first three are **Left Alt**, **Y** and
**J**, because your layout uses Z for drop, B for hotbar slot 9 and H for quick actions. The loose key is new and
will come up as U: check that U is free in your layout (it was not checked against it).

The HUD (top left while piloting) shows the vessel id and block count, speed, altitude, pitch, roll and heading,
hover, level and loose, and mass; for a ship under the survival rules also its sails, top speed and lift. A free ship
(one from before this build, or with the rules off) handles alike at any size (up to about 24 m/s and about 50°/s of
turn); under the rules speed and turning depend on sails and weight.

Operator commands (useful while testing): `/slipway list`, `/slipway info <id>`, `/slipway stats`,
`/slipway mode <id> hover|level|loose|free true|false`, `/slipway disassemble <id>`, `/slipway assemble <x y z of a helm>`, and
`/slipway control <id> <forward> <strafe> <vertical> <pitch> <yaw> <roll> <ticks>` (each axis -1..1), which flies a
vessel with nobody at the helm for that many ticks, so you can walk its deck while it moves, e.g.
`/slipway control 1 0.4 0 0 0 0.3 0 400` for a slow 20-second turn. `/slipway remove <id>` deletes a vessel and its
blocks.

## The sandbox

You spawn on a small glass platform (0 81 -12) facing south, with two identical skiffs floating in front of you
(366 blocks each: oak deck, spruce hull, dark oak keel, railings, a mast with a sail and a red flag, a doorway with
a door, a chest with spare helms and blocks, a redstone lamp with a lever, a sign and a lantern):

- **On your right: vessel #1** (helm at -8 81 0), already assembled. Fly onto its stern (the open end facing you),
  use the helm to take it, and fly. `/slipway info 1` shows its state and position.
- **On your left: the plain copy** (helm at 8 81 0). Use its helm to assemble it yourself; it becomes vessel #2.
  In this build a newly assembled vessel follows the survival rules: the skiff has a sail (count it on the HUD) but no
  hot air, so with hover on the HUD reads "NO LIFT" and it drops to the ground below. To fly it as before, set it free:
  `/slipway mode 2 free true`. Vessel #1 was assembled before the rules and is free.

The world is creative with cheats on, difficulty peaceful, and the ground is about 10 to 15 blocks below. The chest on each
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
10. **Loose cargo** (new): over the deck of a hovering ship, build a few small things in the air (two to thirty
    blocks each, not touching the ship or each other), put a helm on each and use it to assemble them. Then let them
    go: take each helm and press **Toggle loose**, or `/slipway mode <id> loose true` (`/slipway list` shows the
    ids). They should drop onto the deck and lie still. Fly gently (they ride along), stop hard from full speed (they
    slide), roll the ship with level off (past about 30 degrees they slide off and fall). Things to look for: pieces
    sinking into the deck, jittering, flying apart, or falling through it.
11. **Loose ship**: take a helm, press **Toggle loose** while hovering: the ship falls and tumbles as it lands, and
    the helm does nothing; press it again and hover catches it.
12. **Pistons and chests** (fixed): a lever and a piston on deck, a sticky piston with a block, a slime block
    contraption; watch the stroke while the ship flies. Open a chest and watch its lid.
13. **A farm** (fixed): farmland with a waterlogged slab or stair next to it, wheat, a dispenser with bone meal
    facing a crop and a lever or a clock. The sparkle should be on the crop, and crops also grow by themselves.
14. **A boat** (new; the harbour world has one ready): the sandbox has no sea, so make one (`/fill ~-20 ~-8 ~-20 ~20 ~-2 ~20 water` over a pit, or fly
    to an ocean in another world). Build an open hull of planks over the water, floor and two or three rows of
    wall, with the helm inside; assemble it, take the helm, press **Toggle hover**. Things to look for: how it goes
    in (a splash, two or three bobs, then still), whether the waterline looks right for the build, whether the
    inside of the hull is free of water from above and from inside, and whether you can walk in it below the
    waterline without swimming. Then sail: W, A/D, stop.
15. **Sink it** (new): put blocks of iron in the hull and watch the HUD's hull line fall towards 100%. Past it the
    ship settles until the rim is under and goes down; you are in the water then. With level off, roll a floating
    hull (arrow keys) until a rim dips. A hull of stone needs to be large to float at all (about 16 by 16 and six
    high); a raft of stone sinks at once.
16. **A submarine** (new): a closed box with you and the helm inside. With hover on it flies under water like
    anywhere; look for water fog or swimming inside (there should be none). With hover off it comes up like a cork
    unless you ballast it with iron to a little over 100%; then **Descend** takes it down and letting go brings it
    back up.
17. **Dock** (new): float a hull, level, leave the helm, sneak and use the helm. The blocks land in the water and the
    hold should be dry.

## Known limitations of this build

- The pilot's camera does not roll or pitch with the ship; your view stays upright while the ship rotates around you.
- Only blocks become part of a vessel. Entities (item frames, paintings, armor stands, minecarts, mobs) are not
  assembled: standing entities on the deck ride along, but hanging entities stay where they were.
- Water and lava blocks are not assembled (a ship built on the sea does not take the sea with it); waterlogged blocks
  keep their water.
- Vessel blocks are lit by their own storage area, which is open sky: without shaders, a ship inside a cave or under a
  roof still looks sky-lit (day and night still apply). With shaders, the shader's shadows darken it as expected.
- The Distant Horizons view of a far vessel is one coloured box per visible block, not the real models.
- Very large ships: the cap is 4,096 blocks and 512 blocks across (configurable in `config/slipway.json`); larger
  ships cost more. A ship cannot be built or pushed by pistons beyond 512 blocks across after assembly either.
- Mobs do not path-find onto moving decks. Players and entities on decks steeper than 50° slide.
- Right after assembly the vessel can be drawn incomplete for a tick or two while its blocks arrive.
- Loose vessels are not pushed by players or mobs. A deck holds cargo by
  friction only: a hard stop or a sharp turn slides it, tall thin pieces fall over, and a piece sliding fast across
  a deck can catch on an invisible seam and tumble.
- Pistons on a ship do not push you or other entities (you are moved out of the block instead). Particles appear at
  the ship but stay behind when it moves on. A jukebox's music stays where the ship was when the disc started.
  Torches, furnaces and the like show no flame or smoke particles on a ship.
- With shaders on, leaving and reopening worlds many times in one game session still grows memory outside Java by
  about 50 to 120 MB per reopen. It happens with Iris and Bliss alone, without Slipway or Distant Horizons (Iris or
  the graphics driver), so restart the game after many world switches with shaders on. Closed worlds themselves are
  freed now.
- Pre-1.0: saves from this version may not load in later versions.
- With shaders on, Distant Horizons builds before 3.3.3 make ordinary world blocks (not vessels) look dark and blotchy,
  most visibly right after joining the world or reloading shaders. This is a Distant Horizons bug on Minecraft 26.2+,
  not Slipway or Bliss (DESIGN.md, "Dark blotches on world blocks under shaders"). Your instances now have a build
  with upstream's fix (measured clean on a copy of the sandbox world). Players using official Distant Horizons should
  use 3.3.3 or newer.

## Reporting issues

For each problem, note: what you did, what you expected, what happened, and the vessel id (`/slipway info <id>` or
the HUD). Take a screenshot (F2) and keep `logs/latest.log` (and any file in `crash-reports/`) from the instance
folder `%APPDATA%\PrismLauncher\instances\Slipway-MC-26.3-Fabric\.minecraft`. Then tell the assistant in the Slipway
Bridge task (or in chat), attaching or pointing to those files. `/slipway stats` output helps with performance issues.
