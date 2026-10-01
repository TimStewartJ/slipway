# Film tool

Renders promotional footage of Slipway frame by frame inside the real game (Sodium, Iris with Bliss, Distant
Horizons), driven by the Fabric client GameTest API. Nothing here ships in the mod: the `film` source set and the
`runFilm` Gradle run are development only. The window never takes focus (`FilmPreLaunch` sets SDL hints), so the PC
stays usable while it renders.

## Quick start: the Reddit showcase (version 2, 4:5)

```
.\gradlew.bat runFilm -PslipwayFilm=reddit -PslipwayFilmSize=1080x1350 --no-daemon --console=plain
python tools/film/deliver.py build/film/out/reddit-1080x1350 <delivery folder>
```

The first command renders the frames (about 7 minutes including game start, world generation and a 60 s wait for
Distant Horizons). The second checks them (`check_encode.py`), assembles the captioned video
(`assemble.py`) and writes the delivery folder: `slipway-4x5.mp4`, its `.ass` caption file, `segments.json`, a sheet
of the raw frames, a sheet with one frame per second of the video, and a thumbnail; it then verifies the encode with
ffprobe and compares the first and the last frame (the loop point).

On a machine that also runs other game tests, start every `runFilm` through the lock helper so that only one game
uses the GPU at a time:

```
& E:\slipway-e2e\coordination\with-gpu-lock.ps1 -Name film -WorkingDirectory E:\Slipway-film -Command '.\gradlew.bat runFilm -PslipwayFilm=reddit -PslipwayFilmSize=1080x1350 --no-daemon --console=plain > build\film-run.log 2>&1'
```

Rehearsals: a small even size and only the shots in question, for example
`-PslipwayFilmSize=544x680 "-PslipwayFilmOpts=only=cargo+farm,dhWait=5,warm=0,shotSettle=20"` (about 2 to 3 minutes).

## Gradle properties

| Property | Meaning | Default |
| --- | --- | --- |
| `-PslipwayFilm=<shot>` | the shot: `reddit`, `stills`, `probe`, `scout`, `spike` | `spike` |
| `-PslipwayFilmSize=<w>x<h>` | window and frame size (even numbers, or the encoder refuses) | `1080x1350` |
| `-PslipwayFilmSubframes=<n>` | frames per game tick where a shot does not choose (3 = 60 fps) | `3` |
| `-PslipwayFilmOpts=k=v,k=v` | shot options (below) | none |

Frames go to `build/film/out/<shot>-<w>x<h>/f000000.png ...` with `frames.csv` (one row per frame: tick, partial
tick, frames per tick, `cut`, `segment`, camera, vessel centre, tilt, heading, settle renders, milliseconds) and, for
`reddit`, `segments.json` (the frame of each shot and event).

Common options: `seed` (world seed, default `sunsetcoast`), `daytime` (default 11700, late afternoon; the sun is low
in the west), `rd` (render distance in chunks, default 16), `dhWait` (seconds to let Distant Horizons build far
terrain before recording, default 60), `fov` (vertical field of view in degrees, default 70; the README stills use 55).

`reddit` options (all optional; the defaults are the delivered video):

