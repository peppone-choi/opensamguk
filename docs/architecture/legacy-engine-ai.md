# Legacy General AI (GeneralAI)

This document summarizes how `legacy/hwe/sammo/GeneralAI.php` selects nation and
general commands, which data fields it relies on, and how policies shape NPC
behavior. It also outlines considerations for an in-memory rewrite of the AI
loop.

## Entry Points

- `chooseNationTurn(NationCommand $reservedCommand)`
- `chooseInstantNationTurn(NationCommand $reservedCommand)`
- `chooseGeneralTurn(GeneralCommand $reservedCommand)`

All three call `updateInstance()` first, which caches current state and derives
key AI decisions (diplomacy state, general type, policy setup).

## Deterministic RNG

`GeneralAI` uses `LiteHashDRBG` seeded with:

```
hiddenSeed + "GeneralAI" + year + month + generalID
```

This makes AI choices reproducible per turn. All random choices (`choice`,
`choiceUsingWeight`, `nextBool`) flow through this RNG.

## State Snapshot and Derived Fields

`updateInstance()` pulls and caches:

- `game_env` (via `KVStorage`): year, month, startyear, turnterm, develcost,
  init_year/init_month, killturn, global NPC policy defaults.
- `city` (current city row, from `general` -> `city`).
- `nation` (nation row, or fallback for neutral).
- `nation_env` (KVStorage): npc policy overrides, prev income, last attackable.
- `nation['aux']` decoded JSON.
- `general` primary stats (leadership/strength/intel; both full and adjusted).
- `baseDevelCost` and `maxResourceActionAmount`.
- `dipState`, `attackable`, `warTargetNation`.
- `genType` (type flags derived from stats + policy thresholds).

Derived groupings are computed lazily:

- `categorizeNationCities()` => `nationCities`, `frontCities`, `supplyCities`,
  `backupCities` with per-city `dev` and `important` scores.
- `categorizeNationGeneral()` => user/NPC buckets, war/civil buckets,
  troop leaders, lost generals, chief list.

## Diplomacy State (`calcDiplomacyState`)

Diplomacy state controls war/peace behavior:

- `d평화`: no war declarations, no active war.
- `d선포`: declaration in progress, no active war.
- `d징병`: pre-war recruitment window.
- `d직전`: immediate pre-war.
- `d전쟁`: active war, or recently lost a front (grace period).

Key inputs:

- `diplomacy` rows (`state`, `term`) for current nation.
- `front` cities with `supply=1`.
- `year/month` relative to `startyear` (early-game war lockout).

`warTargetNation` tracks eligible enemies:

- `2` = currently at war.
- `1` = declaration in progress / pre-war.
- `0` is used as "any neighbor" fallback when no active target exists.

## General Type (`calcGenType`)

General type is a bitmask:

- `t무장` (strength-leaning)
- `t지장` (intelligence-leaning)
- `t통솔장` (leadership threshold for war-capable NPCs)

Logic:

- Strength vs intel decides initial bias.
- If the weaker stat is within 80%, a probabilistic hybrid type can be added.
- `t통솔장` is set when `leadership >= nationPolicy->minNPCWarLeadership`.

## Policy Inputs (Autorun)

`AutorunNationPolicy` and `AutorunGeneralPolicy` are built from:

- per-user autorun options (`env['autorun_user']['options']`)
- `nation_env` overrides (`npc_nation_policy`, `npc_general_policy`)
- global defaults (`env['npc_nation_policy']`, `env['npc_general_policy']`)
- live nation + env snapshot

Policies provide:

- `priority` action lists
- `can{Action}` toggles
- thresholds (min crew, safe population ratio, resource floors, etc.)

## Nation Turn Behavior (`chooseNationTurn`)

Nation-level choices run only for NPCs (`npc >= 2`) or for autorun users:

1. Apply quarterly promotions and tax/bill adjustments.
2. Honor reserved nation commands if valid.
3. Iterate policy `priority`, invoking matching `do{Action}`.
4. Fall back to neutral (empty) nation command if all fail.

