# Handoff: OPENSAM-128 → v2 just-in-time persistence owners (shared flush substrate)

- **Date:** 2026-07-21
- **Foundation owner:** ARCH-S2-T3 / OPENSAM-128 (`JdbcFlushExecutor` world-scoped C/U/D)
- **Original consumer (superseded 2026-08-13):** OPENSAM-44 broad v2 persistence batch
- **Active consumers:** OPENSAM-150 for the first product leaf, then each just-in-time product owner
  named by `2026-08-13-opensam-44-contract-crosswalk.md`

## Contract ready for consumers
- All V32 world-owned flush methods take `worldId: WorldId` and bind/predicate `world_id`.
- Mixed `game_kv` preserves global `inheritance` (`world_id IS NULL`) vs world-owned families.
- Architecture tests: `JdbcFlushExecutorWorldScopeTest` (method list + unscoped DML scan).
- Global exception: `inheritance_log` remains user-global (not V32 world-owned).

## Rules for OPENSAM-150 and later just-in-time consumers
- Do not co-widen `JdbcFlushExecutor` / `ChangeRecorder` channels without sequential handoff.
- New entity flush steps must accept process `WorldId` and never emit unscoped live SQL for world-owned tables.
