# Frontend Game Info Pages — Parity Report

**Date:** 2026-02-23  
**Scope:** Compare legacy PHP+Vue pages against current Next.js implementations for game main + info browse pages.

---

## Summary

| Page | Next.js File | Legacy File(s) | Parity | Issues |
|------|-------------|----------------|--------|--------|
| Main Dashboard | `(game)/page.tsx` (501L) | `PageFront.vue`, `v_front.ts` | ✅ Good | Minor: see below |
| My General | `(game)/general/page.tsx` (286L) | `b_myGenInfo.php` | ⚠️ Partial | Missing detailed nation-general list; this is really "my general info" not "세력장수" |
| Generals List | `(game)/generals/page.tsx` (392L) | `b_genList.php`, `a_genList.php` | ✅ Good | Minor column differences |
| City Info | `(game)/city/page.tsx` (735L) | `b_currentCity.php`, `b_myCityInfo.php` | ✅ Good | Comprehensive |
| Nation Info | `(game)/nation/page.tsx` (1138L) | `b_myKingdomInfo.php` | ✅ Good | Very thorough with tabs |
| Nations List | `(game)/nations/page.tsx` (268L) | `a_kingdomList.php` | ⚠️ Partial | Missing per-nation general/city drilldown |
| Superior Info | `(game)/superior/page.tsx` (164L) | `b_myBossInfo.php`, `bossInfo.ts` | ⚠️ Partial | Missing personnel management (인사부) features |
| My Page | `(game)/my-page/page.tsx` (903L) | `b_myPage.php`, `myPage.ts` | ✅ Good | Comprehensive |
| NPC List | `(game)/npc-list/page.tsx` (199L) | `a_npcList.php` | ⚠️ Partial | Missing columns |
| Traffic | `(game)/traffic/page.tsx` (227L) | `a_traffic.php` | 🔴 Major | Completely different approach |

---

## Detailed Findings

### 1. Main Dashboard — `(game)/page.tsx`
**Legacy:** `PageFront.vue` + `v_front.ts`  
**Parity: ✅ Good**

The Next.js version is comprehensive with:
- Game info header bar (scenario, year/month, online count, turn info)
- Online nations bar
- Nation notice
- Mobile tab navigation (map/commands/status/world/messages)
- Map viewer, command panel, general/city/nation basic cards
- Message panel, game bottom bar

**Minor gaps:**
- Legacy has fine-grained game config display (확장NPC, 상성, 가상/사실 etc.) — Next.js replicates this but some labels differ slightly
- WebSocket subscriptions for turn and message updates are implemented ✅

### 2. My General — `(game)/general/page.tsx`
**Legacy:** `b_myGenInfo.php` (세력장수 — nation generals list with sorting)  
**Parity: ⚠️ Naming mismatch**

