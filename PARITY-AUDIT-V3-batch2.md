# Parity Audit V3 — Batch 2 (Core Game 1)

Generated: 2026-02-24

---

### commands — 🟡 MINOR GAPS

- Gap 1: Legacy `StoredActionsHelper` saves recent actions to localStorage for quick re-use; new code has no stored/recent actions feature
- Gap 2: Legacy processing has per-command Vue forms (e.g., `che_징병.vue`, `che_등용.vue`, `che_군량매매.vue`) with rich per-action UI; new code delegates to generic `CommandPanel` and `CommandArgForm` — need to verify all command-specific argument forms exist
- Gap 3: Legacy `submitCommand` redirects to `v_chiefCenter.php` after nation commands; new code redirects to `/commands?mode=nation` which is fine but may miss chief center integration

### processing — ✅ PARITY

- New code handles both form mode (command arg submission) and wait mode (turn processing with WebSocket + 30s fallback) — matches legacy behavior adequately
- Legacy `v_processing.ts` creates Vue component from `entryInfo` and dispatches `customSubmit` event; new code uses `CommandArgForm` component + API calls — functionally equivalent

### map — 🟡 MINOR GAPS

- Gap 1: Legacy `map.ts` has touch device support with single-tap toggle button and touch event handling; new SVG map has basic click but no mobile touch optimizations
- Gap 2: Legacy has `saveCityInfo` that stores city data for cross-page reference; new code doesn't persist map data to localStorage
- Gap 3: Legacy `setCityClickable` links cities to `v_currentCity.php` pages; new map tooltip doesn't link to city detail page
- Gap 4: Legacy has dynamic map theme based on server config (`setMapBackground` with seasonal backgrounds/images); new code has simple color themes but no image-based backgrounds
- Gap 5: Legacy `v_cachedMap.ts` → `PageCachedMap.vue` may have historical map playback; new code only shows history log text, not historical map snapshots
- Gap 6: Legacy `recent_map.ts` returns history as rendered HTML; new code fetches structured data — OK if API provides it

### city — 🟡 MINOR GAPS

- Gap 1: Legacy `extExpandCity.ts` has drag-and-drop officer assignment (태수/군사/종사) for 수뇌 (chiefs); new code only displays officers read-only
- Gap 2: Legacy `loadDuty` fetches `b_myBossInfo.php` to show officer assignment UI with select2 dropdowns; new code has no officer assignment feature
- Gap 3: Legacy `currentCity.ts` uses select2 city selector that auto-submits on select; new code uses a standard `<select>` — functionally equivalent but different UX
- Gap 4: Legacy has city-specific remaining stat warnings (warn주민, warn농업, etc. with color indicators); new code uses `SammoBar` but no explicit warning thresholds

### messages — 🟡 MINOR GAPS

- Gap 1: Legacy has `responseMessage` for diplomacy accept/reject prompts (수락/거절 buttons on diplomacy messages with `option.action`); new code has no diplomacy action response buttons
- Gap 2: Legacy has message delete with 5-minute time window (`last5min` check); new code allows delete anytime without time restriction
- Gap 3: Legacy has `option.overwrite` and `option.hide` message handling for edited/hidden messages; new code doesn't handle these message options
- Gap 4: Legacy uses `lastSequence` for incremental message polling (only fetches new messages); new code refetches all messages on each refresh
- Gap 5: Legacy mailbox selector groups generals by nation with color-coded optgroups and shows 즐겨찾기 (favorites), 아국 메세지, 전체 메세지 shortcuts; new code uses simpler recipient type selection
- Gap 6: Legacy `last_contact` tracks most recent private message contact for quick reply; new code has `handleReplyTo` per message but no persistent last-contact feature

### board — ✅ PARITY

- Both have article creation (title + content), article listing, comments with create/delete
- Legacy uses `isSecretBoard` prop; new code uses tab-based UI with public/secret boards — functionally equivalent
- Legacy `BoardArticle.vue` has comment support via `submitComment` event; new code has inline comment section — adequate

### battle — 🟡 MINOR GAPS

- Gap 1: New code combines 전쟁 현황, 군사력 comparison, 전선 info, and personal battle logs — this is richer than legacy `v_battleCenter.ts` which focused on per-general inspection
- Gap 2: Legacy `battleCenter.ts` has battle log rendering and detail page functionality; new battle page uses `generalLogApi.getOldLogs` for personal logs but doesn't show real-time battle event stream
- Note: New code actually adds features not in legacy (military comparison table, front line view)

### battle-center — ✅ PARITY

- New code faithfully reproduces legacy `PageBattleCenter.vue`: general selector with sort options, prev/next navigation, GeneralBasicCard, GeneralSupplementCard, 4 log types (generalHistory, battleDetail, battleResult, generalAction)
- New code adds comparison mode (비교 모드) which is an enhancement over legacy
- Legacy `getNPCColor`, sort keys (recent_war, warnum, turntime, name), officer level display — all present in new code
- Gap: Legacy shows `turnterm` and `lastExecuted` in GeneralBasicCard (turn timing info); new code doesn't display these

### battle-simulator — 🟡 MINOR GAPS

- Gap 1: Legacy has drag-and-drop for general import (drop JSON files onto general cards); new code uses file picker button
- Gap 2: Legacy supports multiple defenders with copy/delete individual defenders; new code has add/remove defenders — functionally equivalent
- Gap 3: Legacy `btn-general-import-server` loads general from server via modal; new code has inline dropdown per UnitBuilder — functionally equivalent
- Gap 4: Legacy exports individual general info to file; new code exports/imports entire config — arguably better
- Gap 5: New code adds terrain/weather selectors and inherit buff editing that may not be in legacy form fields
- Gap 6: New code adds 1000-repeat summary with win rate visualization — enhancement over legacy

---

## Summary

| Page             | Status    | Notes                                                                    |
| ---------------- | --------- | ------------------------------------------------------------------------ |
| commands         | 🟡 MINOR  | Missing stored actions, command-specific forms need verification         |
| processing       | ✅ PARITY | Functionally equivalent                                                  |
| map              | 🟡 MINOR  | Missing touch UX, image themes, city links, historical map snapshots     |
| city             | 🟡 MINOR  | Missing officer assignment drag-and-drop for chiefs                      |
| messages         | 🟡 MINOR  | Missing diplomacy response buttons, message options, incremental polling |
| board            | ✅ PARITY | Full feature coverage                                                    |
| battle           | 🟡 MINOR  | Different focus; adds features but misses real-time battle stream        |
| battle-center    | ✅ PARITY | Faithful reproduction + comparison enhancement                           |
| battle-simulator | 🟡 MINOR  | Missing drag-and-drop; adds terrain/weather/repeat features              |

**Critical gaps (🔴):** None found.
**Most impactful gap:** Messages page missing diplomacy action response (수락/거절) and city page missing officer assignment UI for chiefs.
