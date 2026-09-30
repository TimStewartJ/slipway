# Slipway overnight work log

Running state for the overnight follow-up (started 2026-09-29 22:40 PT). Updated after every meaningful step so the
work can resume exactly after a context summary. Newest entries at the bottom of each section.

## Rules for this run (from the user)

- Unattended: never ask, never wait. Decide, record the reasoning in DESIGN.md, continue.
- Commit at every green step. No push, no publish, no GitHub repo.
- Do NOT touch the play instance `Slipway-MC-26.3-Fabric` or its "Slipway Sandbox" world. Only the Slipway-Test
  instances, `E:\slipway-e2e` and Gradle runs.
- Small images only (contact sheets, crops). No window focus. Don't touch the Tellus/Expeditions repos or harness.
- Never weaken an acceptance check; root-cause failures.
- DH fixes go on a new local branch of `E:\distant-horizons`; that build is used only in test instances and the harness.

## Goals

1. Leak RCA and fix: isolation matrix, exact reference chains (heap dumps + paths to GC roots), fixes at the source,
   a strict leak check (fails on any retained-world growth), RCA in DESIGN.md.
2. Move all in-game testing to Fabric client GameTests (fabric-client-gametest-api-v1 in FAPI 0.160.7+26.3): own
   source set + run + JUnit report in `check`; port every Prism scenario with equal/stronger assertions; reference
   images; packaged-jar check; retire Prism scenarios after 3 consecutive green runs; runtime + flakiness numbers;
   clean validation.json (no pending-review; superseded entries).
3. Docs: Bridge `slipway/design`, `slipway/first-playable-prompt`, tellus-e2e SKILL.md section, new Bridge doc
   `modding/minecraft-testing-strategy`.
4. Finish: clean-commit full build, every test level green, processes stopped, Bridge task updated, final report.

## Current goal

Goal 1 wrap-up (matrix4 = dhfix3 running; then leakfix.4 matrix) in parallel with Goal 2 code (client GameTests).

## Goal 2 progress (client GameTests)

- All 12 scenarios written in src/clientGametest (Assembly, Flight, Deck, Interaction, Collision, Packet, Save, Render,
  Multiplayer + Watcher/WatcherProcess (second JVM via java @argfile, file-based commands), Perf, Leak, Soak) plus
  Game/Flight/Ships/Shots/Report/Check/Lifecycle/ServerPoses/ServerWarnings. Compiles. checkPatches extended:
  clientGametest:<scenario> test ids, "hooks" section (slipway-hook markers); patches.json scenario: -> clientGametest:.
- Production changes: ClientVessel.playbackTick(), DhProxies.proxyState(id), GameRendererAccessor (@Invoker
  shouldRenderBlockOutline) so vessel outlines follow vanilla's rule (F1/adventure/outline switch) - bug found by
  the new outline test design.

- build.gradle: `clientGametest` source set + loom mod `slipway_client_gametest` + run `clientGametest`
  (runDir build/client-gametest, `--username SlipwayTester`, ZGC, 8G, `-Dslipway.clientGametest.only=`,
  `-Dslipway.clientGametest.soakMinutes=` (default 2), templates recorded only with -PslipwayRecordTemplates=true, so
  a missing reference image FAILS in `check`); `prepareClientGametestRun` copies Bliss and writes a shaders-off
  iris.properties; `check` depends on runClientGametest. Test DH jar: devmods/test/DistantHorizons-fabric-*.jar
  (leak-fixed build) else fork.6; -PslipwayClientGametestMods=sodium,iris,dh selects render mods.
- src/clientGametest: fabric.mod.json (entrypoints preLaunch = SDL no-activate hints, fabric-client-gametest),
  SlipwayClientGameTests (single entrypoint, per-scenario isolation + recovery, report after each scenario),
  Report (TEST-slipway-client-gametest.xml + results.json), Check, Game (worlds/options/keys/vessel views/recover),
  Ships (mixed ship, decks). Scenario classes still to write: Assembly, Flight, Deck, Interaction, Collision,
  Packet, Save, Render, Multiplayer (+ Watcher second JVM), Perf, Leak, Soak.

## Open hypotheses

- H5: process private memory still grows ~30-75 MB/cycle in some DH runs even with flat heap objects: leaked
  threads (DH timers) and/or native buffers. Re-measure with leakfix.3.
- H3: Slipway classes that grew (VesselCollisions$Contact, VesselRegistry) should drop to 0 once worlds are freed.
- Matrix4 (dhfix3) so far: 0 retained servers everywhere; full ClientLevel flat at 1 (GlDhTerrainShaderProgram
  static, fixed in leakfix.4); sodiumIris-bliss (no Slipway) ClientLevel 1..5 = Iris leak confirmed independent of
  Slipway/DH. "fail" results are the heap-per-cycle check tripping on first-cycle warm-up (histogram 564->655 then
  +15,+3,+2): the strict check must measure growth after a warm-up cycle.

## Next step

