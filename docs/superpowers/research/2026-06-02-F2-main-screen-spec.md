# F2 — Main Game Screen + Menu Spine Parity Spec

**Date:** 2026-06-02
**Target:** `web/game` (Next.js 15 App Router + React 19)
**Oracle:** `legacy/devsam-core` PHP/Vue `PageFront.vue` + sibling components (GRAND TRUTH). `devsam-core2026` TS = structural second oracle.
**Scope:** The in-game main screen (`/game`), its chrome spine (GameInfo header + GlobalMenu + MainControlBar), the command-reservation flow (modal-first), the info cards + message panel, and the game-web ↔ game-api auth/identity integration that unblocks all of it.

> Parity note: this is a **frontend** parity target. The byte-exact RNG/log gates live in `:logic`/`:common`. Here, "parity" means: same regions in same render order, same menu/button gating predicates, same Korean labels and header templates, same command argument shapes, same responsive (desktop ≥1000px / mobile ≤500px) collapse behavior. Where the legacy uses full-page `.php` navigation, the locked decision is to **prefer modal-driven command args** and **App-Router pages** for the linked sub-screens.

---

## 0. Locked Decisions (from the brief)

1. **Command args → MODAL where feasible.** Reuse/extend `CommandModal.tsx`. No `v_processing.php`-style page navigation for arg collection. The four legacy processing components (`SelectCity`, `SelectGeneral`, `SelectNation`, `SelectAmount`) become **modal sub-forms / arg field-types**, not pages.
2. **MainControlBar 20 buttons + gating = verbatim parity target.** The button table below is the contract. Labels (including the spaced-Hangul rendering), targets, and gating predicates are reproduced exactly.
3. **GlobalMenu is server-driven.** Menu structure comes from a `GetGlobalMenu`-equivalent endpoint (typed union `item | split | multi | line`), filtered client-side against `globalInfo`. Do NOT hardcode the GlobalMenu entries in the React tree.
4. **Design = dark war-room.** Reuse existing Shell/Header/Sidebar/BottomNav/GameCard/GameTable/StatusBadge/Toast + globals.css tokens.

---

## 1. Screen Frame Composition (PageFront equivalent)

### 1.1 Region render order + nesting (desktop)

```
<main> (gated: render only when asyncReady)
├── .commonToolbar → GlobalMenu               (server-driven menu, top)
├── GameInfo                                   (status header, §3)
├── .onlineNations                             (online nation summary)
├── .onlineUsers                               (online user count)
├── .nationNotice                              ("【 국가방침 】" header + v-html notice body)
├── #ingameBoard                               (CSS grid, §1.3)
│   ├── .mapView → MapViewer                   (DEFERRED to a later wave; placeholder OK in F2)
│   ├── .reservedCommandZone → ReservedCommandPanel   (#reservedCommandPanel, §5)
│   ├── #actionMiniPlate   ["갱신"(8) | "로비로"(4)]  (desktop)
│   ├── .cityInfo   → CityBasicCard            (§6.3)
│   ├── .nationInfo → NationBasicCard          (§6.2)
│   ├── .generalInfo → GeneralBasicCard        (§6.1)
│   ├── .generalCommandToolbar → MainControlBar (§2)
│   └── #actionMiniPlateSub ["명령으로"(3)|"갱신"(5)|"로비로"(4)] (mobile only; display:none desktop)
├── .RecordZone (grid row)
│   ├── .PublicRecord  "장수 동향" + ≤15 entries
│   ├── .GeneralLog    "개인 기록" + ≤15 entries
│   └── .WorldHistory  "중원 정세" + ≤15 entries
├── .commonToolbar → GlobalMenu                (repeated)
├── MessagePanel (#msgPanel)                   (§7)
└── .commonToolbar → GlobalMenu                (repeated)

GameBottomBar (#mobileBottomBar)  — OUTSIDE <main>, position:fixed, mobile only (§8)
```

**Loading gate:** while `!asyncReady`, render `서버 갱신 중입니다.` Everything else is suppressed.

### 1.2 Conditional render gates (port to React conditional render, not CSS hide)

| Region | Gate |
|---|---|
| entire `<main>` | `asyncReady === true` |
| CityBasicCard / NationBasicCard / MessagePanel | `frontInfo != null` |
| GeneralBasicCard | `frontInfo && generalInfo && nationStaticInfo` (strictest) |
| MapViewer | `map != null` |

