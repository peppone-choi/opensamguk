# P5 G-GATE — Draw-for-draw divergence catalog (GT3, first real pass)

**GRAND TRUTH:** `legacy/devsam-core/hwe/sammo/GeneralAI.php` (PHP). The golden is captured from a REAL PHP run
(`tools/php-golden/capture_ai.php` over the live installed scenario-1010 DB). NEVER fabricate a golden value,
NEVER weaken a test, NEVER edit a golden to make Kotlin pass.

**Harness (kernel replay):** `logic/src/test/kotlin/opensamguk/logic/golden/AiReplayGateTest.kt` — GREEN (5/5 tests, 0 failures).
**Harness (LIVE selection — GT3-engine):** `app/game-engine/src/test/kotlin/opensamguk/engine/golden/AiSelectionGateIT.kt` — **GREEN (174/174 turns, 667/667 live draws value+cursor)**.
**Golden:** `logic/src/test/resources/golden/p5/ai-turn-000.json .. ai-turn-173.json` + `npc-census-1010.json` (GT1) + `world-1010.json` (GT1b pre-turn world snapshot).
**Manifest:** `tools/php-golden/manifest_ai.json` (scenario 1010, year 181 month 1, hiddenSeed `71adaa4df4012a20c0883beba4810681`).

---

## ✅ GT3-ENGINE RESOLUTION — the GAP-WORLD blocker is CLOSED, the live-selection gate is GREEN

The GAP-WORLD gap documented below (the live-selection dimension blocked on the un-banked world + the missing
engine adapter in `:logic`) is **RESOLVED**. Both blockers are gone:
1. **World fixture exists** — `world-1010.json` (GT1b) banks the exact scenario-1010 pre-turn rows (174 generals
   PK-asc, 94 cities, 2 nations w/ tech/type/aux/chief_set/nation_env, diplomacy).
2. **Engine adapter exercised** — `AiSelectionGateIT` (`:app:game-engine`) materialises that snapshot into ONE
   `InMemoryTurnWorld` and drives the LIVE `AiTurnAdapter.chooseNationTurn`/`chooseGeneralTurn` over it.

| metric | value |
|---|---|
| due-general turns run LIVE + matched (selection + draw stream) | **174 / 174** |
| live draws PULLED by the Kotlin AI + matched value+cursor+consumed | **667 / 667** |
| general-pass selection `(actionCode RAW, args, reason)` matched | 174 / 174 |
| lord nation-pass `(che_포상, args, reason=doNPC긴급포상)` + `drawCountAtNationEnd` matched | 2 / 2 (gid 105 장각, 152 하진) |
| draw-COUNT / cursor / consumed / value mismatches | 0 / 0 / 0 / 0 |
| Kotlin fixes required | **0** (no divergences surfaced) |

**There were ZERO divergences on the first real live-selection pass.** The live Kotlin AI PULLS the same draws
in the same order AND PICKS the same `(actionCode, RAW args, reason)` as the PHP golden — including the
`do국가선택`→`che_이동 {destCityID}` choice draws (×18), the `do일반내정` develop selections (×55), the `do중립`
견문/인재탐색 fallbacks (×99), the `do거병`→`che_거병` raise-army gate (×2), and the lords' nation-pass reward.

### The load-bearing seam this required (and the production parity fix it carries)
`AiTurnAdapter` gained an injectable `rngFactory` (default `AiSeed.rng`, ZERO behavior change) + a per-general
rng cache keyed `(generalId, year, month)`. This reproduces the PHP single-`GeneralAI`-per-general semantics:
the nation pass (officer_level>=5) and the general pass for ONE general thread the SAME `"GeneralAI"` rng —
the nation-pass draws are the stream PREFIX, the general pass CONTINUES it. (Before, the daemon built a fresh
DRBG per `choose*` call; the lord turns gid 105/152 with `drawCountAtNationEnd=1` confirm the shared-stream +
calcGenType-once-guard behavior is now byte-faithful.) The gate's recorder is draw-neutral (`AiDrawRecorder`,
symmetric to `BattleDrawRecorder`); a sensitivity probe (seed-string corruption) drops the gate to `matched=2/174`,
proving it is NON-vacuous and fails loudly on a real divergence.

