# Slipway design notes

Slipway is a fully open (Apache-2.0) Minecraft 26.3 mod for moving block structures ("vessels"): build from any
blocks, place a Helm, assemble, and fly with full three-axis rotation while the blocks stay real blocks.
This file records the architecture as built, every deviation from the Bridge design doc `slipway/design`, and the
source of every non-trivial algorithm. It is kept current with the code.

## Fixed decisions

| Decision | Choice |
| --- | --- |
| License | Apache-2.0 (LICENSE, NOTICE credits jolt-jni, Jolt Physics, V-HACD) |
| Minecraft / loader | 26.3, Fabric loader 0.19.5, Fabric API 0.160.7+26.3, Java 25. NeoForge later. |
| Integrations | Sodium 0.9.2+mc26.3, Iris 1.11.6+mc26.3, Distant Horizons 3.3.4-tellus-fork.7 (3.3.1-tellus-fork.6 until 2026-09-30; official 3.3.4 is checked too) |
| Physics | Jolt Physics through jolt-jni 6.1.1 (MIT), double-precision flavour, behind `dev.timstewart.slipway.physics.PhysicsEngine` |
| Saves | Pre-1.0: no migrations, no backward-compatibility code |

## Clean-room rules

Code is written from vanilla 26.3 sources (`E:\mc-ref\26.3`), Fabric API, Sodium, Iris and Distant Horizons
sources, Jolt/jolt-jni sources and docs, published papers and our own design. No code from Sable, Create
Aeronautics/Simulated or vs-core/Krunch was read or used; vs-core was not decompiled. Valkyrien Skies 2 was not read
while implementing.

## M0 — step-0 verification (2026-09-29)

**jolt-jni natives.** Maven Central publishes the JVM library (`com.github.stephengold:jolt-jni-Windows64:6.1.1`,
platform independent, the same classes are published under several artifact ids) and one native jar per platform,
build type and precision (`jolt-jni-<Platform>:6.1.1:<Debug|Release><Sp|Dp>`). ReleaseDp natives exist for Windows
x86_64 and aarch64, Linux x86_64 and aarch64, and macOS x86_64 and aarch64 (also Linux ARM32hf and Android, not
bundled). The build copies the six ReleaseDp libraries into the mod jar under `slipway-natives/<os>-<arch>/` with a
SHA-256 manifest (`natives.properties`); the JVM library is nested with Loom `include`. The mod jar is 9.5 MB.

At start-up `JoltRuntime` extracts the library for the running platform into
`<gameDir>/.slipway/natives/<platform>-<hash>/`, verifies its hash, and loads it through jolt-jni's own
`NativeLibraryLoader.loadLibrary(path)`. Calling `System.load` from jolt-jni's class binds the library to the class
loader that defines the native methods, which is correct both under Knot (production) and on a plain classpath
(unit tests). Java 25 prints a "restricted method" warning for `System.load` unless the JVM gets
`--enable-native-access=ALL-UNNAMED`; the dev runs and the test instances pass that flag, and without it the load
still succeeds.

Evidence: `JoltNativesTest` (JUnit, Java 25) loads the library and runs `JoltSelfTest`; `gradlew runServer`
(a real Fabric 26.3 dedicated server in the dev environment) logs
`jolt-jni 6.1.1 (Jolt Physics) loaded: windows-x86_64 Release build, double precision` and
`Slipway self-test: Jolt double-precision body came to rest at y=64.5000 (expected 64.5) after 120 steps in 21 ms: PASS`.
The self-test drops a compound body at x=27,000,000.5, z=-27,000,000.5, where single precision would be off by
metres; double precision is why Slipway ships the Dp flavour.

**Render paths confirmed (read from the 26.3 sources):**

- Vanilla 26.3 draws entities through the submit/feature pipeline: `EntityRenderer.submit(state, poseStack,
  SubmitNodeCollector, CameraRenderState)`. `SubmitNodeCollector.submitCustomGeometry(poseStack, renderType,
  renderer)` writes arbitrary vertices, and `RenderTypes.solidMovingBlock()/cutoutMovingBlock()/translucentMovingBlock()`
  (used for falling blocks and pistons) draw block quads with the terrain pipelines
  `RenderPipelines.SOLID_BLOCK/CUTOUT_BLOCK/TRANSLUCENT_BLOCK`.
- Iris 1.11.6 (branch `26.3`): `IrisPipelines` maps those pipelines to `TERRAIN_SOLID`, `TERRAIN_CUTOUT` and
  `MOVING_BLOCK` in the main pass and to `SHADOW_TERRAIN_CUTOUT`/`SHADOW_TRANSLUCENT` in the shadow pass.
  `ShadowRenderer.extractVisibleEntities` + `renderEntities` re-submit every visible entity into the shadow pass,
  so geometry submitted by an entity renderer is shaded as terrain and casts shadows with no Iris patch. Entities
  are kept when `levelRenderer.isSectionCompiledAndVisible(entity.blockPosition())`, the same test vanilla uses.
  Only the vessel's blocks and block entities go into the shadow pass: the hovered-block outline and breaking
  overlay are skipped while `IrisApi.isRenderingShadowPass()` (optional, by reflection). Iris has no shadow program
  for `lines_translucent` and logs "Missing program minecraft:pipeline/lines_translucent in override list" once an
  outline reaches its shadow pass (the soak setup reproduced it on the first release candidate).
- Sodium 0.9.2 (tag `mc26.3-0.9.2`): terrain is meshed and drawn by `RenderSectionManager` for chunks in the client
  chunk cache; entity culling goes through `SodiumWorldRenderer.isEntityVisible` (large boxes are only frustum
  tested) and `isSectionReady` backs `isSectionCompiledAndVisible`. Sodium does not touch the entity feature
  renderers, so custom geometry passes through unchanged.
- Distant Horizons 3.3.1 (fork.6): the public generic-rendering API (`DhApi.Delayed.customRenderObjectFactory`,
  `IDhApiRenderableBoxGroup`, `IDhApiLevelWrapper.getRenderRegister()`) renders groups of axis-aligned boxes at LOD
  distances; `setOriginBlockPos` and `setPreRenderFunc` move a group every frame and `triggerBoxChange()` re-uploads
  changed boxes. No fork change is needed.

## Deviation: vessel geometry is drawn through the entity pipeline, not Sodium's chunk renderer

The design doc and M4 call for vessel chunks "drawn transformed through Sodium". Drawing transformed sections inside
Sodium would need hooks into `RenderSectionManager` (section graph, region layout), `DefaultChunkRenderer`
(per-region uniforms) and Iris's shadow render list, all internal APIs that change between Sodium releases. Instead
each vessel is a `VesselEntity` whose renderer meshes the vessel's real chunks (with vanilla's
`ModelBlockRenderer`, so models, tints, ambient occlusion and light are the vanilla ones) into a cached vertex
buffer and submits it every frame with the vessel's interpolated transform through `submitCustomGeometry` with the
moving-block render types. With Sodium installed Sodium still draws all terrain; Iris shades the vessel with its
terrain programs and renders it into the shadow map. This needs zero Sodium or Iris patches, which is what the
go/no-go criterion on patch count asks for. The cost is a per-frame vertex copy (measured in M7) and that shader
packs see the vessel as terrain drawn during the entity phase.

## Architecture

### Vessels live in real chunks ("plots")

A vessel's blocks are ordinary blocks in ordinary chunks of the same level, in a reserved region far from play
(`VesselRegion`: x and z from 24,000,000, 1,024 x 1,024 plots of 4,096 x 4,096 blocks, a 32-block margin inside each).
Chunks there generate empty with the void biome (`ChunkStatusTasksMixin`). Because the blocks stay real, every block
behaviour works unchanged: block entities, redstone, containers, doors, fluids, scheduled ticks, block-entity
renderers, loot. What makes them a vessel is a pose (position + unit quaternion, `VesselPose`) that maps plot space
into the world, and code that moves entities, clicks, sounds, drops and rendering through that transform.

