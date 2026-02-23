# Legacy Inheritance Points (유산 포인트)

This document describes the cross-season inheritance point system, how points
are accumulated, merged, and spent, and which gameplay effects they unlock.
References include `legacy/hwe/sammo/InheritancePointManager.php`,
`legacy/hwe/sammo/API/InheritAction/*`, `legacy/hwe/sammo/TriggerInheritBuff.php`,
`legacy/hwe/sammo/API/General/Join.php`, and `legacy/hwe/sql/schema.sql`.

## Overview

- Inheritance points are **user-level, cross-game currency**.
- Points are stored in KV storage under `inheritance_{userID}` and are **not
  cleared on season reset** (`legacy/hwe/sql/reset.sql` does not drop
  `storage`).
- The current spendable balance is stored in the `previous` key.

## Storage and Persistence

- KV storage: `storage` table with `namespace = inheritance_{userID}`.
- Snapshot table: `inheritance_result` records per-season snapshots
  `{server_id, owner, general_id, year, month, value}`.
- Logs: `user_record` with `log_type = inheritPoint`.

`InheritancePointManager::applyInheritanceUser()` merges all stored point
entries into a single total and writes it back to `previous`.

## Point Types and Sources

The key list is defined in `InheritanceKey` and configured in
`InheritancePointManager`. Each key has a source and a coefficient.

Directly stored keys (`storeType = true`):

- `previous` (기존 보유): current spendable balance.
- `lived_month`: +1 per executed turn (`TurnExecutionHelper::executeGeneralCommandUntil`).
- `max_domestic_critical`: updated via `updateMaxDomesticCritical()` in
  `legacy/hwe/func_gamerule.php`.
- `active_action`: incremented by many commands (e.g. `che_출병`, `che_거병`,
  `che_건국`, various nation commands). Coefficient = 3.
- `unifier`: points from unification events (e.g. `che_건국` +250,
  `check_united()` awards +2000 for high officers).<br>
- `tournament`: awarded in tournament routines (`legacy/hwe/func_tournament.php`).

Computed keys (`storeType = false` or derived):

- `combat`: from `rank_data.warnum` (war engagements) \* 5.
- `sabotage`: from `rank_data.firenum` (strategy success) \* 20.
- `dex`: sum of arm-type dex, with overflow reduction, \* 0.001.
- `betting`: `betwin * 10 * (betwingold / betgold)^2`.
- `max_belong`: max of `general.belong` and aux `max_belong`, \* 10.

Notes:

- NPCs (`npc >= 2`) do not earn points.
- If `game_env.isunited != 0`, only `previous` remains available; other keys
  are suppressed for calculation/spending.

## Merge and Apply Lifecycle

The system uses a two-step merge:

1. **Merge** (`mergeTotalInheritancePoint`)
   - Computes derived keys and stores them into `inheritance_{userID}`.
   - Records a snapshot into `inheritance_result`.
2. **Apply** (`applyInheritanceUser`)
   - Sums all keys into a total, logs to `user_record`, resets storage to only
     `previous` (plus any rebirth-kept keys).

This merge/apply is triggered in these places:

- **End of season**: `check_united()` calls merge/apply for all player
  generals when the game is unified.
- **Death**: `General::kill()` merges and applies for the owner and refunds
  pending inherit actions (random unique, reserved special war).
- **Rebirth**: `General::rebirth()` merges and applies with reduced retention
  (`rebirthStoreCoeff`).

## Spending and Effects

### Join-time bonuses (`API/General/Join.php`)

Players can spend points during character creation:

- Choose a war special (`inheritSpecial`)
- Select a starting city (`inheritCity`)
- Set a turn-time zone (`inheritTurntimeZone`)
- Add bonus stats (`inheritBonusStat`)

Costs are defined in `GameConst`:

- `inheritBornSpecialPoint`
- `inheritBornCityPoint`
- `inheritBornTurntimePoint`
- `inheritBornStatPoint`

### InheritAction APIs (`API/InheritAction/*`)

- `BuyHiddenBuff`: buys a hidden buff level (1..5) stored in aux
  `inheritBuff` (cost curve `inheritBuffPoints`).
- `SetNextSpecialWar`: reserves the next war special (`inheritSpecificSpecialWar`).
- `ResetSpecialWar`: clears current war special (cost grows by
  `inheritResetAttrPointBase`, Fibonacci-extended).
- `ResetTurnTime`: randomizes turn time offset for future turns
  (`nextTurnTimeBase`).
- `ResetStat`: reassigns base stats; optional bonus stats cost
  `inheritBornStatPoint`.
- `BuyRandomUnique`: flags `inheritRandomUnique` to guarantee a unique drop
  once unique items become available (see below).
- `CheckOwner`: reveals another general's owner name (cost
  `inheritCheckOwnerPoint`).

All of these are disabled once `game_env.isunited` is set.

### Unique items and auctions

- **Random unique**: `inheritRandomUnique` is consumed in
  `tryUniqueItemLottery()` when unique items are available. If no items are
  available, points are refunded.
- **Unique auctions**: `AuctionUniqueItem` uses `inheritPoint` as the bid
  currency. Opening an auction requires `inheritItemUniqueMinPoint`.

### Betting rewards

When betting is configured with `reqInheritancePoint`, rewards are paid
directly into `previous` (`Betting::giveReward()`), and logged to
`user_record`.

## Hidden Buff System (inheritBuff)

The `inheritBuff` aux value is injected into `General` as a `TriggerInheritBuff`
action. It modifies:

- Combat chances: avoid/critical/strategy trial ratios
- Domestic success/fail probabilities
- Opponent combat chances (negative modifiers)

Buff levels are 0..5 and multiply coefficients by 0.01 per level.

## Rank Tracking

`MergeInheritPointRank` event aggregates all keys (except `previous`) into
`rank_data.inherit_earned_dyn`, and then updates:

- `inherit_earned` (sum of earned)
- `inherit_spent` (from `inherit_spent_dyn`)

This is used for leaderboard-style tracking.

## Related Constants

Key cost and limits (from `GameConstBase` / scenario overrides):

- `inheritBuffPoints` (level costs)
- `inheritSpecificSpecialPoint`
- `inheritResetAttrPointBase`
- `inheritCheckOwnerPoint`
- `inheritItemRandomPoint`
- `inheritItemUniqueMinPoint`
- `minMonthToAllowInheritItem`

Scenario files can override these via `const` in `scenario_*.json`.
