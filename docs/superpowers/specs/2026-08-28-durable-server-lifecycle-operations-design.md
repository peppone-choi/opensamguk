# Durable Server Lifecycle Operations Design

- Date: 2026-08-28
- Scope: `opensamguk` gateway/UI + `opensamguk-docker` deployer/control workflows
- Status: approved design, implementation pending

## Problem

Server create, close, and reset are asynchronous, but the current control plane does not expose one durable end-to-end operation contract.

- Create and close have a gateway database transition, but a normal deployer `pending` response becomes HTTP 202 with `ok=false`. The admin UI renders it as a failure and does not reconcile it automatically.
- Reset returns `ok=true` when the deployer only accepted the job. The admin UI immediately renders `처리 완료`, before volume replacement, scenario seed, runtime verification, and shared-registry reload finish.
- Deployer job and operation results live only in memory. A deployer restart erases `/jobs/{id}` and `/operations/{id}` even when the underlying lifecycle mutation completed.
- The job endpoint exposes only an id and status. A caller cannot distinguish a completed operation, a recovery-required operation, and a terminal failure with a safe operator-facing reason.

The Docker mutation layer already has the right destructive-boundary primitives: Docker reachability preflight, a durable lifecycle journal, a maintenance/repair barrier, reset runtime verification, and shared-registry verification. This design keeps those primitives and connects them to a durable operation identity.

## Goals

1. Create, close, and reset use the same 32-character lowercase hexadecimal `operationId` contract.
2. A deployer restart does not erase accepted or terminal operation status.
3. A gateway restart does not lose the transition needed to reconcile its `game_server` registry.
4. The admin UI announces success only after the operation reaches `succeeded`.
5. Retrying the same operation id never repeats its Docker mutation.
6. Recovery after a process crash remains fail-closed and produces one truthful operation state.
7. Operator-facing errors are useful but never expose Docker output, environment values, credentials, paths containing secrets, or stack traces.

## Non-goals

- Replacing the existing file-backed deployer lifecycle journal with a database.
- Building a general-purpose workflow engine.
- Adding unauthenticated lifecycle status endpoints.
- Preserving operation records forever.
- Changing game rules, scenario seed semantics, or the one-daemon-write architecture.

## Chosen architecture

The browser creates the operation identity, the gateway durably records the requested transition, and the deployer durably records execution state. The same operation id crosses every boundary.

```text
admin UI
  | mutation with operationId
  v
gateway-api game_server_registry_transition
  | POST create|close|reset with same operationId
  v
deployer durable operation store + lifecycle journal
  | docker compose / verification / repair
  v
GET gateway operation status -> deployer status -> gateway reconciliation
  | terminal succeeded|failed|cancelled
  v
admin UI final message and one registry refresh
```

This is preferred over making lifecycle HTTP requests synchronous because reset duration exceeds a safe request lifetime. It is preferred over making the gateway the Docker execution source of truth because the deployer owns the lifecycle journal and repair boundary.

## Operation identity and request rules

- Format: exactly `[a-f0-9]{32}`.
- The web UI generates it with `crypto.randomUUID().replaceAll('-', '')` once per user confirmation.
- The same id is reused for every retry and status poll belonging to that confirmation.
- Gateway callers that omit the id remain supported during rollout; gateway-api generates one before creating the transition. The new admin UI always supplies one.
- Deployer direct control workflows must supply an id and poll it to terminal state.
- Reusing an id with a different kind, server id, or request fingerprint returns HTTP 409.
- Reusing an id with the same normalized request returns the existing operation without executing Docker again.

## Unified operation states

The external state set is:

| State | Terminal | Meaning |
|---|---:|---|
| `pending` | no | Accepted and durably reserved, not yet claimed by the mutation worker. |
| `running` | no | Worker owns the mutation lease. |
| `recovery_required` | no | A durable journal remains; normal mutation admission is closed until repair completes. |
| `succeeded` | yes | Docker work, runtime checks, shared reload, and gateway reconciliation completed. |
| `failed` | yes | Failure happened before a recoverable destructive boundary, or repair proved the requested result cannot be completed. |
| `cancelled` | yes | Work was cancelled before a durable mutation boundary and will not be resumed. |

`recovery_required` is intentionally non-terminal. A reset that crossed volume removal must never become a final failure while the repair path can still complete the requested desired state.

## Deployer durable operation store

### Location and format

- Default path: `${SERVERS_DIR}/.deployer-operations.json`.
- Optional override: `DEPLOYER_OPERATION_STORE_FILE`.
- File mode: `0600`.
- Schema version: `1`.
- Maximum records: `512`.
- Terminal retention: `24h`.
- Non-terminal records are never pruned.

Each record contains only:

