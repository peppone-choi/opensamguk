# Review: socket-proxy dependency for every deployer interaction

Scope: `.github/workflows/deploy.yml`, `.github/workflows/reset-game-server.yml` (opensamguk) and `.github/workflows/deploy-orchestration.yml`, `.github/workflows/recreate-server.yml`, `.github/workflows/start-server.yml` (opensamguk-docker) — workflow-level guarantee that the deployer can reach Docker before it is asked to do anything
Verdict: cleared

This cleared review covers the workflow-level mitigation only. **It is not a root-cause fix.** Why the `socket-proxy` container disappeared on 2026-08-04 remains UNKNOWN, and the deployer-side changes that actually close the admin-UI path are a separate branch and a separate review. Merging this does not make the incident impossible; it makes it detectable and self-healing along the workflow paths.

## Incident

Admin server reset stopped working. Chain, each link verified in code:

1. The `socket-proxy` container was absent.
2. deployer reaches Docker only through it — `DOCKER_HOST=tcp://socket-proxy:2375` (`docker-compose.shared.yml:157`).
3. Every deployer `docker compose` invocation exited 1.
4. Reset failed inside `downServerStack` (`deployer/main.go:4093-4102`), which returned into `failAfterIrreversible` (`main.go:2949-2951`), setting `repairRequired: true` and pinning the lifecycle journal at `down-pending` with maintenance `drained`.
5. Reset, server-create, and env-change were all blocked until a manual `POST /maintenance/repair`.

Deploys and promotes kept working the whole time, because the self-hosted runner executes compose directly on the box. Only deployer-mediated administration was dead. Nothing surfaced the real Docker error: lifecycle jobs are in-memory, so the `detail` evaporated with the process.

## Why it recurred structurally

`docker-compose.shared.yml:174-175` declares `deployer.depends_on: socket-proxy`, but every workflow recreates the deployer with `--no-deps`, which does not start dependencies — and **no workflow anywhere started `socket-proxy` explicitly**. Nothing in either repository guaranteed the dependency the deployer cannot run without.

## Findings and resolution

Findings are recorded in the order the independent reviewer raised them. The reviewer was a separate agent with no authorship stake; the author did not self-approve.

- The first review rejected the author's initial placement: in `recreate-server.yml` and `start-server.yml` the new `up -d socket-proxy` sat inside `if [ "$DEPLOYER_BOOTSTRAP" = true ]`. That branch recreates nothing when the deployer is already running — which is exactly the incident state (deployer alive, socket-proxy gone). The guarantee and the `COMPOSE` array definition were hoisted out of the conditional in both files.
- The first review found the **incident path itself was untouched**. `reset-game-server.yml:302-309` is the workflow that calls `POST /servers/reset`, runs on the same `gcp-prod` runner, and was not in the original change. The guarantee plus a reachability check now precede the reset call at `:287-300`.
- The first review found the three new insertions violated the surrounding failure convention. Under `set -euo pipefail` a `run_bounded` timeout (124) would exit without an `::error::` line, leaving the control plane maintenance-closed — every mutation 503 — silently, while every other failure point in those files prints "leaving maintenance marker closed". All three are now wrapped with the same message and an explicit `exit 1`.
- The first review found `up -d socket-proxy` proves container existence, not reachability. `socket-proxy` has no healthcheck and `depends_on` carries no condition (`docker-compose.shared.yml:174-175, 185-221`), so a crash-looping proxy with an unchanged config hash passes `up -d` untouched. All five sites now verify `docker exec opensamguk-deployer docker version`, which traverses the real `DOCKER_HOST` path.
- The first review found the 60s timeout on the new step could expire on a cold image pull and fall straight into the silent maintenance-closed failure above. Timeouts were raised to match each file's deployer-recreate value (180 in `recreate-server.yml`, 300 in `start-server.yml` and `deploy-orchestration.yml`).
- The first review found the new Korean comment described `--no-deps` as the reason the container disappeared. `--no-deps` explains why the stack does not self-heal, not why the container vanished; encoding the wrong causality would misdirect the next investigation. The comment now states the bootstrap-versus-running distinction it actually justifies.
- The re-review found `deploy-orchestration.yml:186` places the reachability check after `ensure_lifecycle_recovery`, unlike the other two files. With an unreachable proxy and a pending journal, that ordering runs `/maintenance/repair` → `repairLifecycleJournal` → `downServerStack` without Docker first, replacing the precise "cannot reach Docker through socket-proxy" diagnosis with a blurred "lifecycle recovery cannot be verified". No state damage, because `main.go:839` already sets `repairRequired` before the down attempt. Resolved by moving the check ahead of `ensure_lifecycle_recovery`, unifying all three files.
- The re-review found the reachability check has zero retries against a **just-created** proxy — the very case this change targets. On the non-bootstrap path in `recreate-server.yml` and `start-server.yml` the intervening `DEPLOYER_BOOTSTRAP` block is skipped entirely, so `up -d socket-proxy` and `docker version` run back to back and haproxy may not have bound yet. Resolved with a short bounded retry loop reusing the files' existing polling idiom. `deploy-orchestration.yml` has no such race: a deployer recreate and a readyz poll sit between the two steps.