- `VesselRecord` (saved in the level's `VesselRegistry` SavedData): id, plot, anchor, local bounds, helm, pose,
  linear and angular velocity, hover/level modes, block count and the long-range proxy data. The plot chunks are
  saved by vanilla like any other chunk.
- `VesselEntity` sits at the vessel's centre in world chunks. It is what players track (it decides who receives the
  vessel's chunks and poses), what the pilot rides, and what draws the vessel. When its chunk loads, the vessel
  becomes active (`ActiveVessel`); when it unloads, the vessel stops and keeps its state.
- `VesselManager` (one per server level) owns active vessels: assembly, disassembly, removal, chunk tickets for plots,
  which players view which vessel (`syncViewers`), pose and info packets, and a next-tick queue for work that must
  follow block updates.

### Assembly and disassembly

`StructureScan` is a bounded flood fill over face-connected non-air blocks from the helm (cap `maxVesselBlocks`,
default 4,096; span cap 512; unloaded chunks fail the scan). `AssemblyRules` leaves out blocks in the
`slipway:assembly_deny` tag (bedrock, barrier, light, structure void, command/structure/jigsaw/test blocks, moving
pistons, nether and end portals, end portal frames, end gateways, reinforced deepslate), the config's
`extraDeniedBlocks`, other helms, and fluid blocks (a ship built on the sea does not take the sea with it; waterlogged
blocks keep their waterlogged state, so fluids aboard are supported that way). `VesselAssembly` copies block states
and block-entity data into the plot, erases the originals without drops, and creates the record and entity.
Disassembly requires the vessel to be within `disassemblyTiltDegrees` (default 20 degrees) of level, snaps the
heading to the nearest quarter turn, rotates every block state with `BlockState.rotate`, refuses (with a message)
when a target is occupied, outside the world or unloaded, and moves entities standing on the deck onto the placed
blocks. Each relocation is pushed out of the placed blocks with the same strict depenetration the decks use: float
rounding in the pose can otherwise put a rider a few microns inside the deck, and vanilla collision ignores a floor
that a box already overlaps by more than 1e-7, so the player would fall through the new blocks (seen in the release
regression run; `InteractionGameTests#entitiesAboardMoveWithTheSnappedBlocks` models it).

### Physics

`PhysicsEngine` is the narrow interface; `JoltEngine` implements it with jolt-jni (double precision). A
`PhysicsWorld` runs the engine on its own thread. At the start of every server tick `VesselPhysicsBridge.exchange`
waits for the step started last tick, publishes its poses to the records, queues commands (shape changes, terrain,
teleports) and starts the next step (0.05 s, 3 collision steps) with the helm forces computed from the latest state.
The server thread never touches a native handle; the physics thread owns them all. Step indices mark results
computed before a teleport or body creation so stale poses are dropped.

- Collision shapes: `SectionShapes` turns each 16³ section's block collision shapes into boxes, merged by
  `GreedyBoxes` per density class (`BlockDensity`: wood 700, stone 2,400, metal 7,800, glass 2,500, earth 1,600,
  light blocks such as wool and leaves 200, anything else 1,000 kg/m³), and `JoltEngine` builds a static compound of
  box shapes. Any block change in a plot marks the vessel's
  shape dirty (`LevelChunkMixin`) and the next exchange rebuilds it.
- Terrain: static bodies for world sections near each vessel (bounded to 4,096 sections per vessel, built a few per
  tick, released after a grace period, rebuilt when a block in them changes).
- Mass and inertia: `BoxList.massProperties` (solid boxes plus the parallel-axis theorem) feeds the controller; Jolt
  computes the same from the shapes.
- Control (`VesselController`): forces scale with mass and torques with the world inertia tensor, so a raft and a
  1,000-block barge accelerate and turn alike. Hover cancels gravity, brakes idle axes and holds the position the
  brake ends at; level adds a rate that rotates the up axis back to world up. All inputs, forces and states are
  checked for NaN/Infinity; a non-finite physics state restores the last good pose. A loose vessel is not controlled
  (below).
- Leaks: every jolt-jni object is closed by its owner (`JoltEngine.release` for shape references, bodies destroyed on
  removal, the engine closed on level unload). `JoltEngineTest.anEngineLifecycleFreesEveryNativeObject` runs a full
  lifecycle with the Debug natives' allocation counters and requires news == deletes.

### Loose vessels, holding and sleeping (0.1.2)

**Loose.** `VesselRecord.loose` (saved as an optional field, so 0.1.0 and 0.1.1 saves load as not loose; bit 3 of the
pose packet's flags) makes a vessel a plain rigid body: `VesselController.drive` applies no force and no torque to it,
whatever the helm, hover and level say, so only gravity, contacts and friction move it. Hover and level keep their
values and apply again when loose ends. It is switched by `/slipway mode <id> loose true|false` and by the pilot's
"Toggle loose" key. The key's default is U: G, the neighbour of H, is vanilla 26.3's quick-actions key, and Iris
takes K, O and R.

**Holding.** Until 0.1.1 a hovering vessel only had its weight cancelled and its speed braked, and with level off only
its turn rate braked. That is no hold: anything resting on it pushed it down for as long as it lay there (10 crates on
a 2,000-block carrier sank it by a quarter of a block per second), and cargo lying off centre turned a vessel with
level off until the cargo slid away. So hover now holds (`VesselController.Hold`, one per vessel, used only on the
physics thread):

- Position: the point the brake would stop the vessel at. Along a local axis with input the point follows the
  vessel; along an idle axis the vessel is pulled to it by a critically damped spring `a = -2wv - w²e` with
  `w = BRAKE_GAIN` (1.5/s). Started from `e = -v/w` this is exactly the old brake `a = -wv`, so an unloaded vessel
  flies and stops as before (unit test `aReleasedHoveringVesselStopsWhereThePlainBrakeStopsIt`). Under a load of a
  fraction `f` of its weight it sags `f g / w²` (4.4 blocks per unit of `f`: 0.10 blocks for the 2.3% in the client
  GameTest, measured 0.103). The pull is limited to `HOLD_SLACK` = 2 blocks of spring (a load of 46% of the vessel's
  weight); beyond that the point gives way and the vessel sinks slowly instead of winding up. A vessel pushed aside by
  another comes back by at most those 2 blocks.
- Attitude, only with level off: the same with `k = RATE_GAIN` (3/s) about each local axis, `alpha = -2kw - k²e`,
  limited to 20 degrees. With level on, levelling is the hold for pitch and roll, and gives by `asin(torque / (4.5 I))`
  (three degrees for a two-block crate 2.2 blocks off the centre of a 7x7 raft).
- The hold is taken anew after a teleport, a restored pose, hover off and on, level on and off, and loose.

**Jolt settings checked for small bodies on a deck** (unit tests in `LooseCargoTest`, on the Debug natives; server
GameTests in `LooseGameTests`; client GameTest `loose-cargo`):

| Setting | Value | Finding |
| --- | --- | --- |
| Step | 0.05 s in 3 collision steps (60 Hz), 10 velocity and 2 position iterations (Jolt's defaults) | Crates dropped from 3 to 8 blocks land without bouncing through or being thrown; a pile of 8 to 10 comes to rest within 70 ticks. |
| Convex radius | 0.05 (less for thin boxes) | Kept. Resting bodies overlap by Jolt's penetration slop of 0.02 blocks and no more; nothing sinks in over time. |
| Friction | 0.6 on vessels, 0.8 on terrain, combined by geometric mean | Kept. Cargo holds on a deck up to atan(0.6) = 31 degrees and slides beyond (it starts between 28 and 36 in the unit test). It also means the deck can pass on at most 0.6 g = 5.9 m/s²: the stop of a released helm brakes at 1.5 times the speed per second, so from more than 4 m/s cargo slides forward, and tall thin pieces topple. Fly gently or build a rail. |
| Restitution | 0.05 (Jolt takes the larger of two) | Kept: nothing bounces. |
| Motion quality | LinearCast for every vessel | Kept: a crate at terminal speed does not pass through a one-block deck. |
| Sleeping | off for controlled vessels, on for loose ones | A controlled vessel gets forces every step and can never sleep. A loose vessel at rest on terrain or on other sleeping vessels is put to sleep after 0.5 s and then costs nothing (0.004 ms per step for 30 of them, against 0.5 ms awake, Debug natives). What rests on a hovering carrier is in the carrier's island and stays awake, as it must to ride along. Jolt wakes a sleeper that is touched, but not one whose support goes away: `JoltEngine` wakes loose vessels around a terrain section that changes and all of them when a vessel is removed, reshaped or teleported. |
| Enhanced internal edge removal | on for every vessel | A deck or the ground is many boxes side by side; a body sliding fast over the buried edges between them caught on them (a cube at 8 m/s over single-block boxes tumbled within four blocks). With the option, vessels slide over terrain seams as over one slab (`vesselsSlideOverTerrainSeamsAsOverOneSlab`). It only removes the edges of the second body of a pair. Terrain is always second; of two vessels the one with the higher Jolt body id is, which jolt-jni 6.1.1 gives no way to choose (no `CreateBodyWithID`). So a crate sliding fast over a seam of a deck that is older than the crate still tumbles there. Slow sliding and resting are not affected. Left as is. |

Cost, client GameTest `loose-cargo` (Release natives; 10 loose vessels of 2 to 30 blocks, 110 blocks together, on a
2,080-block carrier of 4,990 t; 11 plots loaded): physics step 0.24 ms mean while they land (worst 2.2 ms), 0.20 ms
at rest on the deck, 0.18 ms while the carrier flies, 0.16 ms while they slide off and fall, 0.08 ms once all ten
sleep on the ground; Slipway's part of the server tick (the exchange) 0.1 ms or less; server tick 0.8 to 2.0 ms mean,
4.5 ms worst. The client's poses are the server's (offset 0.0 blocks over 748 compared poses).

### Networking

Server-authoritative. Viewers get `VesselInfo` (plot mapping, bounds, helm; flagged on assembly), plot chunks as
normal chunk packets (`ChunkMapMixin` keeps block, light and block-entity updates flowing to them although plots are
far outside their view; the client stores them in a separate map, `ClientChunkCacheMixin`), a `PoseUpdate` every
tick, and `VesselGone` (with `keepProxy` when only the near view ends). The only serverbound packet is `HelmControl`,
accepted only from the vessel's pilot, rate-limited (40 per second), with NaN/Infinity rejected and axes clamped to
[-1, 1] (`ServerPackets`). Vanilla use/break packets aimed at plot positions are checked for reach against where
the block is in the world (`PlayerMixin`).

### Client

`ClientVessel` plays poses back two ticks late through an adaptive jitter buffer (rate within ±10%, jumps only when
more than 10 ticks off) and interpolates between client ticks like entities. `VesselMesh` meshes the plot's sections
with vanilla's `ModelBlockRenderer` and fluid renderer (AO, tints, light from the plot) into cached buffers, rebuilt
per section on change; `VesselRenderer` submits them every frame with the interpolated transform through
`submitCustomGeometry` using the moving-block render types, and renders block entities with their own renderers.
`HelmHud` shows speed, altitude, attitude and modes; `HelmControls` sends input while piloting.

### Interaction and riding

`VesselPicking` moves the crosshair ray into plot space and clips it against the real blocks; the hit stays in plot
coordinates, which is what vanilla's interaction packets carry, so every block's own `use`/`attack` logic runs
unchanged. While a client tick is in progress (vanilla picks there, before entities move) it uses the previous tick's
pose so the ray and the not-yet-carried player agree. `UseOnContextMixin`/`BlockPlaceContextMixin` rotate the
player's facing into the vessel frame so placed blocks orient relative to the vessel. `ServerLevelMixin` moves drops,
sounds and level events from plot positions to where they are in the world. `VesselCollisions` (via `EntityMixin`)
collides entities with vessel blocks in the vessel's frame, carries them with the vessel, lets them walk on decks
tilted up to 50 degrees and slide off steeper ones.

### Distant Horizons

Optional (`DhProxyBridge` only touches DH classes when it is loaded). The server keeps, per vessel, its exposed
blocks with map colours (`VesselProxies`) and sends them to players within `proxyRange`, plus pose updates whenever
the vessel moved or turned since the last one that player got. `DhProxies` registers one DH box group per vessel
through DH's public generic-rendering API, moves it every frame, re-lays it out after more than a degree of rotation,
and turns it off where the real vessel is drawn. After assembly and disassembly the client asks DH to rebuild the
LODs of the world chunks involved (`overwriteChunkDataAsync`): on a client connected to a server DH only rebuilds a
chunk when it loads or the local player edits it, so otherwise a ghost of the ship stayed at its build site (and hid
the real ship until it moved). No DH fork change was needed.

## Algorithms and their sources

| What | Where | Source |
| --- | --- | --- |
| Greedy box merging of voxels | `GreedyBoxes` | Greedy meshing (M. Lysenko, "Meshing in a Minecraft game", 2012), applied to volumes |
| Box inertia, parallel-axis theorem, world inertia R I Rᵀ | `BoxList`, `VesselController` | Textbook rigid-body mechanics |
| Rate controller, τ = I·α | `VesselController` | Proportional control on angular velocity (textbook) |
| Collide and slide | `VesselCollisions` | K. Fauerby, "Improved Collision detection and Response", 2003 |
| Enclosing box of a rotated box | `VesselCollisions` | Standard OBB-to-AABB bound (C. Ericson, *Real-Time Collision Detection*, 2005, 4.2.6) |
| Minimum-translation depenetration | `VesselCollisions.depenetration` | Separating-axis minimum translation (Ericson 2005, ch. 5) |
| Swing-twist decomposition (rider yaw) | `VesselPose.yawTurnSinceDegrees` | Standard quaternion swing-twist decomposition |
| Quaternion slerp | `VesselPose.interpolate` (JOML) | K. Shoemake, "Animating rotation with quaternion curves", 1985 |
| Adaptive playout of poses | `ClientVessel.nextPlaybackTick` | Jitter-buffer playout adaptation (Ramjee et al., 1994), simplified to a rate-limited clock |

## Other deviations and decisions made while building

- **Second player in the e2e harness is an offline dev client.** Prism 8.3 can only launch an instance with a
  Microsoft account; the second test account's session had expired and must be renewed by hand. The e2e server runs
  in offline mode, so `Start-SlipwayE2EWatcher` launches a Loom dev client (`e2eWatcher` run, vanilla renderer) as
  player `SlipwayWatcher`. It is a real second connection; it also covers the no-Sodium render path. The dev client
  needs `-XX:StackShadowPages=32`, which Mojang's 26.3 metadata adds for launchers; without it the JVM can crash.
  (Retired with the Prism multiplayer scenario; the client GameTest's second client is `WatcherProcess`, a second
  process of the test game itself.)
- **e2e screenshots wait for the terrain renderer.** A freshly started client (Sodium plus Distant Horizons) needed 6
  to 12 s before blocks placed next to it were drawn, so early frames showed "invisible" ships although the client
  had the blocks. This happens with plain `setblock` and no vessel involved. `Enter-SlipwayE2EArea` and
  `Save-SlipwayE2EScreenshot` now wait until `LevelRenderer.hasRenderedAllSections()` (Sodium's build queue) has
  stayed true for 1 to 1.5 s, with a bounded timeout.
- **Pilot's view does not roll.** The pilot rides eye-anchored at the helm and turns with the vessel's heading, but
  vanilla cameras cannot roll; when the vessel rolls or loops, the pilot's view stays upright relative to the world
  while the vessel rotates around them.
- **Default keys** avoid vanilla 26.3 defaults (Z descend, arrows pitch/roll, N/M strafe, H hover, B level). The
  play instance for the author rebinds three of them to fit that player's layout.

## M7 — measurements (2026-09-29, this machine)

Measured with the Prism harness, which has since been retired (`tools/e2e/legacy`); the client GameTests' perf, leak
and soak measurements are under "Testing".

**Performance** (`tools/e2e/legacy/scenarios/perf.ps1`, release jar, run `perf-20260929-191053`): Iris + Bliss, Sodium,
Distant Horizons, render distance 10, 1280x720 window, frame rate uncapped, a 961-block barge (20x3x16 concrete plus
helm) seen from 30 blocks, compared with a warm baseline of the same view without it:

| Phase | Client fps (avg / 5th pct) | Server tick (avg / worst) | Slipway exchange (main thread) | Physics step (own thread) |
| --- | --- | --- | --- | --- |
| Baseline, no vessel (before) | 470.8 / 434 | 2.64 / 2.90 ms | 0.001 ms | 0.051 ms |
| Vessel hovering in full view | 381.9 / 250 | 2.91 / 3.02 ms | 0.036 ms | 0.062 ms |
| Vessel flying a circle in view | 425.0 / 345 | 2.94 / 3.11 ms | 0.036 ms | 0.062 ms |
| Baseline again (vessel removed) | 482.3 / 405 | 2.67 / 2.85 ms | 0.003 ms | 0.059 ms |

Against the second baseline, the flying 961-block vessel cost 11.9% of frame rate (about 0.28 ms per frame; about
21% while it hovered filling the view) and +0.27 ms of server tick time, of which Slipway's main-thread work is under
0.05 ms; the rest (entity tracking, packets) is vanilla's. Across the four perf runs on this machine
(`perf-20260929-155228`, `-174348`, `-181150`, `-191053`) the flying cost ranged from 8% to 15% of frame rate and
+0.2 to +0.4 ms of server tick; the server stays near 3 ms of its 50 ms budget. The frame cost is the per-frame
vertex copy of the cached mesh plus Iris's shadow pass drawing it a second time.

**Native handles and leaks** (`tools/e2e/legacy/scenarios/leak.ps1`, release jar, run `leak-20260929-191726`): one client process opened and
closed a singleplayer world with three flying vessels five times. With the world open: 1 Jolt engine, 3 bodies;
after each close: 0 engines, 0 bodies, 0 Slipway level managers, 0 client vessels. Heap after GC grew by about 120 MB
per cycle: whole worlds stayed in memory. The attribution recorded here at the time (one Distant Horizons map) was
not proven and was incomplete; the root-cause analysis below replaces it. The JUnit leak test
(`JoltEngineTest.anEngineLifecycleFreesEveryNativeObject`, Debug natives) proves jolt-jni allocations and frees
balance exactly over full engine lifecycles.

**Soak** (`tools/e2e/legacy/scenarios/soak.ps1`, release jar, run `soak-20260929-192036`): a pilot flew the mixed ship (Iris + Bliss on) for
20.7 minutes through a repeating one-minute program (fast runs, turns, climbs and dives, a full roll through
inverted, strafing, hover-off drops, return legs) with an altitude hold between rounds. 42 samples, every one finite,
the pilot at the helm throughout, no block lost, the ship intact at the end; server tick 2.72 ms on average, worst
sample 6.43 ms; physics step at most 0.091 ms; client 65 fps average with the frame rate capped at 120; no Slipway
error or warning in either log. An earlier soak on the first release candidate (`soak-20260929-182233`) flew just as
cleanly but failed on the Iris outline error described under M0, which the final jar fixes.

## World retention after closing a world: root-cause analysis (2026-09-30)

**Symptom.** In the play stack (Sodium, Iris, Distant Horizons fork.6, Slipway), every world a client opened and
closed stayed in memory: heap after GC grew by 120 to 160 MB per reopening, live `ServerLevel` objects went 3, 6,
9, 12, 15 over five cycles, and with a shader pack one `ClientLevel` per cycle as well.

**Method.** Isolation matrix: `tools/e2e/scenarios/leak-matrix.ps1` runs `leak-new.ps1` (one client process,
identical open/close cycles of a cached world, `jcmd GC.class_histogram` after full GCs at the title screen) with
each mod set; heap dumps of the failing configurations were analysed with Eclipse MAT's batch mode (`path2gc`,
`merge_shortest_paths`, thread stacks; `tools/e2e/mat.ps1`); Native Memory Tracking splits native growth into JVM
categories; thread dumps group live threads by name. Runs: `E:\slipway-e2e\runs\leak-matrix-*`.

**Isolation (before any fix; 5 cycles of the same Slipway-free world; live objects at the title screen):**

| Mods | ServerLevel per cycle | ClientLevel | Heap after GC per cycle |
| --- | --- | --- | --- |
| Sodium + Iris + DH + Slipway | 3, 6, 9, 12, 15 | 1 | +129 MB |
| Sodium + Iris + DH (no Slipway) | 3, 6, 9, 12, 15 | 1 | +142 MB |
| DH only | 3, 6, 9, 12, 15 | 1 | +133 MB |
| Sodium + Iris + Slipway (no DH) | 0 | 0 | +1.2 MB |
| Sodium + Iris | 0 | 0 | +2.2 MB |
| vanilla; Slipway only | 0 | 1 (constant) | +1.5 / +0.8 MB |
| full stack with Bliss shaders | 3, 6, 9, 12, 15 | 1, 2, 3, 4, 5 | +163 MB |
| Sodium + Iris with Bliss (no DH, no Slipway) | 0 | 1, 2, 3, 4, 5 | +40 MB |

Distant Horizons is necessary and sufficient for the server-side retention; Iris with a shader pack separately
retains one client world per cycle; Slipway is in neither.

**Reference chains and root causes** (each from a heap dump; the fix of one exposed the next):

1. *DH world-gen thread parked forever* (the first chain the old JFR run pointed at). A "DH-World Gen Thread" waited in
   `ServerChunkCache.getChunk(..).join()`: surface rules (`MaterialRuleContext.getBiome`) sampled biomes through a
   `BiomeManager` DH built on the live `ServerLevel` (DH's 26.3 `StepTerrain`), which hands the lookup to the server
   thread; after the server stopped nothing ran it, and the parked thread's stack held the level and the server.
   Vanilla's `ChunkStatusTasks.buildTerrain` uses the `WorldGenRegion`'s biome manager.
2. *A running batch joined a closed IOWorker.* `Blender.of` → `WorldGenRegion.isOldChunkAround` →
   `IOWorker.isOldChunkAround(..).join()` never completed after vanilla closed the level's IOWorker, because DH's close
   cancels futures without interrupting running work. Also a static `ThreadLocal` on pooled world-gen threads and a
   static "previous params" held the last level, and a per-level timer thread was never cancelled.
3. *Static per-level map.* `WorldGeneratorInjector.worldGeneratorByLevelWrapper` gained an entry per level and was
   never unbound (its unsynchronised writes are also the play instance's intermittent `HashMap.get(Object) is null`).
4. *Static last-frame render state* (`ClientApi.RENDER_PARAMS`, `RENDER_STATE`, and the reusable event parameters of
   DH's terrain and generic-object renderers, which Slipway's DH proxies use) kept the closed client and server levels.
5. *Unclosed player states.* A player state that was never closed kept its full-data sender's task on DH's static
   upload timer (every 50 ms) and its config listeners on DH's static config, and through them the player, its
   connection and the server: `removePlayer` returned early when the player's level was gone, replaced states were
   not closed, world close did not sweep states, and the integrated-server world's `close()` (which does not call its
   parent's) never swept them either (found by the client GameTests with a second player).
6. *Iris (1.11.6).* `RenderSystem.iris$overrides`, a static map added by Iris's `MixinShaderManager_Overrides`, is
   filled per shader program and never cleared; the programs' custom uniforms capture `Minecraft.getInstance().level`
   (`IrisExclusiveUniforms.WorldInfoUniforms`), so every world opened with a shader pack stayed. Separately, Iris keeps
   its current pipeline (static `Iris.pipelineManager`) after the world closes and rebuilds it only when the dimension
   changes; that pipeline holds the newest world's `ClientLevel` until the next world loads (bounded: one).
7. *Vanilla (bounded).* `LevelRenderer.visibleSections` keeps the last frame's render sections (and through their
   compile tasks the `ClientLevel`) until the next world renders; Sodium replaces that renderer.
8. *jolt-jni `PhysicsSystem` registry (Slipway's use of jolt-jni; not a world, found after the worlds were freed).*
   With every world collected, the fixed stack's class histograms still gained one `com.github.stephengold.joltjni.
   PhysicsSystem` per world reopening (1 to 8 over 8 cycles, run `leak-matrix-dhfix6\full-vessels-r1`), each with the
   Java objects it references (2 `BatchBodyInterface`, 2 `NarrowPhaseQuery`, Slipway's three layer and filter
   tables). jolt-jni 6.1.1's `PhysicsSystem` constructor puts the new system into a static map
   (`PhysicsSystem.va2ps`, used by `PhysicsSystem.find(address)`), its freeing action only frees the native system,
   and only `forgetMe()` removes the entry (read from the class's bytecode); Slipway's `JoltEngine.close()` and the
   startup self-test never called it. The native memory was freed (the Debug-natives allocation test balances); the
   Java objects stayed, a few hundred bytes per world load.

**The "Render thread" GC roots of the first leak run** (`leak-20260929-191726`, JFR old-object samples): 81 of the 95
samples are reported with the root "Threads, stack variable, Render thread", but every one of those chains ends
`java.lang.Class ← ArrayList ← KnotClassLoader`: the objects are held by static fields of classes Fabric's class
loader loaded, and JFR reached that loader through a local variable of a frame on the render thread and named the root
after it. The render thread itself held no world. The static fields were Distant Horizons' `WorldGeneratorInjector.
INSTANCE` (32 samples, all 32 passing through a `ServerLevel` or `MinecraftServer`: chain 3), `GlobalWorldGenParams.
previousGlobalWorldGenParams` (4; 1 through a world: chain 2), DH's lighting array pool `lightArrayCache` (42, int
arrays only) and Netty's default allocator (3, buffers). No Slipway or jolt-jni class is on any sampled path. JFR samples
a subset of allocations and keeps one path per object, which is why that run named chain 3 and none of the others.
The Slipway classes that grew in that run grew with the retained worlds: `VesselRegistry` went 3, 6, 9, 12, 15
(one per retained `ServerLevel`), `VesselCollisions$Contact` 11 to 572 and jolt-jni's `PhysicsSystem` 1 to 5; after
the fixes `VesselRegistry` and `VesselCollisions$Contact` have no instance after any cycle, and `PhysicsSystem` only
kept growing because of chain 8.

**Fixes.**
- Distant Horizons, at the source: local branch `slipway-leak-fix` of `E:\distant-horizons` (wrapper `aa2e97405`,
  core `5e93372c4`, not pushed), patches L1-L8 in its `PATCHES.md`, with core unit tests for the injector
  (`testWorldGeneratorUnbindReleasesTheLevel`, `testConcurrentWorldGeneratorBinding`, the latter failing on the old
  map). The build `3.3.1-tellus-fork.6-leakfix.9` was used by the client GameTests (`devmods/test`) and the test
  instance, and since 0.1.1 by the play instance (the player's choice); the client GameTests now use its successor
  `...-leakfix.9-irisfix.1` (below, "Dark blotches"). The same work fixed a DH thread leak (one "World Gen Progress Updater"
  thread per level per world) and a DH bug that dropped every block-use packet when a client hosts a dedicated server
  in-process.
  Since the evening of 2026-09-30 the fork is rebased onto official 3.3.4 as `3.3.4-tellus-fork.7` (local branch
  `rebase-3.3.4`). It carries the versions of these fixes prepared for upstream (A-G in its `PATCHES.md`) instead of
  L1-L8, and it is the Distant Horizons of the client GameTests and of the play instance.
- Iris and vanilla, mitigated in Slipway: `ClosedWorldCleanup` (client, the first tick without a world) clears
  vanilla's visible-section list and Iris's override cache through guarded reflection (hook `iris-overrides-cache` in
  `patches.json`); Iris rebuilds cache entries on demand. Iris's current pipeline is left alone: destroying it would
  make every world join recompile the shader pack.
- jolt-jni (chain 8), fixed in Slipway: `JoltEngine.close()` and `JoltSelfTest` call `PhysicsSystem.forgetMe()`
  before closing the system. Unit test `JoltEngineTest.closingAnEngineReleasesItsPhysicsSystemFromJoltJni` (fails
  without the fix: the closed engine's system is still found in jolt-jni's map), and the client GameTest `leak`
  requires zero live `PhysicsSystem` objects after every close (it was 1 to 8 before the fix, 0 after).

**Result** (strict leak check, below; and the matrix):

| Stack | Server objects after close | ClientLevel after close | Heap after GC per cycle | Threads |
| --- | --- | --- | --- | --- |
| before: full stack, fork.6 | 5 servers, 15 levels after 5 cycles | 1 (5 with Bliss) | +129 MB (+163 with Bliss) | +6 per cycle |
| after: client GameTest `leak`, no shaders (leakfix.9) | 0 every cycle | 0 | +0 to +2.7 MB (ZGC counts used heap in 2 MB pages) | Netty pool only |
| after: client GameTest `leak`, Bliss | 0 every cycle | 1 (the newest, Iris pipeline) | -0.7 to +2 MB | Netty pool only |
| after: matrix, full stack with vessels, 8 cycles | 0 | 0 | +2.6 to +2.9 MB | Netty pool only |
| after: client GameTest `leak`, fork.7 (`clientgametest-20260930-182511`) | 0 every cycle | 0 (with Bliss 1, the newest) | flat: 576 to 582 MB (622 to 626 with Bliss) | Netty pool only |

Before the `PhysicsSystem` fix (chain 8) that matrix run also kept one jolt-jni `PhysicsSystem` per cycle (1 to 8);
with it the client GameTest `leak` finds none after any cycle.

Observed and left alone (not a retained world, not growing): `DhChunkGenerator` creates a `DhInternalServerGenerator`
per level, whose `java.util.Timer` ("DH-ChunkSaveIgnoreTimer") starts its thread at construction and is never
cancelled (`DhChunkGenerator.close()` cancels only its own timer, patch L3). The thread ends when the generator
becomes unreachable (the JDK's `Timer` stops its thread once the `Timer` is collected): the leak check saw none of them
after most cycles and 3 after two consecutive cycles of one run, never more, with no world retained. Cancelling it from
`DhChunkGenerator.close()` would be a one-line change on the DH branch; it was not made because it would have
required re-validating the whole suite on a new DH build for a thread that already goes away.

**The strict check** is the client GameTest `leak` (`src/clientGametest/.../LeakScenarios.java`): the same saved
world with three flying vessels is opened and closed five times without shaders and five times with Bliss. After
every close and full GCs it requires every earlier cycle's `IntegratedServer`, `ServerLevel`s and `ClientLevel` to
be collected (weak references to those exact objects; with Bliss only the newest cycle's `ClientLevel` may stay, as
explained in chain 6), no live `IntegratedServer` or `ServerLevel` at all, no live jolt-jni `PhysicsSystem`,
Slipway's Jolt engines, bodies, level managers and client vessels at zero, Iris's override cache empty, heap after
GC growing under 16 MB per cycle, no thread group that keeps growing (Netty's local event-loop group is one static
pool of at most two threads per core, started lazily: it gains three threads per world opening, the only group that
grows, and the report lists every group that changed), and without shaders native memory growing under 64 MB per
cycle. Any surviving world writes a heap
dump for the path to its GC roots. There is no attribution: anything retained fails, whoever holds it.

**Native memory.** Windows private bytes do not include ZGC's heap (mapped as shared memory), so they measure native
memory; NMT showed the JVM's own native memory flat across cycles. Without shaders the full stack grows 5 to 55 MB per
cycle (noisy; Distant Horizons alone 31 to 50, Sodium + Iris alone 7 to 8); with Bliss about 160 to 200 MB per cycle,
the same without Slipway and without DH (Sodium + Iris + Bliss: +162 MB outside the JVM). That growth is outside the
JVM, in Iris or the graphics driver; it is reported by the leak check and not bounded with shaders on, and it is not a
retained world. Finding its exact owner needs a native heap profiler; recorded as open.

**Test-harness findings on the way** (not product bugs): Fabric's client GameTest waits assume vanilla's renderer; a
singleplayer close can deadlock the GameTest threading when a mod's client close is slow; a test method's dead local
variable kept a world alive (a JVM frame's dead locals are GC roots); a dedicated server hosted in the test game stays
reachable through vanilla's watchdog thread and JVM shutdown hook. Each is handled in the test code; see "Testing".

## Dark blotches on world blocks under shaders: root-cause analysis (2026-09-30)

**Symptom.** In the play instance's Slipway Sandbox with Bliss, the plain (never assembled) copy of the demo skiff
had black, blotchy lighting, and so did the grass and trees near the camera; the assembled vessel next to it looked
right. It appeared after every shader-pipeline creation (joining the world, reloading shaders), stayed while the
camera stood still, and changed after camera moves.

**Method.** A diagnostic client GameTest (`diag-plain-ship`, `DiagScenarios`, runs only when named) opens a copy of
the player's world with their options (render distance 6, smooth lighting, field of view 100, their Bliss options),
puts vessel #1 back where it was built, and photographs both ships from fixed viewpoints: after the world opens, half a
minute later, after shader reloads with a static camera, and with shaders off. The measure is the mean luminance of
the plain skiff's deck: about 120 clean, 50 to 70 affected. Evidence: `E:\slipway-e2e\diag\run6` to `run19b` and
`diag-run*.log`; `run-diag.ps1` reruns a case.

**Isolation.**

| Varied | Result |
| --- | --- |
| Shaders off | clean; both ships identical |
| Distant Horizons removed | clean (run9b) |
| Bliss debug views | normals and direct light (with shadows) identical on both ships; indirect light has hard black polygons on world blocks only |
| Light data | sky and block light in the region files correct; the vessel's mesh light values equal the terrain's |
| Bliss options: contact shadows, DH AO, TAA, denoise, emissives off | still affected |
| DH builds | affected: fork.6, leakfix.9, leakfix.9 without the fork's five P7 Iris/Sodium mixins, leakfix.9 with patch P6 off, leakfix.9 with the render-pass fix (I2 below), upstream releases 3.3.0 and 3.3.2; clean: upstream 3.3.3, 3.3.4 and 3.3.5-dev |
| LOD database | the same with the player's LOD database and with freshly generated LODs (fork and upstream) |
| leakfix.9 + upstream's blend-state fix (I1) | clean: 120.6 to 125.1 with fresh LODs, 121.8 to 121.9 with the player's |

So it is not Slipway, the world, the light data or the shader pack: it is a Distant Horizons bug on Minecraft 26.2+,
fixed upstream between 3.3.2 and 3.3.3; of that range's rendering changes, the blend-state fix alone removes it.

**Chain.**
1. Minecraft 26.2+ caches blending per draw buffer (`GlStateManager.BLEND_ENABLE[8]`) and calls
   `glEnablei`/`glDisablei` only when the cache differs.
2. DH up to 3.3.2 (and the Tellus fork) toggled blending with `glEnable`/`glDisable(GL_BLEND)`, which switches all
   buffers, but updated the cache for buffer 0 only (`MinecraftGLWrapper.enableBlend`/`disableBlend`).
3. DH draws its LODs in `LevelRenderer.prepareTranslucents`, which 26.3 calls at the start of the main pass, before
   `executeSolid` draws the opaque terrain. After DH's draw, GL has blending on for buffers 1-7 while the cache says
   off (measured: every frame, at Fabric's `START_MAIN` and `AFTER_OPAQUE_TERRAIN` events).
4. The opaque terrain (Sodium, with Iris's G-buffer programs) asks for blending off on those buffers; the cache says it
   already is, so no GL call is made, and the terrain's fragments are blended into what the G-buffers already hold.
   Bliss keeps the lightmap and material data that its indirect lighting reads in those buffers, so world blocks come
   out dark in blotches, while normals and direct light look right.
5. Later draws toggle those buffers through the cache and resync it: by the end of the solid-features phase (where
   Slipway's vessels are drawn, see "Deviation" above) GL and the cache agree again, and between frames they always
   do. That is why the vessel looked right; which draw resyncs it first was not traced. Why the pattern holds while
   the camera is still and changes when it moves (what is already in the buffers, section draw order) is inferred,
   not traced.

**Fixes** (Distant Horizons fork, local branch `slipway-iris-fixes`, not pushed; see its PATCHES.md):
- I1, wrapper d50c680f3: backport of upstream `95bbccaff`; on 26.2+ every buffer is set through
  `GlStateManager._enableBlend(i)`/`_disableBlend(i)` and `glEnablei`/`glDisablei`, so cache and GL stay equal.
- I2, core 9572e8aa0: the render pass is chosen again after `DhApiBeforeRenderEvent`, where Iris sets its
  defer-transparent flag; this removes Iris's "Unexpected; somehow the Opaque + Translucent pass ran with shaders on"
  after each pipeline creation. Not the cause of the blotches. The diagnostic runs with upstream 3.3.2+ (Iris 1.11.6)
  did not show the message, but twelve film-tool runs on official 3.3.4 with Iris 1.11.7 logged it once each, so
  upstream still has the ordering problem and fork.7 keeps I2.
- Build `DistantHorizons-fabric-3.3.1-tellus-fork.6-leakfix.9-irisfix.1-26.3.jar` (SHA-256 `B9FE6130...A79E`), DH core
  tests 106 of 106. It was the client GameTests' Distant Horizons (`devmods/test`) and, for a few hours, the play
  instance's, until fork.7 replaced it (below).
- Upstream 3.3.3+ has two more 26.x Iris fixes the 3.3.1-based builds do not carry: `eb5076971` (DH's lightmap bound
  where Iris reads it on 26.1.2+) and `01b9370b5` (GL state left to Iris while a shader pack is active; rendering with a
  boat on screen). Moving the fork to upstream 3.3.4 brings all three.

**Resolution (2026-09-30, evening).** The fork is rebased onto official 3.3.4 as `3.3.4-tellus-fork.7` (local branch
`rebase-3.3.4` of `E:\distant-horizons`, SHA-256 of the Fabric 26.3 jar `BCF32F99...FEF10`), which contains upstream's
blend fix instead of the backport I1, and keeps I2. With it: the diagnostic on the copy of the player's world measures
121.7 / 121.6 / 121.8 / 121.8 (`E:\slipway-e2e\diag\run20-fork7-userlods`); `render-iris` passes with the cache in
sync at every sampling point and the unchanged reference images (near 4.0e-5, far 4.4e-5); no run logged Iris's
message. It is installed in the play instance and in the Tellus instances.

**Regression check.** `GlStateCheck` (client GameTest `render-iris`, near and far views with Bliss) compares the
per-buffer blend and colour-write-mask cache with GL at five of Fabric's level render events and between frames, over
40 frames, and fails on any difference or if a sampling point never ran. With leakfix.9 it fails in every frame
(119 of 119, buffers 1-7 at `startMain` and `afterOpaqueTerrain`); with irisfix.1 it passes. A first version that only
looked between frames passed with the bug: the state must be sampled inside the frame.

**What the test suite had missed, and why.**
- The two Bliss reference images accepted in the overnight run were recorded with the bug: a dark band across the far
  view's ground and an over-dark shadow in the near view. The contact-sheet review looked at the vessel, not at whether
  the terrain was lit right, and had no independent baseline to compare against. Both are re-recorded with irisfix.1
  (reviewed: old, new and their difference); their difference from the old ones was 5.2e-4 (near) and 1.65e-3 (far),
  well inside the old 0.02 limit, so the image check could not have caught it. The limit is now 2.5e-4: run to run the
  views differ from their references by at most 4.6e-5 (21 runs, before and after the fix), and with leakfix.9 the
  near view now fails it (5.17e-4), independently of the GL state check.
- The Bliss shadow ratio (ground under the vessel vs without it) was 0.61 with the bug and is 0.76 fixed (limit 0.8):
  shaded ground receives only the indirect light, which the bug darkened.
- Run-directory state leaked between runs. Fabric's client GameTest takes its "default" options after `options.txt` is
  loaded, so the diagnostic's field of view 100 became every later run's default and moved the camera framing
  (reference difference 0.012 instead of 4e-5, shadow ratio 0.83 to 0.90). `prepareClientGametestRun` now deletes
  `options.txt` and the Distant Horizons config before every run.

## Testing

Four levels, all part of `gradlew check` (`build` runs them too):

| Level | What | Where | Time |
| --- | --- | --- | --- |
| Unit tests (JUnit) | pure logic and jolt-jni (poses, boxes, controller, records, engine lifecycle with Debug natives) | `src/test` | seconds |
| Server GameTests | assembly, physics, interaction and packets inside a headless server | `src/gametest`, `runGametest` | ~10 s |
| Client GameTests | every in-game scenario on a real client with the play stack | `src/clientGametest`, `runClientGametest` | ~10 min (2-minute soak, as in `check`); ~28 min with the 20-minute soak |
| Packaged-jar check | the release jar with the exact play-stack jars in production Minecraft | `src/packagedCheck`, `runPackagedJarCheck` | ~30 s |

**Client GameTests** (`fabric-client-gametest-api-v1`, shipped in Fabric API 0.160.7+26.3). One entrypoint runs twelve
scenarios (`SlipwayClientGameTests`); each starts at the title screen with default options, a failure is recorded
and the next scenario still runs, and the run fails at the end if any failed. Reports:
`build/client-gametest/TEST-slipway-client-gametest.xml` (JUnit) and `results.json` (every measurement, note and
evidence path); screenshots under `build/client-gametest/screenshots/<scenario>`. Options:
`-PslipwayClientGametestOnly=a,b`, `-PslipwaySoakMinutes=20` (the release soak; the default in `check` is 2),
`-PslipwayRecordTemplates=true` (write missing reference images; otherwise a missing one fails),
`-PslipwayClientGametestMods=sodium,iris,dh` (subset of render mods), `-PslipwayTestDhJar=<jar>`,
`-PslipwayTestDhConfig=<file>` (a Distant Horizons config to start from; otherwise its defaults).
The run uses Sodium, Iris with Bliss (copied into the run directory; shaders are switched on through Iris's API where
a scenario needs them) and the Distant Horizons build from `devmods/test` when there is one (now
`3.3.4-tellus-fork.7`, the same jar as `devmods`; before it the patched `...-leakfix.9-irisfix.1`). Every run starts
from fresh game options and DH defaults
(`prepareClientGametestRun` deletes `options.txt` and `DistantHorizons.toml`).
Checks read game state on the server and client threads (vessel records, client vessels, riders, block states,
block entities, packets refused, tick times) and wait for conditions or a number of game ticks, never wall-clock time.
Pixels are checked where they are the point: the assembled vessel against a picture of the blocks it came from,
reference images for the Bliss near and far views (mean squared difference at most 2.5e-4; about 4e-5 run to run with
Bliss's clouds frozen), the outline pixels
switched by vanilla's own outline switch, the shadow as ground brightness with and without the vessel (with and
without shaders), and the far barge as a compact shape at the aim point. With Bliss on, `render-iris` also checks GL
state directly: Minecraft's per-draw-buffer blend and write-mask cache must equal GL inside every frame
(`GlStateCheck`; see "Dark blotches on world blocks under shaders").
Multiplayer runs a dedicated server inside the test game and a second real client: the same game started again in its
own process (`WatcherProcess`: same classpath and JVM options, its own directory and name, commands through files).
Both clients' vessel poses are compared with the server's pose for the same server tick.
Perf measures at 1280x720, the M7 window size (resized through the test API; the other scenarios use the runner's
854x480), in a superflat world at render distance 12 on the integrated server. Its absolute frame rates and tick times
are therefore not comparable with M7's (a generated world, a dedicated server and a remote client); the cost of the
flying barge relative to the warm baseline is. Six runs (series3 and series5): frame-rate drop between -0.8% and
12.9% (8.5, 12.9, 5.0, -0.8, 3.1 and 4.9%; the measurement is noisy at 750 to 900 frames per second; M7 on Prism:
8 to 15%); server tick with the barge flying 0.44 to 0.68 ms mean, +0.03 to +0.12 ms over the warm baseline, worst
single tick 0.7 to 2.7 ms; physics step 0.07 to 0.08 ms mean on its own thread.

**Harness facts learned the hard way** (each handled in the test code; none needs a Slipway change):
- Fabric's `waitForChunksRender` asks vanilla's `LevelRenderer`, which Sodium replaces, and `waitForChunksDownload`
  expects the full square of chunks while servers send a circle; `Game.waitChunks`/`waitTerrain` wait for the circle
  and ask Sodium (`isTerrainRenderComplete`).
- Closing a singleplayer world can deadlock the GameTest threading: the deferred `disconnect` runs at the start of a
  tick phase and `IntegratedServer.halt` blocks on a task for the server thread; when a mod's client close (Distant
  Horizons closing its databases) outlasts the server's tick, the server is parked at the phase barrier (thread dump:
  render thread in `IntegratedServer.halt → executeBlocking`, server thread in `ThreadingImpl.enterPhase`). The test mods
  carry a test-only mixin (`IntegratedServerHaltMixin`) that queues that task (removing non-owner players) instead.
- Unattended runs need `-Dfabric.noGui=true` (Fabric Loader otherwise opens an error window and waits) and headless
  AWT, decided before Distant Horizons sets it back to false for its dialogs (otherwise the in-process dedicated server
  opens its GUI window). The SDL window is created without activation (`SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN=0`).
- A dedicated server in the test game needs `eula.txt` in the run directory (written by `prepareClientGametestRun`,
  as the Prism harness does for its server), a whitelist entry for the second client, no watchdog
  (`max-tick-time=-1`), and vanilla's JVM shutdown hook removed after it stops (it references the server).
- JVM frames keep dead local variables as GC roots: world steps of the leak test run in their own methods.
- Fabric's client GameTest takes its "default" game options after `options.txt` is loaded, so anything a run saves
  there becomes the next run's default (a diagnostic's field of view once moved every later run's camera framing).
  The run directory's `options.txt` and Distant Horizons config are deleted before every run.
- The idle frame limit (30 fps after a minute without input) is off (`inactivityFpsLimit=minimized`).
- Bliss's clouds move with real time and cast shadows: the run's pack options freeze them (`Cloud_Speed=0.0`) and turn
  their shadows off, so shaded pictures repeat.
- Distant Horizons draws its own LOD clouds whatever vanilla's cloud option says (`enableCloudRendering`, on by
  default), and they drift, so no view with sky was ever still. The test options turn them off through DH's config API
  (an in-memory override).
- A picture that is compared with another must wait until the view is still. After a teleport, terrain beyond the
  render distance (vanilla's chunk edge, then Distant Horizons' LODs) keeps arriving for 40 to 90 ticks and changed
  frames 10 ticks apart by up to 0.015 mean squared difference (the assemble-mixed limit is 0.004); without DH it
  settled after 40 ticks. `Shots.waitStill` waits until two pictures 10 ticks apart are identical (and fails, keeping
  both pictures, if they never are). Found when the first clean build after series2 failed assemble-mixed: its
  reference picture had been taken 5 ticks after landing, and the check then compared a different frame from the one
  it saved and measured (Fabric's comparison takes a new frame), which hid the failing picture. Picture checks now
  assert on the frame they save. With the view still, built and assembled pictures differ by 0.0001 (before:
  0.0001 to 0.0012), and the outline check counts the outline alone (about 960 changed pixels; before, about 4,940
  including drifting clouds and the hand's sway between the two frames).

**What stays on Prism.** No acceptance check. The leak isolation matrix (`tools/e2e/scenarios/leak-matrix.ps1`,
`leak-new.ps1`) stays as a diagnostic tool, because isolating a leak needs configurations without Slipway, and a client
GameTest run always contains Slipway (the test mod depends on it). So do the play-instance tools
(`tools/verify-play-instance.ps1`, `tools/make-sandbox-world.ps1`), which work on the real play instance. The twelve
Prism scenarios, their runner and deploy script were retired to `tools/e2e/legacy` (with a README mapping each to its
client GameTest) after three consecutive green client-GameTest runs with the 20-minute soak (series2), confirmed by
series5 (three more, in fresh run directories, after the fixes described under "Runtime and flakiness"); the
`e2eWatcher` Gradle run that only the Prism multiplayer scenario used was removed.

**Server tick-time criterion (decided 2026-09-30).** The Prism perf and soak checks sampled `/slipway stats`, vanilla's
100-tick average, every 30 s and required the largest sample under 25 ms (perf also a mean under 15 ms). The client
GameTests record every tick's own time (`MinecraftServer.getTickTimesNanos`, `TickTimes`), so the old check can be
evaluated after every tick instead of every 600th: the worst 100-tick average must stay under 25 ms (the old check,
now without sampling gaps), 95% of ticks under 20 ms, every single tick inside the 50 ms tick budget, and for perf the
mean under 15 ms. A first version also required every single tick under 25 ms. That is stricter than anything the old
harness measured, and the 20-minute soak failed it once with one 32.3 ms tick (mean 0.77 ms, p95 1.31 ms). A 6-minute
soak with save tracking then showed where such ticks come from: its worst tick, 23.3 ms, was tick 6000, the vanilla
autosave (`MinecraftServer` saves every 6,000 ticks; the next worst tick was 10.2 ms), and a save costs more the
more chunks the flight has loaded. A lone long tick inside the budget is not a lag the player sees, and the check
reports the five worst ticks with whether a world save ran in each, so a regression that moves the tail is still
visible. Kept: the budget as a hard limit on every tick, which the old harness never checked. The three 20-minute
soaks of series2 confirmed it: in every run the four worst ticks were exactly the four autosaves (ticks 6,000,
12,000, 18,000 and 24,000; the first was the most expensive each time, 37.1 to 37.2 ms), the worst other tick was 7.6
to 12.4 ms, the mean 0.78 to 0.79 ms, the 95th percentile 1.30 to 1.35 ms and the worst 100-tick average 1.35 to
1.43 ms. Series5's three soaks showed the same (first autosave 30.0 to 38.6 ms, the worst other tick at most 12.9 ms,
mean 0.79 to 0.82 ms). That the save time is vanilla chunk writing rather than Slipway's vessel records is inferred (one vessel
record per save), not profiled.

**Runtime and flakiness, before and after.** "Before" is the Prism harness (M1 to M7, 2026-09-29, from
`validation.json`); "after" is the client GameTests (2026-09-30).

| | Prism harness | Client GameTests |
| --- | --- | --- |
| Full suite, 20-minute soak | 39 min wall-clock for the last batch (release jar, 19:02 to 19:41), including a 3.5-minute rerun of a failed leak run; then a manual review of every scenario's screenshots | 28.4, 28.7 and 28.3 min (series5, each in a fresh run directory: 1,702, 1,719 and 1,699 s; the 11 scenarios besides the soak take 7.7 to 8.1 min); series2 before it: 28.1 min three times |
| Full suite, 2-minute soak (`check`) | not run that way | about 10 min (series0: 9.9 min; the first clean build: 10.1 min) |
| Scenario runs recorded | 112 | 36 in series5, 36 in series2, 24 in two earlier series, 12 in the first clean build, 3 perf runs, and the final clean build |
| Failures | 36 by the automated checks (43 after review); 26 runs never reviewed | 3 runs with a failure before series5 (below); none in series2, series3 or series5 |
| Reruns of unchanged code that changed result | 7 of 29 (24%; 6.2% of all runs) | 1 of 36 (2.8%) for the version that series2 retired: assemble-mixed failed in the first clean build after passing three times; after its fix 0 of 24 in series5 |
| Harness failures | 14 runs ended in a "scenario error" (the script itself failed) | 0 |

The Prism harness did not record script versions, so a result that changed on the same jar is either nondeterminism
or a script fix between the runs; the two cannot be separated, which is itself one of its weaknesses. Three client
GameTest runs had a failure, all counted above. Series0 failed once in assemble-mixed: the picture was compared before
the client had meshed the whole vessel, a test race fixed by `waitClientComplete` in f0293d0. Series1's first run
failed the soak's original every-tick 25 ms limit (see the criterion above; series1 was stopped there). And the first
`gradlew clean build` after series2 (13611e2) failed assemble-mixed again, a different race that three consecutive
runs had not shown: its reference picture was taken while distant terrain was still arriving (see "Harness facts"),
fixed in 95ee36b. That is why the final series (series5) ran three times in fresh run directories, as a clean build
does, after that fix and after the `PhysicsSystem` fix (2485c6a); series4 was stopped in its first run for the
latter. Series runs: `E:\slipway-e2e\cgt\series*` and `clean-build*`, recorded in `validation.json`.

## Known log noise (not Slipway)

The harness ignores these, each checked to occur without Slipway or to be expected by a test:
`Reference map ... could not be read` (Iris/Sodium dev refmaps), `Requested post effect does not exist` (vanilla
26.3 with Iris), `Distant Horizons OpenGL error logging`, `Force-disabling mixin` (Sodium/Iris), `Sodium has applied
one or more workarounds`, `Rejected helm control` (forged-packet test), and, with Distant Horizons builds without I2
(the fork before `irisfix.1`; official builds up to at least 3.3.4), Iris's DH compat line
`Unexpected; somehow the Opaque + Translucent pass ran with shaders on` (also in the Slipway-free Tellus-Expeditions
instance; a DH render-pass ordering bug, fixed as I2 under "Dark blotches").
