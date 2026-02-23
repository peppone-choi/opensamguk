# Legacy General Pools

This document summarizes the general pool system used for character selection
and event-driven recruitment. References include `legacy/hwe/sammo/AbsGeneralPool.php`,
`legacy/hwe/sammo/AbsFromUserPool.php`, and `legacy/hwe/sammo/GeneralPool/*`.

## Core Abstractions

- `AbsGeneralPool`
  - Wraps a `Scenario\GeneralBuilder` and a raw `info` payload.
  - Provides `getGeneralBuilder()`, `getInfo()`, and `occupyGeneralName()`.
  - Abstract method: `pickGeneralFromPool(...)`.

- `AbsFromUserPool`
  - Implements reservation logic backed by the `select_pool` table.
  - Reserves candidates for a user (`owner`) until `reserved_until`.
  - On selection, clears reservation and binds `general_id`.

## Pool Implementations

- `GeneralPool/RandomNameGeneral`
  - Generates random names using `GameConst::$randGen*` lists.
  - Checks duplicate counts and appends suffixes if needed.
  - Inserts reservation rows into `select_pool` for user selection.

- `GeneralPool/SPoolUnderU30`
  - Loads a curated pool from `GeneralPool/Pool/UnderS30.json`.
  - Inserts rows into `select_pool` with pre-defined stats and specials.

## Data Model

`select_pool` table fields (observed usage):

- `unique_name` (pool key)
- `info` (JSON: stats, portrait, specials, dex, etc.)
- `owner` (reservation user id)
- `reserved_until` (reservation expiry)
- `general_id` (assigned after creation)

## RNG Notes

Pool selection uses injected `RandUtil` to choose candidates and generate
random names; deterministic seeding depends on caller context.
