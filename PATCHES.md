# Slipway patch registry

Generated from `patches.json` by `gradlew generatePatches`; `gradlew checkPatches` (part of `check`) fails when a
mixin, a targeted method or a covering test is missing. Edit `patches.json`, not this file.

**3 mixin classes, 8 hooked methods.**

| Mixin | Side | Target mod | Target class | Methods | Feature | Reason | Covered by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `dev.timstewart.slipway.mixin.ChunkMapMixin` | server | minecraft | `net.minecraft.server.level.ChunkMap` | `isChunkTracked` | chunk sync | Players who were sent a vessel's plot chunks must receive block, block-entity and light updates of those chunks although they are far outside the player's view. | `dev.timstewart.slipway.gametest.PacketGameTests#plotChunksAreTrackedOnlyByTheirViewers` |
| `dev.timstewart.slipway.mixin.ChunkStatusTasksMixin` | server | minecraft | `net.minecraft.world.level.chunk.status.ChunkStatusTasks` | `generateStructureStarts`<br>`generateStructureReferences`<br>`generateBiomes`<br>`buildTerrain`<br>`generateFeatures`<br>`generateSpawn` | reserved-region storage | Chunks in the reserved vessel region must generate empty (no terrain, structures, features or mobs) with the void biome, so plots start clean and never get weather. | `dev.timstewart.slipway.gametest.AssemblyGameTests#reservedChunksGenerateEmptyVoid` |
| `dev.timstewart.slipway.mixin.LevelChunkMixin` | both | minecraft | `net.minecraft.world.level.chunk.LevelChunk` | `setBlockState` | incremental shapes and meshes | Every block change must reach Slipway so vessel collision shapes, mass, bounds and client meshes, and terrain bodies near vessels, are rebuilt. | `dev.timstewart.slipway.gametest.PhysicsGameTests#addingBlocksMakesAVesselHeavier` |