### 1.3 `#ingameBoard` grid (desktop, `grid-template-columns: 500px 200px 300px`)

| Region | Col span | Row | Width |
|---|---|---|---|
| MapView | 1–2 | 1 | 500px |
| reservedCommandZone | 3 | 1–2 | 300px |
| cityInfo | 1–2 | 2–3 | 500px |
| #actionMiniPlate | 3 | 3 | 300px |
| MainControlBar | 1–3 | 4 | full |
| nationInfo | 1 | — | 300px |
| generalInfo | 2–3 | — | 600px |

Desktop: `#actionMiniPlateSub { display:none }`.

### 1.4 Mobile (`≤500px`, single column `grid-template-columns: 1fr`)

Stacking order: reservedCommandZone → MainControlBar → nationInfo → generalInfo → cityInfo. `#actionMiniPlate { display:none }`. `<main>` gets `margin-bottom: 45px` to clear the fixed GameBottomBar. All record/message regions collapse to single column.

### 1.5 Session/state lifecycle (the load cascade)

Single source of re-fetch truth = a `refreshCounter`-equivalent (React: a `refreshKey` state + `useEffect` deps, or SWR `mutate`). On mount, parallel `Promise.all([getGameConst(), tryRefresh()])` sets `asyncReady`. `refreshCounter` bump cascades:

1. `GetFrontInfo({ lastGeneralRecordID, lastWorldHistoryID })` → `frontInfo` / `globalInfo` / `generalInfo` / `nationInfo` / `nationStaticInfo` + record queues (max 15 each, FIFO) + lastExecuted + vote toast.
2. `GetMap({ neutralView:0, showMe:1 })` → `map`.
3. `ReservedCommandPanel.reload()` + command-table update.

`GetGlobalMenu()` fetched once on mount (independent of refreshCounter). `GetConst()` cached as a module singleton, provided via React Context (avoid prop-drilling). Vote state persisted to `localStorage["state.${serverID}.lastVote"]`.

**F2 port note:** the legacy `ExecuteEngine` (turn execution + server lock) is the daemon's job in opensamguk (game-engine). web/game does NOT call ExecuteEngine — it reads `GetFrontInfo`-equivalent state and listens via `useSSE` (`/realtime/events`) for `turnCompleted`, then bumps `refreshCounter`. Replace the legacy 3s poll / `window.location.reload()` with an SSE-driven soft refresh (refetch, do not full reload).

---

## 2. MainControlBar — VERBATIM parity target (20 buttons)

Legacy: full-page `<a href="*.php">`. **F2 port:** each entry maps to an App-Router page route (`/game/...`) or a modal. The **label, gating predicate, highlight rule, and new-window behavior are the parity contract.** Bar uses spaced Hangul (`회 의 실`); the dropdown replica uses compact labels (`회의실`) — preserve both renderings.

| # | Label (bar) | Legacy target | Gating predicate | New window | Highlight |
|---|---|---|---|---|---|
| 1 | 회 의 실 | `v_board.php` | `myLevel >= 1` | — | — |
| 2 | 기 밀 실 | `v_board.php?isSecret=true` | `permission >= 2` | — | — |
| 3 | 부대 편성 | `v_troop.php` | `myLevel >= 1 && nationLevel >= 1` | — | — |
| 4 | 외 교 부 | `t_diplomacy.php` | `showSecret === true` | — | — |
| 5 | 인 사 부 | `b_myBossInfo.php` | `myLevel >= 1` | — | — |
| 6 | 내 무 부 | `v_nationStratFinan.php` | `showSecret === true` | — | — |
| 7 | 사 령 부 | `v_chiefCenter.php` | `showSecret === true` | — | — |
| 8 | NPC 정책 | `v_NPCControl.php` | `showSecret === true` | — | — |
| 9 | 암 행 부 | `b_genList.php` | `showSecret === true` | ✅ | — |
| 10 | 토 너 먼 트 | `b_tournament.php` | none (always) | ✅ | `btn-sammo-base2 highlight` if `isTournamentApplicationOpen` |
| 11 | 세력 정보 | `b_myKingdomInfo.php` | `myLevel >= 1` | — | — |
| 12 | 세력 도시 | `b_myCityInfo.php` | `myLevel >= 1 && nationLevel >= 1` | — | — |
| 13 | 세력 장수 | `v_nationGeneral.php` | `myLevel >= 1` | — | — |
| 14 | 중원 정보 | `v_globalDiplomacy.php` | none (always) | — | — |
| 15 | 현재 도시 | `b_currentCity.php` | none (always) | — | — |
| 16 | 감 찰 부 | `v_battleCenter.php` | `showSecret === true` | ✅ | — |
| 17 | 유산 관리 | `v_inheritPoint.php` | none (always) | — | — |
| 18 | 내 정보&설정 | `b_myPage.php` | none (always) | — | — |
| 19 | 경 매 장 | `v_auction.php` | none (always); **split-dropdown group** | ✅ | — |
| 20 | 베 팅 장 | `b_betting.php` | none (always) | ✅ | `btn-sammo-base2 highlight` if `isBettingActive` |

