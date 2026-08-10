# Review: Recruit troop-type restrictions

Scope: `app/`, `logic/`, and `web/` recruit-restriction changes only: typed availability, selected-argument precheck propagation, active-unit-set fail-closed behavior, daemon full-mode revalidation, and the `web/game` consumer. It excludes alternate unit-set content, commits, pushes, deployments, production access, and legacy writes.

Stage: independent re-review cleared

Verdict: cleared

## Parity boundary

- PHP `hwe/sammo/Command/General/che_징병.php:92-124` attaches `AvailableRecruitCrewType` for the chosen type; `:271-314` exports contextual `notAvailable`.
- PHP `hwe/sammo/Constraint/AvailableRecruitCrewType.php:41-71` delegates to the unit predicate over general, owned cities/regions, relative year, tech, and nation aux, returning `현재 선택할 수 없는 병종입니다.` on denial.
- PHP `API/Global/GetConst.php:259-272` is static data only. HWE `ts/processing/General/che_징병.vue:187-224` consumes command-specific availability rather than matching display strings.

## Review questions

1. Does the typed API reuse the shared availability constraint without accidentally applying unrelated recruit costs/population to each item?
2. Does POST precheck receive valid selected JSON while the forecast reservation and terminal daemon-result contract remain unchanged?
3. Do unsupported active unit sets expose neither che static content nor a che acceptance path in PRECHECK/FULL?
4. Does the frontend fail closed when typed availability is unavailable and avoid Korean-string inference entirely?

## Independent review findings being remediated

1. PRECHECK/FULL still use static `CityConst`; thread canonical active `mapName` and resolve city/region data through `CityConstRegistry`.
2. Reservation admission and daemon execution are conflated in the result semantics; preserve the admission row and require explicit execution application before rendering applied state.
3. PRECHECK must normalize raw args with the same parser path as FULL, including numeric strings and invalid values.
4. The contextual availability endpoint must require an authenticated principal and its owned general.
5. Missing `unitSet` may default to `che`, but explicit blank/whitespace values must fail closed.
6. `UnitSetTable` must derive `che` entries from `GameUnitConst` rather than duplicate the static unit content.

## Evidence status

- RED contracts were authored before production wiring. Against `origin/main`, their selected-args precheck overload, set-aware unit lookup, typed availability client/route, and daemon `unitSet` environment are absent.
- JDK 21 logic XML: `UnitSetTableTest` 10, `RecruitAlgorithmTest` 19, and `MilitaryConstraintsTest` 16; all 0 failures / 0 errors.
- JDK 21 API XML: `CommandPrecheckServiceTest` 8, `AvailableCommandsControllerTest` 5, `CommandControllerSecurityTest` 11, and `GetConstControllerTest` 6; all 0 failures / 0 errors.
- JDK 21 engine XML: `ReservedTurnHandlerTest` 30, 0 failures / 0 errors, including the nested stale `map.unitSet=che` plus flattened `unitSet=not-ported` FULL-mode denial.
- `pnpm --dir web/game typecheck` passed. Focused `SelectRecruitField` Vitest passed 1 file / 1 test; it proves the static Korean display text is not used to infer availability.
- `git diff --check` passed. Browser evidence is `채점대기`: no listener at 3001, 8081, or 8082, so an authenticated seeded UI could not be observed.
- The local orchestration harness repeatedly emitted generic `fablize gate observed a tool failure` notices during output capture. This is isolated as a harness baseline; review must rely on the XML/port/diff evidence above rather than those notices.

## Remediation submitted for re-review

1. Active map configuration now travels through `CityConstRegistry.activeMapName` in both PRECHECK and FULL. `RecruitAlgorithm` consumes a selected `CityConstVariant`, including its region-name mapping. The cross-call-site suite proves `miniche_b` city 1/1104, city 3/1204, and city 1/1101 region acceptance in both paths.
2. The command-result read endpoint treats a `RESERVED_TURN` lifecycle record as pending until `executionApplied` or `executionRejected`; its admission row remains durable. It also prefers durable execution over a stale Redis admission. Web submission code and modal tests require this terminal execution phase before success UI.
3. API precheck invokes the exact command parser and schema validation used by FULL. The cross-call-site test exercises a numeric amount string, invalid crew-type string, negative amount, blank unit set, and selected valid cases.
4. `GET /api/commands/recruit/availability` requires a principal and uses only its resolved general. Tests prove 401 without a principal and 403 for a foreign query id.
5. `UnitSetTable` defaults only absent values to `che`; explicit empty/whitespace values are unsupported. Its `che` row view is derived from `GameUnitConst`, with duplicate constants removed from consumers.

