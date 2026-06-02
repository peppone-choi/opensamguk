# F3 — Rankings Read API + Ranking Pages (consolidated spec)

**Date:** 2026-06-02
**Phase:** F3 (read-only frontend parity slice for `/game/rankings/*`)
**Status:** spec — ready to plan
**Inputs:** 4 reader reports (rank-pages-A 명장/장수, rank-pages-B 세력/왕조/명전, rank-pages-C-data RankColumn+npcList+traffic, webgame-rank-contract).

---

## 0. Problem statement

`web/game` ships 9 ranking pages (`/game/rankings`, `…/best-generals`, `…/generals`, `…/kingdoms`,
`…/npcs`, `…/hall-of-fame`, `…/traffic`, `…/emperor`, `…/emperor/[id]`). All call `api.rankings.*`
which forwards through the same-origin proxy `web/game/app/api/game/[...path]/route.ts` to
**game-api(:8081)**. game-api today has **no RankingController and no RankData repository** → every
`GET /api/rankings/*` returns 404 → every ranking page hits its `.catch()` branch and shows
"데이터를 불러올 수 없습니다."

**Goal:** add a read-only `RankingController` (+ a `RankDataReadRepository` and the supporting read
repos/queries) on game-api so all 8 ranking endpoints return 200 with the JSON shape each page's
TypeScript interface already expects, and the pages render real data.

### Scope boundaries (load-bearing)
- **game-api = read-only.** JPA on Postgres only. No `ChangeRecorder`, no `EntityManager` write, no
  game-state mutation. Rankings are pure projections of already-flushed rows
  (`rank_data`, `general`, `nation`, `city`, `hall`, `ng_old_nations`, `ng_old_generals`).
- **The frontend interface is the contract.** The page `.tsx` files define the exact field names/types
  consumed. game-api DTO JSON must match those names byte-for-byte (camelCase). The page is the parity
  acceptance, NOT the legacy PHP HTML (which the F3 React pages deliberately simplify — see §6).
- **Two parity layers, two acceptances.** (a) *Endpoint parity* = the DTO matches the web/game TS
  interface and the **sort metric / column source matches the legacy PHP** for the fields the page
  actually shows. (b) *Page parity* = the page renders the documented columns from live data without a
  JS error and the top-N ordering matches the PHP sort. Legacy PHP shows far more columns/sorts than the
  F3 React pages — those extra columns are **deferred, not in F3** (logged as open questions, not faked).
- **Security:** `GameApiSecurityConfig` ends in `.anyRequest().permitAll()`, so `/api/rankings/*` is
  public with no config change. Do NOT add a `.requestMatchers(...).authenticated()` line for rankings.

---

## 1. Data sources (verified against `infra/.../V1__baseline.sql` + read entities)

| Table | Exists? | Used by | Notes |
|---|---|---|---|
| `general` | ✓ | best-generals, generals, npcs, kingdoms(gennum), emperor(detail generals) | `GeneralReadEntity` already maps the scalar surface (`leadership/strength/intel/experience/dedication/officer_level/crew/nation_id/city_id/npc_state/name/picture/image_server`). |
| `nation` | ✓ | kingdoms, best-generals(color/name), npcs(color/name), emperor(color/name) | `NationReadEntity` maps `id/name/color/capital_city_id/gold/rice/tech/level/type_code/meta`. `power`/`pop`/`gennum` are NOT columns → see §1.1. |
| `city` | ✓ | kingdoms(cityCount/capital/pop), emperor-detail(cities) | `CityReadEntity` maps `id/name/level/nation_id/pop/pop_max/...`. |
| `rank_data` | ✓ | (best-generals optional; hall-of-fame fallback) | `(general_id, type)` unique; `nation_id` denormalized; indexes `(type,value)`, `(nation_id,type,value)`. **No JPA entity yet** → new `RankDataReadEntity`/repo. 37 `type` values per `RankColumn`. |
| `hall` | ✓ | hall-of-fame | `id/server_id/season/scenario/general_no/type/value(double)/owner/aux(jsonb)`. |
| `ng_old_nations` | ✓ | emperor, emperor-detail | per-server fallen-nation snapshot; `data` jsonb holds nation aux. |
| `ng_old_generals` | ✓ | emperor-detail | per-server fallen-general snapshot; `data` jsonb. |
| `emperior` | ✗ **DOES NOT EXIST** | — | Legacy `a_emperior.php` reads an `emperior` table; this schema has none. F3 emperor endpoints derive from live `nation`/`general`/`city` (current unifier) + `ng_old_*`. See OQ-1. |
| `general_access_log` | ✗ **DOES NOT EXIST** | traffic | Legacy `a_traffic.php` reads refresh/online metrics + top-5 refreshers. No such table, no online-tracking infra. Traffic has **no real data source** in F3. See OQ-2. |

