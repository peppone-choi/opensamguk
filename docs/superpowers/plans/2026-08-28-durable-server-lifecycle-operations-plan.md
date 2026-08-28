# Durable Server Lifecycle Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make server create, close, and reset durable, idempotent, restart-safe operations whose admin UI reports success only after terminal verification.

**Architecture:** The browser supplies one operation id, gateway-api records the server transition in PostgreSQL, and the deployer records execution state in an atomic file store linked to its lifecycle journal. The UI polls an authenticated gateway status endpoint, which reads the deployer operation and reconciles the gateway registry exactly once.

**Tech Stack:** Go 1.23 HTTP service and file persistence, Kotlin 2.1/Spring Boot/JdbcTemplate/Flyway, Next.js 15/React 19/TypeScript/Vitest, GitHub Actions YAML.

**Spec:** `docs/superpowers/specs/2026-08-28-durable-server-lifecycle-operations-design.md`

## Global Constraints

- Work only in `bin/start-task` worktrees for `opensamguk` and `opensamguk-docker`.
- Follow strict red-green TDD for every production behavior change.
- `operationId` is exactly 32 lowercase hexadecimal characters.
- The deployer operation store uses mode `0600`, holds at most `512` records, retains terminal records for `24h`, and never prunes non-terminal records.
- Persist no request body, Docker output, environment value, password, JWT material, maintenance lease, or stack trace in the deployer operation store.
- Persist `succeeded` before clearing the lifecycle journal; repair uses the same order.
- `recovery_required` is non-terminal and keeps mutation admission fail-closed.
- UI success and registry refresh happen only after `succeeded`.
- Preserve the existing Docker preflight, lifecycle journal, runtime verification, shared reload, and repair-required behavior.
- Every logical code task ends in one commit with the required co-author trailer.
- `[opensamguk-docker]` denotes the root of the `worktrees/opensamguk-docker/server-lifecycle-durable-operations` sibling worktree.

---

### Task 1: Durable deployer operation store

**Files:**
- Create: `[opensamguk-docker]/deployer/operation_store.go`
- Create: `[opensamguk-docker]/deployer/operation_store_test.go`
- Modify: `[opensamguk-docker]/deployer/main.go`

**Interfaces:**
- Produces: `durableOperationStore`, `durableOperationRecord`, `openDurableOperationStore(path string, maxEntries int, retention time.Duration)`, `Reserve`, `Transition`, `Lookup`, and `Recover`.
- Produces operation states `pending`, `running`, `recovery_required`, `succeeded`, `failed`, and `cancelled` for later HTTP and journal tasks.

- [ ] **Step 1: Write failing persistence and restart tests**

Add literal behavior tests:

```go
func TestDurableOperationStoreSurvivesRestart(t *testing.T) {
    path := filepath.Join(t.TempDir(), ".deployer-operations.json")
    first := mustOpenOperationStore(t, path)
    id := "0123456789abcdef0123456789abcdef"
    mustReserveOperation(t, first, durableOperationRecord{
        OperationID: id, Kind: lifecycleKindReset, SubjectID: "pep",
        RequestFingerprint: strings.Repeat("a", 64), Status: lifecycleJobPending,
    })
    mustTransitionOperation(t, first, id, lifecycleJobSucceeded, http.StatusOK, "서버 리셋이 완료되었습니다.")

    restarted := mustOpenOperationStore(t, path)
    got, ok := restarted.Lookup(id)
    if !ok || got.Status != lifecycleJobSucceeded || got.SubjectID != "pep" {
        t.Fatalf("restarted lookup = %#v, %v", got, ok)
    }
}

func TestDurableOperationStoreRejectsMalformedFile(t *testing.T) {
    path := filepath.Join(t.TempDir(), ".deployer-operations.json")
    os.WriteFile(path, []byte(`{"version":1,"operations":[{"operationId":"bad"}]}`), 0o600)
    if _, err := openDurableOperationStore(path, 512, 24*time.Hour); err == nil {
        t.Fatal("malformed durable operation store opened")
    }
}
```

Name the protected mutations before running: removing the disk load or accepting malformed ids must fail these tests.

- [ ] **Step 2: Run tests and verify RED**

Run: `cd deployer && go test ./... -run 'TestDurableOperationStore'`

Expected: compile failure because the durable store API does not exist.

- [ ] **Step 3: Implement the minimal atomic store**

Implement a versioned JSON document, mutex-protected state, strict record validation, terminal pruning, non-terminal retention, and temp-file/fsync/rename/directory-fsync persistence. Keep public messages bounded to 300 characters and reject control characters.

Wire `loadConfig()` to open `${SERVERS_DIR}/.deployer-operations.json` or `DEPLOYER_OPERATION_STORE_FILE`; return startup/readiness failure instead of silently creating an empty cache when an existing file is malformed.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `cd deployer && go test ./... -run 'TestDurableOperationStore'`

