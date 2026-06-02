# F1 — Scenario Seed Build Resolution (Q1–Q6 resolved)

**Date:** 2026-06-02
**Status:** resolved (5 investigation reports + grand-truth re-verification against `legacy/devsam-core` PHP and `scenario_1010.json`)
**Supersedes the open questions in:** `docs/superpowers/research/2026-06-02-F1-scenario-seed-spec.md`
**Goal restated:** fresh/empty PostgreSQL → `nation`/`city`/`general` rows > 0 → game-engine daemon boots → advances ≥1 turn without error.

> **READ FIRST — three report claims were wrong and are corrected here against grand truth.** Two of the five
> investigation reports contained fabricated field semantics that, if built on, would have silently corrupted
> the seed. I re-derived every load-bearing number directly from the PHP `list()` destructuring and the actual
> `scenario_1010.json`. The corrections are called out inline (CORRECTION blocks). The numbers below are the ones
> to build against.

---

## Resolved answers (Q1–Q6)

### Q1 — General stat-name mapping (the "naming trap"). RESOLVED: positional, from PHP `list()`.

Grand truth is `Scenario.php::generateGeneral()` lines 58–62 + `->setStat($leadership,$strength,$intel)` line 88.
The 14-slot raw row (padded to 14 by lines 54–56) destructures as:

| idx | PHP var | meaning | opensamguk `general` column |
|----|----------|---------|------------------------------|
| 0 | `$affinity` | 친화 (≥900 ⇒ fiction marker 999) | `affinity` (A: store as-is) |
| 1 | `$name` | 무장명 | `name` (+NPC prefix) |
| 2 | `$picturePath` | icon id / path (e.g. `1001`) | `picture` (A: `'default.jpg'`) |
| 3 | `$nationName` | **nation id** (or name) | `nation_id` (lookup, else 0) |
| 4 | `$locatedCity` | start city — **null in 1010** | `city_id` (A: derived, see Q5) |
| **5** | **`$leadership`** | **통솔** | **`leadership`** |
| **6** | **`$strength`** | **무력** | **`strength`** |
| **7** | **`$intel`** | **지력** | **`intel`** |
| 8 | `$officerLevel` | 관직 | `officer_level` |
| 9 | `$birth` | 출생년 | `born_year` |
| 10 | `$death` | 사망년 | `dead_year` |
| 11 | `$ego` | 성격 (e.g. '유지','안전') | `personal_code` (mapped) |
| 12 | `$char` | 특기 (null in 1010 early rows) | `special_code`/`special2_code` |
| 13 | `$text` | NPC 대사 | (meta/none) |

**DEFINITIVE STAT MAPPING (build against THIS, identity by position):**
```
JSON general[i][5] → general.leadership   (통솔)
JSON general[i][6] → general.strength     (무력)
JSON general[i][7] → general.intel        (지력)
```

> **CORRECTION to the F1 spec §3 line 101.** The spec's draft tuple `[0]=nation_id … [5]=intel,[6]=force,[7]=politics`
> is the trap itself — **both the field positions AND the stat order are wrong.** PHP `[0]` is `affinity` (not
> nation_id), nation_id is `[3]`, and the stat order is **leadership, strength, intel** (NOT intel, force, politics).
> Verified on a real row: `소제1 = [1,'소제1',1001,1,null,20,11,48,0,168,190,'유지',null]` → affinity 1, nation 1
> (후한), leadership 20, strength 11, intel 48 — consistent with a low-martial boy-emperor. The `inv:stat-mapping`
> report's positional mapping is the correct one; its tuple-index labels (it wrote `[5]=leadership` etc.) match PHP.

### Q2 — `general[]` vs `general_ex[]` vs neutral. RESOLVED.

- `general[]` (491 rows) and `general_ex[]` (187 rows) are **both npcType=2** rosters; the split is organizational
  (primary vs extended). Both INSERT into the **same `general` table**, distinguished only by `npc_state`.
- `general_neutral[]` is **0 rows** in scenario_1010. The neutral pool is NOT a separate array here — it is encoded
  **inline** as `nation_id = 0` on rows inside `general[]`/`general_ex[]`.
