# Frontend Features Parity Report (Part 1)

**Date:** 2026-02-23  
**Scope:** Game feature pages — Commands, Processing, Map, Auction, Battle Center, Battle Simulator, Board, Diplomacy

---

## Summary

| Page | Parity | Key Gaps |
|------|--------|----------|
| Commands | 🟡 70% | Missing: server clock, nation commands, advanced edit features |
| Processing | 🟢 85% | Missing: nation command routing to chief center |
| Map | 🟡 60% | Missing: history log panel, MapViewer image-based rendering, city detail info formatter |
| Auction | 🟡 65% | Missing: dedicated unique item auction (uses generic "item" tab), proper AuctionResource/AuctionUniqueItem separation |
| Battle Center | 🔴 40% | Missing: per-general detail view (info card, battle detail, battle result, personal history, general history), general navigation/sorting |
| Battle Simulator | 🟡 55% | Missing: item/horse/book/special dropdowns with actual game data, inherit buffs, dex values, injury, NPC color, repeat-1000 stat summary, download results |
| Board | 🟢 80% | Missing: article title field, proper article structure (title+body), nation-based secret board |
| Diplomacy | 🟢 85% | Missing: conflict/분쟁 area display, informative vs neutral state char distinction in matrix |

---

## Detailed Analysis

### 1. Commands Page (`(game)/commands/page.tsx`)

**Current:** Delegates to `<CommandPanel>` component (643 lines) which implements 12-turn reservation grid, multi-select, clipboard ops (cut/copy/paste), stored actions, recent actions, repeat, erase-and-pull, push-empty.

**Legacy (`PartialReservedCommand.vue`, 995 lines):**
- ✅ 12-turn command grid with selection
- ✅ Edit mode toggle (일반/고급)
- ✅ Clipboard operations (cut, copy, paste, text copy)
- ✅ Stored actions (보관함) with save/delete
- ✅ Recent actions history
- ✅ Repeat command (반복하기)
- ✅ Erase and pull / push empty (지우고 당기기 / 뒤로 밀기)
- ✅ Range selection (홀수턴/짝수턴/N턴 간격)

**Gaps:**
- ❌ **Server clock display** (`SimpleClock` component with `serverNow` — legacy shows real-time server time in command header)
- ❌ **Nation command mode** — legacy supports both general and nation (chief) command reservation via `isChiefTurn` flag; current only handles general commands
- ❌ **`maxPushTurn` repeat dropdown** — legacy has a dropdown for repeating N turns; current implementation unclear

### 2. Processing Page (`(game)/processing/page.tsx`)

**Current:** Dual-mode page: (1) command argument form when `?command=X&turnList=1,2,3`, (2) turn processing wait screen with WS + 30s timeout.

**Legacy (`v_processing.ts`, `processing/*.vue`):**
- ✅ Vue component per command type (che_건국, che_징병, che_장비매매, etc.)
- ✅ Command routing via `entryInfo` → `commandMap` dispatch
- ✅ `StoredActionsHelper` for recent action tracking
- ✅ Submit → redirect to home (general) or chief center (nation)

**Gaps:**
- ❌ **Nation command redirect** — legacy redirects to `v_chiefCenter.php` for nation commands; current always goes to `/commands`
- ⚠️ **Per-command specialized forms** — legacy has 13+ Vue components for specific command types (건국, 장비매매, 등용, etc.); current uses generic `CommandArgForm`. Need to verify `CommandArgForm` covers all arg types.

### 3. Map Page (`(game)/map/page.tsx`)

**Current:** SVG-based map with computed scaling, city circles, connection lines, tooltip on click, nation legend.

**Legacy (`PageCachedMap.vue` + `MapViewer` component):**
- ✅ Map display with city positions and nation colors
- ✅ Connection lines between cities
- ✅ Nation color legend

**Gaps:**
- ❌ **History log panel** — legacy `cachedMap.history` rendered below map with `formatLog()`; current has no history section
- ❌ **Image-based rendering** — legacy `MapViewer` uses `imagePath` for game images; current is pure SVG circles
- ❌ **City info formatter** — legacy has `formatCityInfo` callback providing rich city data; current tooltip only shows name/nation/level/pop
- ❌ **Detail map mode** (`isDetailMap` prop) — legacy supports a detail mode with richer info
- ❌ **Server name header** — legacy shows `serverName + 현황` as card header

### 4. Auction Page (`(game)/auction/page.tsx`)

**Current:** Tab-based (자원/아이템), full CRUD with create form, bid form, active/completed sections, my-auction summary, 5s polling.

**Legacy (`PageAuction.vue` → `AuctionResource.vue` + `AuctionUniqueItem.vue`):**
- ✅ Resource auction (금/쌀)
- ✅ Unique item auction
- ✅ Tab switching between resource and unique

**Gaps:**
- ❌ **Unique item auction specifics** — legacy has dedicated `AuctionUniqueItem` component; current treats items generically. Legacy unique auctions likely have special item display (game items with stats from game const store)
- ❌ **Buy/Sell rice distinction** — legacy API has separate `OpenBuyRiceAuction`/`OpenSellRiceAuction`/`BidBuyRiceAuction`/`BidSellRiceAuction`; current has single create/bid flow
- ⚠️ **Reload mechanism** — legacy has explicit reload button in TopBackBar; current uses 5s polling (adequate)

### 5. Battle Center Page (`(game)/battle-center/page.tsx`)

**Current:** Aggregate stats table (warnum/killnum/deathnum/killcrew/deathcrew) + recent battle log from history API. Search by general name.

