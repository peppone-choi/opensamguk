# PEP lifecycle recovery implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute sequential tasks with TDD and independent review before integration.

**Goal:** Unblock the approved PEP promotion/reset through guarded management-state reconciliation and non-cancelling deployment admission, without changing game or account data during these prerequisites.

**Architecture:** Reuse the existing durable Gateway registry transition lease and exact remote-operation query. Add an ADMIN-only metadata reconciliation action for an old, already-satisfied CREATE with a genuinely missing remote operation. Wrap shared source deployment in the control repository's atomic idle-maintenance admission before control mutation.

**Tech Stack:** Kotlin/Spring/JDBC, PostgreSQL, Go deployer HTTP contract, Python/shell workflow boundary tests.

**Spec:** Approved user continuation on 2026-09-05 plus `docs/admin/game-server-recovery.md`. Exact stale PEP operation is documented only in private operator evidence; production APIs remain canonical-ID generic. User approved shared management/deployment admission maintenance and controller replacement, with no cancellation of existing jobs. The parent owns actual PEP cold restore/application drill and production operations.

## Global Constraints

- PEP operation target is pep/spep/opensamguk-spep, scenario_1020, generation 1, turnTerm 5. Other servers and account data are not reset or changed.
- No CREATE replay, direct SQL journal deletion, direct deployer reset bypass, or assumed remote success. An exact missing operation is not a successful operation.
- No raw env, tokens, secrets, backup data or external scenario/IP inputs in Git, logs or reports. The repository is PUBLIC despite stale private-repository prose.
- Preserve existing lifecycle and game write contracts. No schema migration or game mechanics/map changes in this plan.
- Do not cancel another operation, claim someone else's maintenance, automatically repair, or reopen after an unknown/failed rollout.
- Use apply_patch, JDK21 and one Gradle writer. You are not alone; preserve others' work. No subagents, commits, push, merge or production calls by implementers. Parent obtains independent review before committing.

### Task 1: Guarded satisfied-CREATE reconciliation

**Files:**
- Modify `app/gateway-api/src/main/kotlin/opensamguk/gateway/service/ServerRegistry.kt` for strict claim/completion methods.
- Modify `app/gateway-api/src/main/kotlin/opensamguk/gateway/service/DeployService.kt` for coordination using the existing exact remote404 classifier.
- Modify `app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt` for ADMIN route.
- Extend `app/gateway-api/src/test/kotlin/opensamguk/gateway/service/ServerRegistryTest.kt`, `ServerRegistryPostgresIT.kt`, `DeployServiceRegistryPersistenceTest.kt`; add a focused controller/security test under the existing gateway test package if necessary.
- Update `docs/admin/game-server-recovery.md` and the relevant lifecycle admin documentation.

**Interfaces:**
- New `POST /admin/servers/{serverId}/operations/{operationId}/reconcile-satisfied-create`, JSON body `{"confirm":"RECONCILE CREATE <canonical serverId>"}`. Existing `/admin/**` ADMIN gate applies. No new credentials or security bypass.
- `DeployService.reconcileSatisfiedCreate(serverId: String, operationId: String, body: String): EnvProxyResponse`.
- `ServerRegistry.claimSatisfiedCreate(operationId: String, serverId: String, ownerToken: String): ServerRegistryTransition?`.
- `ServerRegistry.completeSatisfiedCreateReconciliation(transition: ServerRegistryTransition)` deletes precisely its guarded transition inside a transaction and never updates game_server/account rows.

