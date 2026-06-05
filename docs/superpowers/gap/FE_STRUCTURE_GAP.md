# FE_STRUCTURE_GAP — Frontend page+component structure parity (PHP/Vue → Next.js)

Audit date: 2026-06-05 · Branch: f4-c3-chief

**Scope.** Maps the PHP/Vue **frontend page + component structure** (legacy `hwe/*.php` entry
points + `hwe/ts/` Vue) to the Next.js structure (`web/game/app/**/page.tsx`,
`web/gateway/app/`, and their components). This is the **structure** dimension. Per-command
mutation parity (the 93-command port/intake/FE-submit story) is owned by
`docs/superpowers/PARITY_LEDGER.md` and is referenced here, **not re-audited**.

**Grand truth:** `hwe/ts/` Vue = frontend grand truth; `hwe/*.php` = dist-mount shells +
JSON endpoints; PHP wins every divergence.

**Status legend.**
- **PRESENT** — a Next route/component exists with full feature parity (read AND the mutation
  surface PHP has).
- **PARTIAL** — a Next route exists but is **read-only / placeholder** where PHP is
  interactive, OR a sub-feature (TipTap editor, drag-reorder, advanced reserved-command grid)
  is missing.
- **MISSING** — no Next route/component at all.

---

## 1. PHP/Vue page inventory

### 1.1 Main-game pages (Vue `Page*.vue`, mounted by `v_*.php` shells)

The PHP `v_*.php` files are thin shells that mount a single `Page*.vue` component. Each is a
full **read + interact** surface in the original (command reservation, setters, drag-reorder,
rich-text editors).

| PHP shell | Vue page | Surface |
| --- | --- | --- |
| `v_processing.php` | (legacy `main` + `PartialReservedCommand.vue` + `processing/*`) | reserved-command ring + 17 `che_*.vue` / `cr_*.vue` command arg forms |
| `v_chiefCenter.php` | `PageChiefCenter.vue` (+ `ChiefCenter/TopItem,BottomItem`) | 사령부 — 12 chief reserved commands, edit grid |
| `v_troop.php` | `PageTroop.vue` | 부대 편성 — create/merge/disband/rename troops |
| `v_nationStratFinan.php` | `PageNationStratFinan.vue` | 내무부 — budget tables + tax/rate/secret setters + nation-msg TipTap |
| `t_diplomacy.php` | (diplomacy view) | 외교부 — letters send/respond/destroy/rollback |
| `v_globalDiplomacy.php` | `PageGlobalDiplomacy.vue` | 중원 정보 — global diplomacy map |
| `v_NPCControl.php` | `PageNPCControl.vue` | NPC 정책 — drag-reorder priority lists + setters |
| `v_auction.php` | `PageAuction.vue` (+ `AuctionResource`, `AuctionUniqueItem`) | 경매장 — bid/buy金쌀 + unique |
| `v_nationBetting.php` | `PageNationBetting.vue` (+ `BettingDetail`) | 베팅장 — place bet |
| `b_betting.php` | (betting) | 천통국 베팅 |
| `v_board.php` | `PageBoard.vue` (+ `BoardArticle`, `BoardComment`) | 회의실/기밀실 — article+comment add |
| `v_vote.php` | `PageVote.vue` | 설문조사 — open/cast/comment/close |
| `v_inheritPoint.php` | `PageInheritPoint.vue` | 유산 관리 — buy buff / reset / get-more |
| `v_nationGeneral.php` | `PageNationGeneral.vue` | 세력 장수 |
| `v_battleCenter.php` | `PageBattleCenter.vue` | 감찰부 — battle log center |
| `v_history.php` | `PageHistory.vue` | 연감 |
| `v_cachedMap.php` / `recent_map.php` | `PageCachedMap.vue` | 지도 |
| `battle_simulator.php` | `battle_simulator.ts` | 전투 시뮬레이터 |
| `select_general_from_pool.php` | `select_general_from_pool.ts` | 장수 선택(풀) |
| `select_npc.php` | `select_npc.ts` | 빙의 선택 |
| `v_join.php` / `PageJoin.vue` | `PageJoin.vue` | 입국/가입 |
| `c_tournament.php` | (tournament admin) | 토너먼트 관리 |

