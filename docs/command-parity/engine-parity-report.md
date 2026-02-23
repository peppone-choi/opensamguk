# Engine Parity Report: Legacy PHP + Core2026 TS → Kotlin Backend

**Date:** 2026-02-23  
**Scope:** AI decision-making, battle resolution, turn execution  
**Status:** Kotlin engine is ~60-70% complete for core gameplay loop

---

## 1. File Mapping

### AI Layer

| Legacy PHP | Core2026 TS | Kotlin Backend | Status |
|---|---|---|---|
| `GeneralAI.php` (4293 lines) | N/A (no AI dir) | `ai/GeneralAI.kt` (400 lines) | ⚠️ Partial |
| `AutorunGeneralPolicy.php` | N/A | `ai/NpcPolicy.kt` | ✅ Good |
| (embedded in GeneralAI) | N/A | `ai/NationAI.kt` | ⚠️ Partial |
| (embedded in GeneralAI) | N/A | `ai/AIContext.kt` | ✅ Good |
| (embedded in GeneralAI) | N/A | `ai/DiplomacyState.kt` | ✅ Good |

### War/Battle Layer

| Legacy PHP | Core2026 TS | Kotlin Backend | Status |
|---|---|---|---|
| `WarUnit.php` (453 lines) | `war/units.ts` | `war/WarUnit.kt` (58 lines) | ✅ Good |
| `WarUnitGeneral.php` (378 lines) | `war/units.ts` | `war/WarUnitGeneral.kt` (95 lines) | ✅ Good |
| `WarUnitCity.php` (196 lines) | `war/units.ts` | `war/WarUnitCity.kt` (37 lines) | ✅ Good |
| (WarUnit + engine) | `war/engine.ts` (655 lines) | `war/BattleEngine.kt` (350 lines) | ✅ Good |
| N/A | `war/aftermath.ts` | `war/WarAftermath.kt` (532 lines) | ✅ Good |
| N/A | `war/actions.ts` | `war/BattleService.kt` (393 lines) | ✅ Good |
| `WarUnitTriggerCaller.php` | `war/triggers.ts` | `war/BattleTrigger.kt` (684 lines) | ✅ Good |
| N/A | `war/crewType.ts` | `CrewTypeAvailability.kt` + model `CrewType` | ✅ Good |
| N/A | N/A | `war/WarFormula.kt` (41 lines) | ✅ Good |

### Turn Processing

| Legacy PHP | Core2026 TS | Kotlin Backend | Status |
|---|---|---|---|
| `TurnExecutionHelper.php` (518 lines) | N/A | `TurnService.kt` (~450 lines) | ✅ Good |
| N/A | N/A | `TurnDaemon.kt` | ✅ Good |
| N/A | N/A | `turn/cqrs/TurnCoordinator.kt` | ✅ Good |

---

## 2. What's Working Well

### Turn Pipeline (TurnService.kt)
Complete monthly pipeline matching legacy `TurnExecutionHelper::executeAllCommand`:
- General command execution (with turn ordering)
- Nation command execution (officer level ≥ 5)
- NPC AI fallback for generals + nation actions
- Autorun support for inactive players (`autorun_limit`)
- Monthly processing: economy, diplomacy, events, maintenance
- Yearbook snapshots, tournament, auction processing
- Pre-turn triggers, blocked general handling

### Battle Engine (BattleEngine.kt)
Solid implementation with full trigger pipeline:
- War power computation (legacy formula with ARM_PER_PHASE)
- Critical/dodge/magic rolls with PRE/POST trigger hooks
- Siege phase (vs city units)
- Injury mechanics
- Rice consumption
- Crew type attack/defence coefficients
- Dex level scaling
- Modifier integration via BattleService

### War Aftermath (WarAftermath.kt + BattleService.kt)
- City occupation with development penalties
- Nation collapse with general release/penalty
- Capital relocation
- Tech gain from battles
- Dead counter tracking
- Conflict tracking
- NPC auto-join on nation destruction
- Officer demotion on city capture

---

