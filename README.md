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
gradlew build                # mod jar in build/libs; runs unit tests, Fabric GameTests and the patch registry check
gradlew generatePatches      # regenerates PATCHES.md from patches.json after changing a mixin
```

The jar bundles jolt-jni's double-precision native libraries for Windows, Linux and macOS (x86_64 and aarch64).

## Testing

- `gradlew test`: unit tests (math, controller, shapes, mass properties, collisions, records, Jolt engine including a
  native leak test with the Debug natives).
- `gradlew runGametest`: Fabric GameTests in a headless server (assembly round trips, deny list, physics, packets,
  interaction). Also part of `gradlew build`.
- `tools/e2e/`: the end-to-end harness. A dedicated server plus a Prism test client with a test-only agent mod (and
  an offline dev client as a second player) run scenarios that build ships, fly them, walk on them, interact, save and
  reload, measure performance and check for leaks, with in-game screenshots and a report per run under
  `E:\slipway-e2e\runs`. `tools/e2e/run-scenarios.ps1` runs them in sequence; `validation.json` lists every run and
  its verdict (runs with screenshots only count after the screenshots were reviewed).

## License

Apache-2.0. Bundled third-party components and their licenses are listed in `NOTICE`.
