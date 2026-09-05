# Game-server recovery verification review

## Boundary audit — 2026-09-05

Reviewer: independent `/root/recovery_boundary_review` (read-only). Scope: existing source workflows, control repository lifecycle/maintenance/compose, and engine persistence/boot boundaries. This section is not a review approval of the new helper; that review follows implementation.

### Findings

- Source reset workflow captures DB/Redis while writers remain active and ignores Redis SAVE/copy failures. That backup alone does not prove a consistent or recoverable point.
- Source promotion edits desired tags before image pull and bypasses the deployer's maintenance barrier. Source reset also bypasses the Gateway's durable registry transition. Do not treat the presence of those workflows as a rollout gate.
- A disabled daemon returning health UP does not prove snapshot rehydration: `BootstrapConfig.inMemoryTurnWorld` is lazy, `TurnDaemonRunner` materializes it from the enabled loop, and `TurnDaemonHealthIndicator` treats intentional disablement as UP.
- Changing only a Docker Compose project name does not isolate the stock server compose: container/volume names are explicit and it joins the production external network.
- Pause stops scheduled world/general turns but intentionally continues immediate intake. Stop request producers, observe stable completed tick/recovery readiness and zero nonterminal inbox rows, then stop engine, Redis and PostgreSQL before cold archive.

### Scoped backup boundary

Cold capture of only the selected server's stopped data volumes, private environment, compose and exact images is viable. Rehearsal must use newly named/labeled resources, exact data-service images, no published ports and no production network. For application proof, use a disposable DB copy with clone-only durable `plock=1`, a fresh isolated Redis, seed disabled and daemon enabled; require `serviceMaterialized=true`, expected clock and recovery-ready paused state. A paused `OUT_OF_SERVICE` health response is expected and is not equivalent to a failed restore.

Restoration of the exact storage bytes is distinct from live cutover, registry convergence, command smoke and observed RPO/RTO. Same-VM encrypted storage protects against reset mistakes, not whole-VM/disk loss.

## PEP stale CREATE precondition

Read-only live observations show an expired Gateway CREATE transition for `pep`, dispatched but not marked remotely applied, whose exact deployer operation lookup returns `not_found`. Current selected server env, shared registry and Gateway `game_server` metadata agree on routes/project/scenario/generation. No lifecycle operation was resubmitted and no transition was deleted.

The transition table is keyed by server ID, so this row prevents a new canonical PEP lifecycle transition. Status polling retains a remotely missing transition; retrying CREATE would dispatch CREATE again. There is no current metadata-only reconciliation endpoint. This blocks promotion/reset until separately approved, guarded reconciliation is implemented and verified.

The stale row does not itself mutate game data or run a background reconciler. Therefore the already-authorized quiesced PEP cold backup, scratch restoration and restart of the exact same containers may proceed under a drained control-plane maintenance barrier while retaining the row. Do not recreate production containers, change pins, call reset or delete shared metadata as part of that verification.

Relevant source boundaries:

- `app/gateway-api/src/main/kotlin/opensamguk/gateway/service/DeployService.kt`: missing-operation status handling and lifecycle resubmission.
- `app/gateway-api/src/main/kotlin/opensamguk/gateway/service/ServerRegistry.kt`: per-server durable transition claim/completion.
- `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonRunner.kt`: lazy materialization, pause/intake and shutdown.
- Control repository `deployer/main.go` at `176e9d7`: maintenance barrier, lifecycle journal and desired-state mutation.

## Task 1 implementation review — first pass

Independent reviewer `/root/review_recovery_task`: **FIX-REQUIRED**. Evidence inspected: 30 behavioral tests, one real Docker roundtrip and two existing inventory tests. A focused temporary-fixture reproduction confirmed finding 3; no production actions were performed.

1. P1: manual rollback shell checks did not abort subsequent deletion/extraction after failure. Require one guarded execution sequence and failure-path behavioral proof.
2. P1: separate helper locks did not cover the complete stop/capture/resume window. Source promotion can bypass deployer maintenance. Require a reviewed continuous-lock operator procedure; the helper must remain production-read-only with no CLI bypass.
3. P2: full bundle validation ran before invalidating an old successful verification report. Corrupt/missing payloads could leave the old `success=true` unchanged. Require safe attempt invalidation and success-then-corruption regression coverage.

