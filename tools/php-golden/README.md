# php-golden — P1 che_상업투자 / che_농지개간 golden capture

One-shot golden generator. Boots the **devsam-core PHP** capture environment, drives
a single REAL turn-execution for a deliberately module-free general, and emits the
committed P1 golden fixtures that the Kotlin byte-match gate asserts against:

| Output (committed) | Asserted by |
| --- | --- |
| `logic/src/test/resources/golden/p1/che-action-fixtures.json` | G2 `CommerceActionLogGoldenTest`, F3/C2 seed oracle |
| `logic/src/test/resources/golden/p1/che-golden-db.json` | G4 `VerticalSliceE2EIT` step 5 (row + aux jsonb byte-compare) |

**Oracle = PHP devsam-core** (the parity grand truth — where PHP diverges from the
TS reference, PHP wins). Generate **ONCE**, commit the JSON + DB fragment, regenerate
**ONLY** when the PHP source changes: `che_상업투자.php`, `func_process.php`/
`TurnExecutionHelper.php` (seed), `func_gamerule.php` (`updateMaxDomesticCritical`,
`CriticalRatioDomestic`), `func_converter.php` (`getDomesticExpLevelBonus`,
`getExpLevel`/`getDedLevel`), `RandUtil`, `Util::simpleSerialize`. **Never run in CI.**

## How the harness drives the REAL game (faithful to legacy/devsam-core)

This revision of devsam-core does **not** have `DB::setSelfConnInfo` /
`GameConst::getServerConnInfo`, and `GeneralCommand` is **not** hand-constructed.
The harness mirrors the actual source:

- **Boot / DB binding** (`_boot.php`): the live game binds the DB at INSTALL time by
  substituting the container host/creds into `hwe/d_setting/DB.php`
  (`Util::generateFileUsingSimpleTemplate`, exactly what `hwe/j_install_db.php` does),
  then accesses it through `DB::db()`. `_boot.php` performs that substitution
  (+ `ServConfig.php`/`RootDB.php` from `f_install/templates/`), then requires
  `hwe/lib.php` + `hwe/func.php` (the same bootstrap every `hwe/*.php` entrypoint runs;
  `f_config/config.php` defines `ROOT`).
- **Scenario install** (`install_scenario.php`): calls `ResetHelper::buildScenario(...)`
  — the exact headless install `hwe/j_install.php` invokes. It generates
  `d_setting/UniqueConst.php` (the per-game `hiddenSeed`), copies `GameConst.php`,
  imports `hwe/sql/reset.sql` + `schema.sql`, and populates the scenario.
- **Command execution** (`capture_che.php`): builds the command through the game's own
  factory `buildGeneralCommandClass($rawName, $general, $env, $arg)` (the 3-arg
  `BaseCommand::__construct(General, array, $arg=null)` path), re-derives the exact
  per-turn RNG (`TurnExecutionHelper.php:340-348`), and runs `$cmd->run($rng)` — the
  success branch of `TurnExecutionHelper::processCommand`. The captured log + RNG draws
  are byte/draw-identical to a real turn.

## Prerequisite — the devsam capture environment (one-shot, manual host)

Needs a **fully installed, running devsam-core game** (PHP 8.3 + **MariaDB** + composer
`vendor/`). devsam-core's `reset.sql`/`schema.sql` use MariaDB-only features
(`ENGINE=Aria`, `TEXT`-column defaults), so use **MariaDB**, not MySQL 8.

```sh
# Docker network + MariaDB + php:8.3-cli mounting the repo at /work
docker network create devsam-golden-net
docker run -d --name devsam-golden-db --network devsam-golden-net \
  -e MARIADB_ROOT_PASSWORD=rootpw -e MARIADB_DATABASE=samdb \
  mariadb:11.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_bin
docker run -d --name devsam-golden-php --network devsam-golden-net \
  -v "$PWD":/work -w /work/legacy/devsam-core php:8.3-cli sleep infinity
docker exec devsam-golden-php bash -lc 'apt-get update -qq && \
  apt-get install -y -qq libonig-dev libzip-dev unzip git && \
  docker-php-ext-install pdo_mysql mysqli mbstring bcmath'
docker exec devsam-golden-php bash -lc 'cd /work/legacy/devsam-core && php composer.phar install --no-interaction'
```

