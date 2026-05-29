# php-golden — P1 che_상업투자 / che_농지개간 golden capture

One-shot golden generator. Boots the **devsam-core PHP** capture environment,
steps a single turn for a deliberately module-free general, and emits the
committed P1 golden fixtures that the Kotlin byte-match gate asserts against:

| Output (committed) | Asserted by |
| --- | --- |
| `logic/src/test/resources/golden/p1/che-action-fixtures.json` | G2 `CommerceActionLogGoldenTest`, F3/C2 seed oracle |
| `logic/src/test/resources/golden/p1/che-golden-db.json` | G4 `VerticalSliceE2EIT` step 5 (row + meta jsonb byte-compare) |

**Oracle = PHP devsam-core** (the parity grand truth — where PHP diverges from
the TS reference, PHP wins). Generate **ONCE**, commit the JSON + DB fragment,
and regenerate **ONLY** when the PHP source changes:
`che_상업투자.php`, `func_process.php` (seed), `func_gamerule.php`
(`updateMaxDomesticCritical`, `CriticalRatioDomestic`), `func_converter.php`
(`getDomesticExpLevelBonus`), `RandUtil`, `Util::simpleSerialize`. **Never run
in CI.**

## Prerequisite — the devsam capture environment (one-shot, manual host)

This step needs a **fully installed, running devsam-core game** (PHP 8.3 +
MySQL + composer `vendor/`), which the spec calls the *manual host capture
environment*. It is intentionally **not** part of this repo's build and is not
available on a clean CI/dev host (no `php`, no DB client, no `vendor/`). Bring it
up from `legacy/devsam-core` using the **devsam/docker** stack (a separate
upstream repo — see project memory `project_devsam_core_internals`):

1. `composer install` in `legacy/devsam-core` (PHP 8.3; `composer.json` pins it).
2. Start MySQL and import the schema (`legacy/devsam-core/hwe/sql/schema.sql`).
3. Install a game on the **scenario_0** seed
   (`legacy/devsam-core/hwe/scenario/scenario_0.json`).

### devsam capture-env quirks (project memory `project_devsam_capture_quirks`)

Knowing these saves ~30 minutes:

- **`j_install.php` is called TWICE** — the first call seeds, the second
  finalizes. A single call leaves the install half-built.
- **`getopt` uses the `=` form** — pass `--serverID=foo`, not `--serverID foo`.
  Both `capture_che.php` and `dump_golden_db.sh` follow this.
- **Reflection credentials** — DB credentials are bound via reflection at boot
  (`DB::setSelfConnInfo(GameConst::getServerConnInfo($serverID))`), not a flat
  config you can edit.
- **Install is NOT idempotent** — re-running install on a live DB corrupts it;
  drop + reimport the schema to start clean.
- **Dumps are byte-identical** across reruns of the *same stepped state* — so
  the committed golden is reproducible; a diff means the PHP source changed.

## Run (manual host, from the repo root)

The capture picks ONE module-free general in an owned, **supplied**, **non-front**
city with `commerce < commerceMax` / `agriculture < agricultureMax` and
`gold >= round(develCost)`, with **no triggers affecting 상업/농업**
(no specialDomestic/specialWar/personality module fires). Fill the
`generalId`/`month` tuples in `capture_che.php`'s `$picks` array (and
`SAMMO_BLOCKED_GID` for the blocked case) from the scenario_0 seed, probing
months until **distinct** success/normal/fail picks fall out — the hard
assertions abort if any two collide or if the general carries a module.

```sh
# 1. capture the action fixtures (commerce_success/_normal/_fail, agri_normal,
#    blocked_notSupplied) + the per-game hiddenSeed (committed as a fixture INPUT).
SAMMO_BLOCKED_GID=<gid> \
php tools/php-golden/capture_che.php \
    --serverID=<server> \
    --out=logic/src/test/resources/golden/p1/che-action-fixtures.json

# 2. dump the post-tick general/city/log_entry rows (meta jsonb in PHP key order).
tools/php-golden/dump_golden_db.sh \
    --serverID=<server> \
    --generals=<gid1>,<gid2>,... \
    --cities=<cid1>,<cid2>,... \
    --out=logic/src/test/resources/golden/p1/che-golden-db.json
```

`capture_che.php` enforces these **HARD assertions** (it aborts the capture —
no partial/unfaithful golden is ever written):

1. **Distinct success/normal/fail**, each an independent reproducible fixture
   (varied by generalId/month), each capturing its full env
   (`year/startYear/develCost`) + the six-component seed string.
2. **Module-free general** — `special`/`special2`/`personal` empty, all 8 effect
   slots null, `itemObjs` empty (the P1 empty-`GeneralActionPipeline` identity
   fold is provably faithful — §14 9-source-stack guard).
3. **No level cross** — `getExpLevel(before)==getExpLevel(after)` AND
   `getDedLevel(before)==getDedLevel(after)` for every case (the deferred P2
   level-change log path is never exercised by the golden).
4. **No unique item won + no static event fired** — `tryUniqueItemLottery`
   granted nothing and `StaticEventHandler::handleEvent` produced no event/log
   (so neither perturbs the action RNG stream — the seed stream stays exact).
5. **Integer trust** — the golden city's `trust == floor(trust)` (the Int
   baseline `city.trust` column is lossless + byte-comparable).

It also asserts each `success` case's `max_domestic_critical == prior + score/2`
and that each non-blocked log line contains the spaced action name
(`상업 투자` / `농지 개간`) + the comma-grouped `<C>scoreText</>`.

## The seed oracle (six-component, from `func_process.php:340-347`)

```
Util::simpleSerialize(hiddenSeed, 'generalCommand', year, month, generalId, rawClassName)
  rawClassName = che_상업투자 | che_농지개간   (definition.key — the spaced name with " " stripped)
  str(len,…) length = mb_strlen = UTF-16 code units for BMP Hangul
```

`hiddenSeed` is a **per-game random** (`UniqueConst::$hiddenSeed`, set per
`d_setting`). It is captured into `che-action-fixtures.json` as a fixture
**INPUT** (NOT a `:common` constant); C2/G2/F3 seed off this exact value, and
the captured `seedString` per case is G2's final byte oracle.

## jsonb byte shape (mirror PHP `Json::encode`)

`general.meta` (PHP `aux`) must be: compact (no spaces), keys in insertion order
(`LinkedHashMap`-backed), UTF-8 literal Korean (no `\uXXXX`), unescaped forward
slashes, integers without `.0`. `dump_golden_db.php` re-encodes the row's jsonb
through `Json::encode` so the committed fragment is the byte oracle the D1 row
mappers and the G4 comparison must match.

## Deferral (P2, decided — not an open question)

The level-change side effects — `explevel`/`dedlevel` recompute + the secondary
PLAIN `레벨업`/`레벨다운`/`승급`/`강등` logs (`General.php:448-495`) — are
EXPLICITLY deferred to P2. Hard assertion (3) guarantees P1's golden never needs
them. A front-city golden fixture (the PRE/POST-front-debuff scoreText
asymmetry) is likewise deferred to P2; G1 picks a non-front city.