- Nation-resolution fallback (lines 64–72): if the row's `nationName` is neither a known nation name nor a known id,
  `nationID → 0`. In 1010 the only nation ids that appear are `{0, 1, 2}`, so the fallback never fires — every row
  is already 0, 1, or 2.

> **CORRECTION to `inv:warlord-scope`.** That report claimed 221 generals, 96 distinct nation ids (1,2,6–149,999),
> and 166 "gracefully degraded" neutrals. **All three numbers are fabricated.** It read index 0 (affinity) as the
> nation id. The real array is **678 generals** (491 + 187), the **only** nation ids present are **{0,1,2}**, and
> there are **no** warlord refs 6–149 and **no** 999 nation refs (999 appears only as an *affinity* fiction marker).
> The "graceful degradation" narrative does not apply to 1010.

### Q3 — Reserved-turn ring capacity + slot formula. RESOLVED: 30 / 12, ring is shift-rotated (not month-mod).

- General ring: `MAX_GENERAL_TURNS = 30` (= `GameConst.maxTurn = 30`). Slot = `((turnIdx % 30) + 30) % 30`.
- Nation/chief ring: `MAX_CHIEF_TURNS = 12` (= `GameConst.maxChiefTurn = 12`). Slot = `((turnIdx % 12) + 12) % 12`.
- The boot slot is slot 0. The engine `readReserved(generalId, turnIdx)` reads `general_turn` WHERE `turn_idx = slot`,
  and after each turn `pullGeneralTurn` shifts every row down by 1 (vacated slot recycles to the tail as 휴식). So
  `turn_idx` is a **relative ring position 0..29**, rotated by the daemon — **NOT** `(year*12+month) % cap`.
- Missing-row fallback returns `ReservedTurn("휴식","{}")`, so a sparse seed *technically* boots, but best practice
  (and PHP parity) is to seed the full ring.

> **CORRECTION to the F1 spec §3 line 138.** The spec said "PHP inserts `GameConst::$maxTurn` (default 600) rows …
> safest A: seed 600." **opensamguk `GameConst.maxTurn = 30`, not 600.** Seed exactly **30** general rows (idx 0..29)
> and **12** nation rows (idx 0..11) per officer. The spec's `(year*12+month) % capacity` slot formula is also wrong;
> use the relative-ring formula above. The `inv:turn-ring` report is correct.

### Q4 — Is row-seed enough, or is a DB→snapshot loader a prerequisite? RESOLVED: **LOADER IS A HARD PREREQUISITE.** ⚠️ PIVOTAL

**Row-seeding alone is NOT sufficient for "daemon boots + advances a turn." A DB→`WorldSnapshot` loader does not
exist in production and must be built as a prerequisite (or co-requisite) task.**

Verified facts:
- `InMemoryTurnWorld(snapshot: WorldSnapshot)` is consumed as a constructor/bean dependency by ~15 main-code sites
  (`TurnRunService`, `EngineEventConfig`, `ReservedTurnHandler`, `AiTurnAdapter`, auction/betting handlers, …) but
  **nothing in `src/main` constructs `WorldSnapshot`** — the only `WorldSnapshot(` constructor call in production is
  the `data class` declaration itself; every instantiation is in `*/test/` (e.g. `TurnRunServiceIT`).
- There is **no `@Bean fun inMemoryTurnWorld(...)`** anywhere in game-engine main.
- There is **no `ApplicationRunner` / `CommandLineRunner` / `@PostConstruct`** in game-engine main that builds a world.
- `RehydrateService` loads only *survivor* state (name pool, inheritance KV, auctions, bids, bettings, messages) —
  **NOT** `general`/`city`/`nation`/`diplomacy`/`troop`/`world_state` into a snapshot.
- `JdbcFlushExecutor` is **write-only** (no SELECT/load path).
- The reverse row mappers DB→domain **already exist** and are usable by the loader: `GeneralRowMapper`,
  `CityRowMapper`, `NationRowMapper`, `DiplomacyRowMapper`, `NationTurnRowMapper`, `GameKvRowMapper`,
  `AuctionRowMapper`, `AuctionBidRowMapper`, `MessageRowMapper`, `NgBettingRowMapper` (in
  `infra/src/main/kotlin/opensamguk/infra/persistence/`). **No `TroopRowMapper` was found — flag it (Q4 sub-gap).**

