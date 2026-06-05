# FOUNDING_SEAM_FIX — the founding created-set live-daemon seam (prod crash-loop fix)

**Status:** FIX SPEC (not yet implemented). **Dimension:** engine write-path seam (founding/created-set).
**Crash:** `che_거병` resolves through `ReservedTurnHandler` on the live daemon → `CheGeobyeong.kt:71`
`error("거병 requires a preloaded newNationId (insertId placeholder)")`. The game-engine crash-loops
every tick the moment a neutral general's reserved/AI-chosen turn is `che_거병`.

---

## 0. Root cause (two distinct bugs, one seam)

The `:logic` founding resolvers are **draw-for-draw GOLDEN-GREEN** (`GeobyeongTest` 14/14). The bug is
entirely in the **engine→logic seam** (`ReservedTurnHandler.handle`), which the golden test bypasses
(the test hand-builds the preload args + reads `draft.createdNations` directly). The live daemon does
neither. Concretely:

### Bug A — `che_거병` (INSERT-created-set) → the CRASH
`ReservedTurnHandler.handle` builds `GeneralActionResolveContext(draft, rng, worldEnv, month, date, args = args)`
where `args` is ONLY the decoded reserved/AI `argJson`. `che_거병` (a no-arg command) therefore arrives with
an EMPTY `args`, and `CheGeobyeong.resolve` hits `error(...)` on the missing `newNationId`. Even with the
args injected, the handler **never drains `draft.createdNations` / `draft.createdDiplomacy` /
`draft.createdNationTurns`** — grep of `ReservedTurnHandler.kt` for `draft.created*` / `draft.cascade*` /
`draft.nation` returns NOTHING. The handler only does `recorder.diffGeneral` + `recorder.diffCity` +
the `draft.cascadeDiplomacy` loop. So the nation/diplomacy/nation_turn INSERTs would silently vanish.

### Bug B — `che_건국` / `cr_건국` / `che_무작위건국` (UPDATE-nation + cascade) → SILENT DATA LOSS
These siblings do NOT INSERT a nation — they MUTATE the actor's existing wandering nation
(`d.nation = nation.copy(level=1, name, color, typeCode, capitalCityId, …)`), claim the city
(`d.city = d.city.copy(nationId=...)`), and (무작위건국) move followers via `d.cascadeGenerals`. The handler
diffs `draft.city` (so the city claim survives) but **never diffs `draft.nation` and never drains
`draft.cascadeGenerals` / `draft.cascadeCities`** — so the nation level-up (0→1) and the follower moves are
LOST at flush even though no crash occurs. They ALSO need preload args (`nationName` / `nationType` /
`colorType` / `sameMonthOrBefore`; 무작위 needs `candidateCityIds`) that the handler does not supply →
they currently `return` early (silent no-op) instead of founding. (`ProcessNationCommand` already does
`recorder.diffNation` + `applyNationDirtyFree`; the general-pass handler must do the same.)

### What is ALREADY correct (do NOT redo)
- `InMemoryTurnWorld` already DECLARES `createdNationIds` / `createdDiplomacyKeys` and already DRAINS them
  in `consumeDirtyState()` (lines 251, 257, 290-293) — there is just **no public method to POPULATE them**.
- `DirtyState.nationTurnDirty` already exists and `DatabaseHooks.toFlushPayload(world, recorder, dirty)`
  already wires `createdNationTurns = dirty.nationTurnDirty`, `createdNations`, `createdDiplomacy` (lines
  291, 312-313). The flush is READY.
- `JdbcFlushExecutor` already has `nationCreateMany` / `diplomacyCreateMany` / `nationTurnCreateMany`
  (step-3, lines 422-481), guarded `> 0`. The SQL is READY.

So this fix is upstream-only: **populate the world's created/dirty sets from the draft, and supply the
founding preload args.** No new flush SQL, no new payload field.

---

## 1. Exact files + functions to change

### F1 — `app/game-engine/.../turn/InMemoryTurnWorld.kt` (ADD created-set population API)
The world declares the sets but cannot fill them. Add three public methods + the `nationTurn` channel:

- **`createNation(nation: Nation): Nation`** — mirror `createTroop` (line 177): `nations[id]=nation;
  dirtyNationIds.add(id); createdNationIds.add(id)`. (The id is the placeholder; see §2.)
