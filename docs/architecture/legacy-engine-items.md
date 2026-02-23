# Legacy Items, Traits, and Action Modules

This document summarizes the legacy action modules that supply modifiers and
triggers: items, specials, personalities, nation types, crew types, and
scenario effects. Primary references include `legacy/hwe/sammo/iAction.php`,
`legacy/hwe/sammo/BaseItem.php`, `legacy/hwe/sammo/BaseSpecial.php`,
and `legacy/hwe/sammo/Action*/` directories.

## Core Concept: `iAction`

All traits/items/effects implement `iAction` and are merged by
`General::getActionList()` (see `docs/architecture/legacy-engine-general.md`).
They can:

- Modify stats and command calculations (`onCalcStat`, `onCalcDomestic`, etc.).
- Provide triggers (`getPreTurnExecuteTriggerList`, battle init/phase triggers).
- Apply ad-hoc effects (`onArbitraryAction`).

`DefaultAction` provides no-op implementations for all hooks.

## Item System

- Base class: `BaseItem` implements `iAction` and exposes:
  - `getCost()`, `isConsumable()`, `isBuyable()`, `getReqSecu()`.
- Stat items inherit `BaseStatItem` and apply a fixed stat bonus in
  `onCalcStat()` based on class name tokens.
- Item instances live on the general (`General::getItem()`); items can also
  insert battle triggers (see `ActionItem/`).

Items are configured via `GameConst::$allItems` and scenario overrides.

## Specials (Domestic / War)

- Base class: `BaseSpecial` (implements `iAction`).
- `ActionSpecialDomestic/*` modifies domestic outcomes
  (`onCalcDomestic`), e.g., cost/success/score adjustments.
- `ActionSpecialWar/*` injects battle triggers or stat modifiers.
- Weighting and eligibility are managed by `SpecialityHelper`:
  - Uses `selectWeight` + stat/arm constraints to choose specials.
  - Deterministic RNG affects special selection for NPCs or resets.

## Personalities

- Stored in `ActionPersonality/*` and also implement `iAction`.
- Typically adjust core stats or apply small domestic bonuses.

## Nation Types

- `ActionNationType/*` extends `BaseNation`.
- Provide global modifiers to income and domestic outcomes.
- Injected per-nation via `buildNationTypeClass()`.

## Crew Types (Units as Actions)

- `ActionCrewType/*` defines per-crew `iAction` additions.
- Crew types can alter battle order, apply triggers, or modify war stats.
- For example, `che_성벽선제` forces city battle order to a high value.

## Scenario Effects

- `ActionScenarioEffect/*` provides scenario-wide modifiers
  (e.g., attacker advantage, trigger overrides).
- Bound to `GameConst::$scenarioEffect` during scenario build.

## Interaction with Triggers

Action modules often construct trigger callers:

- Items and war specials can return `WarUnitTriggerCaller` with `BaseWarUnitTrigger`.
- Some item triggers use `raiseType` to allow coexistence with non-item versions.

See `docs/architecture/legacy-engine-triggers.md` for trigger execution rules.

## Open Questions / Follow-ups

- The precise selection rules for personalities and specials depend on
  `SpecialityHelper` RNG thresholds and scenario overrides; capture those
  when documenting specific rule packs.
