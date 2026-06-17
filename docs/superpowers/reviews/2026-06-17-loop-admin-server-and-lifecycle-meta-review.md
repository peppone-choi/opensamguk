# Loop Review: admin deployer registry and lifecycle meta

**Branch:** `codex/loop-admin-lifecycle-fixes`
**Reviewer:** Codex loop-engineering
**Date:** 2026-06-17

## Scope

This review covers two bug/parity slices and one agent-surface documentation slice:

- Admin server loading and server lifecycle controls in `gateway-api`.
- Seed/load lifecycle meta needed to prevent seeded rulers and nations from disappearing after early turns, including `infra/src` seeding behavior in `ScenarioImporter`.
- Shared Claude/Codex loop-engineering documentation.

## PHP and runtime evidence

### Admin server registry

- The external deployer owns runtime server state. The gateway must read `GET /servers` instead of treating boot-time `SERVER_REGISTRY_JSON` as the only truth.
- The deployer server lifecycle endpoints are `POST /servers/create`, `POST /servers/close`, and `POST /servers/reset`.
- UI-facing server ids remain `s1`, while deployer create ids use the numeric form (`1`) for `s1`; action ids remain `s1` because the deployer trims the leading `s`.

### Lifecycle meta / nation disappearance

- `legacy/devsam-core/hwe/sammo/ResetHelper.php` seeds `killturn` from `4800 / turnterm` and divides by 3 for `npcmode == 1`.
- `legacy/devsam-core/hwe/sammo/TurnExecutionHelper.php` treats `killturn <= 0` as the death/succession path.
- `legacy/devsam-core/hwe/sammo/General.php` routes ruler death through succession. In the Kotlin port, a missing `killturn` defaulted to `0` in the turn path, which could trigger ruler death and nation deletion instead of preserving the fresh seed.

## Findings

### 1. `[P0] Admin server loading no longer depends only on static registry` — CLEARED

`DeployService.registeredServers()` now reads the deployer runtime registry and falls back to `ServerRegistry` only if the deployer is unavailable. `VersionService` uses this runtime-aware source for admin version/status listing, and `DeployService.resolve(serverId)` can resolve a server created after gateway boot.

**Evidence:**
- Baseline `AdminVersionDeployTest`: 19 tests, 4 failed.
- After change: `:app:gateway-api:test --tests opensamguk.gateway.service.AdminVersionDeployTest` passed.
- Added coverage verifies `/servers/create`, `/servers/close`, `/servers/reset`, runtime-created server resolution, and unknown-server registry lookup.

### 2. `[P0] Fresh seed lifecycle meta is now explicit and loader repairs older rows` — CLEARED

`ScenarioLifecycleMeta` centralizes the PHP reset formula for `killturn` and `deadyear`. `ScenarioImporter` writes this meta for new seed rows, and `WorldSnapshotLoader` enriches loaded generals from DB `dead_year`, `turnterm`, and `npcmode` so already-seeded worlds are not silently loaded with an empty lifecycle map.

**Evidence:**
- Baseline helper test: compile red before `ScenarioLifecycleMeta` existed.
- After change: `:common:test --tests opensamguk.common.constants.ScenarioLifecycleMetaTest` passed.
- `ScenarioBootIT` now asserts the seeded generals load with the expected `killturn`, `deadyear`, and that the first tick does not reduce the two-nation seed. The local environment skipped the Testcontainers body because Docker was unavailable, so this is compiled coverage locally and executable coverage in Docker-enabled CI/dev.

### 3. `[P1] Shared loop-engineering doc avoids Claude/Codex drift` — CLEARED

`docs/superpowers/LOOP_ENGINEERING.md` is now the shared source of truth. `.claude/skills/loop-engineering/SKILL.md` and `.agents/skills/loop-engineering/SKILL.md` are provider adapters that point at the shared doc. `AGENTS.md` and `CLAUDE.md` both reference the shared doc.

**Evidence:**
- `git diff --check` passed.
- The adapter docs no longer duplicate the full loop rule body.

## Live read-only checks

These checks were run against the current production host before merge/deploy:

- `https://sam.peppone.dev/health` -> HTTP 200, nginx status up.
- `https://sam.peppone.dev/api/servers` -> HTTP 200, includes `s1` with `/game/s1`.
- `https://sam.peppone.dev/api/gateway/actuator/health` -> HTTP 200, Spring health UP.
- `https://sam.peppone.dev/api/gateway/admin/version` -> HTTP 403 without admin auth, which confirms the endpoint is admin-gated but does not verify authenticated UI rendering.

## Verification commands

- `git diff --check`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --rerun-tasks --tests "opensamguk.common.constants.ScenarioLifecycleMetaTest"`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --rerun-tasks --tests "opensamguk.gateway.service.AdminVersionDeployTest"`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --rerun-tasks --tests "opensamguk.engine.boot.ScenarioBootIT"` (BUILD SUCCESSFUL, test body skipped locally by Docker/Testcontainers availability)
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :app:gateway-api:test :app:game-engine:test --rerun-tasks --tests "opensamguk.common.constants.ScenarioLifecycleMetaTest" --tests "opensamguk.gateway.service.AdminVersionDeployTest" --tests "opensamguk.engine.boot.ScenarioBootIT"`

## Residual risk

- Authenticated production admin UI was not exercised in-browser in this loop because no admin session was available in the current context.
- Production `world_state` nation count and post-deploy long-turn behavior still need live DB/runtime verification after the merged build is deployed.
- Full backend parity gate was not run in this slice; targeted tests and strict review gate cover the touched surfaces.

## Verdict: cleared

No fix-required findings remain for this slice. The residual items are deployment/runtime verification tasks, not blockers for merging the code fixes.

**Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>**