| Option | Meaning | Default |
| --- | --- | --- |
| `only=a+b` | film only these shots (`hook`, `cargo`, `roll`, `farm`, `return`); the ship is brought home only if `return` is among them | all |
| `warm`, `shotSettle` | 0 skips the warm-up tour of the shot places; unfilmed ticks at each shot's first camera so Distant Horizons can update | 1, 100 |
| `hookTicks` | length of the first shot in ticks | 42 |
| `cargoTicks`, `cargoLead`, `cargoRollAt`, `cargoRollTicks`, `cargoRoll` | cargo shot: length; ticks between letting the cargo go and the cut; when the roll starts, how long and how hard the roll input is | 112, 12, 22, 33, 1.0 |
| `cargoCamDist`, `cargoCamY`, `cargoCamAlong`, `cargoPush`, `cargoRise`, `cargoAimOut`, `cargoAimY`, `cargoAimOutB`, `cargoAimFore`, `cargoAimDrop` | cargo camera: distance to port of the cargo bay, height relative to the deck, offset towards the bow; how far it moves in and up; where it aims at the start and how far the aim moves out, towards the bow and down | 30, -4, 3, 3, 1.5, 3, 0.5, 3, 1.5, -5.0 |
| `cargoSlowFrom`, `cargoSlowTo` | ticks filmed at 4 frames per tick (mild slow motion); not used | off |
| `rollFrames`, `preRoll`, `leverAt`, `doorAt`, `chestAt` | roll shot: length in frames; unfilmed ticks of roll input before the cut; ticks at which the lever is pulled and the door and the chest open | 366, 34, 6, 32, 40 |
| `rollAX/AY/AZ`, `rollBX/BY/BZ`, `rollLookX/Y` | roll camera in ship coordinates: from, to, and the point on the wall it looks at | -2.4/3.6/-1.9, -2.0/3.3/-0.6, 0.3/2.0 |
| `lampDelay`, `clockDelay` | the machine: delay of the repeaters between the lamps, and of the clock's repeater (redstone ticks) | 1, 4 |
| `farmTicks`, `farmClockAt`, `farmSpeed`, `farmTurn` | farm shot: length; tick at which the clock's lever is pulled; forward and yaw input | 102, 10, 0.14, 0.06 |
| `farmX/Y/Z`, `farmDX/DY/DZ`, `farmLookX/Y/Z` | farm camera in ship coordinates: from, movement, look-at | -4.0/3.7/-7.0, 0.4/-0.3/-0.2, 1.0/1.7/-7.0 |
| `farmDelayA`, `farmDelayB` | delays of the two repeaters in the farm clock (period = 2 x (1 + A + B) redstone ticks) | 4, 2 |
| `returnCut`, `returnGain`, `returnSpeed`, `returnBrake`, `holdTicks` | return: distance from home at which the shot cuts in; autopilot gain, speed limit and planned deceleration; ticks filmed after disassembly | 15, 1.8, 17, 6.5, 110 |

`stills` takes `times=t1+t2` for a time-of-day sweep (at `sweepAngle`, default the Reddit opening view). `probe`
takes `cx`, `cz`, `radius`.

### README stills

The stills in the delivery's `readme/` folder are raw frames (no captions) of the same showcase rendered in landscape
with a narrower field of view, so they show exactly what the video shows:

```
.\gradlew.bat runFilm -PslipwayFilm=reddit -PslipwayFilmSize=1600x900 "-PslipwayFilmOpts=only=hook+cargo+roll+farm,fov=55" --no-daemon --console=plain
python tools/film/readme_stills.py build/film/out/reddit-1600x900 <delivery folder>/readme
```

`readme_stills.py` picks frames by event and offset and saves them 1600 px wide as JPEG under 500 KB. Its defaults
are the delivered stills: `galleon-over-the-bay=hook+0` (the galleon at rest), `cargo-spill=cargo+240` (the cargo
in mid-air below the rolled ship), `machine-wall=roll+314` (lamps lit, pistons in mid-sequence, door and chest open
while the ship is upside down) and `deck-farm=farm+146` (the wheat just after the second dose, with the sparkle).
Other frames: `python tools/film/readme_stills.py <frames> <folder> "name=event+frames" ...`. The wheat's growth per
dose is random, so look at the farm still after every render.

## Shots

- `scout`: maps a seed's terrain from the noise (surface height and biome on a grid, no chunk generation) to
  `build/film/out/scout-<seed>@<x>@<z>.csv`; several seeds with `seed=a+b@x@z+c`, and `radius`, `step`.
  `python tools/film/scout_map.py <csv>` draws the map and ranks coast spots with ocean, cherry grove or meadow and
  peaks nearby. The film's location (seed `sunsetcoast`, around x -4620, z 5800) was picked this way.
- `probe`: looks at a place before a shot is designed there: writes the real surface (height and top block of every
  column, from generated chunks) around `cx`, `cz` to `survey.csv` and saves stills from above and from four sides.
  The cargo shot's headland was chosen from it.
- `stills`: the hero ship at rest from several angles (and optionally several times of day), with the shader history
  primed before each still.
