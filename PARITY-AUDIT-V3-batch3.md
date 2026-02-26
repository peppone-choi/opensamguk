# Parity Audit V3 — Batch 3 (Core Game 2)

Generated: 2026-02-24

---

### my-page — 🟡 MINOR GAPS

- **Missing: `buildNationCandidate` (거병) button** — Legacy has `#buildNationCandidate` to declare nation candidacy with confirmation. New page lacks this.
- **Missing: `instantRetreat` (접경 귀환) button** — Legacy has `#instantRetreat` for instant retreat to allied border. New page has `borderReturn` as a setting toggle but not the instant action button.
- **Missing: `dieOnPrestart` (사전거병 삭제) button** — Legacy has `#dieOnPrestart` for pre-start deletion with confirmation. New has it as a settings checkbox (`preRiseDelete`) but lacks the direct action button.
- **Missing: `use_auto_nation_turn` setting** — Legacy has `#use_auto_nation_turn` toggle for auto nation turn. Not present in new settings.
- **Missing: Screen mode radio** — Legacy has `input:radio[name=screenMode]` for auto/PC/mobile screen mode stored in localStorage. Not present in new.
- **Old log pagination** — Legacy has `.load_old_log` for paginated old log loading by type (with sequence-based cursor). New has `loadOldLogs` with similar functionality — ✅ present.
- **Item drop with unique item double-confirm** — Legacy double-confirms for unique (non-buyable) items. New only does a single confirm. Minor.

### general — ✅ PARITY

- New `general/page.tsx` shows general detail with stats, equipment, proficiency, battle records, nation generals tab — covers legacy general detail PHP functionality.

### generals + generals/[id] — ✅ PARITY

- List page shows all generals with sort/filter. Detail page shows full general profile with stats, equipment, proficiency bars, battle record history. Matches legacy general list/detail pages.

### nation — 🟡 MINOR GAPS

- **Missing: `nationMsg` (국가 방침) rich text editor** — Legacy uses TipTap WYSIWYG editor for nation notice with rich HTML. New uses plain `Textarea`. Rich formatting lost.
- **Missing: `scoutMsg` (임관 권유문) rich text editor** — Same as above; legacy has TipTap editor with 870px width constraint. New likely uses plain text.
- **Missing: `secretLimit` (기밀 권한) policy setting** — Legacy has `secretLimit` (1-99년) policy control. Not found in new nation page.
- **Missing: `blockWar` (전쟁 금지) toggle** — Legacy has war ban toggle with remaining count display (`warSettingCnt`). Not in new.
- **Missing: `blockScout` (임관 금지) toggle** — Legacy has scout block toggle. Not in new.
- **Missing: War setting count display** — Legacy shows remaining war setting uses, monthly increment, and max. Not in new.
- **Missing: Diplomacy end-date calculation** — Legacy calculates and displays diplomacy end year/month based on current year+month+term. New just shows term.

### nations — 🟡 MINOR GAPS

- **Missing: Hover popup for general detail** — Legacy `extKingdoms.ts` has hover popup showing full general row (face, age, personality, skill, level, nation, honor, rank, stats, killturn, penalty) on mouse-over. New has expandable rows but no hover popup.
- **Missing: Penalty-based coloring** — Legacy colors generals yellow (penalty≥1500) or lightgreen (penalty≥200). New doesn't color by penalty.
- **Missing: killturn-based strikethrough** — Legacy strikes through generals past their kill turn. New doesn't.
- **Missing: NPC color coding** — Legacy applies `getNPCColor` to general names. New may not consistently apply this.
- **Missing: "전투장" summary calculation** — Legacy calculates combat-capable user generals and NPC generals separately with estimated troop counts. New has general counts but not the combat-specific breakdown.

### nation-cities — 🟡 MINOR GAPS

