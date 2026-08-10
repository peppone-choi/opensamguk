# Recruit Restrictions Loop Ledger

## Active contract

- Date: 2026-08-10
- Scope: troop-type restriction availability and rejection reasons from the PHP truth through game-api precheck, engine revalidation, and `web/game` rendering.
- Authority: parent delegation for `/private/tmp/opensam-fix-recruit`; it supersedes the stale active `.ai/task.md` only for this worktree. `.ai/task.md` and `.ai/ownership.md` are deliberately read-only because another active lane owns them.
- Non-goals: inventing alternate unit-set content, weakening goldens/tests, changing daemon execution or lifecycle-row persistence, commit/push/merge/deploy, production access, or legacy writes.
- Required evidence: PHP path+line; API/precheck regression; logic/unit-set regression; frontend regression; JDK 21 XML evidence; `web/game` typecheck/tests; browser observation or `채점대기`; review artifact.

## Baseline and hypothesis

The user-reported symptoms are not treated as proven until this loop records direct source and test evidence.

| Round | Baseline / hypothesis | Grader | Score before → after | Verdict | Notes |
|---|---|---|---|---|---|
| 0 | Reproduce the current restriction path and record PHP/hwe, API, engine, and frontend evidence. | Focused existing/new tests and source-flow inspection | pending → pending | supported | PHP/HWE oracle and Kotlin/Next source paths recorded below; no behavior has changed. |
| 1 | Carry the confirmed typed-availability, selected-argument, and unit-set failures through RED → GREEN without altering daemon terminal behavior. | Focused API, logic, engine, and UI tests | baseline absent → focused green | accepted | Approval required: none. |
| 2 | Independent review found city-const/map, result-state, raw-arg, ownership, blank-set, and duplicate-table gaps. Add RED coverage, then remediate without widening alternate content. | Focused API, logic, engine, and UI tests | fix-required → focused green | awaiting independent re-review | No approval required; no commit/push. |
| 3 | Re-review found a real `QUEUE_MUTATION` UI misclassification and an AI-only static recruit predicate. Add RED contracts and converge both onto phase-aware/shared selection paths. | Focused API, logic, engine, and UI tests | fix-required → focused green → typecheck regression → focused green | cleared | Independent re-review cleared diff `807dfaf93c297b938a8aad76119e4e2092d1c684206f7164d291c819947be436`; no commit/push. |

## Hypothesis set (pre-investigation)

1. The frontend string-matches a denial reason because the server does not expose a typed availability/reason result.
2. The game-api precheck path loses the selected `argJson` before constraint evaluation.
3. Alternate or unknown unit-set keys use `che` checks or otherwise fail open instead of an authoritative set-specific/fail-closed decision.

## Round 2 debugger record

- Artifact journal: the only new artifacts in this round are regression cases in the existing scoped Kotlin/TypeScript test files and the source changes they require; no temporary runtime file, environment override, listener, or debugger attachment is planned. Revert target is the scoped diff against `origin/main` if the focused gates fail.
- H1 (configuration propagation): PRECHECK and FULL never carry one canonical active `mapName`, while `RecruitAlgorithm` resolves city names/regions from static `CityConst`; a `miniche_b` owned city must therefore be evaluated with `CityConstRegistry`, not `che` ids. Distinguishing evidence: a city-1/city-3/region restriction test fails before registry propagation and passes afterward.
- H2 (parser parity): PRECHECK constructs `ConstraintContext` from raw JSON values, whereas FULL invokes `parseArgsForGeneral` and schema validation. Distinguishing evidence: numeric strings, malformed values, invalid amount, and unsupported sets give a different PRECHECK result before normalization and the same result afterward.
- H3 (lifecycle and authority boundary): a reservation-admission event is not daemon application, and contextual availability must not accept an arbitrary general id without an authenticated owner. Distinguishing evidence: API/UI tests can observe an admission-only row without an execution application, and unauthenticated/foreign availability calls are rejected.
- H4 (catalog integrity): absent `unitSet` is a default, but a present blank/whitespace value is an unsupported explicit selection; the recruit table must come from `GameUnitConst`, not a second hand-maintained list. Distinguishing evidence: blank values deny/empty before the change and unit catalog parity follows the common source after it.

## Approval boundary

No approval is needed for local source/tests/docs in this worktree. Commit, push, pull request, merge, deploy, production access, secret access, golden/legacy writes, and test weakening remain prohibited.

## Oracle and implementation boundary

