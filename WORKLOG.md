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

Series5 GREEN at 2485c6a: 3 consecutive FRESH-run-dir full runs with the 20-min soak, 36/36 passed (1702/1719/1699 s;
E:\slipway-e2e\cgt\series5-fresh). DESIGN.md updated (runtime/flakiness with the full history, perf over 6 runs, soak
criterion evidence, DH ChunkSaveIgnoreTimer observation). Failed first clean build reconstructed into
E:\slipway-e2e\cgt\clean-build1-failed-assemble-race (results rebuilt from its log).
Next: commit (E), final `gradlew clean build` -> E:\slipway-e2e\cgt\clean-build2 (results, junit, log, screenshots,
packaged-check report, summary), recorder: --superseded series0 --superseded series1 --superseded series2
--superseded series3-perf-1280 --superseded clean-build1-failed-assemble-race series5-fresh clean-build2; commit;
Bridge task; final report.

## Previous state (chain 8)

Chain 8 found and fixed: jolt-jni PhysicsSystem static map (va2ps) kept every closed engine's PhysicsSystem (1..8 per
cycle in dhfix6 histograms; bytecode: only forgetMe() removes). Fix: JoltEngine.close()/JoltSelfTest call forgetMe().
Unit test JoltEngineTest.closingAnEngineReleasesItsPhysicsSystemFromJoltJni (fails without fix, passes with); leak
client GameTest requires live PhysicsSystem == 0 (verified 0 every cycle); per-group thread counts recorded (only
Netty Local IO grows, +3 per world, cap 2 x 20 cores). JFR "Render thread" roots explained in DESIGN (statics via
KnotClassLoader; 32/33 world paths via WorldGeneratorInjector.INSTANCE; no Slipway/jolt class on sampled paths).
Series4 (fresh) aborted at run 1 (passed through multiplayer) because of the production-code fix.
Next: commit (D), series5 = 3 consecutive FRESH full runs with the 20-min soak, then record (series0-4 superseded,
series5 current), final clean build + record, Bridge task, report.

## Earlier state

FINAL BUILD at 13611e2 FAILED once: assemble-mixed "Screenshot does not contain template" (fresh run dir). Root cause
(verified with per-frame data, E:\slipway-e2e\cgt\still-diagnosis): the reference picture was taken 5 ticks after the
teleport while far terrain (vanilla chunk edge + DH LODs) was still arriving (frames 10 ticks apart differ by up to
0.0147 MSD; limit 0.004); matchShot asserted on a second frame (Fabric takes its own) so the failing frame was lost.
Also found: DH draws its own LOD clouds (vanilla cloud option does not cover them), so sky views never settle.
Fixes: Shots.waitStill (identical pictures 10 ticks apart) before compared pictures (assemble-mixed, render-iris
outline), picture checks assert on the saved frame, DH clouds off via DH config API in Game.applyTestOptions.
Verified: assemble-mixed + render-iris pass (outline settles in 30 ticks; Bliss templates MSD 0).
Next: commit (C), series4 = 3 consecutive FRESH-run-dir full runs with the 20-min soak (series.ps1 -Fresh), then
record (series2 superseded by series4 as the current series), final clean build, Bridge task, report.

## Previous goal state

Series2 GREEN: 3 consecutive full client-GameTest runs with the 20-minute soak at fb16469, 36/36 passed
(1689/1686/1684 s; E:\slipway-e2e\cgt\series2). Perf changed afterwards to measure at 1280x720 (M7 window size):
series3-perf-1280, 3/3 passed (drop 8.5/12.9/5.0%). Screenshots now cleared per scenario at start (Shots.clear).
Prism scenarios retired to tools/e2e/legacy (README maps each to its client GameTest); e2eWatcher run removed.
Done: commit A = 721b7ba; series3 summary noted; recorder run (175 entries, 0 pending, commit B). Was: commit (A), set series3 summary Note with A, run the recorder (--superseded series0 --superseded series1
series2 --replacing series3-perf-1280), commit validation.json (B), final `gradlew build` at B, record the final
build as its own series + packaged check (C), stop processes, Bridge task, final report.

## Done since last update

- Tick-time criterion rework: TickTimes (every tick; worst 100-tick average, p95, worst, worst 5 with save
  attribution via Lifecycle.SAVE_TICKS from BEFORE_SAVE), used by soak and perf. soak (6 min) passed: mean 0.92,
  p95 1.79, worst 23.3 (autosave), worst avg100 2.09. perf passed: flying mean 0.68, p95 0.77, worst 1.83.
  Commit fb16469.
