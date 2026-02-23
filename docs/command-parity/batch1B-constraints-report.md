# Batch 1B - Constraint System Parity Report

**Date:** 2026-02-23
**Status:** ✅ Complete

## Summary

Reviewed all constraints across legacy PHP, core2026 TS, and backend Kotlin. Identified and implemented 20 missing constraints in Kotlin.

## Constraint Inventory

### Legend
- ✅ = Present and verified
- 🆕 = Newly implemented
- ⏭️ = Intentionally skipped (not portable or alias)

### General Constraints

| Constraint | Legacy PHP | Core2026 TS | Kotlin | Notes |
|---|---|---|---|---|
| NotBeNeutral | ✅ | ✅ | ✅ | |
| BeNeutral | ✅ | ✅ | ✅ | |
| BeChief | ✅ | ✅ | ✅ | |
| BeLord | ✅ | ✅ | ✅ | |
| BeMonarch | — | ✅ | ⏭️ | Alias for BeLord in core2026 |
| NotLord | ✅ | ✅ | ✅ | |
| NotChief | ✅ | ✅ | 🆕 | |
| MustBeNPC | ✅ | ✅ | ✅ | |
| ReqGeneralGold | ✅ | ✅ | ✅ | |
| ReqGeneralRice | ✅ | ✅ | ✅ | |
| ReqGeneralCrew | ✅ | ✅ | ✅ | |
| ReqGeneralCrewMargin | ✅ | ✅ | 🆕 | |
| ReqGeneralTrainMargin | ✅ | ✅ | ✅ | |
| ReqGeneralAtmosMargin | ✅ | ✅ | ✅ | |
| ReqGeneralValue | ✅ | ✅ | 🆕 | Generic general field check |
| AllowJoinAction | ✅ | ✅ | ✅ | |
| AllowJoinDestNation | ✅ | ✅ | ✅ | |
| AllowRebellion | ✅ | ✅ | 🆕 | Checks lord activity + NPC state |
| NoPenalty | ✅ | ✅ | ✅ | |
| ExistsDestGeneral | ✅ | ✅ | ✅ | |
| FriendlyDestGeneral | ✅ | ✅ | ✅ | |
| DifferentNationDestGeneral | ✅ | ✅ | ✅ | |
| DestGeneralInDestNation | — | ✅ | 🆕 | |
| AvailableRecruitCrewType | ✅ | — | 🆕 | Legacy-only |
| ExistsAllowJoinNation | ✅ | — | 🆕 | Legacy-only |
| ReqGeneralAge | — | — | ✅ | Kotlin-only extra |
| ReqGeneralStatValue | — | — | ✅ | Kotlin-only extra |
| NotInjured | — | — | ✅ | Kotlin-only extra |
| ReqOfficerLevel | — | — | ✅ | Kotlin-only extra |

### City Constraints

| Constraint | Legacy PHP | Core2026 TS | Kotlin | Notes |
|---|---|---|---|---|
| OccupiedCity | ✅ | ✅ | ✅ | |
| NotOccupiedCity | ✅ | ✅ | ✅ | |
| SuppliedCity | ✅ | ✅ | ✅ | |
| SuppliedDestCity | ✅ | ✅ | ✅ | |
| RemainCityCapacity | ✅ | ✅ | ✅ | |
| RemainCityTrust | ✅ | ✅ | ✅ | |
| ReqCityCapacity | ✅ | ✅ | 🆕 | |
| ReqCityTrust | ✅ | ✅ | 🆕 | |
| ReqCityValue | ✅ | — | 🆕 | Legacy-only generic |
| ReqDestCityValue | ✅ | — | 🆕 | Legacy-only generic |
| ReqCityTrader | ✅ | ✅ | ✅ | |
| ReqCityLevel | — | ✅ | 🆕 | Core2026-only |
| NearCity | ✅ | ✅ | ✅ | |
| NeutralCity | ✅ | ✅ | ✅ | |
| ConstructableCity | ✅ | ✅ | ✅ | |
| BattleGroundCity | ✅ | ✅ | ✅ | |
| NotSameDestCity | ✅ | ✅ | ✅ | |
| NotOccupiedDestCity | ✅ | ✅ | ✅ | |
| NotNeutralDestCity | ✅ | ✅ | ✅ | |
| OccupiedDestCity | ✅ | ✅ | ✅ | |
| NotCapital | ✅ | ✅ | ✅ | |
| HasRoute | ✅ | — | ✅ | |
| HasRouteWithEnemy | ✅ | ✅ | ✅ | |
| ExistsDestCity | — | ✅ | 🆕 | |
| RemainCityCapacityByMax | — | ✅ | ⏭️ | Covered by RemainCityCapacity |

### Nation Constraints

| Constraint | Legacy PHP | Core2026 TS | Kotlin | Notes |
|---|---|---|---|---|
| NotWanderingNation | ✅ | ✅ | ✅ | |
| WanderingNation | ✅ | ✅ | ✅ | |
| AllowWar | ✅ | ✅ | ✅ | |
| AvailableStrategicCommand | ✅ | ✅ | ✅ | |
| ReqNationGold | ✅ | ✅ | ✅ | |
| ReqNationRice | ✅ | ✅ | ✅ | |
| ReqNationValue | ✅ | ✅ | ✅ | |
| ReqNationAuxValue | ✅ | ✅ | 🆕 | |
| ReqDestNationValue | ✅ | ✅ | 🆕 | |
| ExistsDestNation | ✅ | ✅ | ✅ | |
| DifferentDestNation | ✅ | ✅ | ✅ | |
| CheckNationNameDuplicate | ✅ | ✅ | ✅ | |
| ReqNationGenCount | — | ✅ | ✅ | |
| NearNation | ✅ | ✅ | 🆕 | |

