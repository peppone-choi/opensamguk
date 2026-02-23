# Frontend Feature Parity Report — Part 2

**Date:** 2026-02-23  
**Scope:** Game feature pages (history, inherit, messages, troop, vote, betting, tournament, npc-control)

## Summary

| Page | Next.js Status | Parity | Gaps |
|------|---------------|--------|------|
| history | ✅ Full impl | 🟢 Good | Minor: legacy has no yearbook (Next.js adds it — improvement) |
| inherit | ✅ Full impl | 🟡 Partial | Missing: unique auction, turn reset, random unique, stat reset, owner check, starting city list from legacy |
| messages | ✅ Full impl | 🟢 Good | Minor differences in mailbox code mapping |
| troop | ✅ Full impl | 🟢 Good | Minor: missing popup hover detail, turn time display, reserved command brief |
| vote | ✅ Full impl | 🟢 Good | Legacy vote is simpler (select-based); Next.js has richer UX with progress bars |
| betting | ✅ Full impl | 🟡 Partial | Legacy uses BettingDetail component with separate betting types; Next.js merged into one view |
| tournament | ✅ Full impl | 🟢 Good | Bracket visualization is an improvement over legacy |
| npc-control | ✅ Full impl | 🟡 Partial | Missing: drag-and-drop priority reorder, nation policy vs general policy split, last setter info |

---

## Detailed Analysis

### 1. History Page (`(game)/history/page.tsx`)

**Legacy:** `v_history.php` + `PageHistory.vue`  
**Next.js:** ~300 lines, fully implemented

**Features present in Next.js:**
- Year/month selector with history API call
- Yearbook summary (nations, territories, key events) — **improvement over legacy**
- Event classification (war/diplomacy/nation/general/city/other) with color-coded badges
- Search filter and category toggle
- Timeline visualization grouped by date

**Gaps:** None significant. Next.js actually adds yearbook functionality not present in legacy Vue.

**Verdict:** 🟢 **Parity achieved (with improvements)**

---

### 2. Inherit Page (`(game)/inherit/page.tsx`)

**Legacy:** `v_inheritPoint.php` + `PageInheritPoint.vue` (~740 lines)  
**Next.js:** ~250 lines

**Features present in Next.js:**
- Point display
- Buff purchase (9 stat/resource buffs with 5 levels)
- War special designation (500P)
- Start city designation (300P)
- Point log

**Missing from legacy:**
- ❌ **Unique item auction** — Legacy has `specificUnique` select + bid amount + auction start. Next.js has no unique auction feature.
- ❌ **Random turn reset** — Legacy has `tryResestTurnTime()` with fibonacci cost increase
- ❌ **Random unique acquisition** — Legacy has `BuyRandomUnique` button
- ❌ **Special war reset** — Legacy has `ResetSpecialWar` with fibonacci cost
- ❌ **Stat reset** — Legacy has detailed stat redistribution (leadership/strength/intel with min/max, plus bonus stats)
- ❌ **Owner check** — Legacy has general owner lookup feature (`checkOwner`)
- ❌ **Inheritance point breakdown** — Legacy shows `previous`, `new`, and individual point sources; Next.js only shows total
- ❌ **Env group** (시작 도시) — Legacy has more detailed starting city mechanics

**Verdict:** 🟡 **Significant gaps** — Many legacy shop items are missing

---

### 3. Messages Page (`(game)/messages/page.tsx`)

**Legacy:** `MessagePanel.vue` + `msg.ts`  
**Next.js:** ~350 lines, fully implemented

**Features present in Next.js:**
- 4-tab mailbox (public/national/private/diplomacy)
- Compose form with recipient type selection
- Diplomacy gating (officer level ≥ 4)
- WebSocket real-time message updates
- Mark as read, delete
- Unread count badges per tab
- General/nation lookup for sender names

**Minor differences:**
- Legacy MessagePanel is embedded in main game view; Next.js is a standalone page
- Legacy uses different message type codes (board/national/personal/diplomacy)
- Next.js maps mailboxType correctly

**Verdict:** 🟢 **Parity achieved**

---

### 4. Troop Page (`(game)/troop/page.tsx`)

**Legacy:** `v_troop.php` + `PageTroop.vue` (~730 lines)  
**Next.js:** ~350 lines, fully implemented

**Features present in Next.js:**
- Troop list with members
- Create/join/exit/disband/kick/rename operations
- Member details (portrait, officer level, crew type, train, atmos)
- Equipment badges (weapon/book/horse/item)
- Troop summary (total crew, avg train, avg atmos)
- My troop highlight

**Missing from legacy:**
- ❌ **Hover popup** — Legacy shows detailed member stats on mouseover (`setPopup`)
- ❌ **Turn time display** — Legacy shows `troop.turnTime` for each troop
- ❌ **Reserved command brief** — Legacy shows upcoming commands (`reservedCommandBrief`)
- ❌ **City display per member** — Legacy shows city names, highlights members in different cities
- ❌ **Officer level permission check** — Legacy requires `myPermission >= 4` for rename

