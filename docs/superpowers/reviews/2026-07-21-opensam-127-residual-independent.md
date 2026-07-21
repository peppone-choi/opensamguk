# Independent review: OPENSAM-127 residual world-owned reads

- **Scope:** residual cohorts after #302 (history, board, vote, troop, hall, statistic, diplomacy_letter, general_access_log, world/nation/admin log, nation_env)
- **Date:** 2026-07-21
- **Verdict: cleared**

## Adversarial checklist

1. **History (yearbook_history)** — facade injects process `worldId`; `findAllByOrderByYearAscMonthAsc` no longer cross-world.
2. **Board/vote/troop/hall/access/diplomacy_letter** — raw methods require `worldId`; public API unchanged for controllers.
3. **Log native queries** — `world_id = :worldId` on WorldLog/NationLog/AdminGeneralLog SQL.
4. **Architecture** — `WorldScopedReadRepositoryArchitectureTest` enumerates residual files; multi-line fun signatures collapsed for worldId check.
5. **IT** — `Op127ResidualWorldScopeIT` seeds w1/w2 identical local shapes; process world=1 returns only w1.
6. **inheritance_log** — intentionally global (user-scoped, not V32 world-owned); not in residual GWT list as world-owned.

## Evidence

- `:app:game-api:test` Architecture + Op127ResidualWorldScopeIT BUILD SUCCESSFUL, failures=0.

## Non-goals

- Independent of OPENSAM-128/129 lands already on main.
- Does not claim prod cutover or second-world admission.
