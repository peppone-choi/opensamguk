# Legacy Engine Execution Flow

This document summarizes how the legacy engine advances turns and executes
commands. It focuses on the execution pipeline, RNG seeding, and state writes
based on `legacy/hwe/sammo/API/Global/ExecuteEngine.php`,
`legacy/hwe/sammo/TurnExecutionHelper.php`, and `legacy/hwe/sammo/Command/BaseCommand.php`.

## Entry Point: ExecuteEngine API

- Endpoint: `legacy/hwe/sammo/API/Global/ExecuteEngine.php`
- Session: no session required (`NO_SESSION`)
- Optional `serverID` guard via `UniqueConst::$serverID`
- Calls `TurnExecutionHelper::executeAllCommand($updated, $locked)`
- Returns `{ updated, locked, lastExecuted }`

## Global Turn Loop (`TurnExecutionHelper::executeAllCommand`)

1. **Time gate**
   - If `now < game_env.turntime`, return without executing.
2. **Locking**
   - `tryLock()` on `plock` row (`type='GAME'`).
   - If locked or `isunited` in `2|3`, return (frozen state).
3. **Pre-turn maintenance**
   - Cache game env, `checkDelay()`, `updateOnline()`, `CheckOverhead()`.
4. **Catch-up monthly loop**
   - Compute `prevTurn = cutTurn(turntime, turnterm)` and `nextTurn = addTurn(prevTurn)`.
   - While `nextTurn <= now`:
     - `executeGeneralCommandUntil(nextTurn, limitActionTime, year, month)`
     - `updateTraffic()`
     - Monthly pipeline:
       - `runEventHandler(PreMonth)`
       - `preUpdateMonthly()`
       - `turnDate(nextTurn)`
       - `checkStatistic()` (only if `month == 1`)
       - `runEventHandler(Month)`
       - `postUpdateMonthly($monthlyRng)`
     - Advance `prevTurn`, `nextTurn`, update `game_env.turntime`.
5. **Current partial month**
   - `turnDate(prevTurn)`
   - `executeGeneralCommandUntil(now, limitActionTime, year, month)`
6. **Post-turn maintenance**
   - `processTournament()`
   - `processAuction()`
   - Reset cache, `unlock()`.

`limitActionTime` is derived from `max_execution_time` (roughly 2/3 of the
configured limit). If time runs out mid-loop, it returns early and keeps
`game_env.turntime` at the most recent executed value.

## Per-General Execution (`executeGeneralCommandUntil`)

For each general with `turntime < targetDate` (ordered by `turntime, no`):

1. **Load state**
   - `General::createObjFromDB()` and `KVStorage` for `game_env`.
2. **Nation command (if officer_level >= 5)**
   - Pull `nation_turn` row for `turn_idx = 0`.
   - Build `NationCommand` with `LastTurn` stored in `nation_env`.
3. **AI/autorun**
   - NPCs (`npc >= 2`) or players past `autorun_limit` use `GeneralAI`.
   - AI can replace both nation and general commands.
4. **Preprocess triggers**
   - RNG seed: `hiddenSeed + 'preprocess' + year + month + generalID`.
   - `preprocessCommand()` merges action triggers + `che_부상경감`, `che_병력군량소모`.
5. **Blocked users**
   - `processBlocked()` consumes `killturn` and logs if `block >= 2`.
6. **Execute nation command**
   - RNG seed: `hiddenSeed + 'nationCommand' + year + month + generalID + commandKey`.
   - `processNationCommand()` checks conditions, term stack, `run()`.
   - If `run()` fails and `getAlternativeCommand()` exists, it retries.
   - Updates `nation_env` with `LastTurn` result.
7. **Execute general command**
   - Load from `general_turn` (`turn_idx = 0`), default to `휴식`.
   - RNG seed: `hiddenSeed + 'generalCommand' + year + month + generalID + commandKey`.
   - `processCommand()` checks conditions, term stack, `run()`.
   - Updates `killturn` based on NPC status, autorun, or `휴식`.
8. **Queue maintenance & turntime**
   - `pullNationCommand()` / `pullGeneralCommand()` advance queues.
   - `updateTurnTime()` handles deletion/retirement and sets next `turntime`.
   - Persist via `General::applyDB()`.
9. **LastTurn persistence**
   - General `LastTurn` lives in `general.last_turn` JSON.
   - Nation `LastTurn` is stored in `nation_env` under `turn_last_{officer_level}`.
   - `LastTurn.term` only advances when the same command + arg repeats across turns.

## Command Semantics (BaseCommand)

- **Constraints**: permission/min/full conditions are evaluated via
  `Constraint::testAll()` with `general/city/nation/dest*` context.
- **Pre-turn requirement**: `getPreReqTurn()` uses `LastTurn` to accumulate
  term stack (`addTermStack()`), logging "수행중" until the term completes.
- **Post-turn requirement**: `getPostReqTurn()` uses `next_execute` storage
  (general: `next_execute`, nation: `nation_env`) to block early execution.
- **Run flow**: `run($rng)` returns `true` when completed; `false` can chain to
  `getAlternativeCommand()`.

## Turntime & Lifecycle (`updateTurnTime`)

- **Inactivity**: if `killturn <= 0`:
  - NPC-owned characters can detach (owner -> NPC) if `deadyear` not reached.
  - Otherwise the general is deleted (`kill()`).
- **Retirement**: if `age >= retirementYear` and not NPC, `CheckHall()` and
  `rebirth()` are invoked.
- **Scheduling**: next `turntime` is `addTurn(current, turnterm)` with
  optional `nextTurnTimeBase` override (aux var).

## killturn, block, autorun

- `killturn` acts as an inactivity counter; it is reset to `game_env.killturn`
  when a non-rest player action completes, and decremented for NPCs, autorun,
  rest commands, or block states.
- `block >= 2` forces `killturn` decrement and logs a block message.
- When `killturn <= 0`, the general is converted to NPC (owner removed) or
  deleted depending on NPC type and `deadyear`.
- `autorun_limit` is stored in general `aux` and used to decide AI takeover.

## Deterministic RNG Seeds

RNG uses `LiteHashDRBG` with `UniqueConst::$hiddenSeed`:

- Preprocess: `('preprocess', year, month, generalID)`
- Nation command: `('nationCommand', year, month, generalID, commandKey)`
- General command: `('generalCommand', year, month, generalID, commandKey)`
- Monthly: `('monthly', year, month)`

These seeds make command outcomes and monthly updates reproducible for
validation.

## Open Questions / Follow-ups

- `preUpdateMonthly()`, `postUpdateMonthly()`, `turnDate()`, `checkStatistic()`
  are defined outside this flow; see `legacy/hwe/func_time_event.php` for
  economic updates and population calculations.
- `updateTraffic()`, `checkDelay()`, `updateOnline()` logic lives in
  `legacy/hwe/func.php` and related utility files.
