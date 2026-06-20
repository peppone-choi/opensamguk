# 2026-06-20 ResetStat intake review

Verdict: cleared

## Scope

- Close the live `ResetStat` 409 gap without widening unrelated inherit actions.
- Keep `InheritAction/CheckOwner` out of this loop because its ownership-transfer semantics are a separate PHP action.

## Skill Chain

- `opensamguk-php-oracle`: PHP `legacy/devsam-core/hwe/sammo/API/InheritAction/ResetStat.php:20-183` and Vue `legacy/devsam-core/hwe/ts/PageInheritPoint.vue:686-701` were used as the oracle.
- `webapp-testing`: the UI symptom is the existing frontend submit path receiving the server 409, so this loop used web/API intake tests rather than a visual-only assertion.
- `systematic-debugging`: root cause was isolated to missing typed backend intake wiring after the earlier frontend-only form loop.
- `loop-engineering`: baseline 409 -> one hypothesis -> deterministic local gates -> PR-visible review.

## Baseline

- `api.instantAction("ResetStat")` reached the instant-action surface but was not in `CommandWireMapper.intakeCodes`.
- Because no `TurnDaemonCommand.ResetStat`, result serializer, dispatcher branch, or engine handler existed, the user-facing path returned the generic un-wired 409 instead of executing the legacy reset.

## Root Cause

The previous frontend loop restored the form and POST surface but did not connect the typed daemon command path. That left the command in the "frontend can ask, backend cannot route" state:

- game-api did not translate `ResetStat` into a daemon command.
- common wire had no command/result pair for the daemon stream.
- game-engine dispatcher had no branch or handler.
- handler-side season lockout and inheritance point reads were unavailable to the reset logic.

## Fix Review

- `InheritResets.resetStat` keeps the PHP gates: 15..80 stats, total 165, bonus count/sum, NPC deny, united deny, season lockout, and inheritance-point deny.
- The RNG path uses `serializeSeed(hiddenSeed, "ResetStat", userId)` and weighted bonus distribution only when explicit bonus points are absent.
- Engine application records both inheritance log lines, updates general stats, writes `inheritance_{owner}.previous`, writes `user_{owner}.last_stat_reset`, and increments `inherit_point_spent_dynamic`.
- Dispatcher and handler fallbacks accept both integer and string owner keys for rehydrated world meta snapshots, preventing JSON key-stringification from turning a valid balance or season list into an empty value.
- `CheckOwner` remains intentionally listed as the remaining unimplemented inherit action.

## Adversarial Checks

- Risk: explicit bonus could spend without enough previous points. Covered by `ResetStatTest` insufficient-point denial and handler full-cycle previous write.
- Risk: repeated seasonal reset could pass after rehydrate. Covered by `last_stat_reset` reader and string-key snapshot test.
- Risk: command result could serialize but not dispatch. Covered by common wire round-trip, `CommandWireMapperTest`, and dispatcher route assertion.
- Risk: frontend test could hide the remaining missing inherit action. `web/game/__tests__/api-intake.test.ts` now keeps `CheckOwner` as the un-wired 409 sentinel.

## Verification

- Targeted Gradle ResetStat/common/game-api/game-engine tests passed.
- `CI=true pnpm --dir web/game test -- api-intake.test.ts` passed: 17 files / 86 tests.
- `CI=true pnpm --dir web/game typecheck` passed.
- `git diff --check` passed.
- Broader local `:common:test :logic:test :app:game-api:test :app:game-engine:test` was blocked only by local Docker/Testcontainers: `DockerClientProviderStrategy` could not find a valid Docker environment.

## Production Adoption

Accepted on production after engine-inclusive `s1` reset/reseed.

- Main deploy run `27862293912` passed for `d2188c79677ac99ef70949b95c842258431a2e31`.
- Because `ResetStat` requires `game-engine` changes and the normal deployer promotion bounces only stateless `game-api`/`web-game`, `s1` was first patched to `IMAGE_TAG=d2188c79677ac99ef70949b95c842258431a2e31` and `WEB_GAME_TAG=d2188c79677ac99ef70949b95c842258431a2e31`, then reset with DB/Redis volumes cleared.
- Reset kept server-scoped settings: `SERVER_GENERATION=0`, `SCENARIO_CODE=scenario_1021`, `SCENARIO_SEED_ENABLED=true`, turn term `60`, full join mode, tournament auto-start enabled.
- Post-reset polling reached `health=up`, `deploy/status.currentTag=d2188c79677ac99ef70949b95c842258431a2e31`, `front-info=200`, and authenticated `game-api`/`game-engine` reachability.
- Live browser QA created account `codex37075247`, registered general `코덱스5247`, observed `front-info.general.hasGeneral=true` with `generalId=1681`, and submitted `ResetStat` successfully: HTTP 202, `code=ResetStat` (no 409).
- Live `/game/s1` rendered the game main instead of registration after the general existed: map `700x520`, title `700x20`, canvas `700x500`, city anchors `94`, no frontend console errors, no error text.
- First city click navigated to `/game/s1/city?id=1` and rendered `도시 정보` without 404/500 text or console errors.

## Residual Risk

No known code-level blocker remains for `ResetStat`. Full integration suites still need a Docker-capable runner, which CI should provide.
