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
  **45 draws, 7 phases.** The base 필살시도/회피시도/계략시도 PRE band + 반계시도/위압시도 +
  calcDamage (attacker-then-defender `nextRange(0.9,1.1)`) + tryWound `nextBool(0.05)` tail.
  warSeed `2e344cb5904febf229cb069fb6e00168` (derived from the live install hiddenSeed
  `fbfb1f7c7914c3e150d5e28e9c45a7e1`, the committed fixture INPUT).
- **`battle-02.json`** — 장각(che_환술) on 귀병 1400 (magicCoef 0.5, INT 93). **69 draws, 7
  phases.** Exercises the full 계략시도 magic sub-stream: `nextBool(trial)` →
  `choice(magicTable)` → `nextBool(success)`. **6 choice draws (화계/혼란/반목/급습).** BOTH
  계략 success AND 계략실패 branches fire (pins the success-folds-`:74-75` vs fail-stores-RAW
  `:85` asymmetry, gate-b). seq4 `nextBool(1.485)` short-circuit (`consumed=false`, cursor
  unchanged `(2,6)` → next draw starts at `(2,6)`) pins the no-draw guaranteed-prob trap.

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

### CC-1 — the COLLAPSE branch per-general draw sub-stream

**What:** the collapse branch (`process_war.php:589-664`, fired when the defender nation's
`cityCount==1` → `DestroyNation`): the `onArbitraryAction` defender loop (`:599`) + the
per-general `nextRange(0.2,0.5)` gold/rice loss (`:628-629`) + the `nextBool(0.5)` scout
lottery (`:645`) + the `nextBool(joinRuinedNPCProp)` + `nextRangeInt(0,12)` NPC-join draws
(`:654-658`).

**Why deferred:** those draws come off a **LOCAL `$rng`** created INSIDE `ConquerCity()`
(`process_war.php:549` and `:589`) — NOT the war `RandUtil`, and not reachable from outside
the function. Wrapping it with `RandUtilDrawRecorder` would require editing
`process_war.php`, which is grand truth and **must not be altered**. Additionally, forcing
the collapse branch requires reducing the defender nation to 1 city (deleting 9 of nation-2's
10 cities — extensive DB surgery). The two NON-collapse branches captured here DO exercise
the `:599` `onArbitraryAction` defender loop + both ConquerCity seed STRINGS + the full
side-effect order, so the seed lineage and side-effect ordering ARE pinned.

**How it closes:** the Kotlin **B2 `ConquerCity` port** replays the documented collapse draw
order (CC-1 above + the draw catalog) on the captured ConquerCity seed STRING through the
symmetric Kotlin draw-recording harness, against a synthetic 1-city defender nation. The
seed string is the bridge — it is captured here; the sub-stream is a B2 standalone-replay
deliverable, not a PHP-capture deliverable (the local-rng cannot be observed without editing
grand truth). Owner: **B2 follow-up**.

### Notes (faithful pins, not gaps)

- **`turntime` is PINNED** in both captures — it is the ONLY wall-clock input
  (`TimeUtil::now()` in `j_simulate:80` / the install). It is only echoed into the 진격
  log's `<1>$date</>` (via `getTurnTime(TURNTIME_HM)`, `process_war.php:253`) and stored to
  `recent_war`. Pinning it keeps both deterministic without changing any computed value.
  Listed in `manifest_battle.json` `perFixtureIgnoreList`.
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