The reviewer also required an explicit boundary for external scenario inputs, which are excluded from this storage bundle. Their immutability must be separately verified for application recovery claims.

Fixes have been assigned to the original implementer. Task re-review and fresh whole-branch review remain pending. No production recovery or rollout approval is claimed by this document.

### Task 1 scoped re-review — approved

All three findings were addressed. The guarded rollback block is exercised through recording command boundaries, including the local Docker socket. Same-instance/process/thread reentrancy retains the outer lock and rejects foreign owners without corrupting ownership state. A safe pending report now replaces old success before full payload validation. No substantive new breakage was found in the fix areas.

Final evidence: 42 fast tests, one real Docker restoration in 50.501 seconds (including capture/verify under an outer lock), and two existing inventory tests passed. The parent independently reran the fast tests (42 passed in 0.887 seconds), inventory tests and diff check. The live harness remains explicitly blocked; this approval applies only to Task 1 integration. Fresh whole-branch review is pending.

## Fresh whole-branch review — first pass

Reviewer `/root/review_recovery_branch`: **FIX-REQUIRED**, one P1 finding. A malformed nested manifest (`containers.game-postgres.labels` as a list) raised an uncaught `AttributeError` during validation, but the `finally` block still published `success=true` without running either storage check. A focused synthetic reproduction made no Docker calls.

The original implementer is adding an explicit completed-storage flag, nested metadata type guards and malformed/unexpected-exception regressions. No other substantive findings were identified in the complete Task 1 diff. Integration remains blocked until this fix passes verification and scoped re-review.

### Final integration verdict — approved for Task 1

The fresh reviewer verified the final correction: an explicit completed-storage flag is set only after both PostgreSQL and Redis checks finish; success also requires clean resource cleanup. Nested manifest type guards reject the malformed labels before Docker access. Tests cover ten malformed variants and unexpected exceptions before validation and after PostgreSQL verification. No substantive new breakage was found.

Final evidence: 45 behavioral tests, one real Docker roundtrip in 50.363 seconds, and two existing inventory tests passed. The parent independently reran 45 tests in 1.372 seconds, inventory tests and diff check. Both review stages approve storage tooling integration. Actual PEP cold capture, application/authenticated restoration, external scenario identity, control-plane reconciliation and the live operator harness remain blocked or unverified; this verdict does not authorize or claim them.

### Post-merge test-discovery guard — approved

PR #633's later external review identified that unittest import/discovery bypassed the real Docker fixture's `__main__` opt-in check. A class-level guard now explicitly skips that fixture unless `RUN_RECOVERY_DOCKER_TESTS=1`; direct script execution without opt-in still reports NOT RUN and exits nonzero. Three regression tests cover missing/non-1 values, explicit opt-in selection and direct invocation. The operations review date and opt-in documentation were updated.

Scoped independent re-review approved the follow-up with no substantive new breakage: 48 fast tests passed, targeted discovery explicitly skipped without Docker, one opted-in real restoration passed in 51.177 seconds, and two inventory tests passed. The external suggestion to hardcode the generic helper to pep was declined as inconsistent with the approved canonical-ID interface; actual live authority remains PEP-only. All live-operation gates remain unchanged.

## Live operator boundary audit

The same reviewer inspected the engine/control boundaries for a future PEP-only operator harness. Admin pause is process-local; durable `game_env.plock` is restored separately at engine materialization. Require initially unpaused/unlocked state for automatic same-container resumption, and compare the restored storage to the committed pre-stop fingerprint, not to a live world that has resumed ticking.

Control revision `176e9d7` maintenance entry cancels its active operation and waits without a bounded server-side deadline. `GET /maintenance` returning `open` is not an idle-operation inventory. Exact-ID job/status endpoints do not provide an atomic no-active-operation precondition. Invoking that entry could affect a non-PEP operation; it is not covered merely by PEP-only backup authority. The live harness is blocked until a non-cancelling admission path or separately authorized coordination resolves this boundary. No maintenance entry was attempted.
