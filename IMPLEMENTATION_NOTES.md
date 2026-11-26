# Tpa implementation notes (for cell adapters)

## Shared core (written once, copied into every cell)

`shared-core/com/frameflags/tpa/core/`: `TpaConfig`, `TpaPose`, `TpaResult`, `TpaRequest`, `RequestRegistry`. Copy this whole package into each cell at `src/main/java/com/frameflags/tpa/core/` (plain source copy, no cross-cell dependencies). Core is Java 8 source: no records, `var`, `List.of` or switch expressions. `shared-tests/` enforces `--release 8`.

## Product module API (must exist in every cell)

`com.frameflags.tpa.Tpa` with static members:

- `TpaConfig config()`
- `RequestRegistry registry()`
- `Path configFile()` — `<gameDir>/config/tpa.json`
- `void init(Path gameDir)` — creates config dir, loads config once
- `void onServerTick(<server>, <tick>)` — drains expired requests, sends one message per side
- `void purge(UUID player)` — removes all requests involving a player
- `void clear()` — server stop
- `void chat(UUID player, String text, int tick)` — single message sink for no-spam probe
- `List<String> chatLog(UUID player)` / `clearChatLog(UUID player)` — probe-only observation
- `ServerPlayer resolveTarget(MinecraftServer server, String name)` — adapter-side player lookup
- `void teleport(ServerPlayer player, TpaPose pose, MinecraftServer server)` — version-specific

## Command adapter

Brigadier cells: eight literals (`tpa`, `tpahere`, `tpaccept`, `tpacceptall`, `tpdeny`, `tpdenyall`, `tpacancel`, `tpacancelall`) plus optional `all`/`*` argument forms; no `.requires(...)`; `StringArgumentType.word()` for player names with online-name suggestions. 1.12.2 uses `CommandBase` classes with `getRequiredPermissionLevel()==0`.

All command logic must call the real product API and send exactly one message per outcome. No chat line may repeat on successive ticks.

## Teleport

Modern cells: resolve `pose.dimension` with `Identifier.tryParse` → `ResourceKey.create(Registries.DIMENSION, id)` → `server.getLevel(key)`; fall back to the current level; `teleportTo(level, pose.x, pose.y, pose.z, Set.of(), pose.yaw, pose.pitch, false)` then zero momentum. 1.16.5 uses its mapping's equivalent APIs. 1.12.2 parses the dimension as an integer, calls `changeDimension` when needed, then `setPositionAndRotation` and zeroes `motionX/motionY/motionZ`.

Unlike Tpa, there is no saved-position `-0.5`/`+0.5` block-corner dance: Tpa snapshots the exact player pose itself.

## Test-only probe

Package `com.frameflags.tpa_test`. The probe must:

1. Wait for a joined player and fabricate a second `ServerPlayer` per loader family.
2. Dispatch real commands through the server command dispatcher as the appropriate player.
3. Assert every check token in `BEHAVIOR_CONTRACT.md`.
4. Write exactly one line `TPA_TEST_RESULT: PASS|FAIL ...` to `tpa-result.txt` at root and `run/`; print with `[TPA_PROBE]` prefix.
5. Never treat a skipped check as PASS.

## Hard rules

- `mod_id = tpa`; test mod id `tpa_test`.
- Product jar `tpa-<cell>-1.0.0.jar`; probe jar `tpa-testmod-<cell>-1.0.0.jar`.
- Product jar excludes `com/frameflags/tpa_test/**`.
- Fabric 1.21.x probe jars go through Loom `remapJar` (named → intermediary).
- No `|| true` on any assertion path.
- Do not change cell pins/metadata without CI evidence.