**경 매 장 (#19) split sub-dropdown:**

| Sub-item | Legacy target | Gating | New window |
|---|---|---|---|
| 금/쌀 경매장 | `v_auction.php` | none | ✅ |
| 유니크 경매장 | `v_auction.php?type=unique` | none | ✅ |

**Gating predicate inputs (component props):**
```ts
interface MainControlBarProps {
  showSecret: boolean;          // secret/admin feature gate
  permission: number;           // 2+ = admin
  myLevel: number;              // basic membership level (officer_level proxy)
  nationLevel: number;          // faction/nation level
  isTournamentApplicationOpen: boolean;
  isBettingActive: boolean;
}
```

**Gating buckets:**
- `myLevel >= 1`: #1, 5, 11, 13
- `myLevel >= 1 && nationLevel >= 1`: #3, 12
- `permission >= 2`: #2
- `showSecret === true`: #4, 6, 7, 8, 9, 16
- always: #10, 14, 15, 17, 18, 19 (+subs), 20

**Disabled behavior:** failed gate adds `disabled` class. In React, when disabled, render a non-navigating element (do NOT route). The legacy `<a class="disabled">` still navigated; our port must actually block navigation (prevent default / render `<span>` instead of `<Link>`).

**MainControlDropdown (mobile/`국가 메뉴` replica):** identical items, identical gating, compact labels, multi-column (`columns` prop, default 4). New-window items get `.open-window`. Used inside GameBottomBar `국가 메뉴` dropup (visible if `permission >= 1 || officerLevel >= 2`). Note legacy typo `dromdown-menu-start` — do NOT reproduce the typo; use `dropdown-menu-start`.

### 2.1 Route mapping (F2 — modal vs page vs new-tab)

| Bar # | F2 destination | Mechanism |
|---|---|---|
| 1, 2 | `/game/board` (`?secret=1` for 기밀실) | page (deferred wave; route stub OK in F2) |
| 3 | `/game/troop` | page (deferred) |
| 4 | `/game/diplomacy` | page (EXISTS) |
| 5 | `/game/my-boss` | page (EXISTS) |
| 6, 7, 8 | `/game/nation` (sub-tabs 내무/사령/NPC) | page (EXISTS `nation`/`my-nation`; sub-tabs deferred) |
| 9 | `/game/generals?secret=1` | page (EXISTS) new-tab |
| 10 | `/game/tournament` | page (EXISTS) new-tab |
| 11 | `/game/my-nation` | page (EXISTS) |
| 12 | `/game/my-cities` | page (EXISTS) |
| 13 | `/game/generals` | page (EXISTS) |
| 14 | `/game/diplomacy` (global view) | page (EXISTS) |
| 15 | `/game/city` (current) | page (EXISTS) |
| 16 | `/game/battle-center` | page (deferred) new-tab |
| 17 | `/game/inherit` | page (deferred) |
| 18 | `/game` (MyPage) | page (EXISTS) |
| 19 | `/game/auction` (+`?type=unique`) | page (EXISTS) new-tab |
| 20 | `/game/betting` | page (EXISTS) new-tab |

> Highest-value linked sub-pages already scaffolded: diplomacy, my-boss, my-nation/nation, my-cities, generals, city, auction, betting, tournament. Deferred (route stub only in F2): board(회의실/기밀실), troop(부대편성), battle-center(감찰부), inherit(유산관리). Order them by traffic later.

---

## 3. GameInfo — status header

Title (`h3.scenarioName`): `{title} {serverName}{cnt}기  {scenarioText}` — e.g. `삼국지 테스트 1기  개발서버`.

Info grid (responsive: mobile col-4/col-8 4-col → desktop col-lg-2/col-lg-4 12-col; each cell: `s-border-t`, `py-2`, cyan text, centered). **Render order is the parity contract.**

| Order | Class | Template |
|---|---|---|
| 1 | subScenarioName | `{globalInfo.scenarioText}` |
| 2 | subNPCType | `NPC {npcCount}명, 상성: {확장\|표준} {가상\|사실}` |
| 3 | subNPCMode | `NPC선택: {불가능\|가능\|선택 생성}` |
| 4 | subTournamentMode | `토너먼트: 경기당 {minutes}분` |
| 5 | subOtherSetting | `기타 설정: <AutorunInfo/>` |
| 6 | subYearMonth | `현재: {year}年 {month}月 ({turnterm}분 턴 서버)` |
| 7 | subOnlineUserCnt | `전체 접속자 수: {count}명` |
| 8 | subAPILimit | `턴당 갱신횟수: {count}회` |
| 9 | subGeneralCnt | `등록 장수: 유저 {userCount} / {limit} + NPC {npcCount}명` |
| 10 | subTournamentState | link/span: `↑{type} {state} {nextText} {time}↑` |
| 11 | subLastExecuted | `동작 시각: {time}` — color cyan if unlocked, magenta if locked |
| 12 | subAuctionState | `{count}건 거래 진행중` / `진행중인 거래 없음` (link if active) |
| 13 | subVoteState | `설문 진행 중: {title}` / `진행중인 설문 없음` (link if active) |

All values come from `globalInfo` (= `GetFrontInfo().global`). `lastExecuted` color is a live lock indicator.

---

## 4. GlobalMenu — server-driven menu model

Fetched as `GetMenuResponse`, NOT hardcoded.

```ts
type GetMenuResponse = { result: true; menu: (MenuItem | MenuSplit | MenuMulti)[] };

type MenuItem = {
  type: 'item'; name: string; url: string;
  funcCall?: string;          // present → emit reqCall(url), preventDefault (API-driven action)
  icon?: string; newTab?: boolean;
  condHighlightVar?: string;  // highlight when globalInfo[var] truthy
  condShowVar?: string;       // '!x' = show when falsy; 'x' = show when truthy
};
type MenuSplit = { type: 'split'; main: MenuItem; subMenu: (MenuItem | MenuLine)[] };
type MenuMulti = { type: 'multi'; name: string; subMenu: (MenuItem | MenuLine)[] };
type MenuLine  = { type: 'line' };
```

**`filterMenu()` (identical in bar + dropdown variants):**
- MenuItem: `condShowVar` starting `!` → show when `globalInfo[var]` falsy; else show when truthy. No `condShowVar` → always.
- MenuMulti: recursively filter `subMenu`; if all filtered out → drop the multi; if exactly 1 remains → hoist that subitem to parent level.
- MenuSplit: filter main + subMenu independently; main filtered out → drop the split; subMenu empty → render main only.

**Click handling:**
```ts
function menuClick(e, menu: MenuItem) {
  if (menu.funcCall) { e.preventDefault(); emit('reqCall', menu.url); return; }
  if (!menu.url || !menu.newTab) return;        // default nav
  e.preventDefault(); window.open(menu.url);    // new tab
}
```

**Default entries (GlobalMenu.php v2 — production):**

| # | Type | Label | Target | Conditions / sub |
|---|---|---|---|---|
| 1 | item | 천통국 베팅 | `v_nationBetting.php` | highlight: `nationBetting` |
| 2 | multi | 게임정보 | — | 세력일람, 장수일람, 명장일람, **line**, 명예의전당, 왕조일람 |
| 3 | item | 연감 | `v_history.php` | newTab |
| 4 | split | 게시판 | `/board/community` | newTab; sub: 건의/제안, 팁/강좌, **line**, 패치 내역 |
| 5 | split | 공식 오픈 톡 | `https://open.kakao.com/o/` | newTab; sub: 잡담 오픈 톡 |
| 6 | item | 전투 시뮬레이터 | `battle_simulator.php` | newTab |
| 7 | multi | 기타 정보 | — | 접속량정보, 빙의일람 (show if `npcMode`) |
| 8 | item | 설문조사 | `v_vote.php` | newTab, highlight: `vote` |

(v3 `GlobalMenu.orig.php` is identical except `게임정보` had no MenuLine — v2 is the production truth.)

**Responsive layout:** GlobalMenu.vue = CSS grid (desktop 8-col / mobile 4-col, props `desktopRowSize`/`mobileRowSize`, `gap:0.1rem`, `white-space:nowrap`). GlobalMenuDropdown.vue = CSS `columns` (default 3); MenuMulti = disabled header + indented (`padding-left:1.5rem`) subitems; MenuSplit = linked header + indented; MenuLine = `<hr class="dropdown-divider">`.

**F2 port:** add a `GetGlobalMenu`-equivalent game-api read endpoint returning this union (server-driven per the locked decision). Until that endpoint exists, ship a typed static fixture matching the v2 table behind the same `GlobalMenu` component contract (so swapping to the API is a data-source change only). External `.php`/`http` targets keep `newTab` semantics; internal targets later remap to App-Router routes.

---

## 5. Command reservation flow (modal-first)

### 5.1 Selection (CommandSelectForm equivalent)

Category tabs (3-col grid) → command list (2-col) filtered by category. Command item:
```ts
interface CommandItem {
  value: string; simpleName: string; title: string;  // key / display / tooltip
  compensation: number;  // -1/0/+1 stat impact tag
  possible: boolean;     // executable now
  reqArg: boolean;       // needs an argument form
  info?: string;
}
```
Categories observed — General: 이동(강행/이동/천도), 전투(출병), 첩보, 사보타주(화계/탈취/파괴/선동/초토화), 방어(백성동원), 특수(수몰), 휴식. Chief/Nation: 외교(선전포고/불가침파기제의/종전제의), 전쟁(급습/이호경식/허보), 부대탈퇴지시.

### 5.2 Arg collection — legacy pages → F2 modal sub-forms

Legacy split: `reqArg===false` → instant reserve; `reqArg===true` → navigate `v_processing.php?command=&turnList=A_B_C&is_chief=`. **F2: ALL arg collection happens in the modal** (extend `CommandModal`). The four legacy processing components become modal field-types:

| Legacy component | Arg | F2 modal field-type | Input UX |
|---|---|---|---|
| (no-arg) | `{}` | — | direct reserve, inline brief |
| SelectCity | `destCityID:number` | `city` | searchable dropdown (초성 search) + (later) map click; red = unavailable, distance-aware. Commands: 강행/이동/출병/첩보/화계/탈취/파괴/선동/수몰/백성동원/천도/초토화 |
| SelectGeneral | `destGeneralID:number` | `general` | searchable dropdown; row = name(color)·city·troop·(통/무/지)·<병/훈/사>; red = unavailable. Commands: 부대탈퇴지시 etc. |
| SelectNation | `destNationID:number` | `nation` | searchable dropdown (+later map); red = diplomatically unavailable. Commands: 선전포고/급습/불가침파기제의/이호경식/종전제의/허보 |
| SelectAmount | `amount:number` | `amount` | numeric input + quick-adjust (-만/-천/-백 / +백/+천/+만) scaled to max + optional guide dropdown. Commands: 세금/헌납/기부 |

Multi-arg (future, e.g. `{destCityID, amount}`) = multiple modal fields in one form.

**`CommandModal` current state** (`web/game/components/CommandModal.tsx`): hardcoded 23-command grid, field types `number`/`select` only, calls `api.command(code, body)` with **NO generalId** and no city/general/nation pickers. F2 must extend it to: (a) inject `generalId` from session, (b) add `city`/`general`/`nation`/`amount` field types, (c) drive the command catalog from `availableCommands()` (`possible`/`reqArg`/`compensation`), (d) support turn-slot reservation.

### 5.3 Reserved-turn panel (ReservedCommandPanel / PartialReservedCommand)

General queue (5-col grid): `[Turn#] [Y/M] [Time] [Command brief] [edit ✎]`. Default 14 rows (expandable to maxTurn). Inline edit ✎ opens the command picker at that row. Tooltip when brief > 22 chars. Row height shifts edit(29.35px)/basic(34.4px).

Chief/Nation queue (`ChiefReservedCommand`, 3-col, edit/single modes): drag-select turns (info=blue/success=green), autorun-period highlight (cyan + tooltip), edit toolbar (cut/copy/paste/stored/repeat/erase), advanced-mode toggle.

### 5.4 API payloads

```ts
// no-arg / single reserve
POST /api/command/{code}?generalId={id}&turnIdx={i}   body: argJson|null
// bulk
ReserveBulkCommand([{ turnList:number[], action:string, arg:Args }, ...])
```
| Type | Arg | Example |
|---|---|---|
| Rest | `{}` | `{turnList:[0], action:"휴식", arg:{}}` |
| City | `destCityID` | `{turnList:[0,1], action:"강행", arg:{destCityID:42}}` |
| General | `destGeneralID` | `{turnList:[0], action:"부대_탈퇴", arg:{destGeneralID:101}}` |
| Nation | `destNationID` | `{turnList:[0], action:"선전포고", arg:{destNationID:2}}` |
| Amount | `amount` | `{turnList:[0], action:"세금", arg:{amount:50000}}` |

**game-api today** (`CommandController.kt`): `POST /api/command/{code}?generalId&turnIdx` body=argJson. Returns 202 `{status:"AVAILABLE", requestId, turnIdx}` on precheck pass, else 200 `{status:"BLOCKED"|"UNKNOWN", reason, constraintName?}`. The UI must render the BLOCKED `reason` (PHP-faithful deny string) rather than treating it as an error. No bulk endpoint yet (single-slot only) — bulk reserve is a backlog item.

---

## 6. Info cards (read-only)

### 6.1 GeneralBasicCard
Header: 64×64 icon + name panel (nation-color bg): name · officer-city (if lvl 2–4 assigned) · officer-level text · general-type call (from 통/무/지) · injury status (color) · turn timestamp `HH:MM:SS`.
Core stats (4×3): 통솔 (value + bonus cyan if >0 + exp bar) / 무력 (+exp) / 지력 (+exp).
Equip/resources: 명마, 무기, 서적 (item name + tooltip), 자금, 군량 (localized num), 도구.
Troop: 병종 (name+icon+tooltip), 병사 (localized), 성격 (tooltip), 훈련, 사기, 특기 (내정특기/전투특기 + tooltips).
Level/age: Lv (+exp-to-next bar), 연령 (green<75% / yellow<100% / red≥100% of retirement year).
Defense/penalty: 수비 (`수비 안함` red / `수비 함(훈사X)` lime), 삭턴, 실행 (minutes remaining), 부대 (name; struck-through if reserved non-assembly cmd; orange if different city), 벌점 (refresh score + point + paren detail).

### 6.2 NationBasicCard
Header: nation name (nation-color bg, auto text color by brightness).
Type: 성향 (type + Pros cyan + Cons magenta) · Rank12 officer · Rank11 officer (NPC color-coded).
Pop/resources (only if `nation.id`; else `해당 없음`): 총 주민 (cur/max), 총 병사 (cur/max), 국고, 병량, 지급률 %, 세율 %, 속령 (city count), 장수 (count), 국력.
Tech/commands: 기술력 (level grade + value; magenta at limit, lime otherwise), 전략 (cooldown red / `가능` yellow-enabled / lime-available), 외교 (cooldown red / `가능` lime), 임관 (`금지` red / `허가` lime), 전쟁 (`금지` red / `허가` lime). Tech-limit tooltip lists impossible strategic commands w/ turn counts + calendar dates.

### 6.3 CityBasicCard
Header: city panel (nation-color bg) `【Region | Level】 City Name`; nation panel `지배 국가 【Nation】` or `공 백 지`.
Metrics (9 panels, 2-col each, bar + cur/max): 주민, 민심 (% + decimal), 농업, 상업, 치안, 수비, 성벽, 시세 (% or `상인 없음`).
Officers: 태수, 군사, 종사 (NPC color-coded; `-` if vacant).

### 6.4 GeneralSupplementCard (extended; lower priority for F2)
3-col: 명성 (+exp paren), 계급 (+dedication paren), 봉급, 전투, 계략, 사관.
Battle stats: 승률 %, 승리, 패배, 살상률 %, 사살, 피살.
Proficiency (4×6): 보병/궁병/기병/귀병/차병 — label · level(color) · value(K) · bar.
Reserved commands (if `showCommandList`): ≤5 brief.

---

## 7. MessagePanel — multi-channel messaging

Input bar (sticky top, 3 col): mailbox selector (`<select>` grouped favorites/diplomacy/nations; `*Name*`=ruler flag0x1, `#Name#`=ambassador flag0x4; favorites `【아국 메세지】`/`【전체 메세지】`) + input (max 99 chars, Enter sends) + `서신전달&갱신` button.

Four stacked channels:
- **PublicTalk** (전체 메시지) — header + `여기로`; actions: `접기` (mobile) / `이전 메시지 불러오기` (desktop).
- **NationalTalk** (국가 메시지) — same.
- **PrivateTalk** (개인 메시지) — `모두 읽음` (disabled unless unread); toast `새로운 개인 메시지가 도착했습니다.` (10min, goto/ignore).
- **DiplomacyTalk** (외교 메시지) — `모두 읽음` (permission ≥4 only); messages permission ≥4 only; toast `새로운 외교 메시지가 도착했습니다.`

Auto-refresh: poll 2.5s w/ incremental sequence-id; on fail toast `메시지 자동 갱신 실패 - 새로고침을 해주세요.`. **F2 port:** drive refresh via SSE + sequence-id incremental fetch (mirror §1.5), not a raw 2.5s poll, but keep failure toast.

MessagePlate (single message): 64×64 icon + body. Header varies by type/permission:
- Private: `【Name:Nation】 ▶ 【Recipient:Nation】` (self → `나 ▶ …`) + `<HH:MM:SS>`.
- National/Diplomacy: same-nation `【Name】`; cross-nation + perm≥4 `【Name】 ▶ 【Nation】`.
- Public: `【Name:Nation】 ▶ 【Icon】` (self → `【Name】`).
Bg colors: private-sent #5d1e1a / private-recv #5d461a / public #141c65 / nat-dip-sent #00582c / nat-dip-recv #704615 / own-nation #70153b. Linkified text; deleted → grey `삭제된 메시지입니다`; delete ❌ only if own + <5min + no action + deletable; action buttons 수락/거절 (confirm dialog; perm≥4 for diplomacy).

game-api today: `GET /api/mailbox/{mailbox}`, `/api/mailbox/{mailbox}/unread`, `GET /api/messages/{id}`; `POST /api/messages/{id}/accept|decline?generalId=`. The `lib/api.ts` `mailbox()` hits bare `/api/mailbox` — must be parameterized by mailbox + generalId.

---

## 8. GameBottomBar — mobile fixed nav (`#mobileBottomBar`)

Visible <992px (`d-sm-block d-lg-none`), `position:fixed; bottom:0; z-index:99; height~45px`. Dropup menus, 16px font, 125px item width, 3-col dropmenus, top box-shadow.

1. **외부 메뉴** → GlobalMenuBar (3-col) w/ globalInfo.
2. **국가 메뉴** → MainControlDropdown (secret items if `permission≥1 || officerLevel≥2`).
3. **빠른 이동** (scroll-to anchors, max-height `100vh-50px`):
   - 국가 정보: 방침→`.nationNotice`, 명령→`#reservedCommandPanel`, 국가→`.nationInfo`, 장수→`.generalInfo`, 도시→`.cityInfo`
   - 동향 정보: 지도→`.mapView`, 동향→`.PublicRecord`, 개인→`.GeneralLog`, 정세→`.WorldHistory`
   - 메시지: 전체/국가/개인/외교 → `.{Channel} > .stickyAnchor`; **로비로** → `location.replace("../")`
4. **갱신** → emits refresh (bump refreshCounter).

F2 already has `BottomNav.tsx` (mobile nav subset) — extend it to host these dropups + scroll-anchors, or add a dedicated GameBottomBar for the `/game` main screen.

---

## 9. Auth-gate integration analysis (the F2 blocker)

### 9.1 Current state (confirmed by source)

| Dimension | web/game | web/gateway | gateway-api | game-api |
|---|---|---|---|---|
| Auth gate | ❌ none | ✅ AuthGate + AuthProvider | n/a | n/a |
| Session/JWT | ❌ no cookie/header | ✅ httpOnly `sam_access`(15m JWT)/`sam_refresh`(7d), proxy attaches Bearer | ✅ JwtTokenProvider + JwtAuthenticationFilter + SecurityConfig | ❌ none |
| Spring Security | n/a | n/a | ✅ | ❌ no `spring-boot-starter-security`, no filter |
| User identity | ❌ none | ✅ `{id,username,role}` via `/api/auth/me` | ✅ subject from JWT | ❌ identity passed as `?generalId=` query param everywhere |
| `my-*` endpoints | ✅ CALLED (`/api/my-page`, `/api/my-generals`, `/api/my-cities`, `/api/my-boss`, `/api/my-nation-detail`) | n/a | n/a | ❌ NOT IMPLEMENTED in game-api source |

Confirmed facts: `web/game/lib/api.ts` sends no auth header; `CommandModal` calls `api.command(code, body)` with no `generalId`; `CommandController.kt` REQUIRES `@RequestParam generalId: Int` (request 400s without it); auction/betting/message controllers all take `generalId` as a query param; the `my-*` endpoints the client calls do not exist server-side. web/gateway has a working `/api/proxy/[...path]` route that reads the httpOnly access cookie and forwards `Authorization: Bearer`.

### 9.2 The two real gaps
1. **No identity carrier into web/game.** Cookies from gateway (`localhost:3000`) do not reach game (`localhost:3001`); web/game has no session bridge, no AuthGate, no cookie read.
2. **game-api can't verify identity.** No Spring Security; `generalId` arrives as an untrusted query param (today a dev `=1` hack). Cannot distinguish callers, cannot map user → general.

### 9.3 Options
- **A — Proxy pattern (RECOMMENDED).** web/game gets its own AuthGate + a server-side route handler `app/api/game/[...path]/route.ts` that reads the httpOnly access cookie (set on the shared parent domain in prod) and forwards to game-api with `Authorization: Bearer` (+ resolves `generalId` from the verified subject). Mirrors the proven web/gateway proxy. JWT never touches client JS. game-api gains a minimal JWT-verify filter + a `userId → generalId` resolver so the query param disappears.
- **B — Bearer in client.** Gateway login returns JWT to client storage; web/game attaches `Authorization` from the browser. Simplest wiring, but XSS-exposed token (rejected by the gateway cookie design rationale in `lib/cookies.ts`).
- **C — Gateway as full API proxy.** All game traffic routes through gateway-api (already secured) which calls game-api server-to-server. Cleanest trust boundary, biggest plumbing/latency cost; defers game-api security.

### 9.4 Recommendation
**Option A** (server-side proxy in web/game + minimal JWT-verify filter in game-api). It (1) reuses the proven web/gateway cookie + proxy machinery and the existing gateway-api `JwtTokenProvider` symmetric-secret/issuer, (2) keeps the JWT off client JS (consistent with `cookies.ts` XSS rationale), (3) lets game-api migrate `@RequestParam generalId` → a verified principal incrementally (proxy can still inject `?generalId=` during transition so existing controllers keep working), and (4) needs no cross-origin cookie hacks. Concretely:
- web/game: add `AuthGate`/`AuthProvider` (port from web/gateway) + `app/api/game/[...path]/route.ts` proxy; point `lib/api.ts` `BASE` at `/api/game` instead of `:8081` directly.
- Shared cookie domain in prod (e.g. `.opensam.example`) so `sam_access` set by gateway is readable by the game origin; in dev, web/game `/api/auth/me`-style endpoint can call gateway-api directly.
- game-api: add `spring-boot-starter-security` + a JWT filter sharing gateway-api's secret/issuer; expose a `userId → generalId` resolver; implement the missing `my-*` read endpoints; over time replace `@RequestParam generalId` with `@AuthenticationPrincipal`.

---

## 10. Open questions
1. Does game-api own the `GetGlobalMenu`/`GetFrontInfo`/`GetConst` read endpoints, or do they belong to game-engine read APIs / a new read controller? (None found in game-api source today.)
2. `userId → generalId` mapping authority: is one user one general per server, or many? Where does that table live (gateway-api users vs game-api generals)?
3. Shared cookie domain / SSO story for prod (gateway :3000 vs game :3001 → behind nginx same-origin paths?). Affects Option A cookie readability.
4. Does the JWT subject carry `permission`/`myLevel`/`nationLevel`/`showSecret`, or must game-api derive those from the resolved general for MainControlBar gating?
5. MapViewer (`.mapView` + `GetMap`) — in-scope for F2 or deferred? (Spec assumes a placeholder in F2; map render is its own wave.)
6. Bulk command reservation (`ReserveBulkCommand`) — game-api only has single-slot `POST /api/command/{code}`. Is bulk needed for F2 parity or backlog?
7. SSE event taxonomy: does `/realtime/events` distinguish `turnCompleted` vs message-arrival vs auction/vote events, so the UI can soft-refresh the right slice instead of `window.location.reload()`?
8. Is `nationNotice` (국가방침) stored HTML (v-html) — what is the sanitization/escaping contract for the React port (`dangerouslySetInnerHTML`)?
