# Legacy Battle and War Resolution

This document summarizes the legacy battle pipeline centered on
`legacy/hwe/process_war.php`, `legacy/hwe/sammo/WarUnit.php`,
`legacy/hwe/sammo/WarUnitGeneral.php`, and `legacy/hwe/sammo/WarUnitCity.php`.

## Entry Point: `processWar`

Inputs:

- `warSeed` (string): battle RNG seed
- `attackerGeneral`: `General`
- `rawAttackerNation`: nation row (snapshot)
- `rawDefenderCity`: city row (snapshot)

Setup steps:

1. Initialize RNG with `warSeed` (`LiteHashDRBG`).
2. Load defender nation row (or default neutral nation if `nation = 0`).
3. Build `WarUnitGeneral` (attacker) and `WarUnitCity` (defender city).
4. Collect defender generals in the city, filter with `extractBattleOrder()`.
5. Optionally append city as defender if city order > 0.
6. Sort defenders by battle order, iterate via `getNextDefender()`.
7. Run `processWar_NG()`; update DB and nation/city stats.

## Battle Order (`extractBattleOrder`)

For defender generals:

- Must have crew > 0
- Must have rice > crew/100
- `train` and `atmos` must meet `defence_train`
- Battle order uses:
  - `totalStat = (realStat + fullStat) / 2`
  - `totalCrew = crew / 1_000_000 * (train * atmos) ^ 1.5`
  - `totalStat + totalCrew / 100`

For cities:

- Uses attacker `onCalcOpposeStat('cityBattleOrder', -1)`.

## Battle Loop (`processWar_NG`)

1. Log start, include seed in battle logs.
2. If no defenders remain, set defender = city and switch to siege.
3. **Initial engagement**
   - `setOppose()` for attacker/defender
   - `addTrain(1)` for both
   - Fire battle-init triggers
4. **Per phase**
   - `beginPhase()` computes war power
   - Fire battle-phase triggers
   - `calcDamage()` on both sides
   - Clamp damage if it exceeds HP ratios
   - Apply damage, increase killed/dead counters
   - Log phase results
5. **Continuation checks**
   - `continueWar()` fails on no rice or HP <= 0
   - On retreat/defeat: log, apply win/lose, try wound
   - If defender removed, move to next defender (or city siege)
6. **Finish**
   - `logBattleResult()` for last phase if needed
   - `finishBattle()` for attacker/defender
   - City conflict tracking and history logs

## Post-Battle Updates (`processWar`)

After `processWar_NG()`:

- Apply attacker DB updates
- Update nation rice (supply and siege rules)
- Distribute city `dead` counts (40% attacker city, 60% defender city)
- Increase nation tech based on killed/dead and nation size adjustments
- Update `diplomacy.dead` for both sides
- If city conquered: call `ConquerCity()`

## Conflict Tracking (`city.conflict`)

City conflict tracks which nations contributed to siege damage, used to
resolve post-war ownership when multiple attackers participate.

Source: `WarUnitCity::addConflict()` in `legacy/hwe/sammo/WarUnitCity.php`.

- `city.conflict` is a JSON map `{ nationID: deadContribution }`.
- Contribution amount is based on city `dead` (minimum 1).
- First/last hit bonus: if no conflict exists yet or city HP is 0, `dead` is
  multiplied by 1.05 ("선타, 막타 보너스").
- Contributions are sorted descending via `arsort()` after updates.
- `addConflict()` returns `true` when a new nation enters the conflict, which
  triggers the global "분쟁" log in `processWar()`.
- `getConquerNation()` returns the first key of the sorted map to select the
  final owner.
- `DeleteConflict($nation)` removes a nation from all city conflicts, used on
  nation deletion and on the `che_방랑` flow.

## City Conquest Resolution (`ConquerCity`)

`ConquerCity()` finalizes city ownership, handles nation collapse, and applies
post-siege side effects. It is deterministic with the conquest RNG seed noted
below.

### Common Flow

