# OPENSAM-368 production daemon-health consumer review

Scope: docker-compose.production.yml; .github/workflows/daemon-health-alert.yml; tools/ops/daemon_health_alert.sh; tools/ops/daemon_health_alert_contract_test.sh; docs/loops/opensam-368-health-consumer-2026-08-13/
Verdict: cleared

## Independent review identity

- Reviewer: independent `lazycodex-code-reviewer` subagent, read-only.
- Current reviewed candidate parent:
  `972d0638704813485a9643198bd2488607cf6963`.
- Base and merge base: `origin/main` at
  `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`.
- Portable product-tree identity for the current candidate working tree based
  on that parent:
  - `.github/workflows/daemon-health-alert.yml` blob
    `e6e09279effbd926fbada81f1774d4f37ca2df35`;
  - `docker-compose.production.yml` blob
    `ca9beff780a1ed0e0674c6fe294edf7f296ad638`;
  - `tools/ops/daemon_health_alert.sh` blob
    `cac0971bc8737759eefbc3bec3eafc45a247de79`;
  - `tools/ops/daemon_health_alert_contract_test.sh` blob
    `3421ac003fc249aa3b1052aa110c127ea96a425a`.

The first exact-HEAD review found no product/runtime issue but found one HIGH
evidence-metadata issue: the earlier artifact cited a non-ancestor commit and an
untracked `.omo` report. After that correction, a new PR review found a real P2
promotion race: the scheduled consumer called the alerter while Docker health
was still `starting`. The runtime remediation was independently cleared, but
that review correctly blocked the now-stale blob evidence. This portable report
therefore records the new workflow/test blobs and the full review progression;
it does not reuse either prior clearance as current-tree proof.

## Findings checked independently

- The consumer grammar matches deploy/reset's canonical internal identifier:
  `s` plus lower-case `[a-z0-9]{1,48}`, including `spep-game-engine`.
- A recovery-gated daemon is classified as bounded `DOWN/recovery_gated`; the
  alert path returns failure to the scheduled workflow and never invokes a
  restart.
- `docker-compose.production.yml` retains `restart: unless-stopped` and gains
  the internal Actuator healthcheck. Docker health state is observational, so
  an unhealthy but still-running recovery-gated daemon cannot form a restart
  loop through that policy.
- Docker inventory query failure remains fail-closed; a deliberately empty
  inventory is a documented successful no-op.
- The scheduler defers only exact Docker health `starting`. The production
  engine healthcheck bounds that state with a five-minute start period.
  `unhealthy` and stopped/no-healthcheck containers still enter the alerter;
  inspect failure makes the workflow fail without dispatching invented status.
- The alert contract proves raw diagnostic and webhook sentinels do not leak to
  payload or output.
- The already-present deploy recovery checks reject `recoveryReady != true`
  before any initial or polling pause skip; the hermetic contract executes both
  branches.

## Evidence

- Controlled RED: `FAIL: alphanumeric game-engine inventory was silently
  skipped`; after identifier alignment the same test reached the missing
  production-healthcheck assertion.
- Focused GREEN: `bash tools/ops/daemon_health_alert_contract_test.sh` printed
  `PASS: daemon health alert workflow and script contracts` with `RESULT=0`.
  It uses local `docker`/`curl` stubs and an invalid sentinel URL only.
- Rendered Compose GREEN: `docker compose --env-file /dev/null -f
  docker-compose.production.yml config --format json` with explicit inert
  environment values rendered `restart=unless-stopped` and the expected
  10s/5s/30/5m healthcheck probe. No Docker daemon or secret file was used.
- Static GREEN: `git diff --check`, shell syntax, and YAML parsing. The reviewer
  also confirmed the game-engine image supplies `curl` and the identifier
  grammar matches the canonical deploy/reset contract.
- Historical exact-HEAD independent rerun before the startup-grace remediation:
  - `git diff --check f4ee9135ad6cbce1c6cfb28f7113d7742f478282
    ee2726cfa735468d9b13c0876f0b9db8e80ed6d8`;
  - `bash -n tools/ops/daemon_health_alert.sh
    tools/ops/daemon_health_alert_contract_test.sh`;
  - `bash tools/ops/daemon_health_alert_contract_test.sh` printed
    `PASS: daemon health alert workflow and script contracts`;
  - production Compose rendered with `--env-file /dev/null` and explicit inert
    required values, confirming the Actuator probe, `10s` interval, `5s`
    timeout, 30 retries, `5m0s` start period, and
    `restart=unless-stopped`.
- Startup-grace RED: before the workflow guard, the new hermetic case exited 1
  with `FAIL: Docker health starting must remain inside startup grace` and had
  dispatched a false `status_unreadable` incident.
- Startup-grace GREEN: the extracted workflow now returns success with no
  payload only for `starting`. The same contract proves `unhealthy` returns
  nonzero with `recovery_gated`, stopped/no-healthcheck returns nonzero with
  `status_unreadable`, and an inspect failure returns nonzero with no payload.
- The reviewer separately confirmed that the contract exercises the
  `spep-game-engine` inventory path, inventory/dispatch/status fail-closed
  branches, secret non-leakage, and Compose healthcheck shape. No secret value
  was read or committed.

## Deliberately unexecuted

`tools/smoke.sh` is not an appropriate scoped verifier here: it builds and
starts the root local stack, then calls `docker compose down` against shared
ports/containers, while it does not use the changed production compatibility
compose. The task's hermetic contract plus rendered Compose surface replaces it.
No production runner, webhook, deployment, restart, merge, or live Docker
workload was contacted.
