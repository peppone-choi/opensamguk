# Turn Daemon Lifecycle

This document defines the lifecycle of the turn daemon for the rewrite when a
single daemon instance is responsible for turn resolution and all triggers come
from the API server.

## Assumptions

- One daemon process per server+scenario profile.
- API server is the only ingress for user/admin requests.
- The daemon owns world-state mutations; the API server mutates reserved turns and messages.

## Current Implementation Status

- The daemon loop handles scheduled runs plus Redis-based control queue commands
  (run/pause/resume/shutdown/getStatus).
- API mutation requests (troopJoin, vacation, etc.) are drained and handled by the daemon loop between turns.
- `getStatus` is fully supported and responds via Redis.
- API server currently writes reserved turns and messages directly to the DB.

## Responsibilities

- Maintain authoritative in-memory state.
- Execute turn resolution when scheduled.
- Flush state changes and checkpoints to the DBMS.
- Expose status for ops and admin tooling.

## Trigger Sources

- Scheduled tick: next turn time is reached.
- API poke: user/admin request asks the daemon to run now.
- Admin control: pause/resume or manual catch-up.

## State Model

```
Idle -> Running -> Flushing -> Idle
   \-> Paused (admin)
   \-> Stopping (shutdown)
```

- `running` flag prevents re-entrant execution when multiple triggers arrive.
- `pendingReason` tracks why a run was requested (schedule, manual, poke).

## Main Loop Sketch

The daemon interleaves API request handling between scheduled turn executions.
API requests are drained until the next turn time is reached; once the turn
starts, incoming requests are queued and processed after the run.
Current implementation does not yet drain API requests between turns; this
section is the intended target behavior.

```ts
while (!stopping) {
  const signal = await waitForNextSignal(nextTurnTime, wakeSignal);
  if (signal.type === "pause") {
    paused = true;
  }
  if (signal.type === "resume") {
    paused = false;
  }
  if (paused || running) {
    continue;
  }

  await drainApiRequestsUntil(nextTurnTime);

  if (now() < nextTurnTime && signal.type !== "run") {
    continue;
  }

  running = true;
  try {
    await runUntil(now(), budget);
    await flushChanges();
    publishTurnEvents();
  } finally {
    running = false;
  }
}
```

- `drainApiRequestsUntil()` processes queued user/admin requests and applies
  them to in-memory state until the next scheduled turn time is reached.
- During `running`, new API requests are enqueued and handled after the run.

## Run Budget and Checkpoints

Replace PHP `max_execution_time` with explicit limits to allow partial progress:

- `budgetMs`: max wall-clock time per run.
- `maxGenerals`: max generals processed per run.
- `catchUpCap`: max turns (or months) processed in one run.

When a limit is hit, persist a checkpoint so the next run resumes safely.
Minimum checkpoint data:

- `game_env.turntime` (last fully processed general turn time)
- optional cursor (last processed general id) if you batch within a turntime
- last known year/month if monthly transitions are mid-flight

## API Server Interaction

- API server enqueues mutations and pokes the daemon.
- Read-only queries can read from DBMS or a read model.
- During `running`, incoming requests are queued and processed after the run.

## Status and Admin Controls

Expose a status endpoint to replace `proc.php` output:

- `running`, `paused`, `lastRunAt`, `lastDurationMs`
- `lastTurnTime`, `nextTurnTime`
- `pendingReason`, `queueDepth`

Admin controls should toggle `paused`, trigger manual run, and request catch-up.

### Suggested Status Endpoint (Draft)

`GET /admin/turn-daemon/status`

```json
{
  "profile": "che:default",
  "state": "idle",
  "running": false,
  "paused": false,
  "lastRunAt": "2026-01-01T12:00:00Z",
  "lastDurationMs": 842,
  "lastTurnTime": "2026-01-01 12:00:00",
  "nextTurnTime": "2026-01-01 12:10:00",
  "pendingReason": "schedule",
  "queueDepth": 3,
  "checkpoint": {
    "turnTime": "2026-01-01 12:00:00",
    "generalId": 1201,
    "year": 12,
    "month": 3
  }
}
```

### Suggested Admin Endpoints (Draft)

`POST /admin/turn-daemon/run`

```json
{
  "reason": "manual",
  "targetTime": "2026-01-01 12:10:00",
  "budgetMs": 2500,
  "catchUpCap": 3
}
```

`POST /admin/turn-daemon/pause`

```json
{
  "reason": "maintenance"
}
```

`POST /admin/turn-daemon/resume`

```json
{
  "reason": "maintenance complete"
}
```

### Daemon Control Contract (Draft)

API server to daemon control messages (Redis Stream or in-process channel):

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

## Recovery and Shutdown

- On startup, load `game_env.turntime` and catch up to `now`.
- On shutdown, stop accepting new triggers, finish the current run, flush, then
  exit cleanly.

## Testing with a Controlled Clock

- Turn daemon tests should use a controllable `Clock` implementation (예: `ManualClock`) to
  advance time deterministically without relying on wall-clock time.
- 기준 시간은 DB에 저장된 `game_env.turntime`을 우선으로 삼고, 테스트에서도 동일한 기준을
  사용해 스케줄 계산과 체크포인트 동작을 검증한다.

## TypeScript Sketch (Draft)

```ts
export type TurnDaemonState =
  | "idle"
  | "running"
  | "flushing"
  | "paused"
  | "stopping";

export interface TurnRunBudget {
  budgetMs: number;
  maxGenerals: number;
  catchUpCap: number;
}

export interface TurnCheckpoint {
  turnTime: string;
  generalId?: number;
  year: number;
  month: number;
}

export interface TurnRunResult {
  lastTurnTime: string;
  processedGenerals: number;
  processedTurns: number;
  durationMs: number;
  partial: boolean;
  checkpoint?: TurnCheckpoint;
}

export interface TurnDaemonStatus {
  state: TurnDaemonState;
  running: boolean;
  paused: boolean;
  lastRunAt?: string;
  lastDurationMs?: number;
  lastTurnTime?: string;
  nextTurnTime?: string;
  pendingReason?: RunReason;
  queueDepth: number;
  checkpoint?: TurnCheckpoint;
}

export interface TurnRunner {
  getStatus(): Promise<TurnDaemonStatus>;
  requestRun(
    reason: RunReason,
    targetTime?: string,
    budget?: TurnRunBudget,
  ): Promise<void>;
  pause(reason?: string): Promise<void>;
  resume(reason?: string): Promise<void>;
}
```
