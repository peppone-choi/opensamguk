# GAP_AUDIT — opensamguk PHP→Kotlin/Next.js migration parity master audit

> Synthesis of 7 gap-audit investigator passes (API, logic-systems, FE-structure,
> FE-output-read, FE-output-action, read-DTO, founding-seam) plus the
> per-command `PARITY_LEDGER.md`. Grand truth = `legacy/devsam-core` (PHP) with
> `legacy/devsam-core2026` (TS) as the structural-only second oracle; **PHP wins
> every divergence**. Per-dimension detail lives in `docs/superpowers/gap/*.md`.
>
> **Headline parity state:** the **engine is gate-closed at the logic level**
> (P0–P7 golden-green, ~2195 tests). The founding crash-loop (WAVE 0) is **FIXED**
> and prod is stable (turn advancing). Entrance flows (B1–B3) are complete.
> The read surface remains ~30% field parity; mutation surface is partially
> wired. The logic core is sound; **the player surface and long-tail commands
> are the remaining work.**

---

## 0. Current State (2026-06-08, parity-final branch)

| Item | Count / Status |
|---|---|
| Branch | `parity-final` — **14 commits ahead of main** (unmerged) |
| Backend tests | ~2195+ tests green (common 192 / logic 1865+ / engine 297 / game-api 177+) |
| Prod status | **STABLE** — turn advancing, crash-loop resolved |
| Entrance flows | **B1 Join** ✅ / **B2 NPC possession** ✅ / **B3 NPC selection** ✅ |
| Command parity (93 total) | **52 DONE** / 19 PORT_MISSING / 20 FE_MISSING / 2 LOGIC_ONLY / 5 WIRING |
| 계략 5종 (화계/파괴/탈취/선동/첩보) | **DONE** — golden-gated, parity-wave batch 1 (`6b5eff5`) |
| WAVE 9 (public reads + admin) | **DONE** — `WorldMapController` + `CityListController` + map page + admin design-replaced |
| Founding seam (WAVE 0) | **FIXED** — created-set drain, 거병 preload args, 건국 cascade |

---

## 1. Executive summary

| Dimension | doc | total | present | partial | missing |
|---|---|---:|---:|---:|---:|
| API endpoints (HTTP surface) | [API_GAP.md](gap/API_GAP.md) | 116 | 25 | 37 | 54 |
| Non-command logic systems | [LOGIC_GAP.md](gap/LOGIC_GAP.md) | 16 | 6 | 7 | 3 |
| FE page+component structure | [FE_STRUCTURE_GAP.md](gap/FE_STRUCTURE_GAP.md) | 52 | 18 | 27 | 7 |
| FE output (read/info pages) | [FE_OUTPUT_READ_GAP.md](gap/FE_OUTPUT_READ_GAP.md) | 218 | 53 | 12 | 153 |
| FE output (action pages) | [FE_OUTPUT_ACTION_GAP.md](gap/FE_OUTPUT_ACTION_GAP.md) | 12 | 4 | 8 | 0 |
| Read-model DTO shapes | [READ_DTO_GAP.md](gap/READ_DTO_GAP.md) | 18 | 6 | 11 | 1 |
| Founding daemon seam (prod) | [FOUNDING_SEAM_FIX.md](gap/FOUNDING_SEAM_FIX.md) | 4 | 4 | 0 | 0 |
| **Per-command mutation path** | [PARITY_LEDGER.md](PARITY_LEDGER.md) | **93** | **52 DONE** | **20 FE_MISSING** | **19 PORT_MISSING** + 2 LOGIC_ONLY + 5 WIRING |
| Entrance flows (B1–B3) | [SESSION_HANDOFF.md](SESSION_HANDOFF.md) §5 | 3 | 3 | 0 | 0 |

> **Note on the read-page 153 "missing":** these are displayed *fields* (≈218
> across 8 info pages), not routes — the routes exist (FE-structure) but their
> DTOs carry ~30% of the PHP fields, so the count is field-level, not page-level.

### The dominant cross-cutting patterns

