# OPENSAM-368 production daemon-health consumer contract

## Authority and ownership

This task is authorized by the root orchestrator for the isolated
`codex/opensam368-health-consumer` worktree. The shared `.ai/` records are
stale and intentionally remain untouched. This task-local contract owns only:

- `docker-compose.production.yml` game-engine healthcheck;
- the scheduled daemon-health alert consumer and its hermetic contract;
- recovery-gated deploy verification if it still permits a successful skip;
- this task-local loop evidence and independent review artifact.

The game-engine health implementation, all production operations, webhook
configuration/delivery, restart/deploy actions, and unrelated workflows are
outside this task.

## Frozen acceptance contract

1. The production compatibility compose declares the same internal Actuator
   probe shape as local compose: `curl -sf http://localhost:8082/actuator/health`.
   Its start period is explicitly long enough for daemon startup/catch-up.
2. The healthcheck is observational. `restart: unless-stopped` remains a
   process-exit policy, so recovery-gated `DOWN` never creates an automatic
   restart loop.
3. The scheduled/manual alert consumer finds every canonical shared-stack
   engine name: `s` plus a lower-case public `[a-z0-9]{1,48}` ID, followed by
   `-game-engine` (for example `spep-game-engine` and `ss1-game-engine`).
4. A non-ready recovery gate sends a bounded `DOWN/recovery_gated` alert through
   the configured secret only; no raw recovery reason or webhook value may
   appear in logs or payload fields.
5. Docker inventory-query failure fails closed. An empty inventory remains a
   successful no-op because a box can legitimately have no game server yet.
6. Deploy verification treats `recoveryReady != true` as failure before an
   intentional pause in both initial and polling branches; it cannot silently
   report a skipped successful verification.
7. All behavioral evidence is hermetic: local command stubs and Compose render
   only. No live Docker engine, production runner, or webhook is invoked.
8. The scheduled consumer defers exactly the Docker health `starting` state,
   which is bounded by the engine healthcheck's five-minute start period. An
   `unhealthy`, stopped/no-healthcheck, or unreadable inspect result never gains
   startup grace: the first two continue through the alert path and an inspect
   failure fails the workflow closed without fabricating daemon diagnostics.

## Baseline and one hypothesis

- Baseline: `origin/main` at `f4ee9135ad6cbce1c6cfb28f7113d7742f478282` has a
  standalone alert workflow/script, but both inventory and script validation
  require `s[0-9]...`. The production naming contract derives `s${public}` for
  lower-case alphanumeric public IDs, so `spep-game-engine` is silently skipped.
  It also lacks the production compatibility compose healthcheck.
- Hypothesis: align the consumer's bounded identifier grammar with the existing
  deploy/reset naming contract and add the observation-only Compose healthcheck;
  the same hermetic contract will then alert `spep` while retaining inventory
  and recovery fail-closed behavior.

## Scoring and adoption rule

The scorer is `tools/ops/daemon_health_alert_contract_test.sh` plus a rendered
production Compose assertion. Before the implementation it must fail for the
new `spep` case (RED); after the narrow change it must pass (GREEN). Any failed
existing safety case or a review verdict other than `cleared` rejects the change.