- **`createDiplomacy(entry: TurnDiplomacy): TurnDiplomacy`** — `diplomacy[key(from,to)]=entry;
  dirtyDiplomacyKeys.add(key); createdDiplomacyKeys.add(key)`. Mirror `createGeneral`. The `buildDiplomacyKey`
  helper already exists (line 63).
- **`createNationTurn(turn: NationTurn)`** — append to a NEW `private val createdNationTurns =
  mutableListOf<NationTurn>()` (nation_turn has no in-memory map — it's an INSERT-only ledger like the rank/kv
  channels, not a queryable world entity in the slice).
- **`consumeDirtyState()`** — add `nationTurnDirty = createdNationTurns.toList()` to the returned `DirtyState`
  (it is currently defaulted `emptyList()`), and `createdNationTurns.clear()` in the clear block (after line 276).
- **`removeNation(id)`** (line 206) — also prune `createdNationTurns.removeAll { it.nationId == id }` so the
  create-then-delete-same-tick cancel invariant holds for the nation_turn ledger too (matches the existing
  diplomacy prune in `removeNation`).

Add an **`allocateNationId(): Int`** helper (or compute inline in the handler — see §2): `nation.id` is
`integer PRIMARY KEY` (V1__baseline.sql:22, **NOT serial**), so the placeholder id IS the authoritative id.
`= (nations.keys.maxOrNull() ?: 0) + 1` over the live world (PHP `insertId()` is the DB autoincrement; in
the memory-centric world the next free integer id is the faithful substitute — single-daemon, no concurrent
INSERT, so it cannot collide).

### F2 — `app/game-engine/.../turn/ReservedTurnHandler.kt` (the resolve-drain seam, ~line 218-231 + new drain block)
This is the load-bearing change. Two parts:

**(2a) Inject the founding preload args BEFORE building `resolveCtx`** (right after `args` is finalized at
line ~168, before `GeneralActionResolveContext` at line 230). Add a constructor param `scenario: Int` (§F4)
and a helper that, **only when `actionCode` is a founding command**, augments `args` with the PHP-query
substitutes the resolver expects:

```kotlin
// che_거병 / che_건국 / cr_건국 / che_무작위건국 — the preload the resolver's PHP DB queries stand in for.
val foundingArgs: Map<String, Any?> = when (actionCode) {
    "che_거병" -> {
        val existing = world.listNations()
        linkedMapOf(
            "newNationId" to world.allocateNationId(),                 // PHP insertId() placeholder
            "existingNationIds" to existing.map { it.id },             // getAllNationStaticInfo()
            "existingNationNames" to existing.map { it.name }.toSet(), // SELECT count(*) … WHERE name dedup
            "scenario" to scenario,                                    // env['scenario'] → secretlimit 1|3
        )
    }
    "che_건국", "cr_건국", "che_무작위건국" -> linkedMapOf(
        // nationName/nationType/colorType come from the RESERVED arg jsonb (the user picked them at
        // intake) — already in `args`. Add ONLY the runtime engine-scanned values:
        "sameMonthOrBefore" to sameMonthOrBefore(general, year, month),   // che_건국.php:148 same-month guard
        // 무작위건국: candidateCityIds is a ConstraintContext/ctx field, threaded via resolveCtx below.
    )
    else -> emptyMap()
}
args = if (foundingArgs.isEmpty()) args else (LinkedHashMap(args).also { it.putAll(foundingArgs) })
```

For `che_무작위건국`, ALSO pass `candidateCityIds = scanNeutralLv56Cities(world)` into the
`GeneralActionResolveContext` ctor (it is a named ctor param, not an `args` key — `che_무작위건국.php:98`
`SELECT … WHERE nation=0 AND level IN (5,6)`); the resolver does `rng.choice(candidateCityIds)` so the draw
order is parity-load-bearing — build it from `world.listCities()` filtered `nationId==0 && level in (5,6)`,
**id-ascending** (PHP query default order).

**(2b) DRAIN the founding/cascade write-set AFTER `definition.resolve(resolveCtx)`** (after line 231,
alongside the existing `recorder.diffGeneral`/`diffCity`/`cascadeDiplomacy` block). Add:

