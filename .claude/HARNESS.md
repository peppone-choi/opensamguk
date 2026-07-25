# HARNESS.md — opensamguk work and parity map

> This is a navigable operating map, not a second source of truth. Project rules live in
> [`CLAUDE.md`](../CLAUDE.md); task routing lives in
> [`docs/agent/README.md`](../docs/agent/README.md); current bounded status lives in
> [`docs/agent/project-overview.md`](../docs/agent/project-overview.md) and the relevant
> ledger. Use those sources when this map and a more specific document differ.

## 1. Start with the Agent OS

Every non-trivial task starts with `.ai/task.md`, `.ai/decisions.md`, and the router in
`docs/agent/README.md`. Respect `.ai/ownership.md`: one writer owns a file at a time, and a
worker preserves all concurrent changes outside its assigned scope.

| Need | Claude entrypoint | Codex entrypoint | Read next |
|---|---|---|---|
| Establish task contract | `/os-start-task` | `$os-start-task` | `.ai/task.md`, `.ai/decisions.md`, router |
| Diagnose a discrepancy | `/os-analyze` or `/os-debug` | `$os-analyze` or `$os-debug` | PHP evidence, failure/verification docs |
| Implement an approved scope | `/os-implement` | `$os-implement` | architecture and coding rules |
| Verify a change | `/os-verify` | `$os-verify` | `docs/agent/verification.md` |
| Obtain an adversarial review | `/os-review` | `$os-review` | `docs/agent/lifecycle-review.md` |
| Record a handoff/checkpoint | `/os-checkpoint` | `$os-checkpoint` | `.ai/current-state.md`, `.ai/handoff.md` |
| Plan tickets or exercise an end-to-end flow | `/os-plan-tickets`, `/os-e2e` | `$os-plan-tickets`, `$os-e2e` | the routed task documents |

The normal path is contract → evidence/plan → narrow implementation → verification → independent
critique → checkpoint. `docs/superpowers/WORKING_SYSTEM.md` is the process authority. Do not
commit, push, merge, deploy, access secrets, or delete data without explicit human approval.

## 2. July 2026 bounded status

As of 2026-07-25, the CQRS hardening foundations below are on `main` as **build-only** work.
They do not claim a production cutover, second-world admission, or replica activation.

- **World-scoped identity:** loader/query/reservation/Redis/flush paths are scoped by
  `world_id`, with two-world isolation coverage.
- **Flush generation and recovery:** `DeltaGenerationSession`, `world_version` CAS,
  `writer_epoch`, and `FlushRecoveryGate` protect generation/fence/recovery boundaries.
- **Durable command path:** commit `command_inbox` to DB before acceptance, send a best-effort
  Redis wake, let the engine claim/apply, then flush state effects + durable result + outbox +
  inbox terminal transition in one DB transaction; only after that commit perform XACK and result
  publication.
- **Bounded reads:** a hot/cold catalog guards boot/runtime read seams; archive reads are
  bounded or on-demand; game-api has a primary `minVersion` visibility barrier that can return
  `409 VERSION_NOT_VISIBLE` for stale reads.

The detailed current status and activation residuals are in
[`docs/agent/project-overview.md`](../docs/agent/project-overview.md) and
`.ai/current-state.md`; PHP/UI gap evidence belongs in `docs/loops/*/LEDGER.md`. Do not turn a
build-only foundation into an activation claim without its separate gate.

## 3. Parity closure map

**Grand-truth order:** `legacy/devsam-core` (PHP) wins every behavioral divergence;
`legacy/devsam-core2026` is structural context only; Kotlin/Next is the port. `legacy/` is
git-ignored. Capture actual PHP evidence before changing a parity-sensitive behavior.

For one reservable command, use `/parity-close <command>` or `$parity-close <command>` when the
local process skill is available. Its seam is:

```text
PHP path + line range → real golden capture (when RNG-bearing) → logic/replay gate
  → game-api reserve + CommandWireMapper intakeCodes/toCommand
  → TurnDaemonCommand → engine dispatcher/handler
  → ChangeRecorder created/dirty/deleted → JdbcFlushExecutor
  → web/game submit route → independent review
```

