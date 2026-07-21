# Handoff: OPENSAM-128 → OPENSAM-44 (shared flush substrate)

- **Date:** 2026-07-21
- **Foundation owner:** ARCH-S2-T3 / OPENSAM-128 (`JdbcFlushExecutor` world-scoped C/U/D)
- **Consumer:** OPENSAM-44 (v2 entity ChangeRecorder → JdbcFlushExecutor persistence)

## Contract ready for consumers
- All V32 world-owned flush methods take `worldId: WorldId` and bind/predicate `world_id`.
- Mixed `game_kv` preserves global `inheritance` (`world_id IS NULL`) vs world-owned families.
- Architecture tests: `JdbcFlushExecutorWorldScopeTest` (method list + unscoped DML scan).
- Global exception: `inheritance_log` remains user-global (not V32 world-owned).

## Rules for OPENSAM-44
- Do not co-widen `JdbcFlushExecutor` / `ChangeRecorder` channels without sequential handoff.
- New entity flush steps must accept process `WorldId` and never emit unscoped live SQL for world-owned tables.
