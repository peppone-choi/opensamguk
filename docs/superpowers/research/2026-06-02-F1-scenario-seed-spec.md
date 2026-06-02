# F1 — Scenario Seed Spec (fresh DB → playable world)

**Date:** 2026-06-02
**Status:** spec (consolidated from 4 reader reports + codebase grounding)
**Goal:** A fresh/empty opensamguk PostgreSQL DB becomes a *playable* world: `nation`/`city`/`general` row counts > 0, the game-engine daemon boots, and it advances at least one turn without error.

---

## 1. Decision summary

- **Where:** A **Kotlin** scenario importer, run by the **game-engine** daemon at boot (an `ApplicationRunner`, mirroring the existing `AdminSeeder` idempotency convention in `app/gateway-api/.../config/AdminSeeder.kt`). It lives in `infra` (the only module allowed to do JDBC writes outside the flush path) and is invoked from a thin `app/game-engine` runner so it executes once on the daemon that owns the world.
- **Trigger:** Run **ONCE, only when the DB is empty** — gated on `SELECT count(*) FROM world_state == 0` (and a belt-and-suspenders `nation count == 0`). If the world row exists, skip entirely (idempotent, like `AdminSeeder`). Optionally also gated on an env flag `SCENARIO_SEED_ENABLED` / `SCENARIO_CODE=scenario_1010`.
- **What it reads:** the grand-truth scenario JSON `legacy/devsam-core/hwe/scenario/scenario_1010.json` PLUS the 24-city map data (cities are NOT in the scenario JSON — see §6). `legacy/` is git-ignored, so the JSON must be **copied into a committed resource** under `infra/src/main/resources/scenario/` (vendored grand-truth values, no Koei IP — these are stat/name tables).
- **What it writes:** direct JDBC `INSERT`s mapping JSON fields → opensamguk columns. **It does NOT pg_dump-restore** — the PHP schema (`storage`, `plock`, `general.no`, `nation.nation`, KV `namespace/key/value`) is a *different shape* from opensamguk (`world_state`, `game_kv`, `general.id`, `nation.id`). A raw PHP dump cannot be inserted; every field must be mapped.

### A-minimal vs B-parity boundary
- **(A) Minimal playable seed — THIS spec's build target.** Non-strict. Inserts deterministic rows so the world boots and a turn advances. RNG-derived fields (city placement, kill-turn offset, turn-time jitter, affinity/personality when null) are **approximated deterministically** (fixed/derived values, NOT real PHP RNG draws). Stat splits are taken **verbatim** from the JSON where present. Goal: unblock the frontend fast.
- **(B) Parity-faithful seed — later, separate phase.** A real `Scenario::build()` port that replays the **exact PHP RNG draw order/count/args** (one `RandUtil(LiteHashDrbg(simpleSerialize(hiddenSeed,'InitScenario')))` for the whole build), produces byte-identical rows + `game_kv`, and is gated against a real `tools/php-golden` capture of the install. Out of scope here; only the boundary is documented.

---

## 2. Minimal-seed table set (A)

These are the tables the importer MUST populate for a playable world. (Row counts confirmed against `WorldStateReadRepository` precheck read + `RehydrateService` + `ReservedTurnHandler` ring read.)

| Table | Rows (A) | Why required |
|-------|----------|--------------|
| `world_state` | 1 (id=1 singleton) | precheck read-path + clock + scenario_code |
| `nation` | 2 (후한, 황건적) + neutral handling | ≥1 playable faction; `id` referenced by general/city/diplomacy |
| `city` | 24 (from map data) | ≥1 owned per nation; production + war target |
| `general` | start with the ~adult subset (or all 491 + 187 ex) | ≥1 controllable; AI turn target |
| `general_turn` | `maxTurn` rows/general (ring) | reserved-turn ring read at boot; slot `(year*12+month)%cap` |
| `nation_turn` | per (nation, officer_level 12..chiefLevel) × maxChiefTurn | nation/AI command ring |
| `diplomacy` | n×(n−1) bidirectional rows | multi-nation relations (state=2 neutral default) |
| `rank_data` | **37 rows/general** (all `value=0`) | flush executor assumes rows EXIST (no INSERT path in P1) |
| `ng_games` | 1 | game-session metadata record |