## 3. Gaps & Missing Features

### 3.1 GeneralAI — MAJOR GAPS (Legacy: 4293 lines → Kotlin: 400 lines)

The legacy `GeneralAI.php` has ~50 detailed `do*()` methods for specific AI actions. The Kotlin version has simplified decision trees that lack:

#### Missing Nation-Level AI Actions (Legacy `chooseNationTurn`)
- **`do부대전방발령` / `do부대후방발령` / `do부대구출발령`** — Troop leader (부대장) assignment logic. Legacy has complex pathfinding, war-route calculation, supply-city classification. Kotlin `decideChiefAction` just returns "발령" without specifying which generals to move where.
- **`doNPC전방발령` / `doNPC후방발령` / `doNPC내정발령`** — NPC-specific assignment with front/rear/development classification. Legacy has city categorization (`categorizeNationCities`) with front/supply/backup cities. Kotlin has basic `frontCities`/`rearCities` but no supply/backup categorization.
- **`do유저장전방발령` / `do유저장후방발령` / `do유저장내정발령` / `do유저장구출발령`** — User general management by NPC chief. Not implemented.
- **`do유저장긴급포상` / `do유저장포상` / `doNPC긴급포상` / `doNPC포상`** — Detailed reward logic with resource sorting by urgency. Kotlin just returns "포상" without target selection.
- **`doNPC몰수`** — Confiscate resources from NPC generals with excessive gold/rice. Not implemented.
- **`do불가침제의`** — NAP proposal with complex target selection based on power, borders, existing relations. Kotlin `NationAI.shouldConsiderNAP` is simplified.
- **`do선전포고`** — War declaration with detailed power comparison, border analysis, timing consideration. Kotlin `shouldConsiderWar` is simplified.
- **`do천도`** — Capital relocation with supply chain analysis. Not implemented beyond basic pop comparison.
- **`choosePromotion` / `chooseNonLordPromotion`** — Officer promotion logic (quarterly). Not implemented in Kotlin.
- **`chooseTexRate` / `chooseGoldBillRate` / `chooseRiceBillRate`** — Tax/bill rate adjustment. Not implemented.
- **전시전략 (strategic commands)** — `급습`/`필사즉생`/`의병모집` are randomly selected; legacy has context-aware selection.

#### Missing General-Level AI Actions (Legacy `chooseGeneralTurn`)
- **`do징병`** — Complex recruitment logic considering city population, safe recruitment ratio, crew type selection. Kotlin simplifies to "모병" or "징병" based only on gold.
- **`do전투준비`** — Battle preparation with crew type consideration. Kotlin only checks train/atmos.
- **`do출병`** — Sortie logic with target city selection, war route calculation, troop coordination. Kotlin only checks `frontState > 0 && crew > 500`.
- **`do전방워프` / `do후방워프` / `do내정워프`** — Teleportation to specific cities based on role. Kotlin just returns "이동".
- **`do귀환`** — Return to home base. Simplified.
- **`do일반내정` / `do긴급내정` / `do전쟁내정`** — Development logic with rate calculations (`calcCityDevelRate`, `calcNationDevelopedRate`). Kotlin has basic threshold checks only.
- **`do금쌀구매`** — Gold/rice trading with amount calculation. Not implemented beyond returning "군량매매".
- **`doNPC헌납`** — Resource donation with amount calculation. Not implemented beyond returning "헌납".
- **`do소집해제`** — Disband troops. Not implemented.
- **`do집합`** — Troop rally. Simplified.
- **`do방랑군이동`** — Wanderer movement with intelligent city selection (join nations, go to recruiting cities). Kotlin has random action selection.
- **`do거병` / `do건국` / `do해산` / `do선양` / `do국가선택`** — Rebellion, nation founding, disbanding, abdication, nation selection for wanderers. Not implemented.
- **`doNPC사망대비`** — Death preparation (legacy: NPC succession planning). Not implemented.