- **Missing: "암행부 연동" (general list integration)** — Legacy `extExpandCity.ts` has a button to load generals per city with detailed columns (stats, troop, gold, rice, guard, crew type, training, morale, action, killturn, turn). New page doesn't embed generals per city in the same view.
- **Missing: "인사부 연동" (duty/appointment integration)** — Legacy has inline appointment buttons (태/군/종) per general per city after loading duty data. New has appointment mode but simpler.
- **Missing: Remaining capacity warnings** — Legacy calculates remaining capacity (e.g., `remain농업 = 농업 - max농업`) and highlights with yellow `[remaining]` annotations when near max. New doesn't show remaining capacity warnings.
- **Missing: Color-coded stat values** — Legacy color-codes city stats (green/yellow/red) based on percentage of max. New doesn't.
- **Missing: "배치 장수 수" sort** — Legacy can sort by number of generals per city. New sorts by city stats only.
- **Missing: 인구율 sort** — Legacy has population ratio sort. New has pop sort but not ratio.

### nation-generals — ✅ PARITY

- New uses table with configurable column visibility, shows all key fields (officer level, stats, crew, training/morale, troop, battle record, equipment, NPC status). Legacy Vue uses ag-grid with `GeneralList` component. Feature-wise comparable.

### diplomacy — 🟡 MINOR GAPS

- **Missing: `prev_no` (선행 문서 참조)** — Legacy diplomacy letters have `prev_no` linking to previous documents (chain/renewal). New has chain progress (제안→수락→이행) but no document reference/renewal linking.
- **Missing: Letter rejection reason** — Legacy `repondLetter` prompts for rejection reason (max 50 chars). New `handleRespond` just passes boolean, no reason.
- **Missing: `state_opt` (파기 요청 상태)** — Legacy shows `try_destroy_src`/`try_destroy_dest` status and disables destroy button for the requesting side. New has simpler destroy handling.
- **Missing: "갱신" (renewal) button** — Legacy has `.btnRenew` that auto-fills previous letter content for renewal. Not in new.
- **Missing: Map view** — Legacy `PageGlobalDiplomacy.vue` includes a `MapViewer` component showing the map with nation territories. New doesn't have map.
- **Missing: Conflict zone with city-level detail** — Legacy shows conflict zones by specific city with nation percentages from server data. New approximates with territory bar chart.
- **Missing: `SimpleNationList` panel** — Legacy shows a nation list sidebar alongside the map. Not in new.
- **Diplomacy state mapping difference** — Legacy uses numeric states (0=war, 1=declared, 2=normal, 7=nonaggression). New uses string codes. Mapping may differ (legacy has "선포/▲" vs "교전/★" distinction; new may conflate).

### chief — 🟡 MINOR GAPS

- **Missing: View other officers' turns** — Legacy `PageChiefCenter.vue` shows all 8 officer levels (12,10,8,6,11,9,7,5) with their reserved commands in a grid. New only shows current user's turns, not other officers' plans.
- **Missing: Bottom officer overview panel** — Legacy has `#bottomChiefBox` with a compact view of all officers' turns for quick reference. Not in new.
- **Missing: `targetIsMe` / `viewTarget` switching** — Legacy lets you click on any officer to view their turn details. New is single-officer focused.
- **Missing: `turnTime` display** — Legacy shows the time each turn will execute. Not prominently shown in new.
- **Missing: `maxPushTurn` logic** — Legacy has `maxPushTurn = Math.floor(maxChiefTurn / 2)` for push-forward capability. Not clearly exposed in new.
- **Missing: Troop selection in command args** — Legacy passes `troopList` to `ChiefReservedCommand` for troop-related commands. New has `CommandArgForm` but troop data availability unclear.

---

## Summary

| Page                     | Verdict       |
| ------------------------ | ------------- |
| my-page                  | 🟡 MINOR GAPS |
| general                  | ✅ PARITY     |
| generals + generals/[id] | ✅ PARITY     |
| nation                   | 🟡 MINOR GAPS |
| nations                  | 🟡 MINOR GAPS |
| nation-cities            | 🟡 MINOR GAPS |
| nation-generals          | ✅ PARITY     |
| diplomacy                | 🟡 MINOR GAPS |
| chief                    | 🟡 MINOR GAPS |

**No 🔴 MAJOR GAPS found.** All pages have functional core implementations. Gaps are mostly around advanced UI features (hover popups, rich text editors, map views, multi-officer views) and secondary policy/setting controls.
