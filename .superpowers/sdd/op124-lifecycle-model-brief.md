# OPENSAM-124 approved lifecycle brief

Goal: encode the user-approved GA-079 child lifecycle now without violating the W0→W3 dependency order.

State machine:

- `PENDING -> RING_COMMITTED -> APPLIED | NOOP | FAILED_AFTER_RING`
- `PENDING -> REJECTED_BEFORE_RING`
- Each transition requires expected `stageVersion`; stale transitions fail.
- Stage A represents a committed ring while the actor general still has old killturn.
- Stage B daemon decision: `npc >= 2` or current killturn >= frozen floor => `NOOP`; otherwise update to `max(current, floor)` through in-memory general + `ChangeRecorder`, then terminal `APPLIED` only with the effect commit.
- A stage-B failure preserves `RING_COMMITTED`/old killturn and retries only stage B. No later child may advance first.

Scope for this wave:

- Add the daemon-owned lifecycle model/coordinator seam and focused unit tests in a new `app/game-engine/.../nationbulk/` package.
- Exercise ChangeRecorder-based effect creation; no direct repository/JPA/JdbcTemplate write.
- Do not add durable schema, API activation, Redis workflow, or temporary singleton `world_id`. Those require OPENSAM-43 and W3.
- Update OPENSAM-124 and the consistency contract from human-approval-blocked to lifecycle-selected/review-pending, while keeping activation dependency explicit.
- Freeze PHP evidence SHA `a8918979ab2d532d85a4b4604c55944d76d5a70b4ee9bb726ba8161e3ff22418`; do not edit the golden artifact.

Focused tests must cover below-floor 93→100, above-floor NOOP, npc>=2 NOOP, crash/failure after ring, stale stageVersion, invalid transition, rejected-before-ring, and later-child ordering guard.

Stop condition: focused engine tests and relevant architecture tests pass with no production activation. Do not commit/push.