### Diplomacy Constraints

| Constraint | Legacy PHP | Core2026 TS | Kotlin | Notes |
|---|---|---|---|---|
| AllowDiplomacyStatus | ✅ | ✅ | 🆕 | |
| AllowDiplomacyBetweenStatus | ✅ | ✅ | 🆕 | |
| AllowDiplomacyWithTerm | ✅ | ✅ | 🆕 | |
| DisallowDiplomacyBetweenStatus | ✅ | ✅ | 🆕 | |
| DisallowDiplomacyStatus | ✅ | ✅ | 🆕 | |
| AllowDiplomacy | — | — | ✅ | Kotlin-only (officer level check) |

### Troop Constraints

| Constraint | Legacy PHP | Core2026 TS | Kotlin | Notes |
|---|---|---|---|---|
| MustBeTroopLeader | ✅ | ✅ | ✅ | |
| ReqTroopMembers | ✅ | ✅ | ✅ | |

### Misc Constraints

| Constraint | Legacy PHP | Core2026 TS | Kotlin | Notes |
|---|---|---|---|---|
| AlwaysFail | ✅ | ✅ | ✅ | |
| NotOpeningPart | ✅ | ✅ | ✅ | |
| BeOpeningPart | ✅ | ✅ | ✅ | |
| ReqEnvValue | ✅ | ✅ | ✅ | |
| AdhocCallback | ✅ | — | ⏭️ | PHP-only callable, not portable |

## Newly Implemented (20 constraints)

All added to `ConstraintHelper.kt`:

1. **AllowDiplomacyStatus** - Check if nation has any diplomacy matching allowed states
2. **AllowDiplomacyBetweenStatus** - Check diplomacy between two specific nations
3. **AllowDiplomacyWithTerm** - Check diplomacy state with minimum term requirement
4. **DisallowDiplomacyBetweenStatus** - Reject specific diplomacy states between nations
5. **DisallowDiplomacyStatus** - Alias for DisallowDiplomacyBetweenStatus
6. **AllowRebellion** - Check if rebellion is possible (lord inactive, not NPC)
7. **NotChief** - Reject if officer level >= 12
8. **ReqGeneralValue** - Generic general field comparison
9. **ReqGeneralCrewMargin** - Check if crew can still be recruited
10. **AvailableRecruitCrewType** - Check crew type availability
11. **ExistsAllowJoinNation** - Check if joinable nations exist
12. **ReqCityValue** - Generic city field comparison
13. **ReqDestCityValue** - Generic dest city field comparison
14. **ReqCityCapacity** - Check city field meets minimum
15. **ReqCityTrust** - Check city trust meets minimum
16. **ReqCityLevel** - Check city level in allowed list
17. **ExistsDestCity** - Check dest city exists
18. **ReqDestNationValue** - Generic dest nation field comparison
19. **ReqNationAuxValue** - Nation aux/meta value comparison with default
20. **NearNation** - Check if nations are geographically adjacent
21. **DestGeneralInDestNation** - Check dest general belongs to dest nation

Also added supporting helper functions for diplomacy state reading, generic comparison, and nation list parsing.

## Intentionally Skipped (3)

1. **AdhocCallback** - PHP callable pattern, not applicable to Kotlin
2. **BeMonarch** - Alias for BeLord (identical logic, already present)
3. **RemainCityCapacityByMax** - Covered by existing RemainCityCapacity which already handles key/max pairs

## Verification Notes

- Existing Kotlin constraints were verified against legacy PHP and core2026 TS logic
- **BeChief vs BeLord**: Both check `officerLevel >= 12` — matches legacy behavior
- **NotWanderingNation vs NotBeNeutral**: Both check `nationId == 0` with different error messages — correct
- **AllowWar**: Checks `nation.warState == 0` — simplified from legacy but functionally equivalent
- **AllowJoinAction**: Uses `makeLimit` field — matches legacy `join_limit` concept
- **HasRouteWithEnemy**: BFS pathfinding with war nation passthrough — matches core2026 logic

## Entity Requirements

The new constraints assume these fields exist on entity classes:
- `City.meta: Map<String, Any>`, `City.trust: Float`, `City.level: Int`, `City.trade: Int`
- `General.meta: Map<String, Any>`, `General.leadership: Int`, `General.crewTypeId: Int`
- `Nation.meta: Map<String, Any>`, `Nation.capitalCityId: Long?`

## Env Keys Used by New Constraints

| Key | Type | Used By |
|---|---|---|
| `diplomacyList` | `List<Map>` | AllowDiplomacyStatus |
| `diplomacyMap` or `diplomacy_{src}_{dest}` | `Map` or `Number` | Diplomacy constraints |
| `killturn` | `Number` | AllowRebellion |
| `lordKillturn` | `Number` | AllowRebellion |
| `lordNpcState` | `Number` | AllowRebellion |
| `availableCrewTypes` | `Set<Int>` | AvailableRecruitCrewType |
| `nationList` | `List<Map>` | ExistsAllowJoinNation |
| `mapAdjacency` | `Map<Long, List<Long>>` | NearNation |
| `cityNationById` | `Map<Long, Long>` | NearNation |
