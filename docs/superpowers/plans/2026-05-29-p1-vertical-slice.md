# P1: Vertical Slice — `che_농지개간` / `che_상업투자` CQRS Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`. Execute each task as a checkbox step (`- [ ]`), TDD red→green, **one logical commit per task**, ending every commit message with the `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` trailer. The orchestrator (not you) creates branches and commits after review; treat the inline `git` lines as the intended commit boundaries, not as instructions to run git yourself. **Verify build success by tail/grep of the gradle output, never by exit code** (project memory: task-notification exit 0 is unreliable — `... | tail -40` and grep for `BUILD SUCCESSFUL` / the test count). Every gradle invocation MUST be prefixed `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — Gradle 8.x/9.x fails to parse Java 25. Run from repo root `/Users/apple/Desktop/개인프로젝트/opensamguk` via `./gradlew`. **Do NOT re-port the P0-B kernel** (RNG / josa / log / constants / wire / world-skeleton / flush-stub / redis-consumer / SSE-relay are already green) — this plan EXTENDS them.

**Goal:** Prove the memory-CQRS write loop end-to-end with ONE deterministic command — `che_농지개간` (farmland reclamation), which in PHP is a 9-line subclass of `che_상업투자` (commerce investment). Both commands are ported as **ONE shared PHP algorithm** instantiated twice (PHP grand truth — the TS reference is deliberately NOT mirrored here; see TS-trap below). The slice carries no inheritance / battle / AI / diplomacy, so it is the most deterministic possible exercise of: a single shared constraint library that gives **precheck == full** identical judgments; the RNG draw-for-draw + Korean action-log byte-parity discipline from P0; the explicit change-recorder → JDBC-batch flush path (NO JPA EntityManager on the write side); and the full api → Redis → daemon → flush → `turnCompleted`-SSE round-trip.