**Legacy (`PageBattleCenter.vue`, 291 lines + `v_battleCenter.ts`):**
- ✅ General list with sorting/navigation
- ✅ Per-general detailed view with:
  - `GeneralBasicCard` — full general info (stats, items, crew type, etc.)
  - `GeneralSupplementCard` — supplementary info
  - Battle detail log section (전투 기록)
  - Battle result log section (전투 결과)
  - General history section (장수 열전)
  - Personal action log section (개인 기록)
- ✅ Sort by multiple criteria with `textMap` ordering
- ✅ Prev/Next general navigation buttons
- ✅ NPC color distinction
- ✅ Officer level marker (`*name*` for level > 4)
- ✅ Last executed time display

**Gaps (MAJOR):**
- ❌ **Per-general detail view** — current only shows aggregate table; legacy is a per-general inspection tool with full character sheet + 4 log sections
- ❌ **General navigation** (prev/next buttons, dropdown selector)
- ❌ **Sorting by multiple criteria** (전투수/살상/피살/승리/패배 etc.)
- ❌ **GeneralBasicCard / GeneralSupplementCard** equivalent components
- ❌ **Per-general log sections** (battle detail, battle result, general history, personal action)
- ❌ **NPC color coding** and **officer level markers**

### 6. Battle Simulator Page (`(game)/battle-simulator/page.tsx`)

**Current:** Two unit builders (attacker/defender) with stats + city defense. Single/1000 repeat toggle. API call for simulation.

**Legacy (`battle_simulator.ts`, 1108 lines):**
- ✅ Attacker/defender unit configuration
- ✅ City defense settings
- ✅ Basic stats (leadership, strength, intel, crew, crewType, train, atmos)
- ✅ Year/month/seed/repeat settings

**Gaps:**
- ❌ **Item/Horse/Book/Special dropdowns with actual game data** — legacy loads real item lists from server (`BasicGeneralListResponse`); current has empty string text inputs
- ❌ **Inherit buffs** — legacy has `InheritBuff` type (warAvoidRatio, warCriticalRatio, warMagicTrialProb + oppose variants)
- ❌ **Dex values** (dex1-dex5 숙련도) — legacy includes these; current omits
- ❌ **Injury** field — legacy has `injury` parameter
- ❌ **Download results** — legacy uses `downloadjs` for result export
- ❌ **Detailed 1000-repeat summary** — legacy likely shows win rate distribution; current shows single result format
- ❌ **General picker** — legacy allows selecting existing generals from server to auto-fill stats
- ❌ **NPC color** display for selected generals
- ❌ **Defence train** (수비 훈련도)

### 7. Board Page (`(game)/board/page.tsx`)

**Current:** Public/Secret tabs, compose form (textarea only), expandable post list with comments, auto-refresh 10s, delete, pagination.

**Legacy (`PageBoard.vue`, 171 lines + `BoardArticle` component):**
- ✅ New article form
- ✅ Article list with comments
- ✅ Comment submission

**Gaps:**
- ❌ **Article title field** — legacy has separate title + body fields (`newArticle.title`, `newArticle.text`); current has only textarea body
- ❌ **Article structure** — legacy `BoardArticleItem` has `title`, `text`, `author`, `author_icon`, `nation_no`, `is_secret`, `date`; current uses generic `Message` type
- ⚠️ **Secret board** — current implements via tab with `officerLevel >= 2` check; legacy uses `is_secret` flag per article. Implementation approaches differ but functionally similar.
- ⚠️ **Auto-resize textarea** — legacy has `autoResizeTextarea` utility; current uses fixed height

### 8. Diplomacy Page (`(game)/diplomacy/page.tsx`)

**Current:** 3 tabs (외교부/중원정보/외교기록). Letters with send/respond/rollback/destroy. NxN matrix. Nation power table. History filtered by diplomacy keywords. WebSocket refresh.

**Legacy (`PageGlobalDiplomacy.vue`, 299 lines + `v_globalDiplomacy.ts`):**
- ✅ NxN diplomacy matrix table
- ✅ Nation coloring in matrix
- ✅ State symbols (불가침 @, 통상 ㆍ, 선포 ▲, 교전 ★)

**Gaps:**
- ❌ **Conflict/분쟁 area** — legacy shows contested cities with per-nation control percentages + progress bars; current has no conflict display
- ❌ **Informative vs neutral state char maps** — legacy has two char maps: `infomativeStateCharMap` (shown for own nation's relations, more detail) vs `neutralStateCharMap` (for others); current uses single label
- ⚠️ **Nation power table** — current includes this but legacy may compute differently; verify `power` field source

---

## Priority Recommendations

### Critical (P0) — Feature completely missing or fundamentally different
1. **Battle Center** — Needs complete redesign to match legacy per-general inspection tool
2. **Battle Simulator** — Add item/horse/book pickers, dex values, inherit buffs, general picker
3. **Map** — Add history log panel below map

### High (P1) — Significant feature gap
4. **Commands** — Add server clock display, nation command support
5. **Auction** — Implement proper unique item auction with game item data
6. **Board** — Add title field to compose form
7. **Diplomacy** — Add conflict/분쟁 area display

### Medium (P2) — Enhancement to reach full parity
8. **Processing** — Nation command redirect to chief center
9. **Map** — Image-based city rendering, detail map mode
10. **Battle Simulator** — Download results, 1000-repeat stats summary
11. **Diplomacy** — Two-tier state char distinction in matrix
