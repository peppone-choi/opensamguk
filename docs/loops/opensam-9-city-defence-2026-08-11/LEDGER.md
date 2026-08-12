# OPENSAM-9 city defence-train read parity ledger

## Contract and scope fence

- The root orchestrator confirmed the direct user-authorized OPENSAM-9 delegation as the
  task-local contract after `.ai/task.md` was found to be the unrelated active OPENSAM-43
  contract. Per the delegated scope, no shared `.ai/*` file is edited.
- Only the controller, its focused test, this loop directory, and one review artifact may
  change. No schema/source-of-truth widening is proposed.

## Oracle and root-cause inventory

- PHP evidence: `legacy/devsam-core/hwe/b_currentCity.php:214` loads
  `defence_train`; lines 303-323 mask foreign values; lines 392-438 count only own,
  non-zero-crew generals after `min(train, atmos) >= defenceTrain`.
- PHP setting evidence: `legacy/devsam-core/hwe/j_set_my_setting.php:18,28-32` gives the
  absent setting default `80` and normalizes the opt-out setting to `999`.
- Kotlin read input: `GeneralReadEntity.meta` is decoded from JSONB at
  `GeneralReadRepository.kt:170-176`, and the established sibling
  `MyController.kt:100-105` already reads `metaInt(g.meta, "defence_train", 80)`.
- Kotlin defect: `CityDetailController.kt:259-284` currently substitutes
  `defenceTrain = 0`, so every own armed general with nonnegative training is overcounted.
- World scope: `GeneralReadRepository.kt:342-380` fixes the process `WorldId` and delegates
  the city lookup to `findByWorldIdAndCityIdOrderByTurnTimeAsc`; this slice must retain that
  wrapper call unchanged.

## Diagnostic hypotheses

1. **H1 (expected root cause):** the literal `0` in the own-aggregate branch discards the
   persisted metadata threshold. A controller regression with a `90` threshold and a
   `min(train, atmos)` value below it should fail before the implementation change.
2. **H2 (rejected by source evidence):** no readable source exists. The decoded `meta` map
   and the existing MyController consumer show that the value is already readable without a
   schema or repository change.
3. **H3 (guard to retain):** evaluating foreign metadata could leak or count foreign
   thresholds. The implementation must evaluate the metadata only after the existing
   own-nation branch; foreign rows continue before that evaluation.

## Tooling baseline

- Aggregated documentation reads were truncated twice. Recovery was to stop batched reads
  and use bounded line-range reads; no repository content was changed by those failed reads.
- The first piped focused Gradle invocation returned before producing XML. A direct rerun was
  started without the pipe; the Gradle process remained active at inspection time, so the
  piped attempt is invalid and is not counted as a passing baseline. The direct run later
  produced `TEST-opensamguk.gameapi.web.CityDetailControllerTest.xml` with 5 tests, 0 failures,
  and 0 errors. Final validation will use completed-process output plus fresh XML only.
- `webapp-testing` helper initialization first used an unavailable `python` executable. Recovery
  used `python3`, whose `--help` output completed successfully; no project file was changed by
  either command.
- A first local endpoint probe used zsh's reserved `status` parameter and stopped before the
  second probe. The corrected probe found neither `127.0.0.1:3001/game/city` nor
  `127.0.0.1:8081/api/city/5` listening (`000` for both), so live/browser observation remains
  explicitly unavailable rather than a claimed pass.
- A source lookup first used the stale `web/MyController.kt` path. `rg --files` recovered the
  current `controller/MyController.kt` location, where the established `metaInt(..., 80)`
  consumer was confirmed. An initial ledger patch also missed the table-row context; it made no
  change, and the corrected patch applied cleanly.
- The controller edit triggered the comment checker. The existing response-field comment was
  updated only to remove its now-false `always 0` claim, and the one new inline comment records
  the non-obvious PHP default/foreign-continue parity contract. The hook did not identify a
  code defect.

## Loop scorecard

| Round | Single hypothesis | Score before -> after | Grader | Decision | Evidence |
|---|---|---|---|---|---|
| 0 | H1: replace only the hardcoded aggregate threshold with the own row's metadata value and PHP default | baseline 5/0/0 -> RED 7/2/0 -> GREEN 7/0/0 | `CityDetailControllerTest` + XML | accepted pending module suite/review | RED XML reported 200 expected/300 actual at the 90 boundary and 300 expected/700 actual for default+999. GREEN XML (`2026-08-11T08:00:45`) reports 7 tests, 0 failures, 0 errors. |

## TDD observations

- **RED:** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --rerun-tasks --tests 'opensamguk.gameapi.web.CityDetailControllerTest' --console=plain` produced fresh XML with 7 tests, 2 failures, 0 errors. The failures were exactly `crewDef` `200 -> 300` and `300 -> 700`, validating H1 without weakening the test.
- **GREEN:** after the controller-only mapping, `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.web.CityDetailControllerTest' --console=plain` produced fresh XML with 7 tests, 0 failures, 0 errors. The command wrapper returned before the Gradle child completed, so completion was established from the exited wrapper process and updated XML, not the early partial console text.

## Validation recovery and current evidence gap

- The required full `:app:game-api:test --rerun-tasks` did **not** produce a completed module
  result. Its original shared-daemon wrapper remained attached overnight with no new XML. Its
  daemon worker was waiting on a Gradle build-completion latch, so only that owned wrapper was
  terminated.
- Two distinct full-suite recoveries were then attempted: a one-use `--no-daemon` run, and an
  in-process Kotlin/one-worker run (`-Pkotlin.compiler.execution.strategy=in-process
  --max-workers=1`). Both remained in Kotlin compilation without emitting any test XML for the
  bounded windows; only their owned wrappers were terminated. This is an environment/build-tool
  validation block, not a passing module-suite claim.
- The smallest follow-up set (`CityDetailControllerTest`,
  `GameApiProcessWorldIdConfigurationTest`, and `WorldScopedReadRepositoryArchitectureTest`) hit
  the same compiler-stage block before it could produce fresh XML. It too was stopped after its
  bounded window. The valid behavioral evidence therefore remains the focused JDK 21 green XML:
  `TEST-opensamguk.gameapi.web.CityDetailControllerTest.xml` timestamp
  `2026-08-11T17:00:57`, 7 tests, 0 failures, 0 errors.
- Independent review returned **APPROVE / WATCH**, no severity findings. Its WATCH status is
  solely the uncompleted module-suite evidence above; task-local review artifact:
  `docs/superpowers/reviews/2026-08-12-opensam-9-city-defence-review.md`.

## Pending non-code observation

- `webapp-testing` was initialized by running its required helper help path with `python3` after
  the unavailable `python` command was isolated above. This task changes no frontend-owned file
  and both local browser/API ports were unavailable; browser rendering is therefore `채점대기`,
  not claimed green. The API MockMvc regression is the acceptance surface.
