# Batch 1B – Nation Commands (Set 2) Parity Report

**Date:** 2026-02-23
**Commands:** 10 nation commands (diplomacy + strategic)

## Summary

All 10 Kotlin commands had significant parity issues. Every file was rewritten.

## Command-by-Command Fixes

### 1. che_불가침수락 (Non-Aggression Accept)
| Issue | Detail |
|-------|--------|
| ❌ Spurious exp/ded +50 | PHP has no exp/ded gain. Removed. |
| ❌ Missing constraints | Added `ReqDestNationGeneralMatch`, `DisallowDiplomacyBetweenStatus({0,1})` |
| ❌ Missing year/month arg handling | PHP uses year/month to compute term, updates diplomacy state=7 with calculated term |
| ❌ Wrong log message | Added proper josa, year/month display, history logs |
| ❌ Missing dest general logs | Added dest general action+history logs |
| ❌ Missing canDisplay=false, isReservable=false | Added (PHP: `AlwaysFail('예약 불가능 커맨드')`) |

### 2. che_불가침제의 (Non-Aggression Proposal)
| Issue | Detail |
|-------|--------|
| ❌ Spurious exp/ded +50 | Removed |
| ❌ Missing constraints | Added `DisallowDiplomacyBetweenStatus({0,1})` |
| ❌ Missing min term validation | Added 6-month minimum check matching PHP `reqMonth < currentMonth + 6` |
| ❌ Wrong service call | Changed to send diplomatic message (PHP sends `DiplomaticMessage`) |
| ✅ Log message | Was already close, added josa |

### 3. che_불가침파기수락 (Non-Aggression Break Accept)
| Issue | Detail |
|-------|--------|
| ❌ Spurious exp/ded +50 | Removed |
| ❌ Missing constraints | Added `AllowDiplomacyBetweenStatus([7])`, `ReqDestNationGeneralMatch` |
| ❌ Missing diplomacy update | Added `setDiplomacyState(state=2, term=0)` |
| ❌ Missing global logs | Added global action + global history logs matching PHP |
| ❌ Missing dest general logs | Added dest general action+history logs |
| ❌ Missing canDisplay/isReservable | Added |

### 4. che_불가침파기제의 (Non-Aggression Break Proposal)
| Issue | Detail |
|-------|--------|
| ❌ Spurious exp/ded +50 | Removed |
| ❌ Missing constraint | Added `AllowDiplomacyBetweenStatus([7])` |
| ❌ Wrong service call | Changed to send diplomatic message |
| ✅ Log message | Added proper josa |

### 5. che_선전포고 (Declaration of War)
| Issue | Detail |
|-------|--------|
| ❌ Spurious exp/ded +50 | Removed (PHP has none) |
| ❌ Missing constraints | Added `NearNation`, `DisallowDiplomacyBetweenStatus({0,1,7})` |
| ❌ Missing diplomacy update detail | PHP: state=1, term=24. Was delegated to service; now explicit. |
| ❌ Missing rich logging | Added 6 log types: general action/history, own national history, dest national history, global action/history |
| ❌ Missing national message | Added 국메 (national message) to dest nation |

### 6. che_수몰 (Flood)
| Issue | Detail |
|-------|--------|
| ❌ No exp/ded gain | PHP: `5*(preReqTurn+1)` = 15. Added. |
| ❌ Extra pop damage | PHP only reduces def/wall to 20%. Removed pop/dead changes. |
| ❌ Wrong getPostReqTurn | Was hardcoded `min(max(1,1),30)`. Fixed to use `nation.gennum` with `sqrt(genCount*4)*10` |
| ❌ Missing broadcast messages | Added broadcasts to own+dest nation generals |
| ❌ Missing history logs | Added general history, national history, dest national history |

### 7. che_의병모집 (Volunteer Recruitment)
| Issue | Detail |
|-------|--------|
| ❌ No exp/ded gain | PHP: 15. Added. |
| ❌ npcState=5 | PHP: npcType=4. Fixed. |
| ❌ Fixed 3 NPCs | PHP: `3 + round(avgGenCount/8)`. Fixed. |
| ❌ Pop deduction | Not in PHP. Removed. |
| ❌ Hardcoded stats | PHP uses nation averages + general pool. Changed to use generalPoolService. |
| ❌ Wrong gold/rice | PHP: 1000/1000. Was 0/0. Fixed. |
| ❌ Missing broadcast/history | Added broadcast to nation generals, general+national history logs |
| ❌ Wrong getPostReqTurn | Was hardcoded. Fixed to use `nation.gennum` with `sqrt(genCount*10)*10` |

