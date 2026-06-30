# 2026-06-30 scenario map city catalog review

## Implementer claim

`scenario_2` declares `map.mapName = miniche_b`, but fresh seed previously loaded
`scenario/cities_1010.json`, so the DB city catalog could still look like the full `che`
map while previews used `miniche_b` coordinates. The fix makes scenario seed load
`map/<scenario mapName>.json` city data and makes `/api/const` expose the active server map's
city catalog instead of always returning `GameConst.mapName = che`.

## Source evidence

- `infra/src/main/resources/scenario/scenario_2.json` declares `mapName = miniche_b`.
- `legacy/devsam-core2026/resources/map/map_miniche_b.json` city 1 is `낙양`, level 8,
  max population 668600, agriculture 7800, commerce 8000.
- `infra/src/main/resources/map/miniche_b.json` now carries those city stats plus display
  coordinates and connections.
- This slice changes seed/catalog data, not RNG, rounding, Korean battle logs, or gameplay write
  ordering, so no PHP golden capture is required.

## Critique

The strongest failure mode was breaking the default `scenario_1010` seed by replacing
`scenario/cities_1010.json` with `map/che.json`. I checked that `map/che.json` still has the same
94 ids and the same name/level/region/max stat values as `cities_1010.json`, and that
`scenario_1010` nation city names all resolve against the `che` map catalog. `ScenarioImporter`
also derives city ownership from `scenario.nation[].cities`, not from the city resource's baked
`nation_id`, so neutral `ScenarioCity.nationId = 0` in `loadMapCities` is not used for ownership.

`code_review_graph.detect_changes_tool` reported overall risk 0.65 and prioritized
`ensureSeeded`, `readResource`, and `/api/const` coverage. Those paths are covered by the targeted
tests below.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.seed.ScenarioJsonTest' --no-parallel`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.seed.ScenarioImporterIT' --no-parallel`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.controller.GetConstControllerTest' --no-parallel`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.controller.MapPreviewControllerTest' --no-parallel`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:compileKotlin --no-parallel`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.boot.ScenarioMapSeedIT' --no-parallel`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.boot.*' --no-parallel`
- `git diff --check`
- `tools/agent-system/check.py --format json`

## Result

cleared

Known production caveat: existing already-seeded server DB rows are not rewritten by this code.
Deploying the code fixes future fresh seeds and active `/api/const` catalog reads. Correcting
currently seeded city rows requires an explicit non-destructive backfill or a deliberate server
recreate/reset decision.
