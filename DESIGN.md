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
| Integrations | Sodium 0.9.2+mc26.3, Iris 1.11.6+mc26.3, Distant Horizons 3.3.1-tellus-fork.6 |
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
  1,000-block barge accelerate and turn alike. Hover cancels gravity and brakes idle axes; level adds a rate that
  rotates the up axis back to world up. All inputs, forces and states are checked for NaN/Infinity; a non-finite
  physics state restores the last good pose.
- Leaks: every jolt-jni object is closed by its owner (`JoltEngine.release` for shape references, bodies destroyed on
  removal, the engine closed on level unload). `JoltEngineTest.anEngineLifecycleFreesEveryNativeObject` runs a full
  lifecycle with the Debug natives' allocation counters and requires news == deletes.

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

**Fixes.**
- Distant Horizons, at the source: local branch `slipway-leak-fix` of `E:\distant-horizons` (wrapper `aa2e97405`,
  core `5e93372c4`, not pushed), patches L1-L8 in its `PATCHES.md`, with core unit tests for the injector
  (`testWorldGeneratorUnbindReleasesTheLevel`, `testConcurrentWorldGeneratorBinding`, the latter failing on the old
  map). The build `3.3.1-tellus-fork.6-leakfix.9` is used only by the client GameTests (`devmods/test`) and the test
  instance; the play instance keeps fork.6. The same work fixed a DH thread leak (one "World Gen Progress Updater"
  thread per level per world) and a DH bug that dropped every block-use packet when a client hosts a dedicated server
  in-process.
- Iris and vanilla, mitigated in Slipway: `ClosedWorldCleanup` (client, the first tick without a world) clears
  vanilla's visible-section list and Iris's override cache through guarded reflection (hook `iris-overrides-cache` in
  `patches.json`); Iris rebuilds cache entries on demand. Iris's current pipeline is left alone: destroying it would
  make every world join recompile the shader pack.

**Result** (strict leak check, below; and the matrix):

| Stack | Server objects after close | ClientLevel after close | Heap after GC per cycle | Threads |
| --- | --- | --- | --- | --- |
| before: full stack, fork.6 | 5 servers, 15 levels after 5 cycles | 1 (5 with Bliss) | +129 MB (+163 with Bliss) | +6 per cycle |
| after: client GameTest `leak`, no shaders (leakfix.9) | 0 every cycle | 0 | +0 to +0.7 MB | no group grows |
| after: client GameTest `leak`, Bliss | 0 every cycle | 1 (the newest, Iris pipeline) | +2 MB | no group grows |
| after: matrix, full stack with vessels, 8 cycles | 0 | 0 | +2.6 to +2.9 MB | Netty pool only |

**The strict check** is the client GameTest `leak` (`src/clientGametest/.../LeakScenarios.java`): the same saved
world with three flying vessels is opened and closed five times without shaders and five times with Bliss. After
every close and full GCs it requires every earlier cycle's `IntegratedServer`, `ServerLevel`s and `ClientLevel` to
be collected (weak references to those exact objects; with Bliss only the newest cycle's `ClientLevel` may stay, as
explained in chain 6), no live `IntegratedServer` or `ServerLevel` at all, Slipway's Jolt engines, bodies, level
managers and client vessels at zero, Iris's override cache empty, heap after GC growing under 16 MB per cycle, no
thread group that keeps growing (Netty's local event-loop group is one static pool of at most two threads per core,
started lazily), and without shaders native memory growing under 64 MB per cycle. Any surviving world writes a heap
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
`-PslipwayClientGametestMods=sodium,iris,dh` (subset of render mods), `-PslipwayTestDhJar=<jar>`.
The run uses Sodium, Iris with Bliss (copied into the run directory; shaders are switched on through Iris's API where
a scenario needs them) and the leak-fixed Distant Horizons build from `devmods/test` (else fork.6).
Checks read game state on the server and client threads (vessel records, client vessels, riders, block states,
block entities, packets refused, tick times) and wait for conditions or a number of game ticks, never wall-clock time.
Pixels are checked where they are the point: the assembled vessel against a picture of the blocks it came from,
reference images for the Bliss near and far views (MSD 0 run to run with Bliss's clouds frozen), the outline pixels
switched by vanilla's own outline switch, the shadow as ground brightness with and without the vessel (with and
without shaders), and the far barge as a compact shape at the aim point.
Multiplayer runs a dedicated server inside the test game and a second real client: the same game started again in its
own process (`WatcherProcess`: same classpath and JVM options, its own directory and name, commands through files).
Both clients' vessel poses are compared with the server's pose for the same server tick.
Perf measures at 1280x720, the M7 window size (resized through the test API; the other scenarios use the runner's
854x480), in a superflat world at render distance 12 on the integrated server. Its absolute frame rates and tick times
are therefore not comparable with M7's (a generated world, a dedicated server and a remote client); the cost of the
flying barge relative to the warm baseline is. Three runs (series3, below): frame-rate drop 8.5%, 12.9% and 5.0%
(M7 on Prism: 8 to 15%); server tick with the barge flying 0.64 to 0.68 ms mean, +0.06 to +0.12 ms over the warm
baseline, worst single tick 1.9 to 2.7 ms; physics step 0.08 ms mean on its own thread.

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
- The idle frame limit (30 fps after a minute without input) is off (`inactivityFpsLimit=minimized`).
- Bliss's clouds move with real time and cast shadows: the run's pack options freeze them (`Cloud_Speed=0.0`) and turn
  their shadows off, so shaded pictures repeat.