**Consequence for the F1 gate.** F1's own deliverable is *rows exist + self-consistent*. But the stated gate
("daemon advances one turn") cannot be met by row-seeding alone — Spring wiring fails at startup on the unsatisfied
`InMemoryTurnWorld` dependency before any tick runs. Therefore F1 must either (a) include a minimal
`WorldSnapshotLoader` + `@Bean`, or (b) split the loader into a named prerequisite task **F1-PRE** and have F1's gate
fall back to the focused integration-test form already permitted by spec §8 ("a test loads the seeded rows into a
`WorldSnapshot` and `TurnRunService` advances one turn green"). **Recommendation: build the loader as part of this
A-minimal effort (tasks T9–T10 below) so the real daemon-boot gate is achievable, not just the test gate.**

> The `inv:boot-loader` report is correct and is the pivotal finding. The F1 spec already half-flagged this in §4
> note ("Note (separate gap, NOT F1)") — this resolution upgrades it from a footnote to a **build prerequisite**.

### Q5 — City base stats + map x/y, and where coords are stored. RESOLVED.

- Cities are **NOT** in `scenario_1010.json` (`cities: []`, confirmed). PHP builds them from
  `CityConstBase::$initCity` (the world baseline; `CityConst.orig.php`/`CityConst.php` extend it). **This is the
  single authoritative A source — transcribe the 24 named cities from it.**
- `$initCity` row shape (CityConstBase.php line 58+):
  `[id, name, level(규모), pop(인구×100), agri(농), comm(상), secu(치), def(성), wall(수), region(지역), x, y, [connected]]`
  — i.e. the **base** stat is column-aligned; the `_max` in opensamguk equals the CityConstBase base value, and the
  initial (non-max) value is the `initialEvents` ChangeCity 70% ratio for occupied cities.
- **x/y live in CityConstBase columns 10 & 11 for ALL cities** (e.g. 업 x=345 y=130, 허창 x=330 y=215, 낙양 x=275 y=180).
- **opensamguk `city` has NO x/y columns.** Map coords are client-display data and are NOT persisted in the relational
  `city` table. For F1 (server boot), x/y are **not needed by the engine** — seed only the stat columns. For the
  frontend (P7), emit a committed `cities_1010.json` carrying `{id,name,level,region,pop_max,agri_max,comm_max,
  secu_max,def_max,wall_max,x,y,nation_id}`; the `city.meta` jsonb is available if persistence is ever wanted.
- `level` and `region` are **integer** columns; the Korean glyphs (특/대/중/소/진/관/이) and region names
  (중원/하북/서북/…) are display labels that must be mapped to their integer codes during transcription.

> **CORRECTION to `inv:city-data-coords`.** That report claimed "15/24 cities have coords from miniche.php; 9 missing,
> fall back to CityConstBase." This is misleading: **all 24 cities have x/y in CityConstBase** (the report's own
> "missing" cities like 역경/호관 are present there). Its x/y values also differ from CityConstBase (it pulled some
> from the miniche overlay, e.g. 업 355/135 vs base 345/130). For A-minimal, **use CityConstBase x/y exclusively** —
> there is no coord gap. The 24-city stat table below is reconciled to CityConstBase as the source of record.

### Q6 — A-minimal general/nation scope + counts. RESOLVED.

- **Nations:** exactly **2** defined — 후한 (id 1, type 유가→`che_유가`, level/scale 7, 14 cities) and 황건적
  (id 2, type 태평도→`che_태평도`, level/scale 2, 10 cities). Plus the implicit neutral nation 0 (재야) that PHP
  creates (`Scenario.php` line 114) — A may model it as nation_id 0 with no `nation` row (generals just carry 0).
- **Cities:** **24** (후한 14 + 황건적 10).
- **Generals (TOTAL 678):** seed **all 678** (491 `general[]` + 187 `general_ex[]`). Faction-assigned = **66**
  (후한 43 = 39 main + 4 ex; 황건적 23). Neutral (nation_id 0) = **612**. All 678 have `locatedCity = null`, so
  city placement is a deterministic A-approximation (see build plan T6) for every general.
