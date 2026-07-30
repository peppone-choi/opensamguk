# OpenSamguk V2 Realtime Battle Foundation Plan Review

Scope: `docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md`, `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`, and this review artifact
Verdict: cleared

- Date: 2026-07-30
- Review type: independent architecture and executability review
- Reviewed plan: `docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md`
- Final plan SHA-256: `ff0e79977ff6e0f02fd80401c76ed0f030f0534c0ccb44f1a46908428feb47b0`
- Final verdict: **CLEAR**
- Reviewer: independent `fable-deep-reasoner` agent; no file or external-system writes

## Review History

### Initial review: FIX-REQUIRED

The reviewer required:

1. a resource-only schema boundary with no `:infra` dependency in battle-engine;
2. a hard V2 campaign revision/runtime-state/mutation-gate predecessor;
3. authority, lifecycle, reinforcement, and replay reducers before actor recovery;
4. correct `LiteHashDrbg` cursor restoration and byte-boundary tests;
5. database-enforced DML ownership, not source scanning alone;
6. backend parity, strict Agent OS, and mandatory Testcontainers `skipped=0` completion gates;
7. canonical payload bytes, snapshot/artifact readers, durable rejected receipts, bounded queues, world binding, cross-world foreign keys, snapshot-failure behavior, malformed-handoff release, and JoinTicket key rotation.

All items were incorporated into Tasks 0–13.

### First follow-up: FIX-REQUIRED

Three P1 gaps remained:

- migration and runtime grant lifecycle was not executable;
- durable handoff storage had no production polling driver;
- predecessor evidence and several task-local architecture filters lacked exact owned paths.

Remediation added:

- one-shot `app:v2-schema-provisioner`, `NOLOGIN` object ownership, transient migration identity, role-lifecycle tests, and reproducible local schema-ready sequencing;
- `BattleHandoffIntakeCoordinator` with Redis-free polling, bounded backoff, restart catch-up, duplicate-wake idempotency, and readiness suppression;
- an exact predecessor-evidence artifact and exact dependency, DML ownership, binding, and mandatory-suite filters.

### Second follow-up: FIX-REQUIRED

Two P1 gaps remained:

- provisioning did not yet prove fail-closed ownership and failure cleanup;
- the local smoke expected a session while the production adapter registry was intentionally empty.

Remediation added:

- connection-level object-owner role selection, owner OID assertions, runtime roles held `NOLOGIN` until final verification, cleanup traps, short migrator expiry, injected migration/grant failure tests, and rerun recovery;
- exact `tools/battle/smoke-handoff-rejection.sh` behavior that proves durable unknown-artifact rejection and no session, while successful session creation stays in fixture-backed integration tests.

### Final follow-up

The final remaining P1 was an environment-name mismatch. The plan now distinguishes host `V2_WORLD_ID` from the application contract and requires this Compose mapping:

```yaml
OPENSAMGUK_WORLD_ID: ${V2_WORLD_ID:?V2_WORLD_ID required}
```

The independent reviewer then returned:

```text
CLEAR — remaining fix-required 없음.
SHA-256: ff0e79977ff6e0f02fd80401c76ed0f030f0534c0ccb44f1a46908428feb47b0
```

## Residual Scope

This clearance covers plan quality and executability only. It does not claim implementation, runtime tests, land/siege/naval adapters, the 2.5D renderer, browser performance, or G6 launch acceptance.

The command wrapper repeatedly emitted generic Fablize advisory warnings after successful read-only commands. The underlying commands completed with exit code 0 and valid output; this is recorded as an orchestration-wrapper baseline, not as project verification evidence.