### 1.1 Missing derived nation fields (`power`, `pop`, `gennum`, capital name)
`KingdomRank` wants `pop`, `genNum`, `power`, `cityCount`, `capitalName`.
- `cityCount` = `cityRepo.countByNationId(id)` (exists).
- `capitalName` = `cityRepo.findById(nation.capitalCityId)?.name`.
- `genNum` = `generalRepo.countByNationId(id)` (exists) OR `nation.meta["gennum"]` (NationReadEntity
  materializes it). Prefer the live `count` for read-truth; record which in the impl.
- `pop` = SUM(`city.pop`) over the nation's cities (aggregate query) — there is no `nation.pop` column.
- `power` (국력) = legacy `nation.power` — **no column in this schema.** Legacy `a_kingdomList.php`
  sorts by `nation.power` DESC. F3 must define a power proxy (e.g. SUM crew of nation generals, or a
  documented placeholder) — see OQ-3. The page labels the column 병력 (military power), so SUM(general.crew)
  over the nation is the closest faithful proxy and is what the React page header ("병력") implies.

### 1.2 RankColumn enum is in the wrong module
`enum class RankColumn(val column: String)` (37 cases, PHP-faithful backing values) lives in
`app/game-engine/.../turn/TurnWorldModel.kt`. game-api does NOT depend on game-engine. If best-generals
ever reads `rank_data` by type, game-api needs the type-string constants. **Do not duplicate-by-copy
silently.** Options (pick in plan): (a) for F3 best-generals uses ONLY `general` scalar columns (no
`rank_data` read at all — see §2.2), so no enum needed yet; (b) if a later ranking needs `rank_data`,
relocate `RankColumn` to `:logic` (shared) or introduce a small game-api-local `RankType` constants
object. F3 default = option (a): best-generals = `general.(leadership+strength+intel)` only. See OQ-4.

---

## 2. Endpoint specs (one per ranking endpoint)

All under `@RequestMapping("/api/rankings")`, `@RestController`, public. Each returns the array/object
shape the matching page already declares. **Order is part of the contract** (the page renders rows in
array order and treats `rank`/`id` as pre-assigned).

### 2.1 `GET /api/rankings/best-generals` → `BestGeneral[]`
- **Page:** `best-generals/page.tsx`. Columns: 순위 | 장수 | 국가 | 통솔 | 묠력 | 지력 | 합계.
- **Legacy parity reference:** the React 명장 순위 page is a *simplified* total-aptitude board, NOT the
  26-tier `a_bestGeneral.php` hall of fame. It sorts by `leadership+strength+intel` DESC (mirrors
  `a_npcList.php` type-3 종능 sort), assigns `rank` 1..N.
- **Source:** `general` only (all non-NPC + NPC generals; legacy bestGeneral filters by user/NPC button
  — F3 includes all generals, top N). Join `nation` for name/color (재야 nationId=0 → name "재야",
  color "#000000").
- **Sort metric:** `total = leadership + strength + intel` DESC, stable. `rank` = 1-based index.
- **Limit:** top N (recommend 100; the React table renders all returned rows). Confirm N in plan.
- **Response shape:**
  ```ts
  { rank, generalId, name, nation, nationColor, leadership, strength, intel, total }[]
  ```
  - `generalId`=general.id, `name`=general.name, `nation`=nation.name or "재야",
    `nationColor`=nation.color or "#000000", `total`=sum.

### 2.2 `GET /api/rankings/generals` → `GeneralRank[]`
- **Page:** `generals/page.tsx`. Client-side sortable (rank|leadership|strength|intel|experience|devotion|crew).
  Columns: 순위 | 장수 | 국가 | 직위 | 통솔 | 묠력 | 지력 | 경험 | 충성 | 병력.
- **Legacy reference:** `a_genList.php` (flat all-general list). React version drops 연령/성격/특기/
  레벨/명성/계급/삭턴/벌점 (those need `general_access_log`/level helpers → deferred). Default order:
  React sets initial `rank` server-side; pick `experience` DESC as the default rank metric (matches the
  page's "순위" default and is a faithful subset of genList's stat sorts). The client re-sorts after load.