## Claims tested and rejected

- **Missing restart policy** — rejected. `socket-proxy` carries `restart: unless-stopped` (`docker-compose.shared.yml:188`). Reboot and Docker-daemon restart are excluded as explanations.
- **Orphan removal or pruning** — rejected. Neither repository has a path applying `--remove-orphans` to the shared project. No `docker rm`, `docker stop`, `system prune`, or `container prune` against it; no cron, no systemd unit. `deploy.yml:311` runs `docker image prune -af`, which does not remove containers.
- **Stale DNS after recreate** — rejected. The deployer execs a fresh docker CLI per call (`main.go:4298`), so a new proxy IP is resolved on the next invocation.
- **Maintenance lease expiring during the added step** — rejected. Leases have no TTL (`main.go:297-302`).
- **`docker version` failing during deployer warm-up** — rejected. The deployer image is `docker:27-cli` (`deployer/Dockerfile:18`), `DOCKER_HOST` is container env inherited by `docker exec` (`shared.yml:157`), and the proxy ACL grants `VERSION: 1` (`shared.yml:199`). The check needs the process, not the HTTP port.
- **Diagnostics lost to `>/dev/null`** — rejected. That redirects fd 1 only; the "Cannot connect to the Docker daemon at tcp://socket-proxy:2375" message goes to stderr and stays in the log. Only the success-path version table is suppressed.

## Known limits — this change does not close these

- **Root cause is UNKNOWN.** No path in either repository deletes `socket-proxy`. The single code-confirmed destructive path is a server id of `hared`: `reservedPublicServerIDs` (`main.go:82-84`) reserves only `all`, so `projectForServerID("hared")` resolves to `opensamguk-shared` and `downServerStack` runs `down --volumes --remove-orphans` against the shared project, removing socket-proxy, deployer, gateway, and nginx. Whether that caused 2026-08-04 is UNKNOWN. Separate ticket.
- **The admin-UI path is not covered, by construction.** Admin reset/create/delete run through `DeployService.kt` → deployer HTTP at arbitrary times, outside any workflow. The 2026-08-04 incident was an admin reset. No workflow-level guarantee can reach it.
- **The reachability check is a point-in-time snapshot.** `POST /servers/reset` returns a `jobId` immediately and the real `downServerStack` runs asynchronously afterwards (`main.go:2911`). The check narrows the window; it cannot close it.
- **`deploy.yml` has no `/maintenance/enter` drain**, unlike the control-plane workflows. `flock` at `:225` serializes runner-side workflows but cannot block a deployer job an admin started. A socket-proxy recreate or deployer force-recreate can still interrupt an in-flight reset. Pre-existing; the new line does not introduce it but does widen the window slightly. Separate ticket.
- **`deploy.yml:257` has no timeout** on `up -d socket-proxy`. That file has no `run_bounded` helper, so introducing one was out of scope. A cold pull could block while holding `/tmp/opensamguk-production.lock`.

The two changes that do close the admin-UI path — a Docker preflight before the irreversible boundary in `downServerStack`, and a Docker reachability check in `/readyz` (`main.go:1831-1845`, which today returns `{"status":"ready"}` with the proxy dead, making the incident invisible to every health gate including `wait_for_deployer_ready`) — are on a separate branch under separate review.

## Evidence

- Five workflows parse under `yaml.safe_load`; every embedded `run` script passes `bash -n`.
- `reset-game-server.yml`: insertion at `:287-300` precedes the reset POST at `:302-309`; `cd "$STACK"` at `:110` makes the relative `-f docker-compose.shared.yml --env-file .env` resolve correctly; the file's existing `docker inspect`/`docker exec`/`docker run` calls are sudo-less and the new block matches.
- Reachability-check placement verified to follow container existence on every path: after the `maintenance_get` polling loop in the control-plane bootstrap branches, after the already-running check on the non-bootstrap branches, and after the existing `/env/shared` exec probe in `deploy.yml`.
- Production was recovered manually before this change: `socket-proxy` started (deployer then reported `client=27.5.1 server=29.7.1`), then `POST /maintenance/repair` returned `{"state":"open"}`. Reset ran to completion — `repairRequired` gone, journal deleted, `spep-game-pgdata` created `2026-08-04T07:40:02Z`, 336 generals / 20 nations / 94 cities, turn daemon `running` with `loopAlive: true`, nginx/gateway/game health all 200. This change pins that manual remediation into the workflows; it is not itself evidence of a deployment.
- **No deployment has occurred from this review.** Nothing is merged; no environment was changed by it.
- No `.env` file, token, or secret was read or printed during the investigation or the review.
