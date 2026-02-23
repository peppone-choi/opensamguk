# Runtime and Build Profiles

Build outputs should be emitted to `/dist/{profileName}` per profile to keep
deployments predictable. Profiles are server+scenario pairs, and scenario
selection is required because it drives unit sets and DB settings.

## Database Schemas (Gateway vs Game)

Gateway uses a shared schema (default `public`) for login/profile state, while
each game profile runs against its own schema. This keeps gateway data stable
and allows profile-scoped game data resets.

- Gateway schema: `GATEWAY_DB_SCHEMA` (default `public`)
- Game schema: `PROFILE` value (e.g., `hwe`, `che`)
- Optional override for gateway DB URL: `GATEWAY_DATABASE_URL`

## Suggested Build Pattern

- Wrapper script under `tools/build-scripts`
- `pnpm build:server --profile che --scenario default`
- CI-friendly: `PROFILE=che SCENARIO=default pnpm build:server`
- Build tooling: use `tsdown` for backend/libs, and Vite for frontend apps.

## Deterministic RNG Policy

- Gameplay randomness must be reproducible from a deterministic seed
- Prefer `legacy/hwe/ts/util/LiteHashDRBG.ts` and `legacy/hwe/ts/util/RNG.ts`
- Seed composition should include hidden base seed plus action context

## Gateway Orchestration (Single Host Draft)

Gateway API is the single source of truth for profile state and reconciles
PM2-managed processes on boot and on a short interval. The orchestrator runs
as a separate process (`GATEWAY_ROLE=orchestrator`). The DB owns the desired
state; PM2 is treated as the actuator. The runtime state is grouped so that
`game-api` + `turn-daemon` are either on together or off together.

### DB-Owned Profile State

Profiles are tracked by `profileName` (= `${profile}:${scenario}`).
Gateway loads the profile table on boot, then reconciles PM2 to match.

- `예약됨` (RESERVED): preopen/open timestamps are set; processes off.
- `가오픈` (PREOPEN): build done; API+daemon on, daemon paused.
- `가동중` (RUNNING): both `game-api` and `turn-daemon` should be on.
- `정지됨` (STOPPED): processes off; may be resumed later.
- `정지(오류)` (PAUSED): fatal error; daemon paused but process remains on.
- `천하통일` (COMPLETED): game finished; API on, daemon paused.
- `비활성화` (DISABLED): excluded from orchestration; start forbidden.

### Boot Reconciliation

1. Load profile rows from DB.
2. List PM2 processes and map `profileName -> running state`.
3. For each profile:
   - If desired `RUNNING/PREOPEN/PAUSED/COMPLETED` and any process is missing, start.
   - If desired `RESERVED/STOPPED/DISABLED` and any process is running, stop both.
4. Persist errors to DB for audit.

### Internal Scheduler (Gateway Cron)

Gateway runs a lightweight cron loop (setInterval) that:

- When `RESERVED` and `preopenAt <= now`, queue a build for the reserved commit.
- When build succeeds, status becomes `PREOPEN` (daemon paused).
- When `openAt <= now`, status becomes `RUNNING` and daemon resumes.
- Optionally drains a build queue (see build workflow).

### Build Workflow (Admin)

- Admin triggers a build request for a profile.
- Gateway queues a build job with `(profileName, commitSha)` and prepares a
  per-commit workspace (`/.worktrees/{commitSha}` by default).
- Workspace is backed by `git worktree` and is reused across builds for the same commit.
- Each workspace stores `lastUsedAt` in DB so cleanup can remove stale worktrees.
- Cleanup is invoked manually by admin API and removes worktrees unused for 6+ months.
- Build runs `pnpm install` when workspace is created, then executes
  `pnpm --filter @sammo-ts/game-api build` and
  `pnpm --filter @sammo-ts/game-engine build`, then marks build success/failure.
- On success, status moves to `PREOPEN` for reserved builds or stays unchanged for manual builds.

## Current Implementation Status

- Turn daemon lifecycle + in-memory state live in `app/game-engine` with DB flush hooks.
- Redis transport for daemon control is implemented and wired into the daemon.
- API server already exposes turn-daemon commands (run/pause/resume/status) via tRPC, communicating via Redis Streams.
- API server writes reserved turns and messages directly to the DB; daemon focuses on world state/logs.

## Turn Daemon and API Server Behavior (Outline)

- Turn daemon responsibilities: scheduling, turn resolution, state persistence
- API server responsibilities: query/command intake, validation, response shaping
- Concurrency model between daemon and API server
- Communication channel: Redis Stream or Redis pub/sub
- Client updates: SSE between API server and frontend where appropriate

## Redis Communication Recommendation (Draft)

Use Redis Streams for daemon control and mutation requests, and Redis pub/sub
for transient fan-out events. Streams provide durability, backpressure, and
replay while pub/sub keeps live updates simple and low-latency.

### Recommended Split

- Redis Streams:
  - API server -> daemon: mutation requests, turn-run commands.
  - Daemon -> API server: run status events, job results, error reports.
  - Use consumer groups for daemon workers and API server listeners.
  - Require `requestId` for correlation and idempotency.
  - Ack on success; move failed items to a dead-letter stream after retry.