### 1.2 Info / ranking pages (`a_*.php`, `b_*.php`) — global-menu + control-bar targets

| PHP | Purpose |
| --- | --- |
| `a_kingdomList.php` (세력일람) · `a_genList.php` (장수일람) · `a_bestGeneral.php` (명장일람) · `a_hallOfFame.php` (명예의전당) · `a_emperior.php`+`a_emperior_detail.php` (왕조일람) · `a_npcList.php` (빙의일람) · `a_traffic.php` (접속량) | global-menu ranking/info pages |
| `b_myKingdomInfo.php` (세력 정보) · `b_myCityInfo.php` (세력 도시) · `b_currentCity.php` (현재 도시) · `b_myBossInfo.php`+`j_myBossInfo.php` (인사부) · `b_genList.php` (암행부) · `b_myPage.php` (내 정보&설정) · `b_myGenInfo.php` · `b_tournament.php` · `b_betting.php` | control-bar info pages |

### 1.3 Navigation structure (the two bars — VERBATIM contract)

- **`components/MainControlBar.vue`** — the 20-button nation control bar (회의실 … 베팅장),
  each gated by `myLevel`/`permission`/`showSecret`/`nationLevel`, with the 경매장 split
  dropdown (금/쌀 · 유니크). **105 lines.**
- **`components/GlobalMenu.vue`** + `sammo/GlobalMenu.php` — the global menu: 천통국 베팅 ·
  게임정보(multi: 세력일람/장수일람/명장일람/명예의전당/왕조일람) · 연감 · 게시판(split) ·
  공식 오픈톡(split) · 전투 시뮬레이터 · 기타 정보(multi: 접속량/빙의일람) · 설문조사.
  Filtered by `condShowVar`/`condHighlightVar`. **180 lines.**
- **`components/GameInfo.vue`** — game-info header (turn/date/nation). **153 lines.**
- Supporting nav: `GlobalMenuDropdown.vue`, `MainControlDropdown.vue`, `BottomBar.vue`,
  `GameBottomBar.vue`, `SammoBar.vue`, `TopBackBar.vue`, `SimpleClock.vue`.

### 1.4 Gateway / lobby (`hwe/ts/gateway/*`)

`entrance.ts` (front/입구) · `login.ts` · `join.ts` · `user_info.ts` (내 정보) ·
`admin_member.ts` · `admin_server.ts` · `install.ts` · `common.ts`.

---

## 2. Next.js inventory

### 2.1 `web/game/app/**/page.tsx` (35 routes)

`game/` (main) · `chief-center` · `troop` · `diplomacy` · `global-diplomacy` ·
`npc-control` · `auction` · `betting` · `board` · `vote` · `inherit` · `nation`
(세력 장수 alt) · `generals` · `my-nation` · `my-cities` · `my-boss` · `my-generals` ·
`nation-finance` · `city` · `history` · `mailbox` · `simulator` · `tournament` ·
`tournament-admin` · `coming-soon` (stub) · `rankings/` (+ `best-generals` · `emperor[/id]` ·
`generals` · `hall-of-fame` · `kingdoms` · `npcs` · `traffic`).

**Components** (`web/game/components/`): `GameChrome` · `GameInfo` · `GlobalMenu` ·
`GlobalMenuDropdown` · `MainControlBar` · `MainControlDropdown` · `PartialReservedCommand` ·
`CommandModal` · `command/{SearchableSelect,SelectAmount/City/General/Nation Field}` ·
`MapViewer` · `MapCityDetail` · `MessagePanel` · `MessagePlate` · `GeneralBasicCard` ·
`NationBasicCard` · `CharacterClaim` · `Shell/Header/Sidebar/BottomNav/GameCard/GameTable/…`.
Nav config: `lib/control-bar-config.ts` (20-button verbatim) + `lib/global-menu-fixture.ts`.

### 2.2 `web/gateway/app/` (5 routes)

`/` (entrance) · `/login` · `/join` · `/lobby` · `/admin`.

---

## 3. GAP TABLE

### 3.1 Navigation (the spine)