- **Source:** `general` (ALL generals, incl. NPC) + `nation` join.
- **Fields:** `officerLevel`=general.officer_level (1–5 per page comment, but pass raw value),
  `experience`=general.experience, `devotion`=general.dedication, `crew`=general.crew.
- **Response shape:**
  ```ts
  { rank, generalId, name, nation, nationColor, officerLevel,
    leadership, strength, intel, experience, devotion, crew }[]
  ```

### 2.3 `GET /api/rankings/kingdoms` → `KingdomRank[]`
- **Page:** `kingdoms/page.tsx`. Top 3 as cards, then table.
  Columns: 순위 | 국가 | 등급 | 금 | 쌀 | 인구 | 장수 | 병력 | 도시 | 수도.
- **Legacy reference:** `a_kingdomList.php` sorts nations by `nation.power` DESC. F3 sorts by the
  `power` proxy (§1.1; SUM crew of nation generals) DESC, `rank` 1-based. Exclude nation id=0 (재야).
- **Source:** `nation` (id≠0) + aggregate `city`/`general`.
- **Fields:** `nationId`=id, `name`, `color`, `level`=nation.level (page header "등급"),
  `gold`, `rice`, `pop`=SUM(city.pop), `genNum`=countByNationId(general), `power`=proxy(§1.1),
  `cityCount`=countByNationId(city), `capitalName`=city.name of capital_city_id (or "" if null).
- **Response shape:**
  ```ts
  { rank, nationId, name, color, level, gold, rice, pop, genNum, power, cityCount, capitalName }[]
  ```

### 2.4 `GET /api/rankings/npcs` → `NpcGeneral[]`
- **Page:** `npcs/page.tsx`. Nation dropdown filter (client-side over the returned list).
  Columns: 장수 | 국가 | 직위 | 통솔 | 묠력 | 지력 | 경험 | 충성 | 병력 | 도시.
- **Legacy reference:** `a_npcList.php` = `general WHERE npc=1` (빙의/악령 gallery). F3 selects
  `general.npc_state = 1`. Order = legacy type-1 (`name` ASC) OR total-aptitude DESC; pick total
  (종능) DESC for a useful default and document it. No `rank` field in the interface.
- **Source:** `general WHERE npc_state=1` + `nation` join + `city` join for `cityName`.
- **Fields:** `cityName`=city.name of general.city_id (or "" if 0). `officerLevel`/`experience`/
  `devotion`(=dedication)/`crew` as in 2.2.
- **Response shape:**
  ```ts
  { generalId, name, nation, nationColor, officerLevel,
    leadership, strength, intel, experience, devotion, crew, cityName }[]
  ```
- **Note:** the proxy attaches a Bearer if a `sam_access` cookie exists; the endpoint stays permitAll
  (an anonymous visitor must still see the NPC list). Do NOT make it `authenticated()`.

### 2.5 `GET /api/rankings/hall-of-fame` → `HallRecord[]`
- **Page:** `hall-of-fame/page.tsx`. Category dropdown filter (client-side).
  Columns: 분류 | 기록 | 이름 | 국가 | 수치 | 달성 시기 | 턴.
- **Legacy reference:** `a_hallOfFame.php` reads the `hall` table (per-type top-10, sorted by
  `hall.value` DESC; 24 types). The React `HallRecord` is a flattened cross-category list with
  `category`/`valueLabel`/`achievedAt`/`turn` — fields that do NOT exist on `hall` (no `achieved_at`/
  `turn` column; `category`≈`type`, `value`=hall.value double). F3 fidelity options:
  - **(a) Empty-but-200:** if the `hall` table is empty in the 1010 capture (fresh DB → likely empty),
    return `[]`. The page renders an empty table without error (parity = no crash). **F3 default.**
  - **(b) Map from `hall`:** if rows exist, map `category`=type label (Korean), `name`=`aux.name`,
    `nation`=`aux.nationName`/`nationColor`=`aux.color`, `value`=hall.value, `valueLabel`=type Korean
    label, `achievedAt`=""/`turn`=0 (no source → documented zero-fill, NOT fabricated game data).
  - Do NOT invent achievement records. See OQ-5.
- **Response shape:**
  ```ts
  { id, category, name, nation, nationColor, value, valueLabel, achievedAt, turn }[]
  ```

### 2.6 `GET /api/rankings/traffic` → `TrafficSummary`
- **Page:** `traffic/page.tsx`. Summary cards + daily history table.
- **Legacy reference:** `a_traffic.php` reads `general_access_log` + online tracking. **Neither exists**
  in this schema, and there is no access-logging infra. F3 cannot produce faithful traffic.
