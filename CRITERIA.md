# Criteria: Tpa

- [ ] `shared-tests` passes with the `--release 8` core compile gate.
- [ ] `build.yml`: all 12 build cells succeed and both `tpa-<cell>-1.0.0.jar` and `tpa-testmod-<cell>-1.0.0.jar` exist.
- [ ] `behavior.yml`: 16/16 jobs succeed with exactly one canonical `TPA_TEST_RESULT:` line per job.
- [ ] All 12 green cells carry `PASS`, `product=true`, `accept_ok`, `tpahere_ok`, `deny_ok`, `cancel_ok`, `expiry_ok`, `no_spam`.
- [ ] All 4 negative cells carry `FAIL`, `product=false`, `reason=no_product`.
- [ ] `/tpa`, `/tpahere`, accept/deny/cancel and expiry behave exactly as `BEHAVIOR_CONTRACT.md` says.
- [ ] Pure single-player with one online player returns one "No other players online" message.
- [ ] LAN/localhost host with two players: request and teleport work in both directions.
- [ ] No repeated chat lines on any tick; expiry fires once and never early.
- [ ] Final HEAD has green build + behavior runs and README links both workflows.
