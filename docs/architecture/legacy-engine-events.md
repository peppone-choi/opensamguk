# Legacy Event System

This document summarizes how the legacy event system executes scenario- and
turn-based events, and how static event hooks are wired into commands. Primary
references include `legacy/hwe/sammo/TurnExecutionHelper.php`,
`legacy/hwe/sammo/Event/*`, and `legacy/hwe/sammo/StaticEventHandler.php`.

## Entry Points

- `TurnExecutionHelper::runEventHandler(EventTarget $eventTarget)`
  - Loads `event` table rows by target and priority, evaluates conditions, and
    runs actions.
- `StaticEventHandler::handleEvent(...)`
  - Invoked by many commands and API handlers to run per-action static hooks.

## Event Table Schema

`event` rows are stored in the legacy DB schema (`legacy/hwe/sql/schema.sql`):

- `id`: auto-increment primary key
- `target`: enum of `PRE_MONTH`, `MONTH`, `OCCUPY_CITY`, `DESTROY_NATION`, `UNITED`
- `priority`: higher first (default 1000)
- `condition`: JSON array (condition DSL)
- `action`: JSON array (action DSL)

Indexes: `(target, priority, id)` for dispatch ordering. Both `condition` and
`action` are JSON-validated by DB constraints.

## Event Table Dispatch

`runEventHandler()` drives the dynamic event pipeline:

1. Query `event` rows with `target = {PRE_MONTH|MONTH|OCCUPY_CITY|DESTROY_NATION|UNITED}`
   (ordered by `priority DESC, id ASC`).
2. Decode `condition` and `action` JSON.
3. Build a `Event\EventHandler` with condition + action lists.
4. Execute `tryRunEvent($env)` where `$env` is `game_env` KV storage plus
   `currentEventID`.

Events are used inside the monthly pipeline and in special moments like
city occupation (`EventTarget::OCCUPY_CITY`, called by some commands).

## Condition and Action DSL

`Event\Condition::build()` and `Event\Action::build()` decode JSON arrays into
class instances:

- **Condition**
  - Supports logic combinators (`and`, `or`, `xor`, `not`) via
    `Event\Condition\Logic`.
  - Built-in condition types include:
    - `Date`, `DateRelative`, `Interval`
    - `RemainNation`
    - `ConstBool`
  - Conditions return `{ value, chain }` for tracing.

- **Action**
  - Actions are classes under `Event/Action/` with `run(array $env)`.
  - The dispatcher instantiates them from `action` arrays like
    `['ProcessIncome', 'gold']`.

## Common Event Actions (Examples)

These are the action modules observed in the legacy tree:

- **Economy & upkeep**: `ProcessIncome`, `ProcessSemiAnnual`, `ProcessWarIncome`
- **World state**: `UpdateCitySupply`, `UpdateNationLevel`, `RandomizeCityTradeRate`
- **NPC/Invader flow**: `RaiseInvader`, `RaiseNPCNation`, `ProvideNPCTroopLeader`
- **Betting & unique items**: `OpenNationBetting`, `FinishNationBetting`,
  `LostUniqueItem`, `MergeInheritPointRank`
- **Event lifecycle**: `DeleteEvent`, `NoticeToHistoryLog`

All action execution uses the event environment (`year`, `month`, `startyear`,
`turnterm`, etc.) coming from `game_env`.

## Static Events (Command Hooks)

Static events are hooks triggered directly by commands/APIs:

- `StaticEventHandler::handleEvent()` looks up handler names from
  `GameConst::$staticEventHandlers[$eventType]`.
- Handlers live under `legacy/hwe/sammo/StaticEvent/` and implement
  `BaseStaticEvent::run()`.
- These hooks are used to extend command behavior without modifying the
  command code itself (e.g., troop join/exit side effects).

### Static Handler Map Sources

`GameConst::$staticEventHandlers` defaults to an empty array in
`legacy/hwe/sammo/GameConstBase.php`. Scenario JSON can override it:

- `legacy/hwe/scenario/scenario_911.json` (only observed override in repo)
  - `sammo\\API\\Troop\\JoinTroop` → `event_부대탑승즉시이동`
  - `sammo\\Command\\Nation\\che_발령` → `event_부대발령즉시집합`

Static handler names should map to classes in `legacy/hwe/sammo/StaticEvent/`
(class name matches handler key).

## RNG Notes

Dynamic event actions can use deterministic RNG by constructing
`LiteHashDRBG` with `UniqueConst::$hiddenSeed` and an event-specific tag.
Examples include `RandomizeCityTradeRate` and `UpdateNationLevel`.

## Open Questions / Follow-ups

- `Event\Engine` is a stub with a TODO; it is not currently used in the main
  turn pipeline.
- Verify whether any runtime code injects additional static handlers beyond
  scenario JSON overrides.