**What stays on Prism.** No acceptance check. The leak isolation matrix (`tools/e2e/scenarios/leak-matrix.ps1`,
`leak-new.ps1`) stays as a diagnostic tool, because isolating a leak needs configurations without Slipway, and a client
GameTest run always contains Slipway (the test mod depends on it). So do the play-instance tools
(`tools/verify-play-instance.ps1`, `tools/make-sandbox-world.ps1`), which work on the real play instance. The twelve
Prism scenarios, their runner and deploy script were retired to `tools/e2e/legacy` (with a README mapping each to its
client GameTest) after three consecutive green client-GameTest runs with the 20-minute soak (series2); the
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
1.43 ms. That the save time is vanilla chunk writing rather than Slipway's vessel records is inferred (one vessel
record per save), not profiled.

**Runtime and flakiness, before and after.** "Before" is the Prism harness (M1 to M7, 2026-09-29, from
`validation.json`); "after" is the client GameTests (2026-09-30).

| | Prism harness | Client GameTests |
| --- | --- | --- |
| Full suite, 20-minute soak | 39 min wall-clock for the last batch (release jar, 19:02 to 19:41), including a 3.5-minute rerun of a failed leak run; then a manual review of every scenario's screenshots | 28.1, 28.1 and 28.1 min (series2: 1,689, 1,686 and 1,684 s; the 11 scenarios besides the soak take 7.5 min) |
| Full suite, 2-minute soak (`check`) | not run that way | 9.9 min (series0) |
| Scenario runs recorded | 112 | 36 in series2, plus 24 in two earlier series and 3 perf runs |
| Failures | 36 by the automated checks (43 after review); 26 runs never reviewed | none in series2 or series3 |
| Reruns of unchanged code that changed result | 7 of 29 (24%; 6.2% of all runs) | 0 of 24 in series2 (each scenario run three times); 0 of 2 in series3 |
| Harness failures | 14 runs ended in a "scenario error" (the script itself failed) | 0 |

The Prism harness did not record script versions, so a result that changed on the same jar is either nondeterminism
or a script fix between the runs; the two cannot be separated, which is itself one of its weaknesses. Two client
GameTest runs failed before series2, both counted above: series0 failed once in assemble-mixed (the picture was
compared before the client had meshed the whole vessel; a test race, fixed by `waitClientComplete` in f0293d0,
passed in every run since), and series1's first run failed the soak's original every-tick 25 ms limit (see the criterion above; series1 was
stopped there). Series runs: `E:\slipway-e2e\cgt\series*`, recorded in `validation.json`.

## Known log noise (not Slipway)

The harness ignores these, each checked to occur without Slipway or to be expected by a test:
`Reference map ... could not be read` (Iris/Sodium dev refmaps), `Requested post effect does not exist` (vanilla
26.3 with Iris), `Distant Horizons OpenGL error logging`, `Force-disabling mixin` (Sodium/Iris), `Sodium has applied
one or more workarounds`, `Rejected helm control` (forged-packet test), and Iris's DH compat line `Unexpected; somehow
the Opaque + Translucent pass ran with shaders on` (also in the Slipway-free Tellus-Expeditions instance).