- `reddit`: the showcase, below.
- `spike`: the original sub-tick capture test on a superflat world.

## The Reddit showcase, version 2 (`RedditShot`)

One continuous simulation filmed as five shots with cuts between them. Between shots the game keeps running unfilmed
and an autopilot flies the ship to the next shot's start. Version 1 (five other shots, also 16:9) is commit `2e1cc72`
of this branch.

| # | Shot | What happens | Frames per tick |
| --- | --- | --- | --- |
| 1 | hook | at rest over the bay; lifts off, rolls hard and yaws | 3 |
| 2 | cargo | eight loose pieces fall onto the deck and stack up; the ship rolls about 70 degrees to port; they slide off through the open rail and tumble onto the headland below | 3 |
| 3 | roll | level mode off, roll input held: the camera is fixed to the ship while it rolls through inverted; on the castle wall the lever starts the clock, the lamps light in sequence, the pistons pump, the door and the chest open | 3, ramping to 6 (half speed) |
| 4 | farm | the ship cruises slowly; the lever starts the farm's clock, the dispensers feed bone meal to the wheat, which grows to full height in two or three doses; four sheep stand behind the bed | 3 |
| 5 | return | the autopilot brings the ship back to the exact starting point, level, then `disassemble`; the camera settles on the opening frame | 3 |

The ship returns to within a few centimetres of where it was assembled, disassembly snaps it to the same blocks, and
the last shot ends on the first shot's camera, so the video loops. Slow motion is real: more frames rendered per game
tick (partials k/6 instead of k/3), and the shader clock follows film time, so clouds and water slow down too.

### What is real, and how each thing is triggered

Everything in the picture is the game and the mod running; the film only gives inputs a player could give.

- **The ship's motion** is helm input, set on the server each tick exactly as `/slipway control` sets it
  (`FilmPilot.apply`); hover and level are switched as `/slipway mode` switches them. The vessel is never teleported.
  The autopilot (`FilmPilot.autopilot`) is a function from the vessel's state to helm input.
- **The cargo** is eight vessels of their own (`FilmShips.cargo()`: 27 barrels around a hidden helm, a two-block keg,
  an oak crate, pumpkins, birch logs, hay, a beam, light-blue wool; 2 to 27 blocks, 83 in all, each with its own helm
  block, which looks like a wooden block with a wheel on one face). Unfilmed, just before the shot: their blocks are
  placed in the world in the air over the deck, 3 to 17 blocks up (`FilmScene.buildCargo`), and each is assembled
  through its helm with `VesselManager.assemble`, which is what using the helm does; they then hover where they were
  built. Twelve ticks before the cut each is let go with `/slipway mode <id> loose true` (Slipway 0.1.2: no hover, no
  stabilizer, no drag; gravity, collisions and friction only). From there nothing touches them: they fall, hit the
  deck and each other, and slide when the ship rolls. After the shot, unfilmed, each is deleted with
  `/slipway remove <id>`.
- **The spill**: the only input of the cargo shot is roll input (-1 for 33 ticks, eased in over half a second, with
  level mode off, so the ship stays rolled). No other axis gets input; hover mode holds the ship on its spot under the load. The port rail is
  open for six blocks at the cargo bay (a design decision: against the rail's 1.5-block collision height cargo stays
  aboard until the ship is rolled past 90 degrees).