### 8. che_이호경식 (Degrade Relations)
| Issue | Detail |
|-------|--------|
| ❌ **Completely wrong logic** | Kotlin was: pick random 3rd nation, declare war between dest and random. PHP: force diplomacy between self↔dest to state=1, with term=3 if war, else term+3. Completely rewritten. |
| ❌ No exp/ded gain | PHP: 5. Added. |
| ❌ Missing constraint | Added `AllowDiplomacyBetweenStatus([0,1])` |
| ❌ Missing broadcasts | Added broadcasts to own+dest nation generals |
| ❌ Missing SetNationFront | Added |

### 9. che_종전수락 (Ceasefire Accept)
| Issue | Detail |
|-------|--------|
| ❌ Spurious exp/ded +50 | Removed |
| ❌ Missing constraints | Added `ReqDestNationGeneralMatch`, `AllowDiplomacyBetweenStatus([0,1])` |
| ❌ Missing diplomacy update | Added `setDiplomacyState(state=2, term=0)` |
| ❌ Missing SetNationFront | Added |
| ❌ Missing rich logging | Added global action/history, national history, dest general+national logs |
| ❌ Missing canDisplay/isReservable | Added |

### 10. che_종전제의 (Ceasefire Proposal)
| Issue | Detail |
|-------|--------|
| ❌ Spurious exp/ded +50 | Removed |
| ❌ Missing constraint | Added `AllowDiplomacyBetweenStatus([0,1])` |
| ❌ Wrong service call | Changed to send diplomatic message |
| ✅ Log message | Added proper josa |

## New Service Methods Required

The fixes assume these service methods exist (or need to be created):

1. **`diplomacyService.setDiplomacyState(worldId, nationId1, nationId2, state, term)`** – Updates both directions of diplomacy table
2. **`diplomacyService.getDiplomacyState(worldId, nationId1, nationId2)`** – Returns current {state, term}
3. **`diplomacyService.sendDiplomaticMessage(...)`** – Sends a diplomatic proposal message with validity window
4. **`nationService.setNationFront(worldId, nationId)`** – Recalculates nation front cities
5. **`messageService.sendNationalMessage(...)`** – Sends national-scope message
6. **`generalPoolService.pickAndCreateNpc(...)`** – Picks from general name pool and creates NPC
7. **`nationRepository.getAverageGennum(worldId)`** – Returns avg gennum across nations
8. **`generalRepository.getAverageStats(worldId, nationId)`** – Returns avg exp/ded/stats for nation

## New Constraint Classes Required

- `AllowDiplomacyBetweenStatus(states: List<Int>, message: String)` – Only allows when diplomacy state is in list
- `DisallowDiplomacyBetweenStatus(stateMessages: Map<Int, String>)` – Blocks when diplomacy state matches
- `ReqDestNationGeneralMatch()` – Validates dest general belongs to dest nation
- `ReqEnvValue(field, op, value, message)` – Generic env value check
- `NearNation()` – Checks nations are geographically adjacent
- `AlwaysFail(message)` – Always denies (for non-reservable commands)

## New Helper Methods on NationCommand

- `pushHistoryLog(msg)` – General history log
- `pushNationalHistoryLog(msg)` – Own nation history
- `pushDestNationalHistoryLog(msg)` / `pushDestNationalHistoryLogFor(nationId, msg)` – Dest nation history
- `pushGlobalActionLog(msg)` / `pushGlobalHistoryLog(msg)` – System-wide logs
- `pushDestGeneralLog(msg)` / `pushDestGeneralHistoryLog(msg)` – Dest general logs
- `broadcastToNationGenerals(nationId, excludeId, msg)` – Send action log to all generals in a nation

## Pattern Notes

- **Diplomacy "제의" commands** (proposals): send `DiplomaticMessage`, no direct diplomacy state change, no exp/ded
- **Diplomacy "수락" commands** (accepts): directly update diplomacy table, no exp/ded, not reservable
- **Strategic commands** (수몰, 의병모집, 이호경식): have exp/ded = `5*(preReqTurn+1)`, strategic_cmd_limit=9, getPostReqTurn based on `sqrt(gennum*factor)*10`
