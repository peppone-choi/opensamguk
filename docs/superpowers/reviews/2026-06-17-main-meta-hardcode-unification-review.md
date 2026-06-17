# Loop Review: game main metadata unification

**Branch:** `codex/deploy-promote-game-stack`
**Reviewer:** Codex loop-engineering
**Date:** 2026-06-17

## Scope

This review covers the hardcoded game-main and admin lifecycle metadata cleanup:

- `front-info.global` is the source for server name, generation, NPC mode text, NPC summary text, tournament minutes, and other-setting text.
- `web/game` renders those fields instead of recomputing them with local constants or fallback generation values.
- `web/gateway` admin create/reset flows send generation to the deployer, and lobby/admin server lists display generation from the registry.
- The deployer contract exposes runtime-created servers through `GET /servers` and persists generation in registry/env during create/reset.

## Findings

### 1. `[P0] Game main no longer fabricates generation or setting text` — CLEARED

Before this change, `GameInfo` rendered a default `1기`, mapped NPC mode locally, calculated tournament minutes locally, and derived the other-setting label from `autorunUser`. That made the main screen drift from server-specific runtime metadata.

After this change, `FrontInfoController` emits the display-ready metadata and `GameInfo` consumes it. `serverCnt` remains as a compatibility alias, but the preferred display field is `generation`. If generation is absent, the UI does not fabricate a generation.

**Evidence:**
- `FrontInfoControllerTest` verifies server display metadata, NPC mode text, NPC summary text, tournament minutes, and other-setting text.
- `GameInfo.test.tsx` verifies tournament minutes and main settings are rendered from front-info metadata.

### 2. `[P0] Admin server loading and generation are runtime registry concerns` — CLEARED

Admin now treats the deployer runtime registry as authoritative when configured, so servers created after gateway boot can be resolved without a gateway restart. The deployer `GET /servers` endpoint returns the registry, and create/reset paths persist `generation`.

**Evidence:**
- `AdminVersionDeployTest` verifies runtime-created server resolution through `/servers`, generation parsing from registry JSON, generation in create/reset request bodies, and `SERVER_GENERATION` server env patching.
- Deployer tests verify `GET /servers`, create env/registry generation, close compatibility, and reset env/registry generation.

### 3. `[P1] Running game servers are not auto-promoted by shared deployment` — CLEARED

The app workflow deploys the shared stack and deployer while preserving existing server `IMAGE_TAG`/`WEB_GAME_TAG` values. Generation changes are tied to admin create/reset/promote flows, not generic shared deploy.

**Evidence:**
- `.github/workflows/deploy.yml` parses as YAML.
- Docker orchestration workflow parses as YAML.
- Deployer tests ran in a remote `golang:1.23` container and passed.

## Verification commands

- `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/deploy.yml"); puts "ok"'`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.controller.FrontInfoControllerTest' :app:gateway-api:test --tests 'opensamguk.gateway.service.AdminVersionDeployTest'`
- XML confirmation: `FrontInfoControllerTest` 18 tests, 0 failures/errors/skips; `AdminVersionDeployTest` 19 tests, 0 failures/errors/skips.
- `pnpm --dir web/game test -- GameInfo`
- `pnpm --dir web/gateway typecheck`
- `pnpm --dir web/game typecheck`
- Docker repo: `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/deploy-orchestration.yml"); puts "ok"'`
- Docker repo: remote `golang:1.23 gofmt -d main.go main_test.go`
- Docker repo: remote `golang:1.23 go test ./...`

## Residual risk

- Authenticated production admin UI still needs a browser pass after deployment.
- Production game data currently needs a non-destructive DB/runtime read before deciding whether to reset or open a new generation.

## Verdict: cleared

No fix-required findings remain for this metadata unification slice.

**Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>**