1. **Founding seam FIXED (was: golden-green / prod-broken).** The founding
   created-set drain, 거병 preload args, and 건국/cr_건국/무작위건국 cascade are
   all resolved. Prod is stable and turns advance. **Remaining seams (3):**
   ruler succession (no heir), DiplomacyMonthProcessor (treaties never expire),
   checkStatistic (empty-lambda stub). These are tracked in WAVE 1 below.
2. **Silent-no-op intake (3, plus tournament).** FE buttons return `202` but the
   mutation is dropped because the posted code mis-cases or is unregistered →
   resolves to `RestAction` (`CommandWireMapper.kt`/`CommandRegistry.kt:161`):
   auction `auction_bid`≠`auctionBid`, betting `bet`≠`placeBet`,
   inherit `BuyHiddenBuff`/`BuyRandomUnique` (unregistered), tournament-admin
   `tournament_start/advance/reset` (unregistered). **Most dangerous class** —
   fails invisibly.
3. **Read DTOs are skeletons.** `FrontGeneralInfo`/`FrontNationInfo`/`FrontGlobalInfo`
   carry ~30% of the contract; no P1/P2 permission tiers on generals; every gauge
   renders as a single number (no now/max bar); ~25 header gates absent so menus
   can't gate. This starves every FE-output page downstream.
4. **Mutation surface is mostly read-only.** Every action page HAS a Next route
   (0 fully-missing), but only 4/12 reach parity; chief-center (the entire
   nation-command reservation editor) is 100% read-only — the single biggest
   action gap.
5. **Visible string-parity break.** `page.tsx:92` and `rankings/generals:59`
   render 물력 as the mojibake 「묠력」 — a display-string parity violation on the
   most-viewed stat.
6. **Entrance flows DONE.** B1 장수생성 (Join/MakeGeneral, draw-for-draw golden),
   B2 장수빙의 (NPC possession claim + daemon persistence), B3 장수선택
   (NPC selection token pool) are all complete with FE pages and intake paths.

---

## 2. Per-dimension findings

### 2.1 API endpoints — [API_GAP.md](gap/API_GAP.md) · 116: 25 / 37P / 54M
Canonical surface = 83 REST handlers (`hwe/sammo/API/**`, dispatched
`api.php?path=Domain/Handler` — the contract the Vue `ts/defs/API/*.ts` calls) +
33 `j_*.php` AJAX + 16 `v_*.php` views. The 22 Kotlin controllers are almost
entirely read-only GETs. Read (`Get*`) handlers are well covered; mutation
handlers are PARTIAL (routed through generic `POST /api/command/{code}` with no
domain-named path a faithful client can call) or MISSING.
- Message `SendMessage`/`GetContactList`/`DeleteMessage` — no personal/national send.
- Vote `Vote`/`NewVote`/`AddComment` — read-only, cannot cast or create.
- Auction `Open*` (BuyRice/SellRice/Unique) MISSING; `Bid*` PARTIAL with casing no-op.
- General `Join` + `BuildNationCandidate` — ✅ B1 Join done (end-to-end intake+FE); 건국 candidate REST entry remains.
- InheritAction `Buy*`/`Reset*`/`SetNextSpecialWar` — 7/8 spend actions unreachable.
- Command/NationCommand `ReserveBulk`/`Push`/`Repeat` (6 handlers) — only single reserve.
- Diplomacy letter send/rollback/destroy MISSING (read+respond only).
- Admin/install (`j_install`, `Global/ExecuteEngine`) intentionally replaced by Flyway+ScenarioImporter+daemon — MISSING-as-REST but design-OK.

### 2.2 Non-command logic systems — [LOGIC_GAP.md](gap/LOGIC_GAP.md) · 16: 6 / 7P / 3M
COMPLETE: battle engine, NPC AI, events DSL, auction (wired), messaging/mailbox,
monthly leaves. The headline = **three remaining golden-green/prod-broken seams**
(founding was fixed in WAVE 0):
- **Ruler succession** — `nextRuler`/`deleteNation` unported; `DaemonLoopConfig.kt:141-147`
  constructs the handler with no-op defaults → ruler death promotes no heir, fires
  no nation-deletion cascade → zombie/rulerless nation.