| PHP/Vue | Next | Status | Notes |
| --- | --- | --- | --- |
| MainControlBar.vue (20-btn) | `MainControlBar.tsx` + `control-bar-config.ts` | **PRESENT** | 20 buttons verbatim, gating buckets, 경매장 split. 4 targets route to `/game/coming-soon` stub (감찰부) or read-only pages. |
| GlobalMenu.vue + GlobalMenu.php | `GlobalMenu.tsx` + `global-menu-fixture.ts` | **PARTIAL** | Fixture-backed (not yet API-driven `GetGlobalMenu`); labels/targets verbatim. Some targets external/.php. |
| GameInfo.vue | `GameInfo.tsx` | **PRESENT** | header. |
| BottomBar/GameBottomBar/SammoBar/TopBackBar | (folded into Shell/Header/BottomNav) | **PARTIAL** | re-composed, not 1:1. |

### 3.2 Main-game interactive pages

| PHP/Vue page | Next route | Status | Notes |
| --- | --- | --- | --- |
| 명령 ring (`PartialReservedCommand.vue`, advanced edit grid) | `PartialReservedCommand.tsx` | **PARTIAL** | **Scaffold only** — game-api has NO reserved-command READ endpoint; renders 휴식 defaults + per-slot edit→CommandModal. Missing: advanced mode (범위/보관함/반복 batch), 30-slot ring data. |
| `processing/*` 17 `che_*.vue`/`cr_*.vue` arg forms | `CommandModal.tsx` + `command/*Field` | **PARTIAL** | Generic modal replaces per-command Vue forms. Drives only commands the ledger marks FE-wired (47 DONE); 20 FE_MISSING + 24 PORT_MISSING not submittable. |
| 사령부 `PageChiefCenter.vue` | `chief-center/page.tsx` | **PARTIAL** | **READ-ONLY** — chief reserved grid displayed; '명령' edit UI DEFERRED (no CommandModal). PHP = full 12-command edit. |
| 부대 편성 `PageTroop.vue` | `troop/page.tsx` | **PRESENT** | READ + MUTATION (create/merge/disband/rename via CommandModal). |
| 내무부 `PageNationStratFinan.vue` | `nation-finance/page.tsx` | **PARTIAL** | Finance setters wired via CommandModal, BUT nation-msg/scout-msg **TipTap editor DEFERRED** (plaintext). |
| 외교부 `t_diplomacy.php` | `diplomacy/page.tsx` | **PARTIAL** | 제의(종전/불가침/파기/선전포고) via CommandModal; letter send/respond/destroy/**rollback** richness reduced. |
| 중원 정보 `PageGlobalDiplomacy.vue` | `global-diplomacy/page.tsx` | **PARTIAL** | read render. |
| NPC 정책 `PageNPCControl.vue` | `npc-control/page.tsx` | **PARTIAL** | **READ-ONLY** — drag-reorder priority lists + 설정/초깃값/이전값 setters DEFERRED. |
| 경매장 `PageAuction.vue` | `auction/page.tsx` | **PRESENT** | READ + bid/buy via CommandModal (金쌀 + unique). |
| 베팅장 `PageNationBetting.vue` | `betting/page.tsx` | **PRESENT** | READ + place-bet via CommandModal. |
| 회의실/기밀실 `PageBoard.vue` | `board/page.tsx` | **PRESENT** | READ + article/comment add (CommandModal). |
| 설문조사 `PageVote.vue` | `vote/page.tsx` | **PRESENT** | READ + open/cast/comment/close (CommandModal). |
| 유산 관리 `PageInheritPoint.vue` | `inherit/page.tsx` | **PARTIAL** | **READ-ONLY display** — buy-buff/reset/get-more store actions DEFERRED. |
| 세력 장수 `PageNationGeneral.vue` | `generals/page.tsx` + `nation/page.tsx` | **PARTIAL** | **READ-ONLY** list; no 발령/permission actions. |
| 감찰부 `PageBattleCenter.vue` | `coming-soon` stub | **MISSING** | control-bar id 16 routes to `/game/coming-soon?feature=감찰부`. No battle-log center page. |
| 연감 `PageHistory.vue` | `history/page.tsx` | **PARTIAL** | read render. |
| 지도 `PageCachedMap.vue` | (`MapViewer.tsx` in chrome) | **PARTIAL** | map viewer embedded; no dedicated cached-map page. |
| 전투 시뮬레이터 `battle_simulator.ts` | `simulator/page.tsx` | **PRESENT** | interactive sim (POST /simulate). |
| 토너먼트 `b_tournament.php` | `tournament/page.tsx` | **PRESENT** | READ + apply via CommandModal. |
| 토너먼트 관리 `c_tournament.php` | `tournament-admin/page.tsx` | **PARTIAL** | admin page present, depth unverified. |
| 입국 `PageJoin.vue` / `v_join.php` | — | **MISSING** | no join/세력입국 selection page in `web/game` (gateway lobby ≠ in-game 입국). |
| 장수 선택풀 `select_general_from_pool` | — | **MISSING** | no Next route. |
| 빙의 선택 `select_npc` | — | **MISSING** | no Next route (NPC 빙의 selection). |

### 3.3 Info / ranking pages

| PHP | Next route | Status |
| --- | --- | --- |
| `a_kingdomList` 세력일람 | `rankings/kingdoms` | **PRESENT** (read) |
| `a_genList` 장수일람 | `rankings/generals` | **PRESENT** (read) |
| `a_bestGeneral` 명장일람 | `rankings/best-generals` | **PRESENT** (read) |
| `a_hallOfFame` 명예의전당 | `rankings/hall-of-fame` | **PRESENT** (read) |
| `a_emperior(_detail)` 왕조일람 | `rankings/emperor[/id]` | **PRESENT** (read) |
| `a_npcList` 빙의일람 | `rankings/npcs` | **PRESENT** (read) |
| `a_traffic` 접속량 | `rankings/traffic` | **PRESENT** (read) |
| `b_myKingdomInfo` 세력 정보 | `my-nation` | **PARTIAL** (read) |
| `b_myCityInfo` 세력 도시 | `my-cities` | **PARTIAL** (read) |
| `b_currentCity` 현재 도시 | `city` | **PARTIAL** (read) |
| `b_myBossInfo` 인사부 | `my-boss` | **PARTIAL** (read; no 발령/permission set) |
| `b_genList` 암행부 | `generals?secret=1` | **PARTIAL** (read) |
| `b_myPage` 내 정보&설정 | `/game` (main, 내정보) | **PARTIAL** (settings setters incomplete) |
| `b_myGenInfo` | `my-generals` | **PARTIAL** (read) |
| (mailbox/messages — `MessagePanel.vue`) | `mailbox` | **PARTIAL** |

### 3.4 Gateway / lobby

| PHP/Vue | Next | Status |
| --- | --- | --- |
| `gateway/entrance.ts` 입구 | `web/gateway/app/page.tsx` | **PRESENT** |
| `gateway/login.ts` | `/login` | **PRESENT** |
| `gateway/join.ts` | `/join` | **PRESENT** |
| `gateway/user_info.ts` 내정보 | (folded into lobby/game) | **PARTIAL** |
| `gateway/admin_member.ts` + `admin_server.ts` | `/admin` | **PARTIAL** (single admin page vs member+server split) |
| (lobby/server-select) | `/lobby` | **PRESENT** |
| `gateway/install.ts` | — | **MISSING** (install flow intentionally divergent / N/A) |

---

## 4. Counts (structure dimension)

- **PHP/Vue user-facing pages + nav components enumerated:** **52**
  (24 main-game interactive · 22 info/ranking · 6 nav-component spine).
- **PRESENT:** **18** · **PARTIAL:** **27** · **MISSING:** **7**.
- **MISSING (7):** 감찰부 (battle center) · 입국 (PageJoin/세력입국) · 장수 선택풀 ·
  빙의 선택(select_npc) · 지도 dedicated page · gateway install · (user_info standalone).
- The dominant gap shape is **PARTIAL = read-only where PHP is interactive** (chief-center
  edit grid, NPC drag-reorder, inherit store, generals 발령, nation-msg TipTap, reserved-command
  advanced grid), NOT missing routes.

Per-command FE-submit gaps (20 FE_MISSING + 24 PORT_MISSING + 5 WIRING_MISSING) are owned by
`PARITY_LEDGER.md` and bound to `CommandModal` reachability — not re-counted here.
