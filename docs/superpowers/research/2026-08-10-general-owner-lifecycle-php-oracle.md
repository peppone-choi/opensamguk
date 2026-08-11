# General owner lifecycle PHP oracle

## Evidence

`legacy/devsam-core/hwe/sammo/TurnExecutionHelper.php:185-204` distinguishes a future-death possession release from death. On release it restores the NPC state and clears the legacy owner fields; otherwise it applies and kills the general.

`legacy/devsam-core/hwe/sammo/General.php:583-594` finalizes death by archiving and deleting the general, its turn rows, rank rows, and access-log rows. The PHP schema has no Kotlin `general_owner` identity table, so that Kotlin-only durable claim must not outlive either lifecycle terminal state.

## Kotlin divergence

- `ReservedTurnHandler.killOrReleasePossession` restored only metadata ownership and left `TurnGeneral.userId` intact.
- Release and `ChangeRecorder.markGeneralDeleted` removed access-log state but emitted no `general_owner` deletion delta.
- `GeneralResolver.resolveGeneral` returned `null` after a stale unresolved owner row instead of considering a distinct replacement general found by typed `general.user_id`.

## Chosen boundary

Use an insertion-ordered, idempotent owner-delete channel in `ChangeRecorder`, carry it through the converged `DatabaseHooks.toFlushPayload`, and perform `DELETE FROM general_owner WHERE world_id = :world_id AND general_id IN (:ids)` in `JdbcFlushExecutor`. The delete deliberately accepts zero affected rows: it supports release rows that were never claimed and retries without inline JDBC/JPA writes. Typed playable ownership is authoritative regardless of a stale owner row, while a same-id candidate remains unresolved only for a genuinely pending correlated request.

Legacy null-`claimRequestId` rows, missing targets, and released state-3 targets are stale rather than ownership. A request-id row is correlated pending only when its result is still pending and a result-after-status body read remains an unowned state-2 candidate. Applied rows whose body has returned to that candidate pool are released/stale; malformed terminal payloads fail closed without cleanup.

## Admission follow-up evidence

`legacy/devsam-core/hwe/j_get_select_npc_token.php:26-32` refuses an NPC token when `general.owner = userID` already exists. `legacy/devsam-core/hwe/sammo/API/General/Join.php:177-182` applies the same direct-owner existence check before creating a general. `legacy/devsam-core/hwe/j_select_npc.php:93-114` atomically accepts only `owner <= 0 AND npc = 2`; Kotlin's deferred command path must additionally reject a user who acquired a different live general between token admission and daemon execution.

For the Kotlin classifier, a playable direct owner is a row with the caller's typed `general.user_id` and `npc_state < 2`. A correlated request remains pending only while its target is still an unowned `npc_state = 2` candidate. Missing targets, released `npc_state = 3` rows, and legacy owner rows that are neither of those are stale. A stale account-side row may be removed only with all observed reservation fields in its predicate, so a newer/pending claim cannot be deleted by a delayed repair.

The daemon MakeGeneral duplicate-user guard uses that same `npc_state < 2` definition. This admits a
replacement after a historical release that left a stale typed `user_id` on an NPC state2/state3 row, while
still denying an active direct-created or possessed state0/state1 general.

The classifier reads that ownership body as a `NamedParameterJdbcTemplate` scalar snapshot scoped by
`world_id` (`id`, `world_id`, `user_id`, `npc_state`), rather than from a JPA managed entity. This keeps a
daemon commit visible to a second classification in the same API transaction and cannot hydrate an equal
local id from another world. JPA detail reads used for rendering must match the snapshot and fail closed on
a mismatch; they do not decide ownership.

When a different typed live body is already visible, the classifier still evaluates the observed durable
reservation. Terminal, legacy, or no-longer-candidate records are removed only with the exact observed
reservation predicate, so they cannot strand the NPC pool; a fresh pending candidate and an invalid result
remain preserved. The live body remains the returned ownership result regardless of whether that conditional
cleanup wins its race.

## Upgrade repair

PHP release restores the NPC state and clears its legacy owner fields before a subsequent possession attempt
(`TurnExecutionHelper.php:185-204`); Kotlin `general.user_id` is the corresponding typed representation. PHP selection accepts only an unowned state-2 NPC
(`j_select_npc.php:93-114`). Existing Kotlin worlds can therefore contain pre-fix state-2/state-3 rows with a
stale `general.user_id`; they must be normalized before a possession-only server can advertise them without
the daemon rejecting their stale owner.

`V39__general_owner_lifecycle_normalization.sql` intentionally follows, rather than changes, immutable V38.
It clears `general.user_id` only for non-playable NPC states and removes `general_owner` only for a missing
body, a legacy null request id, or the latest world-scoped command result whose JSON envelope decodes as the
matching terminal `claimNpc` result. The SQL requires the envelope request id, `sentAt`, command-result event,
claim type, general id, boolean/status agreement, and decoder-valid optional committed-world-version/reason
fields. A pending result or any malformed/mismatched envelope remains fail-closed and durable; a state-0/state-1
live body keeps its link even when its successful claim result is terminal.
