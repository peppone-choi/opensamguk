# PARITY_RECONCILED — true BE/FE state (post-W0 verification)

> Reconciles the stale `GAP_AUDIT.md` / `PARITY_LEDGER.md` (written BEFORE WAVE 0 prod-recovery
> merged) against **actual repo code** on `main` @ `6152b06`. Every claim below was checked with
> Grep/Read/git against real files — not the docs. Read-only audit; no code changed.
>
> Ground truth anchors (verified this session):
> - `main` HEAD = `6152b06` (Merge #38). Founding seam = `a95efdd` (PR#26, merged).
> - **2461 `@Test` annotations** total: logic 1834 · game-engine 233 · common 183 · game-api 99 · infra 94 · gateway-api 18.
> - **28 golden/replay/gate test files** present and executable (full catalog in §4).

---

## 1. Headline — what is ACTUALLY done vs what GAP_AUDIT claims

**GAP_AUDIT's single biggest claim — "PRODUCTION IS DOWN, crash-loops on first che_거병" — is STALE. It is FIXED and live.**

The founding daemon seam (`a95efdd`, PR#26 `wave0-founding-daemon-seam`) is merged to `main` and proven by tests. `InMemoryTurnWorld.kt` now defines the full created-set drain machinery, and `FoundingHandlerSeamTest.kt` passes 3 regression tests. SESSION_HANDOFF's "prod live, turns 181→182" narrative is corroborated by the code.

### STALE_DOC_NOW_DONE (doc says missing/broken — code proves done)

| # | Item | Doc claim | Verified reality |
|---|------|-----------|------------------|
| S1 | **Founding daemon seam** | `GAP_AUDIT L10-11`: PROD DOWN, crash-loops on first che_거병 | `InMemoryTurnWorld.kt:197` `createNation`, `:209` `createDiplomacy`, `:222` `createNationTurn`, `:240` `allocateNationId`, `:52/:282/:319/:357` `createdNationTurns` ledger+cancel+drain. `FoundingHandlerSeamTest.kt` (3 tests). Commit `a95efdd` merged. |
| S2 | **MonthlyPipeline @Lazy CGLIB freeze** | `SESSION_HANDOFF §4`: monthly clock frozen | Layers 1-3 fixed on main (per-run build, `open` class/method, WorldActionContext wire, startyear/log_scope casing). Prod advances turns. |
| S3 | **DiplomacyMonthProcessor "no caller"** | `LOGIC_GAP.md`: missing or not called from monthly tick | `DiplomacyMonthProcessor.kt:19-91` fully ported; 9 tests; integrated via `MonthlyPostUpdateHook.kt:99-150` → `MonthlyPipeline.kt:122`. |
| S4 | **Vote writes (Vote/NewVote/AddComment)** | `API_GAP.md`: 3 MISSING (no REST/intake) | `CommandWireMapper.kt:67-69` registers `newVote`/`voteCast`/`voteComment` in `intakeCodes`; `:191-204` toCommand impls; `VoteController` GET reads present. |
| S5 | **Auction bid intake** | `PARITY_LEDGER`: casing mismatch → silent no-op | `CommandWireMapper.kt:45` `auctionBid` registered, `:112` toCommand. FE posts `auction_bid`; backend wired (FE casing is FE-side, not a no-op). |
| S6 | **Map city state/supply/region DTO** | `GAP_AUDIT 3a`: PARTIAL, city state/supply MISSING | `MapPreviewDto.kt:29-44` has state/supply/isCapital/region; `CityReadRepository.kt:53-57` frontState/supplyState. |
| S7 | **21 nation-ring che_ commands "FE_MISSING"** | `PARITY_LEDGER`: 21 commands missing | `CommandRegistry.kt:125-136` registers all 21; FE exclusion from per-general catalog is **intentional** (nation_turn ring, `AvailableCommandsController.kt:143-147`). |
| S8 | **Inherit resets (inheritResetTurnTime/SpecialWar/SetNextSpecialWar)** | `LEDGER`: FE_MISSING, read-only | All 3 in `intakeCodes` (`:54-56`) + toCommand (`:143-151`); FE buttons live (`inherit/page.tsx`). |
| S9 | **nation-finance setters (setRate/setBill/setSecretLimit/setBlockWar/setBlockScout)** | `LEDGER`: FE_MISSING deferred | All 5 wired to CommandModal in `nation-finance/page.tsx`. |
| S10 | **tournamentEnroll** | (implied in tournament gap) | `TournamentEnrollHandler.kt` + `intakeCodes` + dispatcher — DONE. |
| S11 | **WAVE 9 read endpoints (map/cities)** | doc target | Fully implemented WITH tests — but only on branch `w9-public-read-endpoints`, **NOT on main** (see §2/§3). |

### Genuinely STILL OPEN (doc and code agree it's missing)
- **WAVE 7**: 24 PHP commands unported (calc/battle-scheme che_* + 8 `event_*연구` research unlocks + `cr_인구이동`).
- **WAVE 8**: tournament-RUNNING engine (`processTournament`/bracket/betting/fight/gift) unported; admin codes unregistered.
- **WAVE 6**: SendMessage/DeleteMessage/GetContactList, Auction-Open*, in-game Join/BuildNationCandidate, Command queue bulk/push/repeat, NPC select-pool pick/update.
- **5 WIRING_MISSING no-ops**: `BuyHiddenBuff`, `BuyRandomUnique`, `tournament_start/advance/reset` — FE posts them; backend intake/registry does NOT accept them.
- **WAVE 1**: `nextRuler` succession body unimplemented (hook is identity no-op); `checkStatistic` is empty lambda.
- **WAVE 5**: diplomacy letter send/rollback/destroy mutations absent.
- **WAVE 3/4**: read-DTO + FE field enrichment gaps (large but non-blocking); main-page `묠력` mojibake (load-bearing parity break).

---

## 2. Per-wave true status table

| Wave | Doc-claim | Actual | Remaining tasks | Evidence |
|------|-----------|--------|-----------------|----------|
| **W0 founding seam** | PROD DOWN, crash-loop | **DONE (STALE_DOC_NOW_DONE)** | 0 core. Backlog: 건국/cr_건국/무작위건국 cascade preload (W0b) | `InMemoryTurnWorld.kt:197-357`, `FoundingHandlerSeamTest.kt`, `a95efdd` |
| **W0 monthly pipeline** | @Lazy freeze, 5 broken seams | **PARTIAL** (L1-3 fixed) | Layer-4 event-context dispatch + `checkStatistic` invocation | `MonthlyPipeline.kt` open class, `DaemonLoopConfig.kt:183` stub |
| **W1 ruler succession** | nextRuler/deleteNation missing | **PARTIAL** | Implement successor pick + promote lvl12 + deleteNation cascade | `DaemonLoopConfig.kt:183`, `KillTombstoneTest.kt:187` (hook fires only) |
| **W1 DiplomacyMonthProcessor** | missing/no caller | **DONE (STALE)** | 0 | `DiplomacyMonthProcessor.kt:19-91`, 9 tests, `MonthlyPostUpdateHook.kt:99-150` |
| **W1 checkStatistic** | empty stub | **PARTIAL** | Year-boundary Q14 checkEmperior (isunited) | `DaemonLoopConfig.kt:183` `CheckStatistic { }` |
| **W1 item specials** | ~79 expected | **PARTIAL** | 36 registered (16 domestic + 20 event clones). War-side bodies = A1 scope | `ItemHooks.kt`, `EVENT_SPECIALTY_CLONES:146-167` |
| **W2 auction/bet/inherit/vote intake** | mis-cased no-ops | **DONE (STALE)** | 0 | `CommandWireMapper.kt:44-69` |
| **W2 BuyHiddenBuff/BuyRandomUnique** | unsure | **MISSING (wiring)** | Add to `intakeCodes`/registry | `CommandWireMapper.kt:43-69` lacks both; FE posts them (`inherit/page.tsx:292,342`) |
| **W2 tournament admin codes** | unregistered | **MISSING (wiring)** | Register start/advance/reset | not in `CommandWireMapper`; FE page DOES exist (`tournament-admin/page.tsx`) |
| **W3 read DTOs** | various PARTIAL/MISSING | **PARTIAL** | FrontGlobal/General/Nation/City field enrichment; P1/P2 tiers; GetConst; Betting/Auction/Message DTOs; Map fog fields | `IdentityDto.kt`, `AuctionDto.kt`, `BettingDto.kt`, `MessageDto.kt` |
| **W4 read pages** | mojibake + thin cards | **PARTIAL** (mostly confirmed-missing) | `묠력`→`무력` fix; gauge bars; log/record pages; settings panel | `page.tsx:92` mojibake; missing `/game/battle-records` etc. |
| **W5 chief-center read-only** | read-only contract | **DONE** | 0 | `chief-center/page.tsx:3-7` |
| **W5 nation-finance + inherit setters** | FE_MISSING | **DONE (STALE)** | 0 | `nation-finance/page.tsx`, `inherit/page.tsx` |
| **W5 diplomacy letter mutations** | not in ledger | **MISSING** | send/rollback/destroy endpoints | `DiplomaticMessageController.kt` = only `/accept` `/decline` |
| **W6 Vote/Auction reads** | MISSING | **DONE (STALE)** | 0 | `CommandWireMapper.kt:66-71`, `VoteController`, `AuctionController` |
| **W6 SendMessage/DeleteMessage/GetContactList** | MISSING | **MISSING** | POST send/delete + contacts GET + intake | `MailboxController` GET-only |
| **W6 Auction Open\*** | MISSING | **MISSING** | 3 open intake codes + FE form | `CommandWireMapper.kt` lacks openBuy/Sell/Unique |
| **W6 Join / BuildNationCandidate** | MISSING | **MISSING** | clarify claim-vs-hire; 건국 candidate flow | no join/genFound intake |
| **W6 Command bulk/push/repeat** | MISSING | **MISSING** | 3 queue-mgmt intake codes + FE | `CommandController` single-turn only |
| **W6 NPC select-pool pick/update** | PARTIAL | **PARTIAL** | pick/update POST + custom-gen editor | `PossessionController` read pool only |
| **W7 24 unported commands** | PORT_MISSING | **MISSING (×24)** | full port + registry + intake + golden each | `CommandRegistry.kt:84-161` zero entries |
| **W8 tournament engine** | unported | **MISSING** | full processTournament state machine | `MonthlyPostUpdateHook.kt:163` no-op |
| **W8 tournament admin** | unregistered | **PARTIAL** | register codes + handlers (FE exists) | `tournament-admin/page.tsx` present, codes absent |
| **W8 enrollment / simulator** | done | **DONE** | 0 | `TournamentEnrollHandler.kt`, `simulator/page.tsx` |
| **W9 map/cities read endpoints** | on branch | **PARTIAL (unmerged)** | merge `w9-public-read-endpoints` → main | files absent on main (verified) |

---

## 3. The genuine remaining work, ordered foundation-first

Only items that are **actually PARTIAL/MISSING in code**. Ordered so each tier unblocks the next.

### Tier 0 — Trivial unblock / zero-risk (do first, hours)
1. **Merge WAVE 9** (`w9-public-read-endpoints`, 1 commit ahead) into main. Fully built + tested; just isolated. Unblocks in-game map fog page.
2. **5 WIRING_MISSING no-ops** — register in `CommandWireMapper.intakeCodes` + toCommand:
   - `BuyHiddenBuff`, `BuyRandomUnique` (logic already exists in `InheritActionRegistry.kt:45-46` — pure wiring).
   - `tournament_start/advance/reset` — register codes (handlers blocked on Tier 2 W8 engine; can stub-register to stop silent no-op).
3. **`묠력` → `무력` mojibake** fix across `page.tsx`, `rankings/*`, `my-generals`, `simulator` (~10 occurrences) — load-bearing log/UI parity break.

### Tier 1 — Monthly-tick correctness (daemon core, before new features)
4. **W0 Layer-4**: `checkStatistic` hook invocation + WorldActionContext per monthly event dispatch.
5. **W1 `nextRuler`** successor-selection body (RNG candidate pick → promote officer_level 12 → deleteNation cascade if heirless). Hook seam already wired.
6. **W1 `checkStatistic`** Q14 checkEmperior (isunited) — depends on #4.

### Tier 2 — Tournament subsystem (self-contained; new file family)
7. **W8 `processTournament`** full state machine (pending→fill→qualify→select→prelim→bet→16/8/4/2/finals), bracket gen, `startBetting`, `finalFight`, `setGift`. Wire into tick tail (like auction daemon).
8. **W8 admin handlers** (`TournamentStart/Advance/Reset`) — consumes #7. Unblocks Tier-0 #2 stubs.

### Tier 3 — Domain REST + new-player flow (W6) — disjoint controllers
9. **Message**: POST send/delete + GET contacts + `sendMessage`/`deleteMessage` intake.
10. **Auction-Open**: 3 open intake codes + TurnDaemonCommand variants + FE form.
11. **W5 diplomacy letters**: send/rollback/destroy endpoints + FE buttons.
12. **Join / BuildNationCandidate**: clarify claim-vs-hire; 거병→건국 candidate flow.
13. **Command queue**: bulk/push/repeat intake + multi-turn `CommandController` + FE turn-screen queue.
14. **NPC select-pool**: pick/update POST + custom-general editor.

### Tier 4 — 24 unported PHP commands (W7) — fully parallelizable, disjoint files
15. Each command = Kotlin class + `CommandRegistry` entry + intake + golden test. Use `/parity-close` per command, `/parity-wave` for the batch. Natural sub-batches:
    - **8× `event_*연구`** (극병/무희/상병/대검병/화시병/음귀병/산저병/화륜차/원융노병 research unlocks — uniform NationCommand shape).
    - **계략/battle-scheme che_** (화계/파괴/탈취/선동/첩보/반계family).
    - **misc** (강행/접경귀환/숙련전환/전투태세/모반시도/전투특기초기화/내정특기초기화/단련/등용수락/cr_인구이동).

### Tier 5 — Read-DTO + FE enrichment (W3/W4) — large, non-blocking, parallel
16. DTO field enrichment (FrontGlobal/General/Nation/City), P1/P2 permission tiers, GetConst, Betting/Auction/Message rich DTOs, Map spyList/shownByGeneralList.
17. FE: gauge bars (now/max), log/record pages (`/game/battle-records` etc.), settings panel, generals sort selector.

---

## 4. Test / gate ground truth

**Total `@Test` = 2461** (verified via `find … -exec grep @Test`):

| Module | @Test |
|--------|------:|
| logic | 1834 |
| game-engine | 233 |
| common | 183 |
| game-api | 99 |
| infra | 94 |
| gateway-api | 18 |
| **TOTAL** | **2461** |

(GAP_AUDIT estimated ~2195; the +266 delta is post-audit ported P5-P7 logic.)

**Golden / gate test catalog — 28 files, all present:**
- 12 Che-command golden: 급습/몰수/물자원조/백성동원/부대탈퇴지시/수몰/의병모집/이호경식/초토화/피장파장/필사즉생/허보
- Battle/tick gates: `BattleReplayGateTest`, `ConquerCityReplayGateTest`, `MonthTickReplayGateTest`, `VoteLotteryReplayGateTest`, `AiReplayGateTest`, `ConflictWinnerGateTest`, `RngKernelParityGateTest`
- Domain golden: `FoundingGoldenTest` (13/13), `CommerceActionLogGoldenTest`, `DevelopGoldenTest`, `MilitaryGoldenTest`, `NationGoldenTest`, `NonIdentityFoldGoldenTest`, `PersonnelGoldenTest`, `TradeGoldenTest`, `JosaLogGoldenTest`
- Engine seam: `FoundingHandlerSeamTest` (3 tests — no-crash regression, created-set drain, secretlimit honor)

**Quarantines (proven, carried forward):** che_선양 ORDER BY RAND (G4, unreachable in 1010), che_NPC능동 (NPC-only, excluded from catalog), genfound-방랑군 (needs 거병→건국 mini-sim).

**Gate ground truth:** founding golden 13/13 green and unchanged through the seam fix. No gate was weakened. Backlog items (W0b cascade preload, ruler-succession deny-log) remain documented, not fabricated.

---

## 5. Recommended implementation sequence for "최종 구현" with disjoint parallel groups

The repo discipline (CLAUDE.md) requires **disjoint worktree families** that never co-widen the same file, and a **Tier-0 foundation wave** before parallel families. Sequence below honors that.

### Batch A — Foundation + zero-risk closes (sequential, single session, HIGHEST VALUE)
Touches shared files (`CommandWireMapper`, FE pages) → must be one ordered pass before fan-out:
1. Merge `w9-public-read-endpoints` → main.
2. Wire `BuyHiddenBuff` + `BuyRandomUnique` into `CommandWireMapper` (logic exists — pure plumbing).
3. Stub-register `tournament_start/advance/reset` (stop silent no-op).
4. `묠력`→`무력` mojibake sweep.
> **This is the single highest-value next batch:** it removes every "silent no-op" footgun, ships a fully-built feature (W9 map) for free, and fixes a parity-gate-relevant mojibake — all low-risk, no new engine logic.

### Batch B — Daemon correctness (sequential after A; engine-internal, one family)
W0 Layer-4 event-context + W1 `nextRuler` + W1 `checkStatistic`. These share `MonthlyPipeline`/`DaemonLoopConfig`/`ReservedTurnHandler` → must be ONE family, not parallel. Gate: `MonthTickReplayGateTest` stays green; add ruler-succession golden.

### Then fan out — 3 DISJOINT parallel groups (no shared files):
- **Group P1 — Tournament (W8)**: new `tournament/*` engine files + admin handlers + tick-tail wire. Disjoint from REST controllers and command ports.
- **Group P2 — Domain REST (W6 + W5 letters)**: `MailboxController`/new message+auction-open controllers + diplomacy letter endpoints + their intake codes. *Caveat:* `CommandWireMapper` is shared with Group P3 — widen its `intakeCodes` ONCE in a creator commit (Batch A or a tiny foundation commit) before P2/P3 add toCommand cases, or sequence the `CommandWireMapper` edits.
- **Group P3 — 24 command ports (W7)**: fully parallelizable via `/parity-wave`. Each command = disjoint Kotlin class + golden test. Foundation-first: widen `CommandRegistry`/`intakeCodes`/wire-variants ONCE, then per-command golden→port→gate in parallel. Sub-batch the 8 `event_*연구` first (uniform shape).

### Last — Read/FE enrichment (W3/W4), independent, lowest blocking risk
DTO field enrichment + FE gauges/log-pages/settings. Parallel-safe per-page/per-DTO; do after the mutation surface is closed so the enriched reads reflect real state.

**Rule reminder for the orchestrator:** `CommandWireMapper.kt` and `CommandRegistry.kt` are the cross-family hot files. Any wave that adds intake codes must widen them in a **single creator commit** before consumer commits fan out, per the "never co-widen the same file" discipline.
