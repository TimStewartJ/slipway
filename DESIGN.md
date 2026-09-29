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
| Physics | Jolt Physics through jolt-jni 6.1.1 (MIT), double-precision flavour, behind `dev.timstewart.slipway.physics.PhysicsBackend` |
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

(Filled in as milestones land; see sections below.)
