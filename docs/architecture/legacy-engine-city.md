# Legacy City Data and Helpers

This document describes how city data is defined and accessed in the legacy
engine. References include `legacy/hwe/sammo/CityConstBase.php`,
`legacy/hwe/sammo/CityInitialDetail.php`, and `legacy/hwe/sammo/CityHelper.php`.

## City Constants (CityConst)

`CityConstBase` defines default city data and lookup helpers:

- `CityConstBase::$initCity` includes:
  - id, name, level, population, agri/comm/secu/def/wall,
    region, coordinates, and path connections.
- Region and level maps convert labels to numeric codes.
- `CityConstBase::byID()` and `byName()` return `CityInitialDetail` objects.

During scenario build, the engine generates `d_setting/CityConst.php` from the
scenario map file, replacing or extending these defaults.

## CityInitialDetail

`CityInitialDetail` is a plain data object containing the initial
static attributes for each city:

- `id`, `name`, `level`, `population`
- `agriculture`, `commerce`, `security`, `defence`, `wall`
- `region`, `posX`, `posY`, `path` (adjacent city ids)

## CityHelper Cache

`CityHelper` caches live DB city rows for fast lookup:

- `getAllCities()` returns a map keyed by city id.
- `getAllNationCities($nationID)` returns all cities for a nation.
- `getCityByName()` resolves a name to a DB row (with a warning if missing).

Cache invalidation is manual via `CityHelper::flushCache()`.

## Path Connectivity

City connectivity (used for supply and movement) is defined by the
`path` list in `CityConst` and referenced by systems like
`UpdateCitySupply` to traverse same-nation city graphs.

## OpenSam Resource Mapping

In OpenSam, city static data is sourced from map JSON resources, not from
`game_const.json`.

- Source of truth: `backend/src/main/resources/data/maps/*.json`
  - `cities[].id/name/level/region/population/agriculture/commerce/security/defence/wall/x/y/connections`
  - `regions` and `levelNames` maps
- Load path: `ScenarioService.initializeWorld()` reads `mapName` from scenario
  and loads city definitions through `MapService.getCities(mapName)`.
- `backend/src/main/resources/data/game_const.json` contains global numeric
  engine constants only.

## Open Questions / Follow-ups

- Scenario-specific map files (`legacy/hwe/scenario/map/*.php`) override city
  data; document per-map deltas when needed.
