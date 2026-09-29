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
gradlew build                # mod jar in build/libs, runs unit tests and the patch registry check
```

## License

Apache-2.0. Bundled third-party components and their licenses are listed in `NOTICE`.
