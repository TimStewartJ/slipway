# Slipway

Moving physics block structures for Minecraft 26.3 (Fabric). Build a structure from any blocks, place a Slipway
Helm on it, assemble it into a vessel and fly it with full pitch, yaw and roll. The blocks stay real blocks: chests,
furnaces, doors, levers and redstone keep working while the vessel moves. Physics by
[Jolt Physics](https://github.com/jrouwe/JoltPhysics) through [jolt-jni](https://github.com/stephengold/jolt-jni).

Status: pre-1.0 prototype. See `PLAYTEST.md` for controls and known limitations, `DESIGN.md` for the architecture and
`PATCHES.md` for every mixin Slipway applies.

## Building

Requires Java 25.

```
tools/setup-devmods.ps1      # copies Fabric API, Sodium, Iris and Distant Horizons jars into devmods/
gradlew build                # mod jar in build/libs; runs every test level below and the patch registry check
gradlew generatePatches      # regenerates PATCHES.md from patches.json after changing a mixin
```

The jar bundles jolt-jni's double-precision native libraries for Windows, Linux and macOS (x86_64 and aarch64).

## Testing

All levels run in `gradlew check` (and `build`); details in `DESIGN.md`, "Testing".

- `gradlew test`: unit tests (math, controller, shapes, mass properties, collisions, records, Jolt engine including a
  native leak test with the Debug natives).
- `gradlew runGametest`: Fabric GameTests in a headless server (assembly round trips, deny list, physics, packets,
  interaction).
- `gradlew runClientGametest`: Fabric client GameTests on a real client with Sodium, Iris (Bliss shaders) and
  Distant Horizons: assembly of a mixed ship, flight through every rotation, deck walking, interaction, collision,
  forged packets, save and reload, rendering (shadows, reference images, Distant Horizons far view), multiplayer with
  a second client, performance, world-retention leaks and a flight soak. Reports in `build/client-gametest`.
  `-PslipwaySoakMinutes=20` runs the release soak; `-PslipwayClientGametestOnly=a,b` picks scenarios. The window
  never takes focus. Needs `devmods/` (and the Bliss pack; see `build.gradle`).
- `gradlew runPackagedJarCheck`: the release jar with the exact play-stack jars in production Minecraft; fails on any
  mixin or loader error.
- `tools/e2e/`: the Prism-based leak isolation matrix (runs without Slipway, for diagnosis) and heap-dump tools; the
  retired Prism scenarios are in `tools/e2e/legacy`. `validation.json` lists every recorded run and its verdict.

## License

Apache-2.0. Bundled third-party components and their licenses are listed in `NOTICE`.