- Series2 run 1 (fb16469): 12/12 passed in 1689 s. Soak 20 min: mean 0.78, p95 1.30, worst avg100 1.38; the four
  worst ticks are exactly the four autosaves (37.1 ms @6000, 20.8 @18000, 18.4 @12000, 17.4 @24000), next 12.4 ms.
- Series2 runs 2 and 3: 12/12 each (1686 s, 1684 s); autosaves again the four worst ticks (37.2 / 37.1 ms first).
- Series3 (perf only, 1280x720, working tree fb16469 + perf/Shots changes): 3/3 passed, 120 s each.
- Recorder rewritten (tools/e2e/record-client-gametests.py): --superseded series, run ids from startedAt, commit from
  summary or git, per-run screenshots, idempotent; dry run OK (137 entries, 0 pending).
- Deleted 15 intermediate heap dumps + 91 MAT index files (15.7 GB); kept leak-matrix-before-full-plain-r1.hprof and
  leak-matrix-dhfix6-full-vessels-r1.hprof and every MAT text report (*_Query.zip).
- Commits: 33304f8 packaged-jar check (runPackagedJarCheck passes: exact play stack, audit, 0 errors, vessel
  assembled in production); f0293d0 assemble-mixed waits for the complete mesh (series0 run 1 failed once: MSD 0.0078
  with 156/928 vertices, a test race) + DESIGN.md RCA and Testing sections.
- Goal 3 docs done: Bridge slipway/design (status + testing bullets), slipway/first-playable-prompt (steps 0.2, 6.3-6.6
  and an update note), tellus-e2e SKILL.md section "New mods: start with Fabric client GameTests", new Bridge doc
  modding/minecraft-testing-strategy (failure breakdown, levels, leak lessons, mc-testkit proposal).
- Before numbers (Prism history, validation.json): 112 runs, 43 fail, 26 pending-review, 7 same-jar flips (6.2%),
  14 harness "scenario error"; last full Prism batch at release commit 11943c1 took ~36 min (+3.5 min leak rerun)
  with the 20-min soak (19:02 -> 19:41); scenario step durations sum 31-33 min. Client GameTest full suite: 596 s
  with the 2-min soak (series0 run 1).

## Commits

- Slipway 5c84037: client GameTests (all 12 scenarios), ClosedWorldCleanup, GameRendererAccessor, test accessors,
  checkPatches hooks + clientGametest ids, leak matrix tooling. Fast levels green (59 unit, 28 server GameTests).
- DH core 5e93372c4 / wrapper aa2e97405 on branch slipway-leak-fix (not pushed): L1-L8 (PATCHES.md), leakfix.9 jar
  FDDE1809 in devmods/test and E:\slipway-e2e\dh.

## Client GameTest findings (harness/environment, all fixed in the test setup)

- Fabric's waitForChunksRender asks vanilla's renderer (never done with Sodium) and waitForChunksDownload wants the
  full square (server sends a circle): own waits (Game.waitChunks / waitTerrain via Sodium isTerrainRenderComplete).
- Deadlock closing singleplayer: deferred disconnect -> IntegratedServer.halt executeBlocking while the server is
  parked at the phase barrier (DH's slow client close made the client miss the window). Test-only mixin
  IntegratedServerHaltMixin queues the task instead.
- Fabric Loader error GUI blocked unattended failing runs: -Dfabric.noGui=true.
- Dedicated server needs eula.txt (written by prepareClientGametestRun, as the e2e harness does) and whitelisting of
  the second client; its Swing GUI opened (DH forces java.awt.headless=false): pre-launch fixes headless=true first.
- In-process dedicated server stayed reachable after the test: vanilla watchdog (max-tick-time=-1) + vanilla JVM
  shutdown hook (removed after the server stops) + DH player states (L5, real DH bug, fixed).
- Test-frame dead locals held a world (TestServerContextImpl in a <Java Local>): world steps in their own methods.
- Idle FPS limit (AFK after 1 min) capped perf at 30: inactivityFpsLimit=MINIMIZED. Bliss clouds drift/shadow:
  Cloud_Speed=0, CLOUDS_SHADOWS=false, VL_CLOUDS_SHADOWS=false in the pack options (reference images then MSD 0).
- Real product findings from the new tests: vessel outline drawn with F1/adventure (fixed), DH CCE in client
  proxies with in-process dedicated server (L8), DH player-state sweep missing in DhClientServerWorld.close (L5).

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

Run series2 (3 x full suite with the 20-minute soak). Then retire the Prism scenarios, rewrite validation.json,
fill DESIGN.md numbers, final clean build, Bridge task, final report.

## Next step (old)

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