**Architecture (the 8-step CQRS flow — design §12, ported faithfully):**
1. **Next.js → game-api precheck.** Load DB rows (General/City/Nation/WorldState) via JPA read repos → logic entities; build a precheck-mode `MemoryStateView` + `ConstraintContext(mode=precheck)`; evaluate the command's `buildConstraints` (ReqGeneralGold/OccupiedCity/SuppliedCity + the rest) through the shared `evaluateConstraints` → `available | blocked | unknown` (matching `ConstraintResult` Allow/Deny/Unknown ↔ `PrecheckResult` AVAILABLE/BLOCKED/UNKNOWN — there is no `needsInput` in the slice).
2. **Reserve.** game-api publishes a `TurnDaemonCommandEnvelope(requestId/sentAt/command)` to the Redis MUTATION stream **and** writes a `general_turn` reservation row (ring buffer, MAX=30, default `휴식`).
3. **Daemon XREAD BLOCK + cursor consume.** `RedisCommandStream` (already built) reads new envelopes; the command registry normalizes/validates; the reservation is enqueued into the turn pipeline.
4. **TurnDaemonLifecycle drains due generals.** `reservedTurnHandler` builds a full `WorldStateView` over a copy-on-write per-turn overlay, evaluates the **FULL** constraints (MUST equal precheck), seeds RNG via `serializeSeed(...)` + action context. deny/unknown → `휴식` fallback + deny-reason log.
5. **resolveGeneralAction.** `onCalcDomestic` cost (the 9-source action pipeline), success/crit RNG draws, mutate General+City on an immutable draft, change-recorder derives patch+dirty, `ActionLogger` emits the color-tag Korean log.
6. **ChangeRecorder derives the patch + dirty set** (the SINGLE dirty source) and applies it to InMemoryTurnWorld through a **dirty-free apply path** (the F-area apply routes exclusively through ChangeRecorder; InMemoryTurnWorld's internal `updateGeneral`/`updateCity` dirty-marking is NOT invoked on this path, so dirty is never double-counted — see F2/F3 and the Self-Review "ONE dirty source"). `consumeDirtyState()` single-shot drain → `databaseHooks` bulk flush (worldState→created→deleted→updates→rankData→logEntry) in **ONE JDBC transaction** — the recorder stub becomes a real JDBC-batch executor.
7. **Daemon publishes `turnCompleted` RealtimeEvent** (reusing the P0-B `RealtimePublisher` pub/sub). game-api eventHub (Redis SUBSCRIBE, already built) → SSE frame → Next.js main screen re-render (coarse signal only). **No `commandResult`/events-stream is published in P1** — that publisher+consumer is deferred (P-later); the P1 gate uses ONLY the `turnCompleted` realtime round-trip.
8. **PARITY GATE.** `compare-command-logs` (PHP↔Kotlin) action-log byte-diff vs the PHP golden; an integration test asserts the flushed General/City row + jsonb is byte-comparable to a golden DB dump.

**Parity discipline (non-negotiable, carried from P0):** RNG draw-for-draw byte parity; Korean action-log byte parity (JosaUtil / color-tag / prefix / order); **precheck == full via a SINGLE shared constraint library — NO double implementation**; the daemon write path **NEVER** uses a JPA `EntityManager` for writes (design §0.1 #3 — flush is JDBC-batch / recorder-sunk only, enforced structurally + by a guard test); **PHP is grand truth, TS (core2026) is the structural port target**; PHP float/rounding/order semantics pinned (`Util::round` = `intval(round($v,0))` half-away-from-zero; integer division; `number_format` comma grouping; JSON key insertion order).

**The TS-trap (HIGH risk — do NOT mirror TS here; this is the one place TS structure yields to PHP behavior):**
- `legacy/devsam-core2026/.../che_상업투자.ts` is a structurally-clean port but its critical math lives in **optional, UNWIRED env hooks** (`getCriticalRatio` / `getDomesticExpLevelBonus` / `getCriticalScoreMultiplier` / `adjustFrontDebuff`). As shipped it always picks `'normal'` with `expBonus=1` and drops PHP's `getIntel(injury, +str/4 cross-stat, clamp)`. **P1 WIRES these hooks with the full PHP math.**
- `legacy/devsam-core2026/.../che_농지개간.ts` extends a *simplified* `cityDevelopment.ts` (fixed `baseAmount:100`, no trust/crit/front-debuff) — **unfaithful to PHP**. **Ignore it.** In Kotlin, `che_농지개간` shares the `che_상업투자` algorithm exactly (PHP: it `extends che_상업투자`, overriding only `cityKey=agri`, `statKey=intel`, `actionKey=농업`, `actionName=농지 개간`).
- **Log name divergence (byte-load-bearing):** PHP logs `static::$actionName` WITH the space ("상업 투자를…", "농지 개간을…"); TS strips it (`ACTION_NAME.replace(' ','')`). **Follow PHP — the log keeps the space.** The `JosaUtil.pick` argument is the spaced name too.

**Tech stack:** Kotlin 2.1 / Spring Boot 3.4 / Spring Data JPA (read/precheck only, game-api) / **JDBC batch** (`JdbcTemplate`/`NamedParameterJdbcTemplate`, daemon write path) / Lettuce Redis (Spring Data Redis) / SHA-512 DRBG (P0-B `:common`) / Testcontainers (`postgres:16-alpine`, `redis:7-alpine`, macOS Docker Desktop quirk already pinned in the build files: `api.version=1.44`, `DOCKER_CONTEXT=default`, `RYUK_DISABLED`). Module layout (all exist, P0-A/P0-B baseline): `:common` (RNG/josa/log/constants/wire), `:logic` (currently empty — **becomes the home of the shared constraint library + action engine + the che command**, mirroring TS `packages/logic`), `:infra` (Flyway baseline + JPA/JDBC), `:app:game-api` (precheck + reservation + SSE), `:app:game-engine` (daemon + turn pipeline + flush). Test command base: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :<module>:test`.

**Reference sources (read before transcribing):**
- PHP grand truth: `legacy/devsam-core/hwe/sammo/Command/General/che_상업투자.php` (the algorithm), `che_농지개간.php` (the 9-line subclass), `Constraint/{NotBeNeutral,NotWanderingNation,OccupiedCity,SuppliedCity,ReqGeneralGold,ReqGeneralRice,RemainCityCapacity}.php` + `Constraint/Constraint.php::testAll` (the short-circuit loop), `hwe/func_process.php:12-71` (`CriticalRatioDomestic`/`CriticalScoreEx`), `hwe/func_converter.php:906-908` (`getDomesticExpLevelBonus`), `hwe/func_gamerule.php:942-952` (`updateMaxDomesticCritical`), `hwe/sammo/General.php:359-418` (`getStatValue`/`getIntel`), `src/sammo/Util.php:14-17` (`round`), `:488-508` (`valueFit`/`clamp`).
- TS structural port target: `legacy/devsam-core2026/packages/logic/src/constraints/{evaluate,types}.ts` (the shared library shape), `packages/logic/src/actions/turn/general/che_상업투자.ts` (the class layout + `DomesticActionContext` + `pickByWeight` draw order).
- DB shape: `infra/src/main/resources/db/migration/V1__baseline.sql` (general/city/general_turn columns + jsonb meta).
- Existing P0-B substrate to EXTEND: `app/game-engine/src/main/kotlin/opensamguk/engine/{turn/InMemoryTurnWorld.kt, turn/TurnWorldModel.kt, turn/DirtyState.kt, flush/FlushOp.kt, flush/DatabaseHooks.kt, redis/RedisCommandStream.kt, redis/RealtimePublisher.kt}`, `app/game-api/.../sse/{RealtimeSubscriber,RealtimeRelayController}.kt`, `common/.../wire/*`.

---

## File Structure

All paths relative to repo root `/Users/apple/Desktop/개인프로젝트/opensamguk`. `[NEW]` = create, `[EDIT]` = modify existing P0-B substrate.

```
gradle/
  libs.versions.toml                              # [EDIT] verify spring-jdbc available via boot BOM (no version bump expected)

logic/                                            # the shared constraint library + action engine + the che command live here (mirrors TS packages/logic)
  build.gradle.kts                                # [EDIT] +:common already; +kotlinx-serialization-json (test goldens), +kotlin-test
  src/main/kotlin/opensamguk/logic/
    domain/
      LogicEntities.kt                            # [NEW] General/City/Nation/WorldEnv logic entities (TS domain/entities.ts shape) — intel/exp/ded/meta bag
      GeneralMeta.kt                              # [NEW] typed accessors over meta jsonb bag: trust/explevel/intel_exp/max_domestic_critical/killturn
    constraints/
      ConstraintTypes.kt                          # [NEW] RequirementKey / ConstraintContext(mode) / StateView / Constraint / ConstraintResult (port types.ts)
      EvaluateConstraints.kt                      # [NEW] evaluateConstraints + collectRequirements (port evaluate.ts) — THE single shared judge
      Presets.kt                                  # [NEW] notBeNeutral/notWanderingNation/occupiedCity/suppliedCity/reqGeneralGold/reqGeneralRice/remainCityCapacity (PHP reason strings + order)
    statview/
      MemoryStateView.kt                          # [NEW] StateView over an in-memory entity map (used by BOTH precheck snapshot and daemon overlay)
    stats/
      GetStatValue.kt                             # [NEW] getStatValue layered calc (injury → cross-stat +round(x/4) → clamp 0..maxLevel → onCalcStat → clamp) + calcCache
      ActionPipeline.kt                           # [NEW] GeneralActionPipeline: onCalcDomestic(cost/score/success/fail) + onCalcStat over the 9-source module list (P1 = empty list / identity)
    domestic/
      DomesticHelpers.kt                          # [NEW] CriticalRatioDomestic / CriticalScoreEx / getDomesticExpLevelBonus / updateMaxDomesticCritical (port func_process/func_converter/func_gamerule)
      DomesticConst.kt                            # [NEW] PHP-pinned constants (DEFAULT_TRUST=50, DEFAULT_FRONT_DEBUFF=0.5, FRONT_STATES=[1,3])
    actions/
      GeneralActionDefinition.kt                  # [NEW] definition contract: key/name/buildConstraints/resolve (port actions/definition.ts + engine.ts outcome types)
      GeneralActionResolveContext.kt              # [NEW] resolve context: general/city/nation draft + rng + addLog + env
      CommerceInvestment.kt                        # [NEW] the SHARED algorithm (che_상업투자) — getCost/calcBaseScore/resolve + mutation + log
      CheNongjigaegan.kt                          # [NEW] che_농지개간 = CommerceInvestment with agri/intel/농업/'농지 개간' params (9-line equivalent)
      CommandRegistry.kt                          # [NEW] action-code → definition factory (휴식 fallback + the two che keys)
    util/
      PhpRound.kt                                 # [NEW] phpRound (half-away-from-zero, returns Int) + numberFormat (comma grouping) + clamp/valueFit
  src/test/kotlin/opensamguk/logic/
    constraints/{PresetsTest,EvaluateConstraintsTest,PrecheckEqualsFullTest}.kt   # [NEW]
    stats/{GetStatValueTest,ActionPipelineIdentityTest}.kt                        # [NEW]
    domestic/DomesticHelpersTest.kt                                                # [NEW]
    actions/{CommerceInvestmentResolveTest,CheNongjigaeganTest,CommandRegistryTest}.kt  # [NEW]
    util/PhpRoundTest.kt                                                           # [NEW]
    golden/CommerceActionLogGoldenTest.kt                                          # [NEW] PHP-golden action-log byte diff
  src/test/resources/golden/p1/
    che-action-fixtures.json                       # [NEW] committed PHP golden (per-case general/city before+after + action-log + reqGold)

infra/
  build.gradle.kts                                 # [EDIT] +:logic (FlushPayload/row-mappers use logic.domain.General/City)
  src/main/kotlin/opensamguk/infra/persistence/
    GeneralRowMapper.kt                            # [NEW] general row ↔ logic General (jsonb meta parse/serialize, key-insertion-order preserved)
    CityRowMapper.kt                               # [NEW] city row ↔ logic City
    JdbcFlushExecutor.kt                           # [NEW] the REAL FlushOp sink: NamedParameterJdbcTemplate batch UPDATE/UPSERT/CREATE/DELETE — JDBC ONLY, never JPA
    ReservedTurnRepository.kt                      # [NEW] general_turn ring-buffer read/write (JDBC)
  src/test/kotlin/opensamguk/infra/persistence/
    {GeneralRowMapperTest,CityRowMapperTest}.kt    # [NEW]
    JdbcFlushExecutorIT.kt                         # [NEW] Testcontainers-postgres: flush a dirty draft, assert row+jsonb byte-comparable to golden
    ReservedTurnRepositoryIT.kt                    # [NEW] ring-buffer write/read/overflow

app/game-api/
  src/main/kotlin/opensamguk/gameapi/
    read/{GeneralReadRepository,CityReadRepository,NationReadRepository,WorldStateReadRepository}.kt  # [NEW] JPA read repos (precheck only)
    precheck/
      PrecheckStateViewFactory.kt                  # [NEW] build a MemoryStateView from last-flushed DB rows
      CommandPrecheckService.kt                    # [NEW] step-1 precheck: rows → entities → view + ctx(precheck) → evaluateConstraints → status
    reserve/
      CommandReserveService.kt                     # [NEW] step-2: publish envelope to MUTATION stream + write general_turn reservation
    web/CommandController.kt                        # [NEW] POST /api/command/{code} → precheck result + (if available) reserve
  src/test/kotlin/opensamguk/gameapi/
    precheck/CommandPrecheckServiceTest.kt         # [NEW]
    reserve/CommandReserveServiceIT.kt             # [NEW] Testcontainers redis+postgres
    web/CommandControllerIT.kt                     # [NEW]

app/game-engine/
  src/main/kotlin/opensamguk/engine/
    turn/
      TurnWorldModel.kt                            # [EDIT] add agri_exp not needed; ensure City exposes agri/comm/supply/front; General meta passthrough (no schema change)
      PerTurnOverlay.kt                            # [NEW] copy-on-write overlay used to build the daemon WorldStateView for one general's turn
      WorldStateViewAdapter.kt                     # [NEW] StateView over InMemoryTurnWorld+overlay (full mode) — the SAME library as precheck
      ChangeRecorder.kt                            # [NEW] diff a pre/post draft → patch + dirty(general/city) (the Immer-produceWithPatches replacement)
      ReservedTurnHandler.kt                       # [NEW] step-4/5: resolve definition, build full ctx+view, FULL constraints, seed RNG, resolveGeneralAction, apply patch
      TurnDaemonLifecycle.kt                        # [NEW] resolve next run time + drain due generals (minimal P1: single profile, processed-count gated)
    flush/
      FlushOp.kt                                   # [EDIT] add a payload-carrying op variant so the executor has rows to write (keep the order contract)
      DatabaseHooks.kt                             # [EDIT] thread the real JdbcFlushExecutor through; keep the exact op order; single transaction
      DaemonWriteGuard.kt                          # [NEW] (test-support marker) — documents the JDBC-only invariant; enforced by the guard test
    redis/RealtimePublisher.kt                     # [EDIT] add turnCompleted RealtimeEvent on the P0-B realtime pub/sub (extend, do not rewrite) — NO commandResult/events-stream in P1 (deferred)
    run/TurnRunService.kt                          # [NEW] step-3→7 orchestrator: consume → enqueue → drain → flush(1 txn) → publish
  src/test/kotlin/opensamguk/engine/
    turn/{ChangeRecorderTest,ReservedTurnHandlerTest,PerTurnOverlayTest}.kt   # [NEW]
    flush/DaemonNoEntityManagerTest.kt             # [EDIT/NEW] guard: game-engine write path class-pool has no jakarta.persistence.EntityManager write API
    run/TurnRunServiceIT.kt                        # [NEW] Testcontainers: full daemon-side round trip → flush → turnCompleted published
  src/test/kotlin/opensamguk/engine/e2e/
    VerticalSliceE2EIT.kt                          # [NEW] THE P1 GATE: api precheck → Redis → daemon → flush → turnCompleted SSE round-trip + golden row+log

tools/
  php-golden/
    README.md                                      # [NEW] how to run the devsam capture harness (project memory quirks) to produce che-action-fixtures.json
    capture_che.php                                # [NEW] one-shot PHP capture: seed fixed general/city, run che_상업투자 + che_농지개간, dump before/after + log
    dump_golden_db.sh                              # [NEW] pg_dump --data-only the general+city+log_entry rows after a known tick → golden DB fragment
```

> **Build prerequisites (shared, performed once by the relevant landing area):**
> - `:logic` gains a `testImplementation(libs.kotlinx.serialization.json)` + `testImplementation(kotlin("test"))` for golden parsing (impl scope NOT needed — logic entities are plain Kotlin; the wire codec stays in `:common`).
> - `infra/build.gradle.kts  # [EDIT] +:logic (FlushPayload/row-mappers use logic.domain.General/City)` — `:infra` currently depends only on `:common` (`infra/build.gradle.kts:12`); AREA D (Task D1) MUST add `implementation(project(":logic"))` because the row mappers + `FlushPayload` reference `logic.domain.General`/`City`. **Acyclicity:** `logic → common`, `infra → common + logic` (no cycle: `:logic` never depends on `:infra`).
> - `spring-jdbc` is already on the classpath transitively via `spring-boot-starter-data-jpa` in `:infra`; the `JdbcFlushExecutor` uses `NamedParameterJdbcTemplate` from that BOM — verify it resolves, do NOT add a pinned version. These are the only shared build mutations — see Self-Review for the consistency check.

---

# Dependency graph & parallelization

```
AREA A  (logic: shared constraint library + statview + util)          ── gates everything; land FIRST
AREA B  (logic: getStatValue + action pipeline + domestic helpers)    ── depends on A (util/round)
AREA C  (logic: CommerceInvestment + 농지개간 + registry + log golden)  ── depends on A + B
AREA D  (infra: row mappers + JdbcFlushExecutor + reserved-turn repo)  ── depends on A (entities) only; PARALLEL with B/C
AREA E  (game-api: precheck + reserve + controller)                   ── depends on A + C + D (precheck reuses C's CommandRegistry.resolve().buildConstraints() = the SAME shared lib that makes precheck==full; E is NOT parallel with C)
AREA F  (game-engine: overlay + change-recorder + handler + run + flush wiring)  ── depends on A + C + D
AREA G  (e2e gate + PHP golden generation + precheck==full + JDBC-guard)  ── depends on ALL; land LAST
```

**Parallel worktree fan-out:** After AREA A lands (green), **AREA B and AREA D are independent** and can execute in parallel worktrees. AREA C depends on B. **AREA E depends on A + C + D** (its precheck service imports C's CommandRegistry — discovered during P1 execution: E cannot be scheduled parallel with C). AREA F depends on A + C + D. AREA G is the final integration gate (serial). Corrected wave schedule for the executor: **{A} → {B, D}∥ → {C} → {E, F}∥ → {G}** (E and F are independent of each other — game-api vs game-engine — and both depend on C, so they run in parallel AFTER C lands).

---

# AREA A — Shared Constraint Library + StateView + PHP-numeric util (gates everything)

> **Port target = TS `legacy/devsam-core2026/packages/logic/src/constraints/{types,evaluate}.ts` for STRUCTURE; PHP `Constraint/*.php` for the REASON STRINGS and the EVALUATION ORDER.** This is THE single shared library that makes `precheck == full` true by construction — it is used unchanged by both game-api (precheck snapshot) and game-engine (daemon overlay). **There must be exactly ONE `evaluateConstraints` and ONE set of presets.** This area MUST land before any other.
>
> **Pinned facts (port verbatim, do NOT re-derive):**
> - `ConstraintContext.mode ∈ {full, precheck}`. In `precheck` mode a missing requirement returns `unknown(missing)`; in `full` mode the constraint's `test()` runs regardless (the overlay always has the rows). (TS `evaluate.ts:8-24`.)
> - `evaluateConstraints` short-circuits on the FIRST `deny` (PHP `Constraint::testAll` loops, returns `[constraintName, reason]` on first failing `test()`). Order is load-bearing.
> - The slice's constraint ORDER (PHP `che_상업투자.php::init` lines 49-57): `NotBeNeutral → NotWanderingNation → OccupiedCity → SuppliedCity → ReqGeneralGold(reqGold) → ReqGeneralRice(0) → RemainCityCapacity(cityKey, actionName)`. **Do not reorder** (TS `cityDevelopment.ts` puts `remainCityCapacity` before `reqGeneralGold` — a TS divergence; follow PHP).
> - Reason strings (byte-exact, from the PHP Constraint classes): `재야입니다.` / `방랑군은 불가능합니다.` / `아국이 아닙니다.` / `고립된 도시입니다.` / `자금이 모자랍니다.` / `군량이 모자랍니다.` / `{keyNick}{josaUn} 충분합니다.` (where `josaUn = JosaUtil.pick(keyNick, '은')` — PHP `RemainCityCapacity.php` uses 은/는; TS hardcodes '이' — follow PHP).
> - `Util::round($v)` = `intval(round($v, 0))` = **half-away-from-zero, returns Int** (PHP `round()` default mode `PHP_ROUND_HALF_UP` = away-from-zero). `Util::clamp`/`valueFit`: if both bounds set and `max < min` → return `min`; else lower-clamp then upper-clamp.

### Task A1 — PHP-numeric util: `phpRound` / `numberFormat` / `clamp`

**Files:** create `logic/src/main/kotlin/opensamguk/logic/util/PhpRound.kt`, `logic/src/test/kotlin/opensamguk/logic/util/PhpRoundTest.kt`; edit `logic/build.gradle.kts`.

Steps:
- [ ] Edit `logic/build.gradle.kts`: add `testImplementation(libs.kotlinx.serialization.json)` and `testImplementation(kotlin("test"))` (the shared build prerequisite — if another area already added them, verify & skip).
- [ ] Create `PhpRound.kt`:
  ```kotlin
  package opensamguk.logic.util

  import java.math.BigDecimal
  import java.math.RoundingMode

  /** PHP Util::round = intval(round($v, 0)) = half-AWAY-FROM-ZERO (PHP_ROUND_HALF_UP), returns Int. */
  fun phpRound(value: Double): Int =
      BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toInt()

  /** PHP Util::clamp / valueFit: max<min → min; else lower-clamp then upper-clamp. min/max nullable. */
  fun clamp(value: Double, min: Double? = null, max: Double? = null): Double {
      if (max != null && min != null && max < min) return min
      if (min != null && value < min) return min
      if (max != null && value > max) return max
      return value
  }
  fun valueFit(value: Double, min: Double? = null, max: Double? = null): Double = clamp(value, min, max)

  /** PHP number_format($v, 0): comma thousands grouping, no decimals (e.g. 12345 -> "12,345"). */
  fun numberFormat(value: Int): String = "%,d".format(value)
  ```
  > NOTE on `RoundingMode.HALF_UP`: Java's `HALF_UP` is half-away-from-zero (matches PHP default `PHP_ROUND_HALF_UP`). `phpRound = BigDecimal.setScale(0, HALF_UP).toInt()`. Do NOT use `Math.round` (half-toward-positive-infinity) or `kotlin.math.round` (half-to-even). For the slice, all rounded values are non-negative (score, reqGold ≥ 0), so the .5 tie only matters for positives — but the away-from-zero mode is pinned NOW (OQ2 RESOLVED), proven by a `phpRound(-2.5) == -3` test so the negative case can never silently break.
- [ ] Create `PhpRoundTest.kt`: assert `phpRound(2.5)==3`, `phpRound(3.5)==4`, `phpRound(2.4)==2`, `phpRound(0.5)==1`, `phpRound(-2.5)==-3` (away-from-zero, OQ2); `numberFormat(12345)=="12,345"`, `numberFormat(0)=="0"`, `numberFormat(1000000)=="1,000,000"`; `clamp(5.0,1.0,10.0)==5.0`, `clamp(-1.0,0.0,null)==0.0`, `clamp(20.0,0.0,10.0)==10.0`, `clamp(5.0,10.0,1.0)==10.0` (max<min returns min).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.util.PhpRoundTest' | tail -30`. Expect `BUILD SUCCESSFUL`, all pass.
- [ ] Commit: `feat(logic/util): PHP-faithful round/numberFormat/clamp` + Co-Authored-By trailer.

### Task A2 — Logic domain entities + meta accessors

**Files:** create `logic/src/main/kotlin/opensamguk/logic/domain/LogicEntities.kt`, `domain/GeneralMeta.kt`; create `logic/src/test/kotlin/opensamguk/logic/domain/GeneralMetaTest.kt`.

Steps:
- [ ] Create `LogicEntities.kt` — the precheck/full shared entity shape (column names align to `V1__baseline.sql`; jsonb columns are `Map<String, Any?>` bags with insertion order preserved via `LinkedHashMap`):
  ```kotlin
  package opensamguk.logic.domain

  /** General as the logic layer sees it. `intel` (not intelligence) matches the DB column. */
  data class General(
      val id: Int,
      val nationId: Int,
      val cityId: Int,
      val leadership: Int,
      val strength: Int,
      val intel: Int,
      val injury: Int,
      val experience: Double,   // raw accumulator (PHP increaseVar adds float, no per-add round); truncated → int only at flush (D1)
      val dedication: Double,   // same — see C2 resolve + D1 General row mapper
      val officerLevel: Int,
      val gold: Int,
      val rice: Int,
      val meta: Map<String, Any?> = linkedMapOf(),   // explevel, intel_exp, max_domestic_critical, killturn
  )

  /** City — comm/agri/supply_state/front_state/trust align to DB; meta holds region etc. */
  data class City(
      val id: Int,
      val nationId: Int,
      val level: Int,
      val commerce: Int, val commerceMax: Int,
      val agriculture: Int, val agricultureMax: Int,
      val supplyState: Int,           // truthy = supplied
      val frontState: Int,            // 1|3 = front (debuff)
      val trust: Double,              // PHP schema.sql:202 trust FLOAT; che math uses trust/100.0 & trust/80.0 — port faithfully as Double
      val meta: Map<String, Any?> = linkedMapOf(),
  )

  data class Nation(val id: Int, val level: Int, val capitalCityId: Int?)

  /** World env read by cost/debuff math. */
  data class WorldEnv(val year: Int, val startYear: Int, val develCost: Int) {
      val relYear: Int get() = year - startYear
  }
  ```
  > NOTE: `intel_exp` — PHP has a `general` COLUMN; TS keeps it in meta JSON; the V1 baseline has NO `intel_exp` column → it lives in `meta`. P1 stores it in `meta["intel_exp"]` to match the baseline schema, and the golden DB dump must reflect that (RESOLVED — see OQ5).
  > NOTE (trust = Double): PHP `schema.sql:202` declares `trust FLOAT` and the che math reads it as `trust/100.0` (calcBaseScore) and `trust/80.0` (successRatio scaling) — so `City.trust` is a `Double` and the fractional math is ported faithfully (Task C2). The V1 baseline `city.trust` column is currently INTEGER; to keep the golden byte-comparable, the G1 golden city MUST be pinned to an INTEGER-valued trust (hard G1 assertion `city.trust == floor(city.trust)`), so the integer baseline column is lossless. **Follow-up (P2+):** reconcile the baseline `trust` column to FLOAT/NUMERIC if a fractional-trust golden is ever needed.
- [ ] Create `GeneralMeta.kt` — typed accessors (PHP `getVar`/`getAuxVar` defaults: missing → 0):
  ```kotlin
  package opensamguk.logic.domain

  fun metaInt(meta: Map<String, Any?>, key: String, default: Int = 0): Int =
      (meta[key] as? Number)?.toInt() ?: default
  fun metaDouble(meta: Map<String, Any?>, key: String, default: Double = 0.0): Double =
      (meta[key] as? Number)?.toDouble() ?: default
  fun withMeta(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
      val next = LinkedHashMap(meta); for ((k, v) in pairs) next[k] = v; return next
  }
  ```
- [ ] Create `GeneralMetaTest.kt`: `metaInt(linkedMapOf("explevel" to 3), "explevel")==3`; missing key → default 0; `withMeta` preserves insertion order and overwrites in place (assert `keys.toList()` ordering after overwrite of an existing key keeps original position — load-bearing for jsonb byte-parity).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.domain.GeneralMetaTest' | tail -30`.
- [ ] Commit: `feat(logic/domain): logic entities + meta accessors (DB-aligned columns)` + Co-Authored-By trailer.

### Task A3 — Constraint contract types + MemoryStateView

**Files:** create `logic/.../constraints/ConstraintTypes.kt`, `logic/.../statview/MemoryStateView.kt`; create `logic/src/test/.../statview/MemoryStateViewTest.kt` (folded into A5 test or standalone).

Steps:
- [ ] Create `ConstraintTypes.kt` — faithful port of `constraints/types.ts`:
  ```kotlin
  package opensamguk.logic.constraints

  sealed interface RequirementKey {
      data class General(val id: Int) : RequirementKey
      data class City(val id: Int) : RequirementKey
      data class Nation(val id: Int) : RequirementKey
      data class Env(val key: String) : RequirementKey
      data class Arg(val key: String) : RequirementKey
  }

  enum class ConstraintMode { FULL, PRECHECK }

  data class ConstraintContext(
      val actorId: Int,
      val cityId: Int? = null,
      val nationId: Int? = null,
      val args: Map<String, Any?> = emptyMap(),
      val env: Map<String, Any?> = emptyMap(),
      val mode: ConstraintMode,
  )

  interface StateView {
      fun has(req: RequirementKey): Boolean
      fun get(req: RequirementKey): Any?
  }

  sealed interface ConstraintResult {
      data object Allow : ConstraintResult
      data class Deny(val reason: String, val constraintName: String? = null) : ConstraintResult
      data class Unknown(val missing: List<RequirementKey>) : ConstraintResult
  }

  interface Constraint {
      val name: String
      fun requires(ctx: ConstraintContext): List<RequirementKey>
      fun test(ctx: ConstraintContext, view: StateView): ConstraintResult
  }
  ```
- [ ] Create `MemoryStateView.kt` — one StateView over entity maps; used by BOTH precheck (DB snapshot) and daemon (overlay). `has` = membership; `get` = entity or null:
  ```kotlin
  package opensamguk.logic.statview

  import opensamguk.logic.constraints.RequirementKey
  import opensamguk.logic.constraints.StateView
  import opensamguk.logic.domain.*

  class MemoryStateView(
      private val generals: Map<Int, General>,
      private val cities: Map<Int, City>,
      private val nations: Map<Int, Nation>,
      private val env: Map<String, Any?>,
  ) : StateView {
      override fun has(req: RequirementKey): Boolean = when (req) {
          is RequirementKey.General -> generals.containsKey(req.id)
          is RequirementKey.City -> cities.containsKey(req.id)
          is RequirementKey.Nation -> nations.containsKey(req.id)
          is RequirementKey.Env -> env.containsKey(req.key)
          is RequirementKey.Arg -> true
      }
      override fun get(req: RequirementKey): Any? = when (req) {
          is RequirementKey.General -> generals[req.id]
          is RequirementKey.City -> cities[req.id]
          is RequirementKey.Nation -> nations[req.id]
          is RequirementKey.Env -> env[req.key]
          is RequirementKey.Arg -> null
      }
  }
  ```
- [ ] Create `MemoryStateViewTest.kt`: `has(General(1))` true when present / false when absent; `get(City(5))` returns the city; `get(Env("year"))` returns the env value.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.statview.MemoryStateViewTest' | tail -30`.
- [ ] Commit: `feat(logic/constraints): constraint contract types + MemoryStateView` + Co-Authored-By trailer.

### Task A4 — `evaluateConstraints` (the single shared judge)

**Files:** create `logic/.../constraints/EvaluateConstraints.kt`; create `logic/src/test/.../constraints/EvaluateConstraintsTest.kt`.

Steps:
- [ ] Create `EvaluateConstraints.kt` — port `evaluate.ts:3-33` exactly (precheck `unknown` on missing; first `deny` wins; allow only if all pass):
  ```kotlin
  package opensamguk.logic.constraints

  fun evaluateConstraints(
      constraints: List<Constraint>,
      ctx: ConstraintContext,
      view: StateView,
  ): ConstraintResult {
      for (c in constraints) {
          val missing = c.requires(ctx).filter { !view.has(it) }
          if (missing.isNotEmpty() && ctx.mode == ConstraintMode.PRECHECK) {
              return ConstraintResult.Unknown(missing)
          }
          when (val r = c.test(ctx, view)) {
              is ConstraintResult.Deny -> return r.copy(constraintName = r.constraintName ?: c.name)
              is ConstraintResult.Unknown -> return r
              ConstraintResult.Allow -> continue
          }
      }
      return ConstraintResult.Allow
  }

  fun collectRequirements(constraints: List<Constraint>, ctx: ConstraintContext): List<RequirementKey> =
      constraints.flatMap { it.requires(ctx) }
  ```
- [ ] Create `EvaluateConstraintsTest.kt` with hand-rolled stub constraints: (a) all-allow → `Allow`; (b) second denies → `Deny` with that constraint's name (short-circuit; third never runs — assert via a counter); (c) precheck + missing requirement → `Unknown(missing)`; (d) full + missing requirement → still runs `test()` (the overlay guarantees presence) and returns its result.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.EvaluateConstraintsTest' | tail -30`.
- [ ] Commit: `feat(logic/constraints): evaluateConstraints single shared judge` + Co-Authored-By trailer.

### Task A5 — The 7 slice presets (PHP reason strings + order)

**Files:** create `logic/.../constraints/Presets.kt`; create `logic/src/test/.../constraints/PresetsTest.kt`.

Steps:
- [ ] Create `Presets.kt`. Each preset is a `Constraint` with PHP-exact `requires` + `test`. Transcribe the 7 PHP classes (`Constraint/*.php`). `reqGeneralGold` takes a cost-resolver `(ctx, view) -> Int` (TS pattern — the cost is computed from the live general/city). `remainCityCapacity` uses `JosaUtil.pick(keyNick, "은")`:
  ```kotlin
  package opensamguk.logic.constraints

  import opensamguk.common.josa.JosaUtil
  import opensamguk.logic.domain.City
  import opensamguk.logic.domain.General
  import opensamguk.logic.domain.Nation

  private fun gen(ctx: ConstraintContext, view: StateView) = view.get(RequirementKey.General(ctx.actorId)) as? General
  private fun city(ctx: ConstraintContext, view: StateView) =
      (ctx.cityId ?: (gen(ctx, view)?.cityId))?.let { view.get(RequirementKey.City(it)) as? City }
  private fun nation(ctx: ConstraintContext, view: StateView) =
      (ctx.nationId ?: (gen(ctx, view)?.nationId))?.let { view.get(RequirementKey.Nation(it)) as? Nation }

  fun notBeNeutral() = object : Constraint {
      override val name = "NotBeNeutral"
      override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
      override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
          val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          return if (g.nationId != 0) ConstraintResult.Allow else ConstraintResult.Deny("재야입니다.")
      }
  }

  fun notWanderingNation() = object : Constraint {
      override val name = "NotWanderingNation"
      override fun requires(ctx: ConstraintContext) =
          listOf(RequirementKey.Nation(ctx.nationId ?: 0))
      override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
          val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          return if (n.level != 0) ConstraintResult.Allow else ConstraintResult.Deny("방랑군은 불가능합니다.")
      }
  }

  fun occupiedCity() = object : Constraint {
      override val name = "OccupiedCity"
      override fun requires(ctx: ConstraintContext) =
          listOf(RequirementKey.General(ctx.actorId), RequirementKey.City(ctx.cityId ?: 0))
      override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
          val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          return if (c.nationId == g.nationId) ConstraintResult.Allow else ConstraintResult.Deny("아국이 아닙니다.")
      }
  }

  fun suppliedCity() = object : Constraint {
      override val name = "SuppliedCity"
      override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
      override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
          val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          return if (c.supplyState != 0) ConstraintResult.Allow else ConstraintResult.Deny("고립된 도시입니다.")
      }
  }

  fun reqGeneralGold(cost: (ConstraintContext, StateView) -> Int) = object : Constraint {
      override val name = "ReqGeneralGold"
      override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
      override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
          val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          return if (g.gold >= cost(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("자금이 모자랍니다.")
      }
  }

  fun reqGeneralRice(cost: (ConstraintContext, StateView) -> Int) = object : Constraint {
      override val name = "ReqGeneralRice"
      override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
      override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
          val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          return if (g.rice >= cost(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("군량이 모자랍니다.")
      }
  }

  fun remainCityCapacity(cityKey: String, keyNick: String) = object : Constraint {
      override val name = "RemainCityCapacity"
      override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
      override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
          val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
          val (cur, max) = when (cityKey) {
              "comm" -> c.commerce to c.commerceMax
              "agri" -> c.agriculture to c.agricultureMax
              else -> error("unknown cityKey $cityKey")
          }
          if (cur < max) return ConstraintResult.Allow
          val josaUn = JosaUtil.pick(keyNick, "은")          // PHP RemainCityCapacity.php uses 은/는
          return ConstraintResult.Deny("$keyNick$josaUn 충분합니다.")
      }
  }
  ```
  > NOTE: PHP `OccupiedCity` has an `allowNeutral` arg path (`$this->arg && general.nation==0`). The slice always passes `allowNeutral=false`, so the Kotlin preset omits it (add a param only if a later command needs it — KEEP minimal). `keyNick` for the slice = the spaced action name ("상업 투자" / "농지 개간") — PHP passes `static::$actionName` as the RemainCityCapacity nick (line 56), so the deny string is e.g. `상업 투자는 충분합니다.` — verify the josa against `JosaUtil.pick("상업 투자","은")` == `는` (ends in vowel ㅏ → no jongsung → `는`).
- [ ] Create `PresetsTest.kt`: one deny + one allow per preset against a `MemoryStateView`. Assert EXACT reason strings byte-for-byte. Add an explicit **은-vs-는 byte assertion for BOTH spaced action names**: `remainCityCapacity("comm","상업 투자")` full-city deny == `"상업 투자는 충분합니다."` (투자 ends in vowel ㅏ → no jongsung → `는`), and `remainCityCapacity("agri","농지 개간")` full-city deny == `"농지 개간은 충분합니다."` (개간 ends in ㄴ jongsung → `은`). Assert `notBeNeutral` denies on `nationId==0`, `suppliedCity` denies on `supplyState==0`, `reqGeneralGold` denies when `gold < cost`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.PresetsTest' | tail -40`.
- [ ] Commit: `feat(logic/constraints): 7 slice presets with PHP reason strings` + Co-Authored-By trailer.

**Area A golden/gate:** none external yet (PHP reason strings are transcribed constants verified by `PresetsTest`). The cross-area `precheck==full` gate is AREA G Task G3.

---

# AREA B — getStatValue + Action Pipeline + Domestic Helpers (depends on A)

> **Port target = PHP `General.php::getStatValue` (the layered calc, lines 359-403) + `func_process.php` (CriticalRatioDomestic/CriticalScoreEx) + `func_converter.php` (getDomesticExpLevelBonus) + `func_gamerule.php` (updateMaxDomesticCritical).** The TS `che_상업투자.ts` DROPS getStatValue's injury/cross-stat/clamp — do NOT mirror TS. This area is independent of B/C-internal ordering relative to AREA D and can run in a PARALLEL worktree after A.
>
> **Pinned facts:**
> - `getStatValue(stat, withInjury, withIActionObj, withStatAdjust, useFloor)`: start `getVar(stat)`; if `withInjury` → `*= (100 - injury)/100`; if `withStatAdjust` → `strength += round(intel/4)`, `intel += round(strength/4)` (cross-stat, computed with `withStatAdjust=false` to avoid recursion, `useFloor=false`); `clamp(0, maxLevel)`; if `withIActionObj` → fold `onCalcStat` over the action list; `clamp(0, maxLevel)` again; if `useFloor` → `Util::toInt` (PHP `toInt` = truncate-toward-zero for the cache-return path).
> - **Score path** (`calcBaseScore`) uses `getIntel(true, true, true, false)` = with injury, with action obj, with stat-adjust, **NO floor** (float).
> - **Ratio path** (`CriticalRatioDomestic`) uses `getLeadership/Strength/Intel(false, true, true, false)` = **NO injury**, no floor. avg = (L+S+I)/3; `ratio = avg/statValue`; `min(ratio,1.2)`; `fail = clamp(pow(ratio/1.2,1.4)-0.3, 0, 0.5)`; `success = clamp(pow(ratio/1.2,1.5)-0.25, 0, 0.5)`.
> - `CriticalScoreEx(rng, pick)`: success → `nextRange(2.2,3.0)`; fail → `nextRange(0.2,0.4)`; normal → `1`. **One RNG draw on success/fail, ZERO on normal** — draw count is parity-load-bearing.
> - `getDomesticExpLevelBonus(expLevel) = 1 + expLevel/500`.
> - `updateMaxDomesticCritical(general, score)` (func_gamerule.php:942-952): aux `max_domestic_critical += score/2`; ALSO bumps the inheritance point IF the new aux value > the stored inheritance point. **P1 emits ONLY the meta (aux) bump in the General draft** (`prev + score/2` on success, `0` on non-success — the che `run()` resets to 0 when `pick != 'success'`). The inheritance-point comparison/write sits OUTSIDE the world/flush boundary and is EXCLUDED from P1 — no current-inheritance read seam, no comparison output (deferred to the P6 inheritance seam; G4 asserts no inheritance table is written). RESOLVED — OQ7.
> - `GameConst.maxLevel = 255` (confirmed in `common/.../GameConst.kt:57` and `GameConstBase.php:95`). Resolves OQ "maxLevel for che scenario".

### Task B1 — `GeneralActionPipeline` (the 9-source action stack, P1 = identity)

**Files:** create `logic/.../stats/ActionPipeline.kt`; create `logic/src/test/.../stats/ActionPipelineIdentityTest.kt`.

Steps:
- [ ] Create `ActionPipeline.kt`. The pipeline folds a list of `GeneralActionModule` over `onCalcStat`/`onCalcDomestic`. **P1 has an EMPTY module list** (no nation-type/officer/special/personality/crew/inherit/scenario/item modules yet — those are P2's "9-source stack merge"). With an empty list every hook is the identity, which matches the scenario_0 default general (no triggers). The interface + fold are built now so P2 only adds modules.
  ```kotlin
  package opensamguk.logic.stats

  import opensamguk.logic.domain.General

  /** One of the 9 action-stack sources (nation-type / officer / domestic-special / war-special /
   *  personality / crew / inheritance / scenario / item). P1 wires ZERO modules — identity fold. */
  interface GeneralActionModule {
      /** onCalcStat(statName, value) -> value. Default identity. */
      fun onCalcStat(general: General, statName: String, value: Double): Double = value
      /** onCalcDomestic(actionKey, varType['cost'|'score'|'success'|'fail'], value) -> value. Default identity. */
      fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double): Double = value
  }

  class GeneralActionPipeline(private val modules: List<GeneralActionModule> = emptyList()) {
      fun onCalcStat(general: General, statName: String, value: Double): Double =
          modules.fold(value) { acc, m -> m.onCalcStat(general, statName, acc) }
      fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double): Double =
          modules.fold(value) { acc, m -> m.onCalcDomestic(general, actionKey, varType, acc) }
  }
  ```
  > NOTE — design §14 open question "General action 스택 9소스의 정확 머지·캐시 무효화 규칙 (P1/P2)": P1 deliberately ships the EMPTY pipeline (the scenario_0 default general triggers no modules, so the golden is byte-identical with an identity fold). The 9-source merge ORDER (국가타입·관직·내정특기·전투특기·성격·병종·계승·시나리오·아이템) and the `calcCache` invalidation rules are a P2 concern — but the seam is built here so P2 only adds modules + the fold order, never reshapes the call sites. **The P1 golden MUST be captured from a default general that triggers no modules** (verify in AREA G capture: no specialDomestic/specialWar/personality affecting 상업/농업). This is recorded as an explicit OQ.
- [ ] Create `ActionPipelineIdentityTest.kt`: empty pipeline `onCalcStat(g,"intelligence",80.0)==80.0`; `onCalcDomestic(g,"상업","cost",16.0)==16.0`; a one-module pipeline (test stub adding +5 to cost) folds correctly.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.stats.ActionPipelineIdentityTest' | tail -30`.
- [ ] Commit: `feat(logic/stats): GeneralActionPipeline 9-source seam (identity in P1)` + Co-Authored-By trailer.