- PHP `hwe/sammo/Command/General/che_징병.php:92-124` evaluates the selected type through `AvailableRecruitCrewType`; `:271-314` exports per-type `notAvailable` for the recruit UI.
- PHP `hwe/sammo/Constraint/AvailableRecruitCrewType.php:41-71` delegates to `GameUnitDetail::isValid` with general, own cities/regions, relative year, tech, and nation aux, with exact deny `현재 선택할 수 없는 병종입니다.`
- PHP `API/Global/GetConst.php:259-272` is context-free static content. It is not the source of a general's current availability.
- HWE `ts/processing/General/che_징병.vue:187-224` filters/types styles using the contextual `notAvailable` field and retains a show-unavailable toggle.
- Kotlin faults: `CommandController.kt:76-123` drops raw selected args before `CommandPrecheckService.kt:44-82`; `SelectRecruitField.tsx:47-55` matches `불가능`; `UnitSetTable.kt` and `GameUnitConst` are che-only while `EffectiveGameConst.kt:44-58` accepts arbitrary active names.
- Bound: only `che` content is ported. An unsupported `unitSet` must expose no che units and deny recruit availability; this change must not fabricate any alternate table.

## Tooling note

During source-only discovery, the orchestration harness issued generic `fablize gate observed a tool failure` notices after overlong aggregate output. Subsequent bounded file reads completed and no product command/test failed. Treat this as an isolated harness baseline; focused validation output and XML will be recorded separately.

## RED → GREEN evidence

- RED contract: `origin/main` had no selected-args overload on `CommandPrecheckService`, no set-aware `UnitSetTable.byId(unitSet, id)`, no typed recruit availability route/client contract, and no `unitSet` in the daemon's command environment. The regression contracts were added before their production wiring.
- GREEN behavior: the typed route evaluates the shared `RecruitAlgorithm.crewTypeAvailability` constraint only; normal valid selected JSON reaches precheck; an unsupported set is empty/denied in `GetConst`, PRECHECK, and FULL; the frontend maps only typed server status/reason and fails closed on missing availability.
- The flattened persisted `config.unitSet` deliberately wins over an older nested `config.map.unitSet`. This is the resolved scenario configuration written by `ScenarioImporter`; it prevents a direct unsupported override from silently falling back to `che`.

## Round 2 remediation evidence

- RED was observed before the Round 2 production edits: `UnitSetTableTest` failed on explicit whitespace; API regressions failed for blank unit set, canonical flattened map name, parser normalization, authenticated contextual availability, admission-only polling, and stale admission versus durable execution; the cross-call-site engine regression failed for malformed args, numeric strings, blank set, and `miniche_b` city restrictions.
- `CityConstRegistry.activeMapName` now supplies the same active map key to `PrecheckStateViewFactory` and `ReservedTurnHandler`. `RecruitAlgorithm` resolves owned cities and regions through the selected registry variant. The cross-call-site test covers `miniche_b` city 1 (`낙양`/1104), city 3 (`건업`/1204), and city 1's `중원` region (1101).
- `CommandPrecheckService.precheck` now invokes `parseArgsForGeneral` and the definition schema test before constraints, matching the FULL handler. `precheckAll` remains catalog-mode evaluation because it has no submitted form values.
- The authenticated recruit availability route resolves only the principal's own general; uncredentialed access is 401 and a mismatched supplied id is 403.
- Explicit blank `unitSet` is retained as an unsupported value; only an absent value defaults to `che`. `UnitSetTable` projects its rows from `GameUnitConst` and no longer owns duplicate arm-type/default constants.
- `reservationAccepted` remains stored as the admission row but the result lookup returns `PENDING` with `phase=reservationAccepted` until `executionApplied` or `executionRejected`. A durable execution result supersedes stale Redis admission. The modal success copy now reports execution, not reservation.

## Round 3 reviewer follow-up

- Re-review evidence: `CommandQueueService` persists nation bulk as `queueMutation` / `QUEUE_MUTATION`, so its synchronous queue update cannot truthfully use execution-success copy. The browser poller also discarded the server's `PENDING phase=reservationAccepted` after its retry window. RED frontend contracts first failed because the former polling seam had no phase-bearing response state.
- Re-review evidence: `AiTurnAdapter` independently enumerated `GameUnitConst.byType` and evaluated city/region constraints through static `CityConst`. Its `miniche_b` city-1 `낙양` candidate diverged from the PRECHECK/FULL selected-map path, and unsupported sets still enumerated `che`. RED AI contracts exposed the old private static selector.
- Implemented pending re-review: `CommandSubmitResult` separates `reserved` from `applied`, retaining a reservation-admission phase after polling; queue mutations render as reservations. The logic-level `RecruitUnitAvailability` predicate is now shared by `RecruitAlgorithm` and the AI candidate/finalize path, which receives the same active map/set environment as candidate FULL validation. Focused evidence and fresh independent re-review are required before changing this review verdict.

