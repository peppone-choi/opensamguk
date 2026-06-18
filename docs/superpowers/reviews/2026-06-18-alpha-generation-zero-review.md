# Loop Review: alpha generation zero

**Branch:** `codex/allow-generation-zero`
**Reviewer:** Codex loop-engineering
**Date:** 2026-06-18

## Scope

This review covers the alpha-season reseed and the guardrails needed so `0기` remains a valid runtime generation:

- Gateway admin create/reset validation.
- Gateway admin UI numeric controls for server generation.
- Production UI/API observation after reseeding `s1`.
- The matching deployer change is tracked in the `opensamguk-docker` PR for the same branch name.

## Legacy and runtime evidence

- Legacy UI evidence: `legacy/devsam-core/hwe/ts/components/GameInfo.vue:2-4` renders the server count directly as `{{ frontInfo?.global.serverCnt }}기`; it does not impose a lower bound in the display layer.
- Current game UI evidence: `web/game/components/game/GameInfo.tsx:28-32` renders `global.generation ?? global.serverCnt`, so `0` must be preserved as a real value, not treated as absent.
- Current type evidence: `web/game/lib/types.ts:32-36` models `generation?: number`, so the invalid state is absence/null, not zero.

## Loop

- Baseline: production `s1` needed to be reseeded as alpha `0기`, but admin/gateway/deployer validation still rejected generation values below `1`.
- Hypothesis: generation validation and numeric input lower bounds were copied from production-season assumptions; allowing `0` at the validation edge is sufficient if JSON/meta preserves zero.
- Grader: targeted gateway tests, web gateway typecheck, deployer tests, production API/UI observation.
- Merge/rollback rule: keep the change only if `0` is accepted by create/reset paths, preserved in registry/API/UI, and existing tests still pass.

## Findings

### 1. `[P0] Admin validation no longer blocks alpha generation zero` -- CLEARED

`DeployService.validGeneration()` now accepts `0`, and both create/reset error messages describe `0 이상의 숫자`. Admin UI numeric inputs use `min="0"` and client-side validation matches the backend.

**Evidence:**
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --tests 'opensamguk.gateway.service.AdminVersionDeployTest' --rerun-tasks` passed.
- Added gateway tests cover create and reset requests carrying `"generation":"0"`.
- `/usr/local/bin/pnpm typecheck` passed in `web/gateway`.

### 2. `[P0] Production `s1` is reseeded as alpha generation zero` -- CLEARED

The live `s1` stack was explicitly reset without preserving the old generation. The server registry and browser-visible API now report generation zero.

**Evidence:**
- `GET https://sam.peppone.dev/api/servers` returned `s1` with `"generation":0` and `gameUrl:"/game/s1"`.
- Fresh DB count after reseed: `world_state=181/1`, nations `2`, cities `94`, generals `678`.
- `https://sam.peppone.dev/game/s1` returned HTTP 200 and no "게임 설정을 불러오지 못했습니다" text in browser observation.
- Authenticated admin browser observation rendered the server tab with `통일 서버0기` and no server-load error text.

### 3. `[P1] Deployer registry must not omit zero` -- COVERED IN DOCKER PR

The deployer-side bug was root-caused to Go JSON `omitempty` on `registryEntry.Generation`, which dropped `0` from `SERVER_REGISTRY_JSON`. The paired docker PR removes that omission and adds create/reset tests for generation zero.

**Evidence:**
- First deployer test run failed because registry JSON omitted `"generation":0`.
- After the docker patch, `docker run --rm -v "$PWD":/src -w /src/deployer golang:1.24-alpine go test ./...` passed in `opensamguk-docker`.

### 4. `[P1] Reset modal must preserve the current alpha generation` -- CLEARED

Fresh reviewer found that the reset modal defaulted to `(server.generation ?? 0) + 1 || 1`, so a `0기` server opened the reset form as `1`. The default now uses `server.generation ?? 1`: explicit zero is preserved, while missing generation still falls back to `1`.

**Evidence:**
- Fresh reviewer finding: `web/gateway/app/admin/page.tsx:451` would promote `0기` to `1기` if an admin reset without editing the field.
- `/usr/local/bin/pnpm typecheck` passed after the fix.

## Verification commands

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --tests 'opensamguk.gateway.service.AdminVersionDeployTest' --rerun-tasks`
- `/usr/local/bin/pnpm typecheck` in `web/gateway`
- `docker run --rm -v "$PWD":/src -w /src/deployer golang:1.24-alpine go test ./...` in `opensamguk-docker`
- Playwright authenticated production check for `/admin` server tab and `/game/s1`

## Residual risk

- The current production gateway image still predates the code-side `0기` validation/default-value patch until this PR deploys; the live state itself has already been reseeded to `0기`.
- Game header `0기` is visible only after a user has a general; accounts without a general are routed to the registration flow before `GameInfo` renders.

## Verdict: cleared

No fix-required findings remain for the `0기` alpha reseed slice.

**Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>**
