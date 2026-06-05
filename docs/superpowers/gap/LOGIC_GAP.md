# LOGIC_GAP.md — non-command game-system parity audit (PHP grand truth → Kotlin/engine)

> Scope: the game-LOGIC SYSTEMS that are NOT individual reservable commands (those are in
> `PARITY_LEDGER.md`). Audited dimensions: battle engine, monthly tick/settlement, NPC AI, events,
> diplomacy resolution, betting, auction, inheritance/succession, tournament, vote, messaging/mailbox,
> item/specialty effects, nation-level/city mechanics, and the **FOUNDING created-set through the LIVE
> DAEMON** (the headline gap).
>
> PHP grand truth = `legacy/devsam-core/hwe/sammo/` + `legacy/devsam-core/hwe/func_*.php`.
> Status legend: **COMPLETE** (logic + live-daemon wiring + golden) · **PARTIAL** (logic ported but a
> live-daemon seam / sub-path missing) · **MISSING** (no Kotlin equivalent of the system path).
>
> **The single most important finding:** several systems pass their GOLDEN tests at the *logic-draft*
> level but are **NEVER read by the live daemon** — the golden proves the resolver, not the production
> mutation path. The founding created-set is the worst case (passes `FoundingGoldenTest`, no-ops live).

---

## Totals

| Metric | Count |
|---|---|
| Systems audited | **16** |
| COMPLETE | **6** |
| PARTIAL | **7** |
| MISSING | **3** |
| Live-daemon-vs-golden divergences (golden-green, prod-broken) | **4** |

---

## 0. THE FOUNDING-DAEMON SEAM (headline gap) — PARTIAL → effectively MISSING live

**PHP:** `hwe/sammo/Command/General/che_거병.php:25-186` (canonical created-set), `che_건국.php`,
`cr_건국.php`, `che_무작위건국.php`. A created-set command INSERTs a brand-new nation + a diplomacy PAIR
per existing nation + the nation's 24 reserved `nation_turn` rows, then JOINs the actor as lord.

**Kotlin logic (CORRECT):**
- `logic/.../actions/founding/CheGeobyeong.kt:68-168` — `resolve()` populates
  `draft.createdNations` (1) + `draft.createdDiplomacy` (2×other-nations) + `draft.createdNationTurns`
  (24: officer_level [12,11] × maxChiefTurn) + the JOIN transition on `draft.general`.
- `GeneralActionResolveContext.kt:36-38` declares the three created-set lists on `GeneralActionDraft`.
- `FoundingGoldenTest.kt:56-58` asserts the draft emits exactly 1 nation / 4 diplomacy / 24 nation_turn
  rows — **byte-matched against the PHP golden. The logic is COMPLETE and proven.**

**THE GAP — the live daemon never reads the created-set:**
- `ReservedTurnHandler.handle()` (`app/game-engine/.../turn/ReservedTurnHandler.kt:145-269`) is the sole
  per-general turn driver. It diffs/applies **ONLY** `draft.general` (`diffGeneral` + `applyGeneralDirtyFree`),
  `draft.city` (`diffCity` + `applyCityDirtyFree`), and `draft.cascadeDiplomacy` (lines 246-251). It
  **never** touches `draft.createdNations`, `draft.createdDiplomacy`, `draft.createdNationTurns`,
  `draft.cascadeGenerals`, or `draft.cascadeCities`.
- `InMemoryTurnWorld` has **NO public API to register a created nation/diplomacy from a draft.**
  `updateNation`/`updateDiplomacy` both early-return null if the row does NOT already exist
  (`InMemoryTurnWorld.kt:146-150`, `:153-162`). The `createdNationIds`/`createdDiplomacyKeys` sets
  (`:44`, `:46`) are populated ONLY by boot/snapshot/seed paths — there is no `createNation()` mutator
  (contrast `createGeneral()` at `:93-98`, which DOES exist).
- `ChangeRecorder` has `diffNation`/`diffDiplomacy` (mutate-EXISTING) + `markGeneralDeleted` only — **no
  created-nation / created-diplomacy / created-nation-turn recording method** at all.
- The flush INFRA *does* support it: `DatabaseHooks.kt:83/107/164/207-208` flushes `createdNations` +
  `createdNationTurns` via `FlushOp.Verb.CREATE_MANY`; `DirtyState.kt:139/152` carries them. The plumbing
  exists from the world's drain onward — the missing link is **draft → world created-set → recorder**.

