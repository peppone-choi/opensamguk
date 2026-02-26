# Parity Audit V3 — Batch 5 (Management / Military / Misc)

## Pages

### personnel/page.tsx — 🟡 MINOR GAPS

_New page vs `legacy/hwe/b_myBossInfo.php` + `legacy/hwe/ts/bossInfo.ts`_

- Gap 1: Legacy permission check — `officer_level == 0` → "재야입니다" exit; new page checks `nationId` but not officer level for viewing
- Gap 2: Legacy has `chiefStatMin` (GameConst) threshold for filtering candidates by strength/intel; new uses hardcoded 40 in superior page but personnel page has no stat min filter for appointments
- Gap 3: New page has simplified officer level as a raw number input; legacy renders specific named officer positions (군주/군사/etc) based on nation level
- Gap 4: New page shows all officers in a flat table; legacy separates 수뇌부 (level 5-12) from 도시 관직 (level 2-4) with different appointment logic
- **Note:** Most of these features are actually in `superior/page.tsx` instead, which is the proper parity target. Personnel page seems like a simplified duplicate.

### superior/page.tsx — ✅ PARITY (GOOD)

_New page vs `legacy/hwe/b_myBossInfo.php` + `legacy/hwe/ts/bossInfo.ts`_

- Very good parity: officer display paired by level, 오호장군/건안칠자, 수뇌부 임명, 도시 관직 임명, 추방, 외교권자/조언자 임명
- Gap 1 (minor): Legacy uses `isOfficerSet(chief_set, level)` to lock specific officer slots from being changed; new doesn't check `chief_set`/`officer_set` flags
- Gap 2 (minor): Legacy uses `chiefStatMin` from `GameConst` server value; new hardcodes 40
- Gap 3 (minor): Legacy shows city level badge (도/주/군/현) next to city name in officer list; new omits city level display
- Gap 4 (minor): Legacy officer_set on cities controls which city officer slots are locked; new doesn't reflect `officer_set` status

### internal-affairs/page.tsx — 🟡 MINOR GAPS

_New page vs legacy internal affairs (nation policy PHP, scattered across multiple files)_

- Gap 1: New page adds diplomacy status tab and finance calculator — these are **additions** not in legacy internal affairs page
- Gap 2: Legacy internal affairs/내무 had separate pages for different nation management; new consolidates into one tabbed page
- Gap 3: New page has `blockWar` and `blockScout` toggle switches — these are mapped to legacy `SetBlockScout.php` etc but need to verify exact API mapping
- Gap 4: New page WYSIWYG editor for notice is an improvement over legacy
- Overall good coverage. The new page is actually an **enhancement** over legacy.

### troop/page.tsx — 🟡 MINOR GAPS

_New page vs `legacy/hwe/ts/v_troop.ts` + `legacy/hwe/ts/extPluginTroop.ts` + `legacy/hwe/ts/PageTroop.vue`_

- Gap 1: Legacy `extPluginTroop.ts` had troop plugin extensions (예약 명령 brief, extended troop controls); new page has `reservedCommandBrief` display but may not match all plugin features
- Gap 2: New page adds **TurnBrief** and **CommandTimeline** components — these are great additions not in legacy
- Gap 3: Legacy had specific officer level check for troop creation (`officer_level >= 4` for rename); new checks this
- Gap 4 (minor): Legacy troop page embedded within the game frame with specific PHP validation; new relies on API-level validation
- Overall good coverage with improvements.

### spy/page.tsx — 🟡 MINOR GAPS

_New page vs legacy spy/scout PHP (scattered: ScoutMessage.php, SetScoutMsg.php, SetBlockScout.php)_

- Gap 1: Legacy spy/scout was primarily a command result display integrated into the main page message system; new page creates a dedicated spy mailbox UI
- Gap 2: New page adds **message sending** to specific generals, **recipient groups**, and **forwarding** — these are major enhancements not in legacy
- Gap 3: The `isSpyReport` filter logic is a new invention; legacy didn't have a separate spy message inbox
- Gap 4: Legacy scout message was set via nation policy; new has dedicated scout message tab in internal-affairs
- **Note:** This is largely a new feature, not a legacy port. No parity issues since legacy didn't have a comparable page.

### traffic/page.tsx — ✅ PARITY

_New page vs `legacy/hwe/a_traffic.php`_