- Logs conquest to attacker (general/nation/global) and defender nation history.
- Runs `EventTarget::OCCUPY_CITY` handlers via `TurnExecutionHelper::runEventHandler()`.
- Calls `onArbitraryAction(..., 'ConquerCity')` for each defender general in
  the city, then persists them.

### Nation Collapse Path

Triggered when the defender nation owns exactly one city (the captured city):

- Calls `deleteNation()` using the defender lord (officer level 12).
- All defender generals lose 20–50% of gold and rice, -10% experience, and
  -50% dedication, with action logs. Loss amounts are aggregated.
- Optionally issues scout messages to fleeing generals (when `join_mode` allows).
- NPC defenders (NPC type 2–8 except 5) can auto-queue `che_임관` to the
  attacker nation with a random delay (0–12 turns), gated by
  `GameConst::$joinRuinedNPCProp`.
- Attacker reward:
  - Half of defender nation gold/rice above base (`GameConst::$basegold`,
    `GameConst::$baserice`) plus half of the aggregated general losses.
  - Credited to attacker nation and logged to all chiefs (officer level >= 5).
- Runs `EventTarget::DESTROY_NATION` handlers.

### Nation Survives Path

If the defender nation still has other cities:

- Demotes city officers (태수/군사/종사) to general:
  - `officer_level = 1`, `officer_city = 0`.
- If the city was the capital:
  - Picks a new capital via `findNextCapital()` (closest distance, highest pop).
  - Logs an emergency relocation message to global and all nation generals.
  - Sets `nation.capital` to new city and halves nation gold/rice.
  - Marks new capital as supply city; moves chiefs to it.
  - Applies 20% morale loss to all generals (`atmos *= 0.8`).
  - Refreshes cached nation static info.

### Final Ownership + City Reset

`getConquerNation()` inspects `city.conflict` to decide final owner. If the
attacker loses arbitration, the city is transferred to the conflict winner
and logs are emitted for both nations.

City stats are reset after ownership is settled:

- `supply = 1`, `term = 0`, `conflict = {}`, `nation = conquerNation`,
  `officer_set = 0`.
- `agri/comm/secu` multiplied by 0.7.
- `def/wall` reset:
  - If `level > 3`: both set to `GameConst::$defaultCityWall`.
  - Else: set to `def_max/2`, `wall_max/2`.
- Frontline status recalculated for all nearby nations (`SetNationFront()`).

### Deterministic RNG

Conquest RNG seed:
`hiddenSeed + 'ConquerCity' + year + month + attackerNationID + attackerID + cityID`.

Used for:

- Defender general loss ratios (20–50%).
- Scout message chance and NPC auto-join chance.
- Randomized join turn delay (0–12).

## `WarUnitGeneral` Highlights

- Train/atmos bonuses depend on city level and attacker/defender role.
- War power:
  - Base attack/defence from crew type + tech
  - Adjusted by train/atmos, dex (`getDex()`), crew type coefficients
  - Experience level scales war power and counter-scales opponent
  - `General::getWarPowerMultiplier()` applies special multipliers
- Rice consumption on kills: proportional to damage, tech cost, unit rice
- Wound chance: 5% unless `부상무효` / `퇴각부상무효` triggered
- `finishBattle()` updates rank stats, rounds values, and checks stat changes

## `WarUnitCity` Highlights

- Uses `DummyGeneral` with `CREWTYPE_CASTLE`
- HP = `def * 10`
- Computed attack/defence = `(def + wall * 9) / 500 + 200`
- City train/atmos scales with elapsed years since `startYear`
- Siege state:
  - Non-siege battle ends after one exchange
  - Siege continues until HP <= 0
- `heavyDecreaseWealth()` halves `agri/comm/secu` on supply-based rout
- `addConflict()` records contribution in `city.conflict` JSON

## Deterministic RNG

- Main battle uses `warSeed` directly.
- City conquest uses `hiddenSeed + 'ConquerCity' + year + month + nationID + generalID + cityID`.

## Open Questions / Follow-ups

- No automatic decay for `city.conflict` is visible; confirm if any scheduled
  cleanup exists outside explicit reset paths.