- **DiplomacyMonthProcessor** exists but has NO tick caller → 불가침/정전 never auto-expire.
- **checkStatistic** is an empty-lambda stub (`EngineEventConfig.kt:75`) → year-boundary national statistics never computed.
MISSING: the entire **tournament bracket engine** (`func_tournament.php` 1393 lines;
only enroll ported; `processTournament` not in the tick tail), ruler succession.
PARTIAL: item special-effect hooks (only ~15 of 79 non-stat specials registered),
vote CRUD.

### 2.3 FE page+component structure — [FE_STRUCTURE_GAP.md](gap/FE_STRUCTURE_GAP.md) · 52: 18 / 27P / 7M
20-button MainControlBar + GlobalMenu spine reproduced; all 7 ranking pages + most
info pages present as read renders. Dominant gap is PARTIAL = **read-only where PHP
is interactive**: chief-center 12-command edit grid, NPC-control drag-reorder +
setters, inherit store, generals 발령, nation-finance TipTap. Reserved-command ring
is a scaffold blocked on a missing read endpoint. 7 genuinely MISSING → 4 remaining:
gateway install (design-replaced), standalone user_info; ✅ cached-map page done
(WAVE 9b), ✅ 입국/장수풀선택/빙의선택 entry flows done (B1–B3).

### 2.4 FE output — read/info pages — [FE_OUTPUT_READ_GAP.md](gap/FE_OUTPUT_READ_GAP.md) · 218: 53 / 12P / 153M (~30%)
Four structural root causes: (1) `FrontInfoController` DTOs are skeletons (main page
reads the thin `front-info`, not the richer `MyController`); (2) no permission
tiering on general lists → all P1/P2 secret/derived fields absent; (3) every gauge
is a single number, no now/max bar/민심/holder names; (4) log/record sections
(개인기록·전투기록·전투결과·장수열전) and 연감 map+nation-ranking panel have no Next
surface. Plus the 무력→「묠력」 mojibake at `page.tsx:92` and `rankings/generals:59`.

### 2.5 FE output — action pages — [FE_OUTPUT_ACTION_GAP.md](gap/FE_OUTPUT_ACTION_GAP.md) · 12: 4 / 8P / 0M
0 fully-missing routes; only 4 at parity (global-diplomacy, vote, board, troop).
Two failure modes: read-display thinness (auction has no tab/bidList/logs; betting
no candidates/배당 table/balances; simulator no per-side inputs/log) and action gaps
(chief-center 100% read-only — the entire reservation editor). **3 confirmed
silent-no-op intake mismatches** (auction/betting/inherit) = wave-W0 priority.
diplomacy send/destroy/rollback letter absent; npc-control setters deferred;
nation-finance setRate/setBill/setSecretLimit backend-ready but not surfaced.

### 2.6 Read-model DTO shapes — [READ_DTO_GAP.md](gap/READ_DTO_GAP.md) · 18: 6 / 11P / 1M
PHP ships a column+row wire format; Kotlin returns named-object arrays for Next —
that reshape is not counted as a miss. Real field losses concentrate in:
ChiefCenter (command palette, post holders, troopList, reserved-arg dropped);
GetFrontInfo `global{}` (~25 header gates absent, `recentRecord` hard-coded empty);
GeneralList (no P1/P2 DTO, thin P0); Message (`src`/`dest` bare ints, no MsgTarget/
option block); Map (no fog `spyList`, `shownByGeneralList`, city state/supply/region);
Betting (no market/candidates DTO); Auction (bidder/highestBid/host/remainPoint/logs
dropped); GetConst (unit/city/iAction const bundle missing). 1 MISSING: no public
city-list endpoint (only nation-scoped my-cities).

### 2.7 Founding daemon seam — [FOUNDING_SEAM_FIX.md](gap/FOUNDING_SEAM_FIX.md) · 4: 4 / 0 / 0 ✅
**FIXED.** The prod crash-loop (거병 missing preload args + created-set never drained)
is resolved. `ReservedTurnHandler.handle` now injects `che_거병` preload args
(`newNationId`/`existingNationIds`/`existingNationNames`/`scenario`) before building
`resolveCtx`. The created-set drain (`createdNations` → `createdDiplomacy` →
`createdNationTurns`) runs after `definition.resolve`. The 건국/cr_건국/무작위건국
UPDATE cascade also diffs `draft.nation` and drains properly. Prod is stable and
turns advance.