**Out of A-minimal scope (auto-handled / not needed to boot):**
- `game_kv` — **leave empty**. `RehydrateService.loadObfuscatedNamePool()` auto-bootstraps `obfuscatedNamePool` on first daemon boot via JDBC UPSERT. (B will pre-write the full `game_env` KV set, see §5.)
- `troop` — 0 rows (no troops at scenario start).
- `event` — the scenario `events[]` / deferred-birth events are a B concern; A may skip (engine boots without them, but loses timed history triggers — logged as open question).
- `nation_env`, `message`, `ng_auction*`, `ng_betting` — all empty; `RehydrateService` tolerates absence.

### Required NOT-NULL / no-default columns the importer MUST set
- **world_state:** `scenario_code`, `current_year`, `current_month`, `tick_seconds`. (`config`, `meta` default `{}`; set `start_year`, `start_time`, `turn_term`, `hidden_seed` for reproducibility — `EngineEventConfig.monthlyPipeline` reads `meta.hiddenSeed`/`meta.startYear`/`meta.startTime`.)
- **nation:** `name`, `color`. (`type_code` defaults `che_중립`; set the mapped type.)
- **city:** `name`, `level`, `pop`, `pop_max`, `agri`, `agri_max`, `comm`, `comm_max`, `secu`, `secu_max`, `def`, `def_max`, `wall`, `wall_max`, `region`.
- **general:** `name`, `turn_time`.
- **general_turn:** `general_id`, `turn_idx`, `action_code`.
- **nation_turn:** `nation_id`, `officer_level`, `turn_idx`, `action_code`.
- **diplomacy:** `src_nation_id`, `dest_nation_id`, `state_code`.
- **rank_data:** `general_id`, `type`.
- **ng_games:** `server_id`, `date`, `season`, `scenario`, `scenario_name`.

---

## 3. JSON → opensamguk field mapping

### world_state (from ResetHelper `$env` + scenario header)
| opensamguk column | source | A value | B parity note |
|---|---|---|---|
| id | — | `1` | singleton |
| scenario_code | param | `'scenario_1010'` | — |
| current_year | JSON `startYear` (sync=0) | `181` | B: `cutTurn/cutDay` sync math |
| current_month | computed | `1` | B: sync to wall-clock |
| tick_seconds | turnterm×60 | e.g. `1800` (turnterm 30m) | parity-neutral (cadence) |
| start_year | `startYear` | `181` | — |
| start_time | turntime | now()/epoch | B: real install timestamp |
| turn_term | minutes | tick_seconds/60 | — |
| isunited | const | `0` | — |
| hidden_seed | RNG seed (32 hex) | fixed deterministic hex (A) | **B: `bin2hex(random_bytes(16))`** — A FAKES this |
| config | jsonb | startyear/starttime/turnterm | — |
| meta | jsonb | `{hiddenSeed, startYear, startTime}` (consumed by `EngineEventConfig`) | — |

### nation (JSON `nation[]` 9-tuple: `[name,color,gold,rice,desc,tech,ideology,scale,[cities]]`)
| opensamguk column | source | A value | B note |
|---|---|---|---|
| id | array index (1-based; index 0 neutral skipped) | 1, 2 | — |
| name | tuple[0] | 후한 / 황건적 | — |
| color | tuple[1] | #800000 / #FFD700 | — |
| gold | tuple[2] | 10000 | — |
| rice | tuple[3] | 10000 | — |
| tech | tuple[5] | 1500 / 500 | — |
| level | tuple[7] (scale) | 7 / 2 | maps to `nationLevel`/`getNationChiefLevel` |
| type_code | tuple[6] ideology | `'che_유가'` / `'che_태평도'` (prefix `che_` if no `_`) | **B: if type null → `rng->choice($availableNationType)`** — A never RNG-picks |
| capital_city_id | first city of nation's city list | resolved city id | B: PHP `$capital` logic |
| meta | jsonb | `{gennum, infoText, aux:{can_국기변경:1}}` | `nation_env.scout_msg = infoText` in B |