### Task B2 — `getStatValue` layered calc

**Files:** create `logic/.../stats/GetStatValue.kt`; create `logic/src/test/.../stats/GetStatValueTest.kt`.

Steps:
- [ ] Create `GetStatValue.kt` — port `General.php::getStatValue`. Cross-stat adjust computed with `withStatAdjust=false` to avoid recursion; clamp twice; `onCalcStat` via the pipeline; floor with truncate-toward-zero. Take the raw stat values from a `General` + the pipeline + maxLevel:
  ```kotlin
  package opensamguk.logic.stats

  import opensamguk.logic.domain.General
  import opensamguk.logic.util.clamp
  import opensamguk.logic.util.phpRound
  import kotlin.math.truncate

  /** maxLevel = GameConst.maxLevel = 255 (PHP grand truth). */
  fun getStatValue(
      general: General,
      statName: String,                 // "leadership" | "strength" | "intelligence"
      pipeline: GeneralActionPipeline,
      maxLevel: Int = 255,
      withInjury: Boolean = true,
      withIActionObj: Boolean = true,
      withStatAdjust: Boolean = true,
      useFloor: Boolean = true,
  ): Double {
      fun raw(name: String): Int = when (name) {
          "leadership" -> general.leadership
          "strength" -> general.strength
          "intelligence", "intel" -> general.intel
          else -> error("unknown stat $name")
      }
      var v = raw(statName).toDouble()
      if (withInjury) v *= (100 - general.injury) / 100.0
      if (withStatAdjust) {
          // cross-stat (General.php:376-382): strength += round(intel/4); intel += round(strength/4).
          // The OTHER stat is read via a RECURSIVE getStatValue with withStatAdjust=false, useFloor=false
          // (withInjury/withIActionObj forwarded), then /4 then Util::round (half-away-from-zero).
          when (statName) {
              "strength" -> v += phpRound(crossBase(general, "intelligence", pipeline, maxLevel, withInjury, withIActionObj))
              "intelligence", "intel" -> v += phpRound(crossBase(general, "strength", pipeline, maxLevel, withInjury, withIActionObj))
          }
      }
      v = clamp(v, 0.0, maxLevel.toDouble())
      if (withIActionObj) v = pipeline.onCalcStat(general, statName, v)
      v = clamp(v, 0.0, maxLevel.toDouble())
      return if (useFloor) truncate(v) else v
  }

  /** General.php:378-381: getStatValue(other, withInjury, withIActionObj, withStatAdjust=false, useFloor=false) / 4. */
  private fun crossBase(
      general: General, other: String, pipeline: GeneralActionPipeline, maxLevel: Int,
      withInjury: Boolean, withIActionObj: Boolean,
  ): Double =
      getStatValue(general, other, pipeline, maxLevel,
          withInjury = withInjury, withIActionObj = withIActionObj,
          withStatAdjust = false, useFloor = false) / 4.0
  ```
  > NOTE: PHP `Util::toInt` (the `useFloor` path) is truncate-toward-zero; `truncate()` matches for the non-negative stat values here. The cross-stat term mirrors `General.php:376-382` EXACTLY: the OTHER stat is read by a recursive `getStatValue(..., withStatAdjust=false, useFloor=false)` (forwarding `withInjury`/`withIActionObj`), the result divided by 4, then `phpRound` (half-away-from-zero, `Util::round`). The recursion runs the `onCalcStat` pipeline + double-clamp on the cross stat just like PHP (P1 pipeline is empty so the fold is identity).