**Net effect (PROD):** a human or NPC running `che_거병` / `cr_건국` / `che_무작위건국` in the live daemon:
the actor's own JOIN (nation_id=newNationId, officer_level=12) is applied via `applyGeneralPatch`, but
the **new nation row, its diplomacy pairs, and its 24 chief-turn rows are silently dropped.** The general
ends up "belonging" to a nation id that does not exist in the world or DB → a dangling-FK / orphan-lord
state. `che_건국`/`cr_건국` (wandering-nation → real-nation promotion, which mutates an EXISTING nation
row) is LESS broken than `che_거병` (true INSERT), but both still drop the cascade member/city rewrites.

**Fix scope (the seam to build):** (1) `InMemoryTurnWorld.createNation(Nation)` +
`createDiplomacy(Diplomacy)` + `reserveNationTurn(NationTurn)` mutators that add to the created-sets;
(2) `ChangeRecorder.recordNationCreated/recordDiplomacyCreated/recordNationTurnReserved`; (3) a block in
`ReservedTurnHandler.handle()` (after the general/city apply) that drains
`draft.createdNations/createdDiplomacy/createdNationTurns` + `draft.cascadeGenerals/cascadeCities` into
those mutators. The `newNationId` preload (the insertId placeholder, `CheGeobyeong.kt:70`) must be
threaded from the adapter (a fresh in-memory id reconciled at flush) — today no caller supplies it, so
`resolve()` would `error("거병 requires a preloaded newNationId")` if it ever ran live. **Sibling
created-set commands `che_건국`/`cr_건국`/`che_무작위건국` share the exact same unwired seam.**

---

## 1. Battle engine (processWar) — COMPLETE

**PHP:** `hwe/process_war.php` + `hwe/sammo/WarUnit*.php` + `WarUnitTrigger/` (37 trigger classes) +
`GeneralTrigger/` (4).
**Kotlin:** `logic/war/ProcessWarNG.kt` + `ProcessWar.kt` + `WarUnit*.kt` + `war/trigger/triggers/`
(35 trigger files) + `war/specialty/`.
**Status:** Gate-closed (P4 G1 draw-for-draw). The WHOLE fight runs on ONE `RandUtil(warSeed)`. All 37
PHP `WarUnitTrigger` classes + the 4 `GeneralTrigger` classes have Kotlin counterparts. ConquerCity +
city-conflict + battle items/specialties ported. **No gap.** (`che_출병` is the sole OUTER caller, DONE
in the ledger.)

---

## 2. Monthly tick / settlement — PARTIAL

**PHP:** `TurnExecutionHelper.php:393-518` `executeAllCommand()` orchestrates the master tick:
`monthlyRng = RandUtil(serialize(hiddenSeed,'monthly',year,month))` → PreMonth event →
`preUpdateMonthly()` → `turnDate` → **`checkStatistic()` (month==1)** → Month event →
`postUpdateMonthly($monthlyRng)` → `executeGeneralCommandUntil` → **`processTournament()`** →
**`processAuction()`**.
**Kotlin:** `logic/tick/MonthlyPipeline.kt:89-117` (`runMonth`) faithfully ports the
PreMonth→preUpdate→checkStatistic→Month→postUpdate interleave with the correct OLD/NEW date semantics +
the single month-scoped RNG reaching only `postUpdateMonthly`. `PreUpdateMonthly.kt` + `PostUpdateMonthly.kt`
(Q1-Q17, 72 step lines) + all 9 world-event leaves ported (P3). Wired into the live daemon via
`TurnRunService.kt:139-153` (`runMonth`) + `TurnDaemonLifecycle.MonthBoundaryDriver`.

**Gaps:**
- **`checkStatistic` is a NO-OP STUB live.** `EngineEventConfig.kt:75` wires `checkStatistic = CheckStatistic { }`
  — the year-boundary per-nation statistics computation (`checkStatistic()` in PHP) does nothing in prod.
  The PIPELINE calls it correctly (`MonthlyPipeline.kt:111`), but the injected impl is empty. **PARTIAL.**
- **`processTournament()` is NOT wired into the tick tail.** PHP runs it every `executeAllCommand` after
  the general drain. The 1393-line `func_tournament.php` bracket/phase engine has **no live-daemon
  equivalent** (only the enroll handler exists, see §10). **MISSING from the tick.**
- `processAuction()` IS wired: `TurnRunService.kt:131` `auctionExpiryDaemon?.checkExpiredAuctions(...)`.

