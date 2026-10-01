# Retired Prism-harness scenarios (archived, unmaintained)

These scripts drove Slipway's first in-game validation (M1–M7, 2026-09-29): a dedicated Fabric server and Prism
test clients with the e2e agent mod (`tools/e2e/agent`), commands over RCON and chat, screenshots reviewed by hand
and results recorded in `validation.json`.

Every scenario here was replaced by a Fabric client GameTest in `src/clientGametest` (run with
`gradlew runClientGametest`, part of `gradlew check`) with equal or stronger assertions on structured game state,
and retired after three consecutive green client-GameTest runs with the 20-minute soak. See DESIGN.md, "Testing",
for the mapping, the runtime and flakiness numbers, and why the move was made.

| Retired script | Replaced by |
| --- | --- |
| `scenarios/assemble-mixed.ps1` | `AssemblyScenarios` (`assemble-mixed`) |
| `scenarios/flight-rotation.ps1` | `FlightScenarios` (`flight-rotation`) |
| `scenarios/deck-walk.ps1` | `DeckScenarios` (`deck-walk`) |
| `scenarios/interaction.ps1` | `InteractionScenarios` (`interaction`) |
| `scenarios/collision.ps1` | `CollisionScenarios` (`collision`) |
| `scenarios/forged-packets.ps1` | `PacketScenarios` (`forged-packets`) |
| `scenarios/save-reload.ps1` | `SaveScenarios` (`save-reload`) |
| `scenarios/render-iris.ps1` | `RenderScenarios` (`render-iris`) |
| `scenarios/multiplayer.ps1` | `MultiplayerScenarios` (`multiplayer`) |
| `scenarios/perf.ps1` | `PerfScenarios` (`perf`) |
| `scenarios/leak.ps1` | `LeakScenarios` (`leak`) |
| `scenarios/soak.ps1` | `SoakScenarios` (`soak`) |
| `run-scenarios.ps1` | `SlipwayClientGameTests` (one entrypoint runs every scenario) |
| `deploy-test-build.ps1` | not needed: the client GameTests run from the Gradle build |

Still in use outside this folder: `tools/e2e/SlipwayE2E.psm1`, `tools/e2e/scenarios/_common.ps1` and the agent mod
back the leak isolation diagnostics (`tools/e2e/scenarios/leak-new.ps1`, `leak-matrix.ps1`, `tools/e2e/mat.ps1`),
which need mod configurations without Slipway and so cannot run as client GameTests, and the play-instance tools
(`tools/verify-play-instance.ps1`, `tools/make-sandbox-world.ps1`).

The scripts are kept as they were at retirement (only their paths to the shared helpers were updated). They are not
run by any build and may break as the shared helpers change. The second Prism instance that `multiplayer.ps1` and
`deploy-test-build.ps1` expect, `Slipway-Test2-MC-26.3-Fabric`, was deleted on 2026-09-30.
