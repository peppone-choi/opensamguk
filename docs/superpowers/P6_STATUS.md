# P6 Status — diplomacy / messaging / auction·betting / inheritance

_Last updated: 2026-06-01. Branch: `p6-diplomacy-auction-inheritance`._

P6 was found NOT gate-closed despite the prior "implement" commits: the branch tip had a broken
test gate + many subsystems are dead/unwired or structurally divergent from PHP. A full
evidence-based audit (6 area agents + synthesis) produced this map. **All cleanly-closable
pure-logic P6 gaps are now closed (backend 2195 tests green).** The remainder is bounded by P3
(monthly-pipeline assembly) + P7 (JDBC repos / game-api endpoints) + P8 (PHP golden capture), i.e.
infrastructure of later phases — not pure-P6 logic.

## ✅ Done this cycle (all tests green)

| Commit | Unit |
|--------|------|
| `d45fc5c` | Green the broken P6 test gate — 6 real bug categories (inheritance enum keyName parity, ObfuscatedNamePool static rewrite, AuctionType dup, `${amount}을` Hangul-identifier, CheBulgachimPagiSuak josa, spurious `@Component` on auction handlers). |
| `84d5d84` | `TurnDaemonCommandDispatcher` — routes drained intake commands (AuctionBid/Finalize) to handlers; `readCommands` result was previously discarded. Dropped speculative `suspend`. |
| `644cfc3` | `BettingActions` event-leaf registrar (OpenNationBetting/FinishNationBetting resolvable by name → no monthly-dispatch crash). |
| `9618701` | Missing diplomacy proposal commands `che_종전제의` (CheJongjeonjeui) + `che_불가침파기제의` (CheBulgachimPagijeui) + CommandRegistry registration. |
| `62547d9` | BuyHiddenBuff cumulative-DIFFERENCE cost (`points[level]-points[prevLevel]`, direct index) + already-purchased/higher-grade guards. Fixed the absolute-cost + `level-1` off-by-one (L1 was unbuyable). |
| `b1ec053` | Fold inherit-buff module pair into GeneralActionModuleFactory slot #7 (was an identity stub → buffs were silently dropped from the stat fold). |

**Step 2 (KV persistence channel) was already complete** (pre-existing, audit was wrong): `recordKv`
(delete-on-null/last-write-wins) + `kvDirty` + `FlushPayload.kvWrites`/`inheritanceKvWrites` +
`JdbcFlushExecutor` step-10/11 + Flyway `nation_env`(V3)/`game_kv`(V7) + `GameKvFlushIT`.
`ProcessNationCommand` already routes KV via `recordKv`.

## ⬜ Remaining — bounded by P3 / P7 / P8 (NOT pure-P6 logic)

### P3-coupled — monthly-pipeline engine assembly (the keystone)
The engine never assembles `EventActionFactory → EventDispatcher → MonthlyPipeline → TurnDaemonLifecycle.runMonth`.
~9–12 world event leaves (`UpdateNationLevel`, `AssignGeneralSpeciality`, `ProcessIncome`,
`ProcessWarIncome`, `RaiseDisaster`, `RandomizeCityTradeRate`, `UpdateCitySupply`, `ProcessSemiAnnual`,
`MergeInheritPointRank`) are daemon-seam stubs that throw `NotImplementedError` — their world-context
bindings (some have GREEN pure cores) must land before the pipeline can run. **Until this closes, the
monthly tick — and therefore betting OPEN, auction registration, the diplomacy month processor — does
not execute at runtime.** This is P3-completion scope.

### P7-coupled — repos / API / runtime
- **Auction** (step 4): `AuctionBidHandler`/`AuctionFinalizeHandler` are TODO shells (no repos — fetch/
  persist are NOPs). Needs Auction/AuctionBid repositories, `registerAuction` RngConsumer supplied by a
  concrete `PostUpdateMonthly`, an `AuctionExpiryDaemon` (auctions never close), and a game-api bid
  endpoint + precheck. `ObfuscatedNamePool.genObfuscatedName` is correct but has no caller yet (wire in
  the bid flow).
- **Betting** (step 5): the Kotlin betting subsystem is STRUCTURALLY DIVERGENT from PHP (CloseCondition/
  BettingType vs PHP nationCnt/candidates; no `ng_betting` bet-row model; `Betting.bet/calcReward/
  giveReward` (~436 PHP lines) unported; FinishNationBetting payout is a repo TODO). Full rework =
  bet-row model + BettingInfo realignment + calcReward + payout + PlaceBet command/handler + betwin/
  betwingold rank updates (closes the inheritance-betting data flow).
- **Messaging runtime** (step 3b, task #9): proposal/declaration commands never `sendMessage()` (the
  resolve-context split — `GeneralActionResolveContext` has no message sink, `NationActionResolveContext`
  does — must be unified). `DiplomaticMessage` must become a `Message` subclass (the two messaging models
  `logic/message` vs `logic/messaging` need unifying) with agree/decline that run the accept command via
  CommandRegistry + send confirmations + invalidate — an API accept endpoint. P7-overlapping.

### P8-coupled — the gate (step 7)
- Diplomacy multi-scope logs + josa completeness, `CheJongjeonSuak` `SetNationFront` / `CheBulgachimSuak`
  `resp_assist` side-effects: align to PHP, then verify via PHP-golden replay (Docker capture) — the gate.
- Golden ITs: messages, auction detail JSON byte-parity, betting lifecycle, `registerAuction` RNG-draw
  sequence.

## Tracked tasks
`#4` auction · `#5` betting · `#7` parity-gate · `#8` P3 pipeline assembly · `#9` messaging runtime.
