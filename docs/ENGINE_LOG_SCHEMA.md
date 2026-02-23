# Engine Log Schema

This document defines the structured log formats used by the @sammo/engine.

## Log Format

By default, the engine uses the NestJS Logger, which outputs:
`[Nest] <PID>  - <Timestamp> <LOG_LEVEL> [<Context>] <Message>`

## Key Events

### Turn Cycle

| Event          | Message Format                                            | Description                                     |
| -------------- | --------------------------------------------------------- | ----------------------------------------------- |
| Turn Start     | `Starting turn cycle...`                                  | Beginning of the processTurn loop.              |
| Turn Skip      | `Skipping turn cycle (already running).`                  | Logged when the previous cycle is still active. |
| State Loaded   | `State loaded: <N> generals, <M> nations.`                | Initial state loading or reload after failure.  |
| Turn Processed | `Turn <Year>년 <Month>월 processed in <Duration>ms.`      | Result of GameEngine.processTurn.               |
| State Flushed  | `State changes applied and flushed to DB (<Duration>ms).` | Successful DB persistence.                      |

### Lifecycle

| Event       | Message Format                                    | Description                         |
| ----------- | ------------------------------------------------- | ----------------------------------- |
| Startup     | `Starting Game Engine Service...`                 | Engine service initialization.      |
| Listening   | `Engine is running and listening on port <Port>.` | HTTP server started for monitoring. |
| Shutdown    | `Stopping Game Engine Service...`                 | Graceful shutdown initiated.        |
| Final Flush | `Final flush before shutdown...`                  | Last DB save before process exit.   |

### Errors

| Event         | Message Format                                      | Description                                     |
| ------------- | --------------------------------------------------- | ----------------------------------------------- |
| Load Failure  | `Failed to load initial state: <Error>`             | Fatal error during startup or reload.           |
| Cycle Failure | `Error in turn cycle (<Year>년 <Month>월): <Stack>` | Non-fatal error during a turn, triggers reload. |

## Monitoring Endpoints

- `GET /status`: Returns current engine metrics (JSON).
- `GET /status/health`: Simple health check.
