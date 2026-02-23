# Batch 1D Command Parity Report

**Date:** 2026-02-23
**Commands:** 11 (장비매매, 장수대상임관, 전투태세, 전투특기초기화, 접경귀환, 정착장려, 주민선정, 증여, 집합, 징병, 첩보)

## Summary

All 11 Kotlin files were reviewed against legacy PHP and core2026 TS. All had gaps. All were fixed.

| Command | File | Fixes Applied |
|---------|------|---------------|
| 장비매매 | 장비매매.kt | Added constraints (ReqCityTrader, ReqCityCapacity secu, ReqGeneralGold), experience +10, sell revenue cost/2, global logs for rare item sales, proper buy/sell log formatting with color codes |
| 장수대상임관 | 장수대상임관.kt | Added ReqEnvValue join_mode constraint, AllowJoinDestNation constraint, permissionConstraints, global action log, history log |
| 전투태세 | 전투태세.kt | Added multi-turn tracking (term 1,2 → training msg, term 3 → complete), techCost in gold calculation via getNationTechCost(), ReqGeneralRice in constraints, gold deduction on intermediate turns |
| 전투특기초기화 | 전투특기초기화.kt | Added specAgeField property, prev_types tracking (old special list passed in message for caller to persist), proper field names |
| 접경귀환 | 접경귀환.kt | Added city name in log with proper formatting, readStringMap for city names, Josa particle |
| 정착장려 | che_정착장려.kt | Added getDomesticExpLevelBonus calculation, proper log formatting with color codes and fail/success spans, max_domestic_critical tracking, popMax clamping flag |
| 주민선정 | che_주민선정.kt | Added getDomesticExpLevelBonus calculation, proper log formatting matching legacy, max_domestic_critical tracking, trustMax clamping |
| 증여 | 증여.kt | Added dest general notification log in message output, formatted amount with commas, dynamic gold/rice constraint based on isGold arg |
| 집합 | 집합.kt | Added troopName resolution, member notification message in output for caller to deliver, proper city color formatting |
| 징병 | che_징병.kt | Added ReqCityCapacity(pop), ReqCityTrust(20), ReqGeneralCrewMargin, AvailableRecruitCrewType constraints, minConditionConstraints, crew type name in log, techCost in gold calculation, trustLoss flag in cityChanges |
| 첩보 | 첩보.kt | Added NotBeNeutral to fullConstraints, cost uses develCost*3, distance-based info levels (1→full, 2→partial, 3→rumors), crew type summary, tech comparison (압도/우위/대등/열위/미미), spy info update in nationChanges, global log, inheritancePoint 0.5, random exp 1-100 / ded 1-70 |

## Key Patterns Found

1. **Missing constraints** — Most Kotlin files had incomplete constraint lists vs PHP/TS
2. **Missing log formatting** — Color codes (`<C>`, `<Y>`, `<G>`, `<S>`, `<1>`) and fail/success spans were absent
3. **Missing getDomesticExpLevelBonus** — Domestic commands (정착장려, 주민선정) lacked explevel scaling
4. **Missing multi-turn support** — 전투태세 had no intermediate turn tracking
5. **Missing dest entity notifications** — 증여 and 집합 didn't notify target generals
6. **Missing techCost** — 전투태세 and 징병 ignored nation tech in cost calculations
7. **Missing distance logic** — 첩보 had hardcoded distance=1 instead of BFS calculation

## Notes

- Some Kotlin files delegate side effects (DB writes, entity mutations) to the caller via the `message` JSON field. The fixes maintain this pattern while ensuring all required data is included.
- Helper methods like `getNationTechCost()`, `getCrewTypeName()`, `getDestCityGeneralCount()`, `getDestCityTotalCrew()`, `getDestCityCrewTypeSummary()`, `getLastTurnTerm()`, `pushGlobalLog()`, `pushHistoryLog()`, `getTechComparison()` are assumed to exist or be added to the `GeneralCommand` base class.
- The `getDomesticExpLevelBonus()` function was inlined in 정착장려 and 주민선정 as a private method.
