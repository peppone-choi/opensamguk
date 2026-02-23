# Batch 1A - Command Parity Report

**Date:** 2026-02-23  
**Commands analyzed:** 11  
**Files modified:** 14 (11 command KT files + BaseCommand infra)

## Summary

| Command | Status | Severity | Changes Made |
|---------|--------|----------|-------------|
| NPC능동 | ✅ Minor fix | Low | Fixed arg key `destCityID` → also accepts `destCityId` |
| 강행 | 🔧 Rewritten | Medium | Added JosaUtil formatting, `<G><b>` tags, wandering nation lord move logic, stat decreases (train/atmos) |
| 거병 | 🔧 Rewritten | High | Added constraints (BeOpeningPart, AllowJoinAction, NoPenalty), history/global logs, inheritance points, nation creation data |
| 건국 | 🔧 Rewritten | High | Added constraints (BeLord, WanderingNation, ReqNationGenCount, CheckNationNameDuplicate, ConstructableCity, NoPenalty), initYearMonth check, all log types, city claim, nation aux can_국기변경 |
| CR건국 | 🔧 Rewritten | High | Same as 건국 but for CR (create) variant. Added constraints (NeutralCity), all log types, nation foundation data |
| 견문 | 🔧 Rewritten | Critical | **Flag values were completely wrong** (sequential 1,2,4,8... vs hex 0x1,0x2,0x10,0x20...). Events replaced with legacy-matching messages. Added weight-based selection. Fixed injury cap at 80. |
| 군량매매 | 🔧 Fixed | Medium | Added missing `ReqCityTrader` constraint, `minConditionConstraints`. Changed `exchangeFee` to use `env.exchangeFee` instead of hardcoded 0.03. Added `<C>` tags to log. |
| 귀환 | 🔧 Fixed | Medium | Added `<G><b>` tags and JosaUtil for log. City name now resolved via `CommandServices.getCityName()`. |
| 기술연구 | 🔧 Fixed | High | Added TechLimit check (score/4 when over limit). Added gennum division for nation tech delta. Added maxDomesticCritical tracking. Fixed log message format with `<span>/<S>/<C>` tags. |
| 농지개간 | ✅ Via base | Medium | Fixed via `DomesticCommand.kt` base class: added capital front debuff year-scaling (legacy: relYear < 25 scales debuff), maxDomesticCritical tracking, improved log formatting. |
| 내정특기초기화 | 🔧 Fixed | Medium | Fixed `specialField` from "specialCode" to "special" (matching legacy `special`). Added `specialAgeField` = "specage" (was using "specAge2"). |

## Infrastructure Changes

### New files
- **`backend/shared/.../util/JosaUtil.kt`** - Korean 조사 particle utility (ported from PHP/TS). Handles 이/가, 을/를, 은/는, 과/와, 으로/로 based on 받침.

### Modified files
- **`CommandEnv.kt`** - Added `scenario`, `exchangeFee`, `initialNationGenLimit` fields. Added `isTechLimited()` method.
- **`CommandServices.kt`** - Added `getCityName(cityId)` suspend helper.
- **`ConstraintHelper.kt`** - Added 7 new constraints: `CheckNationNameDuplicate`, `ConstructableCity`, `ReqNationGenCount`, `ReqNationValue`, `ReqCityTrader`, `OccupiedCity(allowNeutral)`, `NotCapital(checkCurrentCity)`. Removed duplicate no-param versions of `OccupiedCity` and `NotCapital`.
- **`DomesticCommand.kt`** - Added capital city front debuff year-scaling, maxDomesticCritical tracking, improved log formatting with `<span>/<S>/<C>` tags.
- **`전투특기초기화.kt`** - Renamed `specialField` default from "special2Code" to "special2", added `specialAgeField`, added `trackPrevTypes` flag in output.

## Critical Issues Found & Fixed

### 1. 견문 flag bitmask mismatch (CRITICAL)
The Kotlin used sequential powers of 2 (1,2,4,8,16,32...) while legacy PHP/TS uses hex-spaced flags (0x1,0x2,0x10,0x20,0x40,0x100...). This meant flag combinations would collide — e.g., Kotlin `INC_INTEL=16` overlapped with PHP's `INC_LEADERSHIP=0x10=16`, and Kotlin's `INC_GOLD=32` had no PHP equivalent at that value. Every multi-flag event would produce wrong results.

### 2. Missing constraints across 건국/거병/CR건국
These commands had minimal or no constraints, allowing execution in invalid states. Added ~5-8 constraints each matching legacy.

### 3. 기술연구 missing TechLimit and gennum division
Tech score was applied directly to nation without the year-based tech limit cap or division by nation general count, causing tech to grow too fast.

### 4. 내정특기초기화 wrong field names
Was resetting "specialCode" instead of "special", and setting "specAge2" instead of "specAge", causing domestic special resets to affect the wrong fields.

## Notes
- Log message format now matches legacy: `<C>`, `<S>`, `<G><b>`, `<D><b>`, `<Y>`, `<1>date</>` tags preserved.
- Some commands output structured JSON in `CommandResult.message` for the executor to apply. The executor must handle new keys like `nationFoundation`, `historyLog`, `inheritancePoint`, `wanderingNationMove`, `maxDomesticCritical`, `trackPrevTypes`.
- `exchangeFee` default in CommandEnv is 0.03; legacy PHP uses `GameConst::$exchangeFee` which varies per server config.
