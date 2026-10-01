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

2026-09-30 afternoon (user awake, approved 14:5x): move every 26.x instance to one Distant Horizons build,
`3.3.4-tellus-fork.7` = upstream DH 3.3.4 + Tellus patches P1-P9 + leak fixes A-G (plan and decisions: Bridge doc
`distant-horizons/fork7-3.3.4-plan`). User decisions: full plan (fork.7 in Slipway play and the 26.2 instance too);
GPU work (launches, client GameTests) only once the film run (other session, E:\Slipway-film) looks finished (final
videos written and 30+ min idle); interim irisfix.1 in the play instance; push Slipway commits (now, and the fork.7
pin/docs commits once green); cleanup: Slipway-Test2, e2e scratch, superseded DH jars now; heap dumps, trial clones,
leakfix/irisfix jars, 0.1.0 backup and the two local DH branches (as archive tags) after fork.7. Ask again before
pushing or tagging the DH fork. Builds during the film run with `gradlew --priority low`; never `gradlew --stop` on
Gradle 9.4.0 (the film's daemon).
Progress:
- 15:01 play instance: DH leakfix.9 -> irisfix.1 (SHA256 B9FE6130...A79E), all 5 jars match
  E:\slipway-e2e\play-instance-expected-sha256.json; backup E:\slipway-e2e\play-instance-backups\Slipway-MC-26.3-Fabric-0.1.1-leakfix.9-20260930-150107.
  No native launch yet (film run uses the GPU); the same jar set passed the packaged-jar check earlier today.
- 15:05 pushed 1f992ef..33f69c5 (CI green, run 36783132935). 15:10 cleanup: Slipway-Test2 instance, e2e scratch,
  leakfix.1-8 + from-source stock jars deleted (0.39 GB); 9b0ebf4 (local) drops Test2 from the e2e module.
- 15:30 Phase 1 done in work clone E:\dh-fork7 (core clone at E:\dh-fork7\coreSubProjects), copied to
  E:\distant-horizons as local branches `rebase-3.3.4` (wrapper cf1d0cd21, core b055cc841), nothing pushed.
  Core: P-series rebased onto core 3.3.4 (one conflict, P1 FullDataUpdatePropagatorV2 catch body), + B, C, E, A
  (A adapted: P9 already frees failed slots) + I2 (kept: official 3.3.4 still logs Iris's "Unexpected; somehow the
  Opaque + Translucent pass" once per film run, 12 of 12 film logs). Wrapper: series rebased onto wrapper 3.3.4
  (only version lines + 4 submodule pointers), + D, F, G, release commit (fork.7, PATCHES.md). range-diff: no other
  change to any fork commit. Builds (gradlew --priority low --no-daemon): 26.3 + 26.2 Fabric/NeoForge, core tests
  106/106. Jars in E:\slipway-e2e\dh\fork7 (fabric 26.3 SHA256 BCF32F99...FEF10, 26.2 3FDFEE9C...4224); logs in
  E:\slipway-e2e\dh\fork7-build. devmods/test now has fork.7 (irisfix.1 kept in E:\slipway-e2e\dh).
  Config: DH 3.3.4 deletes configs with _version < 5 (ConfigFileHandler); migration = set _version = 5 (+ ",grass" in
  blocksDontUseSideTextureCsv for the three Tellus configs).
Next: wait for the film to finish (then Phase 2: Slipway full build with fork.7, stock-3.3.4 lane, blotch diag,
Tellus check on Tellus-MC-26.3-Fabric via the tellus-e2e harness).
Prepared (E:\slipway-e2e\tools, not in any repo): dh-fork7-rollout.ps1 (Backup-DhInstance, Set-DhJar,
Convert-DhConfigToV5 - dry run on copies of all five configs: only _version and the grass entry change, idempotent;
Test-DhInstanceTitleScreen - background launch to the title screen, mod-list DH version, no config reset, no mixin
errors, clean close) and tellus-dh-check.ps1 (fresh copy of save mc263-sp on Tellus-MC-26.3-Fabric, spectator flight
8 x 256 blocks, DH DB growth, far screenshot, log checks for the Tellus generator, guard P5/P6/plan overrides and the
P7 handoff; run on fork.6 first as the baseline).
- 16:02 user: film is done, proceed. Film watcher cancelled. The deck-luminance script (lost from %TEMP%) was
  recreated as E:\slipway-e2e\diag\deck-luminance.py; its region was fitted to the six recorded numbers.
- Phase 2, Tellus check on Tellus-MC-26.3-Fabric (reports in E:\slipway-e2e\tellus-dh-check\<label>-<stamp>):
  fork.6 baseline PASS (491 LOD rows added, generator + P5/P6/plan overrides, handoff via DH OpenGL, 0 errors);
  fork.6 + Bliss PASS (490 rows, handoff via Iris DH shader, 1 Iris "Unexpected ... pass" error);
  instance swapped to fork.7 + config v5 (backup E:\slipway-e2e\instance-backups\Tellus-MC-26.3-Fabric-before-fork7-20260930-161657);
  fork.7 PASS (491 rows, same log lines, 0 errors, settings kept);
  fork.7 + Bliss INTERRUPTED at 16:24 (user asked to pause). Game closed cleanly, iris.properties back to
  enableShaders=false, dhcheck save removed. That partial run showed: handoff active via Iris DH shader, 0 Iris
  "Unexpected" messages, and ONE DH error at close (16:24:48, DH-ClientTickTimer "Quad Tree tick exception",
  RejectedExecutionException from a terminated pool, game closed while in the world) - OPEN: check whether fork.6 or
  official 3.3.4 do the same before calling it harmless.
PAUSED by the user at 16:24. Not run yet: fork.7 + Bliss Tellus check (rerun), Slipway full build with fork.7, the
official-3.3.4 lane, the blotch diagnostic. No play instance has fork.7 (Slipway play: irisfix.1; Tellus-Expeditions
26.3 and 26.2: fork.6). Tellus-MC-26.3-Fabric (test) has fork.7.
- 17:35 user: do the no-game work now, game tests only on "go"; put fork.7 into the play instances before the tests.
  Done without any game launch:
  - Shutdown error explained, not a fork.7 regression, no rebuild. The rejected executor is LodQuadTree's
    fullDataRetrievalQueueThread (shutdownNow in LodQuadTree.close); the 100 ms ClientTickTimer is cancelled only at the
    end of DhClientServerWorld.close, and ClientLevelModule.clientTick skips closing levels only when the player is
    gone, so a tick in flight while the window is closed in-world can hit the terminated pool. That code is identical
    in official 3.3.4 and fork.7 (no fork commit touches it) and the same in 3.3.1 except for the extra super.close().
    Logs on disk: 0 hits in 255 other logs; "Closed DhWorld" appears 4x per world on official 3.3.4 and fork.7, 2x on
    3.3.1-based builds (upstream db7a34e4b). The three finished checks left the world before closing. Not reproduced
    on official 3.3.4 (no in-world close of it on disk). Candidate for the DH upstreaming task.
  - Screenshots of the finished Tellus checks reviewed (they were saved as Proceed-far.png: $label/$Label clash in the
    script, fixed): fork.6 vs fork.7 shaders off MSD 0.00018, far terrain to the horizon, no holes.
  - Static checks: all four fork.7 jars pass the fork's check-mixin-annotations.py; same 27 Fabric mixin classes as
    the installed jars. FOUND: upstream now writes `fabricloader >= fabric_loader_version` into fabric.mod.json
    (c1959b8d7) and raised 26.2's loader to 0.19.5 "for testing" (d0491973c); Tellus-Expeditions-MC-26.2-Fabric has
    loader 0.19.3, so fork.7 would not load there. That instance stays on fork.6 until its loader is updated together
    with a launch check.
  - fork.7 installed (backup, jar swap, config v5, hash check; E:\slipway-e2e\instance-backups\<id>-before-fork7-20260930-1741xx)
    in Tellus-Expeditions-MC-26.3-Fabric, Slipway-MC-26.3-Fabric (expected-sha json updated, 5 of 5 match) and
    Slipway-Test-MC-26.3-Fabric. NOT launched.
  - Slipway: devmods now fork.7 (both devmods and devmods/test); `gradlew assemble test runGametest checkPatches`
    green (E:\slipway-e2e\cgt\fork7-headless): main and client sources compile against fork.7, the jar is unchanged,
    unit tests up to date (60, classes unchanged), server GameTests 28/28 re-run, checkPatches 13. PLAYTEST and README
    updated (local commit, not pushed).
  - Release notes draft: E:\slipway-e2e\dh\fork7\RELEASE-NOTES-draft.md. For publishing: push the official tag 3.3.4
    to both fork repos (the GitHub fork only has 3.2.0b, so fork.6's notes named the wrong base), extend the notes
    template in release.yml, and afterwards replace the locally built jars in the instances with the release assets
    (the fork.6 jars in the instances were the GitHub release assets).
- 18:10 user: "go" for the SHORT set (about 20 min) and update the 26.2 instance; the full Slipway suite and the
  official-3.3.4 lane are dropped by agreement (Slipway source unchanged; official 3.3.4 passed its load check and
  blotch measurement today). Results (all game windows in the background, GRADLE_OPTS=-Dorg.gradle.daemon=false so the
  film session's Gradle daemon is not reused):
  - Launch to the title screen, worlds not opened (E:\slipway-e2e\runs\fork7-launch-checks-20260930):
    Tellus-Expeditions-MC-26.3-Fabric PASS, Slipway-MC-26.3-Fabric PASS (5 of 5 jars match, Bliss on, jolt loaded,
    0 ERROR lines). Both: fork.7 in Fabric's mod list, no config reset (only _version differs from the backup), no
    mixin errors, clean close, no save file written. The one WARN (Tellus's optional Voxy class) is also in fork.6 logs.
  - Packaged-jar check with fork.7 PASS (packaged-jar-check-20260930-181653: same five jars as the play instance,
    world opened, vessel assembled, 0 mixin/loader errors or warnings). Stands in for opening the user's sandbox.
  - Blotch diagnostic with fork.7 on the world copy, user LODs, play config v5 (E:\slipway-e2e\diag\run20-fork7-userlods):
    121.7 / 121.6 / 121.8 / 121.8 (clean ~120), 0 Iris pass errors; pictures reviewed, evenly lit.
  - Tellus check with Bliss on fork.7 PASS twice (fork7-bliss-20260930-182000, fork7-bliss-repeat-20260930-183329):
    handoff active via the Iris DH shader, Tellus generator + P5/P6/plan overrides, 0 Iris pass errors, 0 ERROR lines,
    picture matches fork.6 + Bliss (MSD 0.00025). OPEN: LOD rows added 463 and 456 vs 490 on fork.6 + Bliss (491 with
    shaders off on both builds), with 5 to 6 generation tasks unfinished at close (0 in the other runs). Both runs
    overlapped another session's CPU-heavy jobs (unrelated project; started 18:18:52 and 18:27:36; CPU 100% at
    18:39), so the comparison is confounded. Needs one run on a quiet machine before concluding anything.
  - Slipway render-iris + leak with fork.7 PASS (clientgametest-20260930-182511): GL state cache in sync at every
    sampling point (0/118 near, 0/120 far), references unchanged at MSD 4.03e-5 near and 4.41e-5 far (limit 2.5e-4;
    results.json rounds these metrics to 0.0 - reporting flaw to fix), shadow ratio 0.762, 868 proxy boxes; leak: no
    ServerLevel/IntegratedServer after any of 10 cycles, ClientLevel 0 plain and 1 with Bliss (Iris, as before), heap
    576-582 MB plain and 622-626 MB Bliss, private +53 MB per cycle with Bliss (known), Netty pool 9 to 36 (bounded 40).
  - 26.2 instance: backup (E:\slipway-e2e\instance-backups\Tellus-Expeditions-MC-26.2-Fabric-before-fork7-20260930-183137,
    incl. mmc-pack.json), Fabric Loader 0.19.3 -> 0.19.5 in mmc-pack.json, fork.7 26.2 jar, config v5; launch to the
    title screen PASS ("Loading Minecraft 26.2 with Fabric Loader 0.19.5", fork.7, 0 ERROR lines, settings kept).
  - validation.json: 5 entries added (276). DESIGN.md (integrations, leak table row, blotch resolution, I2 note,
    test DH build), PLAYTEST.md updated.
Next: commit + push Slipway; clean Tellus + Bliss run when the machine is quiet; ask the user about publishing
fork.7; cleanup (heap dumps and trial clones now, jars/branches after publishing).
- 18:43 pushed 33f69c5..396b832 (CI run 36802576102 green). 18:46 deleted the two kept heap dumps (2.1 GB; the MAT
  text reports stay) and E:\dh-fork7-trial.
- 18:50 asked the user how to settle the open far-terrain count (compare now / wait for a quiet machine / skip); the
  user was not available. Decision: no further game launches without their go (their rule from 17:35; the short set is
  used up), and nothing is published. What the existing data says: the split is by time, not by build or shaders. The
  four runs before the other session's job (16:08-16:21: fork.6 and fork.7 with shaders off, fork.6 with Bliss) stored
  490-491 sections with 0 tasks unfinished; the two runs during it (18:20 and 18:33, both fork.7 with Bliss) stored
  463 and 456 with 5-6 unfinished, the lower count under the heavier load. That job's logs show nothing between 15:30
  and 18:18. No code change between fork.6 and fork.7 touches generation only when shaders are on. So machine load is
  the likely cause, but fork.7 with Bliss has not been measured on a quiet machine. (The machine was quiet again from
  about 18:52.)
  Prepared, NOT RUN: E:\slipway-e2e\tools\tellus-dh-ab.ps1 (fork.6 then fork.7 with Bliss on the Tellus test instance,
  back to back, restores fork.7 and the version-5 config afterwards; about 8 minutes); tellus-dh-check.ps1 now records
  the machine's CPU load per step and the generation tasks unfinished at close.
- 22:54 user: "it seems pretty solid. dont run any more tests just go for it and publish". So the fork.6/fork.7
  comparison with Bliss was NOT run (the open point stays open, recorded in the fork's PATCHES.md), and no game was
  started. Published 23:00-23:20:
  - Core: last commit reworded (it said "local only"; same tree), tip 083f71f5c. Wrapper: new commit 5cc89a636
    (release workflow: base version read from mod_version instead of the nearest official tag, optional
    .github/release-notes/<mod_version>.md), release commit rebuilt as e8a43ae12 (PATCHES.md check results, notes
    file, pointer). Against the tested build cf1d0cd21 only .github, PATCHES.md and the pointer differ.
  - Pushed as branch rebase/3.3.4 first: fork CI 36822998242 green (core tests, four jars, mixin-annotation check).
    Then, in both repos: upstream-base -> official 3.3.4 (fast-forward), main -> the rebased series
    (--force-with-lease on the old tips, as for fork.4 and fork.5), annotated tag 3.3.4-tellus-fork.7 (core first).
    Release workflow 36823525406 green; CI on main 36823523468 green; temporary branches deleted.
  - Release: https://github.com/TimStewartJ/distant-horizons/releases/tag/3.3.4-tellus-fork.7 (latest; four jars and
    SHA256SUMS; notes name base 3.3.4, what is new, what to do before updating, what was checked).
  - Published jars against the locally built, tested ones: every class byte-identical; 65-66 text files differ in
    line endings only (LF from Linux, as in the fork.6 release asset); build_info.json differs (commit id).
    Report E:\slipway-e2e\runs\dh-fork7-release-check-20260930-2320\report.json; validation.json entry (277).
  - Published jars installed (game closed, atomic replace, checksum verified, NOT started): Slipway-MC-26.3-Fabric
    (expected-sha json updated, 5 of 5 match), Tellus-Expeditions-MC-26.3-Fabric, Slipway-Test-MC-26.3-Fabric,
    Tellus-Expeditions-MC-26.2-Fabric. Tellus-MC-26.3-Fabric was in use by another session and keeps the local build
    (same classes). devmods = published jar via the reworked tools/setup-devmods.ps1 (copies whatever DH jar the
    instance has; devmods/test is empty); `gradlew assemble checkPatches` green.
  - Local fork repo: main/upstream-base = published; old branches are tags archive/slipway-leak-fix,
    archive/slipway-iris-fixes, archive/fork7-first-build (both repos); working tree on main.
  - Cleanup: leakfix.9, irisfix.1, passfix.1 and bisect jars, the 0.1.0 play-instance backup, the release notes
    draft and the work clone E:\dh-fork7 deleted. Kept: E:\slipway-e2e\instance-backups (rollback to the builds
    before fork.7), E:\slipway-e2e\dh\fork7\{release,local-build-cf1d0cd21}, the official 3.3.x jars.
  - tools/e2e/SlipwayE2E.psm1 no longer hard-codes the fork.6 jar name.
DONE. Open: the Bliss section-count comparison (E:\slipway-e2e\tools\tellus-dh-ab.ps1, not run); the Tellus test
instance jar swap when that instance is free; Slipway's full client GameTest suite has not run with fork.7.

## Previous goal (shader blotches)

2026-09-30 midday (user awake): the user reported black, blotchy lighting under Bliss on the plain skiff (the vessel
looked right) and asked for the cause and a fix. DONE so far:
- Cause found and proven: Distant Horizons up to 3.3.2 (and the Tellus fork) desyncs Minecraft 26.2+'s per-draw-buffer
  blend cache (glEnable/glDisable(GL_BLEND) for all buffers, cache updated for buffer 0 only); DH draws LODs at the
  start of the main pass, so the opaque terrain drawn next blends into Iris's G-buffers. Upstream fixed it in 3.3.3
  (95bbccaff). Isolation runs E:\slipway-e2e\diag\run6..run19b (diag-plain-ship on a copy of the user's world).
- DH fork local branch slipway-iris-fixes (not pushed): I1 blend backport (wrapper d50c680f3), I2 render-pass order
  (core 9572e8aa0); build 3.3.1-tellus-fork.6-leakfix.9-irisfix.1, SHA256 B9FE6130...A79E, core tests 106/106; now
  the client GameTests' DH (devmods/test; leakfix.9 kept in E:\slipway-e2e\dh).
- Slipway d40b68b: GlStateCheck in render-iris (fails every frame with leakfix.9, passes with irisfix.1); Bliss
  reference images re-recorded (the old ones had the bug in them); prepareClientGametestRun deletes options.txt and the
  DH config (a diagnostic's fov leaked into later runs); diag-plain-ship scenario. DESIGN.md "Dark blotches" RCA.
Next: full `gradlew clean build` at d40b68b (E:\slipway-e2e\cgt\irisfix-build-d40b68b), packaged-jar check with
irisfix.1, render-iris 3 consecutive passes, validation.json, then ask the user whether to put irisfix.1 into the play
instance (only with the game closed; back up first, update expected hashes).
Status 13:58: DONE - full build green at d40b68b (unit 60, server GameTests 28, client GameTests 12, packaged-jar
check, checkPatches 13; 18 min); reference limit tightened to 2.5e-4 in 8cb8aeb (leakfix.9 now fails it: 5.17e-4);
render-iris 3/3 at 8cb8aeb (E:\slipway-e2e\cgt\irisfix-render-iris; MSD 3.5e-5 to 4.4e-5, shadow ratio 0.762, GL
state 0 out of sync); packaged-jar check with irisfix.1 pass; validation.json 271 entries (earlier render-iris passes
superseded by clientgametest-20260930-132443-render-iris). Remaining: the user's decision on the play instance.

## Previous goal (0.1.1)

2026-09-30 morning (user awake): Slipway 0.1.1 released locally and installed into the play instance at the user's
request. Release build `gradlew clean build` at ad90790 green (unit 60, server GameTests 28, client GameTests 12,
packaged-jar check, checkPatches; E:\slipway-e2e\cgt\release-0.1.1-build); jar SHA256 9DDC551D...CDCF5 in
E:\Slipway\release. Play instance rebuilt (install-play-instance.ps1 -Replace; the user chose DH leakfix.9 for it;
Tellus instances keep fork.6), old instance backed up to E:\slipway-e2e\play-instance-backups\...-0.1.0-20260930-073550,
fresh Slipway Sandbox (ships at y 81), verified by two native launches (play-instance-verify-20260930-074841, -075217).

## Overnight run result

DONE. Final `gradlew clean build` from clean commit 5d1d85b passed in 678 s (E:\slipway-e2e\cgt\clean-build2):
unit tests 60/60, server GameTests 28/28, client GameTests 12/12 (2-min soak), packaged-jar check pass (exact play
stack, 0 mixin/loader errors or warnings, vessel assembled), checkPatches 13 mixin classes. validation.json rewritten
(236 entries; current: series5 36 + clean-build2 13; 0 pending-review; every superseded entry names its replacement).
Remaining: none. Gradle daemons stopped (no java, Prism or MAT process left), temp folders removed, Bridge task notes,
history (entry 21) and momentum updated, final report sent. Kept on purpose: two heap dumps in E:\slipway-e2e\heap
(leak-matrix-before-full-plain-r1.hprof, leak-matrix-dhfix6-full-vessels-r1.hprof) for re-checking the RCA.

## Before the final build

Series5 GREEN at 2485c6a: 3 consecutive FRESH-run-dir full runs with the 20-min soak, 36/36 passed (1702/1719/1699 s;
E:\slipway-e2e\cgt\series5-fresh). DESIGN.md updated (runtime/flakiness with the full history, perf over 6 runs, soak
criterion evidence, DH ChunkSaveIgnoreTimer observation). Failed first clean build reconstructed into
E:\slipway-e2e\cgt\clean-build1-failed-assemble-race (results rebuilt from its log).

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
