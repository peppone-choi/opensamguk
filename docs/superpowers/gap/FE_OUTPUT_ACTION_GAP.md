# FE OUTPUT-INFO + ACTION PARITY GAP — ACTION pages

> Scope: per-action-page comparison of **PHP/Vue grand truth** vs **Next.js (`web/game`)** for BOTH
> *displayed information* AND *available actions*. Dimension = "FE page structure + displayed-info +
> interaction surface" for the major interactive pages. Commands themselves are audited in
> `docs/superpowers/PARITY_LEDGER.md` (the 93-command mutation-path ledger); this doc references it
> for per-command status and instead measures **what the page renders and what controls it offers**.
>
> Grand truth: `legacy/devsam-core/hwe/ts/` (Vue) + `legacy/devsam-core/hwe/ts/defs/API/` (the API
> contract: `SammoAPI.ts` lists every endpoint) + `legacy/devsam-core/hwe/j_*.php` (feeding JSON).
> Next pages: `web/game/app/game/<page>/page.tsx`. Wire-code intake set:
> `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt`.

## Method note — three failure classes

- **MISSING-INFO** — the Vue page shows a field/section the Next page never renders.
- **MISSING-ACTION** — the Vue page offers a control (button/modal/dropdown) with no Next equivalent.
- **SILENT-NO-OP (the dangerous class)** — the Next page DOES render a button, but it posts a `code`
  that is neither in `CommandWireMapper.intakeCodes` nor a registered `che_*` command, so
  `CommandRegistry.resolve → else → RestAction` swallows it (CommandReserveService path) or
  `toCommand()` returns null and the typed-publish drops it. The submit LOOKS successful (202) but the
  mutation never happens. These are worse than a missing button because the user believes it worked.

---

## 1. chief-center (수뇌부 / 사령부)

**Vue grand truth:** `PageChiefCenter.vue` + `ChiefCenter/TopItem.vue` + `ChiefCenter/BottomItem.vue`
+ **`components/ChiefReservedCommand.vue`** (the command-edit engine). Feeding API:
`SammoAPI.NationCommand.{GetReservedCommand, PushCommand, RepeatCommand, ReserveCommand, ReserveBulkCommand}`.

**Vue shows / offers:**
- 8 chief posts (officer levels 12/11/10/9/8/7/6/5) laid out in the legacy [12,10,8,6,11,9,7,5] grid,
  each post's occupant + name color (NPC tier) + turnTime + that post's `turn[]` reserved-command briefs.
- A **bottom selector strip** (`BottomItem`) to pick which post you're viewing/editing.
- When the viewed post == my officerLevel → the **full `ChiefReservedCommand` editor**:
  - **Edit mode toggle**, multi-turn selection (해제/모든턴/홀수턴/짝수턴/span-select).
  - **명령 선택 ▾** modal → reserve a nation command into the selected turn(s) (`ReserveCommand`/`ReserveBulkCommand`).
  - **반복** dropdown (RepeatCommand — fill N turns), **당기기/미루기** (pull/push by turnIdx, `PushCommand`).
  - **clipboard**: 잘라내기 / 복사하기 / 붙여넣기 / 텍스트 복사; **stored-action presets** (저장/삭제/사용).
  - 비우기 / 뒤로 밀기(empty push) / eraseAndPull.
- `troopList` for nation-command target resolution.

**Next page (`chief-center/page.tsx`):** READ-ONLY. Renders the 8 posts + each post's `turn[].brief`
(dangerouslySetInnerHTML) + occupant + NPC color + short turnTime. officerLevel ≥ 5 gate. The header
comment explicitly says *"The legacy '명령' (reserved-command edit) UI is DEFERRED — no CommandModal wiring here."*

**MISSING-INFO:** none material (post grid + briefs are all present).
**MISSING-ACTION (large):** the **entire nation-command reservation editor is absent** — no ReserveCommand,
no ReserveBulkCommand, no RepeatCommand, no PushCommand (당기기/미루기), no clipboard, no stored-action
presets, no edit-mode/turn-select, no 명령 선택 modal, no bottom post selector. This is the single
biggest action gap on any action page: the chief-center is *the* nation-command surface and it is 100%
read-only in Next. (The general-self command modal exists elsewhere; the chief/nation ring is unwired here.)