---

## 3. NPC AI — COMPLETE (live-wired)

**PHP:** `hwe/sammo/GeneralAI.php` + `AutorunGeneralPolicy.php` + `AutorunNationPolicy.php`.
**Kotlin:** `logic/ai/*` (18 files) + engine `AiTurnAdapter.kt`.
**Status:** Gate-closed P5 (174/174 live-selection turns). **AND it is wired in prod:**
`DaemonLoopConfig.kt:129-173` builds `AiTurnAdapter`, passes `aiHook = ai.chooseGeneralTurn` to the
handler and `chooseNationTurn = ai.chooseNationTurn` + `beginGeneralTurn` to the lifecycle. The
nation-pass-before-general ordering and shared per-general decision RNG stream are honored. Documented
backlog: long-sim multi-turn (gate dim c), G12 nation reserved-fail deny-log; quarantines (genfound-방랑군,
`chooseInstantNationTurn` zero-callers). **No NEW gap beyond the documented backlog.**

---

## 4. Events (DSL / static / dynamic) — COMPLETE

**PHP:** `Event/` (Engine/EventHandler/Action/Condition) + `StaticEvent/` (2 leaves) + `BaseStaticEvent.php`.
**Kotlin:** `logic/event/*` (`EventDispatcher`/`EventCondition`/`EventAction`/`EventCodec`/`StaticEventHandler`/
`WorldActions`) + `EventActionFactory`. Both PHP static-event leaves (`event_부대발령즉시집합`,
`event_부대탑승즉시이동`) covered. Dispatcher wired into `MonthlyPipeline` PreMonth/Month. **No gap.**

---

## 5. Diplomacy resolution — PARTIAL

**PHP:** diplomacy state machine + per-month term decrement (`postUpdateMonthly`) + the proposal/accept
messaging (`DiplomaticMessage.php`).
**Kotlin:** `logic/diplomacy/DiplomacyState.kt` + `DiplomacyMonthProcessor.kt`; proposals via
`messaging/DiplomaticMessage.kt` + `DiplomaticMessageController` (P6/P7). Accept/decline cascade applied
in `ReservedTurnHandler.kt:246-251` (`cascadeDiplomacy`) and `ProcessNationCommand` `diplomacyDeltas`.

**Gap:** `DiplomacyMonthProcessor.kt` exists but is **NOT wired into the monthly tick** — it has no caller
in `PreUpdateMonthly`/`PostUpdateMonthly`/engine (grep: only self-referenced). The per-month diplomacy
**term countdown / auto-expiry** therefore does not run live (terms never tick down → 불가침/정전 never
auto-expires). **PARTIAL.** (The accept/decline transition path IS wired and correct; only the periodic
term-decrement is orphaned.)

---

## 6. Betting — PARTIAL

**PHP:** `hwe/sammo/Betting.php` + `func_tournament.php` `startBetting/giveReward`.
**Kotlin:** `logic/betting/BettingEngine.kt` (calcReward/giveReward) + `BettingInfo.kt` +
`event/OpenNationBetting.kt` + `event/FinishNationBetting.kt` + `event/BettingActions.kt`; engine
`betting/PlaceBetHandler.kt` (gold deduction + ng_betting INSERT) + `ChangeRecorder` betting channel +
`JdbcFlushExecutor` flush step (P6/P7).
**Gap:** the **nation-betting open/finish event leaves exist in logic** but the TOURNAMENT-betting open
(`startBetting`, `func_tournament.php:341`) + reward payout are bound to `processTournament` (§10), which
is not wired into the live tick → tournament betting never opens/settles live. Player `placeBet` intake +
nation-betting open/finish ARE wired. **PARTIAL (tournament-betting half).**

---

## 7. Auction — COMPLETE (live-wired)

**PHP:** `hwe/sammo/Auction.php` + `AuctionBasicResource/BuyRice/SellRice/UniqueItem.php` + `func_auction.php`.
**Kotlin:** `logic/auction/*` (AuctionBase/Detail/Dto/ResultCalculator/BidValidator/ObfuscatedNamePool/
DummyGeneral) + engine `auction/AuctionBidHandler.kt` + `AuctionFinalizeHandler.kt` + `AuctionExpiryDaemon.kt`.
**Status:** `AuctionExpiryDaemon.checkExpiredAuctions` is wired into the tick (`TurnRunService.kt:131`),
the bid intake + finalize + flush channel exist (P6/P7). The four auction subtypes have logic ports.
**No gap** (note the FE `auction/page.tsx` posts `auction_bid` vs intake `auctionBid` — that mis-case bug
is tracked in `PARITY_LEDGER.md`, not here).