- **F3 default:** return a zeroed `TrafficSummary` with empty `history` (`result 200`). Page renders
  cards showing 0 and an empty history table — no crash. This is an explicit "no data source yet"
  zero-fill, logged as OQ-2, NOT fabricated metrics.
- **Response shape:**
  ```ts
  { todayUnique, todayViews, weekUnique, weekViews, monthUnique, monthViews,
    peakConcurrent, currentOnline, history: [] }
  // all numbers 0; history empty
  ```
- **Alternative (plan decision):** wire `currentOnline`/`peakConcurrent` to a future SSE/heartbeat
  source. Out of F3 scope unless an existing online-count signal is found.

### 2.7 `GET /api/rankings/emperor` → `EmperorRecord[]`
- **Page:** `emperor/page.tsx`. First 3 get badges; `name` links to `…/emperor/{id}`.
  Columns: 대수 | 황제 | 국가 | 통일 시기 | 턴 | 장수 | 도시.
- **Legacy reference:** `a_emperior.php` lists the `emperior` (dynasty) table, newest first.
  **No `emperior` table here.** Faithful F3 source:
  - The list of past dynasties/unifications. The closest real source is `ng_old_nations` (fallen-nation
    snapshots per server) — but those are per-fallen-nation, not per-unification, and have no `id`/
    year/month/turn unifier semantics. There is **no clean "list of emperors / unifications" source.**
  - **F3 default: empty-but-200** (`[]`) until a dynasty/unification history table exists. The page
    renders an empty table. If `world_state.isunited=1`, a single current-unifier record MAY be derived
    (the top nation's ruler) — document if implemented. Do NOT fabricate prior dynasties. See OQ-1.
- **Response shape:**
  ```ts
  { id, name, nation, nationColor, unifiedAt, turn, year, month, generalCount, cityCount }[]
  ```

### 2.8 `GET /api/rankings/emperor/{id}` → `EmperorDetail`
- **Page:** `emperor/[id]/page.tsx`. Info cards + generals table (장수|통솔|묠력|지력) + cities table
  (도시|등급|인구).
- **Legacy reference:** `a_emperior_detail.php` (`emperior` master + `ng_old_nations`/`ng_old_generals`
  detail). With no `emperior` table, F3 cannot reconstruct historical dynasty detail.
- **F3 default:** if `/api/rankings/emperor` returns `[]`, this path is unreachable via the UI (no link).
  Return **404** for unknown `id` (page hits `.catch()` → error state — acceptable). If a current-unifier
  record is derived in 2.7, build its detail from live `nation`/`general`/`city`. See OQ-1.
- **Response shape:**
  ```ts
  { id, name, nation, nationColor, unifiedAt, turn, year, month,
    generalCount, cityCount, totalGold, totalRice, totalPop,
    generals: { name, leadership, strength, intel }[],
    cities:   { name, level, pop }[] }
  ```

---

## 3. New game-api artifacts (implementation surface)

1. **`read/RankDataReadRepository.kt`** — `RankDataReadEntity` (`@Table("rank_data")`: id, nationId,
   generalId, type, value) + `JpaRepository<RankDataReadEntity, Int>` with:
   `findByTypeOrderByValueDesc(type, Pageable)`, `findByNationIdAndTypeOrderByValueDesc(...)`,
   `findByGeneralId(generalId)`. (Only needed if a ranking reads `rank_data`; F3 best-generals does NOT
   — build lazily per OQ-4.)
2. **`read/HallReadRepository.kt`** — `HallReadEntity` (`@Table("hall")`) + `findByTypeOrderByValueDesc`.
   (Only if 2.5 option (b).)
3. **`read/OldNationReadRepository.kt` / `OldGeneralReadRepository.kt`** — only if emperor detail is
   derived (OQ-1). F3 default skips these (empty emperor).
4. **Aggregate queries on existing repos** — add to `CityReadRepository`:
   `@Query("select coalesce(sum(c.pop),0) from CityReadEntity c where c.nationId=:n") fun sumPopByNation`;
   add to `GeneralReadRepository`: `@Query sumCrewByNation` (for the `power` proxy) and
   `findByNpcStateOrderBy...` (npc_state=1 list — note existing method is for npcState=2 claimable).
5. **`controller/RankingController.kt`** — `@RequestMapping("/api/rankings")`, 8 `@GetMapping`s,
   assembled from read repos only. Mirror `FrontInfoController` style.
6. **`dto/RankingDto.kt`** — 8 data classes matching §2 shapes exactly (camelCase = JSON keys).
7. **Tests:** controller slice/IT per endpoint asserting (a) 200, (b) JSON keys = TS interface,
   (c) sort order for best-generals/kingdoms/generals against a seeded fixture.

---

## 4. Sort-metric parity table (the load-bearing per-ranking contract)

| Endpoint | Sort metric (DESC unless noted) | Source field(s) | Legacy oracle |
|---|---|---|---|
| best-generals | `leadership+strength+intel` | general | npcList type-3 종능 / simplified bestGeneral |
| generals | `experience` (default; client re-sorts) | general.experience | genList type-5 |
| kingdoms | `power` proxy = SUM(general.crew by nation) | general.crew agg | kingdomList nation.power |
| npcs | `leadership+strength+intel` (default) | general(npc_state=1) | npcList type-3 |
| hall-of-fame | `hall.value` per category | hall.value | hallOfFame value DESC |
| traffic | n/a (zero-fill) | — | traffic (no source) |
| emperor | newest-first by id (when sourced) | — | emperior.no DESC (no table) |
| emperor/{id} | n/a (detail) | — | emperior_detail (no table) |

**Rounding/ties:** PHP `usort` is stable (8.0+); preserve a stable secondary order = `general.id ASC`
to break ties deterministically. Do NOT add a non-stable comparator (CLAUDE.md rule 6). Percent/ratio
tiers from legacy bestGeneral (승률/살상률/전승률/ROI) are NOT in any F3 page → deferred.

---

## 5. Request/response flow (unchanged, for reference)

```
web/game page  →  api.rankings.X()  →  fetch('/api/game/api/rankings/X')
  →  proxy route.ts (attaches Bearer if cookie; permitAll so optional)
  →  game-api :8081  RankingController @GetMapping('/api/rankings/X')
  →  read repos (JPA, Postgres)  →  DTO JSON  →  page renders.
```

---

## 6. Page parity acceptance (what "done" means per page)

For each page: load it against a game-api backed by the 1010-equivalent seed; the documented columns
render from live data; top-N order matches the §4 metric; no console/JS error; empty data renders an
empty table (not a crash). The React pages render FEWER columns than legacy PHP — that simplification
is intentional and accepted; the extra legacy columns are deferred (§OQ list), not faked.

---

## 7. Open questions (must resolve in plan, do not fabricate)

- **OQ-1 (emperor):** No `emperior` / unification-history table exists. F3 default = empty `[]`
  (`emperor` + 404 on `{id}`). Decide: (a) ship empty now, add a dynasty-history table + capture later;
  or (b) derive a single current-unifier record from `world_state.isunited` + top nation. Faithful PHP
  needs the `emperior` table — recommend (a), backlog the table.
- **OQ-2 (traffic):** No `general_access_log` / online-tracking infra. F3 = zeroed `TrafficSummary`.
  Decide whether to backlog an access-log table + online heartbeat, or drop the 접속 통계 page from the
  hub for F3.
- **OQ-3 (nation power):** `nation.power` column does not exist. Confirm the `power` proxy = SUM(crew)
  over nation generals (matches the page's 병력 header) vs. a future computed 국력 formula. PHP sorts by
  the stored `power` — flag this as a knowing divergence (proxy), documented per CLAUDE.md rule 5.
- **OQ-4 (RankColumn location):** F3 best-generals uses only `general` scalar columns, so no `rank_data`
  read and no enum needed. Confirm. If any later ranking needs `rank_data` by type, relocate
  `RankColumn` from game-engine `TurnWorldModel` to `:logic` (shared) rather than copy-pasting.
- **OQ-5 (hall data):** Is the `hall` table populated in the 1010 capture? If empty, 2.5 = `[]` (fine).
  If populated, confirm the `aux` jsonb shape (name/color/nationName/picture/imgsvr) to map fields, and
  confirm `achievedAt`/`turn` have no source (zero-fill) — they are NOT on `hall`.
- **OQ-6 (limit N):** best-generals/generals/npcs page size — return all, or top 100? Pages render the
  full array; pick a cap to bound payload and document it.
- **OQ-7 (npc_state semantics):** confirm npc_state=1 = 빙의(악령 gallery, the legacy `npc=1`) vs
  npc_state=2 = claimable. The npcs page should list `npc_state=1`; verify against the seed.
- **OQ-8 (officerLevel range):** `GeneralRank.officerLevel` page comment says "1–5" but the schema/DTO
  carry the raw 0–12 officer_level. Pass raw; the page renders the number. Confirm no remap expected.
```