---

## 2. diplomacy (외교부 — letter management)

**Vue grand truth:** `ts/diplomacy.ts` (drawLetter) + `j_diplomacy_get_letter.php`. Feeding API:
`SammoAPI.Global.GetDiplomacy` + the five letter endpoints
`j_diplomacy_{get,send,respond,destroy,rollback}_letter.php`.

**Vue shows / offers:** letter cards with state (제안됨/승인됨/거부됨/대체됨) + state_opt (파기 요청) +
parties + brief/detail; and the action endpoints **send / respond / destroy / rollback** a letter.

**Next page (`diplomacy/page.tsx`):** renders letter cards read-only (state/parties/brief verbatim) +
four **빠른 명령** CommandModal quick-actions mapped to registered `che_` proposals
(`che_종전제의 / che_불가침제의 / che_불가침파기제의 / che_선전포고`). accept/decline routed to mailbox.

**MISSING-ACTION:**
- **send-letter (`j_diplomacy_send_letter`)** has no direct Next surface — proposals only go through the
  four quick-action `che_*` commands (a subset; the legacy free-form letter compose/send is absent).
- **destroy-letter (`j_diplomacy_destroy_letter`)** — the "파기 요청" control is missing; Next letter cards
  only *display* `try_destroy_src/dest`, can't *initiate* one.
- **rollback-letter (`j_diplomacy_rollback_letter`)** — no Next control.
- **respond-letter** — partially covered via mailbox accept/decline (`DiplomaticMessageController`), but the
  letter page itself is read-only. Verify the mailbox accept maps to the right `che_*수락`.

---

## 3. global-diplomacy (외교 현황 / 분쟁 현황 / 중원 지도)

**Vue grand truth:** `PageGlobalDiplomacy.vue` + `SammoAPI.Global.GetDiplomacy`. **Read-only by design**
(no mutations). Shows: nations×nations diplomacy matrix (★ 교전 / ▲ 선포 / ㆍ 통상 / @ 불가침, with the
neutral-vs-mine symbol maps), 분쟁 현황 per-city share%, and the 중원 지도 (MapViewer).

**Next page (`global-diplomacy/page.tsx`):** renders all three sections — matrix with byte-for-byte symbol
maps, 분쟁 현황 (hidden when conflict[] empty, matching `v-if`), MapViewer + nation list.

**Gap:** **PARITY OK.** No missing info, no missing action (page is inherently read-only). Best-matched page
in this audit.

---

## 4. board (회의실 / 기밀실)

**Vue grand truth:** `PageBoard.vue` + `components/BoardArticle.vue` + `BoardComment.vue`. Endpoints
`j_board_{get_articles,article_add,comment_add}.php`. Shows article list (no/author/icon/date/title/text/comments).
Actions: **새 게시물 작성** (title+text → article_add), **댓글 달기** (comment_add). Secret-board variant.

**Next page (`board/page.tsx`):** READ + MUTATION. Toggle 회의실/기밀실 (`api.board(secret)`), **글쓰기**
(`boardArticle` via CommandModal extraArgs title/text/isSecret), **댓글 달기** (`boardComment`). Both codes
ARE in `intakeCodes` → wired correctly.

**MISSING-INFO:** Vue `BoardArticleItem` carries `author_icon` + per-comment author/date; spot-check the Next
card renders the author icon + comment timestamps (low-risk).
**MISSING-ACTION:** legacy has **article/comment delete** in some builds (admin/own); not exposed in either —
not a regression. Otherwise **PARITY OK** for the core post/comment loop.

---

## 5. auction (경매장 / 유니크 경매장)

**Vue grand truth:** `PageAuction.vue` → `components/AuctionResource.vue` (393 lines) + `AuctionUniqueItem.vue`
(281). API `SammoAPI.Auction.{GetActiveResourceAuctionList, OpenBuyRiceAuction, OpenSellRiceAuction,
BidBuyRiceAuction, BidSellRiceAuction, GetUniqueItemAuctionList, GetUniqueItemAuctionDetail, BidUniqueAuction,
OpenUniqueAuction}`. **Two tabs** (금/쌀 자원 vs 유니크). Per `Auction.ts`: resource auctions show
buyRice/sellRice split, hostName, openDate/closeDate, amount, startBid/finishBid, highestBid bidder +
`recentLogs[]`; unique auctions show title/target/host/closeDate, **remainCloseDateExtensionCnt**,
availableLatestBidCloseDate, full **bidList[]** with isCallerHighestBidder, obfuscatedName, remainPoint.
Actions: **open** a buy/sell-rice auction, **open** a unique auction (from inherit page), **bid** on resource,
**bid** on unique (with close-date extension).