Finish matrix4; run the leakfix.4 matrix (full x3, full-bliss x2, dhOnly x2 with dumps; controls). Meanwhile write
the client GameTest scenarios (needs the scenario assertion inventory from the explore agent).

## Done (evidence)

- Tools: `tools/e2e/scenarios/leak-new.ps1` (named mod configurations, cached Slipway-free "plain" world, strict
  checks, optional heap dump), `leak-matrix.ps1` (configs x runs, summary.md/json), `tools/e2e/mat.ps1` (Eclipse MAT
  1.17.0 batch queries; MAT in E:\slipway-e2e\tools\mat with `-vm ...\server\jvm.dll` so query quoting survives).
- Matrix pass 1 (plain world, 5 cycles, release jar 58EF0E77), `E:\slipway-e2e\runs\leak-matrix-before`:
  full, stackNoSlipway, dhOnly: IntegratedServer 1..5, ServerLevel 3..15, ClientLevel 1, heap +125..+160 MB/cycle.
  noDH, sodiumIris: 0 servers, 0 levels, 0 client levels, heap flat (~+2 MB/cycle). => DH is necessary and
  sufficient; Slipway, Sodium and Iris are not involved.
- Chain A (heap dump full-plain-r1.hprof, MAT path2gc + thread_details): IntegratedServer <- ServerLevel.server <-
  <Java Local> of "DH-World Gen Thread[n]" parked forever in ServerChunkCache.getChunk(...).join() <-
  LevelReader.getNoiseBiome <- BiomeManager.getBiome <- MaterialRuleContext.getBiome (surface rules) <-
  NoiseBasedChunkGenerator.buildTerrain <- DH StepTerrain.generateGroup, which passes
  GlobalWorldGenParams.biomeManager = new BiomeManager(mcServerLevel, ...) (live ServerLevel as biome source).
  Vanilla ChunkStatusTasks.buildTerrain passes region.getBiomeManager(). Off-thread getChunk hands a task to the
  server thread and joins; after the server stops nothing runs it, so the thread parks forever holding the world.
  Introduced upstream in DH commit fd9b4f151 "26.3 world gen fix" (StepTerrain replaced StepSurface for 26.3).
- Chain B (source + earlier JFR): static WorldGeneratorInjector.INSTANCE.worldGeneratorByLevelWrapper HashMap gets
  an entry per server level (ServerLevelModule.LodRequestState.createRetrievalQueue -> bind); no unbind, clear()
  has no callers. Same unsynchronized HashMap is written by per-dimension ticker threads concurrently => the
  intermittent "WorldGeneratorInjector.bind ... HashMap.get(Object) is null" NPE seen in the play instance.
- DH fix (uncommitted, branch slipway-leak-fix): StepTerrain uses worldGenRegion.getBiomeManager();
  WorldGeneratorInjector -> ConcurrentHashMap + unbind(level) + boundLevelCount(); ServerLevelModule.close() unbinds;
  DependencyInjectorTest: unbind + concurrent binding tests; version 3.3.1-tellus-fork.6-leakfix.1.

- DH fix iteration 2 (leakfix.2, heap dump leak-matrix-dhfix1-full-bliss-plain-r1.hprof, MAT thread_details):
  Chain A2: DH-World Gen Thread parked in IOWorker.isOldChunkAround(..).join() <- WorldGenRegion.isOldChunkAround <-
  Blender.of(region) <- DH StepBiomes.generateGroup. DH's world-gen close cancels CompletableFutures (cancel(true)
  does not interrupt), returns, vanilla then closes the level's IOWorker; a batch still running waits forever.
  Fix: DhChunkGenerator counts running batches (generateChunks wrapper), close() refuses new ones and waits up to
  15 s for running ones. DH closes levels synchronously from Fabric's ServerLevelEvents.UNLOAD, which fires just
  before vanilla ServerLevel.close() in stopServer, so the IOWorker still answers during that wait.
  Chain A3: static ThreadLocal ThreadWorldGenParams.LOCAL_PARAM_REF on pooled DH world-gen threads (param.level =
  ServerLevel) + static previousGlobalWorldGenParams (only read on MC < 1.19.2). Fix: per-level
  GlobalWorldGenParams.threadParams map (cleared on close); static assigned only on 1.18.2..1.19.1.
- DH fix iteration 3 (leakfix.3, dump leak-matrix-dhfix2-full-plain-r1.hprof): Chain C: static ClientApi.RENDER_PARAMS
  (-> dhClientLevel -> DhClientServerLevel -> ServerLevelWrapper -> ServerLevel -> IntegratedServer) and
  ClientApi.RENDER_STATE.clientLevelWrapper (-> ClientLevel): last frame's state never cleared. Fix:
  ClientApi.clearLevelReferences() from SharedApi.setDhWorld(null). Chain D: static FullDataPayloadSender.UPLOAD_TIMER
  keeps a SCHEDULED task of a player state that was never closed (AbstractDhServerWorld.removePlayer returned early
  before unregisterLeftPlayer when the player's level was already gone; world close never swept states;
  registerJoinedPlayer replaced states without closing) -> session -> player -> connection -> IntegratedServer.
  Fix: unregister first, closeAll() on world close, close replaced state; TickTask drops its sender on close + purge.
  Also: DhChunkGenerator.chunkSaveIgnoreTimer (one Timer thread per level, never cancelled: 13 threads after 5
  cycles) cancelled on close; schedule after cancel guarded.