Expected: PASS.

- [ ] **Step 5: Add RED tests for capacity, retention, and secret-safe messages**

Test that 512 non-terminal entries reject a 513th, expired terminal records prune, non-terminal records do not prune, and a message containing newline or more than 300 characters is rejected rather than persisted.

- [ ] **Step 6: Run RED, implement bounds, and rerun GREEN**

Run the focused operation-store test set before and after the minimal implementation.

- [ ] **Step 7: Commit Task 1**

Commit sibling repo changes with message `feat(deployer): persist lifecycle operation state` and the required co-author trailer.

---

### Task 2: Link create, close, and reset execution to durable operations

**Files:**
- Modify: `[opensamguk-docker]/deployer/main.go`
- Modify: `[opensamguk-docker]/deployer/main_test.go`
- Modify: `[opensamguk-docker]/README.md`

**Interfaces:**
- Consumes: Task 1 durable operation store.
- Produces: reset idempotency, durable `/operations/{id}`, journal fields `operationId` and `operationKind`, and restart/repair state reconciliation.

- [ ] **Step 1: Write failing reset idempotency test**

Create two reset requests with the same operation id and identical normalized body. Wait for the first terminal result, issue the second request, and assert the Docker runner observed exactly one `down --volumes` command and both responses identify the same operation.

Also write a conflict test that changes `scenarioCode` while reusing the id and expects HTTP 409 before any second Docker command.

- [ ] **Step 2: Run reset tests and verify RED**

Run: `cd deployer && go test ./... -run 'TestResetOperationID'`

Expected: the current reset response has no operation identity and the second request executes again.

- [ ] **Step 3: Implement unified operation reservation**

Add `lifecycleKindReset`, a reset request fingerprint derived from normalized non-secret reset fields, and require create/close/reset operation-backed jobs to reserve durably before starting. Existing no-id callers remain rollout-compatible but emit a legacy warning and use an ephemeral id.

Make `GET /operations/{id}` read the durable record, returning `kind`, `subjectId`, `status`, `httpStatus`, and `publicMessage`; preserve the exact existing three-key 404 response.

- [ ] **Step 4: Verify reset idempotency GREEN**

Run the same focused tests and confirm one Docker mutation.

- [ ] **Step 5: Write failing restart and repair tests**

Add `TestRestartLinksJournaledOperationToRepair`, which creates a running reset record and linked journal, reconstructs config, asserts `recovery_required`, runs repair, and asserts persisted `succeeded`. Add `TestRestartCancelsUnjournaledPendingOperation`, which reconstructs from a pending record without a journal and asserts persisted `cancelled` plus zero Docker calls. Add `TestOperationSuccessIsDurableBeforeJournalClear`, which blocks journal clearing with a hook, reads the operation file while blocked, and asserts it already contains `succeeded`.

- [ ] **Step 6: Run restart tests and verify RED**

Run: `cd deployer && go test ./... -run 'TestRestart(Link|Cancel)|TestOperationSuccess'`

Expected: current in-memory jobs disappear and the journal lacks operation identity.

- [ ] **Step 7: Implement journal linkage and recovery ordering**

Extend `lifecycleJournal` with optional operation identity/kind. New lifecycle paths write both fields. On worker error, inspect journal presence: store `recovery_required` when it remains, otherwise terminal `failed`. Persist `succeeded` before clearing the journal. On startup and `repairLifecycleJournal`, transition the linked operation according to the design.

- [ ] **Step 8: Verify focused and full Go suites**

Run:

```bash
cd deployer
go test ./... -run 'TestRestart(Link|Cancel)|TestOperationSuccess|TestResetOperationID'
go test ./...
go vet ./...
```

Expected: all pass.

- [ ] **Step 9: Document durable operation behavior and commit**

Update sibling `README.md` with path, retention, states, polling, and repair semantics. Commit with `feat(deployer): unify durable server lifecycle operations` plus trailer.

---

### Task 3: Unify gateway transitions and add reconciliation status API

**Files:**
- Create: `infra/src/main/resources/db/migration/V45__server_registry_reset_transition.sql`
- Create: `infra/src/test/kotlin/opensamguk/infra/persistence/V45ServerRegistryResetTransitionMigrationTest.kt`
- Modify: `app/gateway-api/src/main/kotlin/opensamguk/gateway/service/ServerRegistry.kt`
- Modify: `app/gateway-api/src/main/kotlin/opensamguk/gateway/service/DeployService.kt`
- Modify: `app/gateway-api/src/main/kotlin/opensamguk/gateway/controller/AdminController.kt`
- Modify: `app/gateway-api/src/test/kotlin/opensamguk/gateway/service/DeployServiceRegistryPersistenceTest.kt`
- Modify: `app/gateway-api/src/test/kotlin/opensamguk/gateway/service/AdminVersionDeployTest.kt`

