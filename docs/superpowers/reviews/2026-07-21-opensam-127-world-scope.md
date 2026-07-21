# Review: OPENSAM-127 world-scope loader/query/Redis

- **Ticket:** OPENSAM-127 / GH #273
- **Branch:** `codex/op-127-world-scope`
- **Date:** 2026-07-21
- **Verdict: cleared**

## Scope checked
- Redis stream/result/realtime keys include `w{worldId}` (`TurnDaemonStreamKeys`, `commandResultKey`, `gameEventChannel`).
- Process-world facades for rank, auction count, diplomacy, log feed, game_kv (inheritance global exception), message, world_state.
- Five-cohort general/city/nation/turn facades retained.
- Cross-world negatives: `Op127CrossWorldCohortIT`, architecture test, StreamKeysTest two-world isolation.
- Flush rewrite left for OPENSAM-128 (not co-widened here beyond MessageEntity/GameKvEntity column maps needed for reads).

## Adversarial probes
1. Default world guess on missing config — rejected via `WorldId` require + required `OPENSAMGUK_WORLD_ID` / `opensamguk.world-id`.
2. Same local IDs across worlds — IT proves rank/auction/diplomacy/log/world_state isolation for process world=1.
3. Redis key collision across worlds — StreamKeysTest asserts different command stream keys for w1 vs w2.
4. Unscoped public JpaRepository for converted cohorts — architecture test enforces raw+facade pattern.

## Residual (not fix-required for 127 GWT)
- Some secondary JPA readers (board/vote/hall/history/troop/admin logs) still unscoped; follow-up under 127 residual or later tickets if GWT expands.
- Full `tools/parity/gate.sh backend` not claimed (module-focused XML only).

## Evidence
- Focused + redis/engine tests: BUILD SUCCESSFUL; JUnit failures=0 errors=0 (see implementer scratch `op127/`).