DB creds reach the PHP scripts via `SAMMO_DB_HOST` / `SAMMO_DB_PORT` /
`SAMMO_DB_USER` / `SAMMO_DB_PASS` / `SAMMO_DB_NAME` (see `_boot.php`). `vendor/` is
gitignored — do **not** commit it.

### devsam capture-env quirks (project memory `project_devsam_capture_quirks`)

- **`getopt` uses the `=` form** — pass `--out=foo`, not `--out foo`.
- **Install is NOT idempotent** — re-running install on a live DB rebuilds it; each
  install draws a **new random `hiddenSeed`** (`bin2hex(random_bytes(16))`), so the
  picks (below) are seed-specific. Capture + dump must run on the SAME install.
- **Dumps are byte-identical** across reruns of the *same stepped state*.

## The scenario + module-free general

`scenario_0` is **empty land** (`【공백지】 일반` — no nations/generals), so it cannot
provide the owned/supplied/non-front-city general the HARD assertions require; the
capture uses a populated standard scenario (**`scenario_1010`**: 174 generals, 2
nations, 24 owned cities, year 181). Every shipped scenario general carries a
personality (`personal`) and starts with `explevel=0` while `experience>0`, so the
harness applies a **static-input precondition** (`applyPrecondition`) to the chosen
general before capture — it does NOT change the computed golden:

- `personal='None'` — the no-op personality (the picked general's personality is
  already a domestic no-op; this makes the module-free assertion literal).
- `explevel`/`dedlevel` **synced** to `getExpLevel(exp)`/`getDedLevel(ded)` and
  `exp`/`ded` nudged to ~40% into their current level band, so the captured turn does
  not cross a level (the spurious first-turn 레벨업 the unsynced install would log is
  the **deferred P2** level-change path — HARD assertion 3 keeps the golden off it).

The action's RNG draws, score, log string, and `comm`/`agri`/`gold`/`exp`/`ded` deltas
are **100% real PHP** for that positioned general.

## Run (manual host, from the repo root, inside the php container)

```sh
DB="-e SAMMO_DB_HOST=devsam-golden-db -e SAMMO_DB_PORT=3306 \
    -e SAMMO_DB_USER=root -e SAMMO_DB_PASS=rootpw -e SAMMO_DB_NAME=samdb"

# 1. install scenario_1010 (draws a fresh hiddenSeed)
docker exec $DB devsam-golden-php bash -lc \
  'cd /work && php tools/php-golden/install_scenario.php --scenario=1010 --turnterm=120 --sync=0'

# 2. probe the picks for THIS install/seed (a single module-free general + the months
#    that give DISTINCT clean success/normal/fail commerce + a normal agri, plus the
#    neutral general for the blocked case). Re-install + re-probe until it prints PICKS
#    (a given seed succeeds ~half the time — the no-level-cross filter is strict).
docker exec $DB devsam-golden-php bash -lc \
  'cd /work && php tools/php-golden/probe_picks.php'
#   → PICKS gid=76 city=1 m_success=1 m_normal=3 m_fail=2 m_agri_normal=2 blocked_gid=1 hiddenSeed=… year=181
#   Fill the $picks array in capture_che.php (gid + months) and SAMMO_BLOCKED_GID
#   from this line. (gid 76's CITY varies per install — the dump derives it.)

# 3. capture the action fixtures (commerce_success/_normal/_fail, agri_normal,
#    blocked_notSupplied) + the per-game hiddenSeed. Leaves the DB in the canonical
#    commerce_success post-turn state for the dump. A freshness guard aborts if the
#    DB is dirty from a prior capture (re-run install_scenario.php first).
docker exec $DB -e SAMMO_BLOCKED_GID=1 devsam-golden-php bash -lc \
  'cd /work && php tools/php-golden/capture_che.php \
     --out=logic/src/test/resources/golden/p1/che-action-fixtures.json'

# 4. dump the post-turn general/city/log_entry rows (aux jsonb in PHP key order).
#    The city is derived from the general; pass --cities only to override.
docker exec $DB devsam-golden-php bash -lc \
  'cd /work && bash tools/php-golden/dump_golden_db.sh \
     --generals=76 \
     --out=logic/src/test/resources/golden/p1/che-golden-db.json'
```