**Interfaces:**
- Consumes: deployer operation states and exact not-found contract from Task 2.
- Produces: `ServerRegistryTransitionAction.RESET`, caller-supplied operation ids, and `GET /admin/servers/operations/{operationId}`.

- [ ] **Step 1: Write the failing V45 migration test**

Create V43/V44 state, insert one existing CREATE transition, apply V45, assert the row remains, and insert a RESET transition successfully. Use literal SQL and row values.

- [ ] **Step 2: Run migration test and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests '*V45ServerRegistryResetTransitionMigrationTest'`

Expected: missing migration/RESET constraint failure.

- [ ] **Step 3: Add the minimal migration**

Drop the known V44 action check constraint and recreate it as `CHECK (action IN ('CREATE','CLOSE','RESET'))`, preserving rows and the unique operation id.

- [ ] **Step 4: Verify migration GREEN**

Run the focused migration test and inspect its XML for zero skipped/failures.

- [ ] **Step 5: Write failing gateway transition tests**

Add tests proving:

- a caller-supplied operation id is forwarded unchanged;
- reset pending creates a RESET transition and returns its id;
- polling pending/recovery does not change membership;
- polling CREATE success inserts once;
- polling CLOSE success deletes once;
- polling RESET success updates generation/scenario without deleting membership;
- a new service instance with the same database reconciles the same operation without another mutation POST;
- exact `not_found` returns `resubmitRequired=true` while malformed 404 does not.

- [ ] **Step 6: Run gateway tests and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --tests '*DeployServiceRegistryPersistenceTest' --tests '*AdminVersionDeployTest'`

Expected: RESET action/status endpoint and supplied-id API are missing.

- [ ] **Step 7: Implement unified transition and polling reconciliation**

Extend `ServerRegistryTransitionAction`, allow `beginTransition` to receive a validated operation id, add lookup/lease claim by operation id, and make RESET completion update the existing server row. Normalize all deployer states, include operation metadata in every 202 response, and add the ADMIN controller GET route.

Do not redispatch from the GET status endpoint. Return `resubmitRequired=true` only for exact deployer not-found while the transition exists.

- [ ] **Step 8: Verify gateway GREEN and broader module tests**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :infra:test :app:gateway-api:test --rerun-tasks
```

Confirm `BUILD SUCCESSFUL` and XML counts, not exit code alone.

- [ ] **Step 9: Commit Task 3**

Commit with `feat(gateway): reconcile durable server lifecycle operations` plus trailer.

---

### Task 4: Poll terminal lifecycle state in the admin UI

**Files:**
- Create: `web/gateway/lib/admin-server-lifecycle.ts`
- Create: `web/gateway/__tests__/admin-server-lifecycle.test.ts`
- Modify: `web/gateway/app/admin/page.tsx`
- Modify: `web/gateway/__tests__/admin-server-id.test.tsx`

**Interfaces:**
- Consumes: gateway mutation responses and operation status endpoint from Task 3.
- Produces: `runServerLifecycleOperation`, typed lifecycle responses, bounded resubmit, polling, abort, and UI terminal-state rendering.

- [ ] **Step 1: Install the existing locked frontend dependencies**

Run: `cd web/gateway && pnpm install --frozen-lockfile`

Do not change the lockfile.

- [ ] **Step 2: Write failing lifecycle client tests**

Use fake timers and a small fetch fake returning complete response objects. Test these literal sequences:

```ts
test('pending then succeeded resolves only after terminal status', async () => {
  // initial: 202 pending, poll 1: running, poll 2: succeeded
  // expect onProgress states and one terminal success
});

test('exact missing resubmits the same mutation once with the same operation id', async () => {
  // expect two mutation calls, identical body operationId, then polling success
});