---

## 8. Inheritance / succession — PARTIAL (two distinct subsystems; only one is the gap)

**8a. Inheritance POINTS (collapse-exp, buff slots, hidden-buff, random-unique) — COMPLETE.**
PHP `InheritancePointManager.php` + `TriggerInheritBuff.php`. Kotlin `logic/inheritance/*` (24 files:
`InheritancePointManager`/`Math`/`Calculator`/`Store`/buff modules/`BuyHiddenBuffAction`/`BuyRandomUniqueAction`/
`MergeAndApply`). Gate-closed P6 (keyName parity + buff fold slot #7 + cumulative-diff cost +
`MergeInheritPointRank` monthly leaf). **No gap.**

**8b. RULER SUCCESSION (heir promotion + nation deletion) — MISSING live.**
PHP `func.php:1807 nextRuler()` + `func.php:1713 deleteNation()` — when a ruler (officer_level 12) dies
with no heir, the nation is deleted (cascade); with an heir, the heir is promoted. **`ReservedTurnHandler`
declares a `nextRuler` hook (`ReservedTurnHandler.kt:72`) but PROD wires it to the DEFAULT NO-OP**
(`DaemonLoopConfig.kt:141-147` constructs the handler WITHOUT passing `nextRuler` or `dyingMessage`). There
is **no `nextRuler`/`deleteNation` port in `logic/` at all** (grep: zero). So when a ruler dies in the
live daemon: the kill tombstone + officer_level=1 demotion runs, but **no heir is promoted and no nation
deletion cascade fires** → a nation can be left rulerless/zombie. The RNG-selected dying-message variant
pool is also defaulted to the single byte-exact `$defaultMessage` (the variant draw is unwired). **MISSING
(succession) + PARTIAL (dying-message variants).**

---

## 9. Vote — PARTIAL

**PHP:** `hwe/sammo/API/Vote/*` (Vote/GetVoteList/NewVote/GetVoteDetail) + `DTO/VoteInfo.php` +
`VoteComment.php` + `v_vote.php`.
**Kotlin:** `logic/actions/vote/VoteLottery.kt` + engine `intake/VoteHandler.kt`.
**Gap:** only the vote-LOTTERY (RNG reward draw) + a vote intake handler exist. The vote CRUD API surface
(create vote / list / detail / comment) — `NewVote`/`GetVoteDetail`/`VoteComment` — has **no read/intake
controller port** (the `C2-rest` slice notes vote as "last, RNG golden needed"). FE `vote/page.tsx` is
read-render only. **PARTIAL (lottery done; create/comment CRUD missing).**

---

## 10. Tournament — MISSING (engine), PARTIAL (enroll)

**PHP:** `func_tournament.php` (1393 lines): `processTournament/startTournament/startBetting/fillLowGenAll/
runTournament/printFighting` — the full bracket engine (16-general groups, phase progression, fight
resolution, betting integration).
**Kotlin:** `logic/actions/intake/TournamentEnroll.kt` + engine `intake/TournamentEnrollHandler.kt` ONLY.
**Gap:** the **entire tournament-RUNNING engine is unported** — no `processTournament`, no bracket
progression, no fight resolution, no `startBetting`. Only enrollment exists. The FE `tournament-admin/page.tsx`
posts `tournament_start/advance/reset` to UNREGISTERED codes (silent no-op, tracked in `PARITY_LEDGER.md`).
This is the single largest unported logic SYSTEM. **MISSING.**

---

## 11. Messaging / mailbox — COMPLETE (with one quarantine)

**PHP:** `hwe/sammo/Message.php` + `MessageTarget.php` + `ScoutMessage.php` + `RaiseInvaderMessage.php` +
`DiplomaticMessage.php` + `func_message.php`.
**Kotlin:** `logic/message/*` (Message/MessageType/MessageTarget) + `logic/messaging/*`
(MessageFactory/Store/Draft/SendMessage/DiplomaticMessage/MailboxConstants). Message routing wired into
`ReservedTurnHandler.routeMessage` (`:503-523`, receiver-row-before-sender) + `ProcessNationCommand` +
flush message channel. FE `mailbox/page.tsx` read-render. **No structural gap.** (`che_등용수락`/decline
mailbox-accept flow deferred — tracked in the command ledger, not here.)

