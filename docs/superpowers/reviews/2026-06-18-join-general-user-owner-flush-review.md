# 2026-06-18 join general user owner flush review

Verdict: cleared

## Baseline

- Legacy PHP join creates a user-owned general and the next basic-info response resolves that general by owner.
- Current production accepted the `makeGeneral` command, but the created row had `general.user_id` empty and `npc_state=2`, so lobby and game entry continued to report no playable general.

## Evidence

- PHP oracle:
  - `legacy/devsam-core/hwe/sammo/API/General/Join.php:177-182` validates duplicate owner before join.
  - `legacy/devsam-core/hwe/sammo/API/General/Join.php:404-438` inserts the joined general as the user's playable general.
  - `legacy/devsam-core/hwe/j_server_basic_info.php:119-126` and `legacy/devsam-core/hwe/j_basic_info.php:14-29` resolve the user's current general for lobby and entry.
- Production observation:
  - Redis command result was published for `makeGeneral`.
  - The inserted `general` row existed with `user_id` empty and `npc_state=2`.
  - Browser stayed in the "accepted but not reflected" timeout branch.

## Root Cause

1. `MakeGeneralHandler` created the joined general with `npcState=2`, which is the claimable NPC shape, not a playable user general.
2. `DatabaseHooks.toGeneralCreateRow` carried `user_id`, but `JdbcFlushExecutor.generalCreateMany` omitted `user_id` from the INSERT column list and parameter binding.
3. The idle daemon intake loop used the default `blockMs=0`; for Redis stream reads this can wait indefinitely and surface as a Lettuce timeout when the stream is empty.

## Change

- Joined generals are created with `npcState=0`.
- General create flush now inserts `general.user_id`.
- Idle intake polling uses `blockMs=1` so command intake remains near-immediate without blocking the daemon loop indefinitely.

## Verification Plan

- `MakeGeneralHandlerTest` asserts the in-memory created general and flush payload carry `user_id` and `npc_state=0`.
- `GeneralCreateFlushIT` asserts the database row persists `user_id` and `npc_state=0`.
- `TurnDaemonRunnerTest` keeps the immediate-intake idle-loop behavior covered.