---

## 3. Prioritized closure roadmap

Ordering principle: **stop the bleeding → unblock the chain → fill the surface →
finish the long tail.** Each wave is a tight, disjoint, foundation-first batch.
Waves earlier in the list are prerequisites for (or strictly higher-value than)
later ones. Within a wave, foundation artifacts (DTOs, mutators, registries) build
before consumers.

### WAVE 0 — PROD RECOVERY: founding daemon seam ✅ DONE
*Source: FOUNDING_SEAM_FIX.md §1–§3. All items resolved; prod is stable and turns advance.*
- **0a–0e** ✅ DONE — `InMemoryTurnWorld` created-set API, 거병 preload args injection,
  created-set drain (FK order), 건국/cr_건국/무작위건국 cascade drain, gate test.

### WAVE 1 — daemon-seam correctness (3 remaining golden-green/prod-broken seams) 🔴
*Source: LOGIC_GAP.md. The founding seam (was 4th) is FIXED. These 3 remain.*
- **1a ruler succession** — port `nextRuler`/`deleteNation` into `:logic`; wire into `DaemonLoopConfig` ctor (remove no-op defaults) + dying-message RNG variant pool.
- **1b DiplomacyMonthProcessor wiring** — add the tick caller in PreUpdate/PostUpdateMonthly so 불가침/정전 term countdown + auto-expiry runs.
- **1c checkStatistic** — replace the empty-lambda stub with the real year-boundary national-statistics computation.
- **1d item special-effect hooks** — register the remaining non-stat specials reachable in scenario_1010 (계략/공성/농성/의술/반계/사기/위압/저격/부적); audit the 79 against equip-reachability.
- **1e 계략 5종 DONE** — `che_화계`/`파괴`/`탈취`/`선동`/`첩보` ported + golden-gated
  (`6b5eff5`, parity-wave batch 1). These were originally slated for WAVE 7a;
  completed early via `/parity-wave` sabotage batch.

### WAVE 2 — silent-no-op intake fixes (W0 of PARITY_LEDGER) 🔴
*Source: FE_OUTPUT_ACTION_GAP.md / PARITY_LEDGER cross-cutting. Tiny, high-value — buttons that lie.*
- **2a** auction `auction_bid`→`auctionBid`, betting `bet`→`placeBet` (FE post or intake alias); also thread the missing `bettingType` (candidate pick) the modal omits.
- **2b** inherit `BuyHiddenBuff`/`BuyRandomUnique` — register as intakeCodes or che_ commands + wire FE.
- **2c** tournament-admin `tournament_start/advance/reset` — register (gated on WAVE 8 tournament engine; until then make the FE no-op explicit, not silent).

### WAVE 3 — read-DTO foundation (unblocks ALL FE-output) 🟠
*Source: READ_DTO_GAP.md / FE_OUTPUT_READ_GAP.md. Foundation-first: every read page consumes these.*
- **3a FrontInfo enrichment** — fill `FrontGeneralInfo` (picture/exp/train/atmos/injury/병종/items/내특·전특/성격/Lv/벌점/부대 + generalInfo2 명성·계급·전투통계·dex1~5), `FrontNationInfo` (type/topChiefs/총주민·총병사/지급률·세율/tech/제한), `FrontGlobalInfo` global{} (~25 header gates + real `recentRecord`).
- **3b GeneralList permission tiers** — add P1/P2 DTO + the env/troops/myGeneralID/permission envelope; thicken P0; reproduce PHP 15-sort ordering.
- **3c gauge now/max** — every gauge carries current+max (+민심/holder names) so FE can render bars.
- **3d ChiefCenter read DTO** — command palette + post holders (name/turnTime/npcType) + troopList + reserved `arg`; **add the missing reserved-command READ endpoint** (unblocks the FE-structure scaffold).
- **3e GetConst + Auction/Betting/Message/Map DTOs** — unit/city/iAction const bundle; Auction bidder/highestBid/host/remainPoint/logs; Betting market/candidates; Message MsgTarget/option block; Map fog (spyList)/shownByGeneralList/city state·supply·region.