## Round 3 focused evidence

- JDK 21 XML: `CommandResultLookupTest` 10/0/0, `AiTurnAdapterMaterializeTest` 17/0/0, `RecruitAlgorithmTest` 19/0/0, and `UnitSetTableTest` 12/0/0. The AI suite includes the new `miniche_b` city-1 candidate and unsupported-unit-set cases.
- `pnpm run typecheck` passed. Focused Vitest passed `commandSubmit`, `CommandModal.terminal-result`, `CommandModal.form-spec`, and `SelectRecruitField`: 4 files / 17 tests.
- `git diff --check` passed. Browser remains `채점대기`: no seeded authenticated stack was started or available.
- The local output bridge intermittently detached Gradle before its terminal line; the later XML was inspected only after the exact process exited. This is the same documented harness capture baseline, not a product test failure.
- Re-review found a valid TypeScript regression: the initial `reserved` result branch made `reason` optional, while existing non-applied result consumers correctly require a displayable string. The central contract now requires reservation copy; fresh typecheck and focused Vitest passed (4 files / 17 tests) before re-review.
- Terminal independent review cleared the exact diff `807dfaf93c297b938a8aad76119e4e2092d1c684206f7164d291c819947be436` with 0 blocker / 0 major / 0 minor. It independently reran API XML (`Precheck` 10, `Available` 7, `Security` 11, `GetConst` 8, `Result` 10), web typecheck + 4/17 Vitest, and verified current engine/logic XML (`AI` 17, `Recruit` 19, `UnitSet` 12, `CrossCall` 15, `Reserved` 30, `CityRegistry` 9), all 0 failures / 0 errors.

## Final focused validation

All Gradle commands used JDK 21 (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`). Result XML was inspected directly because the local output bridge intermittently dropped its live completion signal.

| Surface | Observed result |
|---|---|
| `:logic:test` | `UnitSetTableTest` 10, `RecruitAlgorithmTest` 19, `MilitaryConstraintsTest` 16; all 0 failures / 0 errors (2026-08-10T01:59:52–56). |
| `:app:game-api:test` | `CommandPrecheckServiceTest` 8, `AvailableCommandsControllerTest` 5, `CommandControllerSecurityTest` 11, `GetConstControllerTest` 6; all 0 failures / 0 errors (2026-08-10T02:04:13–43). |
| `:app:game-engine:test` | `ReservedTurnHandlerTest` 30, 0 failures / 0 errors (2026-08-10T02:09:33), including `unsupported active unit set is denied again by the daemon`. |
| `web/game` | `pnpm --dir web/game typecheck` passed; focused `pnpm --dir web/game exec vitest run __tests__/SelectRecruitField.test.tsx` passed 1 file / 1 test. |
| Diff | `git diff --check` passed. |

Round 2 focused rerun (JDK 21 XML, 2026-08-10T03:21): `UnitSetTableTest` 12, `RecruitAlgorithmTest` 19, `MilitaryConstraintsTest` 16, `CityConstRegistryTest` 9, `CommandPrecheckServiceTest` 10, `AvailableCommandsControllerTest` 7, `CommandControllerSecurityTest` 11, `CommandResultLookupTest` 9, `GetConstControllerTest` 8, `PrecheckFullCrossCallSiteTest` 15, and `ReservedTurnHandlerTest` 30 all report 0 failures / 0 errors. `pnpm run typecheck` passed; focused Vitest passed 3 files / 9 tests.

## Runtime / browser

`채점대기`: no seeded authenticated runtime was available for browser observation. Ports 3001 (`web/game`), 8081 (`game-api`), and 8082 (`game-engine`) all refused connections during the Round 2 runtime check. The frontend unit test therefore supplies the UI evidence; browser validation remains for a runnable stack.

## Scoped outcome

- No alternate unit-set data was invented. Only `che` is supported; unknown names expose no `che` catalog data and cannot pass PRECHECK or FULL.
- No commit, push, merge, deployment, production access, legacy write, or golden write occurred.
- The harness notices above are an isolated capture baseline, not a product failure: the final claims rely on explicit XML, command exit markers, and direct port/diff checks.