### Major Action Groups

- **Troop movement**
  - `do부대전방발령`, `do부대후방발령`, `do부대구출발령`
  - user/NPC versions to move generals between front/back/supply cities
  - checks `frontCities`, `supplyCities`, `last발령`, and war route.
- **Resource distribution**
  - `do유저장포상`, `doNPC포상`, `doNPC몰수`
  - uses resource floors (`reqNation*`, `reqNPC*`, `reqHuman*`)
  - weighted by target general's deficit and recent activity.
- **Diplomacy**
  - `do불가침제의`: respond to assistance requests with NAP offer.
  - `do선전포고`: probabilistic declaration when strong enough.
- **Capital relocation**
  - `do천도`: moves capital based on population, dev, and connectivity.

## General Turn Behavior (`chooseGeneralTurn`)

General-level decisions are layered:

1. NPC message broadcast if `npcmsg` and RNG triggers.
2. Reserved command is honored if valid (unless `휴식`).
3. Immediate recovery if `injury > cureThreshold`.
4. Special cases:
   - NPC troop leaders (type 5) always `집합`.
   - wanderers decide on founding / moving / disbanding.
5. Iterate policy `priority`, invoking `do{Action}`.
6. Fallback to `do중립`.

### Major Action Groups

- **Domestic development**
  - `do일반내정`, `do전쟁내정`, `do긴급내정`
  - weighted by `city` dev rates and general type flags.
- **War preparation**
  - `do징병`, `do전투준비`, `do출병`
  - strict checks on crew, train, atmos, population and diplomacy state.
- **Mobility**
  - `do전방워프`, `do후방워프`, `do내정워프`, `do귀환`
  - uses `front/supply/backup` cities and population thresholds.
- **Resource handling**
  - `do금쌀구매` (trade), `doNPC헌납` (donation).
- **Neutral behavior**
  - `do중립` selects between `물자조달`, `인재탐색`, `견문`.

## Data Fields Accessed (Representative)

GeneralAI reads or writes:

- **Nation**: `nation`, `capital`, `gold`, `rice`, `tech`, `level`, `type`,
  `chief_set`, `rate`, `bill`, `aux`.
- **City**: `city`, `nation`, `supply`, `front`, `pop`, `pop_max`, `trust`,
  `agri/comm/secu/def/wall` and `*_max`, `trade`, `level`, `region`.
- **General**: `npc`, `officer_level`, `officer_city`, `killturn`, `injury`,
  `troop`, `crew`, `train`, `atmos`, `defence_train`, `gold`, `rice`, `belong`,
  `permission`, `npcmsg`, `dex1..dex5`, `armType`, `affinity`, `makelimit`.
- **KVStorage**: `npc_*_policy`, `prev_income_*`, `last_attackable`,
  `last천도Trial`, `resp_assist*`.

## In-Memory Rewrite Considerations

To port the AI to an in-memory state model without behavior drift:

- **Snapshot-first**
  - Build a per-turn `GameSnapshot` containing env, nation, cities, generals,
    diplomacy, and nation_env. `GeneralAI` should read only from this snapshot.
- **Derived caches**
  - Cache `DiplomacyState`, `CityBuckets`, `GeneralBuckets`, and `WarRoute`.
  - Use lazy recalculation and invalidate only the affected region/city/general
    after a command is applied.
- **Deterministic ordering**
  - For candidate lists, sort by ID before weighted RNG to preserve parity.
  - RNG seeding should keep the exact per-turn seed scheme to ensure replay.
- **Command evaluation**
  - Keep `hasFullConditionMet()` semantics intact by providing the same
    generalized context (`general`, `city`, `nation`, `dest*`).
- **Policy snapshots**
  - Cache policy values per turn and avoid reading KVStorage per action.
  - When policies change, treat it as an explicit state transition.

These guidelines mirror the current "derive once, then select via priority"
pattern and minimize resimulation deltas in the rewrite.