```kotlin
// --- nation UPDATE (건국/cr_건국/무작위건국 level 0→1, name/color/type/capital) ---
if (nation != null && draft.nation != null && draft.nation !== nation) {
    recorder.diffNation(nation, draft.nation!!)                       // same as ProcessNationCommand:183
    world.getNationById(nationId)?.let { world.applyNationDirtyFree(applyNationPatch(it, draft.nation!!)) }
}
// --- cascade generals (무작위건국 follower moves) ---
for (g in draft.cascadeGenerals) {
    val pre = world.getGeneralById(g.id) ?: continue
    recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), g)
    world.applyGeneralDirtyFree(applyGeneralPatch(pre, g))
}
// --- cascade cities (방랑/무작위 city reverts; defensive) ---
for (c in draft.cascadeCities) {
    val pre = world.getCityById(c.id) ?: continue
    recorder.diffCity(PerTurnOverlay.toLogicCity(pre), c)
    world.applyCityDirtyFree(applyCityPatch(pre, c))
}
// --- CREATED-set (거병 INSERTs: nation FIRST (FK target), then diplomacy, then nation_turn) ---
for (n in draft.createdNations) world.createNation(PerTurnOverlay.toEngineNation(n))   // §2 reconcile
for (dip in draft.createdDiplomacy) world.createDiplomacy(PerTurnOverlay.toEngineDiplomacy(dip))
for (nt in draft.createdNationTurns) world.createNationTurn(nt)
```

`applyNationPatch` is the `applyNationDirtyFree`-input mapper — reuse `ProcessNationCommand`'s
`applyLogicToNation` (it already exists; hoist to a shared util or duplicate the small mapper). `toEngineNation`
/ `toEngineDiplomacy` are the inverse of `PerTurnOverlay.toLogicNation` / `toLogicDiplomacy` (those forward
mappers exist in `DatabaseHooks.toFlushPayload`; add the inverse if absent — the created nation carries
`id/name/color/typeCode/gold/rice/level/capitalCityId/meta` and the diplomacy carries `me/you→from/to,
state,term`).

**Ordering is load-bearing:** nation BEFORE diplomacy BEFORE nation_turn (FK + the frozen step-3 contract
`general→nation→troop→diplomacy`, then nation_turn). The handler must enqueue in that order; the world's
LinkedHashSet/list preserves it through `consumeDirtyState`.

### F3 — `app/game-engine/.../turn/DirtyState.kt` + `consumeDirtyState` wiring (NO new field)
`DirtyState.nationTurnDirty` already exists (line 152). Only `InMemoryTurnWorld.consumeDirtyState` must set
it from the new `createdNationTurns` channel (covered in F1). `createdNations` / `createdDiplomacy` are
already drained — no change. **No DirtyState shape change.**

### F4 — `app/game-engine/.../config/DaemonLoopConfig.kt` (scenario injection, line ~141)
`ReservedTurnHandler(...)` is constructed at line 141 WITHOUT a scenario. Add the new ctor param:

```kotlin
val scenario = (state.meta["scenario"] as? Number)?.toInt()
    ?: System.getenv("SCENARIO_CODE")?.removePrefix("scenario_")?.toIntOrNull()
    ?: 0
val handler = ReservedTurnHandler(world = world, registry = registry, hiddenSeed = hiddenSeed,
    startYear = startYear, scenario = scenario, aiHook = { gid, r -> ai.chooseGeneralTurn(gid, r) })
```

`SCENARIO_CODE` default is `scenario_1010` (per CLAUDE.md F1) → scenario `1010` → `>= 1000` → `secretlimit=1`
(the live-server branch). Prefer `state.meta["scenario"]` (seeded by `ScenarioSeedRunner`) and fall back to
the env fence. Surface `scenario` on `WorldEnv`/`WorldEnvBuilder` if a cleaner thread exists, but the
constructor param is the minimal change. (`ProcessNationCommand` does NOT need scenario — none of its
nation-pass commands found a nation; founding is a general-pass action.)

---

## 2. Placeholder-id reconciliation at flush

