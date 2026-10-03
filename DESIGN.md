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

**The jar is the same every time it is built (0.1.3).** Two things made two builds of one commit differ.
`natives.properties` was written with `Properties.store`, which adds the time of the build; the build now writes the
file itself (keys in order, `\n` line ends, no date; the loader reads it as before). And Loom nests the jolt-jni jar
through Java's zip file system, which stamps the two entries it adds (`META-INF/jars/` and the jar in it) with the
time of the build, as a DOS time and in an NTFS time field. Loom rewrites a remapped jar with fixed times afterwards
but not the jar of a game that is not remapped, so `build.gradle` calls that rewrite at the end of the `jar` task
(`ZipReprocessorUtil.reprocessZip`; not public Loom API, so a Loom update may need that one block changed or
removed). Checked: `gradlew clean assemble` twice, and once more with the build's JVM in another time zone, gives the
same jar and the same sources jar byte for byte; the 187 entries have the contents they had before the rewrite; the
packaged-jar check runs on that file. The jar still depends on the JDK that compiles it and on the line endings of
the checkout (`* text=auto`: the JSON resources are CRLF in a Windows checkout with `core.autocrlf=true`, as the
release build's is, and LF elsewhere). A build on another system has not been compared.

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
`extraDeniedBlocks`, other helms, fluid blocks (a ship built on the sea does not take the sea with it; waterlogged
blocks keep their waterlogged state, so fluids aboard are supported that way), and blocks in the `slipway:sea_plants`
tag (kelp, sea grass, bubble columns: they grow from the sea's floor up to a hull and would tie it to the ground;
disassembly puts a vessel down on them as on air). `VesselAssembly` copies block states
and block-entity data into the plot, erases the originals without drops, and creates the record and entity.
Disassembly requires the vessel to be within `disassemblyTiltDegrees` (default 20 degrees) of level, snaps the
heading to the nearest quarter turn, rotates every block state with `BlockState.rotate`, refuses (with a message)
when a target is occupied, outside the world or unloaded, and moves entities standing on the deck onto the placed
blocks. Each relocation is pushed out of the placed blocks with the same strict depenetration the decks use: float
rounding in the pose can otherwise put a rider a few microns inside the deck, and vanilla collision ignores a floor
that a box already overlaps by more than 1e-7, so the player would fall through the new blocks (seen in the release
regression run; `InteractionGameTests#entitiesAboardMoveWithTheSnappedBlocks` models it).

**A plot is freed empty (0.1.3; found in the review of 0.1.2, which was released with it as a known issue).**
Disassembly and `/slipway remove` walk the vessel's bounds, and the registry hands the plot that was freed last to
the next vessel that is assembled. A block in the plot outside the
bounds (one the vessel had not taken in because it lies past the largest size or in the plot's margin, see "Block
events, effects and pistons") stayed there. The next vessel in that plot had it in a section of its own: solid,
drawn and counted as one of its blocks, and put into the world at its disassembly if it lay inside its bounds; the
same ship assembled again in the same place got the block back exactly where it had been. Now:

- When a vessel ceases to exist (`VesselManager.retire`, the one path that frees a plot: disassembly and
  `/slipway remove`; unloading a vessel does not), `VesselAssembly.clearPlot` removes every block and block entity
  left in the chunk columns of its bounds and two columns round them, with `Block.UPDATE_SKIP_ALL_SIDEEFFECTS`:
  nothing drops, no neighbour is updated and nothing is sent (viewers drop those chunks a tick later). Two columns is
  as far as the vessel's tickets load its plot, so as far as anything aboard can have set a block (water flows only
  where chunks tick, one column out). It does not reach a block that a command set further out in a plot chunk that
  something else had loaded.
- As a second line, assembly empties the columns the new vessel takes and the ring its viewers get before its blocks
  go in (those columns are loaded at that point anyway) and logs a warning when it finds anything, because a plot
  that is handed out is expected to be empty. This is no migration of old worlds: a block further out in the plot
  stays until the vessel's bounds reach its section.

Server GameTests (`AssemblyGameTests`): `disassemblyLeavesNothingInThePlot` and
`removingAVesselLeavesNothingInThePlot` set a stone, a chest with items, water and (with `/setblock`, two columns
out) another stone past a lowered largest size; after disassembly or `/slipway remove` the plot is empty and nothing
was dropped, and the ship assembled again has its own ten blocks, shape and mass and disassembles to them.
`aPlotHandedOutWithBlocksInItIsEmptiedFirst` puts blocks into a free plot directly. Against the code before, the
first two find the four blocks still in the plot and the third a vessel of 13 blocks instead of 10; with only one of
the two measures in place, the tests of the other fail.

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
takes K, O and R. Up to 0.1.3 there was no buoyancy: vessels collide with blocks that have a collision shape, water
has none, so a loose vessel sank through water to the bottom, wooden or not. Since then water lifts it (next
section; `aLooseWoodenVesselFloatsOnWater`). Entities do
not push vessels.

**Holding.** Until 0.1.1 a hovering vessel only had its weight cancelled and its speed braked, and with level off only
its turn rate braked. That is no hold: anything resting on it pushed it down for as long as it lay there (10 crates on
a 2,000-block carrier sank it by a quarter of a block per second), and cargo lying off centre turned a vessel with
level off until the cargo slid away. So hover now holds (`VesselController.Hold`, one per vessel, used only on the
physics thread):

- Position: the point the brake would stop the vessel's centre of mass at. Along an idle local axis the point stays
  and the vessel is pulled to it by a spring on top of the brake, `a = -wv - w²(e + tv)`, with `w = BRAKE_GAIN`
  (1.5/s), `e` the distance from the point and `t` the brake's stopping time. A vessel that nothing pushes has
  `e = -tv`, and there the law is the old brake `a = -wv`. Along an axis with input nothing is held: the point is
  put where the brake would stop the vessel now. Under a load of a fraction `f` of its weight the vessel sags
  `f g / w²` (4.4 blocks per unit of `f`: 0.10 blocks for the 2.3% in the client GameTest, measured 0.103). The pull
  is limited to `HOLD_SLACK` = 2 blocks of spring (a load of 46% of the vessel's weight); beyond that the point gives
  way and the vessel sinks slowly instead of winding up. A vessel pushed aside by another comes back by at most those
  2 blocks.
- Attitude, only with level off: the same with `k = RATE_GAIN` (3/s) about each local axis,
  `alpha = -kw - k²(e + tw)`, limited to 20 degrees. With level on, levelling is the hold for pitch and roll, and
  gives by `asin(torque / (4.5 I))` (three degrees for a two-block crate 2.2 blocks off the centre of a 7x7 raft).
- The hold is taken anew after a teleport, a restored pose, hover off and on, level on and off, and loose.

*A vessel that nothing pushes must fly exactly as it did under the plain brake of 0.1.1*, wherever its helm is and
whatever it does. Three things were needed for that, the first two found by the review of the first version:

- It is the centre of mass that is held, because that is the point the velocity belongs to and the one a turn leaves
  in place. The first version held the vessel's origin (the helm's corner) with the same force through the centre
  of mass: a ship whose helm is not at its centre was pulled round its helm when it turned, drifted in turns and
  flew a different circle depending on where the helm stood. The engine reports the position of the shape's origin
  and the velocity of the centre of mass; `drive` adds the rotated centre (`BoxList.MassProperties`, in the
  vessel's frame) to the position.
- The hold works in the engine's steps, not in continuous time. The engine advances a body in
  `PhysicsWorld.SUBSTEPS` = 3 parts of 0.05 s under the force of one call, velocity first (`VesselController.Step`).
  So the plain brake stops a vessel after `t = 1/w - (2/3) 0.05` = 0.633 s of its speed, not `1/w`; and while the
  vessel turns, the axes the hold works along turn between two calls. Both are made up for exactly: `t` is the
  stepped stopping time, and at the end of every call the point is moved by what the plain controller's own
  acceleration `a` changes about where the vessel will stop, `0.05 (v + a/w)` (nothing along an idle axis, the
  vessel's travel along one with input). The held attitude is turned along the same way, by stepping the plain rate
  controller's torque as the engine will (the torque stays as it is in the world while the hull and its inertia turn
  through the three parts). A version without this (holding the centre, but following input only at the start of a
  step) flew a full-thrust turn 1.4% faster and wider than the plain brake, stopped half a block further on, and
  with level off ended a 15-second manoeuvre on all six axes 5 blocks away.
- When blocks change, the centre of mass shifts in the vessel's frame, and the held point is shifted with it
  (`aHoveringVesselStaysWhereItIsWhenItsCentreOfMassShifts`). The review proposed to take the hold anew instead. That
  lets go of a load: the vessel's present place becomes the point, so a loaded carrier would sink by its sag at every
  block change, and a ship with cargo and a piston clock would sink all the time
  (`aLoadedHoveringVesselKeepsItsHeightWhenItsBlocksChange`: ten block changes under a load that sags the carrier
  1.3 blocks leave it at its height).

`ActiveVessel.brakeOnly` (set by tests only) drives a vessel without the hold, as 0.1.1 did: the reference the tests
compare with, flown side by side in the same engine. Unit tests on the real engine (`LooseCargoTest`):
`aHoveringVesselTurnsAboutItsCentreOfMass` (a hull with its origin 6 blocks from its centre turns more than 90
degrees on the spot, level on and off, and the centre moves less than 0.02 blocks);
`aCruiseTurnWithTheHoldIsTheTurnOfThePlainBrakeWhereverTheHelmIs` (ten seconds of full thrust and full yaw, then the
stop, with the helm at the stern and at the bow: speed and radius within 0.01% of the reference and of each other,
the path within 0.001 blocks; measured 0.00001); `withNothingPushingItAVesselFliesWithTheHoldAsWithThePlainBrake`
(a lopsided hull flown on all six axes for 15 seconds, level on and off: within 0.001 blocks and 0.01 degrees of the
reference). Each fails when one of the corrections above is taken out. Server GameTest
`aShipSteeredFromItsSternTurnsAboutItsCentreAndFliesAsUnderThePlainBrake`: a ship with its helm 8 blocks from its
centre of mass, in the open above the arenas beside its reference; a quarter turn on the spot moves the centre of
mass 0.0000 blocks, and at full thrust and yaw both fly 13.6743 blocks per second on a radius of 15.1937 blocks and
are 0.00001 blocks apart after eight seconds and after the stop.

**Jolt settings checked for small bodies on a deck** (unit tests in `LooseCargoTest`, on the Debug natives; server
GameTests in `LooseGameTests`; client GameTest `loose-cargo`):

| Setting | Value | Finding |
| --- | --- | --- |
| Step | 0.05 s in 3 collision steps (60 Hz), 10 velocity and 2 position iterations (Jolt's defaults) | Crates dropped from 3 to 8 blocks land without bouncing through or being thrown; a pile of 8 to 10 comes to rest within 70 ticks. |
| Convex radius | 0.05 (less for thin boxes) | Kept. Resting bodies overlap by Jolt's penetration slop of 0.02 blocks and no more; nothing sinks in over time. |
| Friction | 0.6 on vessels, 0.8 on terrain, combined by geometric mean | Kept. Cargo holds on a deck up to atan(0.6) = 31 degrees and slides beyond (it starts between 28 and 36 in the unit test). It also means the deck can pass on at most 0.6 g = 5.9 m/s²: the stop of a released helm brakes at 1.5 times the speed per second, so from more than 4 m/s cargo slides forward, and tall thin pieces topple. Fly gently or build a rail. |
| Restitution | 0.05 (Jolt takes the larger of two) | Kept: nothing bounces. |
| Motion quality | LinearCast for every vessel | Kept: a crate at terminal speed does not pass through a one-block deck. |
| Sleeping | off for controlled vessels, on for loose ones | A controlled vessel gets forces every step and can never sleep. A loose vessel at rest on terrain or on other sleeping vessels is put to sleep after 0.5 s and then costs nothing (0.004 ms per step for 30 of them, against 0.5 ms awake, Debug natives). What rests on a hovering carrier is in the carrier's island and stays awake, as it must to ride along. Jolt wakes a sleeper that is touched, but not one whose support goes away: `JoltEngine` wakes the loose vessels around a terrain section that changes and around a vessel that is removed, reshaped or teleported (within 8 blocks of its bounds; a piston working on one ship does not wake a pile elsewhere). |
| Enhanced internal edge removal | on for every vessel | A deck or the ground is many boxes side by side; a body sliding fast over the buried edges between them caught on them (a cube at 8 m/s over single-block boxes tumbled within four blocks). With the option, vessels slide over terrain seams as over one slab (`vesselsSlideOverTerrainSeamsAsOverOneSlab`). It only removes the edges of the second body of a pair. Terrain is always second; of two vessels the one with the higher Jolt body id is, which jolt-jni 6.1.1 gives no way to choose (no `CreateBodyWithID`). So a crate sliding fast over a seam of a deck that is older than the crate still tumbles there. Slow sliding and resting are not affected. Left as is. |

Cost, client GameTest `loose-cargo` (Release natives; 10 loose vessels of 2 to 30 blocks, 110 blocks together, on a
2,080-block carrier of 4,990 t; 11 plots loaded): physics step 0.24 ms mean while they land (worst 2.2 ms), 0.20 ms
at rest on the deck, 0.18 ms while the carrier flies, 0.16 ms while they slide off and fall, 0.08 ms once all ten
sleep on the ground; Slipway's part of the server tick (the exchange) 0.1 ms or less; server tick 0.8 to 2.0 ms mean,
4.5 ms worst. The client's poses are the server's (offset 0.0 blocks over 748 compared poses).

### Water: floating, sinking and dry hulls (unreleased)

A vessel's blocks are stored in its plot, so where the vessel is, the world is as it was: the sea is still there, in
and around the hull. Everything about water is therefore something Slipway adds on top of a world that knows nothing
of the vessel: the lift, the resistance, who counts as in the water, and what is drawn.

**What a vessel displaces (`Hull`).** Built with the collision shape, from the same pass over the plot
(`SectionShapes` reports every block with a collision shape: its cell, the volume of its shape, where the shape
begins and ends in height, and whether it keeps water out, which every such block does unless it is in the tag
`slipway:not_watertight`). Each cell of the vessel's
bounds is then one of: *watertight*; *open* (the water outside reaches it as soon as it stands that high);
*sheltered* (air below a rim: pour water into the upright vessel and it is a cell that fills, so water from outside
gets there only over that rim); *sealed* (air with no way out). The rim is found by a priority flood from outside
(Barnes, Lehman and Mulla 2014, in three dimensions with a cell's height as its elevation): every open cell gets the
lowest level the outside water must reach to run into it, and the cell on the way in where that level is reached,
its *pour point*. A cell whose level is above itself is sheltered. A watertight block that does not fill its cell (a
carpet, a chest, a slab) leaves the rest of the cell as dry as the air around it when all of that air is sheltered.
The flood runs on the bounds plus one cell, up to 2^20 cells; a vessel with larger bounds gets no sheltered air
(4,096 blocks cannot enclose much in such a box). What displaces is listed as elements (centre, volume, height,
pour point): one for each block, where its shape is and as high as its shape (a raft of bottom slabs floats 0.35
deep, not 0.7), and one for the dry air of each cell. A block with open space beside it is marked as the vessel's
outside. Above 8,192 elements they are merged cube by cube of 2, 4, 8 cells (outside blocks, everything else that
always displaces, and sheltered air each on their own). The same code builds the same hull on
the client from its copy of the plot. A hull is rebuilt only when the blocks that matter change (a fingerprint of
cells, volumes, heights and tightness), not for a lever or a chest lid.

This is the design decision the feature stands on. Lift from the blocks alone is simple and lets wood float, but
then no hull carries anything and a stone ship is impossible. A hull's air is what carries a real ship, and a player
expects walls to keep water out. Deciding it per cell by a pour point keeps it stateless (nothing to save, nothing
to pump) and still gives the events that make water interesting: overloading, a rim that dips when the hull heels,
a hole below the waterline.

**Where the water is (`FluidField`).** The physics thread may not read the level. With each terrain section it
builds for a vessel's surroundings, `VesselPhysicsBridge` also copies the section's fluids, one byte per block
(amount 1 to 8, lava or water; waterlogged blocks count), and hands them over with a physics command; a change to a
block of a built section rebuilds both. `FluidField.submerged` tells how much of an upright cube lies below the
surface of the column through its centre (a source's surface is 8/9 up, or the block's top when fluid stands above;
continuous as the cube rises through the surface and across sections).

**Lift and resistance (`Buoyancy`, in the step, after the controller).** Each element under the surface is pushed up
by `density g volume` (water 1,000, lava 3,100 kg/m³) at its place; a sheltered element counts by how far its pour
point's cell is still out of the water (flooded from the rim to half a block above it, so nothing jumps). The sum is
a force and a torque about the centre of mass: the draught where the vessel displaces its weight, the righting
moment of a heeled hull, and the loss of lift on the side whose rim dips all come out of it. Resistance is on the
centre of mass and the turn rate, scaled by the ratio of displaced weight to the vessel's own (1 afloat):
`a = -ratio (c v + q |v| v)` along the vessel's own forward, right and up axes with c = 0.7, 2.5, 2.5 per second and
q = 0.02, 0.15, 0.15 per block (a keel: a boat runs straight and does not slide), and `alpha = -1.5 ratio w`. The
numbers are chosen for feel: a hull with a draught of one to two blocks bobs two or three times after a drop, a
block of stone sinks at 4.35 blocks a second, and a boat under full thrust (12 m/s² against 0.5 per second in the
air and 0.7 + 0.02 v in the water) runs 8.7 blocks a second. Never more than 80% of a speed is taken in one step.

A hovering vessel gets none of it (`forces = loose || !hover`): hover cancels the vessel's weight, and lift without
weight would throw it out of the water against the hold. So hover is "no gravity" consistently: the ship is a
submarine. What it displaces is still recorded, for the display and the splash.

**Sleeping.** A loose vessel afloat must be able to fall asleep, and Jolt's body interface restarts a body's sleep
timer with every force it is given. `JoltEngine.applyForceAndTorque` therefore adds to the body itself while it is
awake. Asleep, a vessel gets no lift (it rests where the last step left it) and keeps what was recorded. Water is
no body, so a section whose fluids changed wakes the loose vessels in and around it
(`PhysicsEngine.wakeLooseVessels`): a raft asleep in a pool falls when the pool is drained.

**Dry inside (`Shelter`).** A point is dry when it lies in a sealed cell, or in a sheltered cell whose pour point is
not yet half flooded (the level's fluid there, at the vessel's pose). `EntityMixin` asks after vanilla has found an
entity in fluid: the entity's look at the fluids around it is then repeated with no box to look in, which leaves it
as vanilla leaves an entity in the air (no swimming, slowing, current, drowning; the same code on server and client,
so nothing is predicted differently). `CameraMixin` does the same for the fluid the camera is in (water fog, and a
shader pack's eye-in-water).

A vessel under way is somewhere else every tick, and a place belongs to the pose it was reached with. Asked with
the current pose only, someone standing by the aft wall of a hull at speed is in that wall, or in the sea behind it:
the fluids are looked at in `baseTick`, before the entity's move carries it from the previous pose to the current
one; a player's place on the server is what the player's client sent, and the client plays the vessel back two
ticks late on top of the way there; the camera is between two ticks. So `Shelter` asks with the pose that fits:

| Who | Poses | Why |
|---|---|---|
| An entity this side moves (`VesselCollisions.simulates`) | current or previous | carried once a tick, the look at the fluids comes before or after it |
| Anything else: a player on the server, a passenger, everything a client is only told about | any of `View.recentPoses()`: the server keeps ten ticks (`ActiveVessel.POSE_HISTORY`), a client its two playback poses and up to six newer snapshots | its place follows the vessel by a delay nobody here knows |
| The camera | `View.framePose(partial tick)` | it is interpolated like the vessel it is drawn in |

The price is the reverse case: a swimmer right behind a moving hull, where its hold was up to half a second ago, is
not in the water for that moment. That costs a stroke; the other way round a crew would drown below deck.

**The water mask (`WaterMask`).** The water's surface would be drawn across the inside of a floating hull. Vanilla's
boats have the same problem and solve it with a patch drawn into the depth buffer only, just before the water
(`RenderTypes.waterMask()`); Slipway draws such patches for any hull. Once a client tick the dry cells near a
surface are picked; every frame each one's cut with the surface is computed for the frame's pose (a cube against a
horizontal plane: three to six corners) and drawn 0.03 above the surface, for the view from above, and 0.03 below,
for the view from inside the hull under the waterline, each facing both ways. They are submitted into the frame's
water-mask phase directly (`SubmitNodeCollection.waterMask`), where vanilla draws it before the water in both of its
transparency modes; through another mod's collector they go as custom geometry.

**Docking.** `VesselManager.disassemble` takes the fluid blocks out of the cells the hull kept dry (at the pose it
had), after its blocks are in the world: they keep the water out themselves from then on.

**Launching (`VesselManager.closeTheWater`).** The other way round the world needs help too. A hull standing in
water as blocks keeps the water out of its own cells and of its hold; assembling it takes the blocks away and leaves
a hole in the sea. The game fills such a hole in its own time, flowing in from the edges and making sources as it
goes: seconds for a large hold. Until then there is no water where the vessel is, and the lift comes from the
world's water: the stone barge of the harbour test (13 by 17, six deep), released ten ticks after assembly, dropped
19 blocks to the sea's floor with all of its 923 m³ of air counted as flooded. So assembly fills the hole in the
same tick, as the game would in the end: the hull is built from the plot at once (`VesselPhysicsBridge.hullNow`),
and every cell its blocks were in or kept dry becomes a water source if a source lies beside or above it, and so on
inwards (a queue; cells above the water around never fill). Alternatives: reading the water around the vessel
instead of under it (no clear rule where "around" is, for a hull in a lock or beside a pier), or holding the vessel
until the hole has filled (the wait depends on the hold's size, and the hole is visible meanwhile).

**A new body knows its surroundings.** Ground and water go to the physics thread section by section, at most 24 a
tick over all vessels. A body got its first step in the tick it was made, so with many vessels made at once (a
world loading, 50 tests in one batch) one could have its weight a tick before its water: a drop of 0.13 blocks,
enough to put a low rim under. The sections around a body made in this tick are now built in the same tick, beyond
the budget (`ActiveVessel.surroundingsUrgent`, up to 512).

**Effects.** The step reports up to eight places where the surface cuts a block of the vessel's outside: a block
with open space to a side of it (not the dry air of the hold, which the surface cuts as well, nor a chest in it, nor
the middle of the floor), and the place is 0.3 outside that side's face, since at the block's centre half of the
spray would come out on the inside of the wall. `WaterEffects` makes the splash (particles and a sound, when a
vessel goes in at more than 1.5 blocks a second) and the spray along the waterline of a moving vessel there. The
game's own drifting specks in water blocks (`WaterFluid.animateTick`) are skipped in air a hull keeps dry
(`WaterFluidMixin`): they would drift through a hold and a cabin.

**What it looks like from a submerged cabin.** The view is not a view from under water, so there is no water fog:
through the windows the sea is clear, with its floor, its plants and its surface from below. Seen in the harbour
test, with and without Bliss. A tint for what lies beyond the glass would need the pack's or the game's fog applied
by depth outside the hull only; not done.

Tests: `FluidFieldTest`, `HullTest` (rims, holes, sealed air, railings, furniture, slabs, the outside, merging),
`ShelterTest` (dry and wet places, flooding over the rim, a heeled hull, a vessel under way), `CubeCutTest`,
`BuoyancyTest` on the real engine (draught of a plank, of a raft of slabs, of a stone hull of 1,334 t, terminal speed, flooding over the rim, a swamped wooden
hull coming back up, righting from 12 degrees, a sealed cabin rising from 20 blocks down, hover, a boat's speed,
straight run and turn, sleeping and waking, lava); server GameTests `BuoyancyGameTests` in a pool (the level's water
reaching the step, cargo until it is too much, an armour stand dry in a hull and wet when it floods, a docked hull
dry, the water closed in the tick of assembly and the hull released at once, kelp under a hull at assembly and at
disassembly); client GameTest `afloat` (also with Bliss on: the mask from inside and from above, no view from under
water, no drifting specks in the hold).

**In a real sea (`make-harbour`, a diagnostic client GameTest, run only when named).** A normal world with a fixed
seed; at the nearest deep sea a pier and four things moored at it as blocks: a raft, a boat with a mast, a closed
submarine of planks and glass with iron ballast, and a barge of stone bricks. Each is assembled, tried with
Distant Horizons and with Bliss, put back and disassembled; the world is saved for playing by hand ("Slipway
Harbor"). Measured on 2026-10-02:

| | Mass | Can displace | Result |
|---|---|---|---|
| Boat (87 planks, mast, sail) | 68.2 t | 145 m³ | floats level (0.65°), feet 0.62 under the waterline in a dry hold; full thrust: 46.9 blocks in six seconds, 8.88 blocks a second at most, 0.65° of tilt; 130° of turn in four seconds |
| Submarine (5 by 5 by 9, 57 m³ sealed) | 193.0 t | 224 m³ | dives 9.8 blocks under hover, eyes 11.1 under the surface, not in water, full breath after ten seconds; hover off: comes up, roof 0.70 above the surface, 0.2° |
| Barge (557 stone bricks) | 1,384 t | 1,548 m³ | floats with 0.71 of freeboard, level (0.02°), no drop at release; eyes 3.65 under the surface in a dry hold |
| Raft (25 logs) | 19.6 t | 28 m³ | floats loose, level |

All four moored again with every block as built and no water in the air they keep dry. The scenario takes every
view without the shader pack, with it, and with it and the mask off (uild/client-gametest/screenshots/make-harbour).

### Survival rules: sails, hot air and ballast (unreleased)

Up to 0.1.3 every vessel got the same thrust acceleration whatever it weighed, and hover held any weight up for
nothing: a helm on a mountain of stone flew at 24 blocks a second. Under the survival rules a vessel moves and
lifts itself with what it is built of. The rules are a setting (`survivalRules`, on by default) that newly assembled
vessels take; each vessel remembers whether it is *free* of them (`VesselRecord.free`). Vessels from saves made
before the rules load as free, so no ship that flew stops flying, and an operator can change one vessel with
`/slipway mode <id> free true|false`.

**What is read off the blocks (`Rig`).** In the same pass over the plot that builds the collision shape and the hull:

- *Sails.* A block in the tag `slipway:sails` (wool) with open air, or open water, on two opposite sides (east and
  west, or north and south) counts. "Open" is what the hull calls open: no block, not below a hull's rim, not sealed
  in, and not inside an envelope. So a sail on a mast counts, the part of it below the gunwale does not, and wool
  laid as a deck, walled in, or forming the skin of a balloon does not. There is no wind: every sail adds the same
  push in whatever direction the helm asks for. A fin of wool on a submarine's stern is its screw.
- *Hot air.* The air a vessel's blocks keep from rising away is its envelope. It is the vessel's hull upside down:
  the same blocks are given to a second `Hull.Builder` with up and down swapped, and what that hull calls sheltered
  (air below a rim, here the mouth of a balloon or the top of a doorway) or sealed is air that cannot rise out. The
  flood for it runs only for a vessel that has a burner, and only when its blocks changed. A burner is a block in
  the tag `slipway:burners` that is lit (a campfire or soul campfire); it counts when there is nothing but air
  between it and envelope straight above it, within 16 blocks. Each burner heats `burnerVolume` (100 m^3) of air;
  the air that is both held and heated lifts `hotAirLift` (500 kg) a cubic metre. Putting a fire out takes its lift
  away at the next tick.

**What the rig allows (`Rig.rating`, applied by `VesselController`).**

| | Free vessel | Under the rules |
|---|---|---|
| Thrust along the deck | `thrustAcceleration` (12 m/s^2) | `helmAcceleration` (1.5 m/s^2, the helm's own: oars) plus sails x `sailThrust` (50 kN) over the mass, at most `thrustAcceleration` |
| Top speed in the air | `maxSpeed` (24 m/s) | in proportion: 3 m/s under the helm alone |
| Hover | holds any weight | holds the vessel when its lift is at least its weight; otherwise the lift only makes it lighter, and it floats, sinks or falls like a vessel without hover |
| Up and down while hovering | `thrustAcceleration` | up with the lift to spare (at least 0.5 m/s^2), down by letting air out (half a g, or as fast as it climbs) |
| Up and down in water, not hovering | `thrustAcceleration` | ballast: `ballastTrim` (0.3) of its weight, in proportion to how much of its weight it displaces; nothing out of the water |
| Turn rate | `maxTurnRate` | times the square root of its share of the full thrust, at least 0.3 |

Drag is the same for all, so a rated vessel's top speed falls with its thrust. The hold of a hovering vessel is
unchanged: a vessel that is held up is braked and held as before; one that is not gets no hold. Buoyancy acts on
every vessel that hover does not hold up, so a boat assembled in the water floats at once, hover on or off.
A submarine is a closed hull that displaces a little more than its weight (the hull line of the pilot's display must
read between 100% and 130%): it floats, goes down while descend is held, and comes back up by itself.

The pilot's display shows the rig (`VesselInfo` carries it): sails and the top speed they give, and the lift as a
share of the weight with the number of burners, or that the vessel is too heavy to fly. With hover on and too little
lift the display reads "NO LIFT" (a flag bit of the pose packet).

Measured in `make-harbour` on 2026-10-03, every ship assembled under the rules:

| | Mass | Rig | Result |
|---|---|---|---|
| Raft (25 logs) | 19.6 t | helm only | 1.2 blocks a second on the sea |
| Boat | 68.2 t | 12 sails | 7.7 blocks a second on the sea, 41 blocks in six seconds, 120 degrees of turn in four seconds |
| Submarine | 194.6 t, displaces 119% | 8 blocks of wool as a screw | dives 8 blocks on its ballast at 0.7 blocks a second, rests there with the ballast trimmed (0.02 blocks a second), runs 2.5 blocks a second under water, comes up when the helm is let go |
| Stone barge | 1,421 t | 112 sails on two masts | 4.2 blocks a second, 0.54 of freeboard |
| Balloon (wool canopy 7 by 7 by 6 over two campfires) | 44.1 t | lift 140% of its weight, 4 sails | hangs where it is assembled, climbs 47 blocks in eight seconds (7.7 blocks a second at most), sails 11.9 blocks a second |

Tests: `RigTest` (what counts as a sail, the envelope and burners, the rating's numbers, the controller with a
rating and without), `VesselRecordTest` and `SlipwayConfigTest` (the flag and the settings), and
`SurvivalGameTests` on a server with real blocks (the rig of a ship with a mast, a fire under the open sky and wool
in its deck; two decks of iron dropped side by side, one under oars and one under sail; a balloon that hangs, climbs
with its spare lift and comes down when its fires are put out). The test and film runs turn `survivalRules` off in
their run directories (`freeVesselsConfig` in `build.gradle`), because their ships are built to test something else;
the tests of the rules put their own vessels under them, and `make-harbour` turns the rules on.

### Networking

Server-authoritative. Viewers get `VesselInfo` (plot mapping, bounds, helm; flagged on assembly), plot chunks as
normal chunk packets (`ChunkMapMixin` keeps block, light and block-entity updates flowing to them although plots are
far outside their view; the client stores them in a separate map, `ClientChunkCacheMixin`), a `PoseUpdate` every
tick, and `VesselGone` (with `keepProxy` when only the near view ends). The only serverbound packet is `HelmControl`,
accepted only from the vessel's pilot, rate-limited (40 per second), with NaN/Infinity rejected and axes clamped to
[-1, 1] (`ServerPackets`). Vanilla use/break packets aimed at plot positions are checked for reach against where
the block is in the world (`PlayerMixin`).

**Which plot chunks viewers get (0.1.2).** The columns that hold the vessel's blocks (ticketed,
`ActiveVessel.ticketChunks`) and the ring of columns around them (`viewChunks`). The ring is empty, and it is what
lights the vessel's outer faces: vanilla's block renderer takes a face's light from the block next to it, and in a
column the client does not have, the client's light is 0. Up to 0.1.1 viewers got the ticketed columns only, so every
face on their outer edge was drawn black. That is common, because the helm stands in the middle of the plot, which is a
chunk corner: a build that begins at its helm (a keg, a raft with the helm on its edge, a 2x2x2 crate) had black west
and north sides, in any light and at any attitude. Found by the film agent in a close view of cargo; it measured sky
light 0 on exactly those faces and 15 on all others.

*Why the ring, and not a mesher that never asks for light outside the sent columns.* Both cure the black faces. A
stand-in (open sky for every block outside the client's columns) costs no packets, but it is a guess, and the light
next to an outer face is not always the open sky's: a lamp on the vessel lights the faces round the corner through
the neighbouring column (block light 11 on the west faces beside a glowstone in the test, 10 on the north faces; a
stand-in would give 0, which shows at night). The stand-in would also have to be put into everything that samples
round a block: the mesh, the moving blocks of a piston's stroke (drawn as block models from the level), fluids. With
the ring the client has what the server has, real light in real chunks, kept up to date by the ordinary light
packets (viewers count as tracking these columns, `ChunkMapMixin`), and every renderer is right without knowing
about it. The price is 8 more empty chunk packets for a vessel in one column (14 for one of three by two), sent
once; the server has those columns loaded anyway, because the tickets reach two columns out.

- Assembly loads the ring with the vessel's own columns so that both go out in the same tick
  (`VesselAssembly.loadPlotChunks`); for a vessel loaded from a save a ring column that is not loaded yet follows
  when it is (`unsentChunks`). When a column's light is switched on, the client marks the columns around it for
  rebuilding (vanilla's `enableChunkLight`, through `ClientLevelMixin`), so the faces are remeshed when the ring
  arrives.
- When the bounds grow (`VesselManager.includeLocal`), the columns that are new to the bounds and to their ring are
  added and sent the same way. A piston or a placed block moves an edge by a block or a few, so the ring is always a
  column ahead of it; only a command can put a block on a far chunk border whose neighbouring column is new to
  viewers, and that column is sent as soon as it has loaded.
- Smooth lighting blends, at each vertex, the light of the block next to the face with that of three blocks round
  it; at a chunk corner one of them is in the column diagonally across, which is part of the ring. That is not what
  made faces black: vanilla's blend (`LightCoordsUtil.smoothBlend`) takes the face's own light in place of a sample
  of light 0, so only a face whose own neighbour is missing goes dark (the test below passes without the diagonal
  columns). They are sent all the same, so that a lamp's light at a corner is the real one. How much a corner is
  darkened by ambient occlusion depends on the blocks round it, which are air there with or without the ring.
- A block entity takes its light from its own place, in one of the vessel's own columns: it was never affected.

Checked by the client GameTest `small-vessel-light`: vessels in daylight without shaders, a 2x2x2 crate of white
wool under its helm towards +x and +z, the same crate round its helm (in all four columns at the corner; never
affected), a build at the corner with glowstone and a chest, and a row with a piston.

- Pictures of the crate taken square on from the west and the east, and from the north and the south, are equally
  bright in the middle (luminance 100.8 and 100.7 of 255; 134.4 and 134.5; limit 10%). Without the ring the west
  side has 13% of the east side's brightness (13.2 against 100.7), and the north side the same share.
- Every vertex of every face that looks sideways or up has sky light 15, on all three vessels, and on the third
  again after a block was set at the east end of its column and after one at the west end of the next column (lit
  in the tick the block showed, or the tick after). On a fourth vessel a piston pushes a block of wool to the east
  end of its column: the block's east, north, south and top faces have sky light 15. (The extended piston has 14
  there: it is no full block and is lit by its own place under the redstone block, as it is in the world.)
- The darkest vertex of the faces beside the glowstone has block light 11 (west) and 10 (north); the chest at the
  edge is drawn with sky light 15 and block light 14.

And by `loose-cargo` (the lowest sky light baked into the side and top faces of the carrier and of the ten pieces,
which begin at their helms, is 15; it was 0 for the first piece's west faces before the fix), by `save-reload` (a
crate that begins at its helm and is in view when the world is reopened: 15 before quitting, and after loading 15
for good once the client has the columns round it and their light, see below; 0 without the ring), by a picture in
`assemble-mixed` (the wool crate seen from the north-west, where the two sides
fill the view: assembled, it differs from the blocks it was built from by less than 0.00001 mean squared
difference, limit 0.001; with the sides black it was 0.0069) and by the server GameTests
`viewersHaveTheColumnsAroundAVesselToo` and `columnsOfABlockSetFarOutsideAVesselReachItsViewersOnceLoaded`. Not
measured: the moving blocks of a piston's stroke at a chunk border (they take their light as the mesh does).

**The ring's sky light after loading (0.1.3).** A build after the release of 0.1.2 failed in `save-reload`: the crate
was lit when it first showed after loading and had a black west side ten ticks later. Two things were behind it. The
first is in 0.1.2 as released (the server GameTest below fails on its code; in `save-reload` it showed in 2 of 18
world loads counted over the released code and the code after it).

*A fault that stayed.* With the check rewritten to wait (below), two of six runs ended with the west side black for
good: every column on the client, all light applied, and sky light 0 beside the crate on the client and on the
server. The server had it wrong. Light data for a section exists wherever a section with blocks is next to it, in
the column next door too. When a chunk with blocks comes from a save before the column beside it, that data is made
for the neighbour while the neighbour's light is still off, as zeros. Vanilla mends this in two ways: with the light
saved in the neighbour's chunk, and when the neighbour's light is switched on (`SkyLightEngine.setLightEnabled`
fills its all-zero data with 15, from the top down to the lowest section that is wholly open to the sky). Neither
holds here. The saved light is not always there: a chunk is saved when it is unloaded, and if the vessel's column
went first, the light data round its blocks, the neighbour columns' share included, was already dropped. And the
fill does nothing for a column without any block, which is what every column round a vessel is: the lowest open
block of such a column is `Integer.MIN_VALUE`, vanilla subtracts one, the number overflows to the largest there is,
and the section to stop at comes out far above the world. So with the saved light missing and the vessel's column
first, the zeros stayed, went to every client as that column's light, and the vessel's sides towards it were black
until the vessel was loaded again in another order. `SkyLightEngineMixin` takes the value one higher for plot
columns, so the subtraction gives minus infinity and every section is filled, as vanilla's code does for a column
that has a block; columns outside the plots are untouched. Server GameTest
`anEmptyPlotColumnGetsItsSkyLightWhenItsLightComesOnLast` goes through the sequence with the light engine's own
calls (a block on the edge of a plot column; the column beside it without light; the block's section announced; the
column's light switched on) and reads the sky light beside the block: 0 without the mixin (two runs of two), 15 with
it. `save-reload` then passed in ten runs of ten.

*A moment that passes.* The client applies the light it was sent a share at a time (`ClientLevel.pollLightUpdates`:
a tenth of what waits, at least ten chunks, each frame), and after a join some hundred chunks wait. A vessel's mesh
is built as soon as its blocks are there. If its own column reaches the client before the columns round it, its
sides on the chunk border are lit by nothing: bright at first (with no light data for the plot at all, the client
reads open sky), black once the own column's light has been applied, and lit for good when the columns round it
come. With those columns held back 60 ticks on the server (a hook put in for the measurement, not in the code),
three reloads gave: bright at tick 0, black from tick 5 or 14 on, lit one or two ticks after the columns came.
Without holding them back the nine columns came together in every reload looked at: no dark tick in forty, and in
the ten runs after the fix every side was lit for good 9 to 26 ticks after the crate showed. It can still happen:
the server sends a column once the columns next to it are loaded too, so a vessel's own column can be ready before
its ring, and after a join vanilla's chunk sender waits before its next batch. Holding a vessel back until its ring
can go with it would close that; it is not done, because it would make every vessel appear later after a load.

The old check in `save-reload` waited for the first lit reading and looked again ten ticks later, which takes the
bright-by-nothing state for the lit one. It now waits until the client has all nine columns and has applied all the
light it was sent (`Game.lightUpdatesQueued`), requires every side to be lit from then on for twenty ticks, all
within 400 ticks of loading, and on failure reports the light per side of the mesh, the light data beside the crate
on the client and on the server, and whether meshing again changes it.

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
player's facing into the vessel frame so placed blocks orient relative to the vessel. `ServerLevelMixin` moves drops
and sounds from plot positions to where they are in the world and sends block and level events to the vessel's
viewers (next section). `VesselCollisions` (via `EntityMixin`)
collides entities with vessel blocks in the vessel's frame, carries them with the vessel, lets them walk on decks
tilted up to 50 degrees and slide off steeper ones.

**Jumping and climbing on a deck are the game's own (after 0.1.3).** Four things were wrong, found by measuring a
survival player's jumps tick by tick against the same jumps on the ground (`deck-jump`):

- *Ladders did nothing.* The game decides whether an entity climbs from the block of the world its feet are in, and
  a vessel's blocks are not in the world where the vessel is. `LivingEntityMixin` asks `VesselClimbing` when the
  world's block is not climbable: the feet are taken into each nearby vessel's plot (by the current and the previous
  pose for an entity this side moves, by the recent poses for a player on the server) and the block there is asked
  with vanilla's rules (the climbable tag, an open trapdoor over a ladder).
- *A jump died after its first tick on a vessel that was not square to the world.* `Entity.move` calls every axis on
  which the collided movement differs from the requested one a collision, at any size of difference, and stops the
  entity along it. Turning the whole movement into the vessel's frame and back changed its last bits, so beside any
  vessel block a jump on a heeling deck, or a running jump on a turned one, lost its speed in the air, where nothing
  gave it back (on the deck `afterMove` did). Now only what the vessel took from the movement is turned back and
  taken from what was asked; what it left alone comes back bit for bit.
- *Jumping on a vessel that was going down hurt.* The server adds up a player's fall from the moves the client
  reports, in the world: a jump on a ship sinking through the air at 16 blocks a second counted as a fall of nine
  blocks. The damage also sent the client the server's idea of the player's speed, which cut the next jump short or
  doubled it. `DeckFall` (in `ServerGamePacketListenerImplMixin`) counts the vertical part of a move of a player
  within a vessel's bounds against where the vessel carried the player's last place.
- *The step up worked in the air.* Stepping up onto an edge (0.6 of a block) was allowed whenever the entity was
  carried by the vessel, also in a jump or on a ladder; it is now from the ground only, as in the game.

After the changes a jump on a deck is 1.252 blocks high and 11 ticks long, as on the ground, hovering, under way at
21 blocks a second, climbing, descending, turning and afloat (1.223 on a deck banked 12 degrees), five jumps to five
with the key held, no damage, and a five-block ladder takes 45 ticks on the ground and on the ship.

**The pilot's view turns in the pilot's own tick (after 0.1.3).** The pilot rides the vessel's entity.
`VesselEntity.positionRider` puts their eyes where a standing pilot's would be and keeps their facing relative to the
vessel: it turns them as far as the vessel has turned about the vertical (`VesselPose.yawTurnSinceDegrees`). Up to
0.1.3 that turn was made in `updateFrom`, where the vessel's pose arrives, which is before the pilot's tick (a vehicle
ticks before its passengers, and the server sets the pose at the start of the level tick). An entity's tick begins by
keeping its rotation as the old one, and the camera's yaw in a frame is interpolated from the old rotation to the new
one (`LocalPlayer.getViewYRot`). Turned before the tick, old and new were the same: the view stood still between
ticks and jumped at each one, by up to 2.6 degrees at the full turn rate (0.9 rad/s over 20 ticks), while the vessel
was drawn turning in every frame. At the helm every turn juddered. Vanilla places a rider at the end of the rider's
tick (`Entity.rideTick`), so the turn is made there now, and the frames between two ticks show the turn in between. The
pilot's position was always set there and was never affected. An entity standing on a deck is turned in its own
`move` (`VesselCollisions.carry`), which is inside its tick as well.
Measured in the level turn of `flight-rotation` (179 frames at 60 frames a second, the vessel turning up to 2.57
degrees a tick): the camera's yaw and the vessel as drawn in the same frame turned up to 2.48 degrees apart before
the change and 0.00005 degrees after it.
No test had looked at the pilot's view: the smoothness trace of `multiplayer` records the vessel as drawn, not the
camera. `flight-rotation` now records the camera's yaw and the vessel as drawn in every frame of its level turn
(`SlipwayDebug.viewTraceStart`) and allows them to turn 0.1 degrees apart, and the server GameTest
`InteractionGameTests#thePilotTurnsWithTheVesselWithinTheTick` checks on a headless server that a pilot's facing at
the start and at the end of a tick differ by the vessel's turn in that tick.

### Block events, effects and pistons (0.1.2)

**What was wrong.** Vanilla tells clients about three kinds of things at a block by distance to the block's position:
block events (`ServerLevel.runBlockEvents`: a chest's lid, a piston's stroke, a note block, a bell), level events
(`levelEvent`: bone meal, dispenser smoke and click, block breaking, doors) and sounds, each to the players within 64
blocks (sounds: their own range). For a vessel block that position is in the plot, so nobody was told. 0.1.1 moved
sounds and level events to the block's world position on the server. That was enough for sounds, but block events
were never sent (a chest's lid stayed shut and a piston's stroke was not animated: the client makes the moving
blocks itself from the event, the server sets them with flags that are not sent), and a level event at the world
position cannot work when the client must read the block (bone meal's sparkle takes the crop's shape: at the world
position there is air, so nothing was shown).

**Server.** `ServerLevelMixin` sends block events and level events of a plot position to the vessel's viewers (the
players who have its plot chunks) within 64 blocks of where the block is in the world, with the plot position
unchanged (`VesselManager.sendToViewersNear`). Sounds still go out at the world position (the client needs no block
for them), and entities spawned in a plot still appear in the world.

**Client.** The plot chunk is loaded on the client (`ClientChunkCacheMixin`), so `ClientLevel.blockEvent` finds the
block and runs its `triggerEvent`; block entities in plot chunks tick on the client (the chest's lid, the moving
piston); `VesselRenderer` draws every block entity of the plot, also the moving-piston ones, and `LevelChunkMixin`
marks the mesh dirty when the client's own replay of the stroke changes blocks. What the client then makes at plot
coordinates is put where the vessel is (`VesselEffects`):

- particles made through the level (`ClientLevelMixin` on `doAddParticle`, before the check that drops particles
  more than 32 blocks from the camera): a note, bone meal's sparkle, dispenser smoke, redstone dust at a lever;
- particles added to the engine directly (`ParticleEngineMixin` on `add`): the chips of mining and breaking;
- sounds played through the level (`ClientLevelMixin` on `playSound`, as before) and directly through the sound
  manager (`SoundManagerMixin`): the thud of mining, a jukebox's music.

A particle's position goes through the vessel's pose; its velocity is turned with the vessel and gets what the
vessel moved at that point in the last tick, so it starts at rest relative to the deck. After that it lives in the
world: it does not follow the vessel and does not collide with it.

**Chests.** `ContainerOpenersCounter` looks for the players who have a container open every five ticks in a box
around the block; on a vessel that box was in the plot, found nobody, and shut the lid a quarter of a second after it
opened. `ContainerOpenersCounterMixin` puts the box where the block is in the world.

**Pistons.** They work in the plot as in the world. What Slipway adds:

- The collision shape, mass, block count and bounds follow each stroke (`LevelChunkMixin` marks the shape dirty; it
  is rebuilt at the next exchange). In mid-stroke the plot holds `minecraft:moving_piston` blocks whose collision
  shape is the moved block's, shifted by its progress; `SectionShapes` weighs such a block as the block it moves (an
  iron block weighs as iron while it is pushed). Bounds grow with a push and never shrink.
- Bounds do not grow past the configured largest size (`maxVesselSpan`, the rule assembly applies; 0.1.2, found in
  review). Before, the size was only checked at assembly and bounds grew to the plot's edge, 2,016 blocks out: two
  slime-block flying machines at right angles made a vessel of 15,876 chunk columns, each ticketed, ticked, saved,
  sent to every viewer and walked at every shape rebuild, frame and disassembly. The rule is
  `VesselRecord.fitsSpan`: along each axis the bounds may grow only while they span at most `maxVesselSpan` blocks;
  a position inside the bounds always fits, so a vessel that is larger than the limit (assembled under a larger
  setting, or stretched under 0.1.1) keeps working and only cannot grow. It is applied in three places:
  - A piston does not make a push that would carry a block or its head past it (`PistonStructureResolverMixin`,
    server side; the client follows the strokes it is sent). It does not move, as against an immovable block, and
    nothing is lost. Server GameTest `aPistonAtTheEdgeOfAVesselThatSpansTheLimitDoesNotExtend`: powered by a
    redstone block, the piston moves neither on the neighbour update nor when its stroke event is carried out, and
    with a block of room it pushes; `aVesselDoesNotGrowPastTheLargestSpan` for the edge cases of the rule.
  - A block item is not placed past it (`BlockItemMixin` on `BlockItem.place`, behind `updatePlacementContext`, so
    for the position the item settles on, for players and dispensers): the placement fails as vanilla's does above
    the build height, and the item is kept. The server decides, because the size is its setting and the client does
    not know it; so the client has placed the block and taken the item by itself. Vanilla takes the block back (the
    server does not confirm the prediction); Slipway tells the player why (overlay message) and sends the inventory
    again. Server GameTest `blockItemsAreNotPlacedWhereAVesselMayNotGrow`; client GameTest `interaction` (without
    the resent inventory the client showed 14 of its 15 stones).
  - Whatever else sets a block further out (a bucket of water, a plant growing, water flowing, the far half of a
    bed, a command) is not taken into the bounds, like a block in the plot's margin
    (`VesselManager.onPlotBlockChanged`). Such a block is in the plot but not part of the vessel: disassembly does
    not put it into the world, and since 0.1.3 it is removed when the vessel ceases to exist (see "A plot is freed
    empty" under "Assembly and disassembly"). While the vessel exists, such a block is solid and drawn if it lies in
    a 16-block section the bounds reach into (shape and mesh are built from whole sections). Refusing every such
    change in `Level.setBlock` would be the complete rule; it was left out because it changes what every block
    change in a plot may do, for a case that needs a vessel 512 blocks across.

  With the rule the plot's margin cannot be reached any more: the bounds always hold the helm's place at the plot's
  centre, and the largest setting (2,000) is less than the 2,016 blocks to the margin. The margin checks stay.
- Growing the bounds no longer sends the vessel's chunks to its viewers again; only chunk columns that are new are
  sent (`VesselManager.includeLocal`). Sending a chunk again replaces it on the client, which deletes the moving
  blocks the client has just made from the piston's event (the stroke was invisible whenever it grew the bounds) and
  costs a remesh of the whole vessel for every block placed beyond the bounds. Each new column (of the bounds or of
  the ring around them, see "Which plot chunks viewers get") goes out once: at once when it is loaded, otherwise in
  the tick it has loaded (`ActiveVessel.unsentChunks`; a command can set a block many columns outside the vessel).
- A push that would put the head or a block into the 32-block margin of the plot is refused
  (`PistonStructureResolverMixin`, like vanilla's refusal at the build height): pistons are the one thing that moves
  blocks by itself, and a slime-block flying machine must not walk into the neighbouring plot. A block that gets
  into the margin another way (a command) is ignored by the vessel.
- Disassembly lets every stroke finish first (`VesselAssembly.finishPistonMoves`): a block in mid-move is a block
  entity that knows which way it goes in the plot's frame and cannot be turned with the vessel.

**Left as it is.** Pistons do not push entities on a vessel (the piston looks for them at its plot position; an
entity standing on a pushed block is moved by Slipway's own collision, which pushes it out of the block, not by the
piston). Particles do not follow the vessel after they were made, and do not land on its deck. A jukebox's music
plays where the vessel was when the disc started and stays there. Blocks' ambient effects (`animateTick`: torch
flames, furnace smoke, dripping) are not shown: the client picks random positions around the player for them, never
in a plot. Particles the server sends as particle packets (`ServerLevel.sendParticles`) from a plot position reach
nobody. A push into a chunk column the vessel did not reach before sends that new chunk in mid-stroke, and the
block in it is invisible for the two ticks of that stroke.

### The picture kept at disassembly (0.1.2)

Disassembly puts the blocks into the world and removes the vessel in one step on the server. On the client the
vessel's entity (which the vessel is drawn with) was removed and the world blocks arrived in the same tick, but the
terrain renderer shows new blocks only when it has rebuilt their sections, which it does off the render thread
(Sodium; vanilla's renderer too): for a tick or two neither was drawn, and the ship blinked.

Now the vessel's picture is kept that long:

- Server: the vessel's entity is not discarded with the vessel but retired (`VesselEntity.retire`: it no longer asks
  for its vessel and takes no passengers) and discarded by the manager `RETIRED_ENTITY_TICKS` = 6 ticks later. Its
  plot is freed as before.
- Client: on `VesselGone` without `keepProxy`, `ClientVessels` keeps the vessel as "gone" instead of forgetting it.
  The packet arrives before the packets that drop its plot chunks, so the mesh is complete and is frozen
  (`VesselMesh.freeze`), and its block entities are kept as they were (`ClientVessel.keepPicture`), each with the
  light its renderer drew it with at that moment: a block entity's light is read from the level when it is drawn, and
  the plot's light goes with its chunks (a chest beside a lamp was drawn with block light 0 for those ticks, and one
  under a roof with full sky light; found in review). The `disassembly` test's small ship has both, a chest on deck
  beside glowstone and a chest walled in and roofed over: in the kept picture each is drawn with the light of its last
  draw before (sky 15 and block 14, and 0 and 0, in six draws each; without the kept light two of three draws had
  block light 0 on deck and sky light 15 in the dark). `VesselRenderer`
  draws that picture at the vessel's last poses. It stops when the terrain renderer has had nothing waiting for 2
  ticks, asked every frame (`TerrainProgress`: vanilla's `hasRenderedAllSections`, or Sodium's
  `isTerrainRenderComplete` by guarded reflection, hook `sodium-terrain-complete`), and after 6 ticks at the
  latest. Both answers are about the build queue only (Sodium's is `ChunkBuilder.isBuildQueueEmpty`): the renderer
  starts on the new blocks in its next frame, hands out a limited number of sections per frame, and a section being
  built or waiting to be uploaded is not in the queue. One "nothing waiting" therefore does not mean the blocks
  are on the screen; two ticks without any do. A gone vessel is no vessel for anything else: no collision, no
  picking, no plot.
- Both are drawn together for those two ticks. When the terrain renderer is busy for another reason (new terrain
  streaming in while flying), the picture stays for the full 6 ticks over the blocks that are already there; the two
  differ by the snap to the block grid at most. A rebuild that takes longer than 6 ticks would still show the ship
  late in places; that was not seen (for a 2,080-block ship the terrain renderer was last busy in the tick of the
  disassembly with all build threads, and one tick later with a single one).

Measured by the client GameTest `disassembly`: the frame on the screen before each of the twelve ticks following a
disassembly (copied with the game's own screenshot copy, which skips no tick; a test screenshot takes several) is
compared with the picture before, for a 58-block ship, for a 2,080-block carrier, and for the carrier with Sodium
limited to one build thread. With the picture kept the ship is whole in every frame of every run (largest
difference 3% of what a missing ship makes: the blocks' lighting) and the gone vessel is drawn for 2 or 3 ticks. With it turned off (`ClientVessels.keepGoneVessels`, for this test) the ship is missing from one
of the twelve frames in most runs and from none in some: the gap is about a tick long there and does not always
cover the frame at a tick's end. In free-running play the film agent measured two ticks without the ship (four and
eight in other runs of its 2,503-block galleon under shaders at film resolution, where frames are slow).

What still changes in the picture at that moment is light. The placed blocks are shaded by the world's light and the
vessel by its own, and a vessel changes no light where it flies (its blocks are in the plot): the film agent saw the
water under the hovering galleon evenly lit, and a dark patch under the hull from the first frame in which it was
blocks again (Bliss darkens by sky light, which the placed blocks lower in the columns below them). Not measured
here; it is the same in 0.1.1.

The mirror problem at assembly (the vessel can be drawn incomplete for a tick or two while its plot chunks arrive)
has a different cause and is not changed: the world blocks are removed by block updates in one tick, and the
vessel's mesh needs the plot chunks, which come as chunk packets within the following ticks. Keeping the world
blocks drawn until the mesh is complete would mean holding back block updates of the terrain renderer.

### What is proven to work on a moving vessel (0.1.2)

"Everything keeps working" is the claim; this is what tests hold it to. Server GameTests run the vessel rising,
sinking and turning inside its arena; client GameTests fly it.

| What | Test |
| --- | --- |
| Lever, redstone lamp, door, chest contents, placing and mining in vessel space | client `interaction` (since 0.1.0) |
| Chest lid opens for the player, stays open, closes | client `block-events`; server `BlockEventGameTests.aChestOnAVesselStaysOpenWhileItsUserIsAtTheVessel` |
| Piston pushes a block (bounds grow), sticky piston pulls it back, shown as a moving block on the client | client `block-events`; server `aPistonOnAVesselMovesItsBlockAndTheBooksFollow` |
| Sticky piston with a slime block moving three blocks, vessel rolled 25 degrees and under way | server `aStickyPistonWithSlimeWorksWhileTheVesselFliesRolled` |
| Piston at the plot's edge refuses; disassembly in mid-stroke | server `pistonsRefuseToPushOutOfThePlot`, `disassemblingInMidStrokeFinishesTheStroke` |
| Repeater clock (two 4-tick repeaters) driving three lamps through repeaters, exact timing over 120 ticks | server `MachineGameTests.aRepeaterClockLightsARowOfLampsInTurnWhileTheVesselFlies` |
| Dispenser with bone meal on wheat: growth, sparkle, smoke and sounds at the vessel | client `farm`; server `aFarmOnAFlyingVesselTakesBoneMealAndGrowsByItself` |
| Random ticks in the plot: wheat grows by itself, farmland is wetted by a waterlogged slab and stays farmland, the water stays in its block | server `aFarmOnAFlyingVesselTakesBoneMealAndGrowsByItself` (1,000 ticks at the normal random tick speed) |
| Hopper into chest, observer into lamp, dropper (its item appears at the vessel), note block (sound and note at the vessel) | server `machinesOnAFlyingVesselWorkAndTheirOutputAppearsAtTheVessel`; client `block-events` (note block) |
| Mining: chips and thud at the block | client `block-events` |

Not proven or known not to work: fluids outside waterlogged blocks (water and lava source blocks are not
assembled; a waterlogged block whose water can flow out sideways will pour it into the plot, which was not tested);
pistons pushing entities; ambient block effects; anything that looks for entities or players near a block's plot
position and is not listed above (beacons, conduits, spawners, bells ringing mobs, sculk sensors); a world border
smaller than 24 million blocks would stop blocks in the plots from working (read from the code, not tested).

### Distant Horizons

Optional (`DhProxyBridge` only touches DH classes when it is loaded). The server keeps, per vessel, its exposed
blocks with map colours (`VesselProxies`) and sends them to players within `proxyRange`, plus pose updates whenever
the vessel moved or turned since the last one that player got. `DhProxies` registers one DH box group per vessel
through DH's public generic-rendering API, moves it every frame, re-lays it out after more than a degree of rotation,
and turns it off where the real vessel is drawn. After assembly and disassembly the client asks DH to rebuild the
LODs of the world chunks involved (`overwriteChunkDataAsync`): on a client connected to a server DH only rebuilds a
chunk when it loads or the local player edits it, so otherwise a ghost of the ship stayed at its build site (and hid
the real ship until it moved). No DH fork change was needed.

**A bright patch of sea around glass in water, under shaders (after 0.1.3; not Slipway's bug, worked around).** With
Distant Horizons 3.3.4 and Iris 1.11.6 on Minecraft 26.2 or later, the water of a whole chunk looked like a mirror of
the sky wherever the chunk held a glass block in water: around the moored submarine with its windows, and around a
single glass block put into the sea of an untouched chunk. Iris turns back-face culling off with a plain GL call
before each Distant Horizons render pass (`LodRendererEvents`, its handler of `DhApiBeforeRenderPassEvent`). Distant
Horizons turns it back on (its own workaround for Iris issue 2582), but on these versions with a shader pack in use
it makes no plain GL calls (`MinecraftGLWrapper.runDirectGlCall`) and goes through Minecraft's state cache, which
still says "on" and so does nothing. Culling stays off in GL for the rest of the frame while the cache says it is on.
Sodium draws a chunk section whose translucent faces must be sorted together (water with side faces, as against
glass) with the faces of all directions in one draw; with culling off the underside of the water's surface is drawn
too, and the pack shades it as a mirror. `DhCullRepair` (registered when both mods are present) turns culling off and
on through the cache after Distant Horizons' pass (`DhApiAfterRenderEvent` and `DhApiBeforeRenderCleanupEvent`),
which makes GL and the cache agree. Measured with `diag-water-patch`: at the end of the main pass GL had culling off
in 89 of 89 frames without the repair and in none with it, and the patch is gone in both cases. `GlStateCheck`
(scenario `render-iris`) now compares the culling flag as well. The fix belongs in Distant Horizons or Iris; it is
noted for the fork.

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
| Which air a hull keeps dry, and where water runs in | `Hull` | Priority flood with a bucket per level (R. Barnes, C. Lehman, D. Mulla, "Priority-flood: An optimal depression-filling and watershed-labeling algorithm for digital elevation models", 2014), in three dimensions, keeping each cell's pour point |
| Lift, element by element | `Buoyancy` | Archimedes' principle; linear and quadratic drag (textbook) |
| A few places of many, each as likely | `Buoyancy` (waterline places) | Reservoir sampling (J. Vitter, "Random sampling with a reservoir", 1985, algorithm R) |
| Depth-only patch that keeps water out of a hull | `WaterMask` | The technique of vanilla's boat (its water patch and `RenderTypes.waterMask()`), with our own geometry |

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
  core `5e93372c4`; never pushed, kept as the local tag `archive/slipway-leak-fix`), patches L1-L8 in its
  `PATCHES.md`, with core unit tests for the injector
  (`testWorldGeneratorUnbindReleasesTheLevel`, `testConcurrentWorldGeneratorBinding`, the latter failing on the old
  map). The build `3.3.1-tellus-fork.6-leakfix.9` was used by the client GameTests (`devmods/test`) and the test
  instance, and since 0.1.1 by the play instance (the player's choice); the client GameTests now use its successor
  `...-leakfix.9-irisfix.1` (below, "Dark blotches"). The same work fixed a DH thread leak (one "World Gen Progress Updater"
  thread per level per world) and a DH bug that dropped every block-use packet when a client hosts a dedicated server
  in-process.
  Since the evening of 2026-09-30 the fork is rebased onto official 3.3.4 and published as
  [`3.3.4-tellus-fork.7`](https://github.com/TimStewartJ/distant-horizons/releases/tag/3.3.4-tellus-fork.7). It
  carries the versions of these fixes prepared for upstream (A-G in its `PATCHES.md`) instead of
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

0.1.2: in the runs for the release these threads were seen more often and in sixes (two timers for each of the three
levels; in `devmods`' fork.7 jar neither generator's bytecode calls `Timer.cancel`). Their tasks run 5 s after the
last chunk DH generated, so a second after a world closes they are there whenever DH generated a chunk in that
world's last seconds, and they end at the first collection after those tasks. Counts at the ends of the five cycles:
0, 6, 0, 0, 0 in one run, none in another, and 0, 0, 6, 6, 12 in a third, which the leak check's rule for a growing
thread group (see below) took for growth and failed. The rule now counts again what rose, eight seconds after the
last cycle and after collections, and fails for what is still there. Tried both ways with threads made for the
purpose: six that end after 6 s, started after cycles 3 and 5, are let through; two per cycle that never end fail it.

**The strict check** is the client GameTest `leak` (`src/clientGametest/.../LeakScenarios.java`): the same saved
world with three flying vessels is opened and closed five times without shaders and five times with Bliss. After
every close and full GCs it requires every earlier cycle's `IntegratedServer`, `ServerLevel`s and `ClientLevel` to
be collected (weak references to those exact objects; with Bliss only the newest cycle's `ClientLevel` may stay, as
explained in chain 6), no live `IntegratedServer` or `ServerLevel` at all, no live jolt-jni `PhysicsSystem`,
Slipway's Jolt engines, bodies, level managers and client vessels at zero, Iris's override cache empty, heap after
GC growing under 16 MB per cycle, no thread group that keeps growing (Netty's local event-loop group is one static
pool of at most two threads per core, started lazily: it gains three threads per world opening, the only group that
grows, and the report lists every group that changed; a group that rose at the cycles' ends is counted again eight
seconds after the last one, because a thread that ends by itself is no leak), and without shaders native memory
growing under 64 MB per cycle. Any surviving world writes a heap
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

**Fixes** (Distant Horizons fork, local branch `slipway-iris-fixes`; never pushed, kept as the local tag
`archive/slipway-iris-fixes`; see its PATCHES.md):
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

**Resolution (2026-09-30, evening).** The fork is rebased onto official 3.3.4 and published as
[`3.3.4-tellus-fork.7`](https://github.com/TimStewartJ/distant-horizons/releases/tag/3.3.4-tellus-fork.7), which
contains upstream's blend fix instead of the backport I1, and keeps I2. With it: the diagnostic on the copy of the
player's world measures
121.7 / 121.6 / 121.8 / 121.8 (`E:\slipway-e2e\diag\run20-fork7-userlods`); `render-iris` passes with the cache in
sync at every sampling point and the unchanged reference images (near 4.0e-5, far 4.4e-5); no run logged Iris's
message. It is installed in the play instance and in the Tellus instances.
These checks ran on a local build of the same sources (Fabric 26.3 jar SHA-256 `BCF32F99...FEF10`). The instances now
hold the published jars (`EF401FD5...6CF6` for Fabric 26.3), which were not started again: every class in them is
byte-identical to the local build, and they differ only in line endings of 66 text files and in the embedded commit
id (`E:\slipway-e2e\runs\dh-fork7-release-check-20260930-2320\report.json`).

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
| Unit tests (JUnit) | pure logic and jolt-jni (poses, boxes, controller and holds, records, engine lifecycle with Debug natives, loose cargo on a carrier in the real engine) | `src/test` | under a minute |
| Server GameTests | assembly, physics, interaction, packets, loose vessels, water, the survival rules, block events and pistons, redstone, a farm and machines inside a headless server | `src/gametest`, `runGametest` | ~45 s for 68 tests (the farm test runs 1,000 ticks) |
| Client GameTests | every in-game scenario on a real client with the play stack | `src/clientGametest`, `runClientGametest` | ~12 min (2-minute soak, as in `check`); ~30 min with the 20-minute soak |
| Packaged-jar check | the release jar with the exact play-stack jars in production Minecraft: every mixin applied, a vessel assembled, a chest on it opened by a block event, a vessel set loose | `src/packagedCheck`, `runPackagedJarCheck` | ~30 s |

**Server GameTests** run all at once, each in its own arena: `slipway:arena`, 16 blocks each way, which the
framework closes with barrier blocks (walls, floor and ceiling). Vessels collide with barriers like with any block,
so a vessel in a server GameTest flies inside its box (rising, sinking, turning) and cannot reach the test beside
it. Blocks changed in one arena can still wake a loose vessel sleeping in the next (terrain sections are shared).
One test needs room (a turn at full thrust is 30 blocks across): it moves its two ships 60 and 100 blocks above the
arenas with `VesselManager.teleport` and force-loads the chunks under their flight, because a vessel is unloaded
with the chunk its entity is in; the runner releases forced chunks at the end of the batch, and the test removes its
ships.
Mock players (`makeMockServerPlayerInLevel`, and `BlockEventGameTests.spy`, which keeps the channel to read what was
sent) get packets written during a tick only at the end of that tick, after the test code of that tick has run.
Every run starts in a new world, as on CI (`runGametest` deletes `build/gametest/world` first): the world of an
earlier run holds that run's vessels, its registry with the freed plots, and whatever the code it ran left in them.
The runner puts the arenas at a random place, eight to a row, in an order that differs from run to run, and runs at
most 50 tests at a time (the rest follow as a second batch), so a test must not depend on what is beside it. One did:
a vessel's entity sits at the middle of its bounds, and a block set 100 blocks out moved it 50 blocks, into the arena
of another test or, from the last arena of a row and depending on where the chunk borders fell, into chunks that are
not loaded, where the vessel unloaded (`columnsOfABlockSetFarOutsideAVesselReachItsViewersOnceLoaded`; it now sets
a block on either side, which leaves the middle where it is).
A check that fails inside a step of a test sequence does not end the test at once: the sequence runs for one more
tick, and the failure that is reported is the last one.

**Client GameTests** (`fabric-client-gametest-api-v1`, shipped in Fabric API 0.160.7+26.3). One entrypoint runs nineteen
scenarios (`SlipwayClientGameTests`; 0.1.2 added `small-vessel-light`, `loose-cargo`, `block-events`, `farm` and
`disassembly`; `afloat` came with the water, see "Water: floating, sinking and dry hulls"; `deck-jump` with the
survival pass, see "Interaction and riding"); each starts
at the title screen with default options, a failure is recorded
and the next scenario still runs, and the run fails at the end if any failed. Reports:
`build/client-gametest/TEST-slipway-client-gametest.xml` (JUnit) and `results.json` (every measurement, note and
evidence path); screenshots under `build/client-gametest/screenshots/<scenario>`. Options:
`-PslipwayClientGametestOnly=a,b`, `-PslipwaySoakMinutes=20` (the release soak; the default in `check` is 2),
`-PslipwayRecordTemplates=true` (write missing reference images; otherwise a missing one fails),
`-PslipwayClientGametestMods=sodium,iris,dh` (subset of render mods), `-PslipwayTestDhJar=<jar>`,
`-PslipwayTestDhConfig=<file>` (a Distant Horizons config to start from; otherwise its defaults).
The run uses Sodium, Iris with Bliss (copied into the run directory; shaders are switched on through Iris's API where
a scenario needs them) and the play stack's Distant Horizons from `devmods` (`3.3.4-tellus-fork.7`), unless
`devmods/test` holds another build to try (`tools/setup-devmods.ps1 -TestDhJar`; this is how the patched
`...-leakfix.9-irisfix.1` was tested before fork.7). Every run starts
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
- A client under Fabric's client GameTest never makes up ticks: the harness limits it to one tick per frame, where a
  normal client runs up to ten to catch up after a long frame. A second client that has just joined sets up its
  renderer in long frames (Distant Horizons compiles its shaders then), so its game clock, and with it the pose
  playback, is left behind the server's poses: measured 5 to 8 ticks behind when the vessel first shows and in step
  again only 80 to 100 ticks later (playback runs at most 10% fast), or with a jump when it gets more than 10 ticks
  behind. The multiplayer scenario used to start its smoothness trace as soon as the watcher had the vessel, in the
  middle of that; it passed while the lag stayed under 10 ticks and failed the first 0.1.2 release build when it did
  not (one jump of 11.5 ticks, 1.6 blocks, eight ticks into the trace). The watcher now reports ready once its
  playback has been in step for 20 ticks (`ClientVessel.playbackLag`), and the scenario records how long that took.
- A client has a vessel from the first packets about its entity; the vessel's body is made when its plot chunks have
  loaded. With another game busy on the machine (a Prism instance using 13 of 20 cores) the chunks came later, and
  the leak scenario, which asserted that physics runs as soon as the client had the three vessels, found no engine
  and no body. It now waits for them (up to 400 ticks), as the collision, loose-cargo and save scenarios always did.
  The lock that keeps the film renders apart from these runs does not cover other games on the machine.

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
