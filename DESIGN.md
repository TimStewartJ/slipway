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
blocks.

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
- **Pilot's view does not roll.** The pilot rides eye-anchored at the helm and turns with the vessel's heading, but
  vanilla cameras cannot roll; when the vessel rolls or loops, the pilot's view stays upright relative to the world
  while the vessel rotates around them.
- **Default keys** avoid vanilla 26.3 defaults (Z descend, arrows pitch/roll, N/M strafe, H hover, B level). The
  play instance for the author rebinds three of them to fit that player's layout.

## Known log noise (not Slipway)

The harness ignores these, each checked to occur without Slipway or to be expected by a test:
`Reference map ... could not be read` (Iris/Sodium dev refmaps), `Requested post effect does not exist` (vanilla
26.3 with Iris), `Distant Horizons OpenGL error logging`, `Force-disabling mixin` (Sodium/Iris), `Sodium has applied
one or more workarounds`, `Rejected helm control` (forged-packet test), and Iris's DH compat line `Unexpected; somehow
the Opaque + Translucent pass ran with shaders on` (also in the Slipway-free Tellus-Expeditions instance).
