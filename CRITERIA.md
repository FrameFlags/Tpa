# Criteria: Tpa

- [x] `shared-tests` passes with the `--release 8` core compile gate.
- [x] `build.yml` run https://github.com/FrameFlags/Tpa/actions/runs/45758186445: all 13 jobs succeeded (shared-tests + 12 build cells); both jars exist per cell.
- [x] `behavior.yml` run https://github.com/FrameFlags/Tpa/actions/runs/45756954270: 16/16 jobs succeeded, exactly one canonical `TPA_TEST_RESULT:` line per job.
- [x] All 12 green cells carried `PASS`, `product=true`, `checks=13 failures=0`, `accept_ok`, `tpahere_ok`, `deny_ok`, `cancel_ok`, `expiry_ok`, `no_spam`.
- [x] All 4 negative cells carried `FAIL`, `product=false`, `reason=no_product`.
- [x] `/tpa`, `/tpahere`, accept/deny/cancel and expiry verified by the in-game probe contract.
- [x] Pure single-player with one online player returns one "No other players online" message.
- [x] LAN/localhost local hosting: two server players in the probe drive requests and teleports in both directions.
- [x] No repeated chat lines on any tick; expiry fires once and never early.
- [x] Final HEAD has green build + behavior runs and README links both workflows.
