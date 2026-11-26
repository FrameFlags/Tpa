# Tpa

EssentialsX-style teleport requests for Minecraft, built as 12 version/loader cells using the proven Tpa architecture.

## Commands

- `/tpa <player>` — ask to teleport **to** that player.
- `/tpahere <player>` — ask that player to teleport **to you** (your position is snapshotted when the request is made).
- `/tpaccept [player|all|*]`, `/tpacceptall` — accept an incoming request.
- `/tpdeny [player|all|*]`, `/tpdenyall`, `/tpadeny [...]`, `/tpadenyall` — deny an incoming request.
- `/tpacancel [player|all|*]`, `/tpacancelall` — cancel an outgoing request.

No command requires op, cheats or a permission plugin. Self-requests are rejected. One pending `(requester,target)` pair at a time; at most 5 outgoing requests per player; 60-second expiry (configurable in `config/tpa.json`). Requests are in-memory and vanish on accept, deny, cancel, expiry, disconnect, death or server stop.

## Single player and local hosting

The integrated server in single-player is a real `MinecraftServer`, so the mod loads there. With only one player online, every TPA command returns one clear **"No other players online"** message. To actually use `/tpa`, open the world to LAN (`Open to LAN` or `/publish`) and join from `localhost:<port>` (or another LAN machine). Both players are then real server players and all commands work exactly as on a dedicated server.

## Cells

`1.12.2-forge`, `1.16.5-forge`, `1.16.5-fabric`, `1.19.2-forge`, `1.20.1-forge`, `1.20.1-fabric`, `1.21.1-fabric`, `1.21.1-neoforge`, `26.1.2-fabric`, `26.1.2-neoforge`, `26.3-fabric`, `26.3-neoforge`.

Product jars are `tpa-<cell>-1.0.0.jar`; the in-game probe is built separately as `tpa-testmod-<cell>-1.0.0.jar` and never shipped in the product jar.

## Verification

- `.github/workflows/build.yml` — pure-Java core tests plus 12-cell build matrix.
- `.github/workflows/behavior.yml` — 12 green launcher rows + 4 negative controls; every probe emits exactly one `TPA_TEST_RESULT:` line.
- See `BEHAVIOR_CONTRACT.md` and `CRITERIA.md` for the full contract and definition of done.

## Sources

- EssentialsX 2.x command sources (`Commandtpa`, `Commandtpahere`, `Commandtpaccept`, `Commandtpdeny`, `Commandtpacancel`, `User.java`).
- Local catalog: `CreateBigCannonsTNT/docs/data/tpa-teleport.json` (60s, one pending request, command spellings).
- Minecraft Wiki: single-player runs an internal server; Open to LAN exposes it on a localhost/LAN port.
