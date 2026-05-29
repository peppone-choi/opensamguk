# P1 Vertical-Slice Plan — Adversarial Review Report

> Multi-agent review (46 agents, 37 findings → 34 confirmed after adversarial verification), 2026-05-29, before execution.
> Dimensions: feasibility · coherence · scope · correctness · parity-risk + a 10-item Open-Questions triage.

## Verdict: **go-with-edits**

CQRS decomposition (precheck→reserve→consume→drain→resolve→flush→SSE), single-shared-constraint-library discipline, JDBC-only write path, empty-pipeline-faithful seam, and the golden-byte-match gate are all correct. No rework. Three blockers bake wrong oracles/contracts into the TDD red phase and MUST be fixed in plan text first.

## P0 — must fix before ANY execution (3)

1. **Action-RNG seed is wrong** (AREA C draw-order, C2, C2 test literal, F3, Self-Review, OQ4). Verified `TurnExecutionHelper.php:340-347` + `BaseCommand.php:261`: PHP seeds with SIX components — `simpleSerialize(UniqueConst::$hiddenSeed, "generalCommand", year, month, general.getID(), commandShortClassName)`. Component 2 is the literal `"generalCommand"` (NOT actionKey), component 6 is the SHORT class name `"che_상업투자"`/`"che_농지개간"`. Plan's 5-arg `serializeSeed(seedBase, actionKey, …)` is wrong on tag, field count, className. **`hiddenSeed` is a per-game random (`bin2hex(random_bytes(16))`, ResetHelper.php:96) — NOT a `:common` constant; CAPTURE from the golden game's `d_setting`/`UniqueConst` as a committed fixture input.** Wrong seed → every draw diverges from #1 → whole G2+G4 gate breaks. OQ4→RESOLVED.
2. **`:infra` missing `:logic` dep** (File Structure build list, build-prereq note, D1/D2). Verified `infra/build.gradle.kts:12` = only `implementation(project(":common"))`. AREA D's FlushPayload/row-mappers use `opensamguk.logic.domain.General/City` → won't compile. AREA D runs in a parallel worktree right after A (nothing touches the file first). Add `implementation(project(":logic"))` as a D1 prerequisite + the File-Structure edit line. Acyclic: logic→common, infra→common+logic.
3. **`addExperience`/`addDedication` side effects** (C2 resolve, phpFloorAdd note, G1 capture, G2 assert, OQ3). Verified `General.php:448-495`: each recomputes explevel/dedlevel and on level change writes the level var + pushes a SECOND PLAIN log line (레벨업/레벨다운, 승급/강등). Plan pushes 1 log, asserts only `logs()[0]`. **Decision (applied): take (b)** — constrain G1 capture to a provably non-level-crossing general (hard assertion `getExpLevel(before)==after` && `getDedLevel(before)==after` in `capture_che.php`), defer the level-change log path to P2 (G1 acceptance checklist, not an OQ). ALSO G2 asserts the FULL ordered `logs()` list. In-memory exp/ded = Double (accumulate raw+delta, no per-add rounding); truncate→Int only at the D1 flush mapper.

## Major / P1 — fix before the relevant area lands (9)