### city (NOT in scenario JSON — from map data §6; nation assignment from `nation[].cities`)
| opensamguk column | source | A value | B note |
|---|---|---|---|
| id, name, level, region | map data | verbatim | — |
| nation_id | reverse-lookup: which nation lists this city name | mapped id, else `0` | — |
| pop/agri/comm/secu (+_max) | map data base × initialEvents ratio | **`*_max` × 70%** (per `initialEvents` ChangeCity 70%) | parity-OK (ratio is in JSON) |
| def/wall (+_max) | map data | occupied: `*_max × 70%`; free: base | — |
| trust | initialEvents | `80.0` | — |
| trade | const | `null` or 100 | RandomizeCityTradeRate writes NULL on fail |
| supply_state/front_state/term/officer_set/conflict/meta | defaults | `1/0/0/0/{}/{}` | — |

### general (JSON `general[]` 13-tuple + `general_ex[]`)
**13-tuple index:** `[0]=nation_id, [1]=name, [2]=unique_id, [3]=type, [4]=null, [5]=intel, [6]=force/strength, [7]=politics→leadership, [8]=0, [9]=birth, [10]=death, [11]=personality, [12]=skill, [13]=quote]`

> NOTE the stat naming trap: JSON order is **intel, force, politics**. PHP maps politics→`leadership`(통솔), force→`strength`(무력), intel→`intel`(지력). Confirm against `GeneralBuilder` before finalizing (open question Q1).

| opensamguk column | source | A value | B parity note |
|---|---|---|---|
| id | tuple[2] unique_id (1001..) or seq | use unique_id | — |
| name | prefix(npc)+tuple[1] | `ⓝ`+name for NPC (prefix table in GeneralBuilder) | — |
| nation_id | tuple[0] | as-is (warlord ids 6–149, 999 → see Q2) | — |
| city_id | NOT in JSON | **A: first city of nation, deterministic** | **B: `rng->choice(nationCities|allCities)`** — A FAKES placement |
| leadership | tuple[7] politics | verbatim | — |
| strength | tuple[6] force | verbatim | — |
| intel | tuple[5] intel | verbatim | — |
| born_year | tuple[9] | verbatim | — |
| dead_year | tuple[10] | verbatim | — |
| age | startYear − born | computed | — |
| experience / dedication | — | `age*100` | matches PHP default branch |
| officer_level | ruler logic | ruler→12 else 0/1 | B: postBuild ruler selection by leadership+str+intel |
| npc_state | tuple[3] type / npc flag | type 0→0(player-able), else NPC>0 | — |
| personal_code | tuple[11] | mapped enum or `'None'` | **B: if null → `rng->choice($availablePersonality)`** — A uses 'None' |
| special_code / special2_code | tuple[12] skill | mapped (domestic/war split) | **B: spec RNG + specage** |
| affinity | — | `null` or fixed | **B: if null & non-fiction → `rng->nextRangeInt(1,150)`** — A FAKES |
| turn_time | — | `now()`/epoch | **B: `getRandTurn(rng,...)`** — A FAKES jitter |
| gold/rice | — | `1000`/`1000` (or JSON if present) | — |
| crew/crew_type_id/train/atmos | — | `0`/`0`/`0`/`0` | — |
| weapon/book/horse/item_code | — | `'None'` | — |
| picture | tuple/icon | `'default.jpg'` (show_img_level<3) | — |

### general_turn (generated, per general)
| column | A value |
|---|---|
| general_id | general id |
| turn_idx | `0 .. (maxTurn−1)` |
| action_code | `'휴식'` (PHP install seeds action='휴식', brief='휴식') |
| arg | `{}` |
| brief | `'휴식'` |

