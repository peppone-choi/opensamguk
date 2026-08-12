# Review: OPENSAM-34 D4 hermetic web-health contract portability

Scope: `tools/ops/predeploy_go_check_contract_test.sh` — the test-only Docker
double for the already deployed web-game health parser.
Verdict: cleared

## Failure linkage and confirmed cause

The first post-merge D4 rerun, GitHub Actions run `31560954690`, stopped before
the live grader. Its `Validate predeploy grader contract` step completed both
syntax checks and then reported:

```text
FAIL: expected success for success s1 s1 0123456789abcdef0123456789abcdef01234567 scenario_1010 101 2 10
```

The production health command correctly runs `node` inside the `web-game`
container. However, the hermetic `docker` double executes that inner `sh -ceu`
command directly on the workflow host. The self-hosted runner has no `node` on
its PATH, turning the contract's positive fixture into a hidden command failure.

This was reproduced under the same Linux Bash invocation shape:

- Node-free `ubuntu:24.04` printed `node=` and reproduced the exact positive
  fixture failure.
- Unchanged checkout in `node:22-bookworm-slim` printed `node=v22.23.2` and
  `PASS: predeploy-go-check hermetic contract`.

That toggle proves the cause is the test double's host dependency, not the live
web-game health endpoint or the parser's JSON semantics.

## Loop and TDD evidence

The existing hermetic contract is the frozen evaluation gate.

| Item | Evidence |
| --- | --- |
| Baseline | D4 run `31560954690` fails before the live grader. |
| Single hypothesis | The double leaks a container-only Node dependency onto the host. |
| RED | A temporary Node-free stub made the local contract print the same `expected success` failure. |
| GREEN | The private test-path Node double restored `PASS: predeploy-go-check hermetic contract`. |
| Remeasurement | The full contract also passes in Node-free Linux. |
| Decision | Adopt: preserve production parsing and make only the hermetic runtime model portable. |

The test double accepts the known healthy response emitted by
`web/game/app/api/health/route.ts` and fails every other fixture. Therefore
valid `DOWN`, malformed JSON, and unavailable `wget` responses remain
fail-closed; no assertion was deleted or weakened.

## Production-runtime QA

The current `docker/web-game.Dockerfile` was rebuilt from this checkout. Its
in-container request to `http://127.0.0.1:3001/api/health` returned:

```json
{"status":"UP","app":"web-game"}
```

The exact production parser command accepted that payload and rejected both
`{"status":"UP",not-json}` and
`{"status":"DOWN","app":"web-game"}`.

## Independent review

`/root/fix_opensam34_web_health_nogo/review_web_health_portability` returned
**WATCH / APPROVE** with no blockers after independently running syntax,
whitespace, and the hermetic contract checks. It identified one non-blocking
maintenance note: the test double recognizes the fixed health route's current
serialization, while the production Node parser accepts any valid object whose
top-level status is `UP`. The local rebuilt-container QA above independently
covers the broader production parser behavior; broadening the test double with
another host JSON dependency would reintroduce the failed portability property.

## Release boundary

No production write, workflow dispatch, deployment, merge, or follow-up D4
rerun occurred in this lane. The local images and containers used for the
Linux/container proof are removed before commit.