```json
{
  "operationId": "0123456789abcdef0123456789abcdef",
  "kind": "create|close|reset",
  "subjectId": "pep",
  "requestFingerprint": "<sha256>",
  "status": "pending|running|recovery_required|succeeded|failed|cancelled",
  "httpStatus": 200,
  "publicMessage": "서버 리셋이 완료되었습니다.",
  "createdAt": "2026-08-28T00:00:00Z",
  "updatedAt": "2026-08-28T00:00:00Z"
}
```

The store must not contain the request body, environment values, Docker output, generated database passwords, JWT material, maintenance leases, or stack traces.

### Durability

Every state transition uses the repository's durable-file pattern:

1. serialize the complete bounded store;
2. write a sibling temporary file;
3. `fsync` the file;
4. rename over the destination;
5. `fsync` the containing directory.

The in-memory job manager may cache records, but reservation and state transitions are successful only after the durable write succeeds.

### Startup recovery

On deployer startup:

- Terminal records are loaded as-is.
- A non-terminal record whose operation id is referenced by the lifecycle journal becomes `recovery_required`; `/readyz` remains closed until repair succeeds.
- A non-terminal record without a matching lifecycle journal becomes `cancelled` with the public message `deployer 재시작 전에 작업이 중단되었습니다. 다시 요청해 주세요.` This is safe because every lifecycle path writes the journal before its first durable desired-state mutation.
- A malformed operation store fails deployer readiness closed. It is never silently replaced with an empty store.

### Journal linkage and repair

The lifecycle journal adds optional fields `operationId` and `operationKind`. New create, close, and reset requests always populate them before changing env, registry, volumes, or compose state.

- Worker success: clear journal, then persist operation `succeeded`.
- Failure while a journal remains: persist `recovery_required`, not `failed`.
- Repair success: verify the existing postconditions, clear the journal, then persist the linked operation `succeeded`.
- Repair failure: keep the journal and operation `recovery_required`.
- Failure before a journal exists: persist terminal `failed` with a bounded public message.

If the process crashes between clearing the journal and persisting success, startup sees a non-terminal operation without a journal. To avoid a false cancellation, the worker must persist `succeeded` before clearing the journal, and repair follows the same ordering. A succeeded record with a leftover journal is safe: startup repair re-verifies postconditions and clears the journal without replaying the Docker mutation.

## Deployer HTTP contract

### Mutation endpoints

`POST /servers/create`, `POST /servers/close`, and `POST /servers/reset` accept `operationId`.

Accepted response:

```json
{
  "ok": true,
  "id": "pep",
  "operationId": "0123456789abcdef0123456789abcdef",
  "operationStatus": "pending"
}
```

The accepted response never means completion.

### Operation lookup

`GET /operations/{operationId}` returns:

```json
{
  "operationId": "0123456789abcdef0123456789abcdef",
  "kind": "reset",
  "subjectId": "pep",
  "status": "recovery_required",
  "httpStatus": 202,
  "publicMessage": "서버 복구 확인이 필요합니다. 운영 복구가 끝날 때까지 기다려 주세요."
}
```

- HTTP 200 is used when the operation record exists, regardless of operation state.
- HTTP 404 keeps the existing exact three-key `not_found` contract.
- `publicMessage` is selected from bounded lifecycle-stage messages. Raw command errors remain server logs only.

`GET /jobs/{id}` remains for control workflows but returns the same status and public message when the job is operation-backed. New gateway/UI code uses `/operations/{operationId}`.

## Gateway durable transition

### Database migration

A new Flyway migration extends `game_server_registry_transition.action` from `CREATE|CLOSE` to `CREATE|CLOSE|RESET` without rewriting existing rows. The existing unique `operation_id` remains the idempotency key.

No request body is stored in PostgreSQL. A browser retry retains and resubmits the normalized mutation body with the same operation id if the deployer reports exact `not_found`. The deployer's durable store makes `not_found` after acceptance exceptional rather than a normal restart outcome.

### Transition behavior

- `CREATE`: terminal success upserts `game_server`, then deletes the transition.
- `CLOSE`: terminal success deletes `game_server`, then deletes the transition.
- `RESET`: terminal success leaves membership unchanged, refreshes generation/scenario metadata from the transition snapshot, then deletes the transition.
- Terminal `failed|cancelled`: delete the unapplied transition and return its public failure.
- `pending|running|recovery_required`: release the transition lease and return HTTP 202 with `ok=false`, `completed=false`, `retryable=true`, and the operation identity.

### Status endpoint

Add authenticated ADMIN route:

```text
GET /admin/servers/operations/{operationId}
```

The service:

