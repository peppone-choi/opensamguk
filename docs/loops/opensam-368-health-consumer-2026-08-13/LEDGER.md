# LEDGER — OPENSAM-368 health consumer (2026-08-13)

| Wheel | Hypothesis | Score before → after | Scorer | Decision | Cause / evidence |
|---|---|---|---|---|---|
| 0 | Current alert-consumer coverage reaches all production engine names. | Existing contract PASS, but `spep-game-engine` has no case. | `tools/ops/daemon_health_alert_contract_test.sh` | Baseline only | Source inspection found the narrower `s[0-9]` grammar in both workflow and script, unlike deploy/reset's `s${public}` alphanumeric contract. |
| 1 | Aligning that grammar and adding a non-restarting Compose healthcheck makes the same bounded consumer alert a recovery-gated alphanumeric engine. | RED → GREEN | Hermetic alert/Compose contract | Adopt | Controlled RED first reported `FAIL: alphanumeric game-engine inventory was silently skipped`; after grammar alignment it reached the missing-Compose-healthcheck RED; final rerun printed `PASS: daemon health alert workflow and script contracts` / `RESULT=0`. The `spep` recovery-gated payload is asserted while raw diagnostic and webhook sentinels remain absent. |
| 2 | The narrow candidate is independently safe to hand off. | N/A → fix-required → cleared | Fresh `lazycodex-code-reviewer` read-only review | Adopt after evidence correction | Reviewed reachable PR HEAD `ee2726cfa735468d9b13c0876f0b9db8e80ed6d8` against merge base `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`. Product/runtime changes had no finding. One HIGH evidence-metadata finding identified the prior non-ancestor review SHA and absent `.omo` report; the PR-visible review record now carries immutable product blob IDs and the independently observed exact-HEAD commands instead. |
| 3 | Docker health `starting` is an explicit bounded promotion grace, while every other state remains fail-closed. | False incident RED → GREEN; review fix-required → cleared | Extracted workflow with hermetic Docker/curl stubs + fresh independent review | Adopt after evidence refresh | Before the fix, a `starting` `spep-game-engine` produced `FAIL: Docker health starting must remain inside startup grace` and a false `status_unreadable` incident. The narrow workflow guard now skips only exact `starting`; `unhealthy` still alerts `recovery_gated`, stopped/no-healthcheck still alerts `status_unreadable`, and inspect failure exits nonzero without a fabricated webhook. The first independent review cleared runtime behavior but blocked stale blob evidence; the review artifact was updated to the current workflow/test blobs and re-reviewed. |

## Tooling baseline

The execution wrapper intermittently returned no terminal status for otherwise
successful read-only commands. The underlying contract test was polled through
its retained PTY and reported `PASS: daemon health alert workflow and script
contracts` with `RESULT=0`. This wrapper telemetry is not product evidence.

The rendered Compose check used explicit inert environment values and
`--env-file /dev/null`; it printed only the game-engine healthcheck and restart
shape. No local or production secret file, Docker daemon, webhook, or runner was
used.