- [ ] Create `GetStatValueTest.kt`: a general with `intel=90, strength=40, injury=0` → `getIntel(true,true,true,false)` = `90 + round(40/4)=90+10=100`; with `injury=20` → `(90*0.8) + round((40*0.8)/4) = 72 + round(8) = 72+8 = 80`; clamp at maxLevel for a `intel=250,strength=80` case (`250+20=270 → clamp 255`). Assert float (no floor) and floored variants.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.stats.GetStatValueTest' | tail -30`.
- [ ] Commit: `feat(logic/stats): getStatValue layered calc (injury/cross-stat/clamp/onCalcStat)` + Co-Authored-By trailer.

### Task B3 — Domestic helpers (ratio / scoreEx / expBonus / maxCritical)

**Files:** create `logic/.../domestic/DomesticHelpers.kt`, `domestic/DomesticConst.kt`; create `logic/src/test/.../domestic/DomesticHelpersTest.kt`.

Steps:
- [ ] Create `DomesticConst.kt`: `const val DEFAULT_TRUST = 50; const val DEFAULT_FRONT_DEBUFF = 0.5; val FRONT_STATES = setOf(1, 3)`.
- [ ] Create `DomesticHelpers.kt` — port `CriticalRatioDomestic`/`CriticalScoreEx`/`getDomesticExpLevelBonus`/`updateMaxDomesticCritical`. `CriticalRatioDomestic` uses the NO-injury floored-false stat reads:
  ```kotlin
  package opensamguk.logic.domestic

  import opensamguk.common.rng.RandUtil
  import opensamguk.logic.domain.General
  import opensamguk.logic.stats.GeneralActionPipeline
  import opensamguk.logic.stats.getStatValue
  import opensamguk.logic.util.clamp
  import kotlin.math.pow

  data class CriticalRatio(val success: Double, val fail: Double)

  /** func_process.php:12-50 — NOTE: stats read with withInjury=FALSE. */
  fun criticalRatioDomestic(general: General, type: String, pipeline: GeneralActionPipeline, maxLevel: Int = 255): CriticalRatio {
      val l = getStatValue(general, "leadership", pipeline, maxLevel, withInjury = false, useFloor = false)
      val s = getStatValue(general, "strength", pipeline, maxLevel, withInjury = false, useFloor = false)
      val i = getStatValue(general, "intelligence", pipeline, maxLevel, withInjury = false, useFloor = false)
      val avg = (l + s + i) / 3.0
      val statValue = when (type) { "leadership" -> l; "strength" -> s; "intel", "intelligence" -> i; else -> error("bad type $type") }
      val ratio = minOf(avg / statValue, 1.2)
      val fail = clamp((ratio / 1.2).pow(1.4) - 0.3, 0.0, 0.5)
      val success = clamp((ratio / 1.2).pow(1.5) - 0.25, 0.0, 0.5)
      return CriticalRatio(success = success, fail = fail)
  }

  /** func_process.php:63-71 — success draws nextRange(2.2,3.0); fail nextRange(0.2,0.4); normal=1 (no draw). */
  fun criticalScoreEx(rng: RandUtil, pick: String): Double = when (pick) {
      "success" -> rng.nextRange(2.2, 3.0)
      "fail" -> rng.nextRange(0.2, 0.4)
      else -> 1.0
  }

  /** func_converter.php:906 — 1 + expLevel/500. */
  fun getDomesticExpLevelBonus(expLevel: Int): Double = 1.0 + expLevel / 500.0

  /** func_gamerule.php:942-952 — aux max_domestic_critical += score/2 (success) / =0 (non-success, per che run()).
   *  P1 emits ONLY the meta (aux) value for the General draft. The inheritance-point comparison/write
   *  (oldMaxDomesticCritical = getInheritancePoint; bump if greater) sits OUTSIDE the world/flush boundary
   *  and is a P6 seam — it is NOT computed or output here (OQ7; G4 asserts no inheritance table is written). */
  fun updateMaxDomesticCritical(currentAux: Double, score: Int): Double = currentAux + score / 2.0
  ```
