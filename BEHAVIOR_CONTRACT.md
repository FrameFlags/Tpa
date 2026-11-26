# Tpa behavior contract

Single source of truth for every cell. The in-game probe and CI assertions are derived from this file.

## Commands

1. `/tpa <player>` creates a `TO_TARGET` request; `/tpahere <player>` creates a `TO_REQUESTER` request with the requester's exact pose snapshotted at creation.
2. `/tpaccept [player|all|*]` and `/tpacceptall` accept incoming requests; bare accepts the oldest.
3. `/tpdeny [player|all|*]`, `/tpdenyall`, `/tpadeny [...]`, `/tpadenyall` deny incoming requests; bare denies the oldest.
4. `/tpacancel [player|all|*]` and `/tpacancelall` cancel outgoing requests; bare cancels the oldest.
5. Self-target, unknown/offline target and a duplicate `(requester,target)` pair are rejected with exactly one message. A requester may have at most 5 outgoing requests; inserting a 6th evicts the oldest.
6. No command requires op, cheats or a permission plugin. No Bukkit-only features (economy, ignore lists, world permissions).

## State and safety

- All requests are in-memory and ephemeral. Nothing is written to disk except `config/tpa.json`.
- Deadline is `currentTick + expirySeconds * 20`; default 60 seconds, configurable for tests.
- Expiry removes the request, sends one message to each side and never teleports.
- Requests are purged on accept, deny, cancel, expiry, disconnect/death and server stop.
- `/tpa` resolves the target's live pose at accept. `/tpahere` uses the stored snapshot, ignoring the requester's later movement.
- Cross-dimension teleports are allowed. Dimension is stored as a stable string key; missing dimensions fall back to the player's current level. Exact yaw/pitch is preserved and momentum is zeroed.
- Pure single-player (one player online): every command returns one clear "No other players online" message. Local hosting = Open to LAN / `/publish` and join via localhost.

## No spam

- No command emits repeated chat lines on successive ticks.
- A rejected command emits exactly one error message; all/deny/cancel accept a count summary.

## Test assertions (all cells)

The probe drives the real command dispatcher as two players (the joined player and a fabricated second `ServerPlayer`) and asserts:

1. `request_ok` — `/tpa` creates a pending request.
2. `duplicate_rejected`, `outgoing_limit`, `self_rejected`, `no_such_player` — validation.
3. `accept_ok` — `/tpa` + `/tpaccept` moves the requester to the target's pose (0.01 tolerance).
4. `tpahere_ok` / `snapshot_ok` — `/tpahere` + `/tpaccept` moves the target to the requester's snapshotted pose, not the reverse.
5. `deny_ok`, `cancel_ok`, `cancelall_ok` — removal paths with no teleport and one message.
6. `expiry_ok` — with `expirySeconds: 2`, an untouched request expires once and never teleports.
7. `no_spam` — no repeated chat line on consecutive ticks.

The probe writes exactly one canonical line `TPA_TEST_RESULT: PASS|FAIL ...` to `tpa-result.txt` at root and `run/`. A cell staged without the product jar must emit `FAIL reason=no_product product=false`. A skipped check never counts as a pass.