An action absent from `intakeCodes` can precheck as available and still be denied by the engine;
that is an open parity gap. Keep comments in Korean. Identifiers remain conventional English,
while game content and PHP-parity log strings retain their required Korean and markup exactly.

Non-negotiable checks:

1. RNG is draw-for-draw: order, count, and arguments matter; a battle threads one
   `RandUtil(warSeed)` reference.
2. `PhpRound` is half-away-from-zero; PHP truncation and `ceil()` behavior must remain exact.
3. Korean log bytes, Josa, markup, and execution order are observable parity behavior.
4. The daemon writes only through `ChangeRecorder` → `JdbcFlushExecutor`; JPA is read/precheck
   only on this path.
5. Goldens come from actual PHP capture. Never fabricate or weaken a fixture/test; quarantine
   only with proof and a backlog record.
6. Preserve PHP insertion and stable-sort behavior with `LinkedHashMap` where required.

## 4. Verification map

Use [`docs/agent/verification.md`](../docs/agent/verification.md) to choose the smallest gate
that covers the change. Gradle exit status alone is not proof: inspect `BUILD SUCCESSFUL` output
and test XML (`failures="0" errors="0"`). Testcontainers may be skipped when Docker is unavailable;
a failed integration test is not a skip.

| Change | Minimum evidence |
|---|---|
| RNG/logic parity | Targeted `*GoldenTest`/`*ReplayGateTest`, then `tools/parity/gate.sh logic` when applicable |
| Backend-wide or pre-commit proof | `tools/parity/gate.sh backend` with XML evidence |
| API/engine/infra | The routed module test plus focused integration coverage |
| Next.js surface | App typecheck/test and browser observation when behavior changes |
| Docs or agent configuration | `git diff --check`, path/link checks, `tools/agent-system/check.py` |

An independent critique is required for non-trivial work. A `fix-required` review blocks shipping
or merging. Record the exact observed evidence; do not mark an unrun gate as green.

## 5. Production and deployment boundary

The production control plane is the separate
[`opensamguk-docker`](https://github.com/peppone-choi/opensamguk-docker) repository. It owns the
shared/server split:

- The application workflow builds and publishes immutable GHCR images.
- Its self-hosted deploy job synchronizes `opensamguk-docker` and refreshes only the shared
  stack (including deployer, gateway, and nginx).
- Each running game server retains its own `servers/<id>.env` image pins (`IMAGE_TAG` and
  `WEB_GAME_TAG`); a server promotion, re-seed, or new season is an explicit approved operation
  outside that shared-stack refresh.

`docker-compose.production.yml` and `scripts/deploy.sh` in this repository are
**compatibility-only** surfaces, not the source of truth for current multi-server production
operations. Follow the approved `opensamguk-docker` runbook and obtain explicit human approval
before any production action.

Two historical lessons are now operational invariants:

- Recreate/reload nginx after shared upstreams are healthy so it resolves their current container
  addresses and avoids stale-DNS 502s.
- Health endpoints are necessary but not sufficient. For a running game server, verify a
  world-clock advance; an intentionally empty admin-created server is the documented exception.

## 6. Quick map

| You need to… | Use |
|---|---|
| Understand architecture and current scope | `docs/agent/architecture.md` + `docs/agent/project-overview.md` |
| Close one PHP parity gap | `parity-close` + PHP oracle protocol |
| Validate a change | `docs/agent/verification.md` + `$os-verify` / `/os-verify` |
| Review a completed slice | `$os-review` / `/os-review` and an independent critic |
| Work on UI parity | `hwe/ts/` evidence → `webapp-testing` → the loop ledger |
| Operate or deploy | `docs/agent/lifecycle-ops.md` and the approved shared-stack runbook |

This harness makes the next evidence-backed slice easier to run without replacing the project’s
parity, ownership, or approval rules.