**Issue:** The legacy `b_myGenInfo.php` is actually "세력장수" (nation's generals list with detailed sorting by 15 criteria). The Next.js `general/page.tsx` is "나의 장수" (my general info display).

These serve different purposes:
- Next.js `general/page.tsx` shows: portrait, stats, equipment, current command, biography — this is a personal info view
- Legacy `b_myGenInfo.php` shows: all generals in my nation, sorted by various criteria (통솔/무력/지력/금/쌀/병력/훈련/사기/명성/계급/etc.)

**The nation generals list functionality exists at:** `(game)/nation/page.tsx` under the "generals" tab, which partially covers this. However, the legacy version has 15 sort options and shows owner names after unification.

**Missing from general/page.tsx:**
- No injury percentage display
- No special/special2 display  
- No personality display
- No dexterity (숙련도) display
- No battle stats

These are covered in `my-page/page.tsx` instead, which is fine architecturally but differs from legacy's single-page approach.

### 3. Generals List — `(game)/generals/page.tsx`
**Legacy:** `b_genList.php` (장수 목록), `a_genList.php` (전체 장수일람)  
**Parity: ✅ Good**

Next.js has:
- Search filter, nation filter, NPC filter ✅
- Sortable columns (name, nation, city, officerLevel, stats, crew, etc.) ✅
- Extended columns toggle (crewType, train, atmos, gold, rice, dedication) ✅
- Spy access for viewing troop info ✅

**Minor differences:**
- Legacy `a_genList.php` has additional sort options: 종능(total stats), 명성, 계급, 연령, 도시, NPC
- Legacy shows 특기(special) and 성격(personal) columns — Next.js omits these in the table view
- Legacy shows age column — Next.js omits

### 4. City Info — `(game)/city/page.tsx`
**Legacy:** `b_currentCity.php` (도시정보), `b_myCityInfo.php`  
**Parity: ✅ Good**

Next.js has:
- City selector/filter ✅
- City stats display (pop, trust, agri, comm, secu, def, wall, trade) ✅
- SammoBar progress bars ✅
- Sort options (12 criteria) ✅
- Generals in city display ✅
- Adjacency map ✅
- Spy visibility logic ✅
- WebSocket turn updates ✅

**Minor differences:**
- Legacy uses dropdown city selector with `select2` widget; Next.js uses a different filter approach
- Legacy shows general turn commands inline; Next.js shows in expandable city sections

### 5. Nation Info — `(game)/nation/page.tsx`
**Legacy:** `b_myKingdomInfo.php` (세력정보)  
**Parity: ✅ Good**

Next.js has comprehensive tabs:
- Info tab: nation stats, income/expense calculations, city list, diplomacy ✅
- Generals tab: sorted general list with 15 sort options ✅  
- Cities tab: sorted city list with 12 sort options ✅
- Admin tab: notice editing, tax rate, bill settings ✅

Income calculation functions (`calcCityGoldIncome`, `calcCityRiceIncome`, `calcCityWallRiceIncome`) replicate legacy logic ✅

**Minor differences:**
- Legacy shows population bars with color coding; Next.js uses SammoBar
- Legacy has spy info display; Next.js handles this via nation state

### 6. Nations List — `(game)/nations/page.tsx`
**Legacy:** `a_kingdomList.php` (세력일람)  
**Parity: ⚠️ Partial**

Next.js has:
- Sortable table (name, capital, level, power, generals, cities, gold, rice, tech, type) ✅
- Chief/advisor display ✅
- Power progress bar ✅

**Missing features:**
- Legacy shows **per-nation expandable sections** with full general lists (name, officer level, city, penalties, permission) and city lists — Next.js only shows counts
- Legacy shows 재야(ronin) generals and cities as a separate section
- Legacy shows penalty info and permission info per general
- Legacy integrates `killturn` and `autorun_user` config display
- Legacy links to each nation's detail page

### 7. Superior Info — `(game)/superior/page.tsx`
**Legacy:** `b_myBossInfo.php` (인사부), `bossInfo.ts`  
**Parity: ⚠️ Major scope difference**

**Critical issue:** The legacy `b_myBossInfo.php` is actually the **인사부 (Personnel Department)** — a full personnel management page for officers. The Next.js version only shows "상관 정보" (superior info).

**Legacy features NOT in Next.js:**
- Officer appointment (관직 임명): assign officers to positions
- Ambassador/Auditor permission management
- General expulsion (추방) with stat check
- Officer candidate lists filtered by stat minimums (`chiefStatMin`)
- Chief settings management
- Select2 dropdown for officer assignment

**Next.js only has:**
- Direct superior display
- Command chain list (all officers sorted by rank)

This is the **largest parity gap** — the personnel management functionality needs to be either added to this page or exists elsewhere.

### 8. My Page — `(game)/my-page/page.tsx`
**Legacy:** `b_myPage.php`, `myPage.ts`  
**Parity: ✅ Good**

Next.js has comprehensive tabs:
- 장수 정보: portrait, stats, bars, resources, equipment, proficiency ✅
- 전투 통계: war/kill/death stats, win/kill rates ✅
- 설정: defence training, tournament, potion threshold, vacation ✅
- 기록: personal records, battle records, biography ✅

**Missing features:**
- Legacy has `myset` counter (설정 저장 횟수 제한) — Next.js doesn't show remaining saves
- Legacy has `use_auto_nation_turn` setting (자동 사령턴 허용) — missing
- Legacy has custom CSS textarea — missing
- Legacy has 500px/1000px mobile screen mode toggle — missing (N/A for responsive Next.js)
- Legacy has 빙의 해체 요청 (NPC detach request) — missing
- Legacy has 가오픈 장수 삭제 (pre-open character deletion) — missing
- Legacy has 사전 거병 (pre-open nation building) — missing
- Legacy has 접경 귀환 (instant retreat) button — missing
- Legacy has 다른 장수 선택 (select different general from pool, npcmode=2) — missing
- Legacy has separate 전투 결과 (battle result) log section — Next.js merges into battle records
- Legacy potion thresholds use values 10/21/41/61/100; Next.js uses 20/40/60/80/999 — **value mismatch**
- Legacy defence train 999 shows dynamic penalties from `onCalcDomestic`; Next.js hardcodes description

### 9. NPC List — `(game)/npc-list/page.tsx`
**Legacy:** `a_npcList.php` (빙의일람)  
**Parity: ⚠️ Partial**

Next.js has:
- NPC general list with search and nation filter ✅
- Sortable columns (name, nation, stats, crew) ✅

**Missing columns from legacy:**
- 악령 이름 (owner_name) — the player controlling the NPC
- 레벨 (explevel)
- 성격 (personal)
- 특기/특기2 (special/special2) with display formatting
- 종능 (sum of leadership+strength+intel)
- 명성 (experience)
- 계급 (dedication)

**Missing sort options:**
- Legacy has 8 sort options: 이름/국가/종능/통솔/무력/지력/명성/계급
- Next.js has: name/nation/leadership/strength/intel/politics/charm/crew

**Missing feature:**
- Legacy also includes generals from `select_pool` (npc=0 but in selection pool) — Next.js only filters `npcState > 0`

### 10. Traffic — `(game)/traffic/page.tsx`
**Legacy:** `a_traffic.php` (트래픽정보)  
**Parity: 🔴 Completely different concept**

**Legacy shows:**
- 접속량 (Refresh count) — bar chart over recent turns with color gradient (red→blue)
- 접속자 (Online user count) — bar chart over recent turns
- Max records for both metrics
- 주의대상자 (Excessive refreshers) — top 5 users by refresh count with bars

**Next.js shows:**
- 이동 현황 (Moving generals) — generals currently in transit between cities
- Shows departure/destination, ETA, remaining time

These are **completely different features**. The Next.js "traffic" page is about troop movement, while the legacy page is about server traffic/monitoring.

**Recommendation:** Either rename the Next.js page or create a separate server-traffic page matching legacy behavior.

---

## Priority Fixes

### P0 — Critical Parity Gaps
1. **Traffic page** — completely wrong concept. Legacy = server traffic stats, Next.js = troop movement
2. **Superior page** — missing entire personnel management (인사부) functionality
3. **My Page potion values** — threshold values don't match legacy (10/21/41/61/100 vs 20/40/60/80/999)

### P1 — Significant Missing Features  
4. **NPC List** — missing owner_name, level, personality, special, 종능, 명성, 계급 columns
5. **Nations List** — missing per-nation general/city expandable drilldown
6. **My Page** — missing `use_auto_nation_turn`, `myset` counter, pre-open actions, instant retreat, general pool selection

### P2 — Minor Gaps
7. **General page** — could show special/personality/injury info  
8. **Generals List** — missing 종능, 특기, 성격, 연령 columns
9. **My Page** — missing battle result (전투 결과) as separate log tab; missing custom CSS editor
10. **Nations List** — missing 재야 section

---

## Architecture Notes

- The Next.js app sensibly splits my-general-info and nation-generals into separate pages, while legacy combines some of these
- WebSocket subscriptions for real-time updates are well-implemented across dashboard, general, city pages
- The component library (SammoBar, GeneralPortrait, NationBadge, etc.) is consistent and well-structured
- Game utility functions (formatInjury, formatOfficerLevelText, etc.) provide good legacy parity for formatting