4. **City trust Float vs Int** (A2, C2). Verified `schema.sql:202` trust FLOAT, used fractionally (`score *= trust/100`, `successRatio *= trust/80`); baseline:49 trust integer. **Decision (applied):** `City.trust = Double` in the logic entity + faithful fractional math; G1 golden city pinned to an **integer-valued** trust (hard assertion) so the integer baseline column is lossless + byte-comparable; flag baseline trust-FLOAT reconciliation as a documented follow-up for any future fractional-trust golden.
5. **commandResult transport** (flow step 7, F5). P0-B built ONLY `publishTurnCompleted` realtime pub/sub + SSE subscribe; no events-stream. **Decision (applied): take (a)** — DROP events-stream commandResult from P1; the gate uses only the turnCompleted realtime→SSE round-trip. commandResult/events-stream deferred.
6. **RemainCityCapacity josa byte error** (E2 fixture): `농지 개간는` → `농지 개간은` (간 ends ㄴ jongsung → `JosaUtil.pick("농지 개간","은")`=은). Add explicit 은-vs-는 byte assertion for both spaced action names.
7. **Full-mode env drift** (F3 vs E2): daemon full-mode ConstraintContext must use the SAME year/startYear/develCost keys as E2's precheck factory — via a single shared env-builder helper. Add ReservedTurnHandlerTest assertion.
8. **Dirty-source contradiction** (F2/F3 vs flow step 6): "InMemoryTurnWorld marks dirty" vs "ChangeRecorder is the ONLY dirty source". P0-B's InMemoryTurnWorld marks dirty inside updateGeneral/updateCity. Resolve to one source; add a dirty-free apply path or route through ChangeRecorder.
9. **JDBC-only guard scope** (F4, D2): extend the no-EntityManager guard to `:infra` (the module carrying kotlin.jpa + data-jpa) — scan `infra/.../persistence` asserting JdbcFlushExecutor/row-mappers/ReservedTurnRepository use no EntityManager. Pin JdbcFlushExecutor transaction manager (DataSourceTransactionManager, not JpaTransactionManager).
10. **precheck==full enforcement** (G3): beyond same-module twice-run + grep — add a cross-call-site test driving the ACTUAL game-api CommandPrecheckService and game-engine constraint entry against the same seeded world+fixture, asserting identical Allow/Deny/Unknown.
11. **G1 hard assertions** (G1, OQ1/8): make load-bearing guarantees committed assertions, not prose — distinct success/normal/fail picks as independent reproducible fixtures; module-free general; no-level-cross; no-unique-item/no-static-event.
12. **OQ6 ReserveGeneralTurn — clean NO** (E3, Self-Review, OQ6). **LEAD ruling (applied):** do NOT add a ReserveGeneralTurn wire variant, do NOT touch `:common/wire` in P1. The reserved action lives in the `general_turn` ring buffer (action_code + arg jsonb, `UNIQUE(general_id,turn_idx)`); daemon wakes via the existing control signal.

## P2 (4)

13. crossBase: recurse `getStatValue(withStatAdjust=false,useFloor=false)` + phpRound /4, mirror General.php:376-382.
14. updateMaxDomesticCritical: pass real inheritance point (read seam) OR remove shouldBump from P1, re-add in P6.
15. Drop `needsInput` from flow status list (keep available|blocked|unknown) + `parseArgs` from GeneralActionDefinition comment.
16. Add jsKeyOrder assertion (`["fail","success","normal"]` unreordered) + choiceUsingWeight cumulative-cutoff assertion.

## OQ resolutions (resolve-now → applied)

- OQ2 round: `phpRound = BigDecimal.setScale(0,HALF_UP).toInt()`, away-from-zero; add `phpRound(-2.5)==-3` test.
- OQ3 exp/ded: Double in-memory, accumulate raw+delta no rounding; truncate→Int only at D1 mapper.
- OQ5 intel_exp/explevel/max_domestic_critical → `meta` jsonb (LinkedHashMap); precheck JPA read must NOT reference a column.
- OQ7 max_domestic_critical: `meta['max_domestic_critical'] += score/2` (success) or `=0` (non-success); inheritance-point write EXCLUDED from flush; G4 asserts no inheritance table written; P6 seam.
- OQ8 tryUniqueItemLottery/StaticEventHandler = no-op for P1; G1 verifies no unique item + no static event.
- OQ9 grade RAW stored log text (pre-convertLog, markup intact) as PRIMARY G2 gate.
- meta jsonb encoder: compact (no spaces), UTF-8 literal (no ASCII-escape Korean), unescaped slashes, insertion-order LinkedHashMap (mirror Json::encode).

## Deferred (P2/P6)

Front-city front-debuff fixture (port math now, fixture P2); RequirementKey.Arg/collectRequirements (cheap faithful port, unused in slice); serializeSeed Double branch (int/string-only seed); calcCache memoization (P1 cache-free is correct); 9 concrete GeneralActionModule classes (P1 = interface + identity fold + 1 stub); che_상업투자::run trailing checkStatChange/StaticEventHandler (P1 no-op, verify at G1); inheritance-point write (P6 seam).

## Residual risks

hiddenSeed capture dependency (per-game random, Kotlin can't regen → must capture); golden faithfulness unverifiable until G1 runs on host; two ConstraintContext build sites remain a drift surface; JdbcFlushExecutor TX-manager binding (data-jpa autoconfig → JpaTransactionManager default); meta jsonb byte-comparability across two schemas (golden = opensamguk-shaped projection); parallel-worktree build-mutation discipline (libs + :logic test dep + :infra +:logic) must precede the wave.

_Full output: `tasks/w7ex1gx69.output` (run wf_7bf4bcb1-3a3)._
