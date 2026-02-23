# Batch 1B - Nation4: Event Research Commands Parity Report

**Date**: 2026-02-23
**Commands**: 9 event research commands (병종 연구)

## Commands Covered

| Command | actionName | auxKey | cost (gold/rice) | preReqTurn | exp/ded |
|---------|-----------|--------|-------------------|------------|---------|
| event_극병연구 | 극병 연구 | can_극병사용 | 100,000 | 23 | 120 |
| event_대검병연구 | 대검병 연구 | can_대검병사용 | 50,000 | 11 | 60 |
| event_무희연구 | 무희 연구 | can_무희사용 | 100,000 | 23 | 120 |
| event_산저병연구 | 산저병 연구 | can_산저병사용 | 50,000 | 11 | 60 |
| event_상병연구 | 상병 연구 | can_상병사용 | 100,000 | 23 | 120 |
| event_원융노병연구 | 원융노병 연구 | can_원융노병사용 | 100,000 | 23 | 120 |
| event_음귀병연구 | 음귀병 연구 | can_음귀병사용 | 50,000 | 11 | 60 |
| event_화륜차연구 | 화륜차 연구 | can_화륜차사용 | 100,000 | 23 | 120 |
| event_화시병연구 | 화시병 연구 | can_화시병사용 | 50,000 | 11 | 60 |

## Issues Found & Fixed

All 9 commands shared the same structural issues. 3 commands had additional data errors.

### Issue 1: Missing `ReqNationAuxValue` constraint (ALL 9)
- **Severity**: Critical
- **Problem**: Kotlin had no check for whether research was already completed. PHP/TS both use `ReqNationAuxValue(auxKey, 0, "<", 1, "...이미 완료...")` to prevent duplicate research.
- **Fix**: Added `ReqNationAuxValue` constraint to `fullConditionConstraints`.

### Issue 2: Wrong experience/dedication calculation (ALL 9)
- **Severity**: High
- **Problem**: Kotlin hardcoded `general.experience += 100` / `general.dedication += 100` for all commands. PHP/TS use `5 * (preReqTurn + 1)`, which varies per command (120 for 23-turn, 60 for 11-turn).
- **Fix**: Changed to `val expDed = 5 * (getPreReqTurn() + 1)`.

### Issue 3: Missing history and national history logs (ALL 9)
- **Severity**: High
- **Problem**: Kotlin only had one `pushLog` (general action log). PHP/TS emit 3 logs:
  1. General action log: `<M>{actionName}</> 완료`
  2. General history log: `<M>{actionName}</> 완료`
  3. National history log: `<Y>{generalName}</>{josaYi} <M>{actionName}</> 완료`
- **Fix**: Added `_history:` and `_nation_history:` prefixed pushLog calls with JosaUtil for proper Korean particle.

### Issue 4: Missing `minConditionConstraints` (ALL 9)
- **Severity**: Medium
- **Problem**: Kotlin didn't set `minConditionConstraints`. PHP/TS set min = full for these commands.
- **Fix**: Added `override val minConditionConstraints get() = fullConditionConstraints`.

### Issue 5: Wrong cost and preReqTurn (3 commands)
- **Severity**: Critical
- **Affected**: event_산저병연구, event_음귀병연구, event_화시병연구
- **Problem**: Kotlin had cost=100,000 and preReqTurn=23 for these three, but PHP/TS specify cost=50,000 and preReqTurn=11.
- **Fix**: Corrected to cost=50,000/50,000 and preReqTurn=11.

| Command | Old KT cost | Correct cost | Old KT turn | Correct turn |
|---------|-------------|-------------|-------------|-------------|
| event_산저병연구 | 100,000 | 50,000 | 23 | 11 |
| event_음귀병연구 | 100,000 | 50,000 | 23 | 11 |
| event_화시병연구 | 100,000 | 50,000 | 23 | 11 |

## Not Ported (Intentional)

- **InheritanceKey::active_action** increment — not yet in Kotlin infrastructure
- **StaticEventHandler::handleEvent** — event handler system not yet in Kotlin
- **LastTurn tracking** — turn result recording not yet in Kotlin

## Pre-existing Issue Noted

- `ConstraintHelper.kt` has **duplicate `ReqNationAuxValue` function** definitions (lines 913 and 1078) with same signature but different parameter names (`defaultValue` vs `default`). This will cause a Kotlin compile error and should be deduplicated separately.