- **The machine on the castle wall** (`FilmShips.machine`) is plain redstone, built unpowered: the lever on the wall
  powers its wall block; inside the castle dust leads to a comparator in subtract mode whose output runs through a
  repeater back into its own side (a clock that runs while the lever is on, period 20 game ticks); the output climbs
  a dust staircase to the top row of the wall, where five lamps alternate with four repeaters; behind the last lamp
  a repeater sends the signal down to three repeaters with delays of 1, 2 and 3 ticks, each powering the wall block
  behind a sticky piston that lifts an iron block. In the shot the lever is pulled once with `LeverBlock.pull` (what
  a player's click calls) at tick 6; lamps and pistons follow from the circuit alone. The door is opened with
  `DoorBlock.setOpen` (what a player's click calls) at tick 32. The chest gets its own open event at tick 40:
  `level.blockEvent(pos, chest, 1, 1)`, which is what a chest sends when a player opens it (no player is looking in,
  so the film sends the event itself; the lid then stays open until the close event). Piston strokes and the lid are
  animated on a vessel since Slipway 0.1.2, which passes block events on to the players who see the vessel. After the shot, unfilmed, the lever is pulled again, the door closed and the
  chest sent its close event.
- **The farm** (`FilmShips.farm`): four wheat plants, just planted, on moist farmland set into the deck around a
  waterlogged slab (water source blocks are not assembled; a waterlogged block is), four dispensers with bone meal
  that face the wheat, dust on top of the dispensers, and a second comparator clock (period 28 game ticks). In the
  shot the clock's lever is pulled with `LeverBlock.pull` at tick 10; each pulse makes the dispensers fire, vanilla's
  bone meal behaviour grows the wheat by two to five stages per dose, chosen at random (three doses in the shot, at
  ticks 15, 43 and 71; wheat has seven stages to grow, so it is ripe after the second or the third, and a dispenser
  facing ripe wheat keeps its bone meal). The green sparkle at the wheat and the smoke at the dispensers are the game's own effects, which Slipway
  0.1.2 shows at the vessel; they are placed in the world when they are made and do not follow the ship, which is
  why the ship only cruises at 3.4 blocks per second here. The wheat is not reset afterwards (it ends the
  video fully grown; from the opening camera it is hidden behind the hull). The world's random ticks are off (as in
  version 1, so that nothing in the landscape changes between the first and the last frame), which means crops do
  not grow by themselves in this world: all growth in the shot is bone meal from the dispensers. No game rule is
  raised, and no block state is set by the film.
- **The sheep** are ordinary mobs, spawned on the deck before the farm shot and removed after it (unfilmed).
- **The return**: autopilot to the rest position; when the vessel is within 0.06 blocks and still,
  `VesselManager.disassemble`, which is what `/slipway disassemble` and sneak-using the helm call. The change from
  the vessel to its blocks takes the client a few ticks: Slipway 0.1.2 keeps drawing the vessel until the terrain
  shows the placed blocks (2 to 6 ticks; 0.1.1 drew neither for a tick or two). Film time is held over those ticks
  (`Recorder.hold`, until the blocks are drawn and the vessel's picture is gone; 4 ticks in the delivered render), so
  the film goes from the last frame of the vessel to the first frame of the blocks alone. Nothing is hidden by it
  that a player would not see: at normal speed the ship simply stays in the picture.

The hero ship (`FilmShips.hero()`, 2,503 blocks, all assembled) is a three-masted galleon: curved dark-oak hull with
a birch stripe and a keel, spruce decks, forecastle, a two-storey stern castle with windows and a quarterdeck, wool
sails with red foot bands, yards, crow's nest, flags, bowsprit and jib, railings and lanterns; the machine wall, the
farm bed between the fore and main masts, and the open rail at the cargo bay. The helm faces south: a vessel's
forward is opposite the helm's facing.

### Places

| Shot | Vessel origin (the helm's corner), heading | Why |
| --- | --- | --- |
| hook, return | -4620, 80, 5800, north | the bay, mountains and cherry grove behind (version 1's spot) |
| cargo | -4537.7, 87, 5813.1, north-east | the deck is about 10 blocks over a grassy headland with stone ledges; the camera is 30 blocks to port over the water, below deck level, looking south-east at the snowy peaks; the cargo lands on the grass |
| roll | -4650, 100, 5770, north | open air over the bay |
| farm | -4665, 88, 5735, north, turning slowly east | the coast with the cherry grove and the peaks passes behind the starboard rail |

## Checks and encoding

- `tools/film/check_encode.py <dir>` checks `frames.csv` and the PNGs: within each shot, the per-frame step of the
  vessel, the camera position and the camera rotation must stay within a ratio of 0.5 to 2 of the previous step
  (ignoring changes smaller than 0.01 blocks, e.g. starting from rest). Cuts are declared by the `cut` column (1 on a
  shot's first frame) or `--cuts 120,480`; motion is not compared across them. No frame may be black, or identical to
  the previous frame while something moved (identical frames while nothing moves are intended holds). Writes
  `contact.jpg` (numbered thumbnails) and, without `--no-encode`, a plain `clip.mp4`.
- `tools/film/assemble.py <dir> --out <video.mp4>` builds the final video: shots joined by 6-frame crossfades,
  captions from an ASS file (`<video>.ass`, timed from `segments.json` events; Segoe UI Black, white with a dark
  outline and shadow, inside safe margins; the end card at the top), H.264 High, yuv420p, 60 fps, CRF 17, +faststart,
  no audio track, and `<video>-contact.jpg` (one frame per second, captions included). `--back-seconds` sets how long
  "Back to plain blocks" stays before the end card (2.4 s). It also assembles rehearsals with only some shots.
- `tools/film/deliver.py <dir> <delivery folder>` runs both, names the outputs for the delivery, picks the thumbnail
  (`--thumb <event>+<frames>`), verifies the encode with ffprobe and reports the loop point.

## How capture works, and the lesson behind it

Never use Fabric's `ctx.takeScreenshot` for film frames: in this Fabric API version it waits game ticks until the GPU
readback completes, so every screenshot advances the world by a tick and motion stutters.

Instead, `FilmRig.Recorder.tick(n)` waits exactly one game tick, then renders `n` frames of it itself, at partial
ticks k/n, with its own `DeltaTracker` (`FilmCapture`). Each frame is read back synchronously: it submits command
encoders until the fenced readback callback has run, and PNGs are written on background threads. Film time is
`ticks - 1 + partial`, the same instant vessels are drawn at (they interpolate from the previous tick's pose), so
cameras attached to the vessel's render pose never jitter against it.

Other pieces:

- `FilmCamera` + `mixin/FilmCameraMixin`: the camera path (position, yaw, pitch, roll) placed on every frame after
  vanilla aligned the camera; `Frame.basis` builds a rolled frame from a look direction and an up vector (for cameras
  fixed to a rolling ship); `ease`, `lerp`, `blend` for smooth paths.
- `mixin/FilmLoopMixin`: while recording, the game's own render loop draws nothing, so the shader's temporal history
  (TAA, auto exposure) only ever sees film frames in order. At a cut, the new view is rendered 40 times unsaved first
  to fill that history.
- `mixin/FilmIrisTimerMixin`: Iris's `frameTimeCounter` follows film time during film frames (clouds and water move
  smoothly and deterministically at any render speed).
- Before each frame the recorder re-renders until Sodium reports the terrain in view as built (no pop-in; `settle`
  in `frames.csv` counts those renders) and until no vessel has mesh sections left to rebuild (a lamp, a piston or a
  crop that changed this tick is in the frame it changed in). `Recorder.hold` keeps film time still while the world
  ticks (used after disassembly, until the terrain shows the placed blocks and the vessel is no longer drawn).
- `mixin/FilmHaltMixin`: the client GameTests' IntegratedServer.halt workaround; without it the game never exits.
- `FilmRig`: options (HUD, chat and vanilla clouds off, no view bobbing, render distance), the scenic world (normal
  world generation with a fixed seed: `setUseConsistentSettings(false)`, otherwise the GameTest builder makes a
  superflat seed-1 world), frozen time and weather, no mob spawning, no random ticks.
- `src/film/bliss.txt`: Bliss settings for the film (less haze, cloud speed, `entityShadowDistanceMul` 1.0 instead of
  0.25; vessels render as entities).

## Render stack

The film does not need the Tellus Distant Horizons fork.

| Component | Version | Source |
| --- | --- | --- |
| Minecraft / Fabric Loader | 26.3 / 0.19.5 | |
| Fabric API | 0.160.7+26.3 | `devmods/` |
| Sodium | 0.9.2+mc26.3 | `devmods/` |
| Iris | 1.11.7+mc26.3 (shadow-pass fixes) | `devmods/` (1.11.6 moved to `devmods/superseded/`) |
| Distant Horizons | 3.3.4-26.3, stock from Modrinth | `devmods/DistantHorizons-fabric-3.3.4-26.3.jar` (what `tools/fetch-devmods.py` downloads) |
| Shader pack | Bliss v2.1.2 (Chocapic13 Shaders edit), settings in `src/film/bliss.txt` | `shaderPackSource` in `build.gradle` |

Do not use DH 3.3.1-tellus-fork.6 (or its leakfix builds) for film. It has the GL blend-state bug that causes dark
blotches (fixed upstream in 3.3.3) and lacks upstream's 26.3 Iris entity fix (3.3.4). With it, test renders showed
blackened sails at some times of day, and the vessel's shadow on the water was unreliable.

`-PslipwayClientGametestMods=sodium,iris` renders without Distant Horizons (the default is `sodium,iris,dh`). The film
code does not call the Distant Horizons API; without DH, `dhWait` is simply an idle wait.

Stack check (2026-09-30): the stills shot was run twice with the default mods and once without DH (1920x1080, time
11700, plus a 9000/10000/11400 sweep). In the broadside view, the water under the ship was 0.77x as bright as the
water beside it in both default runs, and 0.81x in the no-DH run, so the ship casts a shadow consistently. Near-black
pixels at time 9000 were 0.02% or less, with no dark blotches. In the low-sun opening view the shadow falls outside
the frame; that is the sun angle, not a bug.

## Version 2 as delivered (2026-10-01)

Delivery folder `E:\slipway-e2e\film\reddit-v2\`. Rendered with `dev-0.1.2` at `7ece326` (Slipway 0.1.2; the commit
is in the folder's `mod-commit.txt`), merged into `film`. An earlier complete render with `00814fa` (before the
version number and the disassembly fix) was replaced by it; the two differ only in the random growth of the wheat.

- `slipway-4x5.mp4`: 1080x1350, 60 fps, 1,635 frames, 27.25 s, 40.9 MB, H.264 High, yuv420p, no audio track.
- Shots and events in the video's time (raw frame numbers are in `segments.json`; each of the four crossfades
  shortens the video by six frames):

  | Shot | Seconds | Events |
  | --- | --- | --- |
  | hook | 0.00 to 2.00 | helm input from 0.30 s, full at 0.70 s |
  | cargo | 2.00 to 7.50 | cargo let go 12 ticks (0.6 s) before the cut; roll input from 3.10 s |
  | roll | 7.50 to 13.50 | lever 7.90 s, first piston stroke 9.50 s, door 10.50 s, chest 11.30 s |
  | farm | 13.50 to 18.50 | clock lever 14.00 s; bone meal at 14.25 s, 15.65 s and 17.05 s (wheat stages 0000, 2535, 7777: ripe after the second dose) |
  | return | 18.50 to 27.25 | disassembled at 21.75 s, 4.7 cm from the starting point; end card from 24.15 s |

- Captions: "Every block stays a real block" 0.10 to 1.85 s, "Physics on top of physics" 2.35 to 7.25 s, "Full
  3-axis physics" 7.65 to 9.40 s, "Pistons. Chests. Redstone." 9.55 to 13.30 s, "Crops grow in flight" 13.80 to
  18.25 s, "Done flying?" 18.90 to 21.60 s, "Back to plain blocks" 21.80 to 24.15 s (2.35 s; version 1: 1.25 s),
  end card 24.15 to 27.25 s.
- `check_encode.py`: passed (1,659 frames, cuts declared at 126, 462, 828 and 1134, no black or repeated frame).
- Loop point: the last frame against the first differs by 4.85 of 255 on average; 7.1% of the pixels differ by more
  than 24. Ship and sky match. The water differs: the waves are in another phase, and there is a dark patch on the
  water under the hull in the last frame only. It appears in the first frame in which the ship is blocks again
  (version 1 has it too). Not measured, but most likely sky light: placed blocks lower the sky light of the columns
  below them and the shader darkens by it, while a vessel's blocks are stored elsewhere and change no light where
  the ship is. Starting the film from placed blocks instead would put the assembly into the first frames.
- Server load: 1.6 to 2.7 ms per tick, physics step 0.09 to 0.86 ms (nine vessels in the cargo shot: 1.9 ms and
  0.30 ms). The film held 4 ticks at the disassembly.
- Times: the render 6 min 33 s (319.5 s in the game, 222.8 s of it recording, 85 ms per frame); `deliver.py` 7 to
  9.5 min (the PNG checks and the encode); the stills render 5 min 7 s.
- README stills (`readme/`, 1600x900, 286 to 383 KB): `galleon-over-the-bay`, `cargo-spill`, `machine-wall`,
  `deck-farm`, from a second render of the same showcase (`only=hook+cargo+roll+farm,fov=55`) with the same commit.

## Decisions made for version 2

- **4:5 only.** The 16:9 cut was dropped; every camera is framed for 1080x1350.
- **The cargo spills through an opening in the rail, not over it.** A fence's collision is 1.5 blocks high; cargo on
  a deck rolled 60 degrees lies in the corner between deck and rail and stays aboard until the roll passes 90 degrees
  (worked out before building; a full roll at a height the masts clear would have needed a camera too far away to
  read the pieces on a phone). The port rail is therefore open for six blocks where the cargo is loaded.
- **Low camera for the cargo shot** (below deck level, 30 blocks to port): the ship stands against the peaks, the deck
  comes into view as it rolls towards the camera, and the landing place is in the lower third of the frame.
- **No slow motion in the cargo shot**: the fall and the spill fit into 5.5 s at normal speed and read well.
- **The farm bed is on the centreline between the masts**, fenced on the camera's side, with the dispensers behind
  the wheat and the sheep behind the dispensers, so one view shows wheat, dispensers, sheep, rail, sea and coast.
- **Three pistons, not four**: a powered block next to the door would open the door, so the piston next to it was
  left out; for the same reason the lever is two blocks from the door and no lamp is directly above it.
- **The machine starts in the picture** (the lever is pulled 0.4 s into the roll shot) so that cause and effect are
  seen; the second caption appears with the first piston stroke.
- **The wheat is not reset** before the return (see above); the lamps, pistons, door and chest are put back to their
  resting state by the same inputs that started them.
- **Random ticks stay off** for the whole film, as in version 1.
- **The hook's helm ramp is 9 ticks** (version 1: 6): with 0.1.2's hover hold the lift-off was a hair steeper and
  tripped the smoothness check's 2x step rule at the first moving frames.
- **The return cuts in 15 blocks from home**; the autopilot plans a 6.5 m/s^2 deceleration (a pure proportional
  approach either overshot the spot by 1.8 blocks at gain 1.8 or took more than 5 s at gain 1).

## Lessons

- With `NoDefaultCurrentDirectoryInExePath=1` in the environment, `cmd /c "gradlew.bat ..."` fails at once
  ("'gradlew.bat' is not recognized"); write `.\gradlew.bat` in the lock helper's command.
- A run can hang at start, before the film's first log line (`=== Slipway film: ...`): the render thread parks in
  `Minecraft.postRunTasks` (Fabric client GameTest hook) and the test thread in `ThreadingImpl.enterPhase`. It
  happened once in about twenty starts. If that line has not appeared 30 s after Iris's "Creating pipeline" lines,
  stop the film's java process by PID (its command line contains `Slipway-film`) and start again.
- Frame sizes must be even (libx264 with yuv420p): rehearse at 544x680, not 540x675.
- "Can't keep up!" lines in a render's log are the film itself: the tick loop waits while each tick's frames are
  rendered (up to half a second per tick in slow motion), so the server reports being seconds behind after every
  shot. No tick is skipped. The server's own work is logged at the end of each shot ("server N ms per tick, physics
  step N ms"); in the delivered render it was 1.6 to 2.7 ms per tick with a physics step of 0.09 to 0.86 ms (1.9 ms
  and 0.30 ms in the cargo shot, with nine vessels).
- Build cargo only where the ship is not: `FilmScene.buildCargo` refuses a piece within a block of any ship block
  (the first layout put a piece into the main mast, which rises through every height at ship x 0, z -2).
- A lever's block, a lamp or a powered wall block next to a door opens it; keep powered blocks two away.
- Vessel blocks that change (lamps, pistons, crops) need their mesh rebuilt before the frame is saved; the recorder
  now waits for it.

## Process hygiene

If a run hangs, stop only the java process whose command line contains `Slipway-film`, by PID.