- Redis pub/sub:
  - Daemon -> API server: low-stakes live update signals (run started/ended).
  - API server -> frontend: SSE fan-out triggered by pub/sub updates.
  - Do not use pub/sub for data that must be replayed or audited.

### Operational Notes

- Stream keys should be namespaced per server+scenario profile.
- Use bounded stream length (`MAXLEN`) to cap storage.
- API server should guard against duplicate processing by `requestId`.
- When the daemon is busy, API queues new mutations to stream and responds
  with an accepted status to clients.

Detailed lifecycle and control flow are defined in
`docs/architecture/turn-daemon-lifecycle.md`.

## Authentication and Session Management (Draft)

Login uses Kakao OAuth as the primary identity provider because it leverages
Korean real-name verification and helps prevent multi-account abuse. The system
also supports local ID/password login for users who cannot use Kakao.

### Login Options

- Kakao login button (OAuth flow via Gateway).
- Local login with ID/password (managed by Gateway).
- Passkey is a possible future option; define later if required.
- Auto-login should be supported when an active session exists.

### Session and SSO-Like Behavior

- Gateway handles login and owns primary sessions in Redis.
- Game servers may run different branches; treat Gateway as a central SSO
  authority that issues session tokens for each server+scenario profile.
- API servers validate tokens against Redis and accept sessions issued by
  Gateway without re-authentication.
- Session tokens should be scoped by server+scenario profile to avoid cross-server leaks.

### Operational Notes

- Prefer HTTP-only secure cookies for session tokens where possible.
- Provide a logout flow that revokes tokens in Redis.
- Track last-login and session metadata for audit and abuse detection.

## Engine Runtime Flow (Draft)

### Turn Daemon Loop

- The turn daemon runs as a single-threaded loop.
- The daemon engine uses in-memory state as the primary working set.
- The daemon waits on two conditions during the event loop.
  - Query/command requests from the external API server.
  - The scheduled start time of the next turn.
- External requests are processed until the next turn start time is reached.
  - If no requests arrive, the daemon waits until the next turn start time.
  - When the next turn start time arrives, the daemon starts turn processing
    immediately even if requests remain queued.
- While the daemon is resolving a turn, the API server queues incoming requests.

Note: the current implementation does not yet process API mutation requests
between turns; only control commands are handled by the in-process queue.

### Daemon Control Contract (Draft)

API server commands are delivered to the daemon over the control channel
(Redis Stream or in-process). The daemon replies with status and run events.

```ts
export type RunReason = "schedule" | "manual" | "poke";

export type DaemonCommand =
  | {
      type: "run";
      reason: RunReason;
      targetTime?: string;
      budget?: TurnRunBudget;
    }
  | { type: "pause"; reason?: string }
  | { type: "resume"; reason?: string }
  | { type: "getStatus"; requestId: string };

export type DaemonEvent =
  | { type: "status"; requestId?: string; status: TurnDaemonStatus }
  | { type: "runStarted"; at: string; reason: RunReason }
  | { type: "runCompleted"; at: string; result: TurnRunResult }
  | { type: "runFailed"; at: string; error: string };
```

### API Server Flow

- The API server validates queries/commands and writes them to Redis Streams
  or Redis pub/sub.
- After a request is processed, the API server returns the result to clients.
- Read-only queries may access the DBMS directly.
- The API server may use SSE to stream live updates to the frontend.

### Queue and Rate Limits

- API server requests are delivered to the daemon via Redis Streams or
  Redis pub/sub.
- Redis Stream mutation requests are rate-limited per user.
  - Each user can have up to 30 pending mutation requests.
  - Additional requests are rejected once the limit is exceeded.

### In-Memory and DBMS Flush

- The daemon processes actions against in-memory state by default.
- DBMS writes are flushed in bulk after turn processing completes.
- Frequently changing "next-turn intent" data is stored separately.
  - The API server persists this data in the DBMS.
  - The daemon loads only this data when the next turn begins.

## Turn Daemon vs API Query Priority (Outline)

- Expected priority order under load
- Rules for preemption or deferral
- Handling of write-heavy operations during turn resolution

## In-Memory Processing and DBMS Flush (Outline)

- When in-memory state is authoritative
- Flush checkpoints and transactional boundaries
- Recovery strategy after crash during flush

## Testing and Observability (Outline)

- Metrics and logs required to validate scheduling and flush behavior
- Suggested test scenarios for concurrency and consistency

## Game Logic Testing (Draft)

### Deterministic Inputs

- RNG seed composition (hidden server seed, turn info, general info).
- Scenario selection and scenario data.
- Trigger set inputs: nation, general, and city state.
- Game time and tick schedule.

### Recommended Unit Test Flow

- Prepare a deterministic test fixture (mock DB or in-memory state snapshot).
- Execute game logic unit tests with fixed inputs and seeds.
- Compare expected outputs against the pre-flush change set that would be
  written to the DBMS.

### Notes

- Deterministic RNG makes output comparison stable and repeatable.
- Prefer snapshotting inputs/outputs so regressions are easy to track.
