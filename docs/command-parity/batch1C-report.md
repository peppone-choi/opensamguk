# Batch 1C - Command Parity Report

**Date:** 2026-02-23  
**Commands reviewed:** 11  
**Files modified:** 9 Kotlin files + 1 constraint file

## Summary

| Command | Status | Changes Made |
|---------|--------|-------------|
| 선동 | 🔧 Fixed | Trust as float/50 division, global log, formatted numbers, city state=32 |
| 선양 | 🔧 Fixed | Added penalty check, experience*0.7, history/national/global logs, dest general logs |
| 성벽보수 | ✅ OK | No changes needed - simple delegation matches legacy |
| 소집해제 | 🔧 Fixed | Added `<R>` formatting tag in log message |
| 수비강화 | ✅ OK | No changes needed - simple delegation matches legacy |
| 숙련전환 | 🔧 Fixed | Added Josa particles, minConditionConstraints, tryUniqueLottery flag |
| 요양 | ✅ OK | Already correct (injury=0 full heal, exp=10, ded=7) |
| 은퇴 | 🔧 Fixed | Added conditional CheckHall (isunited==0), tryUniqueLottery flag |
| 이동 | 🔧 Fixed | Added roaming leader logic, tryUniqueLottery flag |
| 인재탐색 | 🔧 Fixed | Added inheritance bonus, global/history logs, tryUniqueLottery, rice constraint |
| 임관 | 🔧 Fixed | Added NoPenalty, ReqEnvValue, AllowJoinDestNation constraints, history/global logs, moveToCityOfLord, inheritanceBonus, tryUniqueLottery |

## New Constraints Added

Added to `ConstraintHelper.kt`:
- `ReqEnvValue(key, op, expected, reason)` - Check environment value (used by 임관)
- `NoPenalty(penaltyKey)` - Check general has no specific penalty (used by 임관)
- `AllowJoinDestNation(relYear)` - Check dest nation allows joining (used by 임관)

## Detailed Changes

### 선동 (선동.kt)
- **Trust calculation**: Was treating trust as Int. Legacy PHP divides by 50.0 and uses float. Fixed to use `Double` math.
- **Global log**: Added `[GLOBAL]` prefixed log for city unrest message (legacy: `pushGlobalActionLog`).
- **Number formatting**: Added `%,d` and `%.1f` formatting to match legacy `number_format()`.
- **City state**: Added `"state" to 32` in return map (legacy sets `state=32`).
- **Damage constants**: Used local file-level constants matching base 화계 values.

### 선양 (선양.kt)
- **Penalty check**: Legacy PHP checks `NoChief`, `NoFoundNation`, `NoAmbassador` penalties on dest general. Added this check.
- **Experience reduction**: Legacy PHP does `experience *= 0.7`. Added `experienceMultiplier` to JSON output.
- **Multiple log types**: Added global history, national history, general history logs matching legacy.
- **Dest general changes**: Added `destGeneralLogs` array with logs for the receiving general.

### 소집해제 (che_소집해제.kt)
- **Log formatting**: Added `<R>` tag around "소집해제" matching legacy PHP formatting.

### 숙련전환 (che_숙련전환.kt)
- **Josa particles**: Added proper Korean particle selection (을/를, 으로/로) matching legacy `JosaUtil::pick`.
- **minConditionConstraints**: Added (was missing, legacy PHP has them).
- **srcArmType validation**: Added check that src != dest arm type.
- **Unique item lottery**: Added `tryUniqueLottery` flag (legacy PHP calls `tryUniqueItemLottery`).

### 은퇴 (은퇴.kt)
- **CheckHall**: Legacy PHP calls `CheckHall` only when `isunited==0`. Added conditional flag.
- **Unique item lottery**: Added `tryUniqueLottery` flag.

### 이동 (이동.kt)
- **Roaming leader**: Legacy PHP moves all nation generals when `officer_level==12 && nation.level==0`. Added `roamingMove` JSON section.
- **Unique item lottery**: Added `tryUniqueLottery` flag.
- **Log formatting**: Added `<G><b>` tags matching legacy.

### 인재탐색 (인재탐색.kt)
- **Rice constraint**: Added `ReqGeneralRice` to fullConditionConstraints (legacy PHP has it).
- **Inheritance bonus**: On NPC found, legacy adds `sqrt(1/foundProp)` inheritance points. Added to JSON output.
- **Global/history logs**: Added `[GLOBAL]` and `[HISTORY]` prefixed log messages.
- **Unique item lottery**: Added `tryUniqueLottery` flag for both success and failure paths.

### 임관 (임관.kt)
- **Constraints overhaul**: Added `ReqEnvValue` (join_mode check), `NoPenalty` (noChosenAssignment), `AllowJoinDestNation` (relYear-based).
- **minConditionConstraints**: Added full set matching legacy PHP.
- **Logs**: Added history log, global action log with Josa particles.
- **City assignment**: Added `moveToCityOfLord` flag (legacy PHP moves general to lord's city).
- **Inheritance/lottery**: Added `inheritanceBonus` and `tryUniqueLottery` flags.

## Notes

1. Log prefixes like `[GLOBAL]`, `[HISTORY]`, `[GLOBAL_HISTORY]`, `[NATIONAL_HISTORY]` are conventions for the command executor to route logs to appropriate scopes. The executor layer should parse these prefixes and dispatch accordingly.

2. JSON message fields like `tryUniqueLottery`, `roamingMove`, `moveToCityOfLord`, `experienceMultiplier` are signals for the command executor to perform post-processing. The executor layer must handle these.

3. Core2026 TS has some improvements over legacy PHP (e.g., gradual injury healing in 요양, pipeline-based probability modifiers in 인재탐색). These are design decisions documented but not forced into Kotlin, as PHP is the canonical reference.
