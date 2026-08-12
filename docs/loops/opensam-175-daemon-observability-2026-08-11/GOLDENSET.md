# OPENSAM-175 daemon observability contract

## Authority and scope

This task is authorized by the root orchestrator under the user's maximum-parallel
execution approval. The shared `.ai/task.md` still names OPENSAM-43, so it is not
edited for this isolated OPENSAM-175 worktree. This file is the task-specific
contract and does not change shared ownership records.

Owned surfaces are daemon health/status, its focused tests, a dedicated alert
workflow and script, the recovery-gated lines in `deploy.yml`, and task evidence.
The OPENSAM-149 persistence spine and OPENSAM-9/84/79 surfaces are out of scope.

## Frozen acceptance contract

1. A daemon whose recovery gate is not ready is Actuator `DOWN` with the bounded
   daemon state `recovery_gated`.
2. An intentionally paused daemon is Actuator `OUT_OF_SERVICE`, not silent `UP`.
3. A stuck daemon is `DOWN` when the age of its last successful wall-clock tick
   exceeds `3 * tickSeconds`; the threshold is never a fixed number of seconds.
4. Persisted `clock.lastTurnTime` remains diagnostic context only. A catch-up
   daemon with an old game clock but fresh successful ticks is not alerted solely
   for that lag.
5. A dedicated scheduled/manual alert workflow invokes an independently testable
   alert script outside the deploy workflow. Alert labels are bounded and neither
   raw recovery reasons nor webhook values are emitted.
6. `deploy.yml` must fail closed when the recovery gate is not ready instead of
   reporting a successful skipped turn-advance verification.

## Ticket wording correction for Jira follow-up

The ticket's phrase "lastTurnTime lag threshold" is unsafe if interpreted as the
persisted game clock alone: recovery catch-up can intentionally advance the game
clock over many successful real-time ticks. The operational signal is therefore
wall-clock age since the last successful tick, with `lastTurnTime` retained only
as diagnostic context. Jira should be corrected to state that distinction.

## Non-goals

- No production webhook installation, production alert dispatch, deployment, or
  merge. A reviewed local commit/push and ready PR are separately authorized by
  the root orchestrator after all task gates pass.
- No persistence-spine change and no unbounded labels or secret-bearing fields.
