# Legacy Trigger System (iAction + Trigger Callers)

This document explains how the legacy Trigger system is built on `iAction`
and `TriggerCaller`, and how it affects both command outcomes and battle flow.
Primary references include `legacy/hwe/sammo/iAction.php`,
`legacy/hwe/sammo/TriggerCaller.php`, `legacy/hwe/sammo/General.php`,
`legacy/hwe/process_war.php`, and `legacy/hwe/sammo/WarUnit.php`.

## Core Concepts

### `iAction`: modifier hooks + trigger provider

`iAction` is the unified interface for traits, specials, crew types, items,
scenario effects, and hidden buffs. It provides:

- Modifier hooks (`onCalcDomestic`, `onCalcStat`, `onCalcOpposeStat`,
  `onCalcStrategic`, `onCalcNationalIncome`, `getWarPowerMultiplier`).
- Trigger hooks (`getPreTurnExecuteTriggerList`,
  `getBattleInitSkillTriggerList`, `getBattlePhaseSkillTriggerList`).
- Ad-hoc hooks (`onArbitraryAction`) for non-turn side effects.

Action sources are merged into a single list in `General::getActionList()`:

1. Nation type
2. Officer level
3. Domestic special
4. War special
5. Personality
6. Crew type (`GameUnitDetail`)
7. Inheritance buff (`TriggerInheritBuff`)
8. Scenario effect
9. Items

The order above is the execution order for modifier hooks, and also the merge
order for trigger lists (later actions override duplicates with the same
unique trigger ID).

Crew types (`GameUnitDetail`) are special:

- They implement `iAction`.
- They expose `initSkillTrigger` and `phaseSkillTrigger` arrays defined in
  scenario data (`buildWarUnitTriggerClass`).
- They can include an `iActionList` (crew-type-specific actions) which
  themselves implement `iAction`.

### `ObjectTrigger` and `TriggerCaller`

Triggers are small, prioritized actions:

- `ObjectTrigger` defines priorities and a single `action()` method.
- `TriggerCaller` groups triggers by priority, merges lists, and `fire()`s
  them in priority order.

Priority constants (lower runs earlier):

- `PRIORITY_BEGIN` = 10000
- `PRIORITY_PRE` = 20000
- `PRIORITY_BODY` = 30000
- `PRIORITY_POST` = 40000
- `PRIORITY_FINAL` = 50000

`TriggerCaller` uses a unique ID (`priority + class + object id`) for dedup.
`BaseWarUnitTrigger` extends this with `raiseType` to separate item-based
triggers from trait-based ones.

Specialized callers enforce type safety:

- `GeneralTriggerCaller` accepts `BaseGeneralTrigger`.
- `WarUnitTriggerCaller` accepts `BaseWarUnitTrigger`.

## Modifier Hooks (Command + Stat + War)

### `onCalcDomestic`

Used by general commands to adjust cost, success, failure, and score. Inputs:

- `turnType`: command key (`징병`, `조달`, `주민선정`, `정착장려`, 등).
- `varType`: `cost`, `rice`, `train`, `atmos`, `success`, `fail`, `score`.
- `aux`: extra context (ex: `armType` in `che_징병`).

Examples:

- `Command/General/che_징병.php` uses `cost`, `rice`, `train`, `atmos`.
- `Command/General/che_주민선정.php` uses `score`, `success`, `fail`.
- `GeneralTrigger/che_병력군량소모.php` uses `징집인구:score`.

### `onCalcStrategic`

Used by nation commands to adjust delays/limits:

- `varType` often `delay`, `globalDelay`, `strategic_cmd_limit`.
- Examples: `Command/Nation/che_급습.php`, `che_백성동원.php`, `che_수몰.php`.

### `onCalcNationalIncome`

Used by `Event/Action/ProcessSemiAnnual.php` to adjust population growth and
income ratios at the nation level. Nation types (e.g., 유가/법가/병가 계열)이
여기에서 보정을 걸어준다.

### `onCalcStat` / `onCalcOpposeStat`

Used for base stats and battle-derived parameters. Common `statName` keys:

- Base stats: `leadership`, `strength`, `intel` (General stat calc).
- Progression: `addDex`, `experience`, `dedication`.
- Battle timing: `initWarPhase` (phase count from `WarUnitGeneral`).
- Battle accuracy: `dex{armType}` (e.g., `dex2`).
- Train/atmos: `bonusTrain`, `bonusAtmos`.
- Combat odds: `warCriticalRatio`, `warAvoidRatio`.
- War magic: `warMagicTrialProb`, `warMagicSuccessProb`,
  `warMagicSuccessDamage`, `warMagicFailDamage`.