- Iris chain (sodiumIris-bliss, no DH, no Slipway; leak-matrix-dhfix1-sodiumIris-bliss-plain-r1.hprof):
  RenderSystem.iris$overrides (static IdentityHashMap from Iris MixinShaderManager_Overrides, never cleared) ->
  ExtendedShader (old pipeline programs) -> CustomUniforms -> ... -> IntCachedUniform.supplier -> lambda in
  IrisExclusiveUniforms$WorldInfoUniforms capturing the ClientLevel (addWorldInfoUniforms captures
  Minecraft.getInstance().level). One ClientLevel (~40 MB) per world opened with a shader pack. Not our code.
  Slipway mitigation: ClosedWorldCleanup (client, END_CLIENT_TICK when the level becomes null) clears
  iris$overrides via guarded reflection (Iris rebuilds entries on demand; nothing is overridden without a world)
  and calls LevelRenderer.clearVisibleSections() (vanilla chain below).
- Vanilla chain (vanilla/slipwayOnly configs, bounded: 1 ClientLevel, not growing): Minecraft.levelRenderer ->
  LevelRenderer.visibleSections -> RenderSection.lastCompileTask -> RenderSectionRegion.level -> ClientLevel.
  LevelExtractor.setLevel(null) only flags shouldResetLevelRenderData; resetLevelRenderData() (which clears the list)
  runs in the next level extraction, i.e. when the next world renders. Sodium replaces that renderer (0 retained).
- Matrix dhfix1 (leakfix.1): full 3,3,3,6,9; full-bliss servers 3,3,3,3,6 / client levels 1..5;
  sodiumIris-bliss & noDH-bliss client levels 1..5 (Iris). Matrix dhfix2 (leakfix.2): full 3,3,3,6,6 (x2),
  dhOnly 3,3,6,6,9 / 3,3,3,6,6, stackNoSlipway 3,3,3,3,3 (x2; r2 failed private memory +75 MB/cycle).
- Matrix dhfix3 (leakfix.3 + Slipway dev jar 86D26A1B with ClosedWorldCleanup): full -> 0 retained servers, heap
  +6.7 MB/cycle; dhOnly 0 servers; full-bliss 0 servers + ClientLevel flat 1 (Iris mitigation works).
- DH leakfix.4 (687C6566, built, untested): GlDhTerrainShaderProgram.BEFORE_BUFFER_RENDER_EVENT_PARAM.clientLevelWrapper
  cleared after the event (the last static holding a ClientLevel in DH).
- NMT matrix (leak-matrix-dhfix4, 8 cycles; NMT flag must go INSIDE Prism's quoted JvmArgs): growth is outside the
  JVM (NMT non-heap flat). full +32 MB/cycle, dhOnly +31, sodiumIris +8, full-bliss +194, sodiumIris-bliss +162
  (no Slipway/DH: Iris/driver). Java threads +6/cycle = 3 "Netty Local IO" (vanilla static EventLoopGroupHolder.LOCAL,
  lazily started, bounded 2 x cores) + 3 "DH-World Gen Progress Updater" (DH leak: AbstractLodRequestState's
  single-thread pool never shut down). ZGC heap is NOT in Windows private bytes (committed 6-8 GB > private 1.6 GB).
- leakfix.5/6/7: progress-updater shutdownNow + volatile flag + closed flag (race: tick loop restarted it after close
  -> RejectedExecutionException logged, seen in dhOnly); GlGenericObjectRenderer / BlazeDhGenericObjectRenderer /
  BlazeDhTerrainRenderer static event params cleared (MAT: last ClientLevel held by GlGenericObjectRenderer.EVENT_PARAM,
  used by Slipway's DH proxies). leakfix.7 SHA 76DD73BD, in devmods/test and E:\slipway-e2e\dh.
- Matrix leak-matrix-dhfix6 (leakfix.6 + dev jar 86D26A1B): full/vessels r1+r2: servers 0, ClientLevel 0, heap
  +2.6..2.9 MB/cycle, native +17..55 MB/cycle (noisy, DH), threads only Netty (bounded) + JIT compiler threads.
  full-bliss: ClientLevel constant 1 = Iris's current pipeline (static Iris.pipelineManager -> pipeline ->
  CustomUniforms -> WorldInfoUniforms lambda -> ClientLevel); Iris rebuilds pipelines only on dimension change, so
  bounded; not mitigated (destroying it would force a Bliss recompile on every world join).

## Open hypotheses (old)
