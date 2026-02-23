# Legacy Game Constants and Unit Sets

This document summarizes the static configuration layers that define game
rules and unit sets. References include `legacy/hwe/sammo/GameConstBase.php`,
`legacy/hwe/sammo/GameUnitConstBase.php`, `legacy/hwe/sammo/GameUnitDetail.php`,
and `legacy/hwe/sammo/GameUnitConstraint/*`.

## GameConst (Rules)

`GameConstBase` defines the default rule set:

- Turn limits, stat caps, and progression thresholds.
- Resource floors (`basegold`, `baserice`) and maintenance coefficients.
- Command lists (`availableGeneralCommand`, `availableChiefCommand`).
- Availability lists for specials, items, and nation types.
- Scenario overrides apply via `Scenario::buildConf()`.

During scenario build, the engine generates `d_setting/GameConst.php` from
`GameConstBase` and scenario overrides (see
`docs/architecture/legacy-scenarios.md`).

## Unit Sets (GameUnitConst)

`GameUnitConstBase` defines the base unit list and their combat properties:

- Unit identifiers, arm types, and labels.
- Base attack/defence stats, speed, avoid, magic coefficients.
- Cost and rice consumption.
- Attack/defence coefficients vs. other arm types.
- Attached trigger lists (`initSkillTrigger`, `phaseSkillTrigger`).
- Requirements via `GameUnitConstraint` objects.

Scenario build emits `d_setting/GameUnitConst.php` based on the selected
`unitSet` (`scenario/unit/*.php`).

## GameUnitDetail

`GameUnitDetail` is the runtime representation of a crew type:

- Computes derived attack/defence using general stats and tech (`getTechAbil`).
- Calculates resource costs (`costWithTech`, `riceWithTech`).
- Applies attack/defence multipliers by opponent crew type.
- Can include an `iActionList` (crew-type-specific action modules).

Crew types are also `iAction` providers, enabling triggers and stat modifiers
through `General::getActionList()`.

## Unit Constraints

Unit availability is controlled by `GameUnitConstraint` implementations:

- `ReqTech`, `ReqCities`, `ReqRegions`, `ReqMinRelYear`, `Impossible`, etc.
- Each constraint exposes `test(...)` and `getInfo()` for UI display.
- `ReqNationAux` gates unit availability by `nation.aux` flags (e.g. research
  unlocks like `can_화시병사용`), which are written by nation commands.

## Open Questions / Follow-ups

- Scenario-specific overrides may redefine available units or unit sets;
  document those deltas per scenario when needed.
