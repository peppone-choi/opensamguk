# P4 battle + conquest golden capture — backlog (G1)

The P4 G1 gate captures the battle + conquest oracles from PHP grand truth via the PROVEN
Docker harness (`MariaDB 11.4` + `php:8.3-cli` + `scenario_1010`, mirroring
`capture_monthtick`). The PHP goldens
(`logic/src/test/resources/golden/p4/{battle,conquercity,conflict}-*.json`) are the
**byte/draw-for-draw oracle** — every draw, log line, and side-effect transcribed VERBATIM
from the REAL `process_war.php` / `j_simulate_battle.php`. **Nothing fabricated.** This file
documents what was captured faithfully this pass vs what is deferred, with the exact PHP
`file:line` reason for every gap — so future work raises the matched count without ever
faking green or weakening the golden.

## Captured this pass (the oracle — committed, immutable, byte-stable)

All five fixtures were captured by running the PHP harness **TWICE (three times for the
ConquerCity branches) and byte-diffing** — every committed golden is byte-identical across
independent runs (OQ #12). Each capture re-installs `scenario_1010` fresh and re-applies a
deterministic setup, so the delta is reproducible.

### Battle (G1a/G1b) — `tools/php-golden/capture_battle.php` + `RandUtilDrawRecorder.php`

The recorder is a DRAW-NEUTRAL `extends RandUtil` decorator over the single shared
`RandUtil(LiteHashDRBG(warSeed))` threaded into every `WarUnit` + trigger `fire()`. It
snapshots the `LiteHashDRBG` `stateIdx`/`bufferIdx` cursor BEFORE each draw, delegates to
`parent::…` (byte-identical RNG path — verified `MATCH=YES` vs a bare `RandUtil`), and logs
`{seq, method, args, result, consumed, stateIdxBefore, bufferIdxBefore}`. The capture
inlines the EXACT `simulateBattle()` body of `j_simulate_battle.php:366-476` (repeatCnt=1,
no live DB) with the ONE RNG line swapped to the recorder.

- **`battle-01.json`** — 조조(che_반계) vs 관우(che_위압), 보병 1100, 5000 crew each.
  **46 draws, 7 phases.** The base 필살시도/회피시도/계략시도 PRE band + 반계시도/위압시도 +
  calcDamage (attacker-then-defender `nextRange(0.9,1.1)`) + tryWound `nextBool(0.05)` tail.
  warSeed `e40b0cdd01d00f70516e8f11d14c0c2b` (derived from the PINNED hiddenSeed
  `8ebfeb6fa932a181ec9ef43b7473f4c9`, the committed fixture INPUT — reproducible across
  fresh installs).
- **`battle-02.json`** — 장각(che_환술) on 귀병 1400 (magicCoef 0.5, INT 93). **68 draws, 7
  phases.** Exercises the full 계략시도 magic sub-stream: `nextBool(trial)` →
  `choice(magicTable)` → `nextBool(success)`. **6 choice draws (반목/매복/위보/화계).** BOTH
  계략 success AND 계략실패 branches fire (pins the success-folds-`:74-75` vs fail-stores-RAW
  `:85` asymmetry, gate-b). 1 `nextBool` short-circuit (`consumed=false`, cursor unchanged →
  next draw starts at the SAME cursor) pins the no-draw guaranteed-prob trap.

Each battle golden carries: `seed`, `hiddenSeed`, `warSeedDerivation`, all army/city/nation
inputs, the FULL ordered `draw_stream`, the byte-exact `phase_log` (진격 / per-phase
`<Y1>【name】</> <C>HP (-dead)</>` / 퇴각·전멸 / 계략 success+fail lines), and `post_state`
(general vars + `finishBattle` RankColumn counters).

### Conquest (G1c) — `tools/php-golden/capture_conquercity.php`

Drives a REAL, UNMODIFIED `processWar()→ConquerCity` against installed `scenario_1010` to a
city fall, snapshotting every conquest-touched table BEFORE/AFTER and emitting
created/updated/deleted ROW DELTAS — the exact oracle the Kotlin `ChangeRecorder` must
reproduce (no inline DB write on the Kotlin side). `UniqueConst::$hiddenSeed` is PINNED to
the plan's fixed live value `8ebfeb6fa932a181ec9ef43b7473f4c9` so the warSeed + both
ConquerCity seed strings are reproducible regardless of the install's random seed.

- **`conquercity-survive-01.json`** — survive branch (non-capital 관도 city=80 falls, 황건적
  keeps 9 cities). db_delta: city nation flip + agri/comm/secu ×0.7 + def/wall reset
  (`def_max/2` since level≤3) + dead 0.4/0.6 split; nation tech bump; 1 officer demote;
  SetNationFront. conquest_records: 진격 + 점령(공략 성공) byte-exact.
- **`conquercity-capital-01.json`** — capital-fall branch (황건적 capital 업 city=1 falls,
  nation survives → 긴급천도). `findNextCapital` picked 복양(city=18) via the PHP BFS-ring +
  max-pop (the residual-divergence target: PHP BFS, NOT TS Euclidean). nation capital 1→18,
  gold/rice ×0.5, tech bump; chiefs moved; ALL nation-2 generals atmos ×0.8; 33
  conquest_records incl. per-general 긴급천도 + 함락 history.
- **`conflict-01.json`** — `WarUnitCity::addConflict` over 3 sieges on 관도: pins the PHP
  `arsort` stable-DESC tie-break (decision #6) + ×1.05 선타/막타 + `getConquerNation`
  = `array_key_first` winner (N3) + `deleteConflict` (unset, NO re-sort). NO RNG.

The two ConquerCity seed STRINGS are captured identical (the double-seed RESET, built twice
at `process_war.php:549` AND `:589`).

## Quarantined / deferred (with the exact PHP reason — NOT a gap in faithfulness)

### CC-0 — the COLLAPSE branch DB-delta + log golden (CAPTURED, 2026-08-17, OPENSAM-186)

`capture_conquercity.php` runs the collapse branch on both `join_mode` values and writes
`conquercity-collapse-full-01.json` / `conquercity-collapse-only-random-01.json`. Both are
byte-identical (sha256) across two independent fresh-DB runs. They carry the REAL `deleteNation`
(`func.php:1713`) destroy logs — global history `【멸망】` (`:1729`) + per-general action/history
(`:1772-1773`) — the defender-nation `nation.deleted` row, the 재야 reset general deltas, the
winner gold/rice reward, and (full only) the 6 scout messages the `join_mode != 'onlyRandom'`
branch issues. Asserted by `ConquerCityCollapseTest`, including the 38-draw gold/rice stream replayed off the
committed `conquerCitySeeds.seed1`. Only the CONDITIONAL scout/NPC-join draws remain (CC-1 below).

### CC-1 — the COLLAPSE branch CONDITIONAL draw sub-stream (scout / NPC-join)

**RESOLVED as of CC-0 (2026-08-17): the gold/rice draws ARE captured and gated.** The earlier
"the LOCAL `$rng` cannot be observed" reason was WRONG. The `$rng` at `process_war.php:589` is a
local *variable*, but its seed is fully deterministic —
`Util::simpleSerialize(hiddenSeed,'ConquerCity',year,month,attNationID,attID,cityID)` — and that
seed STRING is committed in every conquest golden as `conquerCitySeeds.seed1`. Re-seeding a Kotlin
`RandUtil(LiteHashDrbg(seed1))` reproduces the stream without touching grand truth, and the draw
RESULTS are already in the golden (the per-general 도주 log 금/쌀 amounts + the
`db_delta.general.updated` gold/rice `from`/`to` pairs). `conquercity-collapse-only-random-01.json`
short-circuits both conditional draws (`:645`, `:656`), so its stream is exactly
`nextRange(0.2,0.5)` ×2 per general — 19 generals / 38 draws, replayed value-for-value and
order-for-order by `ConquerCityCollapseTest.collapse loseGold-loseRice draws replay from the golden
ConquerCity seed`.

**What actually remains:** the CONDITIONAL draws in the `join_mode != 'onlyRandom'` branch — the
scout `nextBool(0.5)` (`:645`) and the NPC-join `nextBool(joinRuinedNPCProp)` + `nextRangeInt(0,12)`
(`:656-658`).

**Why deferred:** the NPC-join branch is gated on `2 <= npcType <= 8 && npcType != 5`, and
`db_delta.general.updated` carries only CHANGED columns — `npc` never changes during collapse, so
it is absent from the golden. Without each general's `npcType` the conditional draw ORDER of the
`full` fixture cannot be reconstructed, so `conquercity-collapse-full-01.json` is asserted for logs
and message-issuance only, not draw-for-draw.

**How it closes:** add the defender generals' `npc` (and `owner`) columns to the collapse capture's
general snapshot as an explicit pre-state block (a capture-script change, not a golden edit), then
extend the replay to the `full` fixture. Owner: **next conquest-capture pass**.

### CC-3 — `deleteNation` general order has no `ORDER BY` in PHP

`func.php:1733` runs `SELECT no FROM general WHERE nation=%i AND no != %i` with **no `ORDER BY`**.
The observed "others ascending PK + lord appended LAST" holds in the capture, but ascending PK is an
InnoDB PK-scan side effect, not a contract PHP guarantees — a different storage engine or query plan
could reorder it, which would reorder both the destroy logs AND the per-general draw pairing. The
Kotlin port sorts explicitly, so it is deterministic; the risk is that the ORACLE's order is not
pinned by PHP. Noted in `ConquerCityCollapseTest`. No action unless a divergent capture appears.

### CC-4 — the harness writes pretty-printed JSON but the committed goldens are minified

`capture_conquercity.php:266` passes `JSON_PRETTY_PRINT` to `Json::encode`, yet every committed p4
golden is a single minified line. The minifying step is not committed or documented anywhere, so
"re-run the harness and reproduce the committed sha256" is not a runnable path — reproduction
currently requires normalizing (e.g. `python3 -m json.tool`) before comparing. Pre-existing across
ALL p4 goldens, not introduced by CC-0. Fix by making the capture write the committed form directly
(and re-verifying, NOT re-editing, the existing goldens). Owner: **harness cleanup**.

### Notes (faithful pins, not gaps)

- **`turntime` is PINNED** in both captures — it is the ONLY wall-clock input
  (`TimeUtil::now()` in `j_simulate:80` / the install). It is only echoed into the 진격
  log's `<1>$date</>` (via `getTurnTime(TURNTIME_HM)`, `process_war.php:253`) and stored to
  `recent_war`. Pinning it keeps both deterministic without changing any computed value.
  Listed in `manifest_battle.json` `perFixtureIgnoreList`.
- **`hiddenSeed` is PINNED** in both captures to `8ebfeb6fa932a181ec9ef43b7473f4c9` (the
  plan's fixed live config value), so the warSeed (che_출병.php:245-253) + the ConquerCity
  seed strings are REPRODUCIBLE across independent fresh installs — verified by capturing on
  two separate fresh `scenario_1010` installs and byte-diffing IDENTICAL. The battle
  general-input's install-random battle-IRRELEVANT fields (`city` placement, `killturn`
  auto-kick countdown) are also pinned (the battle reads the city context from the explicit
  attacker/defender city raw rows, never the general's own `city`; killturn is never read by
  `processWar_NG`) — zero computed-value change.
- **The armies in `capture_battle.php` are authored** (crew/train/atmos overlaid on REAL
  installed generals' stats). This is FAITHFUL: `j_simulate_battle` is the in-game battle
  SIMULATOR, which by design takes hand-authored armies — the engine (`processWar_NG`,
  `WarUnitGeneral`, every trigger) runs UNMODIFIED. `personal='None'` isolates the draw
  stream to the crewType + war-specialty (`special2`) triggers (no personality injects a war
  trigger).
- **The ConquerCity setup weakens the target city's def/wall** to a low value — a legitimate
  heavily-damaged city state. `processWar()` runs unmodified; only the pre-state is arranged
  (the simulator/che_출병 mobilization a player would perform).
- **Chiefs PINNED into the defender capital** (capital-fall branch only) — `buildScenario`
  places generals via install-RNG, so a chief's pre-천도 `city` varies per run and would
  surface in the 긴급천도 `UPDATE general SET city=… WHERE nation=… officer_level>=5` delta.
  Seating chiefs in their capital is the canonical pre-state and makes the delta byte-stable.

## Asserted by the Kotlin replay gate (next agent — NOT this PHP-capture agent)

This agent produced ONLY the PHP-side oracle + committed goldens. The Kotlin
`BattleReplayGateTest` / `ConquerCityReplayGateTest` / `ConflictWinnerGateTest` (the replay
job) assert, against these goldens: (a) the draw stream value-for-value AND cursor-for-cursor
at the first divergence; (b) `finishBattle` rice/exp/ded + RankColumn counters; (c) the
per-phase battle log byte-equal; (d) the ConquerCity db_delta reproduced as ChangeRecorder
DELTAS + both seed strings + findNextCapital winner + 긴급천도/정복 logs; (e) the ConflictMap
winner + JSON byte-equal. Out-of-P4 skills are quarantined per `manifest_battle.json`.

## G1b replay-gate RESULTS (the Kotlin-side replay agent)

`BattleDrawRecorder` (the Kotlin draw recorder, symmetric to `RandUtilDrawRecorder.php`) +
the three gate tests landed under `logic/src/test/kotlin/opensamguk/logic/golden/`.

**Draw streams: byte-exact (value + cursor).** battle-01 46/46, battle-02 68/68. The recorder
is draw-neutral vs a bare RandUtil and reproduces the battle-01 cursor anchors (1,0)/(1,7)/(1,35).

**Port bugs the gates localized + FIXED (golden = grand truth, faithful port only):**
1. **위압발동 oppose atmos −5 was DROPPED** (`specialty.triggers.CheWiapBaldong`). PHP
   `che_위압발동.php:24` does `$oppose->increaseVarWithLimit('atmos',-5,40)` on a general — NOT
   cosmetic: the opponent's atmos 100→95 lowers their `computeWarPower` every later phase.
   Restored `oppose.decreaseAtmos(5,40)`.
2. **crewType war triggers were never wired** (action-stack source #6). footman 1100 carries
   `phaseSkillTrigger=['che_방어력증가5p']` (`GameUnitConstBase.php:54-61`) → the defender's
   `oppose.multiplyWarPowerMultiply(1/1.05)`. Added `CrewTypeWarModule` (mirrors
   `GameUnitDetail.getBattle{Init,Phase}SkillTriggerList`, `GameUnitDetail.php:239-275`).
3. **WarUnitGeneral.addTrain/addAtmos were missing** (the state's `train` was write-locked) so
   the first-contact `addTrain` hook was inert; the opponent divides warpower by
   `getComputedTrain` so the +1 (100→101) is load-bearing. Ported faithfully (`WarUnitGeneral.php:81-89`).
4. **ConflictMap.phpFloat rendered whole floats as `3150.0`** but the `Json::encode` golden
   renders `3150` (conflict-01 `{"1":3150}` from 3000×1.05). Fixed to drop the trailing `.0`
   on integral floats.
5. **Officer-level warpower multipliers were never ported** (`TriggerOfficerLevel.php:62-86`).
   Officer rank 12 should apply `[1.07, 0.93]`, rank 11 `[1.05, 0.95]`, ranks 10/8/6
   `[1.10, 1]`, ranks 9/7/5 `[1, 0.90]`, and ranks 4/3/2 `[1.05, 0.95]` after the
   existing away-from-officer-city demotion. Porting this table reduced battle-02's attacker
   post-state residual from 804 crew to 52 crew.

**Trigger surface byte-exact** (proves full skill/trigger determinism beyond the raw draws):
phase count + per-unit `activatedSkillLog` match the golden EXACTLY on both battles
(필살/회피/계략/위압/반계/환술 all fired identical counts in identical order).

**QUARANTINED:**
- **G1b-WP** — a residual warpower-arithmetic gap remains, but it is now gated tightly:
  battle-01 and battle-02 post-state killed/dead/hp match the golden to **<1% of starting
  crew/HP on the relevant damage scale** (`dead`/`hp` use own starting HP; `killed` uses the
  opposing side's starting HP), including the battle-02 siege wall numeric surface. The remaining
  gap touches NEITHER the byte-exact draw stream NOR the trigger/skill firing — it is a sub-unit
  rounding in the warpower chain I could not pin to a PHP `file:line` without a PHP runtime trace
  of `computeWarPower` per phase. If exact numeric closure becomes required, run the capture host
  with per-phase `rawWarPower`/`getComputedAtmos`/`getDexLog` tracing.
- **CC-1** — as of CC-0 the collapse gold/rice draws ARE captured and replayed (see CC-1 above);
  `ConquerCityReplayGateTest`'s two fixtures are still SURVIVE cases, so the collapse assertions
  live in `ConquerCityCollapseTest` against the two collapse fixtures instead. Only the CONDITIONAL
  scout/NPC-join draws (`process_war.php:645`, `:656-658`) remain unreplayed — the golden lacks the
  `npc` column needed to reconstruct their branch order.
- **CC-2** — the FULL numeric db_delta re-drive needs the complete pre-state world (every
  city/general/nation/diplomacy row for the findNextCapital BFS + front recompute), which the
  delta-only goldens do not carry. The gate asserts the seed strings (double-seed), the
  점령/지배/긴급천도-to-복양 log tokens, and the delta STRUCTURAL facts (capital→복양(18), nation
  survival, conquered-city ownership flip) rather than re-deriving every numeric.
