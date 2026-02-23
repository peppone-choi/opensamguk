# Batch 1B - Command Parity Report

**Date:** 2026-02-23
**Commands analyzed:** 11
**Files modified:** 11 Kotlin files (including DomesticCommand base class)

## Summary of Changes

### 1. 단련 (che_단련.kt)
| Issue | Fix |
|-------|-----|
| `DEFAULT_TRAIN_LOW`/`DEFAULT_ATMOS_LOW` was 50, PHP/TS uses 40 | Changed to 40 |
| Weight order mismatch (PHP: success first at 0.34) | Reordered to match PHP: success=0.34, normal=0.33, fail=0.33 |
| Missing arm type name in log messages | Added `getArmTypeName()` call |
| Missing HTML formatting in logs (`<S>`, `<C>`, `<span class='ev_failed'>`) | Added full formatting to match PHP/TS |
| Missing number formatting with commas | Added `%,d` formatting |

### 2. 등용 (등용.kt)
| Issue | Fix |
|-------|-----|
| Missing `ReqEnvValue("join_mode")` constraint | Added to both full and min constraints |
| Missing `AlwaysFail` when destGeneral is lord (officer_level==12) | Added conditional constraint |
| Cost formula wrong: `((dev + x/1000) / 10) * 10` vs PHP `round(dev + x/1000) * 10` | Fixed to match PHP formula |

### 3. 등용수락 (등용수락.kt) — **Major rewrite**
| Issue | Fix |
|-------|-----|
| Missing 6 constraints (join_mode, AllowJoinDestNation, level>0, DifferentDestNation, officer_level!=12) | Added all constraints |
| Missing recruiter rewards (destGeneral exp+100, ded+100) | Added recruiterChanges to output |
| Missing neutral bonus (exp+100, ded+100 when nationId==0) | Added neutral branch |
| Missing killturn reset for non-NPC generals | Added killturn to statChanges |
| Missing global/history log messages | Added pushGlobalLog, pushHistoryLog |
| Missing destGeneral log notification | Added destGeneralLog to message |
| MAX_BETRAY_CNT not capped | Added min(betray+1, 10) |

### 4. 랜덤임관 (랜덤임관.kt) — **Major rewrite**
| Issue | Fix |
|-------|-----|
| Was a stub with no actual logic | Rewrote with proper parameters for engine-side nation selection |
| Missing log templates (action, history, global with randomTalk) | Added logTemplates to message |
| Missing exp gain info (700 for small nations, 100 default) | Added expGain to message |
| Missing statTemplate (officerLevel, belong, etc.) | Added statTemplate to message |
| Missing genLimit calculation | Added genLimit based on relYear |

### 5. 모반시도 (모반시도.kt)
| Issue | Fix |
|-------|-----|
| Missing `AllowRebellion()` constraint | Added |
| Missing lord experience penalty (×0.7) | Added lordChanges with experienceMultiplier |
| Missing all log messages (global history, national history, general history, lord logs) | Added all log types |

### 6. 모병 (che_모병.kt)
| Issue | Fix |
|-------|-----|
| Missing constraints: ReqCityCapacity, ReqCityTrust, ReqGeneralCrewMargin, AvailableRecruitCrewType | Added all 4 constraints |
| Missing minConditionConstraints | Added with base 4 constraints |
| Missing city trust reduction on recruit | Added trustLoss to cityChanges |
| Missing dex gain for crew type | Added dexChanges to message |

### 7. 무작위건국 (무작위건국.kt) — **Major rewrite**
| Issue | Fix |
|-------|-----|
| Missing `ReqNationGeneralCount(2)` constraint | Added |
| Missing `CheckNationNameDuplicate` constraint | Added |
| Missing minConditionConstraints (BeOpeningPart, ReqNationValue level==0) | Added |
| Missing all log messages (action, global action, global history, general history, national history) | Added all 5 log types with proper Josa |
| Missing nation aux updates (can_국기변경, can_무작위수도이전) | Added to nationChanges |
| Missing moveAllNationGenerals flag | Added to message |

### 8. 물자조달 (che_물자조달.kt)
| Issue | Fix |
|-------|-----|
| Missing capital city front debuff scaling (relYear < 25 adjustment) | Added scaling logic matching PHP |
| Missing HTML formatting in log messages | Added `<span>`, `<S>`, `<C>` tags |
| Missing number formatting with commas | Added `%,d` formatting |

### 9. 방랑 (방랑.kt)
| Issue | Fix |
|-------|-----|
| Missing `AllowDiplomacyStatus` constraint (states [2,7]) | Added |
| Missing proper log messages (global action, global history, general history) | Added all log types with Josa |
| minConditionConstraints was duplicate of full | Kept same (no SuppliedCity needed) |

### 10. 사기진작 (che_사기진작.kt)
| Issue | Fix |
|-------|-----|
| Missing HTML formatting in log messages | Added `<C>` tags |
| Missing date formatting tags | Added `<1>$date</>` |

### 11. 상업투자 (che_상업투자.kt) + DomesticCommand base
| Issue | Fix |
|-------|-----|
| Missing HTML formatting in all domestic command logs | Fixed in DomesticCommand.kt |
| Missing Josa in log messages (was hardcoded "을(를)") | Added `pickJosa()` call |
| Missing `max_domestic_critical` tracking for consecutive success | Added to statChanges in message |
| Missing capital city front debuff scaling | Added relYear < 25 scaling in DomesticCommand |

## Constraints Not Yet Verified (need corresponding Kotlin constraint classes)

The following constraint classes were referenced in fixes. If they don't exist yet in the Kotlin codebase, they need to be created:

- `ReqEnvValue` - Check env variable against value
- `AllowJoinDestNation` - Check if joining destination nation is allowed
- `ReqDestNationValue` - Check destination nation property
- `DifferentDestNation` - Ensure different from current nation
- `ReqGeneralValue` (with negate) - Generic general property check
- `AllowRebellion` - Check rebellion is allowed
- `AllowDiplomacyStatus` - Check diplomacy state
- `ReqCityCapacity` - Check city has enough population
- `ReqCityTrust` - Check city trust level
- `ReqGeneralCrewMargin` - Check general can recruit more
- `AvailableRecruitCrewType` - Check crew type is available
- `ReqNationGeneralCount` - Check nation has enough generals
- `CheckNationNameDuplicate` - Check nation name uniqueness
- `AllowJoinAction` - Check joining is allowed
- `AlwaysFail` - Always deny with message

## Helper Methods Referenced

These methods were used in the Kotlin fixes and should exist on `GeneralCommand`:

- `pickJosa(text, particle)` - Korean Josa particle selection
- `pushGlobalLog(message)` - Add to global action log
- `pushHistoryLog(message)` - Add to general history log
- `pushNationalHistoryLog(message)` - Add to national history log
- `getArmTypeName()` - Get current crew type arm type name