test('failed operations reject with their public message', async () => {
  // fake initial accepted response followed by terminal failed status
  await expect(runServerLifecycleOperation(input)).rejects.toThrow('서버 리셋 검증에 실패했습니다.');
  expect(progress).not.toContain('succeeded');
});
```

Add a separate fake-timer timeout test that advances exactly 10 minutes, asserts a timeout result containing the operation id, and asserts no `succeeded` progress event.

- [ ] **Step 3: Run lifecycle client tests and verify RED**

Run: `cd web/gateway && pnpm exec vitest run __tests__/admin-server-lifecycle.test.ts`

Expected: module/API missing.

- [ ] **Step 4: Implement the minimal client**

Generate one id, submit, parse normalized state, poll every 1s up to 10m, resubmit once only when requested, reuse the identical id, and support `AbortSignal`. Export types used by the page.

- [ ] **Step 5: Verify lifecycle client GREEN**

Run the focused test and confirm all cases pass under fake timers.

- [ ] **Step 6: Write failing admin component tests**

Test real `AdminView` behavior with only the network boundary faked:

- pending reset renders `처리 중` and never `처리 완료`;
- succeeded reset renders completion and reloads version exactly once;
- failed create/delete render `publicMessage` and do not reload membership;
- controls remain disabled while polling.

- [ ] **Step 7: Run component tests and verify RED**

Run: `cd web/gateway && pnpm exec vitest run __tests__/admin-server-id.test.tsx`

Expected: current page immediately renders completion from accepted reset and does not poll.

- [ ] **Step 8: Wire all three controls to the lifecycle client**

Extend response types, include generated operation id in create/reset bodies and delete body, render non-terminal labels, abort on unmount, and call `loadVersion(false)` only on terminal success.

- [ ] **Step 9: Verify focused and full web-gateway gates**

Run:

```bash
cd web/gateway
pnpm exec vitest run __tests__/admin-server-lifecycle.test.ts __tests__/admin-server-id.test.tsx
pnpm typecheck
pnpm test
```

- [ ] **Step 10: Commit Task 4**

Commit with `fix(web-gateway): wait for server lifecycle completion` plus trailer.

---

### Task 5: Update control workflows, admin docs, and cross-repo verification

**Files:**
- Create: `tools/ops/wait_deployer_operation.sh`
- Create: `tools/ops/wait_deployer_operation_contract_test.sh`
- Modify: `.github/workflows/reset-game-server.yml`
- Modify: `docs/admin/README.md`
- Create: `[opensamguk-docker]/scripts/wait-deployer-operation.sh`
- Create: `[opensamguk-docker]/scripts/wait-deployer-operation-test.sh`
- Modify: `[opensamguk-docker]/.github/workflows/recreate-server.yml`
- Modify: `[opensamguk-docker]/README.md`
- Create: `docs/superpowers/reviews/2026-08-28-durable-server-lifecycle-operations-review.md`

**Interfaces:**
- Consumes: terminal operation polling contracts from Tasks 2–4.
- Produces: rollout-safe workflow behavior and operator documentation.

- [ ] **Step 1: Write failing workflow contract tests**

Create executable polling helpers whose only inputs are deployer base URL, bearer token source, operation id, absolute deadline, and poll interval. Their tests put a fake `curl` first on `PATH`, feed the literal sequence `pending -> recovery_required -> succeeded`, and assert exit 0 only after the third response. A separate literal `failed` response must exit non-zero and a malformed/missing response must never count as success.

- [ ] **Step 2: Run workflow tests and verify RED**

Run:

```bash
bash tools/ops/wait_deployer_operation_contract_test.sh
cd [opensamguk-docker] && bash scripts/wait-deployer-operation-test.sh
```

Expected: both fail because the polling helpers do not exist.

- [ ] **Step 3: Implement bounded operation polling**

Generate one 32-hex id, include it in the request, poll `/operations/{id}`, require `succeeded`, preserve existing absolute deadlines and maintenance failure messages, and never print response bodies containing secrets.

- [ ] **Step 4: Verify workflow GREEN and parse checks**

Run:

```bash
bash tools/ops/wait_deployer_operation_contract_test.sh
bash -n tools/ops/wait_deployer_operation.sh
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/reset-game-server.yml")'

cd [opensamguk-docker]
bash scripts/wait-deployer-operation-test.sh
bash -n scripts/wait-deployer-operation.sh
go test ./deployer/... -run 'TestRecreateWorkflow|TestMaintenanceWorkflow'
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/recreate-server.yml")'
```

- [ ] **Step 5: Update admin documentation**

Document accepted versus completed states, `recovery_required`, operation id correlation, polling timeout semantics, and the repair workflow. Do not describe the feature as deployed until deployment occurs.

- [ ] **Step 6: Run full cross-repo verification**

Run fresh commands:

```bash
# opensamguk-docker
cd deployer && go test ./... && go vet ./... && go build ./...

# opensamguk backend
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :infra:test :app:gateway-api:test --rerun-tasks

# opensamguk web gateway
cd web/gateway && pnpm typecheck && pnpm test

# both repositories
git diff --check
```

Read Gradle XML counts and full command exit codes.

- [ ] **Step 7: Commit Task 5 changes in each affected repository**

Use one logical commit per repository with the required co-author trailer.

- [ ] **Step 8: Request independent adversarial review**

Give the reviewer the spec, this plan, both base/head SHAs, and ask specifically about duplicate Docker mutation, crash boundaries, transition reconciliation, secret leakage, workflow deadlines, and false UI completion. Fix every Critical/Important finding and rerun the affected gates.

- [ ] **Step 9: Write metarepo task reports**

Record result, commits, verification, docs impact, deployment status, and remaining risk under both `reports/opensamguk/tasks/` and `reports/opensamguk-docker/tasks/`.