1. validates the id;
2. looks up and claims the gateway transition by operation id when present;
3. queries deployer `/operations/{operationId}`;
4. reconciles a terminal result into `game_server` within the existing transition transaction;
5. returns the normalized operation response;
6. when no transition remains, still returns the deployer's retained terminal result so a lost status response is safe to repeat.

If deployer returns exact `not_found` while a transition exists, the response is HTTP 202 with `status=missing`, `retryable=true`, and `resubmitRequired=true`. The UI resubmits the original mutation once with the same operation id, then resumes polling. A second exact `not_found` is shown as a failure instead of looping forever.

## Admin UI behavior

Create a focused lifecycle client module outside the large admin page. It owns operation-id generation, initial submission, bounded resubmission, polling, timeout, and abort behavior.

- Poll interval: `1s`.
- Deadline: `10m`.
- At most one automatic mutation resubmission, only for `resubmitRequired=true`.
- Abort polling when the component unmounts or the user starts a replacement operation.
- Disable create/reset/delete controls while their operation is non-terminal.
- Render `요청 접수`, `처리 중`, or `복구 확인 중` for non-terminal states.
- Render success and refresh the server list exactly once only after `succeeded`.
- Render terminal failure/cancellation using `publicMessage`; do not refresh membership as if successful.
- A polling timeout says the operation is still running and includes the operation id for log correlation. It never says the mutation failed or succeeded.

The response TypeScript type includes `operationId`, `operationStatus`, `completed`, `retryable`, `resubmitRequired`, and `publicMessage`.

## Control workflow changes

Direct create/reset workflows generate an operation id, include it in the mutation request, poll `/operations/{id}`, and require `succeeded` before moving to their existing container/public postcondition checks.

Workflow polling treats:

- `pending|running|recovery_required` as non-terminal;
- `succeeded` as permission to continue postcondition verification;
- `failed|cancelled` as an immediate bounded failure;
- missing/malformed status as failure, never implicit success.

## Failure messages

Allowed public message categories are fixed and stage-based:

- request validation failed;
- Docker is unavailable before mutation;
- operation is already in progress;
- create/close/reset preparation failed;
- lifecycle recovery is required;
- lifecycle verification failed;
- operation was cancelled before mutation;
- operation completed.

The internal log retains the detailed Go error and Docker stderr. HTTP and durable operation records receive only the category text, server id, and operation id.

## Test strategy

All behavior changes follow red-green TDD.

### `opensamguk-docker`

1. Terminal operation lookup survives constructing a new job manager from the same store file.
2. A running operation linked to a journal restarts as `recovery_required` and repair changes it to `succeeded`.
3. A pending operation without a journal restarts as `cancelled` without executing Docker.
4. Two reset requests with the same operation id and fingerprint execute `down --volumes` once.
5. Reusing a reset operation id with a different normalized request returns 409.
6. Operation-store writes are atomic, bounded, retain non-terminal entries, and reject malformed state fail-closed.
7. Operation lookup never returns injected Docker output or environment values.
8. Direct reset/create workflows poll terminal operation state before existing postcondition checks.

### `opensamguk`

1. Migration accepts `RESET` and preserves existing transition rows.
2. Create, close, and reset all return an operation id on pending responses.
3. Status polling reconciles CREATE, CLOSE, and RESET exactly once.
4. Gateway restart with the same database transition resumes polling and reconciliation without a second deployer mutation.
5. Exact deployer `not_found` requests one bounded resubmission with the same operation id.
6. UI pending and recovery states never render completion.
7. UI success refreshes the server list once; failure and timeout do not.
8. Ambiguous initial response reuses the same id and never creates a second reset.

## Rollout and compatibility

1. Deploy deployer support first. It accepts existing callers without an operation id but logs that the call is legacy and cannot provide cross-restart idempotency.
2. Deploy the gateway migration and unified transition/status API.
3. Deploy the web gateway lifecycle client.
4. Update direct control workflows to require operation polling.
5. Verify create and close with a disposable server; verify reset only against an explicitly approved disposable server with backup evidence.

During mixed-version rollout, the gateway recognizes an old deployer response without `operationStatus` and returns a clear `deployer lifecycle capability upgrade required` failure before claiming completion.

## Acceptance criteria

- No create, close, or reset UI path displays completion from an accepted/pending response.
- The same operation id cannot invoke Docker mutation twice, including across a deployer restart.
- A deployer restart preserves terminal lookup and connects unfinished durable work to lifecycle repair.
- A gateway restart preserves transition reconciliation for all three actions.
- Reset membership metadata changes only after deployer terminal success.
- All new backend, deployer, UI, migration, and workflow regression tests pass.
- Existing deployer Go suite, gateway-api suite, web-gateway suite, and repository verification gates remain green.
- Admin/operator documentation describes accepted versus completed operations and recovery-required behavior.