`capture_che.php` enforces these **HARD assertions** (it aborts the capture — no
partial/unfaithful golden is ever written):

1. **Distinct success/normal/fail**, each an independent reproducible fixture (varied
   by month on a single module-free general, the game clock set to the case month so
   the log "N월:" prefix + seed-month agree), each capturing its full env
   (`year/startYear/develCost`) + the six-component seed string.
2. **Module-free general** — `special`/`special2`/`personal` ∈ {`None`,``,null}, every
   equipment slot a `None` item, AND the domestic pipeline is **identity** for
   상업/농업 × {score,cost,success,fail} (the real empty-`GeneralActionPipeline` fold
   guard for this revision: `General::getActionList` folds nationType/officer/special/
   personal/crew/inherit/scenario/items — identity proves none perturb the action).
3. **No level cross** — `explevel(before)==explevel(after)` AND
   `dedlevel(before)==dedlevel(after)` for every case (the deferred P2 level-change log
   path — `General::checkStatChange`, General.php:455-495 — is never exercised).
4. **No unique item won + no static event fired** — exactly ONE action log row (no
   `tryUniqueItemLottery`/`StaticEventHandler::handleEvent` PLAIN line) and the
   equipment-slot count is unchanged (so neither perturbs the action RNG stream).
5. **Integer trust** — the golden city's `trust == floor(trust)`.

It also asserts each `success` case's `max_domestic_critical == prior + score/2` and
that each non-blocked log line carries the spaced action name (`상업 투자` / `농지 개간`)
+ the comma-grouped `<C>scoreText</>`.

## The seed oracle (six-component, TurnExecutionHelper.php:340-348)

```
Util::simpleSerialize(hiddenSeed, 'generalCommand', year, month, generalId, rawClassName)
  rawClassName = che_상업투자 | che_농지개간   (the short class name)
  serialize fmt = str(mb_strlen,VALUE) | int(VALUE) … joined by '|'
  mb_strlen("che_상업투자") = 8  (UTF-16 code units for BMP Hangul)
```
e.g. `str(32,<hiddenSeed>)|str(14,generalCommand)|int(181)|int(7)|int(76)|str(8,che_상업투자)`.

`hiddenSeed` is a **per-game random** (`UniqueConst::$hiddenSeed`). It is captured into
`che-action-fixtures.json` as a fixture **INPUT** (NOT a `:common` constant); C2/G2/F3
seed off this exact value, and the captured `seedString` per case is G2's byte oracle.

## jsonb byte shape (mirror PHP `Json::encode`)

`general.aux` (the meta jsonb) must be: compact (no spaces), keys in insertion order,
UTF-8 literal Korean (no `\uXXXX`), unescaped forward slashes, integers without `.0`.
`dump_golden_db.php` re-encodes the row's `aux` (and city `conflict`) through
`Json::encode` so the committed fragment is the byte oracle the D1 row mappers and the
G4 comparison must match. The action log lives in the `general_record` table
(`log_type='action'`), flushed by the ActionLogger on `$general->applyDB($db)`.

## Deferral (P2, decided — not an open question)

The level-change side effects — `explevel`/`dedlevel` recompute + the secondary PLAIN
`레벨업`/`레벨다운`/`승급`/`강등` logs (`General.php:455-495`) — are EXPLICITLY deferred
to P2. Hard assertion (3) (plus the no-cross precondition) guarantees P1's golden never
needs them. A front-city golden fixture (the PRE/POST-front-debuff scoreText asymmetry)
is likewise deferred to P2; G1 picks a non-front city.