`nation.id` is `integer PRIMARY KEY` (`V1__baseline.sql:22`), **NOT `serial`** — and
`JdbcFlushExecutor.nationCreateMany` INSERTs `id` EXPLICITLY (`INSERT INTO nation (id, name, …) VALUES (:id, …)`,
line 433-434). **There is therefore NO SERIAL/insertId reconciliation step** — unlike `message`/`auction`
(serial PKs needing the pre-assigned-id-must-match-flushed-serial dance), the nation placeholder id IS the
final id. The contract is simply:

1. `world.allocateNationId()` returns the next free integer (`maxNationId + 1`) at resolve time.
2. `che_거병` stamps that id into `createdNations.id`, `createdDiplomacy.{me,you}`, and all 24
   `createdNationTurns.nationId` (the resolver already threads `newNationId` through all three — verified in
   `CheGeobyeong.resolve`).
3. The handler writes the created rows into the world under that SAME id (`createNation` keys the map by it),
   so subsequent same-tick reads (e.g. the actor's `nationId` now points at it, the diplomacy FK target) are
   consistent.
4. At flush, `DatabaseHooks.toFlushPayload(world, recorder, dirty)` carries `createdNations` (excluded from the
   UPDATE batch via `createdNationIds`), `createdDiplomacy`, `createdNationTurns = dirty.nationTurnDirty`;
   `JdbcFlushExecutor` step-3 INSERTs them verbatim with the placeholder id. The actor's general UPDATE
   (nation_id = newNationId) lands in step-7 — AFTER the nation INSERT (step-3), so the FK is satisfied.

**Single-daemon guarantee:** the engine runs ONE game on ONE daemon thread; the resolve→flush is one
transaction with no concurrent nation INSERT, so `maxNationId+1` cannot race. (If a future multi-found-per-tick
case arises, allocate sequentially within the tick — `allocateNationId` reads the live map which already
contains the prior same-tick `createNation`, so it naturally increments.) `DatabaseHooks` excludes
created ids from the step-7 UPDATE (`createdNationIds` filter, line 287) so the new nation is INSERTed, not
UPSERT-updated.

---

## 3. The gate test to add (engine-level, the missing coverage)

Add `app/game-engine/src/test/kotlin/opensamguk/engine/turn/FoundingHandlerSeamTest.kt` — the engine-level
test that the golden `GeobyeongTest` (logic-level) does NOT cover. Contract:

1. **`che_거병 founds a nation through the handler and drains the created-set`** — seed an
   `InMemoryTurnWorld` with ≥1 existing nation + a neutral actor general in a lv-5 city; build the handler
   (no aiHook, `scenario = 1010`); call `handler.handle(actorId, ReservedTurn("che_거병", ""), year, month, date)`.
   Assert: NO exception (regression guard for `CheGeobyeong.kt:71`); `result.fellBack == false`. Then
   `world.consumeDirtyState()` and assert `createdNations.size == 1` (id == allocateNationId, name == actor
   name, `secretlimit == 1` because scenario≥1000), `createdNationTurns.size == 24` (outer [12,11] × inner
   0..11), `createdDiplomacy.size == 2 * existingNationCount` (ascending `{dest,new}` then `{new,dest}`),
   and the actor general is dirty with `nationId == newNationId, officerLevel == 12`.
2. **`the founding created-set survives to the flush payload`** — run the handler, then
   `DatabaseHooks.toFlushPayload(world, handler.recorder, world.consumeDirtyState())`; assert
   `payload.createdNations / createdDiplomacy / createdNationTurns` are non-empty with the right counts, and
   the actor general is in `updatedGenerals` (NOT createdGenerals) with the new nation id. (Proves the §2
   reconciliation + the FK ordering.)
3. **`che_건국 levels the nation up and the UPDATE survives the handler`** — actor is a wandering lord
   (nation level 0); reserve `che_건국` with `argJson` carrying `nationName/nationType/colorType`; assert the
   nation is in the dirty UPDATE set with `level == 1` and the city claim landed (regression guard for Bug B).
4. **`secretlimit honors the scenario param`** — construct the handler with `scenario = 999`, assert the
   created nation's `meta["secretlimit"] == 3`; with `scenario = 1010`, assert `1`. (Proves the §F4 thread.)
5. **(IT, Docker-gated)** extend an existing `JdbcFlushExecutor` IT (or add `FoundingFlushIT`) to flush the
   payload from test 2 against Postgres and assert the `nation` / `diplomacy` / `nation_turn` rows exist with
   the placeholder id — the byte-level reconciliation gate. Skip (not fail) when Docker is unavailable.

The phase gate stays a real PHP `che_거병` golden replayed draw-for-draw (the existing
`che_거병-fixtures.json`); this engine test closes the seam the golden cannot reach.

---

## 4. Sibling created-set / cascade commands needing the same seam

| PHP command | Logic class | Write shape | Preload args the handler must inject | Drain the handler must add |
|---|---|---|---|---|
| `che_거병` | `CheGeobyeong` | **INSERT** nation + diplomacy pairs + 24 nation_turn; JOIN actor | `newNationId`, `existingNationIds`, `existingNationNames`, `scenario` | `createdNations` → `createdDiplomacy` → `createdNationTurns` (the CRASH fix) |
| `che_건국` | `CheGeonguk` | **UPDATE** wandering nation lv 0→1 + city claim | `nationName`, `nationType`, `colorType` (from reserved arg), `sameMonthOrBefore` | `draft.nation` → `diffNation`+`applyNationDirtyFree` (Bug B) |
| `cr_건국` | `CrGeonguk` (extends CheGeonguk) | same as 건국 (NO unifier grant) | same as 건국 | same as 건국 |
| `che_무작위건국` | `CheMujakwiGeonguk` (extends CheGeonguk) | UPDATE nation + `rng.choice(candidateCityIds)` dest + `cascadeGenerals` follower moves | `nationName`/`nationType`/`colorType`/`sameMonthOrBefore` + `candidateCityIds` (ctor param, id-ascending neutral lv5/6) | `draft.nation` + `draft.cascadeGenerals` drain |

`che_방랑` (`FoundingCascade`) is the inverse (reverts a nation to wandering, cascading over every
city/general/diplomacy → `removeNation` + cascade UPDATEs) — it shares the SAME unfilled cascade-drain gap;
its created-set is empty but its `cascadeGenerals`/`cascadeCities`/diplomacy-prune path runs through the same
new drain block, so add it to the §3 test matrix as a follow-up (it is already quarantined in the backlog as
genfound-방랑군). `che_선양` / `che_해산` (CheSeonyang/CheHaesan) are nation-pass or non-INSERT and out of
this seam's scope — they route through `ProcessNationCommand`, which already drains `diffNation`.

**Implement order:** (1) F1 world API + F3 drain → (2) F2 handler 거병 path + gate test 1/2 → unblocks prod
crash; (3) F4 scenario thread + test 4; (4) F2 건국/cr_건국/무작위건국 nation+cascade drain + test 3 (Bug B,
no crash but data loss). Ship (1)(2)(3) FIRST to stop the crash-loop; (4) is a correctness follow-up.

---

## 5. Why this is parity-safe (NON-NEGOTIABLE checklist)

- **RNG draw-for-draw:** the preload args (`newNationId`/`existingNationIds`/`existingNationNames`/`scenario`)
  are pure DB-query substitutes — they consume NOTHING from the action rng (`GeobyeongTest` line 224-245
  proves a fresh rng yields the same first draw post-resolve). `che_무작위건국`'s `rng.choice(candidateCityIds)`
  DOES draw — so `candidateCityIds` MUST be id-ascending (the PHP `SELECT` default order) or the draw desyncs.
- **Flush delta, not inline writes:** the handler records every founding mutation through `ChangeRecorder`
  (`diffNation`/`diffGeneral`/`diffCity`) + the world created-set; `applyNationDirtyFree`/`applyGeneralDirtyFree`
  update the read-state WITHOUT marking dirty (the recorder stays the lone dirty source — one-daemon-write rule
  intact, JDBC-only flush).
- **Insertion order:** `createNation`→`createDiplomacy`→`createNationTurn` enqueue order is preserved by the
  world's LinkedHashSet/list and the frozen step-3 flush contract (FK-safe: nation before its diplomacy/turns).
- **Faithful port:** every value (`secretlimit` 1|3, the `㉥` dedup, the 24-row [12,11]×0..11 loop, the
  ascending diplomacy pairs) is already byte-matched in `CheGeobyeong` against `che_거병.php`; this fix only
  carries the resolver's output to the world+flush, inventing nothing.
