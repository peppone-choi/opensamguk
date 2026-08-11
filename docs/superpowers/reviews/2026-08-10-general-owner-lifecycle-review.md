# General owner lifecycle review

Scope: `app/` game-api/game-engine release/death durable owner cleanup and possession/direct-creation admission, plus `infra/` JDBC flush; no deployment or legacy source mutation.

Status: independently re-reviewed and cleared. The current Docker-backed API remeasurement remains
environment-blocked at Testcontainers initialization; the historical full API result is retained only as
historical evidence.

Verdict: cleared

Remediated findings independently verified:

- `GeneralOwnershipClassifier` now classifies playable typed ownership, correlated pending claims, stale rows, and no ownership. Resolver, claimable, claim, and join all use it.
- Direct-created `general.user_id` ownership blocks token issuance and claim admission; `ClaimNpcHandler` repeats the live typed-owner check immediately before the NPC mutation.
- `MakeGeneralHandler` uses that same live typed-owner threshold (`npc_state < 2`), so legacy released NPC rows retaining a stale `user_id` at states 2/3 do not contradict API recreation admission.
- Stale owner rows are removed only by an exact compare-and-delete of observed world/user/general/timestamp/request fields, preserving a newer or pending reservation.
- A distinct live typed body no longer bypasses that reconciliation: terminal/legacy/noncandidate reservations are conditionally removed before the classifier still returns `LiveOwned`; a fresh pending candidate and an invalid result remain non-destructive.
- The direct-owner read is explicitly world-scoped, ordered, and playable-only (`npc_state < 2`); the classifier defensively verifies the same predicate.
- Classifier ownership state is a process-world JDBC scalar snapshot, so an outer JPA persistence context cannot reuse a stale candidate or an equal local id from another world.
- V39 is a new forward-only upgrade repair; it does not alter deployed V38. It nulls stale typed ownership for all non-playable NPC states (`npc_state >= 2`) and removes only orphan, legacy-null, or latest-result JSON-correlated terminal reservations. It preserves live Applied links, genuine pending claims, malformed/mismatched envelopes, and a same-id/request pending row in another world.

Review criteria:

- Release clears both metadata ownership and typed `TurnGeneral.userId`.
- Release and death record one owner-delete intent through `ChangeRecorder`, without daemon inline database or JPA writes.
- Flush deletion is world-scoped, accepts zero affected rows, and does not cross-delete an equal local id in another world.
- A stale released owner does not block a distinct recreated live general, while a same-id pending claim remains unresolved.
- Focused JDK 21 tests, fresh XML, `git diff --check`, and source ownership checks are captured before handoff.

Observed evidence:

- `KillTombstoneTest`: 11 tests, 0 failures, 0 errors.
- `FlushPayloadConvergenceTest`: 10 tests, 0 failures, 0 errors.
- `ClaimNpcHandlerTest`: 3 tests, 0 failures, 0 errors; includes MakeGeneral then ClaimNpc ordering.
- Historical consolidated game-api selection: 55 tests, 0 failures, 0 errors, 0 skips: `GameApiApplicationTests` (1), `GeneralResolverTest` (9), `PossessionControllerTest` (28), `JoinControllerTest` (10), `WorldScopedReadRepositoryIT` (2), and `SelectNpcTokenRepositoryIT` (5). The three integration classes then ran through PostgreSQL Testcontainers without skips. The new PostgreSQL regression preloads an outer-transaction candidate, commits activation on another transaction, and proves the process-world snapshot wins over an equal local id in another world.
- The terminal-result reader classifies only an absent result plus a freshly-read unowned state-2 body as correlated pending. Applied/released candidates are stale and repairable; malformed payloads are stale but fail closed without cleanup. The same-key owner cleanup now uses `@Modifying(flushAutomatically = true, clearAutomatically = true)` and has a PostgreSQL regression test.
- Fresh targeted `PossessionControllerTest` XML is 28/0/0. Its terminal rejection regression asserts that a live id 11 still reports ownership, exact-cleans rejected reservation id 10, and exposes id 10 to user 8; its pending sibling asserts zero cleanup.
- RED→GREEN `MakeGeneralHandlerTest`: 6 tests first had one failure because stale typed state2 was denied; fresh targeted XML is 6/0/0 and covers state2/state3 allowance plus state0/state1 denial.
- Fresh JDK 21 `--rerun-tasks` infra XML is `GeneralOwnerDeleteFlushIT` 1/0/0/0 and `JdbcFlushExecutorWorldScopeTest` 3/0/0/0. The prior Docker Desktop create failure was not reproduced.
- RED→GREEN V39 migration IT: after an empty schema is migrated through V38, the pre-V39 state leaves released `user_id=7` (`expected null, was 7`). After V39, `GeneralOwnerLifecycleNormalizationMigrationIT` is 1/0/0 through PostgreSQL Testcontainers. Its second focused RED proved that an envelope with invalid optional fields was incorrectly deleted (`expected owner count 1, was 0`); the final SQL now preserves it. The same test covers latest-result selection, two-world scope, live Applied retention, pending/Invalid preservation, orphan cleanup, and second-run Flyway idempotence.
- Current API remeasurement is partly blocked by Docker Desktop rather than a source assertion: resolver 9/0/0, possession 28/0/0, and join 10/0/0 are fresh green; `GameApiApplicationTests`, `SelectNpcTokenRepositoryIT`, and `WorldScopedReadRepositoryIT` each failed only while Testcontainers received Docker HTTP 500 creating PostgreSQL. The visible `Created` Testcontainers containers predate this task, so no removal/restart was performed. The historical 55/0 API result is not represented as a current full gate.
- Independent final source audit found no remaining product defect and cleared the review with the preceding current-API verification caveat.
- `git diff --check` passed.