### WAVE 4 — read-page output parity (consumes WAVE 3) 🟠
*Source: FE_OUTPUT_READ_GAP.md / FE_STRUCTURE_GAP.md.*
- **4a mojibake fix** — 무력 label at `page.tsx:92` + `rankings/generals:59` (trivial, do first).
- **4b render the enriched fields** — main general/nation cards, gauge bars, permission-tiered general list.
- **4c missing read surfaces** — 내정보 log/record sections (개인기록·전투기록·전투결과·장수열전), 연감 map+nation-ranking panel, standalone user_info, cached-map page, 감찰부 battle center.

### WAVE 5 — mutation surface: chief-center + finance/npc/inherit setters 🟠
*Source: FE_OUTPUT_ACTION_GAP.md / FE_STRUCTURE_GAP.md / PARITY_LEDGER FE_MISSING(21). Backend largely ready; surface it.*
- **5a chief-center reservation editor** — the full nation-command editor: ReserveCommand grid, multi-turn select, 명령 선택 modal, bottom post selector. Foundation for 5b/5c.
- **5b expose the 21 FE_MISSING ring commands** — add the F4-C3 chief 12 + nation-internal 9 to `GENERAL_COMMAND_CODES` / chief-center catalog (logic+golden already green).
- **5c surface backend-ready setters** — nation-finance setRate/setBill/setSecretLimit (in intakeCodes, FE read-only), npc-control setter suite + drag-reorder, inherit store (buy/reset/get-more — after WAVE 2b).
- **5d diplomacy letter lifecycle** — send/rollback/destroy letter actions + REST surface (`j_diplomacy_{send,destroy,rollback}_letter` equivalents).

### WAVE 6 — domain REST surface + new-player flow 🟡
*Source: API_GAP.md. Faithful-client paths + entry flows.*
- **6a Message** — `SendMessage`/`GetContactList`/`DeleteMessage` (+ MsgTarget DTO from 3e).
- **6b Vote write** — `Vote`/`NewVote`/`AddComment` REST (intake already DONE; add domain paths).
- **6c Auction Open*** — OpenBuyRice/SellRice/Unique + bid/finish + tab split + bidList/logs FE.
- **6d General Join + BuildNationCandidate** — in-game join + 건국 candidate REST.
- **6e Command queue mgmt** — ReserveBulk/Push/Repeat handlers (after the WAVE 5a editor exists).
- **6f NPC select-pool flow** — token/pick/update-picked-general.

### WAVE 6b — entrance flows (B1–B3) ✅ DONE
*New-player entry flows completed outside the 93-command ledger.*
- **B1 장수생성 (Join)** ✅ — `MakeGeneral.draw()` draw-for-draw golden (`c6622f5`/`33d4893`);
  end-to-end variant+handler+dispatcher+intake+FE PageJoin (`d92a6db`/`5e4f045`).
- **B2 장수빙의 (NPC possession)** ✅ — Token pool (`8532064`), daemon persistence
  (`63f88c8`), claim publish (`8963773`), gateway empty-until-admin (`983e27b`).
- **B3 장수선택 (NPC selection from pool)** ✅ — Legacy token requirement (`8532064`)
  wired through the possession seam.

### WAVE 7 — per-command port long tail (PARITY_LEDGER PORT_MISSING 19) 🟡
*Source: PARITY_LEDGER.md. Run via `/parity-wave` foundation-first (registry + intakeCodes + wire variants widened once, then per-command golden→port→gate in parallel, disjoint files).*
- **7a RNG-bearing 계략 family** — ~~che_화계/파괴/탈취/선동~~ ✅ DONE (`6b5eff5`).
  Remaining: che_첩보 (moved to 7b).