- [ ] Create `DomesticHelpersTest.kt`: `getDomesticExpLevelBonus(0)==1.0`, `(500)==2.0`; `criticalScoreEx(rng,"normal")==1.0` with ZERO RNG draws (assert by checking the RNG's draw count is unchanged — use a draw-counting RandUtil stub or compare two rngs); `criticalRatioDomestic` for a balanced general (L=S=I=70) → `avg/stat=1.0`, `fail=clamp(pow(1/1.2,1.4)-0.3,..)`, `success=clamp(pow(1/1.2,1.5)-0.25,..)` — assert against hand-computed doubles (bit-tolerant within 1e-12 OR exact via the AREA G golden); `updateMaxDomesticCritical(10.0, 8)` → `14.0` (aux only, no inheritance comparison).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.domestic.DomesticHelpersTest' | tail -30`.
- [ ] Commit: `feat(logic/domestic): CriticalRatio/ScoreEx/expBonus/maxCritical helpers` + Co-Authored-By trailer.

**Area B golden/gate:** the exact float values of `criticalRatioDomestic` are pinned by the AREA G PHP golden (G1) — B's unit test uses hand-computed doubles as a guard; G's golden is the byte oracle.

---

# AREA C — CommerceInvestment algorithm + 농지개간 + registry + log golden (depends on A + B)

> **Port target = PHP `che_상업투자.php` (`run()`+`calcBaseScore()`+`getCost()`), instantiated for both commands. The TS class layout (`CommandResolver`/`ActionResolver`/`ActionDefinition`) is the STRUCTURE; the PHP `run()` is the BEHAVIOR.** This is where the slice's economics and the byte-exact Korean log live.
>
> **RNG draw order (MUST match PHP `run()` for byte-identical golden):**
> 1. `calcBaseScore` draws `nextRange(0.8, 1.2)` (the score random factor) — FIRST.
> 2. `choiceUsingWeight({fail, success, normal})` draws once (`nextFloat1`).
> 3. `criticalScoreEx` draws `nextRange` ONLY on success/fail (zero on normal).
>
> **Per-action RNG seed (PHP grand truth — `TurnExecutionHelper.php:340-347`):** the per-general-command RNG is seeded with SIX components via `Util::simpleSerialize`:
> ```
> serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, commandShortClassName)
> ```
> - Component 1 `hiddenSeed` = `UniqueConst::$hiddenSeed`, a PER-GAME random (`bin2hex(random_bytes(16))`, `ResetHelper.php:96`). It is NOT a `:common` constant — it is CAPTURED from the golden game's `d_setting`/`UniqueConst` during G1 and committed as a golden FIXTURE INPUT that tests read.
> - Component 2 is the LITERAL string `"generalCommand"` (NOT the actionKey `상업`/`농업`).
> - Components 3-5 are `year`, `month`, `generalId` (ints).
> - Component 6 is the SHORT command class name = `getRawClassName(shortName=true)` → `ReflectionClass::getShortName()` = `che_상업투자` / `che_농지개간` (= the registry key = `name.replace(" ","")`). `BaseCommand.php:261` defaults `shortName=true`.
> - `simpleSerialize` (`Util.php:872`) encodes each string as `str(mb_strlen,value)`, each int as `int(value)`, joined by `|`. For BMP Hangul, Kotlin `String.length` (UTF-16 code units) == PHP `mb_strlen`, so the `str(len,..)` length matches byte-for-byte.
>
> `tryUniqueItemLottery` uses a SEPARATE rng (`genGenericUniqueRNGFromGeneral`) — in P1 it is a no-op for the default general (verify in AREA G: scenario_0 default general wins no unique → no RNG perturbation), so it is NOT seeded from the action rng and does NOT advance the action draw stream.
>
> **Mutation order (PHP `run()` lines 138-224):** trust = `valueFit(city.trust, 50)` (lower-bound only); `score = valueFit(calcBaseScore, 1)`; ratios → trust<80 success scaling → onCalcDomestic success/fail → clamp success [0,1], fail [0, 1-success]; normal = 1-fail-success; `choiceUsingWeight`; THEN `score *= criticalScoreEx; score = round(score)`; `exp = score*0.7; ded = score*1.0` (from POST-crit, PRE-front-debuff score); max_domestic_critical update; build log string (scoreText from PRE-front-debuff score); THEN front-debuff `score *= debuff`; THEN `city[cityKey] = valueFit(city[cityKey]+score, 0, max)`; gold `increaseVarWithLimit(-reqGold, 0)` = `max(0, gold-reqGold)`; `addExperience(exp)`; `addDedication(ded)`; `increaseVar(statKey_exp, 1)`.
>
> **CRITICAL log ordering trap:** `scoreText = number_format($score, 0)` is computed on the **POST-critical, PRE-front-debuff** score (line 176, BEFORE the front-debuff block at 189-204). The CITY mutation uses the **POST-front-debuff** score. So a front-city's log shows a higher number than the city actually gained. Reproduce exactly.

### Task C1 — Action definition + resolve-context contracts

**Files:** create `logic/.../actions/GeneralActionDefinition.kt`, `actions/GeneralActionResolveContext.kt`; (tests folded into C2).

Steps:
- [ ] Create `GeneralActionResolveContext.kt` — the draft + rng + log sink + env, mirroring TS `GeneralActionResolveContext`:
  ```kotlin
  package opensamguk.logic.actions

  import opensamguk.common.rng.RandUtil
  import opensamguk.logic.domain.City
  import opensamguk.logic.domain.General
  import opensamguk.logic.domain.Nation
  import opensamguk.logic.domain.WorldEnv

  /** Mutable per-turn draft. The resolver mutates these in place (the Immer-draft replacement);
   *  ChangeRecorder (game-engine) diffs pre/post to derive patch+dirty. */
  class GeneralActionDraft(var general: General, var city: City, var nation: Nation?)

  class GeneralActionResolveContext(
      val draft: GeneralActionDraft,
      val rng: RandUtil,
      val env: WorldEnv,
      val date: String,                      // turn-time HH:MM for the log <1>date</>
      private val logs: MutableList<String> = mutableListOf(),
  ) {
      fun addLog(text: String) { if (text.isNotEmpty()) logs.add(text) }
      fun logs(): List<String> = logs.toList()
  }

  interface GeneralActionDefinition {
      val key: String
      val name: String
      fun buildConstraints(ctx: opensamguk.logic.constraints.ConstraintContext): List<opensamguk.logic.constraints.Constraint>
      fun resolve(context: GeneralActionResolveContext)
  }
  ```
  > NOTE: P1's log text uses an explicit `<1>$date</>` suffix to match PHP (`<1>$date</>` is appended in the PHP log; TS drops it). The date string comes from the General's turn-time (HH:MM) — supplied by the handler. See log strings below.
- [ ] Create `GeneralActionDefinition.kt` (the interface above is enough; if you split, keep `buildConstraints`/`resolve`/`key`/`name`).
- [ ] (no standalone test — exercised by C2.)
- [ ] Commit: `feat(logic/actions): action definition + resolve-context contracts` + Co-Authored-By trailer.

### Task C2 — `CommerceInvestment` shared algorithm

**Files:** create `logic/.../actions/CommerceInvestment.kt`; create `logic/src/test/.../actions/CommerceInvestmentResolveTest.kt`.

Steps:
- [ ] Create `CommerceInvestment.kt` — the shared algorithm, parameterized by `(cityKey, statKey, actionKey, actionName)`. Transcribe PHP `run()` with the EXACT draw order + mutation order + log strings above. `getCost = round(pipeline.onCalcDomestic(g, actionKey, "cost", env.develCost))`; the front-debuff capital scaling reads `env.relYear`:
  ```kotlin
  package opensamguk.logic.actions

  import opensamguk.common.josa.JosaUtil
  import opensamguk.logic.constraints.*
  import opensamguk.logic.domestic.*
  import opensamguk.logic.domain.*
  import opensamguk.logic.stats.GeneralActionPipeline
  import opensamguk.logic.stats.getStatValue
  import opensamguk.logic.util.*

  open class CommerceInvestment(
      private val pipeline: GeneralActionPipeline,
      private val cityKey: String,        // "comm" | "agri"
      private val statKey: String,        // "intel"
      private val actionKey: String,      // "상업" | "농업"
      override val name: String,          // "상업 투자" | "농지 개간" (WITH space — PHP)
      private val maxLevel: Int = 255,
      private val frontDebuff: Double = DEFAULT_FRONT_DEBUFF,
  ) : GeneralActionDefinition {
      override val key: String get() = "che_${name.replace(" ", "")}"   // che_상업투자 / che_농지개간

      fun getCost(general: General, env: WorldEnv): Int =
          phpRound(pipeline.onCalcDomestic(general, actionKey, "cost", env.develCost.toDouble()))

      override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
          notBeNeutral(), notWanderingNation(), occupiedCity(), suppliedCity(),
          reqGeneralGold { c, view -> getCost(view.get(RequirementKey.General(c.actorId)) as General, envOf(c)) },
          reqGeneralRice { _, _ -> 0 },
          remainCityCapacity(cityKey, name),
      )

      private fun calcBaseScore(d: GeneralActionDraft, rng: opensamguk.common.rng.RandUtil): Double {
          val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())  // lower-bound only; trust is Double (PHP FLOAT)
          var score = getStatValue(d.general, "intelligence", pipeline, maxLevel, withInjury = true, useFloor = false)
          score *= trust / 100.0   // PHP che_상업투자.php:124 — fractional, trust is Double
          score *= getDomesticExpLevelBonus(metaInt(d.general.meta, "explevel"))
          score *= rng.nextRange(0.8, 1.2)                                          // DRAW 1
          return pipeline.onCalcDomestic(d.general, actionKey, "score", score)
      }

      override fun resolve(context: GeneralActionResolveContext) {
          val d = context.draft; val rng = context.rng; val env = context.env
          val reqGold = getCost(d.general, env)
          val trust = valueFit(d.city.trust, DEFAULT_TRUST.toDouble())   // Double (PHP FLOAT)
          var score = valueFit(calcBaseScore(d, rng), 1.0)

          val ratio = criticalRatioDomestic(d.general, statKey, pipeline, maxLevel)
          var successRatio = ratio.success; var failRatio = ratio.fail
          if (trust < 80) successRatio *= trust / 80.0   // PHP che_상업투자.php:144 — fractional, trust is Double
          successRatio = pipeline.onCalcDomestic(d.general, actionKey, "success", successRatio)
          failRatio = pipeline.onCalcDomestic(d.general, actionKey, "fail", failRatio)
          successRatio = clamp(successRatio, 0.0, 1.0)
          failRatio = clamp(failRatio, 0.0, 1.0 - successRatio)
          val normalRatio = 1.0 - failRatio - successRatio

          val pick = rng.choiceUsingWeight(linkedMapOf(                              // DRAW 2 (key order fail,success,normal)
              "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))

          score *= criticalScoreEx(rng, pick)                                       // DRAW 3 (only success/fail)
          val roundedScore = phpRound(score)                                        // POST-crit, PRE-front-debuff

          val exp = roundedScore * 0.7
          val ded = roundedScore * 1.0

          // max_domestic_critical (success: aux += score/2; else reset to 0) — meta/aux ONLY (no inheritance write; OQ7/P6 seam)
          val nextMeta = if (pick == "success") {
              val nextAux = updateMaxDomesticCritical(metaDouble(d.general.meta, "max_domestic_critical"), roundedScore)
              withMeta(d.general.meta, "intel_exp" to metaInt(d.general.meta, "intel_exp") + 1,
                       "max_domestic_critical" to nextAux)
          } else {
              withMeta(d.general.meta, "intel_exp" to metaInt(d.general.meta, "intel_exp") + 1,
                       "max_domestic_critical" to 0)
          }

          // LOG (scoreText from PRE-front-debuff roundedScore; name WITH space; <1>date</> suffix — PHP)
          val scoreText = numberFormat(roundedScore)
          val josaUl = JosaUtil.pick(name, "을")
          val log = when (pick) {
              "fail"    -> "$name$josaUl <span class='ev_failed'>실패</span>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
              "success" -> "$name$josaUl <S>성공</>하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
              else      -> "$name$josaUl 하여 <C>$scoreText</> 상승했습니다. <1>${context.date}</>"
          }
          context.addLog(log)

          // front-debuff (applied to score AFTER exp/ded + log)
          var cityScore = roundedScore.toDouble()
          if (d.city.frontState in FRONT_STATES) {
              var debuff = frontDebuff
              if (d.nation?.capitalCityId == d.city.id && env.relYear < 25) {
                  val scale = clamp((env.relYear - 5).toDouble(), 0.0, 20.0) * 0.05
                  debuff = scale * frontDebuff + (1 - scale)
              }
              cityScore *= debuff
          }

          // mutations (immutable draft replacement)
          val curCity = if (cityKey == "comm") d.city.commerce else d.city.agriculture
          val maxCity = if (cityKey == "comm") d.city.commerceMax else d.city.agricultureMax
          val nextCityVal = valueFit(curCity + cityScore, 0.0, maxCity.toDouble()).toInt()
          d.city = if (cityKey == "comm") d.city.copy(commerce = nextCityVal) else d.city.copy(agriculture = nextCityVal)
          d.general = d.general.copy(
              gold = maxOf(0, d.general.gold - reqGold),
              // PHP increaseVar (LazyVarUpdater.php:68) = raw + delta with NO per-add rounding.
              // experience/dedication are Double in-memory; truncate-toward-zero → Int happens ONLY in the D1 row mapper at flush.
              experience = d.general.experience + exp,
              dedication = d.general.dedication + ded,
              meta = nextMeta,
          )
      }

      private fun envOf(c: ConstraintContext) = WorldEnv(
          year = (c.env["year"] as Number).toInt(),
          startYear = (c.env["startYear"] as Number).toInt(),
          develCost = (c.env["develCost"] as Number).toInt(),
      )
  }
  ```
  > NOTE — `addExperience`/`addDedication` accumulation (RESOLVED against PHP `General.php:448-495` + `LazyVarUpdater.php:68`): PHP `addExperience(float)`/`addDedication(float)` call `increaseVar`, which does `raw[key] = raw[key] + delta` with **NO per-add rounding** — the running value accumulates raw (float). The DB column is `integer`, so the value is truncated toward zero **only at flush**. Therefore the in-memory `General.experience`/`dedication` are `Double`, accumulated as `raw + delta` here (mirroring `increaseVar`); the truncate-toward-zero → `Int` conversion happens **ONLY in the D1 General row mapper** at flush time. (OQ3 RESOLVED.)
  > NOTE — level-change side effects DEFERRED to P2: `addExperience`/`addDedication` (General.php:448-495) ALSO recompute `explevel`/`dedlevel` via `getExpLevel`/`getDedLevel` and, on a boundary cross, push secondary PLAIN logs (`레벨업`/`레벨다운` / `승급`/`강등`). P1 does NOT port the level-change recompute or those secondary logs — see the G1 acceptance checklist deferral + the hard G1 no-level-cross assertion (the captured general's pre/post exp/ded provably stay within one level band, so the golden never exercises this path).
- [ ] Create `CommerceInvestmentResolveTest.kt` with a FIXED-seed RNG using the SIX-component PHP seed shape (`hiddenSeed`, literal `"generalCommand"`, year, month, generalId, short class name = registry key). Use a clearly-marked placeholder captured-`hiddenSeed` fixture constant until the real G2 oracle lands:
  ```kotlin
  // FIXTURE INPUT — replaced by the G2-captured golden hiddenSeed (UniqueConst::$hiddenSeed) before lock.
  private const val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"  // placeholder; G1 captures the real per-game seed
  // serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, shortClassName)
  val rng = RandUtil(LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, 3, 42, "che_상업투자")))
  ```
  and a hand-constructed general/city. Assert: (a) the deterministic `pick` for that seed; (b) the resolved `city.commerce` delta; (c) `general.gold` decremented by reqGold (floored at 0); (d) `meta["intel_exp"]` incremented; (e) the EXACT log string (incl. `<C>`, scoreText comma grouping, `<1>date</>`); (f) two runs with the same seed are identical (determinism). Use the AREA G golden values once available; until then assert structural correctness + determinism.
  > NOTE — `serializeSeed` `str(len,..)` uses `String.length` = UTF-16 code units = PHP `mb_strlen` for BMP Hangul, so the length token matches byte-for-byte. **G2's captured PHP seed string is the FINAL oracle** for the literal — once captured, this fixture asserts against the exact PHP `Util::simpleSerialize(...)` output.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.CommerceInvestmentResolveTest' | tail -40`.
- [ ] Commit: `feat(logic/actions): che_상업투자 shared algorithm (PHP run() draw+mutation+log order)` + Co-Authored-By trailer.

### Task C3 — `che_농지개간` + `휴식` fallback + `CommandRegistry`

**Files:** create `logic/.../actions/CheNongjigaegan.kt`, `actions/CommandRegistry.kt`; create `logic/src/test/.../actions/{CheNongjigaeganTest,CommandRegistryTest}.kt`.

Steps:
- [ ] Create `CheNongjigaegan.kt` — the 9-line-equivalent: `CommerceInvestment(pipeline, cityKey="agri", statKey="intel", actionKey="농업", name="농지 개간")`. Provide a `che_상업투자` factory too (`name="상업 투자"`). Keep them as thin factory functions or subclasses:
  ```kotlin
  package opensamguk.logic.actions
  import opensamguk.logic.stats.GeneralActionPipeline
  fun cheSangeobTuja(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
      CommerceInvestment(pipeline, "comm", "intel", "상업", "상업 투자", maxLevel)
  fun cheNongjigaegan(pipeline: GeneralActionPipeline, maxLevel: Int = 255) =
      CommerceInvestment(pipeline, "agri", "intel", "농업", "농지 개간", maxLevel)
  ```
- [ ] Create `CommandRegistry.kt` — action-code → definition; unknown/deny → the `휴식` fallback definition (no-op resolve, no constraints, no log beyond the rest log). The handler uses this to resolve the reserved action-code and the fallback:
  ```kotlin
  package opensamguk.logic.actions
  import opensamguk.logic.constraints.*
  import opensamguk.logic.stats.GeneralActionPipeline

  object RestAction : GeneralActionDefinition {
      override val key = "휴식"; override val name = "휴식"
      override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()
      override fun resolve(context: GeneralActionResolveContext) { /* no-op: a rest turn produces no mutation/log in P1 */ }
  }

  class CommandRegistry(private val pipeline: GeneralActionPipeline, private val maxLevel: Int = 255) {
      fun resolve(actionCode: String): GeneralActionDefinition = when (actionCode) {
          "che_상업투자" -> cheSangeobTuja(pipeline, maxLevel)
          "che_농지개간" -> cheNongjigaegan(pipeline, maxLevel)
          else -> RestAction
      }
      val fallback: GeneralActionDefinition get() = RestAction
  }
  ```
- [ ] Create `CheNongjigaeganTest.kt`: assert `cheNongjigaegan(p).key == "che_농지개간"`, `name == "농지 개간"`, mutates `city.agriculture` (not commerce), `meta["intel_exp"]++`, and the log uses "농지 개간" WITH space + the correct josa (`JosaUtil.pick("농지 개간","을")` → "간" ends in ㄴ jongsung → "을"). Assert byte-exact log prefix `농지 개간을 `.
- [ ] Create `CommandRegistryTest.kt`: `resolve("che_농지개간")` is a CommerceInvestment with agri; `resolve("che_상업투자")` with comm; `resolve("불명")` and `resolve("휴식")` return `RestAction`; `fallback === RestAction`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.actions.CheNongjigaeganTest' --tests 'opensamguk.logic.actions.CommandRegistryTest' | tail -40`.
- [ ] Commit: `feat(logic/actions): che_농지개간 + 휴식 fallback + CommandRegistry` + Co-Authored-By trailer.

**Area C golden/gate:** `CommerceActionLogGoldenTest` (AREA G Task G2) is the byte oracle for the log strings; C's unit tests pin determinism + structure.

---

# AREA D — infra: row mappers + JDBC flush executor + reserved-turn repo (depends on A; PARALLEL with B/C)