- **A-minimal vs the spec:** the spec hedged "start with ~adult subset (or all 491 + 187 ex)". Resolution: seed
  **all 678** — there is no adult/age filter needed to boot, and counts are small. No JSON pruning (the warlord
  report's "Option A: delete nations 6–149" is moot — those nations do not exist in 1010).

---

## DEFINITIVE 24-city table (source of record: `CityConstBase::$initCity`)

Stats are the CityConstBase **base = `_max`** values. `level`/`region` shown as glyph/name → must map to the integer
code on insert. x/y are client-only (NOT inserted into `city`; carried in `cities_1010.json`).

| # | nation | city | id | level | region | pop(×100) | agri_max | comm_max | secu_max | def_max | wall_max | x | y |
|---|--------|------|----|-------|--------|-----------|----------|----------|----------|---------|----------|---|---|
| 1 | 후한 | 낙양 | 3 | 특 | 중원 | 8357 | 117 | 120 | 100 | 121 | 124 | 275 | 180 |
| 2 | 후한 | 계 | 17 | 중 | 하북 | 3885 | 75 | 80 | 60 | 78 | 81 | 386 | 55 |
| 3 | 후한 | 역경 | 77 | 진 | 하북 | 985 | 18 | 19 | 20 | 39 | 41 | * | * |
| 4 | 후한 | 진양 | 35 | 소 | 하북 | 3074 | 56 | 59 | 40 | 64 | 59 | 310 | 75 |
| 5 | 후한 | 남피 | 9 | 대 | 하북 | 5032 | 99 | 101 | 80 | 101 | 105 | 395 | 95 |
| 6 | 후한 | 호관 | 70 | 관 | 하북 | 887 | 19 | 18 | 20 | 95 | 96 | * | * |
| 7 | 후한 | 호로 | 71 | 관 | 중원 | 1112 | 22 | 21 | 20 | 103 | 98 | * | * |
| 8 | 후한 | 사곡 | 72 | 관 | 서북 | 1008 | 21 | 19 | 20 | 99 | 101 | * | * |
| 9 | 후한 | 함곡 | 73 | 관 | 서북 | 1081 | 20 | 22 | 20 | 101 | 102 | * | * |
| 10 | 후한 | 사수 | 74 | 관 | 중원 | 958 | 17 | 19 | 20 | 95 | 96 | * | * |
| 11 | 후한 | 하내 | 23 | 중 | 서북 | 3736 | 77 | 81 | 60 | 81 | 80 | 295 | 140 |
| 12 | 후한 | 장안 | 4 | 특 | 서북 | 5923 | 116 | 123 | 100 | 120 | 118 | 145 | 165 |
| 13 | 후한 | 홍농 | 42 | 소 | 서북 | 2748 | 57 | 63 | 40 | 58 | 63 | 220 | 170 |
| 14 | 후한 | 완 | 10 | 대 | 중원 | 4724 | 103 | 100 | 80 | 101 | 99 | 270 | 235 |
| 15 | 황건적 | 업 | 1 | 특 | 하북 | 6205 | 125 | 113 | 100 | 117 | 122 | 345 | 130 |
| 16 | 황건적 | 계교 | 78 | 진 | 하북 | 1012 | 21 | 19 | 20 | 40 | 42 | * | * |
| 17 | 황건적 | 진류 | 19 | 중 | 중원 | 3957 | 82 | 80 | 60 | 80 | 83 | 370 | 175 |
| 18 | 황건적 | 관도 | 80 | 진 | 중원 | 1123 | 22 | 20 | 20 | 42 | 43 | * | * |
| 19 | 황건적 | 정도 | 81 | 진 | 중원 | 1085 | 21 | 21 | 20 | 41 | 38 | * | * |
| 20 | 황건적 | 평원 | 36 | 소 | 하북 | 3074 | 62 | 65 | 40 | 61 | 63 | 445 | 110 |
| 21 | 황건적 | 복양 | 18 | 중 | 중원 | 4185 | 80 | 83 | 60 | 82 | 80 | 412 | 170 |
| 22 | 황건적 | 패 | 39 | 소 | 중원 | 2877 | 64 | 58 | 40 | 58 | 59 | 425 | 210 |
| 23 | 황건적 | 허창 | 2 | 특 | 중원 | 5876 | 121 | 124 | 100 | 117 | 125 | 330 | 215 |
| 24 | 황건적 | 초 | 38 | 소 | 중원 | 3286 | 60 | 62 | 40 | 62 | 57 | 375 | 225 |

\* x/y for the 진/관-level cities (역경/호관/호로/사곡/함곡/사수/계교/관도/정도) must be transcribed from their own
`$initCity` rows in CityConstBase during T2 — they exist there (this table omits the ones I did not directly read out;
**T2 transcribes all 24 verbatim from CityConstBase, do NOT hand-copy from this summary**). The 15 dense-stat cities
above are reconciled to CityConstBase base values; the 9 starred rows' stats come from the `inv:city-data-coords`
report and must be re-verified against CityConstBase rows 70–81 during T2.

**Ownership (from `nation[].cities` in scenario_1010.json):**
- 후한 (id 1): 낙양, 계, 역경, 진양, 남피, 호관, 호로, 사곡, 함곡, 사수, 하내, 장안, 홍농, 완 (14)
- 황건적 (id 2): 업, 계교, 진류, 관도, 정도, 평원, 복양, 패, 허창, 초 (10)
- Any CityConstBase city NOT in either list → `nation_id = 0`. (A-minimal: only seed the 24 named; do not seed the full world map.)

---

## Ring capacity + slot formula (build constants)

| | general ring | nation/chief ring |
|---|---|---|
| capacity constant | `MAX_GENERAL_TURNS = 30` (`GameConst.maxTurn`) | `MAX_CHIEF_TURNS = 12` (`GameConst.maxChiefTurn`) |
| slot formula | `((turnIdx % 30) + 30) % 30` | `((turnIdx % 12) + 12) % 12` |
| rows to seed | **30 per general** (turn_idx 0..29) | **12 per (nation, officer_level)** (turn_idx 0..11) |
| seed values | `action_code='휴식', arg='{}', brief='휴식'` | same |
| boot-slot guarantee | full 0..29 seed ⇒ slot present every rotation | full 0..11 seed |

Nation officer levels needing rows: per `GameConst.getNationChiefLevel(level)`, officers at level ≥5 (chief seats 5/7/9/11
by nation level). A-minimal: seed `officer_level` rows for each chief seat the nation's level grants (no-op for level <5).

---

## Boot-loader verdict (PIVOTAL)

**Row-seed is NOT enough. A DB→`WorldSnapshot` loader + a `@Bean InMemoryTurnWorld` are a prerequisite for the
daemon to boot and advance a turn.** They do not exist in production today. Build them (T9–T10) as part of this A-minimal
effort, or split them into a named prerequisite (F1-PRE) and accept the integration-test gate form. Reverse row mappers
already exist for general/city/nation/diplomacy/nation_turn (no TroopRowMapper — but troop=0 rows at scenario start, so
the loader can return an empty troop list).

---

## A-minimal scope counts (final)

| entity | count | note |
|--------|-------|------|
| `world_state` | 1 | singleton id=1 |
| `nation` | 2 | 후한 (1), 황건적 (2); neutral=0 carried on generals, no row |
| `city` | 24 | 후한 14 + 황건적 10 |
| `general` | 678 | 491 `general[]` + 187 `general_ex[]`; 66 faction (43+23), 612 neutral(0) |
| `general_turn` | 678 × 30 = 20,340 | full ring per general |
| `nation_turn` | 2 nations × chief seats × 12 | ~per getNationChiefLevel |
| `diplomacy` | 2×1×2 = 2 ordered rows (+ JSON overrides; JSON diplomacy=[]) | state_code=2 neutral |
| `rank_data` | 678 × 37 = 25,086 | all value=0 |
| `ng_games` | 1 | session record |
| `troop` / `game_kv` / `event` / `message` / `ng_*` | 0 | A skips; RehydrateService tolerates / bootstraps name pool |

---

## Build plan — finalized A-minimal ordered task list

See StructuredOutput `build_plan` for the canonical ordered list with file paths. Summary: vendor JSON+city table →
infra JSON model + city const → `ScenarioImporter` (world→nation→city→general→turns→diplomacy→rank→ng_games) →
game-engine `ScenarioSeedRunner` (idempotent gate) → **WorldSnapshotLoader + `@Bean InMemoryTurnWorld`** → boot/tick IT.
