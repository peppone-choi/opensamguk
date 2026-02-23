# Legacy Economy and Monthly Updates

This document summarizes the legacy economic pipeline: monthly income,
maintenance, population growth, city supply recalculation, and nation level
updates. References include `legacy/hwe/func_time_event.php`,
`legacy/hwe/sammo/Event/Action/*`, and `legacy/hwe/sammo/TurnExecutionHelper.php`.

## Entry Points

- `TurnExecutionHelper::executeAllCommand()`
  - Calls `runEventHandler(PRE_MONTH)` → `preUpdateMonthly()` → `turnDate()` →
    `runEventHandler(MONTH)` → `postUpdateMonthly()`.
- Event actions under `legacy/hwe/sammo/Event/Action/` execute concrete
  economic updates.

## Monthly Income (ProcessIncome)

`Event/Action/ProcessIncome` distributes **gold** and **rice** on schedule:

- Collects all nations and cities, groups generals per nation.
- Computes income using:
  - `getGoldIncome()` / `getRiceIncome()` from `func_time_event.php`.
  - `getWallIncome()` for rice from walls (same file).
- Applies nation tax rate (`rate_tmp`) and salary ratio (`bill`).
- Pays generals by `dedication` via `getBill()`.
- Updates `nation_env.prev_income_{gold|rice}` and logs to history.

Key dependencies:

- `GameConst::$basegold`, `GameConst::$baserice` for minimum reserves.
- `GameConst::$resourceActionAmountGuide` for action amounts.
- `iAction::onCalcNationalIncome()` for nation-type modifiers.

## Semiannual Maintenance (ProcessSemiAnnual)

`Event/Action/ProcessSemiAnnual` applies upkeep and growth:

- City stats decay by 1% (agri/comm/secu/def/wall).
- Population growth via `popIncrease()`:
  - Uses tax rate (`rate_tmp`) and security.
  - Nation type can adjust via `onCalcNationalIncome('pop', ...)`.
- General and nation resources decay (1–5% bands based on thresholds).

## War Income and Casualties (ProcessWarIncome)

`Event/Action/ProcessWarIncome`:

- Converts `city.dead` into national gold income (supply-based war income).
- Returns 20% of `dead` to population (`pop += dead * 0.2`), then clears `dead`.

## City Supply and Isolation (UpdateCitySupply)

`Event/Action/UpdateCitySupply`:

- BFS from each nation capital through `CityConst` paths to mark `supply = 1`.
- Unsupplied cities (`supply = 0`) receive 10% stat/pop loss monthly.
- Generals in unsupplied cities lose 5% crew/train/atmos.
- Cities with `trust < 30` become neutral (nation = 0), and officers are reset.

## Nation Level Updates (UpdateNationLevel)

`Event/Action/UpdateNationLevel`:

- Nation level increases based on number of level≥4 cities.
- On level-up:
  - Grants gold/rice bonus.
  - Logs global and national history.
  - Initializes `nation_turn` rows for new chief levels.
  - Runs a unique-item lottery (deterministic RNG tagged `nationLevelUp`).

## City Trade Rates (RandomizeCityTradeRate)

- Once per month, adjusts `city.trade` with deterministic RNG:
  - Probability depends on city level.
  - Trade rate ranges 95–105 when triggered.

## RNG Notes

Economic events using RNG seed with `UniqueConst::$hiddenSeed` and a
feature-specific tag:

- `RandomizeCityTradeRate`: `hiddenSeed + 'randomizeCityTradeRate' + year + month`
- `UpdateNationLevel`: `hiddenSeed + 'nationLevelUp' + year + month + nationID`

## Open Questions / Follow-ups

- `preUpdateMonthly()`, `postUpdateMonthly()`, and `turnDate()` live in
  `legacy/hwe/func_time_event.php` and `legacy/hwe/func.php`; their full
  side effects should be expanded alongside this document.