> **The daemon write path is JDBC-ONLY (design §0.1 #3 / §7).** This area builds the REAL `FlushOp` sink: a `NamedParameterJdbcTemplate` batch executor that materializes the recorded flush ops into SQL while preserving the exact `databaseHooks` op order. **No `EntityManager`, no JPA repository, no `@Entity` on the write side.** A guard test (AREA F) asserts the game-engine write path has no `jakarta.persistence` write API in its class pool. jsonb columns serialize with **key insertion order preserved** (byte-comparable to the PHP golden DB dump). Independent of AREA B/C — run in a parallel worktree after AREA A.

### Task D1 — General/City row mappers (logic entity ↔ DB row, jsonb order-preserving)

**Files:** edit `infra/build.gradle.kts`; create `infra/.../persistence/GeneralRowMapper.kt`, `persistence/CityRowMapper.kt`; create `infra/src/test/.../persistence/{GeneralRowMapperTest,CityRowMapperTest}.kt`.

Steps:
- [ ] **Build prerequisite (do this FIRST):** edit `infra/build.gradle.kts` to add `implementation(project(":logic"))` (currently `infra/build.gradle.kts:12` has only `implementation(project(":common"))`). The row mappers + `FlushPayload` (D2) reference `logic.domain.General`/`City`, so `:infra` must depend on `:logic`. **Acyclicity:** `logic → common`, `infra → common + logic` — `:logic` never depends on `:infra`, so no cycle.
- [ ] Create `GeneralRowMapper.kt` — map a JDBC `ResultSet`/`Map<String,Any?>` row to a logic `General` and back to a column map. The `meta` jsonb is parsed into a `LinkedHashMap` (insertion order preserved) and serialized with a deterministic, insertion-order JSON writer (NOT a sorted writer — PHP `Json::encode` preserves PHP array insertion order). Column names: `id, nation_id, city_id, leadership, strength, intel, injury, experience, dedication, officer_level, gold, rice, meta`.
  > Use the `:common` `WireJson`/kotlinx JSON for jsonb parse only if it preserves order; otherwise a small ordered-map JSON encoder. The golden DB dump comparison (D3) is the authority. Pin: numbers serialize without trailing `.0` for integers (match PHP `Json::encode` which emits `1`, not `1.0`).
  > **exp/ded truncation (the ONLY flush-time rounding):** the logic `General.experience`/`dedication` are `Double` (raw accumulators); this mapper truncates them **toward zero** to the `integer` columns at flush (`experience.toInt()` / `.let(kotlin.math.truncate)` then `.toInt()` for the non-negative slice values) — this is the single place the float→int conversion happens (mirrors PHP storing the int column from the float var; C2 keeps them raw in-memory). No per-add rounding anywhere upstream.
  > **meta jsonb encoder (pinned, mirrors PHP `Json::encode`):** compact (NO spaces), UTF-8 literal (do NOT ASCII-escape Korean), unescaped forward slashes, keys in INSERTION order (the `LinkedHashMap`-backed `meta`). The G4 byte-comparison is the oracle.
- [ ] Create `CityRowMapper.kt` — columns `id, nation_id, level, comm, comm_max, agri, agri_max, supply_state, front_state, trust, meta`.
- [ ] Create `GeneralRowMapperTest.kt` / `CityRowMapperTest.kt`: round-trip a row → entity → column-map; assert `meta` key order preserved through round-trip (insert keys `["explevel","intel_exp","max_domestic_critical"]`, overwrite `intel_exp`, assert order unchanged); assert integer jsonb values serialize as `5` not `5.0`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.GeneralRowMapperTest' --tests 'opensamguk.infra.persistence.CityRowMapperTest' | tail -30`.
- [ ] Commit: `feat(infra/persistence): General/City row mappers (jsonb insertion-order preserving)` + Co-Authored-By trailer.

### Task D2 — `JdbcFlushExecutor` (the real FlushOp sink; JDBC batch, single transaction)

**Files:** create `infra/.../persistence/JdbcFlushExecutor.kt`; edit `app/game-engine/.../flush/FlushOp.kt` to carry payload rows (this edit is in AREA F to keep game-engine edits in one area — D consumes a `DirtyState`-shaped payload directly). Create `infra/src/test/.../persistence/JdbcFlushExecutorIT.kt`.

Steps:
- [ ] Create `JdbcFlushExecutor.kt`. Constructor takes a `NamedParameterJdbcTemplate` + a `TransactionTemplate` built over a **`DataSourceTransactionManager`** (NOT the `JpaTransactionManager` that `spring-boot-starter-data-jpa` autoconfig would default to — pin a `DataSourceTransactionManager` bean explicitly for the write path so the JDBC flush never binds a JPA `EntityManager`/persistence-context transaction). It exposes `flush(payload: FlushPayload)` that runs the EXACT `databaseHooks` order in ONE `transactionTemplate.execute { ... }`:
  1. `world_state` UPDATE (always)
  2. `ng_old_nations` UPSERT per deleted-nation snapshot
  3. createMany general/nation/troop/diplomacy (guarded > 0)
  4. deleteMany troop
  5. deleteMany general, then rank_data
  6. nation cascade: diplomacy, nation_turn, nation
  7. updates: general (excl created), city, nation upsert (excl created), troop, diplomacy
  8. rank_data upsert (RANK_ROWS_PER_GENERAL per target)
  9. log_entry createMany
  10. reserved_turns flush
  > P1 only ever exercises steps 1, 7 (general+city UPDATE), 9 (log_entry), 10 — but the executor implements the full ordered contract so later phases don't reshape it. Use `batchUpdate` for the multi-row steps. The General/City UPDATE uses the D1 row mappers; jsonb columns bound via `org.postgresql.util.PGobject` with `type="jsonb"`.
  ```kotlin
  package opensamguk.infra.persistence
  // FlushPayload mirrors DirtyState (engine) but lives in infra so the executor has no engine dep cycle;
  // game-engine maps DirtyState -> FlushPayload at the call site (AREA F).
  data class FlushPayload(
      val worldStateUpdate: Map<String, Any?>,
      val updatedGenerals: List<General>,         // logic entities
      val updatedCities: List<City>,
      val logEntries: List<LogRow>,
      /* created/deleted lists default empty in P1; full contract present for later phases */
  )
  ```
- [ ] Create `JdbcFlushExecutorIT.kt` (Testcontainers `postgres:16-alpine`, Flyway baseline applied): seed one general + one city; build a `FlushPayload` representing a `che_농지개간` post-state; `flush()`; SELECT the rows back; assert the general/city columns + `meta` jsonb match the expected post-state byte-for-byte (the AREA G golden DB fragment once generated); assert exactly ONE general UPDATE, ONE city UPDATE, ONE log_entry INSERT fired (instrument the executor to record its op sequence, assert order == the contract).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.JdbcFlushExecutorIT' | tail -40`. (First run pulls the postgres image.)
- [ ] Commit: `feat(infra/persistence): JdbcFlushExecutor — JDBC-batch flush in one transaction (databaseHooks order)` + Co-Authored-By trailer.

### Task D3 — `ReservedTurnRepository` (general_turn ring buffer)

**Files:** create `infra/.../persistence/ReservedTurnRepository.kt`; create `infra/src/test/.../persistence/ReservedTurnRepositoryIT.kt`.

Steps:
- [ ] Create `ReservedTurnRepository.kt` (JDBC): `reserve(generalId, turnIdx, actionCode, argJson)` upserts a `general_turn` row (`UNIQUE (general_id, turn_idx)`); `readReserved(generalId, turnIdx)` reads it; ring buffer MAX=30 (turn_idx mod 30); default action `휴식` when no row. Mirror TS `reservedTurns.ts setGeneralTurn` semantics.
- [ ] Create `ReservedTurnRepositoryIT.kt` (Testcontainers postgres): write turn_idx 0 = `che_농지개간`; read it back; overwrite same (general, turn_idx) → upsert (no dup); read a never-written idx → default `휴식`.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.ReservedTurnRepositoryIT' | tail -30`.
- [ ] Commit: `feat(infra/persistence): general_turn ring-buffer reserved-turn repo` + Co-Authored-By trailer.

**Area D golden/gate:** the `JdbcFlushExecutorIT` row+jsonb assertion is finalized against the AREA G golden DB fragment (G1) — until then it asserts a hand-built expected post-state.

---

# AREA E — game-api: precheck + reserve + controller (depends on A + D)

> **Step 1 (precheck) + step 2 (reserve) of the 8-step flow.** Precheck uses JPA READ repos to load the last-flushed rows, builds a `MemoryStateView` + `ConstraintContext(mode=PRECHECK)`, and runs the SAME `evaluateConstraints` + the SAME command `buildConstraints` as the daemon. **No constraint logic is re-implemented here — it imports `:logic`.** Reserve publishes the Redis envelope + writes the general_turn row.

### Task E1 — JPA read repositories (precheck only)

**Files:** create `app/game-api/.../read/{GeneralReadRepository,CityReadRepository,NationReadRepository,WorldStateReadRepository}.kt`; create `app/game-api/src/test/.../read/ReadRepositoryIT.kt`.

Steps:
- [ ] Create JPA `@Entity` read-only mappings (game-api ONLY — this is the legitimate JPA use per §7) + Spring Data repos for general/city/nation/world_state. Map jsonb `meta` via a `@Convert` AttributeConverter to `Map<String,Any?>` (read path; order need not be preserved on read since precheck does not write). **The read entity MUST NOT declare an `intel_exp` column** (the V1 baseline has none) — `intel_exp`/`explevel`/`max_domestic_critical` are read from `meta` (OQ5). Likewise `city.trust` reads as the baseline column (integer in V1; the logic `City.trust` is `Double` so the read mapper widens int→Double).
- [ ] Create `ReadRepositoryIT.kt` (Testcontainers postgres + Flyway): seed rows, load via repos, assert entities materialize.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.read.ReadRepositoryIT' | tail -30`.
- [ ] Commit: `feat(game-api/read): JPA read repos for precheck (general/city/nation/world_state)` + Co-Authored-By trailer.

### Task E2 — `CommandPrecheckService`

**Files:** create `app/game-api/.../precheck/{PrecheckStateViewFactory,CommandPrecheckService}.kt`; create `app/game-api/src/test/.../precheck/CommandPrecheckServiceTest.kt`.

Steps:
- [ ] Create `PrecheckStateViewFactory.kt`: load general/city/nation/world_state rows → logic entities → `MemoryStateView` + the `env` map (`year/startYear/develCost`) built via the **single shared env-builder helper** (the SAME helper the daemon's `ReservedTurnHandler` uses in F3 — both call sites call it so the precheck and full-mode env can never drift). `develCost = EffectiveGameConst.develcost(year, startYear)`. Place the helper where both `:app:game-api` and `:app:game-engine` can call it (e.g. a `:logic` `WorldEnvBuilder` keyed by `year/startYear` → `WorldEnv`/env map), so there is exactly one implementation.
- [ ] Create `CommandPrecheckService.kt`: `precheck(generalId, actionCode): PrecheckResult`. Loads the actor's general → derive cityId/nationId → build `ConstraintContext(mode=PRECHECK, actorId, cityId, nationId, env)` → `CommandRegistry.resolve(actionCode).buildConstraints(ctx)` → `evaluateConstraints(...)`. Map `Allow → AVAILABLE`, `Deny → BLOCKED(reason)`, `Unknown → UNKNOWN(missing)`.
- [ ] Create `CommandPrecheckServiceTest.kt` (no DB — hand-built repos/stubs): owned-supplied-funded city + `che_농지개간` → AVAILABLE; non-owned city → BLOCKED("아국이 아닙니다."); insufficient gold → BLOCKED("자금이 모자랍니다."); full agriculture → BLOCKED("농지 개간은 충분합니다.")  (간 ends in ㄴ jongsung → `JosaUtil.pick("농지 개간","은")` == `은`).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.precheck.CommandPrecheckServiceTest' | tail -30`.
- [ ] Commit: `feat(game-api/precheck): CommandPrecheckService via shared :logic constraints` + Co-Authored-By trailer.

### Task E3 — `CommandReserveService` + `CommandController`

**Files:** create `app/game-api/.../reserve/CommandReserveService.kt`, `web/CommandController.kt`; create `app/game-api/src/test/.../reserve/CommandReserveServiceIT.kt`, `web/CommandControllerIT.kt`.

Steps:
- [ ] Create `CommandReserveService.kt`: on AVAILABLE, write the `general_turn` reservation via `ReservedTurnRepository` (the durable source of action-code + arg) AND publish the EXISTING P0-B control signal to the MUTATION stream to wake the daemon (reuse the existing `TurnDaemonCommandEnvelope` + the existing wire command — do NOT add a new variant; see NOTE).
  > NOTE — reserved-turn transport (LEAD RULING, clean NO): **do NOT add a `ReserveGeneralTurn` wire variant and do NOT touch `:common`/`wire` in P1.** The reserved ACTION persists in the `general_turn` ring buffer (`action_code` + `arg` jsonb, `UNIQUE(general_id, turn_idx)`); the daemon wakes via the EXISTING P0-B control signal (reuse the existing wire — Redis carries control + realtime, the DB carries the reserved action, matching TS). No `:common`/wire round-trip test change. (OQ6 RESOLVED.)
- [ ] Create `CommandController.kt`: `POST /api/command/{code}?generalId=` → precheck; if AVAILABLE → reserve → 202 with requestId; else → 200 with the blocked reason.
- [ ] Create `CommandReserveServiceIT.kt` (Testcontainers redis+postgres): reserve `che_농지개간`; assert the stream has one message with the expected envelope AND the general_turn row exists.
- [ ] Create `CommandControllerIT.kt` (MockMvc + Testcontainers): AVAILABLE path returns 202 + requestId; BLOCKED path returns the reason; no stream message on BLOCKED.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.reserve.*' --tests 'opensamguk.gameapi.web.CommandControllerIT' | tail -40`.
- [ ] Commit: `feat(game-api): reserve (Redis MUTATION + general_turn) + command controller` + Co-Authored-By trailer.

**Area E golden/gate:** the `precheck==full` agreement is the AREA G Task G3 gate.

---

# AREA F — game-engine: overlay + change-recorder + handler + run + flush wiring (depends on A + C + D)

> **Steps 3-7 daemon side.** Builds the per-turn copy-on-write overlay, the `WorldStateView` (the SAME `:logic` StateView/constraints — full mode), the change-recorder (Immer-produceWithPatches replacement = the SINGLE dirty source), the `reservedTurnHandler`, and threads the real `JdbcFlushExecutor` into `DatabaseHooks`. **The write path stays JDBC-only — guard test enforces it.**

### Task F1 — `PerTurnOverlay` + `WorldStateViewAdapter` (full-mode StateView)

**Files:** create `app/game-engine/.../turn/PerTurnOverlay.kt`, `turn/WorldStateViewAdapter.kt`; create `app/game-engine/src/test/.../turn/PerTurnOverlayTest.kt`.

Steps:
- [ ] Create `PerTurnOverlay.kt`: a copy-on-write view over `InMemoryTurnWorld` for ONE general's turn — reads fall through to the world; writes stage into the overlay until applied. Convert engine `TurnGeneral`/`City` ↔ logic `General`/`City` (the engine model has more columns; map the slice-relevant subset + carry `meta`).
- [ ] Create `WorldStateViewAdapter.kt`: implements the `:logic` `StateView` over the overlay+world so the daemon evaluates the SAME constraints in `mode=FULL`. (This is the structural proof of "one constraint library".)
- [ ] Create `PerTurnOverlayTest.kt`: read-through returns world rows; a staged write is visible in the overlay but not the world until applied; `WorldStateViewAdapter.has/get` resolve general/city/nation.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.turn.PerTurnOverlayTest' | tail -30`.
- [ ] Commit: `feat(game-engine/turn): per-turn overlay + full-mode WorldStateView adapter` + Co-Authored-By trailer.

### Task F2 — `ChangeRecorder` (the single dirty source)

**Files:** create `app/game-engine/.../turn/ChangeRecorder.kt`; create `app/game-engine/src/test/.../turn/ChangeRecorderTest.kt`.

Steps:
- [ ] Create `ChangeRecorder.kt`: given the pre-state and post-state logic `General`/`City` (the resolver's draft before/after), derive (a) the dirty general/city ids, (b) the column patch (only changed columns, incl. `meta` deep-changed keys). This is the Immer-`produceWithPatches` replacement and MUST be the ONLY thing that marks general/city dirty — the resolver never calls the world's `updateGeneral` directly (design Risk #4: two dirty sources = silent flush divergence).
- [ ] Create `ChangeRecorderTest.kt`: no change → empty patch, nothing dirty; changed `commerce` + `gold` + `meta.intel_exp` → patch lists exactly those; unchanged `meta` keys not in the patch; `meta` key insertion order preserved in the patch.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.turn.ChangeRecorderTest' | tail -30`.
- [ ] Commit: `feat(game-engine/turn): ChangeRecorder — single dirty/patch source (Immer replacement)` + Co-Authored-By trailer.

### Task F3 — `ReservedTurnHandler` (resolve → full constraints → RNG → resolve → apply)

**Files:** create `app/game-engine/.../turn/ReservedTurnHandler.kt`, `turn/TurnDaemonLifecycle.kt`; create `app/game-engine/src/test/.../turn/ReservedTurnHandlerTest.kt`.

Steps:
- [ ] Create `ReservedTurnHandler.kt` — for one due general: read the reserved action-code (from `ReservedTurnRepository` / the enqueued command); `CommandRegistry.resolve(code)`; build `ConstraintContext(mode=FULL)` + `WorldStateViewAdapter` (the `env` map built by the SAME shared env-builder helper as E2 — see NOTE); `evaluateConstraints(definition.buildConstraints(ctx), ctx, view)`; on Deny/Unknown → `CommandRegistry.fallback` (휴식) + push the deny-reason log; else seed the per-action RNG with the SIX-component PHP construction (`TurnExecutionHelper.php:340-347`), build `GeneralActionResolveContext`, `definition.resolve(context)`, then `ChangeRecorder` → apply patch to the world (via the dirty-free apply path — F2) + push logs.
  > **Seed (RESOLVED — PHP grand truth `TurnExecutionHelper.php:340-347`):**
  > ```kotlin
  > RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, definition.key)))
  > //                                   1            2 LITERAL        3     4      5          6 = short class name = registry key
  > ```
  > - Component 1 `hiddenSeed` = `UniqueConst::$hiddenSeed`, a PER-GAME random (`bin2hex(random_bytes(16))`, `ResetHelper.php:96`) — NOT a `:common` constant; it is CAPTURED from the golden game's `d_setting`/`UniqueConst` in G1 and committed as a golden FIXTURE INPUT the daemon/test reads.
  > - Component 2 is the LITERAL string `"generalCommand"` (NOT the actionKey `상업`/`농업`).
  > - Component 6 is the SHORT command class name = `definition.key` = `che_상업투자`/`che_농지개간` (= `getRawClassName(shortName=true)` → `ReflectionClass::getShortName()`; `BaseCommand.php:261` defaults `shortName=true`).
  > - `serializeSeed` `str(len,..)` uses `String.length` = UTF-16 code units = PHP `mb_strlen` for BMP Hangul → byte-match. **G2's captured PHP seed string is the final oracle.**
  > **Env-builder (P1 #7):** the full-mode `ConstraintContext.env` (`year`/`startYear`/`develCost`) MUST be built by the SAME single shared env-builder helper that E2's `PrecheckStateViewFactory` uses (both call sites call the one helper — they CANNOT drift). Add a `ReservedTurnHandlerTest` assertion that the full-mode cost/env equals the precheck env for the fixture (see test below).
- [ ] Create `TurnDaemonLifecycle.kt` (minimal P1): resolve next run time, list due generals for one profile, call the handler per general. **Processed-count gated, NOT wall-clock** for parity replay (research §1e / N5: a wall-clock budget can flush mid-turn, creating a DB state PHP never had). P1 drains all due generals in one pass (no partial checkpoint) so the golden compares at a clean turn boundary.
- [ ] Create `ReservedTurnHandlerTest.kt`: AVAILABLE general + `che_농지개간` → world's city.agriculture increased, general.gold decreased, a log pushed, dirty marked via the recorder (the dirty-free apply path — the resolver never calls `world.updateGeneral/updateCity`; ChangeRecorder is the only dirty source); BLOCKED general (non-owned city) → rest fallback, deny-reason log, no economic mutation; determinism: same world + same seed → identical world post-state across two runs; **env parity:** the full-mode `ConstraintContext.env` (cost/`year`/`startYear`/`develCost`) built by the shared env-builder EQUALS the precheck env for the same fixture (P1 #7 — assert key-for-key equality, proving the single shared helper).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.turn.ReservedTurnHandlerTest' | tail -40`.
- [ ] Commit: `feat(game-engine/turn): reservedTurnHandler (full constraints + RNG + resolve + apply)` + Co-Authored-By trailer.

### Task F4 — Thread `JdbcFlushExecutor` into `DatabaseHooks` + JDBC-only guard

**Files:** edit `app/game-engine/.../flush/FlushOp.kt`, `flush/DatabaseHooks.kt`; create `app/game-engine/.../flush/DaemonWriteGuard.kt`; edit/create `app/game-engine/src/test/.../flush/DaemonNoEntityManagerTest.kt`.

Steps:
- [ ] Edit `FlushOp.kt`: keep the recorder for tests, but add a path where `DatabaseHooks.flushChanges` maps the `DirtyState` (general/city/log) → `infra.persistence.FlushPayload` and calls `JdbcFlushExecutor.flush(payload)` inside one transaction — preserving the op ORDER. Do NOT delete the recorder (tests still assert order via the recorder; the executor records its own op sequence for the IT).
- [ ] Edit `DatabaseHooks.kt`: accept a `JdbcFlushExecutor` (injected); keep the exact 10-step order; the P1-exercised steps (worldState update, general+city update, log_entry createMany, reserved_turns flush) write real SQL, the others are no-ops on empty lists.
- [ ] Create `DaemonNoEntityManagerTest.kt` — the STRUCTURAL guard (design §0.1 #3 / research N4): scan the `opensamguk.engine.flush` + `opensamguk.engine.turn` + `opensamguk.engine.run` package class files' constant pool / imports and ASSERT no reference to `jakarta.persistence.EntityManager`, `EntityManagerFactory`, or a Spring Data write repository on the write path. (Use `Class.forName` reflection + scan declared field/method types, or a ClassGraph scan of the compiled classes, asserting the write-path classes only depend on `org.springframework.jdbc.*` for persistence.)
  > This makes the "JDBC-only" invariant a test, not a convention. If a future edit attaches an `EntityManager` to the daemon write path, this test fails.
- [ ] **Extend the guard to `:infra`** (it carries `kotlin.jpa` + `spring-boot-starter-data-jpa`, so a stray `EntityManager` could leak onto the flush sink): add/extend a guard test (in `:infra`, e.g. `InfraNoEntityManagerTest.kt`, scanning `infra/build/classes/.../opensamguk/infra/persistence`) asserting `JdbcFlushExecutor` + the row mappers + `ReservedTurnRepository` reference NO `jakarta.persistence.EntityManager`/`EntityManagerFactory` and depend only on `org.springframework.jdbc.*` for persistence. (The `:infra` build retains JPA on the classpath for the read path that lives in `:app:game-api`, but the `:infra` persistence write classes must stay JDBC-only.)
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.flush.DaemonNoEntityManagerTest' --tests 'opensamguk.engine.flush.DatabaseHooksOrderTest' | tail -40` and `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.InfraNoEntityManagerTest' | tail -30`.
- [ ] Commit: `feat(game-engine/flush): real JdbcFlushExecutor wiring + JDBC-only guard test (engine + infra)` + Co-Authored-By trailer.

### Task F5 — `TurnRunService` + `turnCompleted` publish

**Files:** create `app/game-engine/.../run/TurnRunService.kt`; edit `app/game-engine/.../redis/RealtimePublisher.kt`; create `app/game-engine/src/test/.../run/TurnRunServiceIT.kt`.

Steps:
- [ ] Edit `RealtimePublisher.kt`: add `publishTurnCompleted(at, lastTurnTime)` → `RealtimeEvent` of type `turnCompleted` on the EXISTING P0-B realtime pub/sub channel (reuse the P0-B `RealtimePublisher`). EXTEND — do not rewrite the existing publisher. **No `commandResult`/events-stream publish in P1** (DECISION = drop from P1 scope): the events-stream `commandResult` publisher+consumer is DEFERRED (P-later). The P1 gate uses ONLY the `turnCompleted` realtime pub/sub → SSE relay round-trip.
- [ ] Create `TurnRunService.kt`: the daemon-side orchestrator — `RedisCommandStream.readCommands` → enqueue → `TurnDaemonLifecycle` drains due generals via the handler → `DatabaseHooks.flushChanges` (1 transaction) → `publishTurnCompleted` (only — no `commandResult`).
- [ ] Create `TurnRunServiceIT.kt` (Testcontainers redis+postgres): seed world + reserved `che_농지개간`; run one tick; assert (a) general/city rows flushed to DB (the post-state), (b) a `turnCompleted` realtime event was published, (c) a log_entry row written. Processed-count gated (no mid-turn flush).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.run.TurnRunServiceIT' | tail -40`.
- [ ] Commit: `feat(game-engine/run): TurnRunService daemon round-trip + turnCompleted publish` + Co-Authored-By trailer.

---

# AREA G — Golden generation + the P1 gate (depends on ALL; land LAST, serial)

> **The parity gate.** Generates the PHP golden (action log + flushed-row DB dump) using the devsam capture harness, then asserts (1) the action-log byte-matches, (2) the flushed General/City row+jsonb byte-matches the golden DB dump, (3) precheck==full agree on a fixture, (4) the full api→Redis→daemon→flush→turnCompleted-SSE round-trip works.

### Task G1 — PHP golden generation (the devsam capture harness)

**Files:** create `tools/php-golden/{README.md, capture_che.php, dump_golden_db.sh}`; output (committed) `logic/src/test/resources/golden/p1/che-action-fixtures.json` + a golden DB fragment.

> **One-shot, manual host step — never CI.** Uses the devsam-core PHP capture environment (project memory quirks: `j_install.php` called twice, `getopt` `=` form, reflection credentials, install not idempotent, dumps byte-identical). Generate ONCE, commit the JSON + DB fragment, regenerate only when the PHP source changes.

Steps:
- [ ] Create `capture_che.php`: boot the devsam scenario_0 seed; pick ONE general in an owned, supplied, **non-front** city with `commerce < commerceMax` / `agriculture < agricultureMax` and `gold >= round(develcost)`, with **no triggers affecting 상업/농업** (verify: no specialDomestic/specialWar/personality module fires — required so the P1 empty-pipeline golden is faithful; this is the §14 9-source-stack OQ guard). Capture BOTH `che_상업투자` and `che_농지개간` turns; obtain DISTINCT `success`/`normal`/`fail` picks as INDEPENDENT reproducible fixtures (vary `generalId`/`month` rather than relying on a single re-stepped seed), each capturing its full env (`year/startYear/develCost`) + the per-action seed string. For each case dump: general row before+after (`gold, experience, dedication, meta.intel_exp, meta.explevel, meta.max_domestic_critical`), city row before+after (`comm`/`agri`; `*_max` unchanged; `trust`), the action-log row(s) **char-for-char** (incl `<C>`/`<S>`/`<span class='ev_failed'>`/`<1>date</>` + comma grouping), the computed `reqGold`, and the RNG seed string used. Also CAPTURE the per-game `hiddenSeed` (`UniqueConst::$hiddenSeed` from `d_setting`) and commit it as a golden FIXTURE INPUT (the seed oracle for C2/G2/F3). Add a BLOCKED case (non-owned / unsupplied / insufficient-gold) to lock `getFailString()`/the deny reason. Name fixtures `commerce_success/_normal/_fail`, `agri_normal`, `blocked_notSupplied`.
- [ ] **G1 hard acceptance assertions (in `capture_che.php` — these are HARD assertions that ABORT the capture if violated, not "verify" prose):**
  - **(1) Distinct success/normal/fail**, each an independent reproducible fixture (vary generalId/month), each with its full env captured.
  - **(2) Module-free general** — assert `special`/`special2`/`personal` codes are empty, all 8 effect slots null, `itemObjs` empty (so the P1 empty-pipeline identity fold is provably faithful).
  - **(3) No level cross** (from P0 #3): assert `getExpLevel(before) == getExpLevel(after)` AND `getDedLevel(before) == getDedLevel(after)` for every captured case (so the deferred level-change log path is never exercised by the golden).
  - **(4) No unique item won + no static event fired** — assert `tryUniqueItemLottery` granted nothing and `StaticEventHandler::handleEvent` produced no event/log for the captured turns (so neither perturbs the action RNG stream).
  - **(P1 #4 integer trust)** — assert the golden city's `trust` is INTEGER-valued (`trust == floor(trust)`), so the integer baseline `city.trust` column is lossless and byte-comparable.
  - **DEFERRAL (P2, NOT an open question):** the level-change side effects — `explevel`/`dedlevel` recompute + the secondary PLAIN `레벨업`/`레벨다운`/`승급`/`강등` logs (General.php:448-495) — are EXPLICITLY deferred to P2. The (3) no-level-cross hard assertion guarantees P1's golden never needs them.
- [ ] Create `dump_golden_db.sh`: `pg_dump --data-only --table=general --table=city --table=log_entry` (or a row-level SELECT to JSON) AFTER the captured tick → a golden DB fragment with byte-exact jsonb (PHP `Json::encode` key insertion order).
- [ ] Create `README.md`: the prerequisite (devsam capture env), the quirks (project memory), the exact run commands, and that the outputs are committed and regenerated only on PHP-source change.
- [ ] Run the capture (manual host) → write `che-action-fixtures.json` + the DB fragment into `logic/src/test/resources/golden/p1/`.
- [ ] Verify: the JSON has all 5 cases; each `success` case's `max_domestic_critical == score/2`; each log string contains the spaced action name + comma-grouped scoreText.
- [ ] Commit: `chore(tools): PHP golden capture for che_상업투자/농지개간 + committed fixtures` + Co-Authored-By trailer.

### Task G2 — Action-log byte-match golden test

**Files:** create `logic/src/test/.../golden/CommerceActionLogGoldenTest.kt`.

Steps:
- [ ] Create `CommerceActionLogGoldenTest.kt`: for each non-blocked fixture, construct the general/city/env from the golden's BEFORE state, seed the per-action RNG with the golden's captured SIX-component seed string (`serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, key)` — the G1-captured `hiddenSeed` is the oracle), set `context.date` = the golden's date, run `CommerceInvestment.resolve`, and assert the FULL ordered `context.logs()` list (NOT just `logs()[0]`) == the golden's ordered action-log lines **byte-for-byte** (in P1 the no-level-cross golden produces exactly one primary line; asserting the whole ordered list catches any stray/extra log). Assert the resolved general/city post-state numbers (gold/exp/ded/commerce|agriculture/intel_exp/max_domestic_critical) == the golden AFTER state.
  > This is the action-log half of the P1 gate (design §12 step 8 / §10.3). Failure modes: josa wrong (JosaUtil), missing space in action name (TS-trap), scoreText not comma-grouped (numberFormat), wrong draw order (RNG), front-debuff applied before scoreText (ordering trap).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.golden.CommerceActionLogGoldenTest' | tail -40`.
- [ ] Commit: `test(logic/golden): che action-log byte-match PHP golden` + Co-Authored-By trailer.

### Task G3 — `precheck == full` invariant test (single shared library)

**Files:** create `logic/src/test/.../constraints/PrecheckEqualsFullTest.kt`.

Steps:
- [ ] Create `PrecheckEqualsFullTest.kt`: build ONE entity fixture (general/city/nation/env). Evaluate the SAME `che_농지개간.buildConstraints` through `evaluateConstraints` TWICE: once with `mode=PRECHECK` over a `MemoryStateView`, once with `mode=FULL` over the SAME view (simulating the daemon overlay). Assert the results are IDENTICAL (Allow==Allow; for a denying fixture, Deny reason+constraintName identical). Add the designed-divergence case (research §1a): mutate the world between precheck and full (e.g. gold drops below cost) → assert precheck=AVAILABLE but full=Deny("자금이 모자랍니다.") and that this is a CLEAN deny→rest path, NOT a parity failure (a PASSING test of the fallback).
  > This is the structural proof that there is ONE constraint library and that divergence is only ever data-freshness, never logic drift.
- [ ] **Cross-call-site enforcement (P1 #10 — beyond same-module-twice + grep):** add a test that drives the ACTUAL `:app:game-api` `CommandPrecheckService` AND the ACTUAL `:app:game-engine` constraint-evaluation entry point (the `ReservedTurnHandler`'s full-mode `evaluateConstraints` path) against the SAME seeded world + the SAME fixture, asserting IDENTICAL outcome class (Allow/Deny/Unknown → AVAILABLE/BLOCKED/UNKNOWN) AND the IDENTICAL reason string. (This proves the two real call sites, not just two invocations within `:logic`, agree.) Place it where it can depend on both apps (an e2e/integration source set) — exercise it for an AVAILABLE fixture and a denying fixture.
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests 'opensamguk.logic.constraints.PrecheckEqualsFullTest' | tail -30`.
- [ ] Commit: `test(logic/constraints): precheck==full invariant + designed-divergence fallback` + Co-Authored-By trailer.

### Task G4 — End-to-end vertical-slice gate (the P1 GATE)

**Files:** create `app/game-engine/src/test/.../e2e/VerticalSliceE2EIT.kt` (or a dedicated e2e module test wiring game-api + game-engine against shared Testcontainers).

Steps:
- [ ] Create `VerticalSliceE2EIT.kt` (Testcontainers postgres+redis, Flyway baseline, scenario_0-equivalent seed matching the golden BEFORE state): 
  1. game-api `CommandPrecheckService.precheck(generalId, "che_농지개간")` → AVAILABLE.
  2. `CommandReserveService` → Redis MUTATION envelope + general_turn row.
  3. game-engine `TurnRunService` consumes the stream, drains the general, resolves `che_농지개간`, flushes (1 txn).
  4. Subscribe to the realtime channel; assert a `turnCompleted` event arrives (or assert the SSE relay forwards it via `RealtimeRelayController`).
  5. SELECT the general+city+log_entry rows; assert they byte-match the golden DB fragment (G1) — general gold/exp/ded/meta jsonb (key order!), city agriculture, log_entry text.
  6. Assert ONLY general+city+log_entry changed (no spurious writes; inheritance/storage/hall/dynasty untouched per TruncateContract).
- [ ] Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.e2e.VerticalSliceE2EIT' | tail -50`.
- [ ] Full suite sanity: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test :app:game-api:test :app:game-engine:test | tail -40` → all `BUILD SUCCESSFUL`.
- [ ] Commit: `test(e2e): P1 vertical-slice gate — api→Redis→daemon→flush→turnCompleted + golden row+log` + Co-Authored-By trailer.

**Area G golden/gate (the P1 GATE, design §12 step 8):** action-log byte-match (G2) + flushed General/City row+jsonb byte-comparable to the golden DB dump (G4 step 5) + precheck==full (G3) + full round-trip (G4). All four green = P1 closed.

---

## Self-Review

**Consistency hazards (verify before/while executing):**
- **Single shared build mutation:** the `:logic` `testImplementation(libs.kotlinx.serialization.json)` + `kotlin("test")` is added once (Task A1). Later logic-area tasks VERIFY it is present, never re-add. `:infra`/`:app:*` already have their test deps. `spring-jdbc` must NOT be pinned — it comes from the boot BOM via `spring-boot-starter-data-jpa` already present in `:infra`.
- **ONE constraint library:** `:logic/constraints/{Presets,EvaluateConstraints}.kt` is the only place constraint logic exists. game-api (E2) and game-engine (F3) both import it; NEITHER re-implements `test()`. The guards are (a) the grep-scan — `evaluateConstraints` and the 7 preset names must appear ONLY in `:logic`; (b) `PrecheckEqualsFullTest` (G3) same-view PRECHECK==FULL; and (c) the **cross-call-site test (G3, P1 #10)** that drives the REAL `:app:game-api` `CommandPrecheckService` and the REAL `:app:game-engine` full-mode evaluation against the same seeded world, asserting identical Allow/Deny/Unknown + identical reason string. A second implementation cannot pass (c) silently.
- **ONE dirty source:** the resolver mutates the draft only; `ChangeRecorder` (F2) is the ONLY thing that marks general/city dirty. P0-B's `InMemoryTurnWorld` marks dirty INSIDE its own `updateGeneral`/`updateCity` — so the F-area apply MUST NOT go through those (that would double-count dirty against the ChangeRecorder). F3 applies the ChangeRecorder patch via a **dirty-free apply path** (either a new world apply method that writes rows WITHOUT internal dirty-marking, or by routing the apply exclusively through ChangeRecorder so `InMemoryTurnWorld`'s internal marking is never invoked on the turn path). The resolver/handler must NOT call `world.updateGeneral/updateCity` directly (research Risk #4). Review F2/F3 for any direct dirty-marking world-write from the resolver path. (Flow step 6 reflects this.)
- **JDBC-only write path:** `DaemonNoEntityManagerTest` (F4, `:app:game-engine`) AND `InfraNoEntityManagerTest` (F4, `:infra`) are the structural enforcers. The game-engine write path (`engine.flush`, `engine.run`, `engine.turn`) AND the `:infra` persistence write classes (`JdbcFlushExecutor` + row mappers + `ReservedTurnRepository`) must depend only on `org.springframework.jdbc.*` for persistence; JPA `@Entity`/repos live ONLY in `:app:game-api/read`. Verify the `:infra` `JdbcFlushExecutor` uses `NamedParameterJdbcTemplate` over a pinned **`DataSourceTransactionManager`** (D2 — NOT the `JpaTransactionManager` the data-jpa autoconfig would default to), never `EntityManager`. (`:infra` keeps JPA on the classpath for the game-api read path, so the infra guard is required to keep the write classes clean.)
- **Log byte-parity ordering trap:** scoreText is computed PRE-front-debuff; city mutation is POST-front-debuff. The golden BEFORE/AFTER city + log must reflect this asymmetry for a front city. The non-front golden cases (G1 picks a non-front city) do not exercise it — add a front-city fixture if the golden capture can produce one, else flag as a P1-watch deferred case.
- **Action name space:** PHP logs "상업 투자"/"농지 개간" WITH space; the `che_` key strips it (`name.replace(" ","")`). Verify `CommerceInvestment.key` produces `che_상업투자`/`che_농지개간` while `name`/log keep the space.

**Placeholder scan (RESOLVED — no remaining STUBS in the plan):**
- exp/ded accumulation (C2) — RESOLVED: PHP `increaseVar` (LazyVarUpdater.php:68) adds the float delta raw, NO per-add rounding; in-memory `General.experience`/`dedication` are `Double` accumulators, truncated-toward-zero → `Int` ONLY in the D1 General row mapper at flush. (No `phpFloorAdd` stub anymore.)
- action RNG seed (C2/F3/G2) — RESOLVED: SIX-component `serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, shortClassName)` (`TurnExecutionHelper.php:340-347`). `hiddenSeed` is a per-game random captured in G1 and committed as a fixture INPUT (NOT a `:common` constant). Component 6 = `definition.key` (`che_상업투자`/`che_농지개간`). G2's captured PHP seed string is the final oracle.
- reserved-turn transport (E3) — RESOLVED (clean NO): NO `ReserveGeneralTurn` wire variant, NO `:common`/wire change in P1. The action persists in the `general_turn` ring buffer; the daemon wakes via the EXISTING P0-B control signal. (OQ6.)
- jsonb encoder (D1) — must preserve key insertion order, emit integers without `.0`, be compact (no spaces), UTF-8 literal (no ASCII-escaping Korean), unescaped slashes — mirror PHP `Json::encode`. The G4 byte-comparison is the oracle.

**Type consistency:**
- DB column `intel` (not `intelligence`) — logic `General.intel`; `getStatValue("intelligence")` maps to it. Verify no stray `intelligence` column reference.
- `intel_exp` lives in `meta` jsonb (no baseline column) — General entity stores it in `meta`, not a field. The golden DB dump reflects `meta.intel_exp`.
- `supplyState`/`frontState` are Int (truthy = supplied; 1|3 = front) — match DB `supply_state`/`front_state`.
- RNG draw types: `RandUtil.nextRange` returns Double; `choiceUsingWeight` returns String key; assert the linkedMap key order `fail,success,normal` (insertion order = PHP array order) — `jsKeyOrder` does not reorder string keys, so insertion order holds. **Add unit assertions (P2 #16):** (a) `jsKeyOrder(setOf("fail","success","normal")) == listOf("fail","success","normal")` — proving the numeric-first divergence does NOT reorder non-numeric keys; (b) a `choiceUsingWeight` assertion that a FIXED draw (a seeded/stubbed `nextFloat1`) returns the SAME bucket as a hand-computed cumulative-cutoff over the `fail→success→normal` order.
- `phpRound` returns Int; score math is Double until `phpRound(score)`; city/gold/exp/ded derive from the Int roundedScore.

## Open Questions — RESOLVED (baked into the tasks above; kept for the audit trail)

1. **9-source action-stack merge + cache (design §14) — RESOLVED for P1.** P1 ships the EMPTY `GeneralActionPipeline` (identity), faithful because the G1-captured golden general is HARD-asserted module-free (special/special2/personal empty, 8 effect slots null, itemObjs empty — G1 assertion (2)). The full 9-source merge ORDER (국가타입·관직·내정특기·전투특기·성격·병종·계승·시나리오·아이템) and `getStatValue` `calcCache` invalidation are a P2 concern; the seam is built so P2 only adds modules.
2. **`Util::round` semantics — RESOLVED.** `phpRound = BigDecimal.setScale(0, HALF_UP).toInt()` (half-AWAY-from-zero). Add a `phpRound(-2.5) == -3` test note (negative case pinned now even though the slice is non-negative), so the away-from-zero behavior is locked, not assumed.
3. **exp/ded accumulation — RESOLVED.** PHP `increaseVar` adds the float delta raw (no per-add round); in-memory `General.experience`/`dedication` are `Double`, truncated-toward-zero → `Int` only in the D1 row mapper at flush. No `phpFloorAdd` stub. (See P0 #3 / C2 / D1.)
4. **Action RNG seed string — RESOLVED.** SIX components: `serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, shortClassName)` (`TurnExecutionHelper.php:340-347`). Component 2 is the literal `"generalCommand"`; component 6 is `definition.key` (`che_상업투자`/`che_농지개간`). `hiddenSeed` is a per-game random captured in G1 as a fixture INPUT. `str(len,..)` uses `String.length`=UTF-16 code units=PHP `mb_strlen` for BMP Hangul. G2's captured PHP seed string is the final oracle.
5. **`intel_exp` storage — RESOLVED.** PHP has a column; the V1 baseline has none → P1 stores `intel_exp` in `meta` jsonb (a `LinkedHashMap`, insertion order preserved). Likewise `explevel` and `max_domestic_critical` live in `meta`. The precheck JPA read path MUST NOT reference an `intel_exp` column — it reads from `meta`. The golden DB dump records `meta.intel_exp`.
6. **Reserved command transport — RESOLVED (clean NO).** NO `ReserveGeneralTurn` wire variant; NO `:common`/wire change in P1. The reserved action persists in the `general_turn` ring buffer (`action_code` + `arg` jsonb, `UNIQUE(general_id, turn_idx)`); the daemon wakes via the EXISTING P0-B control signal (reuse the existing wire). (See E3.)
7. **`max_domestic_critical` inheritance-point write — RESOLVED.** P1 records ONLY `meta['max_domestic_critical'] += score/2` (success) / `= 0` (non-success, per che `run()`) in the General draft. The inheritance-point write is EXCLUDED from the flush op list (no current-inheritance read seam, no comparison output in P1); G4 asserts no inheritance table is written. The inheritance bump is a P6 seam. (See P2 #14 / B3.)
8. **`tryUniqueItemLottery` + `StaticEventHandler` — RESOLVED.** Both are no-ops for P1; G1's hard assertion (4) confirms the captured general wins no unique item and fires no static event, so neither perturbs the action RNG stream and the golden seed stream is exact.
9. **Parity grading stage — RESOLVED.** The gate grades the RAW stored log text (pre-`convertLog`, with `<C>`/`<S>` markup intact — what `finalizeLogEntry` persists) as the PRIMARY G2 gate. A rendered-HTML (post-`convertLog`) grade can be added as a secondary golden field later if wanted.
10. **Front-city golden coverage — DEFERRED (decided).** G1 picks a non-front city (simplest deterministic case); the PRE/POST-front-debuff scoreText asymmetry is only exercised by a front city. The front-debuff math IS ported in P1 (C2) regardless; capturing a front-city golden fixture is deferred to P2. Flagged as a P1-watch in the Self-Review.

**Pinned fact — meta jsonb encoder (mirror PHP `Json::encode`):** compact (no spaces), UTF-8 literal (do NOT ASCII-escape Korean), unescaped forward slashes, keys in insertion order (`LinkedHashMap`-backed `meta`). Applies to the D1 General/City row mappers and the G1 golden DB dump; the G4 byte-comparison is the oracle.