- **7b military/personal** — che_첩보/단련/강행/접경귀환/숙련전환/전투태세/모반시도/전투특기초기화/내정특기초기화.
- **7c nation research family** — the 8 uniform `event_*연구` (극병/무희/상병/대검병/화시병/음귀병/산저병/화륜차/원융노병) + cr_인구이동.
- **7d trigger/accept** — che_등용수락 (P6-deferred accept-trigger).

### WAVE 8 — tournament engine (largest unported system) 🟡
*Source: LOGIC_GAP.md. `func_tournament.php` 1393 lines.*
- **8a** port processTournament/startTournament/startBetting/bracket-progression/fight-resolution to `:logic` (draw-for-draw golden).
- **8b** wire `processTournament` into the tick tail (TurnRunService, after the general drain, like processAuction).
- **8c** tournament-admin FE + register start/advance/reset (closes WAVE 2c).
- **8d** simulator parity — per-side combat inputs (crew/crewtype/train/atmos/city/items/specialties) + export-object + draw-for-draw battle log (PageBattleCenter/battle_simulator.ts).

### WAVE 9 — public read endpoints + remaining views + admin parity ✅ (구현 완료)
*Source: API_GAP.md / READ_DTO_GAP.md. Long-tail completeness. 스펙·실행: `gap/waves/WAVE_9.md`. 브랜치 `w9-public-read-endpoints`.*
- **9a** ✅ public city-list endpoint(`GET /api/cities` = `CityListController`, j_get_city_list 패러티) + fog 포함 인게임 `GetMap`(`GET /api/map` = `WorldMapController`, `getWorldMap` 충실 이식: cityList/nationList compact tuple + spyList/shownByGeneralList/myCity/myNation fog 게이트). 게이트: `WorldMapControllerTest` 4/4 · `CityListControllerTest` 2/2 · `ReadRepositoryIT` distinct-city(Docker-gated). DTO: `WorldMapDto.kt`/`CityListDto.kt`, repo: `GeneralReadRepository.findDistinctCityIdByNationId`.
- **9b** ✅ 인게임 map 페이지 `web/game/app/game/map/page.tsx`(`v_cachedMap`→Next) — `MapViewer`에 fog 오버레이(아국 장수 소재 금색 링 + 정찰 잔여개월 배지) prop 확장. typecheck 통과 + `MapViewer.interaction` 15/15 회귀-clean. gateway install page = 9c 참조(자동 시드라 마법사 불요).
- **9c** ✅ admin/install 엔드포인트(`j_install*`/`j_install_db`/`j_autoreset`/`j_raise_event`/`Global/ExecuteEngine`/`Admin/BanEmailAddress`/`Misc/UploadImage`)는 **design-replaced 확정** — Flyway 마이그레이션 + `ScenarioImporter`/`ScenarioSeedRunner`(F1) + `AdminSeeder`(F0) + 자율 daemon(수동 ExecuteEngine 불요)로 대체. **포팅하지 않음**(의도적 divergence). 이미지 업로드/이메일밴은 운영 진입 후 별 트랙.

---

## 4. Wave dependency summary

```
WAVE 0 (prod crash) ✅ DONE
WAVE 1 (seams)       ├─ correctness first (engine must not corrupt state)
WAVE 2 (no-op fix)  ─┘
        │
WAVE 3 (read DTO foundation) ──▶ WAVE 4 (read output) ──▶ WAVE 5 (mutation surface)
        │                                                       │
        └────────────────────────▶ WAVE 6 (domain REST + entry) ┘
                                          │
WAVE 6b (B1–B3 entrance) ✅ DONE
WAVE 7 (command port tail)  ◀─ independent, parallelizable via /parity-wave
  └─ 7a 계략 5종 ✅ DONE (was 7a, now closed)
WAVE 8 (tournament)         ◀─ unblocks WAVE 2c
WAVE 9 (public reads + admin)◀─ long tail ✅ DONE
```

**One-line state:** logic core gate-closed; **prod stable** (founding seam fixed, turns
advancing); entrance flows (B1–B3) complete; 계략 5종 golden-gated; read surface ~30%
and mutation surface mostly read-only/silent-no-op — fix remaining 3 seams, ship
the read DTOs, then fill the player surface command-by-command.