- Damage range: `criticalDamageRange` (in `WarUnit::criticalDamage()`).
- Supply cost: `killRice` (war rice consumption).
- Battle order: `cityBattleOrder` (opponent modifies city order).

`aux` carries context such as `isAttacker`, `opposeType`, or magic name
(`반목`, `화계`, etc.).

## Pre-Turn General Triggers

`TurnExecutionHelper::preprocessCommand()` runs pre-turn triggers:

1. `General::getPreTurnExecuteTriggerList()` merges triggers from all actions.
2. Base triggers are appended:
   - `GeneralTrigger/che_부상경감` (priority 10000 / BEGIN)
   - `GeneralTrigger/che_병력군량소모` (priority 50000 / FINAL)
3. `TriggerCaller::fire()` executes them before the command runs.

General triggers use `General::activateSkill()` for logging and gating.
`TurnExecutionHelper::processCommand()` clears activated skills after the
command completes.

## Battle Triggers

Battle triggers are fired in two stages inside `process_war.php`:

### Battle-init triggers

- Fired once per engagement when a defender is first set (`phase == 0`).
- Constructed via `General::getBattleInitSkillTriggerList()`.
- Used for start-of-battle setup (ex: `che_부상무효` from `견고`).

### Phase triggers

Every phase:

1. `WarUnit::beginPhase()` clears activated skills and recomputes war power.
2. `General::getBattlePhaseSkillTriggerList()` builds the trigger list.
   - Base triggers (always included):
     - `che_필살시도`, `che_필살발동`
     - `che_회피시도`, `che_회피발동`
     - `che_계략시도`, `che_계략발동`, `che_계략실패`
3. Attacker/defender trigger lists are merged and fired.
4. Damage is calculated using `getWarPower()` (after trigger multipliers).

### Attempt → Execute pattern (PRE/POST)

Most battle skills split into two triggers:

- **Attempt (PRE)**: check conditions, set flags or env payload.
- **Execute (POST)**: read env/flags and apply damage or status.

Examples:

- `WarUnitTrigger/che_저격시도.php` (PRE) sets `저격발동자`, wound ranges,
  then `che_저격발동.php` (POST) applies wounds and logs.
- `WarUnitTrigger/che_계략시도.php` (PRE) sets `magic` and success/failure,
  then `che_계략발동.php` / `che_계략실패.php` (POST) applies multipliers.
- `WarUnitTrigger/che_필살시도.php` → `che_필살발동.php` adjusts war power.

### Battle env and stop flags

`BaseWarUnitTrigger::action()` supplies a mutable env:

- `e_attacker` / `e_defender`: per-side state map.
- `stopNextAction`: if true, later triggers are skipped.

Triggers can return `false` from `actionWar()` to set `stopNextAction`.

### Item-based triggers and consumption

`BaseWarUnitTrigger` uses a `raiseType` bitmask to tag item-based triggers:

- `TYPE_ITEM`: item-triggered, with `아이템사용` skill gating.
- `TYPE_CONSUMABLE_ITEM`: consumes and deletes the item.
- `TYPE_DEDUP_TYPE_BASE`: offset for dedup grouping.

`processConsumableItem()` handles the consumption flow and logging. Item
triggers typically pass `raiseType` so the same skill can coexist with
non-item versions (see `ActionItem/che_저격_매화수전.php`).

## Interaction Notes for Porting

- Preserve `General::getActionList()` order; modifier hooks stack in sequence.
- Apply `onCalcOpposeStat` using the opponent's action list after the
  general's own `onCalcStat` adjustments.
- Keep priority-based execution (`PRIORITY_*`) and the PRE/POST split.
- Replicate trigger dedup semantics (`getUniqueID` + `raiseType`).
- Maintain `beginPhase()` clearing of activated skills; battle triggers assume
  per-phase activation.
- Ensure RNG usage stays deterministic (`RandUtil` everywhere in triggers).

## Related Files

- `legacy/hwe/sammo/iAction.php`
- `legacy/hwe/sammo/TriggerCaller.php`
- `legacy/hwe/sammo/ObjectTrigger.php`
- `legacy/hwe/sammo/General.php`
- `legacy/hwe/sammo/WarUnit.php`
- `legacy/hwe/process_war.php`