**Verdict:** 🟢 **Core parity achieved** — Missing features are UI polish items

---

### 5. Vote Page (`(game)/vote/page.tsx`)

**Legacy:** `v_vote.php` + `PageVote.vue`  
**Next.js:** ~310 lines, fully implemented

**Features present in Next.js:**
- Active/history tabs
- Create vote form (title + dynamic options)
- Cast vote with option buttons
- Progress bar results with percentages
- My vote indicator
- Winner highlight on closed votes
- Close vote button for chiefs (officer ≥ 5)
- Deadline display
- Reward display

**Improvements over legacy:**
- Visual progress bars
- Better results visualization
- Link to vote detail page

**Verdict:** 🟢 **Parity achieved (with improvements)**

---

### 6. Betting Page (`(game)/betting/page.tsx`)

**Legacy:** `b_betting.php` + `PageNationBetting.vue` + `BettingDetail.vue`  
**Next.js:** ~500 lines, fully implemented

**Features present in Next.js:**
- Tournament type display (전력전/통솔전/일기토/설전)
- 16-candidate betting table with odds/payout
- Bet amount selector (10–1000)
- My bets tab
- Statistics tab (pool, nation-level aggregation)
- Betting rules info card

**Differences from legacy:**
- Legacy uses a list-based betting system with `BettingDetail` component per betting event; supports multiple betting events over time
- Next.js combines betting with tournament bracket data into a single view
- Legacy tracks open/close yearMonth; Next.js uses `isBettingActive` flag
- Legacy has separate `BettingDetail` component with detailed per-event view

**Missing:**
- ❌ **Multiple betting events list** — Legacy lists past/current betting events; Next.js shows only current
- ❌ **Per-event history** — Legacy allows clicking into specific past betting events

**Verdict:** 🟡 **Mostly achieved** — Current betting works well but lacks historical event browsing

---

### 7. Tournament Page (`(game)/tournament/page.tsx`)

**Legacy:** `b_tournament.php` + `c_tournament.php`  
**Next.js:** ~380 lines, fully implemented

**Features present in Next.js:**
- Tournament type display with icons
- State badge (대기/모집중/진행중/종료)
- Registration button
- Full bracket visualization (16→8→4→final) with winner highlighting
- Champion display with portrait
- Participant table sorted by stat
- Tournament rules info card
- Tournament type info grid

**Improvements over legacy:**
- Better bracket visualization (legacy PHP was table-based)
- Champion column with portrait
- Interactive tournament type selector

**Verdict:** 🟢 **Parity achieved (with improvements)**

---

### 8. NPC Control Page (`(game)/npc-control/page.tsx`)

**Legacy:** `v_NPCControl.php` + `PageNPCControl.vue` (~740 lines)  
**Next.js:** ~420 lines, fully implemented

**Features present in Next.js:**
- NPC generals list with stats
- Policy editor (war/military/domestic/diplomacy/resources)
- Priority list with up/down reorder
- General-level policy override
- NPC mode selector (combat/balanced/domestic)

**Missing from legacy:**
- ❌ **Drag-and-drop priority** — Legacy uses `vuedraggable` for drag-and-drop between active/inactive lists; Next.js uses simple up/down buttons
- ❌ **Active/Inactive split** — Legacy has separate "active" and "inactive" columns for priority items; Next.js has a single ordered list
- ❌ **Nation policy section** — Legacy has separate "NPC 국가턴 우선순위" (nation turn priority) distinct from general priority
- ❌ **Last setter info** — Legacy shows who last changed settings and when (`lastSetters.nation.setter`, `lastSetters.general.setter`)
- ❌ **Reset/Rollback buttons** — Legacy has "초깃값으로" (reset to default) and "이전값으로" (revert to previous) per section
- ❌ **Help tooltips** — Legacy has `v-b-tooltip` with `actionHelpText` for each priority item

**Verdict:** 🟡 **Core parity achieved** — Missing UX features (drag-drop, dual-column layout, audit trail)

---

## Priority Recommendations

### High Priority (functionality gaps)
1. **Inherit page** — Add unique auction, turn reset, random unique, stat reset, owner check
2. **NPC Control** — Add nation turn priority (separate from general), active/inactive split, last setter info

### Medium Priority (UX improvements)
3. **Troop page** — Add turn time display, reserved command brief, member city display
4. **Betting page** — Add historical betting event list/browsing
5. **NPC Control** — Implement drag-and-drop for priority reorder, reset/rollback buttons

### Low Priority (polish)
6. **Troop page** — Hover popup for member details
7. **NPC Control** — Help tooltips per priority item
8. **Inherit page** — Detailed point breakdown display