**Next page (`auction/page.tsx`):** single flat list (no 금/쌀 vs 유니크 tab split). Card shows
type/title/host/amount/reqResource/highestBid/남은시간. One **입찰** button.

**MISSING-INFO:**
- No **유니크 vs 자원 tab** (legacy `BButton 금/쌀 | 유니크`); types are flattened.
- No **bidList / bid history** (legacy unique shows full bidder list + isCallerHighestBidder).
- No **recentLogs[]**, no **startBid/finishBid**, no **close-date extension count / availableLatestBidCloseDate**,
  no **remainPoint** (유산 포인트 잔액) on unique auctions.
**MISSING-ACTION:**
- **Open a new auction** — no `OpenBuyRiceAuction`/`OpenSellRiceAuction`/`OpenUniqueAuction` surface
  (the 경매 시작 lives on the inherit page in legacy; see §8 — also missing there).
- **SILENT-NO-OP (CONFIRMED):** the 입찰 button posts `pinnedCommand="auction_bid"`, but the intake code is
  **`auctionBid`** (CommandWireMapper.kt:43,112). `auction_bid` ∉ intakeCodes → `toCommand()` returns null →
  bid silently dropped. (Ledger cross-cutting bug #1.)

---

## 6. betting (국가 베팅장)

**Vue grand truth:** `PageNationBetting.vue` + `components/BettingDetail.vue`. API
`SammoAPI.Betting.{GetBettingList, GetBettingDetail, Bet}`. Per `Betting.ts`: list shows
openYearMonth/closeYearMonth/name/finished/(종료/마감); **detail** shows candidates grid with title/info(html)/
**선택율%**, **총액**, **잔여 포인트/금**, **사용 포인트**, a 베팅액 input (10–1000, step 10), 배당 순위 table
(대상/베팅액/내 베팅/기대배율 or 배율), winner highlight, myBetting per-candidate.

**Next page (`betting/page.tsx`):** list of betting cards (type/status/totalPool/closeCondition) + **베팅** modal.

**MISSING-INFO:**
- No **candidates grid** (the actual bet targets) with **선택율%** and html info — only a pool total.
- No **배당 순위 table** (대상/베팅액/내 베팅/기대배율), no winner highlight, no per-candidate myBetting.
- No **잔여 포인트/금 + 사용 포인트** display, no reqInheritancePoint distinction (포인트 vs 금).
- No year/month-stamped open/close window text matching `parseYearMonth`.
**MISSING-ACTION:**
- **SILENT-NO-OP (CONFIRMED):** 베팅 posts `pinnedCommand="bet"`, intake code is **`placeBet`**
  (CommandWireMapper.kt:44,105). `bet` ∉ intakeCodes → dropped. (Ledger cross-cutting bug #2.)
- Bet input also omits `bettingType` (candidate selection) — the Next modal sends amount + nationId only,
  but `PlaceBet` requires `bettingType: List<Int>` (the chosen candidate indices). Even after the code fix,
  the missing candidate-pick means the bet target is unspecified.

---

## 7. troop (부대 편성)

**Vue grand truth:** `PageTroop.vue` + `SammoAPI.Troop.{NewTroop, JoinTroop, ExitTroop, SetTroopName,
KickFromTroop}`. Shows per-troop: name, leader city 【】, turnTime, leader icon+name, **reservedCommandBrief[]**,
members list (leader / same-city / diff-city styling) + 병력 count. Actions: 부대 탑승(join), 부대 탈퇴/해산(exit),
부대원 추방(kick), 부대명 변경(setName), 부대 결성(new, when troopless).

**Next page (`troop/page.tsx`):** READ + MUTATION. Renders troops + members; all five ops wired via
CommandModal extraArgs: `troopNew / troopJoin / troopExit / troopKick / troopSetName` — **all five are in
`intakeCodes`** (CommandWireMapper.kt:58-62). Permission gate (myPermission ≥ 4 for rename) mirrored.

**MISSING-INFO:** spot-check the **reservedCommandBrief[]** (troop's reserved nation-commands) and the
**leader 64×64 icon** + diff-city member styling are rendered; the Vue is icon-rich.
**MISSING-ACTION:** **PARITY OK** — the cleanest mutation page (slice B). No silent-no-op.

---

## 8. inherit (유산 포인트 상점)

**Vue grand truth:** `PageInheritPoint.vue` (741 lines) + `SammoAPI.InheritAction.{BuyHiddenBuff,
BuyRandomUnique, ResetSpecialWar, ResetTurnTime, SetNextSpecialWar, GetMoreLog, CheckOwner, ResetStat}` +
`Auction.OpenUniqueAuction`. Shows: point summary (총/기존/신규/천통/토너먼트), **상점** rows each with
필요 포인트 + 구입 button, **유산 버프** grid (8 buff types, current level, fibonacci cost, prev-level revert),
**장수 소유자 확인** (CheckOwner), **능력치 초기화** (ResetStat with leadership/strength/intel + inheritBonusStat),
유산 포인트 변경 내역 (log + 더 가져오기).
Store actions: 다음 전투 특기 선택(SetNextSpecialWar), **유니크 경매 시작(OpenUniqueAuction, 24턴)**,
랜덤 턴 초기화(ResetTurnTime), 랜덤 유니크 획득(BuyRandomUnique), 즉시 전투 특기 초기화(ResetSpecialWar),
유산 버프 구입(BuyHiddenBuff), 능력치 초기화(ResetStat), 소유자 찾기(CheckOwner).

**Next page (`inherit/page.tsx`):** renders point summary + store rows + buff grid + 소유자 확인 / 능력치 초기화
panels + log + 더 가져오기. Wires a CommandModal for store/reset actions.

**MISSING-ACTION / SILENT-NO-OP:**
- **SILENT-NO-OP (CONFIRMED, ×2):** buttons post **`BuyHiddenBuff`** and **`BuyRandomUnique`** — *neither* is in
  `intakeCodes` *nor* a registered `che_*` command. `toCommand()` returns null and there is no ring path →
  both silently dropped. (Ledger cross-cutting bug #3.) The three resets *do* work
  (`inheritResetTurnTime / inheritResetSpecialWar / inheritSetNextSpecialWar` ∈ intakeCodes).
- **MISSING-ACTION:** **유니크 경매 시작 (OpenUniqueAuction)** — the inherit page's "유니크 경매" row (start a
  24-turn unique-item auction by spending points) is **read-only / absent** in Next. No `OpenUniqueAuction`
  intake code exists anywhere → this is also the missing "open auction" path noted in §5.
- **MISSING-ACTION:** **능력치 초기화 (ResetStat)** and **소유자 확인 (CheckOwner)** are described as
  *read-only display* in the Next header comment — confirm whether their buttons actually submit;
  `ResetStat`/`CheckOwner` are not in `intakeCodes` (would be silent-no-op if wired).
**MISSING-INFO:** point-summary breakdown (천통 기여 / 토너먼트 입상 sub-points) — verify all five summary rows render.

---

## 9. vote (설문 조사 / 투표)

**Vue grand truth:** `PageVote.vue` (535) + `SammoAPI.Vote.{GetVoteList, GetVoteDetail, NewVote, Vote, AddComment}`.
Per `Vote.ts`: detail shows title + multipleOptions ("N개 선택 가능"), opener, options with single(radio)/
multi(checkbox) input, **투표율 voteTotal/userCnt + %**, per-option result bars, comments list, **댓글 달기**,
and **새 설문 조사 열기** (title + options newline-split + multipleOptions).
Actions: 투표(Vote), 댓글 달기(AddComment), 새 설문 개설(NewVote), (admin) 마감.

**Next page (`vote/page.tsx`, 33KB — the largest action page):** READ + MUTATION. List + detail
(options/results/myVote/userCnt). canVote → radio/checkbox + 투표 button (`voteCast`); 댓글 달기
(`voteComment`); 마감 (`voteClose`, admin, shown only when endDate absent); 새 설문 (`newVote`).
**All four codes (`voteCast / voteComment / voteClose / newVote`) are in `intakeCodes`** (CommandWireMapper.kt:67-70).

**Gap:** **PARITY OK** (best mutation coverage). Minor: confirm multi-select cap enforcement
("N개까지만 선택할 수 있습니다") and 투표율 % rendering match. No silent-no-op.

---

## 10. simulator (전투 시뮬레이터)

**Vue grand truth:** `PageBattleCenter.vue` / `battle_simulator.ts` (1108) + `battle_simulator.php` /
`j_simulate_battle.php` / `j_export_simulator_object.php`. A full attacker-vs-defender battle simulator:
pick two generals, set crew/crewtype/train/atmos/city/items, run `processWar` and render the blow-by-blow log
+ outcome, with exportable simulator objects and rich per-side stat inputs.

**Next page (`simulator/page.tsx`, 10.5KB):** pick two generals from `api.generals`, `api.simulateBattle({...})`,
render result. Much thinner input surface.

**MISSING-INFO/ACTION:**
- No **per-side combat-parameter inputs** (crew/crewtype/train/atmos/city/items/specialties) — legacy lets you
  tune both sides; Next appears to pass a minimal pair.
- No **export simulator object** (`j_export_simulator_object`).
- Confirm the **blow-by-blow battle log** (the war engine's Korean log lines) renders, not just a summary —
  the simulator's value is the draw-for-draw log.

---

## Adjacent pages touched (context, not in the 9-page ask)

- **nation-finance (`nation-finance/page.tsx`)** — setters ARE wired: `setNotice / setScoutMsg / setBlockWar /
  setBlockScout` ∈ intakeCodes. But `Nation.ts` exposes more setters: **setRate (지급률), setBill (세율),
  setSecretLimit (기밀 권한)** are in `intakeCodes` *but the Next page renders the policy block read-only*
  (no 세율/지급률/기밀-한도 edit controls) — MISSING-ACTION (backend ready, FE not surfaced).
- **npc-control (`npc-control/page.tsx`)** — Vue `PageNPCControl.vue` has 국가 정책 (gold/rice 포상 한도 등) +
  NPC 사령턴/장수턴 우선순위 drag-reorder + 초깃값으로/이전값으로/설정 submit (`j_set_npc_control`). Next renders
  policy + priority lists **read-only (setters DEFERRED per header comment)** — MISSING-ACTION (all NPC-control
  mutations: resetPolicy/rollbackPolicy/submitPolicy + priority reorder).
- **mailbox** — accept/decline diplomatic proposals via `DiplomaticMessageController` (wired). **SendMessage**
  (free-form private/national/diplomacy message compose, `SammoAPI.Message.SendMessage`) — verify a compose
  surface exists; the message-send path is a notable interactive feature not obviously present.

---

## SUMMARY (counts)

PHP/Vue interactive surfaces audited across the 9 requested pages (+3 adjacent): the page-level
present/partial/missing tally below counts each page as one "dimension item", and the action-level gaps
are enumerated in `topGaps`.

- **Read-display parity:** strong for global-diplomacy, vote, board, troop, chief-center (briefs), diplomacy
  (letter cards). Weak for **auction** (no tab/bidList/logs), **betting** (no candidates/배당 table/balances),
  **simulator** (no per-side inputs/log).
- **Action parity:** GOOD — board, troop, vote, global-diplomacy(read-only), nation-finance(partial).
  BROKEN/ABSENT — **chief-center** (entire reserved-command editor missing), **auction**+**betting**+**inherit**
  (3 confirmed SILENT-NO-OP intake-code mismatches), **diplomacy** (send/destroy/rollback letter absent),
  **npc-control** (all setters deferred), **simulator** (thin), **nation-finance** (3 setters not surfaced).
- **3 SILENT-NO-OP bugs** are the highest priority (auction `auction_bid`→`auctionBid`, betting `bet`→`placeBet`,
  inherit `BuyHiddenBuff`/`BuyRandomUnique` unregistered) — they fail invisibly and match the ledger's
  cross-cutting bug list; tracked as parity wave W0.