### Quarantines (unchanged, all HELD)
- Q1 ORDER BY RAND (선양/오랑캐) UNREACHABLE (census 0/0) — never fires; nothing to exclude.
- INSTANTNATIONTURN — no live call-site, not exercised.
- Diplomacy downstream delta (m10) — month-1 window never reaches 불가침제의/선전포고/천도; the gate asserts
  SELECTION + draw stream only (never inspects resolved downstream delta), so m10 is moot here.

**The dated GAP-WORLD section below is retained for historical context — it is now CLOSED by GT3-engine.**

---

## ✅ GT2 RESOLUTION — the under-covered families are CRAFTED-CAPTURED (8/9), 1 BACKLOGGED with proof

The documented UNDER-COVERED families (the month-1 1010 window never exercises do불가침제의/do선전포고/do천도
diplo SELECTION, the war-state branch of do출병, the genfound 방랑군 families, do선양, NOR the month-3/6/9/12
nation pass) are now captured by **`tools/php-golden/capture_ai_crafted.php`** via FAITHFUL DB MUTATION of the
SAME GT1 install (NO re-install — the hiddenSeed `71adaa4df4012a20c0883beba4810681` is preserved). Each family
snapshots+RESTORES its mutation; the DB returns to the year-181-month-1 GT1 baseline (asserted: clock 181/1,
npc5==0, capital 3, diplomacy 2/2, recv_assist NULL — so manifest_ai.json + world-1010.json + AiSelectionGateIT
stay valid). Every `world-crafted-*.json` + `ai-crafted-*.json` is **byte-identical across two ALL runs**.

### The load-bearing constraint (why month-1 can't reach them)
`GeneralAI::calcDiplomacyState` (GeneralAI.php:219) short-circuits to 평화/선포 with `attackable=false` AND leaves
`warTargetNation` UNSET for `yearMonth <= joinYearMonth(startyear+2,5) = (183,5)`. So do불가침제의 (reads
`warTargetNation`, crashes on null at month 1 — a REAL PHP code path, NOT a harness bug), do선전포고/do출병 (need
the full dipState/attackable), and the diplomacy families are ALL unreachable at the pinned year-181-month-1
instant. The crafted diplo+war families advance the clock to **184/1** (a real value the engine reaches in play)
so calcDiplomacyState fully runs; the per-general `'GeneralAI'` seed RE-PINS from the mutated (year,month)
(self-consistent — the seed is `simpleSerialize(hidden,'GeneralAI',year,month,gid)`). The nation-pass-month
families advance only the month (181/{3,6,12}).

