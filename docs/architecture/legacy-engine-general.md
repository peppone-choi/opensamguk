# Legacy General Model and Action Stack

This document captures how `General` aggregates modifiers, computes stats, and
exposes trigger hooks. The main reference is `legacy/hwe/sammo/General.php`.

## Construction and Core State

- `General` builds from raw DB rows (general, city, nation, rank, access log).
- `last_turn` is parsed into `LastTurn`, and a separate `resultTurn` is
  maintained for updates.
- `penalty` JSON is decoded into `PenaltyKey -> value` map.
- `aux` JSON is lazily decoded and only written when aux values change.
- Rank data is tracked with:
  - `rankVarRead` (current read view)
  - `rankVarIncrease` (increment queue)
  - `rankVarSet` (explicit set queue)

## Action Objects (`iAction`)

`General` composes action sources into a single list:

1. Nation type (`buildNationTypeClass`)
2. Officer level (`TriggerOfficerLevel`)
3. Domestic special (`buildGeneralSpecialDomesticClass`)
4. War special (`buildGeneralSpecialWarClass`)
5. Personality (`buildPersonalityClass`)
6. Crew type (`GameUnitConst::byID`)
7. Inheritance buff (`TriggerInheritBuff`)
8. Scenario effect (`buildScenarioEffectClass`)
9. Items: horse / weapon / book / item (`buildItemClass`)

`getActionList()` returns this merged list and is the core entry point for
modifiers and triggers.

## Stat Computation

`getStatValue()` applies layered modifiers:

- Base stat from raw value
- Injury multiplier
- Cross-stat adjust (strength/intel + 1/4 of the other stat)
- Clamp to `GameConst::$maxLevel`
- `onCalcStat()` from every action object
- Cached by `(statName, injury, action, adjust)` tuple

Convenience wrappers:

- `getLeadership()`, `getStrength()`, `getIntel()`

## Turn Reservation and LastTurn

- `getReservedTurn(turnIdx)` reads from `general_turn`.
- When no entry exists, `buildGeneralCommandClass(null)` creates `휴식`.
- `LastTurn` holds `{ command, arg, term, seq }` and is used by
  `BaseCommand::addTermStack()` to advance multi-turn commands.
- `applyDB()` persists updated fields and writes `last_turn` if it changed.

## Aux and Penalty Behavior

- `aux` is loaded on demand (`unpackAux()`), and `setAuxVar()` writes through to
  `general.aux` only when values changed.
- Setting an aux value to `null` deletes the key from JSON.
- Command constraints that require aux values trigger `General::unpackAux()`
  before evaluation.
- `penalty` is used in both `General` logic (AI and permission checks) and
  API flows (messages, nation tools); runtime expiration is not automatic.

## Trigger and Modifier Hooks

`General` delegates calculations and trigger lists through action objects:

- Economy/strategy: `onCalcDomestic`, `onCalcStrategic`,
  `onCalcNationalIncome`
- Combat modifiers: `onCalcStat`, `onCalcOpposeStat`, `getWarPowerMultiplier`
- Arbitrary actions: `onArbitraryAction` (used in city conquest)

Turn-prep triggers:

- `getPreTurnExecuteTriggerList()` merges trigger lists from every action
  object. `TurnExecutionHelper::preprocessCommand()` adds
  `che_부상경감` + `che_병력군량소모` on top.

Battle triggers:

- `getBattleInitSkillTriggerList()` merges per-action battle-init triggers.
- `getBattlePhaseSkillTriggerList()` includes base phase triggers:
  - `che_필살시도`, `che_필살발동`
  - `che_회피시도`, `che_회피발동`
  - `che_계략시도`, `che_계략발동`, `che_계략실패`
  - plus any action-specific additions.

## Rank and Access Log Updates

- `increaseRankVar()` / `setRankVar()` queue updates until `applyDB()`.
- `applyDB()` updates `general` and `rank_data`, then flushes logs.
- `checkStatChange()` uses `*_exp` thresholds to increase/decrease stats and
  logs level changes.

## Open Questions / Follow-ups

- `PenaltyKey` effects and how penalties are applied are outside this file.
- `GeneralBase` and `LazyVarAndAuxUpdater` may contain additional state
  conventions for new code to mirror.