---

## 12. Item / specialty effects — PARTIAL

**PHP:** `ActionItem/` (161 classes), `ActionSpecialDomestic/` (30), `ActionSpecialWar/` (21),
`ActionCrewType/`, `ActionPersonality/`, `ActionScenarioEffect/`.
**Kotlin:** `logic/items/*` (3 files: `ItemModules`/`ItemHooks`/`WarItemModules`) +
`logic/war/specialty/*` (registries + stat/multiplier modules + injected triggers).
**Coverage strategy (sound):** the 161 PHP items are dominated by `+stat` 명마/무기/서적/능력치 classes
covered GENERICALLY by `BaseStatItemModule` (parses category+value+name from the class-name token, zero
per-class code) — so the bulk is COMPLETE by construction. War specialties: `SpecialWarRegistry.kt`
registers 21 (matches PHP `ActionSpecialWar/`).
**Gap:** of the **79 non-stat special items** (계략/격노/공성/농성/간파/반계/사기/의술/약탈/위압/저격/부적…),
`ItemHooks.kt` registers only a **curated subset (~15)** with bespoke hooks (the items reachable in
scenario_1010 + battle-relevant ones: 삼략/납금박산로/주판/동작/평만지장도/두강주류/변도론/백상/기주마/맥궁/
비도/태현청생부 + the 5 `event_전투특기_*` aliases). The remaining non-stat special items (e.g.
`che_의술_청낭서`, `che_농성_위공자병법`, `che_공성_묵자`, `che_반계_백우선`, `che_사기_*`) have **no
hook port** → if equipped, their special effect is silently inert. `ActionSpecialDomestic/` (30) and
`ActionPersonality`/`ActionScenarioEffect` coverage is partial/per-need. **PARTIAL** (acceptable for
scenario_1010 reachability, but a real gap for arbitrary item assignment).

---

## 13. Nation-level / city mechanics — COMPLETE

**PHP:** `UpdateNationLevel` + `UpdateCitySupply` + `RandomizeCityTradeRate` + city supply/front-state.
**Kotlin:** `logic/world/UpdateNationLevel.kt` + the monthly leaves (`UpdateCitySupply`,
`RandomizeCityTradeRate` ported in P3). Nation level 0-9 is an INTENTIONAL divergence from legacy 7-level
(documented, not a parity violation). City level conventions (lv=4 이민족-only, lv=5 한족 군 치소)
honored. **No gap.** (Note: the officer-rank 9-level extension is intentional.)

---

## 14. Disaster / income / semi-annual — COMPLETE

`logic/world/RaiseDisaster.kt` + `ProcessIncome.kt` + `ProcessSemiAnnual.kt` + `ProcessWarIncome` +
`AssignGeneralSpeciality` — all 9 world-event leaves ported and wired into `PostUpdateMonthly` (P3, zero
stubs). **No gap.**

---

## 15. Restart / rehydrate (lossless) — PARTIAL

**Kotlin:** `engine/turn/RehydrateService.kt` + `boot/WorldSnapshotLoader.kt` + `boot/ScenarioSeedRunner.kt`.
**Gap (documented roadmap):** the "restart-rehydrate lossless gate" is open (P6 P8-coupled remainder).
Tied to the founding seam (§0): created nations that DO flush must round-trip through rehydrate; until
§0 is fixed there is no live created-nation to test the round-trip with. **PARTIAL.**

---

## Live-daemon-vs-golden divergence summary (golden-green, prod-broken)

These pass tests at the logic-draft level but the LIVE DAEMON drops or never invokes the path:

1. **Founding created-set (§0)** — `FoundingGoldenTest` green; `ReservedTurnHandler` never reads
   `draft.createdNations/createdDiplomacy/createdNationTurns/cascade*`. Worst case (new nation vanishes).
2. **Ruler succession (§8b)** — `nextRuler`/`dyingMessage` hooks DEFAULTED to no-op in `DaemonLoopConfig`;
   no `nextRuler`/`deleteNation` logic port. Ruler death leaves a zombie nation live.
3. **Diplomacy month term-decrement (§5)** — `DiplomacyMonthProcessor` exists, no tick caller. Treaties
   never auto-expire live.
4. **`checkStatistic` (§2)** — pipeline calls it, but `EngineEventConfig` injects an empty lambda. Year
   statistics never computed live.

(Tournament §10 is not "golden-green prod-broken" — it has no golden either; it is simply unported.)
