# Review — loop/isunited-world-state-persist (PR #140)

Scope: `world_state.isunited` 영속화 + 재기동 로드.

Reviewer: `oh-my-claudecode:code-reviewer` (cross-agent critique).
Date: 2026-06-23.

## Source of truth

- PHP devsam/core `hwe/sammo/World/CheckEmperior.php` 및 `InvaderEndingAction` — `game.env.isunited` 플래그.
- opensamguk `InMemoryTurnWorld.setIsunited()` / `getIsunited()` — 기존 메모리 전용 경로.
- Flyway `V1__init.sql` — `world_state.isunited INT NOT NULL DEFAULT 0` 컬럼 이미 존재.

## Changes

- `DatabaseHooks.toFlushPayload` 두 반환점에 `isunited` 포함.
- `JdbcFlushExecutor` `world_state` UPDATE에 `isunited = :isunited` 추가.
- `WorldSnapshotLoader.loadWorldState` SELECT에 `isunited` 추가 후 `meta["isunited"]` 주입.
- `JdbcFlushExecutorIT` round-trip + default-0 fallback, `ScenarioBootIT` rehydrate assertion.

## Findings

- `JdbcFlushExecutorIT` cleanup SQL에서 `meta = CAST('{}' AS jsonb)` 누락 → 수정 완료.
- 기타 BLOCKER/HIGH 없음.

## Verdict

Verdict: cleared. 기타 BLOCKER/HIGH 없음. 천하통일/엔딩 플래그가 재기동 후에도 유지되어 `checkEmperior` 통일 탐지가 끊기지 않는다.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
