# Build Fix Report — 2026-02-23

## Summary
Fixed all 36 compilation errors. Build now succeeds with 0 errors.

## Fixes Applied

### GROUP 1 — Type Mismatches (7 fixes)
| File | Fix |
|------|-----|
| `che_모병.kt` (×2) | `ReqCityTrust(20)` → `ReqCityTrust(20.toFloat())` |
| `che_징병.kt` (×2) | `ReqCityTrust(MIN_TRUST_FOR_RECRUIT)` → `.toFloat()` |
| `che_정착장려.kt` | `getDomesticExpLevelBonus(expLevel)` → `.toInt()` |
| `등용.kt` | `destGeneral?.officerLevel == 12` → `?.officerLevel?.toInt() == 12` |
| `탈취.kt` | `nationId != 0` → `!= 0L` |

### GROUP 2 — Nullable Service Calls (5 fixes)
| File | Fix |
|------|-----|
| `che_선전포고.kt` | `services!!.messageService.` → `messageService?.` |
| `che_의병모집.kt` | `services!!.generalPoolService.` → `generalPoolService?.` |
| `che_이호경식.kt` (×2) | `services!!.nationService.` → `nationService?.` |
| `che_종전수락.kt` (×2) | `services!!.nationService.` → `nationService?.` |

### GROUP 3 — Unresolved References (24 fixes)
| File | Issue | Fix |
|------|-------|-----|
| `CommandServices.kt` | `findById()` returns `Optional` | Added `.orElse(null)` |
| `CommandEnv.kt` | Missing `trainDelta`, `killturn` | Added fields with defaults |
| `ConstraintHelper.kt:799` | `general.crewTypeId` | → `general.crewType.toInt()` |
| `ConstraintHelper.kt:800` | `general.leadership * 100` (Short) | → `general.leadership.toInt() * 100` |
| `ConstraintHelper.kt:874` | `city.level in levels` (Short vs Int) | → `city.level.toInt() in levels` |
| `che_단련.kt` | `getArmTypeName()` doesn't exist | → `getCrewTypeName(general.crewType.toInt()) ?: "병사"` |
| `che_수몰.kt` | `findById()?.name` | Added `.orElse(null)` |
| `장비매매.kt` (×6) | `ItemService.getItemInfo` | → `ItemModifiers.getMeta` |
| `장비매매.kt` | `item.reqSecu` not in ItemMeta | → `item.grade * 10` |
| `장비매매.kt` | `general.getItemCode(type)` | Inlined with `when` block |
| `장비매매.kt` (×2) | `item.name` not in ItemMeta | → `"${item.rawName}(+${item.grade})"` |
| `등용수락.kt` | Wrong `ReqGeneralValue` signature | → `ReqGeneralStatValue` with inverse logic |
| `등용수락.kt` | `general.npcType` | → `general.npcState` |
| `등용수락.kt` | `killturn` key casing | → `killTurn` |
| `내정특기초기화.kt` | `specialAgeField` overrides nothing | → `specAgeField`; fixed field values to `specialCode`/`specAge` |
| `che_의병모집.kt` | `n.gennum ?: 0` (non-nullable) | Simplified to `n.gennum` |

## Build Verification
```
BUILD SUCCESSFUL in 15s
5 actionable tasks: 5 up-to-date
```
