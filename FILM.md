# Film tool

Renders promotional footage of Slipway frame by frame inside the real game (Sodium, Iris with Bliss, Distant
Horizons), driven by the Fabric client GameTest API. Nothing here ships in the mod: the `film` source set and the
`runFilm` Gradle run are development only. The window never takes focus (`FilmPreLaunch` sets SDL hints), so the PC
stays usable while it renders.

## Quick start: the Reddit showcase

```
gradlew runFilm -PslipwayFilm=reddit -PslipwayFilmSize=1920x1080 "-PslipwayFilmOpts=rd=24"
gradlew runFilm -PslipwayFilm=reddit -PslipwayFilmSize=1080x1350 "-PslipwayFilmOpts=rd=24"
python tools/film/check_encode.py build/film/out/reddit-1920x1080 --no-encode
python tools/film/check_encode.py build/film/out/reddit-1080x1350 --no-encode
python tools/film/assemble.py build/film/out/reddit-1920x1080 --out slipway-16x9.mp4
python tools/film/assemble.py build/film/out/reddit-1080x1350 --out slipway-4x5.mp4
```

Each render is a separate run at its native size (framing is widened for the portrait cut, not cropped). A run takes
about 6 to 9 minutes including game start, world generation and a 60 s wait for Distant Horizons.

## Gradle properties

| Property | Meaning | Default |
| --- | --- | --- |
| `-PslipwayFilm=<shot>` | the shot: `reddit`, `stills`, `scout`, `spike` | `spike` |
| `-PslipwayFilmSize=<w>x<h>` | window and frame size | `1080x1350` |
| `-PslipwayFilmSubframes=<n>` | frames per game tick where a shot does not choose (3 = 60 fps) | `3` |
| `-PslipwayFilmOpts=k=v,k=v` | shot options (below) | none |

Frames go to `build/film/out/<shot>-<w>x<h>/f000000.png ...` with `frames.csv` (one row per frame: tick, partial
tick, frames per tick, `cut`, `segment`, camera, vessel centre, tilt, heading, settle renders, milliseconds) and, for
`reddit`, `segments.json` (the frame of each shot and event).

Common options: `seed` (world seed, default `sunsetcoast`), `daytime` (default 11700, late afternoon; the sun is low, so the ship's shadow falls far from it, on the water only in low shots), `rd` (render
distance in chunks), `dhWait` (seconds to let Distant Horizons build far terrain before recording, default 60).
`reddit` also takes timing options (`hookTicks`, `flybyTicks`, `rollFrames`, `preRoll`, `deckTicks`, `bank`,
`returnGain`, `returnSpeed`, `returnRunUp`, `holdTicks`, `shotSettle`: unfilmed ticks at each shot's first camera so Distant Horizons can update, default 100); `stills` takes `times=t1+t2` for a time-of-day sweep (at `sweepAngle`, default the Reddit opening view).

## Shots

- `scout`: maps a seed's terrain from the noise (surface height and biome on a grid, no chunk generation) to
  `build/film/out/scout-<seed>@<x>@<z>.csv`; several seeds with `seed=a+b@x@z+c`, and `radius`, `step`.
  `python tools/film/scout_map.py <csv>` draws the map and ranks coast spots with ocean, cherry grove or meadow and
  peaks nearby. The film's location (seed `sunsetcoast`, around x -4620, z 5800) was picked this way.
- `stills`: the hero ship at rest from six angles (and optionally several times of day), with the shader history
  primed before each still.
- `reddit`: the showcase, below.
- `spike`: the original sub-tick capture test on a superflat world.

## The Reddit showcase (`RedditShot`)

One continuous simulation filmed as five shots with cuts between them. Between shots the game keeps running unfilmed
and an autopilot flies the ship to the next shot's start. All vessel motion is helm input, set on the server each tick
exactly as `/slipway control` sets it (`FilmPilot`); the vessel is never teleported.

| Shot | What happens | Frames per tick |
| --- | --- | --- |
| hook | at rest over the bay; lifts off, rolls hard and yaws | 3 |
| flyby | full speed along the coast in front of the mountains, banking | 3 |
| roll | level mode off, roll input held: the camera is fixed to the ship while it rolls through inverted; the door opens and the lever lights the lamp | 3, ramping to 6 (half speed) |
| deck | four sheep on the main deck while the ship banks one way and the other | 3 |
| return | autopilot back to the exact starting point, level, then `disassemble`; the camera settles on the opening frame | 3 |

The ship returns to within a few centimetres of where it was assembled, disassembly snaps it to the same blocks, and
the last shot ends on the first shot's camera, so the video loops. Slow motion is real: more frames rendered per game
tick (partials k/6 instead of k/3), and the shader clock follows film time, so clouds and water slow down too.

The hero ship (`FilmShips.hero()`, 2,430 blocks, all assembled) is a three-masted galleon: curved dark-oak hull with a birch stripe
and a keel, spruce decks, forecastle, a two-storey stern castle with windows and a quarterdeck, wool sails with red
foot bands, yards, crow's nest, flags, bowsprit and jib, railings and lanterns; on the castle's front wall a spruce
door, a chest (with a map, compass, emeralds and bread) and a redstone lamp with a lever. The helm faces south: a
vessel's forward is opposite the helm's facing.

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
  no audio track, and `<video>-contact.jpg` (one frame per second, captions included).

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
- Before each frame the recorder re-renders until Sodium reports the terrain in view as built (no pop-in); `settle`
  in `frames.csv` counts those renders. `Recorder.hold` keeps film time still while the world ticks (used after
  disassembly, when the client drops the vessel a tick or two before the placed blocks arrive).
- `mixin/FilmHaltMixin`: the client GameTests' IntegratedServer.halt workaround; without it the game never exits.
- `FilmRig`: options (HUD, chat and vanilla clouds off, no view bobbing, render distance), the scenic world (normal
  world generation with a fixed seed: `setUseConsistentSettings(false)`, otherwise the GameTest builder makes a
  superflat seed-1 world), frozen time and weather, no mob spawning, no random ticks.
- `src/film/bliss.txt`: Bliss settings for the film (less haze, cloud speed, `entityShadowDistanceMul` 1.0 instead of
  0.25; vessels render as entities).

## Render stack

The film does not need the Tellus Distant Horizons fork. The reddit-v1 videos and stills were rendered with:

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

## Process hygiene

If a run hangs, stop only the java process whose command line contains `Slipway-film`, by PID.
