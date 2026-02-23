# Batch 1B - Nation Commands Parity Report

**Date:** 2026-02-23  
**Commands:** 10 nation commands  
**Status:** All fixed

## Summary of Changes

### Infrastructure Changes

1. **ConstraintHelper.kt** — Added 4 new constraint functions:
   - `ReqNationAuxValue(key, default, op, expected, failMessage)` — checks nation.meta aux values
   - `ReqDestNationValue(key, displayName, op, expected, failMessage)` — checks dest nation values
   - `AllowDiplomacyWithTerm(minState, minTerm, failMessage)` — checks diplomacy term from env
   - `ReqDestCityValue(field, displayName, op, expected, failMessage)` — checks dest city field values

2. **NationRepository.kt** — Added `findByWorldIdAndName()` for duplicate nation name check

3. **GeneralRepository.kt** — Added `findByWorldIdAndNationId()` for listing nation generals

4. **DiplomacyService.kt** — Added `getRelationsBetween()` for bilateral diplomacy queries

---

### Command Fixes

#### 1. che_감축 (Capital Reduction)
| Issue | Old KT | Fixed KT (matches PHP/TS) |
|-------|--------|---------------------------|
| Cost formula | `DEVEL_COST * 5 + 1000/2 = 1000` | `env.develCost * 500 + 60000/2 = 80000` |
| City update | Multiplied max by 0.8 ratio | Subtract fixed amounts (POP_INCREASE=100000, DEVEL_INCREASE=2000, WALL_INCREASE=2000) |
| Nation gold/rice | Subtracted cost then added half back | Adds cost back (recovery, not payment) |
| Min pop floor | None | MIN_POP = 30000 |
| capset tracking | Missing | Increments `nation.meta["capset"]` |
| Experience | Missing | 5 × (preReqTurn + 1) = 30 |
| Capital check | `c.level <= 1` | `capitalCity.level <= 4` + fetches capital from nation |

#### 2. che_국기변경 (Flag Change)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Constraint | Missing aux check | Added `ReqNationAuxValue("can_국기변경", 0, ">", 0, ...)` |
| Color handling | Stored string "red" | Uses indexed color list (33 colors matching TS) |
| Aux update | Missing | Sets `nation.meta["can_국기변경"] = 0` |
| Experience | Missing | +5 exp/ded |

#### 3. che_국호변경 (Name Change)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Constraint | Missing aux check | Added `ReqNationAuxValue("can_국호변경", 0, ">", 0, ...)` |
| Name validation | `length > 18` | `length > 8` (matches TS schema `max(8)`) |
| Duplicate check | Missing | Queries `nationRepository.findByWorldIdAndName()` |
| Aux update | Missing | Sets `nation.meta["can_국호변경"] = 0` |
| Experience | Missing | +5 exp/ded |

#### 4. che_급습 (Raid)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Constraint | Missing `AllowDiplomacyWithTerm` | Added term ≥ 12 check |
| Diplomacy reduction | Reduced ALL dest nation relations | Reduces only bilateral relations (forward + reverse) by 3 |
| Experience | Missing | +5 exp/ded |
| getPostReqTurn | Complex sqrt formula with wrong constants | Removed (set to 0, cooldown via strategic_cmd_limit) |

#### 5. che_몰수 (Seizure)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Betray increment | `destGen.betray += 1` | Removed (not in PHP/TS) |
| Self-check | Missing | Added `destGen.id == general.id` check |
| Amount capping | Fixed 100-100000 | Same range but actual capped to dest general's resource |

#### 6. che_무작위수도이전 (Random Capital Move)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Constraint | Missing `ReqNationAuxValue` | Added `can_무작위수도이전` check |
| City selection | Used `env.gameStor["neutralCities"]` | Queries cities directly (level 5-6, nationId=0) |
| Old city release | Missing | Sets old capital to neutral (nationId=0, frontState=0, officerSet=0) |
| General relocation | Missing | Moves ALL nation generals to new capital |
| Aux decrement | Missing | Decrements `can_무작위수도이전` counter |
| Experience | Missing | 5 × (preReqTurn + 1) = 10 |

#### 7. che_물자원조 (Material Aid)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Constraint | Missing surlimit checks | Added `ReqNationValue("surlimit", "==", 0, "외교제한중입니다.")` |
| Arg parsing | Custom goldAmount/riceAmount | Supports `amountList: [gold, rice]` (PHP/TS format) + legacy fallback |
| Resource reservation | None (raw check) | Keeps BASE_GOLD/BASE_RICE reserved |
| Surlimit update | Missing | Increments by POST_REQ_TURN (12) |
| Experience | Missing | +5 exp/ded |

#### 8. che_발령 (Assignment)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Self-check | Missing | Added `destGen.id == general.id` check |
| last발령 meta | Missing | Sets `destGen.meta["last발령"] = yearMonth` |
| Troop removal | Had troop removal logic | Removed (not in PHP/TS — troop removal is separate) |

#### 9. che_백성동원 (Mobilize People)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| **Core logic completely wrong** | Spawned NPC generals from population | Restores city def/wall to 80% of max |
| Strategic cooldown | Set to 9 | Same (correct) |
| Experience | Missing | +5 exp/ded |
| getPostReqTurn | Complex sqrt formula | Set to 0 (cooldown via strategic_cmd_limit) |

#### 10. che_부대탈퇴지시 (Troop Kick)
| Issue | Old KT | Fixed KT |
|-------|--------|----------|
| Self-check | Missing | Added `destGen.id == general.id` check |
| Logic | Already mostly correct | No major changes needed |

---

## Files Modified

- `backend/.../command/constraint/ConstraintHelper.kt` — 4 new constraints
- `backend/.../command/nation/che_감축.kt` — Rewritten
- `backend/.../command/nation/che_국기변경.kt` — Rewritten
- `backend/.../command/nation/che_국호변경.kt` — Rewritten
- `backend/.../command/nation/che_급습.kt` — Rewritten
- `backend/.../command/nation/che_몰수.kt` — Rewritten
- `backend/.../command/nation/che_무작위수도이전.kt` — Rewritten
- `backend/.../command/nation/che_물자원조.kt` — Rewritten
- `backend/.../command/nation/che_발령.kt` — Rewritten
- `backend/.../command/nation/che_백성동원.kt` — Rewritten
- `backend/.../command/nation/che_부대탈퇴지시.kt` — Rewritten
- `backend/.../repository/NationRepository.kt` — Added query method
- `backend/.../repository/GeneralRepository.kt` — Added query method
- `backend/.../engine/DiplomacyService.kt` — Added query method

## Notes

- Could not verify compilation (no JDK in sandbox)
- Log messages in Kotlin use simplified format; full multi-scope logging (general/nation/global action+history) would need a logging framework similar to PHP's ActionLogger — currently only pushLog to general action log
- `ReqNationValue("surlimit")` reads from `nation.meta["surlimit"]` since surlimit isn't a direct entity field — may need schema alignment later
