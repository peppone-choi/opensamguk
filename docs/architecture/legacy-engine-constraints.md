# Legacy Constraint System

This document describes the constraint framework used to validate command
preconditions. References include `legacy/hwe/sammo/Constraint/Constraint.php`
and `legacy/hwe/sammo/Constraint/ConstraintHelper.php`.

## Core Concepts

Constraints are reusable predicate classes that validate a command’s inputs and
state. They are built per-command and executed in order:

- Each constraint extends `Constraint` and implements `test()`.
- `Constraint::testAll()` iterates a list of constraints and returns the first
  failure `[constraintName, reason]` or `null` for success.
- `BaseCommand::hasFullConditionMet()` uses these to decide command validity.

## Required Input Flags

Constraints declare required inputs using bit flags:

- `REQ_GENERAL`, `REQ_CITY`, `REQ_NATION`
- `REQ_DEST_GENERAL`, `REQ_DEST_CITY`, `REQ_DEST_NATION`
- `REQ_ARG` with typed sub-flags:
  - `REQ_STRING_ARG`, `REQ_INT_ARG`, `REQ_NUMERIC_ARG`, `REQ_BOOLEAN_ARG`,
    `REQ_ARRAY_ARG`, `REQ_BACKED_ENUM_ARG`

`Constraint::checkInputValues()` enforces these expectations and throws if
inputs are missing or malformed.

Some constraints expect `general.aux` to be preloaded as `auxVar`. Command
evaluation calls `General::unpackAux()` before constraint checks to satisfy
these requirements.

## Constraint Helper DSL

`ConstraintHelper` provides factory-style helpers used in command definitions:

- Examples: `AllowWar()`, `NearCity($distance)`, `ReqGeneralGold($amount)`,
  `NotOccupiedDestCity()`, `AllowDiplomacyStatus(...)`, etc.
- These helpers return `[ConstraintName, arg]` tuples consumed by
  `Constraint::testAll()`.

## Common Constraint Classes

Constraints are organized by domain:

- **Diplomacy**: `AllowDiplomacyStatus`, `AllowDiplomacyBetweenStatus`.
- **Nation/City**: `OccupiedCity`, `NotCapital`, `RemainCityCapacity`.
- **General**: `ReqGeneralCrew`, `ReqGeneralGold`, `MustBeTroopLeader`.
- **Routing**: `HasRoute`, `HasRouteWithEnemy`.

Each constraint sets a failure reason string used by UI and logs.

## Rewrite References

Rewrite constraint contracts and runtime split details live in
`docs/architecture/rewrite-constraints.md`.

## Open Questions / Follow-ups

- Some constraints rely on `env` values (`turnterm`, `year`, etc.); document
  each command’s exact `env` payload when porting.
