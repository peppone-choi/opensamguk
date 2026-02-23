# Frontend Parity Report: Nation Management, Chief & Admin Pages

**Generated:** 2026-02-23  
**Scope:** Compare legacy (PHP+Vue) and core2026 (Vue 3) against current Next.js frontend

---

## Summary

The Next.js frontend has **strong parity** with legacy/core2026 for most nation management pages. Key gaps are in polish details rather than missing pages. All target pages exist and are functional.

| Page | Status | Parity | Notes |
|------|--------|--------|-------|
| chief | ✅ Implemented | **95%** | Comprehensive: tabs for command reservation, personnel, generals list |
| nation-generals | ✅ Implemented | **85%** | Missing: crew type, train/atmos columns; no city column |
| nation-cities | ✅ Implemented | **90%** | Has budget calc, diplomacy, policy, sortable table. Missing: officer appointment per-city inline, trust column, region/city-level display, trade %, city filter |
| internal-affairs | ✅ Implemented | **80%** | Has policy/notice/scout tabs. Missing: blockWar/blockScout toggles, rich text editor for notice, finance tab |
| personnel | ✅ Implemented | **75%** | Basic officer table + appointment. Missing: chief hierarchy levels, ambassador/auditor, city officer (2/3/4) appointment |
| spy | ✅ Implemented | **85%** | Full spy report viewer with delete/mark-read. Parsing is heuristic |
| hall-of-fame | ✅ Implemented | **80%** | Has category filter + table. Missing: season/scenario selector (core2026 has this) |
| dynasty | ✅ Implemented | **90%** | Nation cards + comparison table. Good parity |
| emperor | ✅ Implemented | **85%** | Emperor info + nation levels + dynasty logs. Has detail sub-page |
| best-generals | ✅ Implemented | **95%** | Excellent: 5 group tabs, sub-categories, NPC toggle, dex levels. Missing: unique item owners section |
| admin/* | ✅ Implemented | **85%** | Dashboard, diplomacy, game-versions, logs, members, statistics, time-control, users all present |

---

## Detailed Gap Analysis

### 1. Chief Center (`/chief/page.tsx`) — 95% Parity

**What's implemented:**
- 3-tab layout: 사령부 (command center), 인사부 (personnel), 소속장수 (generals)
- Nation command reservation with 12-turn slots, shift/ctrl multi-select
- Immediate execution of nation commands
- Full officer hierarchy display (levels 5-12)
- Officer appointment by level with stat-based candidate filtering (strength/intel ≥ 65)
- City officer appointment (levels 2-4) with region-grouped city selectors
- Ambassador/Auditor appointment (chief only)
- Expulsion functionality
- City officer status table by region

**Gaps vs core2026 `ChiefCenterView.vue`:**
- [ ] Command argument form integration may not cover all nation command arg types
- [ ] No display of `warSettingCnt` (war setting count: remain/inc/max)

### 2. Nation Generals (`/nation-generals/page.tsx`) — 85% Parity

**What's implemented:**
- Table with portrait, name, stats (leadership/strength/intel/politics/charm), crew, NPC badge

**Gaps vs core2026 `NationGeneralsView.vue`:**
- [ ] Missing columns: city assignment, officer level, crew type, training, morale
- [ ] No sorting functionality
- [ ] No search/filter by name
- [ ] No link to general detail

### 3. Nation Cities (`/nation-cities/page.tsx`) — 90% Parity

**What's implemented:**
- Policy editor (rate/bill) with save
- Diplomacy overview table
- Budget calculation per city (gold income, rice income, wall rice, expenses)
- Sortable city table with all dev stats (pop, agri, comm, secu, def, wall, supply)
- Total income/expense summary

**Gaps vs core2026 `NationCitiesView.vue`:**
- [ ] Missing: trust column in city table
- [ ] Missing: region label and city level/size display
- [ ] Missing: trade % (시세) display
- [ ] Missing: per-city officer display (태수/군사/종사) inline
- [ ] Missing: inline officer appointment mode (core2026 has toggle for "관직 임명 모드")
- [ ] Missing: per-city general list
- [ ] Missing: city name filter/search
- [ ] Missing: capital city indicator
- [ ] Missing: income breakdown per-city (gold vs rice vs wall separately shown in core2026)
- [ ] Sort options don't include: trust, trade, region, city level

### 4. Internal Affairs (`/internal-affairs/page.tsx`) — 80% Parity

**What's implemented:**
- 3 tabs: policy, notice, scout message
- Policy: rate, bill, secretLimit, strategicCmdLimit
- Notice: plain textarea with save
- Scout message: plain textarea with save

**Gaps vs core2026 `NationAffairsView.vue` / `NationStratFinanView.vue`:**
- [ ] Missing: `blockWar` and `blockScout` boolean toggles in policy
- [ ] Missing: rich text editor (TipTap) for nation notice — core2026 uses full WYSIWYG with image upload
- [ ] Missing: finance tab with income/expense breakdown, war income calculation
- [ ] Missing: diplomacy overview (shown in core2026 StratFinan view)
- [ ] Missing: year/month status display

### 5. Personnel (`/personnel/page.tsx`) — 75% Parity

**What's implemented:**
- Officer table with portrait, level, city, expel button
- Appointment form: general select, officer level input, optional city

**Gaps vs core2026 `NationPersonnelView.vue`:**
- [ ] Missing: chief hierarchy display (levels 5-12 with proper titles)
- [ ] Missing: separate chief-level appointment (levels 5-12) vs city officer (2-4)
- [ ] Missing: ambassador/auditor assignment
- [ ] Missing: stat-based candidate filtering (strength ≥ min for military, intel ≥ min for advisor)
- [ ] Missing: kick/expel with proper confirmation
- [ ] Note: The `/chief` page already covers most of these features in its "인사부" tab, making this page somewhat redundant

### 6. Spy (`/spy/page.tsx`) — 85% Parity

**What's implemented:**
- Spy report inbox with unread count
- Report display with target city/general, sender info
- Mark as read on click, delete button
- Heuristic parsing of spy/scout result payloads
- Refresh button

**Gaps:**
- [ ] No spy action initiation (sending spies) — this is handled by command system
- [ ] Report parsing is generic; may miss specific legacy payload structures
- [ ] No categorization of spy report types (정찰 vs 첩보 vs 계략)

### 7. Hall of Fame (`/hall-of-fame/page.tsx`) — 80% Parity

**What's implemented:**
- 20 category definitions matching legacy `a_hallOfFame.php`
- Category selector dropdown
- Rankings table with medals, nation badges, values
- Fallback display for unstructured data

**Gaps vs core2026 `HallOfFameView.vue`:**
- [ ] Missing: season selector (core2026 loads options via `getHallOfFameOptions`)
- [ ] Missing: scenario filter within season
- [ ] Missing: server name / scenario name / start/united time display per entry
- [ ] Data loading uses single `rankingApi.hallOfFame()` — may need season/scenario params

### 8. Dynasty (`/dynasty/page.tsx`) — 90% Parity

**What's implemented:**
- Nation cards with ruler portrait, stats grid (generals, cities, power, tech, gold, rice, pop, founded)
- Level progression reference
- Power comparison table
- Sorted by power

**Minor gaps:**
- [ ] No link to nation detail page
- [ ] Missing: capital city name in nation card

### 9. Emperor (`/emperor/page.tsx`) — 85% Parity

**What's implemented:**
- Emperor nation display (level ≥ 7)
- Nation level overview table
- Dynasty chronicle logs
- Detail sub-page with chief general info, key officers list

**Gaps:**
- [ ] Level title mapping is simplified (missing 주목/자사/목 distinctions from dynasty page)
- [ ] No actions (e.g., emperor decrees if applicable)

### 10. Best Generals (`/best-generals/page.tsx`) — 95% Parity

**What's implemented:**
- 5 group tabs: 능력치/전투/숙련도/토너먼트/기타
- Sub-category tabs within each group
- NPC/User toggle
- Stats group shows all 5 stats + total in columns
- Other groups show single value with formatting (percent, dex level names)
- Medal display for top 3, up to 50 entries
- Minimum threshold filtering (10 wars for winrate, 50 matches for tournament)

**Gaps vs core2026 `BestGeneralView.vue`:**
- [ ] Missing: unique item owners section (core2026 shows per-slot unique item holders)
- [ ] Data fetched client-side per category; core2026 fetches all sections at once server-side

### 11. Admin Pages — 85% Parity

All admin sub-pages exist:

| Admin Page | Features |
|-----------|----------|
| `/admin` (dashboard) | World info, notice edit, turn term, lock toggle |
| `/admin/diplomacy` | Diplomacy list with state labels |
| `/admin/game-versions` | Game instance management (start/stop/delete), version input |
| `/admin/logs` | General log search by ID |
| `/admin/members` | General list with search |
| `/admin/statistics` | Nation statistics table |
| `/admin/time-control` | Year/month edit, lock toggle |
| `/admin/users` | User list with grade management, admin toggle |

**Gaps:**
- [ ] No admin world creation/deletion page
- [ ] No admin scenario management
- [ ] No admin nation merge/split tools
- [ ] No bulk operation support in members/users
- [ ] No admin event/announcement broadcast tool

---

## Priority Recommendations

### High Priority (functional gaps)
1. **nation-cities**: Add per-city officer display + inline appointment mode (core2026 parity)
2. **internal-affairs**: Add `blockWar`/`blockScout` toggles, finance tab
3. **hall-of-fame**: Add season/scenario selectors
4. **best-generals**: Add unique item owners section

### Medium Priority (UX improvements)
5. **nation-generals**: Add city, officer level, crew type, train/atmos columns + sorting
6. **nation-cities**: Add trust, trade%, region, capital indicator, city filter
7. **internal-affairs**: Rich text editor for nation notice (TipTap or similar)
8. **personnel**: Consider deprecating in favor of `/chief` personnel tab, or bring to parity

### Low Priority (polish)
9. **dynasty**: Add capital city name, link to detail
10. **emperor**: Normalize level title mapping
11. **admin**: Bulk operations, world creation
