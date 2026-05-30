# P3 monthly-tick golden capture — backlog (G1)

The G1 gate is **partial by design** (matched-count gate, plan-authorized fallback). The
PHP golden (`logic/src/test/resources/golden/p3/month-0{0..4}-*.json`) is the **byte oracle**
and was captured from the REAL `devsam-core` `executeAllCommand` inner month block (faithful;
nothing fabricated). This file documents what the Kotlin replay asserts NOW vs what is
quarantined for later families, so future work raises the matched count without ever
faking green or weakening the golden.

## Captured (the oracle — committed, immutable)

- **N = 4 months** of `scenario_1010` (startYear 181 → ticks 181-1→181-2→…→181-5).
- `month-00-before.json` — the install month-0 before-state of every monthly-tick table
  (`game_env`, `nation`, `city`, `general`, `diplomacy`, `nation_env`) in PK-ascending order,
  plus the live install **hiddenSeed `ee61d0c02de10291d9d26067f8d88956`** (the G1b fixture input;
  a config-file→DB-column divergence per the plan).
- `month-NN-after.json` — per month: full numeric state, the monthly RNG seed string +
  draw count, the PreMonth/Month event dispatch order (`priority DESC, id ASC`), and every
  `general_record` row added that month (char-for-char).

## Asserted NOW (G1c — `MonthTickReplayGateIT`, logic module)

For each of the 4 months the Kotlin pure tick primitives byte/number-match the golden:

1. **monthly RNG seed string** — `MonthScopedRng.monthlySeed(hiddenSeed,year,month)` byte-equals
   the captured `monthlySeedString` (the monthly RNG lineage oracle — gate (f)).
2. **date advance** — `ServerClock.turnDate` reproduces each month's `oldDate → newDate` exactly
   (the L7 calendar advance — gate (b) partial).
3. **dispatch ordering invariant** — the seeded `EventStore.rowsFor(target)` is ordered
   `priority DESC, id ASC`, matching the golden's order on the rows that ARE seeded (gate (b)).
4. **iteration order = PK ascending** — the golden's `city`/`nation`/`general` dumps are
   strictly id-ascending (the #1 cross-unit parity invariant — gate (a)/(b) prereq).

`mismatches == 0` after the manifest ignore-list, per month, all 4 months. **No parity bug
was found in the asserted surface** — every asserted fact matched the PHP oracle on the first run.

## Quarantined (documented, NOT fake-green — manifest `quarantine[]`)

| id | what | why deferred | owner |
|----|------|--------------|-------|
| **EV-1** | full `$defaultEvents` dispatch COUNT (golden MONTH=82 / PRE_MONTH=1) | Kotlin `EventStore.withDefaults` seeds only **11** rows; the full 82-row `GameConstBase.php:447-531` transcription is F2's task. The 73 priority-1000 rows are no-ops in the year<184 window. The dispatch *ordering* is asserted; the COUNT match awaits F2. | F2 |
| **NUM-1** | full per-table numeric-flush byte-match over a wired DB world | The Kotlin tick is built as per-step PURE functions with **no production driver** that loads a Postgres world, runs a full month end-to-end, and flushes via JPA. That wiring (WorldSnapshot JSON loader + `MonthlyPipeline` assembly + flush) does not exist yet. For the year<184 window the golden numeric state is near-static anyway (income/supply gated off by Date conditions), so the numeric oracle is low-signal here regardless. | F1/F4 |
| **DISASTER-1** | RaiseDisaster (mo 1/4/7/10) + AssignGeneralSpeciality (mo 1) bodies | `startYear=181` ⇒ `year<startYear+3` (year<184) skip window: the disaster-table / speciality-assignment bodies are skipped for 181-183; only the unconditional `city.state` reset + RNG-seed draws are live. The committed N never reaches 184 → bodies vacuously matched. | vacuous for this N |
| **H-1** | 봄/가을/봉급/수입/고립/작위(0-9)/개전/종전 history strings | The 181-2..181-5 window produced **zero** `general_record` rows (income/봉급 = per-general drain = P2 surface; 봄/가을 = `checkStatistic` at the Jan boundary, not crossed by this N). | extend N past a January boundary (year 182 mo 1) |
| **D-1** | nation-level 0-9 transitions; UNITED/checkEmperior | not reachable in a 4-month non-unifying replay of a 24-owned-city start; `WHERE LEVEL>=4` never crosses the 8/9 thresholds. | extend N or seed a synthetic fixture nation |

## How to raise the matched count (the monotonic gate)

1. **F2 completes `EventStore.withDefaults`** to the full 82-row `$defaultEvents` → drop the
   `EV-1` ignore-entry and add a dispatch-COUNT assertion to `MonthTickReplayGateIT`.
2. **F1/F4 land the production `MonthlyPipeline` wiring + a WorldSnapshot loader** that can build
   the in-memory world from `month-00-before.json` and flush to Postgres → drop `NUM-1`, add the
   per-table numeric-flush byte-match (Testcontainers postgres) as `MonthTickReplayGateIT`
   graduates from the logic module to `app/game-engine` per the plan's G1c file target.
3. **Re-capture with a larger N** (cross year 182 month 1) using
   `php tools/php-golden/capture_monthtick.php --months=16` → activates `H-1` (history strings),
   `DISASTER-1` (year≥184), and `D-1` (level transitions).

The capture harness (`capture_monthtick.php`) drives the REAL tick — re-running it with a larger
`--months` regenerates a faithful, larger oracle with no code changes.