#### Missing Infrastructure
- **`calcWarRoute`** — War route calculation for pathfinding between cities. Not implemented.
- **`categorizeNationCities`** — City classification into front/supply/backup. Partially implemented (front/rear only).
- **`categorizeNationGeneral`** — General classification into NPC civil/war, user civil/war, chiefs, lost generals, troop leaders. Not implemented.
- **NPC message broadcasting** — Legacy sends NPC personality messages (`npcmsg`). Not implemented.
- **Development rate calculation** — `calcNationDevelopedRate`, `calcCityDevelRate`. Not implemented.

### 3.2 Battle Engine — Minor Gaps

- **Dexterity (경험치) integration** — `getDexLog` is called with `(0, 0)` hardcoded in `computeWarPower`. Should use actual general dex values.
- **Crew type trigger integration** — Core2026 `appendCrewTypeTriggers` attaches crew-type-specific triggers. Kotlin `collectTriggers` only checks `specialCode`/`special2Code`.
- **Battle phase cap for general vs general** — Legacy may have phase limits. Kotlin has no cap for general-vs-general combat (siege is uncapped by design).
- **Kill/capture mechanics** — Legacy has general capture/kill on defeat. Not visible in current Kotlin.
- **Experience gain from battle** — Not visible in BattleEngine/BattleService.

### 3.3 Turn Processing — Minor Gaps

- **Instant nation turns** — Legacy `chooseInstantNationTurn` processes certain nation commands immediately. Not implemented.
- **Pre-command processing** — Legacy `preprocessCommand` handles block checks, general state. Kotlin has basic `blockState` check but may miss edge cases.
- **NPC message frequency** — Legacy `GameConst::$npcMessageFreqByDay`. Not implemented.

---

## 4. Priority Recommendations

### P0 — Critical for Gameplay
1. **Fix dex parameter in `computeWarPower`** — Currently hardcoded `(0, 0)`. Should pass actual general experience/dex.
2. **Implement war route / city categorization** — Without this, NPC generals can't navigate to front lines intelligently.
3. **Implement target selection for 출병** — NPCs need to know which city to attack.
4. **Implement 징병 with crew type and population awareness** — Current logic ignores city population limits.

### P1 — Important for AI Quality
5. **Implement `categorizeNationGeneral`** — Distinguish NPC war/civil, user war/civil, troop leaders.
6. **Implement detailed nation AI actions** — Assignment, promotion, tax rate, reward targeting.
7. **Implement war-time strategic command selection** — Context-aware instead of random.
8. **Implement development rate calculations** — For intelligent internal affairs decisions.

### P2 — Nice to Have
9. **Wanderer AI improvement** — Nation selection, rebellion, founding logic.
10. **NPC message broadcasting**
11. **Kill/capture mechanics in battle**
12. **Crew type triggers in battle**
13. **Instant nation turns**

---

## 5. What Was Fixed

No code changes were made in this review — the gaps identified are too architectural to fix piecemeal. The main issue is that the Kotlin `GeneralAI.kt` needs to grow from 400 lines to ~2000+ lines to match legacy complexity. The battle engine and turn processing are in good shape.

### Recommended Approach for AI Completion
1. Port `categorizeNationCities` and `categorizeNationGeneral` first (infrastructure)
2. Port `calcWarRoute` (pathfinding infrastructure)  
3. Port nation-level `do*` methods one by one into `NationAI.kt`
4. Port general-level `do*` methods one by one into `GeneralAI.kt`
5. Each method should be its own function, not inlined into decision trees

---

## 6. Summary

| Area | Legacy Complexity | Kotlin Coverage | Gap |
|---|---|---|---|
| General AI decision | ~50 action methods | ~10 simplified paths | **Large** |
| Nation AI decision | ~20 action methods | ~8 simplified paths | **Large** |
| Battle resolution | Full pipeline | Full pipeline | **Small** (dex bug) |
| War aftermath | Full | Full | **None** |
| Turn processing | Full pipeline | Full pipeline | **Small** |
| NPC Policy | Full | Full | **None** |
| War units | Full | Full | **None** |
| Battle triggers | Full framework | Full framework | **Small** (crew triggers) |