- [ ] **Step 1: Baseline.** Run existing `ServerRegistryTest` and `DeployServiceRegistryPersistenceTest` with JDK21. Record BUILD SUCCESSFUL and XML failures/errors/skips. Existing expected failure-fixture logs are not real production incidents.
- [ ] **Step 2: RED.** Tests first: exact old CREATE/dispatched=true/remoteApplied=false/expiredlease/fullmatchingServerDef/exactremote404 removes only that transition; current registry and a second server/transition and account fixture are unchanged. No remote POST occurs. Wrong confirmation/server/op format, unknown transition, age under24hours, non-CREATE, not dispatched, remoteApplied, active lease, changed owner, expired completion lease, any definition field mismatch including null generation/scenario, missing current game_server, remote pending/succeeded/failed/timeout/5xx/malformed404/otheroperation404 must never delete. Include two independent service/registry instances racing and demonstrate exactly one completion, using a real PostgreSQL transaction test with bounded latches; Docker skip is NOT passing evidence.

```kotlin
val result = service.reconcileSatisfiedCreate("pep", operationId,
    """{"confirm":"RECONCILE CREATE pep"}""")
assertEquals(200, result.status)
assertEquals(0, jdbc.queryForObject(
    "SELECT COUNT(*) FROM game_server_registry_transition WHERE operation_id = ?",
    Int::class.java, operationId))
assertEquals(beforeRegistry, registry.all())
// beforeRegistry and the transition fixture are hand-derived independently.
```

- [ ] **Step 3: Minimal JDBC/HTTP implementation.** Reject invalid path/confirmation before lease mutation; require configured deployer. Claim only the exact server+operation CREATE with correct flags, age >=24hours based on database CURRENT_TIMESTAMP, expired prior lease. Use the existing lease duration/owner pattern. A new age query can bind databaseNow minus24hours as Timestamp, avoiding changes to existing transition DTO/fixture schemas when unnecessary. Query remote once via existing strict operation parser; only Missing advances. Release only own lease on conflict/error. Transactional completion locks transition and current game_server, checks same server definition/op/fingerprint/owner/flags and still-valid own lease/age, then deletes exactly one row. Never call register, completeTransition(CREATE), generic cancelTransition, remote POST or repair.

```sql
-- Guard shape, parameters derived inside the transaction using database time:
UPDATE game_server_registry_transition SET owner_token = ?, lease_until = ?
 WHERE operation_id = ? AND server_id = ? AND action = 'CREATE'
   AND dispatched = TRUE AND remote_applied = FALSE
   AND lease_until <= CURRENT_TIMESTAMP AND created_at <= ?;
-- Completion: SELECT transition FOR UPDATE, SELECT game_server FOR UPDATE,
-- compare all ServerDef fields null-safely, then guarded one-row DELETE.
```

  Return200 with `ok:true`, `reconciled:true`, `completed:true`, canonical serverId and operationId; do not label the nonexistent remote operation `succeeded`. Conflict409, missing404 and unavailable503 are explicit non-success responses with no secrets/internal exceptions. Repeated call after successful deletion returns404, not fabricated idempotent success. Document that actual runtime/env/control-registry equality and operation maintenance are operator preconditions, separately from this endpoint's locked DB equality and exactremote404 proof.
- [ ] **Step 4: GREEN/docs.** Run targeted tests then full `:app:gateway-api:test --rerun-tasks` once with Docker available; inspect XML and explicitly confirm the new real PostgreSQL concurrency test did not skip. Add controller boundary tests proving unauthenticated/non-ADMIN rejection before service call and ADMIN access to this exact route. Update admin usage/errors without claiming PEP was reset.
- [ ] **Step 5: Report.** Write RED/GREEN commands/output, changed files, self-review and concerns to the task report. Do not commit; parent reviews the uncommitted diff independently.

### Task 2: Source deployment idle admission

**Files:**
- Modify `.github/workflows/deploy.yml` shared deploy step.
- Add `tools/ops/test_source_deploy_maintenance.py` and wire into `.github/workflows/ci.yml` contracts.
- Update relevant `docs/admin/` deployment/recovery documentation.

**Interfaces:**
- Consumes control `POST /maintenance/enter-if-idle` success maintenance-v1/drained/privatelease. This task must not assume every live controller already supports it.
- Entire existing shared deploy mutation window remains under `/tmp/opensamguk-production.lock`, now also requiring successful fresh idle admission before git sync/socket-proxy/deployer/shared changes.

