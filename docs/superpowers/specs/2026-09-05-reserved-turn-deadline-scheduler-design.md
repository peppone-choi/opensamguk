# Reserved Turn Deadline Scheduler Design

## Problem

The game exposes each general's `turnTime` as the execution time of that general's reserved command, but the production runner currently calls `TurnRunService.runTick` only at the world's global boundary (`lastTurnTime + tickSeconds`). With a five-minute world cadence and per-general jitter, a reserved command can therefore execute almost five minutes after the displayed time.

Command reservation also feels slower than the durable write itself. The game API commits the `general_turn`, `command_inbox`, and `reservationAccepted` result before returning `202`, while the web client always sleeps 300 ms before its first canonical result lookup.

This is a scheduler-semantics mismatch, not a timezone conversion error. The production world cadence remains five minutes; the daemon's idle observation bound changes from 1,000 ms to 250 ms.

## Goals

- Execute a general's reserved command no later than 250 ms after its persisted `turnTime` during normal operation.
- Preserve the five-minute global world cadence and all month/phase boundary ordering.
- Preserve strict due semantics: a general is due only when `turnTime < executionAsOf`.
- Preserve deterministic ordering by `(turnTime, generalId)` and one transactional flush per drain batch.
- Preserve the one-daemon-write rule and the existing flush recovery/world-version fence.
- Remove the unconditional 300 ms delay before the web client's first command-result lookup.

## Non-goals

- Changing `tickSeconds`, game speed, month length, or seeded general jitter.
- Moving turn execution to GitHub Actions or another external cron.
- Replacing Redis command intake or the command-result/SSE protocol.
- Changing the strict `<` due gate to an inclusive comparison.

## Scheduling Model

The in-process daemon owns two independent deadlines:

1. **World boundary** — `lastTurnTime + tickSeconds`. Reaching this deadline runs the existing full `runTick`, including ordered general drains, month/phase processing, tournament and auction hooks, one flush, world clock advancement, and `turnCompleted` publication.
2. **General deadline** — the minimum in-memory general `turnTime`, made runnable one nanosecond later to retain strict `<` semantics. Reaching this deadline before the next world boundary runs a general-only drain and one flush without advancing `world_state.last_turn_time` or the world calendar.

The runner still checks immediate intake and configuration changes at most every 250 ms. It sleeps for the smaller of the remaining deadline duration and `idlePollMs`; it does not busy-spin.

### Catch-up ordering

When the process is behind and the next world boundary is already due, the runner always executes that world boundary before any general-only drain. This ensures general actions are interleaved with month/phase transitions in the same order as the existing boundary driver. General-only drains are legal only while `now < nextWorldBoundary`.

If a general deadline exactly matches a world boundary, the world boundary runs first. Strict `<` leaves the equal-time general pending, and the following general deadline executes it immediately after the boundary.

## Engine Components

### `TurnDaemonLifecycle`

Expose the earliest general execution deadline from the in-memory world. The deadline is `min(turnTime) + 1ns`; an empty general set returns no deadline. The existing `dueGenerals` ordering and strict comparison remain unchanged.

### `TurnRunService`

Keep `runTick(worldBoundary)` as the only world-clock-advancing operation. Add a general-only entry point that:

- claims and dispatches executable immediate-intake envelopes before the general pass, matching the existing full-tick ordering;
- snapshots the general drain cohort;
- runs `TurnDaemonLifecycle.runTick(executionAsOf, cohort)`;
- builds a normal daemon flush payload with the current year, month, phase, and `lastTurnTime` unchanged;
- applies the existing writer fence and combines immediate-intake plus reserved-execution command-result rows;
- flushes through `flushWithGeneration`, acknowledges claimed wakes, publishes settled command results, and leaves the world clock untouched.

The general-only path does not run monthly, tournament, or auction hooks and does not publish the coarse `turnCompleted` event. Reserved execution results continue to produce their existing command-settled realtime signal.

Shared non-clock-advancing flush construction should be extracted only as far as needed to keep `runIntakeCommands` and the new general drain consistent. No second dirty-state mechanism is introduced.

### `TurnDaemonRunner`

Change the default `opensamguk.daemon.idle-poll-ms` from `1000` to `250`. On each loop:

1. preserve the existing world availability, pause, and recovery gates;
2. process an overdue world boundary first;
3. otherwise process a due general deadline;
4. otherwise check immediate intake and sleep until the earliest of the two deadlines, capped at 250 ms.

World ticks retain the existing success/failure diagnostics. General-only drain failures use the same recovery and backoff path so failed batches cannot admit new work before recovery.

## Web Command Acknowledgement

`pollCommandResultResponse` performs its first canonical `api.commandResult(requestId)` lookup immediately. It sleeps 300 ms only before subsequent retries. A synchronously committed `reservationAccepted` result therefore returns without the artificial delay, while asynchronous immediate commands retain the same bounded polling and SSE race behavior.

No API response schema change is required.

## Configuration and Operations

- Default `opensamguk.daemon.idle-poll-ms`: `250`.
- Global `tickSeconds`: unchanged; production remains `300` seconds unless explicitly reconfigured.
- The five-minute `daemon-health-alert.yml` schedule is monitoring only and remains unchanged.
- Operational documentation must distinguish the 250 ms daemon observation bound, the per-general deadline, and the five-minute global world cadence.

At 250 ms, an idle engine performs at most roughly four observation loops per second per server. This is acceptable for the current one-engine-per-server topology and avoids the unnecessary ten-or-more wakeups per second of a 100 ms setting.

## Failure Handling

- A failed general-only flush enters the existing `FLUSH_RETRY` or `RELOAD_REQUIRED` state; the runner does not process intake, another general drain, or a world tick until recovery.
- The retained payload for a general-only flush carries the unchanged world clock. Retrying it must not emit `turnCompleted`.
- Retained-payload clock application treats `payload.last_turn_time == current lastTurnTime` as a non-clock batch and returns without calendar mutation or `turnCompleted` publication.
- A restart with overdue world boundaries catches those boundaries up before executing later personal deadlines.
- Pause semantics remain unchanged: immediate intake may drain while paused, but neither world ticks nor reserved general turns execute.

## Verification

Tests must prove:

- the earliest general deadline is `turnTime + 1ns` and preserves strict `<` execution;
- a due personal deadline invokes the general-only path while the world boundary is still in the future;
- an overdue world boundary takes priority over a personal deadline;
- a general-only flush advances the general and command result but not year, month, phase, or `lastTurnTime`;
- flush retry for a general-only batch does not advance or publish the world clock;
- pause and recovery gates block the new path;
- the runner defaults to a 250 ms idle bound;
- the web client performs its first result lookup without advancing fake timers and retains 300 ms spacing for later attempts;
- existing runner, turn-service recovery, command submission, and focused module regression suites remain green.

## Acceptance Criteria

- Under normal load, `actual reserved execution time - persisted general turnTime <= 250 ms` excluding the command's own execution and flush duration.
- A five-minute production world cadence remains a five-minute cadence.
- No general turn executes twice across deadline/world-boundary adjacency or flush retry.
- No world/month transition occurs on a general-only drain.
- A committed reservation can be acknowledged without the previous mandatory 300 ms client delay.