- All three sections present: 접속량 (refresh count bars), 접속자 (online user bars), 주의대상자 (top refreshers)
- Traffic bar color calculation matches legacy `getTrafficColor` logic (red-blue gradient)
- Max record display present
- Refresh score total display present
- Very good parity.

### npc-control/page.tsx — 🔴 MAJOR GAPS

_New page vs `legacy/hwe/ts/PageNPCControl.vue` + `legacy/hwe/ts/v_NPCControl.ts`_

- Gap 1: **Missing all legacy NPC policy fields.** Legacy has ~20 specific named policy fields (reqNationGold, reqNationRice, reqHumanWarUrgentGold/Rice, reqHumanWarRecommandGold/Rice, reqHumanDevelGold/Rice, reqNPCWarGold/Rice, reqNPCDevelGold/Rice, minimumResourceActionAmount, maximumResourceActionAmount, minWarCrew, minNPCRecruitCityPopulation, safeRecruitCityPopulationRatio, minNPCWarLeadership, properWarTrainAtmos, cureThreshold). New page uses generic categorized policy fields (warPolicy, recruitPolicy, etc.) that don't match legacy field names or semantics.
- Gap 2: **Missing `zeroPolicy` computed defaults.** Legacy shows "0이면 ..." with computed fallback values from server; new has no equivalent.
- Gap 3: **Missing `calcPolicyValue` logic** — legacy computes derived values when policy value is 0 (e.g., reqHumanWarRecommandGold = reqHumanWarUrgentGold \* 2 when 0).
- Gap 4: **Missing CombatForce/SupportForce/DevelopForce** hidden JSON fields (troop assignment config).
- Gap 5: **Priority items don't match legacy.** Legacy uses `NPCChiefActions` and `NPCGeneralActions` typed enums with specific item sets loaded from server (`staticValues.availableNationPriorityItems`); new uses hardcoded generic strings.
- Gap 6: Legacy priority list uses `vuedraggable` with active/inactive split; new has a similar DnD UI but items are hardcoded and may not match actual server-side action types.
- Gap 7: **Missing NumberInputWithInfo-style** detailed help tooltips explaining each policy field's effect.
- Gap 8: New page adds NPC list tab, general-level override, NPC mode selector, and settings history — these are additions.

### npc-list/page.tsx — 🟡 MINOR GAPS

_New page vs `legacy/hwe/b_genList.php`_

- Gap 1: Need to verify legacy `b_genList.php` had the same fields; new shows name, owner, level, nation, personality, special, stats, experience, dedication
- Gap 2: New page has enhanced sorting and filtering vs legacy
- Gap 3 (minor): Legacy may have had additional columns (도시, 병력 etc.) for general list; new focuses on NPC-specific view
- Overall reasonable parity for an NPC list view.

### page.tsx (game dashboard) — 🟡 MINOR GAPS

_New page vs `legacy/hwe/ts/v_main.ts` + `legacy/hwe/ts/v_front.ts` + `legacy/hwe/ts/PageFront.vue`_

- Gap 1: Legacy `PageFront.vue` has full Vue component with `SammoAPI.Global.ExecuteEngine` for server-side turn execution with lock checking; new uses `frontApi.getInfo` which may not trigger engine execution
- Gap 2: Legacy has `responseLock` pattern with 3-second timeout race for refresh; new doesn't have this timeout pattern
- Gap 3: Legacy has `lastVoteState` localStorage tracking for vote notification; new doesn't track vote state
- Gap 4: Legacy has `GameConstStore` with version checking (`showVersionInfo` modal); new doesn't have version info modal
- Gap 5: Legacy MessagePanel is deeply integrated with general ID/name/nationID/permissionLevel; new passes simpler props
- Gap 6: Legacy has `GlobalMenu` component with `reqMenuCall` handler; new has `MainControlBar` instead
- Gap 7: New page adds **WebSocket** subscription for real-time updates — this is a major improvement over legacy polling
- Gap 8: New page adds mobile tab navigation, general status summary, nation power summary — these are enhancements
- Gap 9 (minor): Legacy record zone uses `Denque` for efficient prepend with 15-item limit; new renders full array from API response
- Gap 10: Legacy uses `formatLog` from utilGame to format log HTML; new renders raw message text without HTML formatting in records

## Shared Components Audit