### Captured (8 families, byte-identical twice)
| family | clock | faithful mutation (the EXACT PHP state shape) | fires → selection + draws |
|---|---|---|---|
| **diplo-불가침제의** | 184/1 | nation_env `recv_assist={n2:[2,5000000]}` (che_물자원조 write shape) for lord 하진 (gid 152) | nationTurn `do불가침제의` → `che_불가침제의 {destNationID:2,year:272,month:4}`, **drawCountAtNationEnd=0** (0-draw arsort path). m10: downstream EXCLUDED |
| **diplo-선전포고** | 184/1 | nation-1 city.front all zeroed + nation-1 generals gold/rice→1e6 (trialProp>=1) | nationTurn `do선전포고` → `che_선전포고 {destNationID:2}`; prefix `[nextBool(prob>=1, consumed=false → NO draw), nextFloat1, choiceUsingWeight]`. m10: EXCLUDED |
| **diplo-천도** | 184/1 | nation-1 capital RELOCATED to low-pop edge city 77 (+ city.capital flag) + last천도Trial cleared | nationTurn `do천도` → `che_천도 {destCityID:17}`; prefix `[choice (the >1-hop pick, choiceIndex=0)]`. m10: EXCLUDED |
| **nation-pass-m3** | 181/3 | month 1→3 (promotion month); both lords (105, 152) | lord nation pass runs **choosePromotion** before the priority loop → **ZERO draws** (every empty slot takes the newChiefProb=1 no-draw path line 4097, the `:4102` phantom NEVER draws — decision #9; every occupied slot is in chief_set → SKIPPED line 4082). Reason→doNPC긴급포상 |
| **nation-pass-m6** | 181/6 | month 1→6; both lords | + **chooseTexRate + chooseRiceBillRate** (ZERO draws, getOutcome half-away). choosePromotion 0-draw |
| **nation-pass-m12** | 181/12 | month 1→12; both lords | + **chooseTexRate + chooseGoldBillRate** (ZERO draws). choosePromotion 0-draw |
| **war-출병** | 184/1 | diplomacy 1↔2 set 교전 (state 0, term 0) + nation-1 gen (gid 14 공융) at a front-2 supply city bordering enemy, war-ready (train/atmos 100, crew 5000) | dipState=d전쟁 + attackable → `do출병` → `che_출병 {destCityID:1}`; stream `[choice([1,36])→idx 0→city 1]` |
| **genfound-선양** | 181/1 | lord 하진 (gid 152) npc=5 (RESTORED after; census stays 0/0) | genReason `do선양` → `che_선양`; **do선양 draws ZERO from the LiteHashDRBG** (the destGeneralID is `ORDER BY RAND()` — OUTSIDE the stream). Q1 byte-stability: the id is replaced by sentinel `__ORDER_BY_RAND_QUARANTINED__` + `_q1ValidTargets[]` + `_q1ProducedIdWasValid`; the gate asserts membership, NOT the literal id |

The **nextBool(0.1) per-occupied-slot** path of choosePromotion (decision #9) is NOT exercised by the installed
1010 chief_set: it fires only for a chiefGeneral at a level NOT marked in `chief_set` (a desync state the
installer does not present). The m3/m6/m12 fixtures FAITHFULLY prove the 0-draw invariant (the phantom never
draws, empty slots don't draw) — the occupied-slot draw is structurally rare and documented, not fabricated.

### Backlogged (1 family — precise reason, NOT fabricated)
- **genfound-방랑군** (`do건국` / `do해산` / `do방랑군이동`): these fire ONLY for a 방랑군 lord
  (officer_level==12, nation!=0, **!capital** = a wandering-army nation with capital=0; chooseGeneralTurn:3802-3827).
  Scenario 1010 installs NO wandering-army nation (nation 1 cap 3, nation 2 cap 1). The 방랑군 state is produced by
  the **거병→건국 lifecycle** — `che_거병.php` INSERTs a new nation row + diplomacy rows for every nation +
  nation_turn rows for all officer levels + flips city ownership + SEEDS the nation_env `npc_nation_policy`/
  `npc_general_policy` KV the GeneralAI ctor `cacheValues` reads — NOT by the installer. Hand-fabricating the
  wandering-army nation + its nation_env policy/aux KV would **fabricate the exact KV the AI branches on**
  (a parity-law violation). The faithful path is to RUN `che_거병` (real processCommand) — the long-sim GT3
  territory (the mutating step + its SEPARATE `'generalCommand'` rng), NOT the AI-selection surface this gate pins.
  **BACKLOG:** capture via a single-general 거병→건국 mini-sim (run che_거병 processCommand, advance ≥1 turn, then
  capture the now-방랑군 lord's chooseGeneralTurn) once the P2–P4 거병/건국 resolvers + a one-turn-advance harness
  are wired. Owner: P5 long-sim backlog (GT3 mini-sim or a future GT2b).

### Q1 still HELD (sharpened by GT2)
The do선양 crafted fixture confirms Q1: do선양 draws ZERO LiteHashDRBG bits; the only non-determinism is the
SQL `ORDER BY RAND()` target-id, which is byte-replaced by a sentinel + a valid-candidate set. The gate asserts
`destGeneralID ∈ {valid nation members, npc!=5}`, never the literal byte. do국가선택's 오랑캐임관 branch (npc==9
lord ORDER BY RAND) is likewise unreachable (census npc9off12==0) and shares the quarantine.

### Reason distribution (crafted captures)
do불가침제의 ×1 (nation pass), do선전포고 ×1 (nation pass), do천도 ×1 (nation pass), do출병 ×1, do선양 ×1,
+ 6 nation-pass-month general-pass captures (choosePromotion/Tex/Bill prefix + doNPC긴급포상). **8 families
CAPTURED, 1 (genfound-방랑군) BACKLOGGED.**

---

## Headline result — the RNG-stream dimension is byte-for-byte GREEN

**`every due-general draw stream byte-matches value-for-value and cursor-for-cursor` — PASS.**

| metric | value |
|---|---|
| due-general turns replayed | **174 / 174** |
| turns matched draw-for-draw (value + stateIdx/bufferIdx cursor + `consumed`) | **174 / 174** |
| total draws re-issued + matched | **667** (`nextFloat1`×133, `nextBool`×516, `choice`×18) |
| nation-prefix turns (`drawCountAtNationEnd`) matched | 2 / 2 (gid 105 장각, gid 152 하진) |
| draw-COUNT mismatches | 0 |
| cursor (stateIdx/bufferIdx) mismatches | 0 |
| `consumed` short-circuit mismatches | 0 |
| value mismatches | 0 |

**There are ZERO RNG-stream divergences.** Re-issuing the golden's recorded `method+args` sequence on a fresh
per-general `LiteHashDrbg(seedString)` (the symmetric `BattleDrawRecorder`) reproduces the live PHP AI's stream
exactly — value-for-value AND cursor-for-cursor — for every method the month-1 1010 window exercised:
`nextBool` (including the `prob<=0`/`>=1` no-draw short-circuits and the `prob==0.5` bit path), `nextFloat1`, and
the `choice` cursor draw (`nextInt(size-1)` index, from the `do국가선택` move path). This is the load-bearing
DRBG-kernel parity surface — the part the banked golden FULLY supports.

This proves the **F-SEED lineage + the common-module RNG kernel** are byte-faithful for the exact draws the
live AI made. It does NOT, by itself, prove the live Kotlin `chooseGeneralTurn` PULLS the same draws in the
same order (that is the SELECTION dimension below — blocked by GAP-WORLD).

### Supporting invariants (all PASS)
- **A0 — F-SEED lineage:** `AiSeed.seed(hidden,year,month,generalId)` reproduces all 174 `seedString`s
  byte-for-byte (`str(32,…)|str(9,GeneralAI)|int(181)|int(1)|int(<gid>)`). A byte-0 desync here would desync
  EVERY per-general DRBG; it does not.
- **A2 — hiddenSeed format / census (M7 quarantine validity):** hiddenSeed matches `^[0-9a-f]{32}$` and equals
  the pinned value across all 174 fixtures; npc census is **0/0** (npc5==0, npc9off12==0); the two
  officer_level==12 rulers (하진 nation 1, 장각 nation 2) are both npc==2. The Q1 do선양 / 오랑캐임관
  `ORDER BY RAND` quarantine HOLDS.

---

## The one real gap — GAP-WORLD (live-selection dimension NOT replayable in :logic)

**This is the divergence GT3 surfaces — a STRUCTURAL gap in what GT1 banked, not a draw mismatch. It is
cataloged here, NOT faked.**

### What

GT3 dimension (a-SELECTION) wants: rebuild the per-general `"GeneralAI"` rng, **run the LIVE
`chooseGeneralTurn`/`chooseNationTurn`** over the SAME scenario-1010 world state, and assert the chosen
`(actionCode, RAW args, reason)` + the live-pulled draw stream match the golden. Dimensions (b) downstream
delta/log and (c) long-sim timeline likewise need the live world advanced turn-for-turn.

### Why it is blocked (the precise cause)

The GT1 capture (`tools/php-golden/capture_ai.php`) drove the REAL PHP AI over the **live installed DB** and
swapped ONLY the rng to a recorder. Each fixture banks the `seedString` + the consumed `drawStream` + the
chosen `(che_* RAW, args, reason)` — **but NOT the scenario-1010 GAME-ENTITY ROWS** the AI branched on:
the 174 general rows (stats/crew/gold/rice/injury/officer_level/city/nation/meta), the 94 city rows
(level/trust/pop/dev/supply/front/conflict), the 2 nation rows (level/tech/gold/rice/capital/type), the
diplomacy rows, and the `nation_env` KV. The capture read them straight from the live DB and never serialized
them into the golden.

Two compounding facts make the live-selection replay un-runnable in this `:logic` test:
1. **No world fixture.** There is no banked scenario-1010 `InMemoryTurnWorld` snapshot (no importer in
   `:logic` test resources; `tools/php-golden/install_scenario.php` only installs the MariaDB DB for the PHP
   capture). Reconstructing the 174 generals + 94 cities + 2 nations from scratch would be FABRICATING the
   world — a parity-law violation.
2. **No engine adapter in `:logic`.** The fully-materialized live-AI path is `AiTurnAdapter` in
   `:app:game-engine` (`app/game-engine/src/main/kotlin/opensamguk/engine/turn/AiTurnAdapter.kt`), which
   requires a live `InMemoryTurnWorld`. `:logic` cannot reach it (module boundary), and even on the engine
   side the world snapshot does not exist.

### Impact

- The 174 banked **selection targets** (chosenActionCode RAW + chosenRawArgs + reason) are present and verified
  PRESENT by the harness (`the live-selection dimension is blocked on the un-banked scenario-1010 world`
  test), so a future engine-side gate that DOES have the world can consume them.
- The RNG-stream dimension (the hard part of "draw-for-draw") is fully closed here.

### Resolution path (not in GT3 scope — recorded for the phase backlog)

Either (preferred, faithful):
- **GT1b — bank the scenario-1010 world snapshot.** Extend `capture_ai.php` to ALSO dump the installed
  `general`/`city`/`nation`/`diplomacy`/`nation_env` rows (PK-ascending, byte-identical across two runs) into
  `golden/p5/world-1010.json`, then a future `AiReplayGateTest` (engine-side, `:app:game-engine`) materializes
  that snapshot into `InMemoryTurnWorld` and drives `AiTurnAdapter.chooseGeneralTurn`/`chooseNationTurn`,
  asserting the live-pulled draw stream == the banked stream (which then ALSO proves selection, since a correct
  selection pulls the correct draws by construction) + the chosen `(actionCode, RAW args, reason)`.

Or (interim, smaller):
- crafted single-general fixtures (GT2-style) that bank BOTH the minimal world AND the draw stream for the
  representative `do일반내정` / `do중립` / `do거병` / `do국가선택` / `doNPC긴급포상` paths.

Until then the live-selection / downstream-delta / long-sim dimensions stay **out of the gate**, documented
here. The RNG-stream + seed-lineage + census dimensions are IN and green.

---

## Quarantines honored (per plan / manifest)

- **Q1 / AI-QUAR-ORDERBYRAND** — do선양 (npc==5) + 오랑캐임관 (npc==9 lord) `ORDER BY RAND` target-id pick:
  UNREACHABLE in 1010 (census 0/0, asserted). Off the gate; the crafted-fixture path (GT2) byte-matches only
  the non-id bytes. **HELD.**
- **AI-QUAR-INSTANTNATIONTURN** — `chooseInstantNationTurn`: no live PHP call-site → no golden → OFF the gate;
  structural stub proven by sibling `chooseNationTurn` byte-match (decision #3). Not exercised by this gate.
- **Diplomacy downstream delta (m10)** — `do불가침제의`/`do선전포고`/`do천도`: these families are NOT exercised
  by the month-1 1010 window at all (lord-only + recv_assist/war/relocation preconditions a pristine install
  cannot satisfy — R-GATE §3.B), so the gate window touches none of them. When they ARE gated (crafted
  fixtures), the downstream delta/log is EXCLUDED per m10 (no P2-P4 green resolver); only SELECTION + boolean
  + draw stream apply.

## Residual UNCERTAINs (golden-gated, not asserted bare)

- **G12 — the NATION reserved-fail deny-log string** (`"{failString} <1>{date}</>"`, reason-first): the month-1
  window never hits the nation deny path (both lords' reserved command is 휴식 → no honor → straight to the
  loop), so the deny-log string is NOT exercised by this golden. It must be re-derived from a crafted nation
  fixture before it can be gated. NOT asserted here.
- **The long-sim window depth:** this golden is a single-turn (year 181 month 1) cohort capture, so dimension
  (c) has no data. A multi-turn capture is a separate GT (GT1b/long-sim), recorded above.