- [ ] **Step 1: RED boundary tests.** Execute the actual affected shell block with controlled command boundaries. Busy409, unavailable/oldcontroller, invalidJSON, pre-existingclosedmaintenance and transport failure must produce no git sync, env write, container replacement, repair or cancelling entry. Include HTTP/command failure with success-shaped JSON stdout: check transport exit independently before parsing every response, never discard it through nested command substitution. Successful own entry must precede mutations; an injected later failure must not reopen maintenance; full successful health checks may leave own maintenance. Token/lease values never appear in captured logs. Tests assert effects/order, not source-text presence.
- [ ] **Step 2: Minimal admission wrapper.** Before existing git sync, GET and POST via fixed Python urllib/curl inside running deployer, consuming only its own DEPLOYER_TOKEN env. Do not execute old `deployer --authenticated-http`/--help: its initialization can recover live operations before HTTP. Require existing controller, open initial state, successful new idle entry and drained state. If absent/old/busy, fail closed with a clear operator message; no fallback. Track ownership only after confirmed successful response. Use the same safe HTTP adapter after replacement, require it is still drained and readiness/registry checks pass, then leave only that owned window after the existing success gates. On unexpected/failed result, leave closed for operator diagnosis.

```text
flock acquired → safe HTTP idle-entry validated → existing shared sync/update/health
→ verify current controller still drained → leave owned maintenance → success
Any error before/after entry: exit nonzero, no force, cancellation, repair or blind leave.
```

  Remove the broad `docker image prune -af --filter until=168h` from this deployment step: image retention/cleanup is not part of a scoped rollout and may remove rollback artifacts. Do not add replacement cleanup or global scenario rematerialization. Preserve every game IMAGE_TAG/WEB_GAME_TAG and do not start/stop game services.
- [ ] **Step 3: GREEN/docs/report.** Run the new behavior tests, existing deployment inventory tests, YAML parse and git diff --check. Document first-upgrade refusal and closed-on-failure behavior. Parent runs relevant CI and independent review; no production deployment by the implementer.

### Task 3: Preserve an already-correct administrator row on shared restart

Tracking: https://github.com/peppone-choi/opensamguk/issues/635 (opened after confirming no matching existing issue).

**Why this is in scope:** Parent inspected `AdminSeeder.ensureAdmin`: every Gateway startup currently BCrypt-encodes the same configured password, sets `updatedAt`, and saves even when role/grade/password already match. The approved rollout must preserve shared account data. Legitimate configured ADMIN login has already succeeded; no credential change is requested. This is a narrowly scoped idempotence prerequisite, not account management expansion.

**Files:** `app/gateway-api/src/main/kotlin/opensamguk/gateway/config/AdminSeeder.kt`, existing corresponding test(s), and a short note in affected admin operations documentation if needed. Do not alter authentication/authorization, credentials, account schema, other seeders, or source workflow ownership.

- [ ] **RED:** Add tests proving an existing ADMIN/grade6 whose password matches the configured password does not call password encode, repository save, or JDBC writes and preserves its exact hash/updatedAt. A repeat run is also a no-op. Preserve existing tests for missing admin creation, wrong role/grade correction, and different configured password correction. Invalid/empty credential behavior remains unchanged.
- [ ] **Minimal GREEN:** At the existing ensureAdmin boundary, return without modifying the entity only if role is ADMIN, grade is6, and `passwordEncoder.matches(adminPassword, user.password)` is true. Otherwise retain existing correction semantics. Never compare raw passwords, log hashes/passwords, or silently disable seeding.
- [ ] Run focused AdminSeeder tests with JDK21, record XML failures/errors/skips and full command/output. Full Gateway suite is run once on the final integrated tree by parent. Independent task review precedes integration; no production calls or commits by implementer.

```kotlin
if (user.role == "ADMIN" && user.grade == 6 &&
    passwordEncoder.matches(adminPassword, user.password)) {
    return
}
// Existing corrective path follows unchanged.
```
