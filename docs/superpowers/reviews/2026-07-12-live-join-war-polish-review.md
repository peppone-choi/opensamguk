# 2026-07-12 live join, war, and join-form review

## Scope

- Mapped behavior areas: `app/game-engine`, `infra/src`, `logic/src`, and `web/game`.
- Fix the production tick failure observed on s1 while an AI sortie was resolving.
- Keep the legacy three-stat presets and add a five-stat full-random convenience preset.
- Stop the join form from restoring an account identifier after the player clears the general name.

## Evidence

- s1 `s1-game-engine` repeatedly failed with `IllegalArgumentException: 적절한 id는 1000이상이어야합니다:0` from `GameUnitConst.byId(0)` through `ProcessWar` and `CheChulbyeong`.
- The live `general` table contained 393 rows with `crew_type_id='0'`; the engine loader copied that value unchanged.
- PHP grand truth uses `GameUnitConst::DEFAULT_CREWTYPE` when constructing a `General` (`legacy/devsam-core/hwe/sammo/General.php:118`, `legacy/devsam-core/hwe/sammo/GameUnitConstBase.php:25`).
- `WorldSnapshotLoader` now normalizes invalid persisted crew types to `1100`; `ScenarioImporter` writes `1100` for fresh rows; war entry points defensively apply the same fallback.
- The join form previously initialized and re-applied `name` from `frontInfo`/`joinForm.member.name`. It now starts empty and reset also clears it.

## Verification

- `:logic:test --tests opensamguk.logic.war.ProcessWarWrapperTest --tests opensamguk.logic.war.CheChulbyeongTest`: `BUILD SUCCESSFUL`.
- `pnpm exec vitest run __tests__/join-route.test.tsx --reporter=dot`: 5 tests passed.
- `:infra:compileKotlin :app:game-engine:compileKotlin`: `BUILD SUCCESSFUL`.
- `git diff --check`: passed.

## Review outcome

Verdict: cleared

The production failure is a persisted-data/default-value boundary defect, not a short-turn scheduling delay. The fix preserves valid crew types and only substitutes the PHP default for invalid values. The legacy three-stat presets remain unchanged; the new full-random preset is UI-only and enforces the existing five-stat range and total.

Tests: `ProcessWarWrapperTest`, `CheChulbyeongTest`, `join-route.test.tsx`, engine/infra Kotlin compilation, and `git diff --check` passed.