### gameApi.ts — ✅ GOOD COVERAGE

All major legacy API endpoints are mapped:

- ✅ Front info (frontApi)
- ✅ Nation management (nationManagementApi) — officers, expel, permissions
- ✅ Nation policy (nationPolicyApi) — policy, notice, scout msg
- ✅ NPC policy (npcPolicyApi) — policy, priority
- ✅ Troop (troopApi) — CRUD, join/exit/kick/rename/disband
- ✅ Traffic (trafficApi)
- ✅ Messages (messageApi) — send, get, board, secret
- ✅ Commands (commandApi) — turns, execute, nation turns
- ✅ Realtime (realtimeApi)
- ✅ Diplomacy (diplomacyApi)
- ✅ Tournament, Betting, Vote, Auction, Items, Inheritance, Map, Scenarios
- ✅ Battle Simulator
- ✅ Admin APIs

Missing legacy endpoints (if any exist):

- ❓ `j_set_npc_control.php` — mapped to `npcPolicyApi` but field names differ
- ❓ Legacy used `SammoAPI.Global.ExecuteEngine` for turn execution; new has `realtimeApi.execute`

### game-utils.ts — 🟡 MINOR GAPS vs legacy utilGame/

Present in new:

- ✅ `formatOfficerLevelText`, `getNPCColor`, `formatInjury`, `calcInjury`, `formatRefreshScore`, `nextExpLevelRemain`, `formatDexLevel`, `formatHonor`, `formatDefenceTrain`, `isValidObjKey`, `convTechLevel`, `getMaxRelativeTechLevel`, `isTechLimited`, `formatGeneralTypeCall`
- ✅ Additional: `isBrightColor`, `ageColor`, `statColor`, `trustColor`, `numberWithCommas`, `CREW_TYPE_NAMES`, `REGION_NAMES`, `CITY_LEVEL_NAMES`

Missing from new (present in legacy utilGame/):

- ❌ `formatLog` — exists separately at `@/lib/formatLog`, not in game-utils (minor: different location)
- ❌ `formatCityName` — not in game-utils
- ❌ `formatVoteColor` — not in game-utils
- ❌ `postFilterNationCommandGen` — not in game-utils (may be handled differently)
- ❌ `calcTournamentTerm` — not in game-utils (tournament page may compute inline)

### components/game/ — ✅ GOOD COVERAGE

Present: city-basic-card, command-panel, command-select-form, command-arg-form, empty-state, error-state, game-bottom-bar, general-basic-card, general-portrait, konva-map-canvas, loading-state, main-control-bar, map-viewer, message-panel, message-plate, nation-badge, nation-basic-card, page-header, record-zone, resource-display, sammo-bar, stat-bar, turn-timer, dev-bar

## Summary

| Page             | Status    | Key Issues                                                                                                                          |
| ---------------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| personnel        | 🟡 MINOR  | Simplified duplicate of superior; missing officer level name mapping                                                                |
| superior         | ✅ GOOD   | Minor: missing chief_set/officer_set lock checks, hardcoded stat min                                                                |
| internal-affairs | 🟡 MINOR  | Enhanced over legacy; minor API mapping verification needed                                                                         |
| troop            | 🟡 MINOR  | Good with enhancements; plugin features may differ                                                                                  |
| spy              | 🟡 MINOR  | Largely new feature, not a port; no legacy equivalent page                                                                          |
| traffic          | ✅ PARITY | Excellent match                                                                                                                     |
| npc-control      | 🔴 MAJOR  | Policy fields completely different from legacy; missing zeroPolicy, calcPolicyValue, force assignments, correct priority item types |
| npc-list         | 🟡 MINOR  | Reasonable; verify against legacy columns                                                                                           |
| page (dashboard) | 🟡 MINOR  | Missing engine execution trigger, vote tracking, version modal; good additions (WebSocket, mobile tabs)                             |
| gameApi.ts       | ✅ GOOD   | Comprehensive API coverage                                                                                                          |
| game-utils.ts    | 🟡 MINOR  | Missing 5 utilities (exist elsewhere or not ported)                                                                                 |
| components/game/ | ✅ GOOD   | Comprehensive component set                                                                                                         |

**Critical fix needed:** `npc-control/page.tsx` policy fields need to match legacy `NationPolicy` type fields exactly.
