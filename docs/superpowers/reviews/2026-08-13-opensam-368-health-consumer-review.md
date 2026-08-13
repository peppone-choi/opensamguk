# OPENSAM-368 production daemon-health consumer review

Scope: docker-compose.production.yml; .github/workflows/daemon-health-alert.yml; tools/ops/daemon_health_alert.sh; tools/ops/daemon_health_alert_contract_test.sh; docs/loops/opensam-368-health-consumer-2026-08-13/
Verdict: cleared

## Independent review identity

- Reviewer: independent `lazycodex-code-reviewer` subagent, read-only.
- Current reviewed candidate parent:
  `bff0271813cfbff328ddc171a9bac584fece4205`.
- Base and merge base: `origin/main` at
  `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`.
- Portable product-tree identity for the current candidate working tree based
  on that parent:
  - `.github/workflows/daemon-health-alert.yml` blob
    `c94303748135011a6002407dd685496327951482`;
  - `docker-compose.production.yml` blob
    `a526c697f1050cd61f4bc1e86c84f21a91d7c27b`;
  - `tools/ops/daemon_health_alert.sh` blob
    `cac0971bc8737759eefbc3bec3eafc45a247de79`;
  - `tools/ops/daemon_health_alert_contract_test.sh` blob
    `6ed725280baf55d6c156b376b48630700bd6b6e3`.

The first exact-HEAD review found no product/runtime issue but found one HIGH
evidence-metadata issue: the earlier artifact cited a non-ancestor commit and an
untracked `.omo` report. After that correction, a new PR review found a real P2
promotion race: the scheduled consumer called the alerter while Docker health
was still `starting`. The runtime remediation was independently cleared, but
that review correctly blocked the now-stale blob evidence. This portable report
therefore records the new workflow/test blobs and the full review progression;
it does not reuse either prior clearance as current-tree proof.

A subsequent PR review found two more P2s in that candidate: the real managed
sibling server Compose has no engine healthcheck, and the compatibility
healthcheck's `5m + 30×10s` failure window was about ten minutes. The current
candidate removes healthcheck availability from the scheduler grace decision
and bounds both independent mechanisms to five minutes.

The first ADR-LITE-026 review of `bff0271813...` found another P2 boundary:
converting a raw `-0.5` second duration with `int()` produced zero before the
future-time check. The current candidate compares the raw duration first and
adds a frozen-clock regression for this exact subsecond case. Because this
remediation changes the exact SHA, all three PR review rounds restart afterward.

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
- The scheduler defers only a `running` container with a validated Docker
  `StartedAt` age below 300 seconds. This works for the managed sibling Compose
  without requiring a healthcheck. Age 300 and older runs the alerter; stopped
  containers do not receive grace; inspect or timestamp parsing failures make
  the workflow fail without dispatching invented status.
- Future timestamps are rejected on the raw duration before integer truncation,
  including subsecond clock skew.
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
- Historical rendered Compose GREEN before the bounded-deadline correction:
  `docker compose --env-file /dev/null -f
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
- Historical startup-grace GREEN, later superseded because managed Compose has
  no engine healthcheck: the extracted workflow returned success with no
  payload only for `starting`. The same contract proves `unhealthy` returns
  nonzero with `recovery_gated`, stopped/no-healthcheck returns nonzero with
  `status_unreadable`, and an inspect failure returns nonzero with no payload.
- Health-independent grace RED: the managed Compose has no engine healthcheck,
  while the prior workflow depended on health `starting`; the compatibility
  healthcheck also allowed roughly ten minutes before `unhealthy`.
- Health-independent grace GREEN: a recent `running|StartedAt` returns success
  without payload; exactly 300 seconds and an old running engine both reach the
  alerter; stopped reaches `status_unreadable`; inspect and invalid timestamp
  fail without payload. The compatibility Compose contract asserts a four-minute
  start period, three retries, and 10-second interval, leaving timeout headroom
  below the five-minute deadline.
- Subsecond-future RED/GREEN: with the extracted workflow clock frozen at
  `2026-08-13T00:00:00Z`, a `StartedAt` 500 ms later originally produced
  `FAIL: subsecond future Docker StartedAt must fail closed before integer
  conversion`; checking the raw duration before `int()` now fails closed with
  no payload.
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