Focused rerun evidence (JDK 21 XML, 2026-08-10T03:21): logic `UnitSetTableTest` 12, `RecruitAlgorithmTest` 19, `MilitaryConstraintsTest` 16, `CityConstRegistryTest` 9; API `CommandPrecheckServiceTest` 10, `AvailableCommandsControllerTest` 7, `CommandControllerSecurityTest` 11, `CommandResultLookupTest` 9, `GetConstControllerTest` 8; engine `PrecheckFullCrossCallSiteTest` 15 and `ReservedTurnHandlerTest` 30. Every listed suite has 0 failures / 0 errors. `web/game` typecheck passed and focused Vitest passed 3 files / 9 tests. `git diff --check` passed.

Browser evidence remains `채점대기`: ports 3001, 8081, and 8082 were not listening, so no authenticated seeded stack was available for live observation.

Re-review request: validate the six former findings against the scoped diff, especially the distinction between admission persistence and terminal UI state, and leave the verdict `fix-required` unless all six are disproven by source and focused evidence.

## Second independent review findings being remediated

1. Nation bulk is a synchronous `QUEUE_MUTATION` (`queueMutation`), not a reserved-turn execution. Its real row must render as reserved, while `executionApplied` remains the only reserved-turn success state. A reservation-admission `PENDING` phase must also survive polling instead of degrading into generic delay.
2. `AiTurnAdapter` still had an independent static `CityConst`/`GameUnitConst` recruit-candidate path. It must use the same active map, active set, and shared unit predicate as PRECHECK/FULL; a `miniche_b` city-1 `낙양` high-tier candidate and an unsupported set are the regressions.

## Second remediation evidence

- `CommandResultLookupTest` now verifies a real `QUEUE_MUTATION` row remains `RESOLVED` with its true type/command kind. The frontend preserves a final `PENDING phase=reservationAccepted` response, classifies both reservation admission and queue mutation as `reserved`, and only presents `executionApplied` as executed. The Modal contract uses a real nation-bulk `queueMutation` fixture.
- `RecruitUnitAvailability` is the one logic predicate consumed by `RecruitAlgorithm` and the AI candidate/finalize paths. `AiTurnAdapter` also adds the canonical active map/set values to both candidate FULL environments. Its materialization suite now proves `miniche_b` city 1 admits the `낙양` high-tier footman candidate and an unsupported set produces no candidates.
- Focused evidence: JDK 21 XML `CommandResultLookupTest` 10/0/0 and `AiTurnAdapterMaterializeTest` 17/0/0; logic `RecruitAlgorithmTest` 19/0/0 and `UnitSetTableTest` 12/0/0. `pnpm run typecheck` passed and focused Vitest passed 4 files / 17 tests. `git diff --check` passed.

Re-review request: verify that queue admission cannot reach execution copy, `reservationAccepted` survives the polling window as a reserved state, and every AI recruit candidate/finalize branch now fails closed or uses the same selected-map predicate as human PRECHECK/FULL. Keep verdict `fix-required` unless the evidence and scoped source close both findings.

## Re-review type-safety correction

The first second-remediation handoff widened `reserved.reason` to optional, which broke existing callers that correctly relied on every non-applied/non-rejected result carrying a displayable reason. The fix makes `reserved.reason` required and returns the explicit admission copy `명령이 예약되었습니다.` for both phase-only reservation admission and real queue mutation. Fresh `pnpm run typecheck` and focused Vitest (4 files / 17 tests) pass.

## Terminal independent re-review

Independent verdict: cleared on diff hash `807dfaf93c297b938a8aad76119e4e2092d1c684206f7164d291c819947be436` with 0 blocker, 0 major, and 0 minor findings. The reviewer confirmed every original and Round-3 finding closed. Fresh reviewer evidence: JDK 21 API XML `CommandPrecheckServiceTest` 10, `AvailableCommandsControllerTest` 7, `CommandControllerSecurityTest` 11, `GetConstControllerTest` 8, and `CommandResultLookupTest` 10, all 0 failures / 0 errors; web typecheck plus focused Vitest 4 files / 17 tests passed. Current engine/logic XML also shows `AiTurnAdapterMaterializeTest` 17, `RecruitAlgorithmTest` 19, `UnitSetTableTest` 12, `PrecheckFullCrossCallSiteTest` 15, `ReservedTurnHandlerTest` 30, and `CityConstRegistryTest` 9, all 0 failures / 0 errors.

## Release boundary

No commit, push, merge, deploy, production access, or legacy/golden write was performed. An independent reviewer must set the verdict only after checking the scoped diff and the evidence above. Browser validation remains `채점대기` until a seeded authenticated stack is runnable.
