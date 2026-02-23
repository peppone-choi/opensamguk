# Batch 1E - Command Parity Report

**Date:** 2026-02-23  
**Commands reviewed:** 12  
**Files modified:** 10 Kotlin files

## Summary

| Command | Status | Changes Made |
|---------|--------|-------------|
| 출병 | ⚠️ Fixed | Added city state update (state=43, term=3), inheritance point logic, improved log formatting |
| 치안강화 | ✅ OK | Already correct - extends DomesticCommand with `secu`/`strength`/`debuffFront=1.0` |
| 탈취 | ⚠️ Fixed | Added supply check logic, nation vs general resource split (70/30), proper log formatting |
| 파괴 | ⚠️ Fixed | Changed damage range to use `env.sabotageDamageMin/Max` instead of hardcoded 200-400, added log formatting |
| 하야 | ⚠️ Fixed | Fixed exp/ded penalty formula, added global/history logs, permission/belong/makelimit reset, inheritance point, gennum decrement |
| 해산 | ⚠️ Fixed | Added global/history logs, all nation generals gold/rice cap, city release, OccupyCity event trigger, fixed yearMonth calc |
| 헌납 | ⚠️ Fixed | Added `<C>` and `<1>` formatting to log message |
| 화계 | ⚠️ Fixed | Added defence probability (defender generals), distance factor, injury calculation, diplomacy constraint, `firenum` rank tracking, `state=32` city update, env-based cost/damage |
| 훈련 | ⚠️ Fixed | Use env.trainDelta/maxTrainByCommand, fixed crew=0 guard, added `<C>` formatting |
| CR맹훈련 | ⚠️ Fixed | Use env.trainDelta, added train/atmos clamping to maxTrain/maxAtmos, fixed score formula |
| 휴식 | ⚠️ Fixed | Changed log from "휴식을 취했습니다" to "아무것도 실행하지 않았습니다" (matching PHP/TS) |
| DomesticCommand | ✅ OK | Already has capital city front debuff scaling, critical ratio, maxDomesticCritical tracking |

## Detailed Changes

### 출병.kt
- Added `cityStateUpdate` to result (state=43, term=3) matching legacy PHP
- Added inheritance point logic: `crew > 500 && train*atmos > 70*70` → active_action +1
- Added `<G><b>` formatting around city name in log

### 탈취.kt
- Added supply check: supplied cities steal from nation treasury, non-supplied reduce city stats
- Added 70/30 split: 70% of stolen resources go to own nation, 30% to general (100% if neutral)
- Added `<G><b>` and `<C>` formatting to logs

### 파괴.kt
- Changed hardcoded damage range (200-400) to `env.sabotageDamageMin/Max`
- Added `<G><b>` and `<C>` formatting to logs

### 하야.kt
- Fixed penalty formula: `experience * (1 - 0.1 * betray)` (was only subtracting the penalty amount)
- Added global action log with `<Y>name</>` formatting
- Added history log
- Added `setPermission:"normal"`, `setBelong:0`, `setMakeLimit:12`
- Added `gennum:-1` to nation changes
- Added `inheritancePoint` (active_action +1)

### 해산.kt
- Added global/history logs matching PHP/TS
- Added `allNationGenerals` gold/rice cap to result
- Added `releaseCities:true` to result
- Added `triggerEvent:"OccupyCity"` for event handler
- Fixed yearMonth calculation (month-1 for 0-indexed)

### 화계.kt (base class for 탈취/파괴)
- **Major rewrite**: Added full sabotage probability system
  - `calcAttackProb()`: attacker stat / sabotageProbCoefByStat
  - `calcDefenceProb()`: defender max stat, general count bonus, secu/supply bonus
  - Distance factor: probability /= distance
  - Max probability capped at 0.5
- Added `DisallowDiplomacyBetweenStatus(7)` constraint
- Added `calculateInjuries()`: injury to defending generals (prob=0.3, amount=1-16, cap=80)
- Added `firenum` rank increment on success
- Added `state:32` to dest city changes
- Cost now uses `env.develCost * 5` instead of hardcoded `5 * 5`
- Added josa formatting to log messages

### 훈련.kt
- Changed `MAX_TRAIN_BY_COMMAND`/`TRAIN_DELTA` to use env values with defaults
- Added crew=0 guard (avoid division by zero)
- Added `<C>` and `<1>` formatting to log

### CR맹훈련.kt
- Added `maxAtmos` clamping (was applying raw score without cap)
- Score formula now uses `env.trainDelta` instead of hardcoded `2/3` ratio
- Train/atmos gains properly clamped to `maxTrain - current` / `maxAtmos - current`

## Remaining Gaps (Not Fixed - Require Infrastructure)

1. **onCalcDomestic callbacks**: PHP uses trait/item-based modifiers for domestic score/cost/success. Kotlin lacks this hook system.
2. **getDomesticExpLevelBonus**: PHP applies experience level bonus to domestic score. Not in Kotlin.
3. **tryUniqueItemLottery**: PHP/TS call unique item lottery after domestic/sabotage commands. Kotlin doesn't have this system yet.
4. **StaticEventHandler**: PHP fires events after commands. Kotlin result JSON signals intent but handler not verified.
5. **searchDistance**: 출병/화계 use graph-based city distance. Kotlin assumes `getDistanceTo()` exists on GeneralCommand.
6. **SabotageInjury**: Full injury system with crew/train reduction for defenders. Implemented in 화계.kt but depends on `destCityGenerals` being populated.
7. **Item consumption**: 화계 PHP consumes items on success. Not implemented in Kotlin.
8. **josa() helper**: Added calls to `josa()` in several files - assumes GeneralCommand base class has this helper.