> **Capacity:** PHP inserts `GameConst::$maxTurn` (default 600) rows. The opensamguk daemon reads slot `(year*12+month) % capacity`. A may seed the full `maxTurn` (matches PHP) OR a smaller ring (≥ enough to cover the first turn's slot). Confirm the opensamguk reserved-turn capacity constant (Q3) — reader claimed 24 but no `RESERVED_TURN_CAPACITY` constant was found in `ReservedTurnHandler.kt`. Safest A: seed `maxTurn` (600) to match PHP and guarantee the slot exists.

### nation_turn (generated)
- For each nation, for `officer_level` in `12 .. getNationChiefLevel(level)` (descending), for `turn_idx` in `0..maxChiefTurn−1`: `action_code='휴식'`, `arg={}`, `brief='휴식'`.

### diplomacy (generated + JSON `diplomacy[]`)
- Default: all ordered nation pairs `(me,you)` and `(you,me)` → `state_code=2` (neutral), `term=0`.
- Then apply JSON `diplomacy[]` entries `[me,you,state,remainMonths]`: set `state_code=state`, `term = remain − monthDiff` where `monthDiff=(year*12+month−1)−(startyear*12)`. (At startYear/month1, monthDiff=0 ⇒ `term=remain`.)
- opensamguk extras: `is_dead=false`, `is_showing=true`, `meta={}`.

### rank_data (generated, per general — 37 rows)
- For each of the **37 `RankColumn` cases** (confirmed verbatim in `app/game-engine/.../TurnWorldModel.kt:148-186`): `firenum, warnum, killnum, deathnum, killcrew, deathcrew, ttw, ttd, ttl, ttg, ttp, tlw, tld, tll, tlg, tlp, tsw, tsd, tsl, tsg, tsp, tiw, tid, til, tig, tip, betwin, betgold, betwingold, killcrew_person, deathcrew_person, occupied, inherit_earned, inherit_spent, inherit_earned_dyn, inherit_earned_act, inherit_spent_dyn`.
- `general_id=<id>`, `nation_id=0` (PHP install uses 0 here), `type=<column>`, `value=0`.

### ng_games (1 row)
| column | A value |
|---|---|
| server_id | generated `opensamguk_<date>_<rand4>` |
| date | start time |
| season | `1` |
| scenario | `1010` |
| scenario_name | JSON `title` (황건적의 난) |
| env | jsonb of the `$env`-equivalent map |
| winner_nation / map | `null` / map theme |

---

## 4. Importer trigger / placement design

```
GameEngineApplication boot
  └─ ScenarioSeedRunner (ApplicationRunner, @Component in app/game-engine)
       1. if (worldStateCount() > 0) → log "world exists, skip" ; return   ← idempotent gate
       2. load vendored scenario_1010.json (infra/src/main/resources/scenario/)
       3. load 24-city map data (committed resource)
       4. ScenarioImporter.importAll(jdbcTemplate)   ← lives in infra
            - INSERT world_state(1)
            - INSERT nation × N
            - INSERT city × 24 (with nation_id reverse-mapped)
            - UPDATE nation.capital_city_id
            - INSERT general × M (+ rank_data ×37 each, general_turn ×cap each)
            - INSERT nation_turn
            - INSERT diplomacy (default neutral + JSON overrides)
            - INSERT ng_games(1)
       5. log row counts
```

- **Module placement:** the `ScenarioImporter` (JDBC writer) goes in **`infra`** (`infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt`) — infra is the JDBC-write home and is depended on by game-engine. The `ScenarioSeedRunner` (Spring `ApplicationRunner` + emptiness gate) goes in **`app/game-engine`** so the daemon that owns the world performs the seed. JSON parsing model goes in infra too (`ScenarioJson.kt`).
- **Does NOT violate the one-daemon-write rule:** that rule forbids the daemon using a JPA `EntityManager` for *gameplay* writes. The importer is a **bootstrap row-loader via raw JDBC**, not a turn-resolver write — same category as Flyway/`AdminSeeder`. It uses `JdbcTemplate`, never JPA dirty-checking, and runs before any turn loop. Document this explicitly so the architecture test author whitelists it.
- **Ordering vs RehydrateService:** the seed runner MUST run before the first turn tick. `game_kv` stays empty; `RehydrateService` bootstraps `obfuscatedNamePool` independently on first rehydrate. No conflict.
- **Note (separate gap, NOT F1):** there is currently no DB→`WorldSnapshot` loader in main code (`InMemoryTurnWorld` is injected as a bean dependency; only tests build `WorldSnapshot` by hand, and `EngineEventConfig` has no `@Bean InMemoryTurnWorld`). F1's gate is *rows exist + engine advances a turn*; if "advances a turn" requires the daemon to actually load the world from DB, the DB→snapshot loader is a prerequisite and should be flagged (open question Q4). F1 itself only guarantees the rows are present and self-consistent.

---

## 5. A-vs-B boundary (the approximation ledger)

Fields A approximates and B must parity-correct (every one is an RNG draw or sync-math in PHP):

| Field | A (minimal) | B (parity) — the real PHP draw |
|---|---|---|
| `world_state.hidden_seed` | fixed deterministic hex | `bin2hex(random_bytes(16))` then `simpleSerialize(seed,'InitScenario')` seeds the build RNG |
| `world_state.current_year/month` | startYear / 1 | `cutTurn`/`cutDay` sync math (sync=1) |
| `general.city_id` | first nation city (deterministic) | `rng->choice(nationCities | allCities)` — **order/count parity-critical** |
| `general.affinity` | null/fixed | `rng->nextRangeInt(1,150)` when null & non-fiction |
| `general.personal_code` | 'None'/mapped | `rng->choice($availablePersonality)` when null (fiction/reset) |
| `general.special*_code` + specage | mapped/'None' | spec RNG draws + spec-age math |
| `general.killturn` | fixed/from death | `(death−year)*12 + rng->nextRangeInt(0,11) + month−1` |
| `general.turn_time` | now()/epoch | `getRandTurn(rng, turnterm, turntime)` jitter |
| `nation.type_code` | from JSON ideology | `rng->choice($availableNationType)` when type null |
| `game_kv` (game_env 25+ keys) | empty (RehydrateService bootstraps name pool) | full `$env` KV written (scenario_text, icon_path, develcost, killturn, maxgeneral, maxnation, season, etc.) |
| RNG draw order | NONE (A makes zero draws) | **ONE `RandUtil` over the whole build**, draw-for-draw vs `tools/php-golden` capture |
| `event[]` + deferred births | skipped (A) | inserted (timed history triggers) |

**The single most important B invariant:** the entire scenario build runs on **one** `RandUtil(LiteHashDrbg(simpleSerialize(hiddenSeed,'InitScenario')))`, and the draw order is: nations (type choice) in JSON order → generals (`general` → `general_ex` → `generalsNeutral`), and within each general affinity→personality→city→killturn→turntime. A makes **zero** draws, so A and B will produce different rows — A is explicitly non-strict and not gated against a PHP golden.

---

## 6. The city-data gap (critical)

Cities are **NOT** in `scenario_1010.json` (`cities: []`) and **NOT** in `default.json` (keys: `stat`, `iconPath` only). PHP loads them from `CityConst` (`legacy/devsam-core/hwe/d_setting/CityConst.orig.php` + `CityConstBase.php`, built via `CityConst::build()`), and there is also the user-provided 260-CE 3,222-point dataset (per project memory) as the opensamguk map source. The importer needs a committed **24-city table** (id, name, level, region, pop_max/agri_max/comm_max/secu_max/def_max/wall_max) for the 24 cities named in `nation[].cities` (후한 14 + 황건적 10). **Source of city base stats must be resolved (Q5)** — either transcribe from `CityConst.orig.php` (grand truth) or from the 260 dataset. This is a hard dependency for A; without it `city` cannot be seeded with its NOT-NULL columns.

---

## 7. Build tasks — see StructuredOutput

## 8. Gate criterion

Against a fresh PostgreSQL (Flyway V1–V9 applied, all tables empty), running the daemon once:
- `world_state`=1, `nation`≥2, `city`=24, `general`≥1, and for every general `rank_data`=37 and `general_turn`≥1 (covering the boot slot);
- the daemon boots without exception and **advances at least one monthly turn** (or, if the DB→snapshot loader is not yet present, a focused integration test loads the seeded rows into a `WorldSnapshot` and `TurnRunService` advances one turn green);
- re-running the daemon a second time is a **no-op** (idempotent skip — second run inserts 0 rows).
