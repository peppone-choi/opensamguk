# 2026-06-29 deployable tags and scenario map review

## Scope

- `opensamguk-docker-env-admin/deployer`: promotion tag listing and deploy failure safety.
- `opensamguk`: scenario map metadata propagation for `che`/`miniche` families.

## Findings Checked

- The reported promotion failure used an app commit tag that lacked a matching `web-game-<tag>` image in GHCR.
- Deployer status previously queried a non-matching package shape and treated a single service tag source as representative.
- Deployer deployment previously wrote `IMAGE_TAG`/`WEB_GAME_TAG` before `docker compose pull`, leaving a bad pin possible after a pull failure.
- Scenario JSON resources contain `map.mapName` values such as `miniche`, `miniche_b`, `miniche_clean`, and `cr`, but `ScenarioJson` dropped the `map` block.
- `MapPreviewController` already reads `world_state.config["map"]`; `DaemonLoopConfig` reads `world_state.meta["map"]` as a string.

## Changes Reviewed

- Deployer now reads GHCR tags from the `opensamguk` container package and returns only suffixes with all required app images.
- Deployer now validates pullability through a temporary env file before mutating the server env file.
- Scenario parsing now preserves `map` and `const` blocks.
- Scenario import now writes resolved map data to `config.map`, top-level `config.mapName`/`unitSet`, and engine-facing `meta.map`.
- `miniche_b` and `miniche_clean` now resolve to the existing miniche city constant variant for engine world-event logic.

## Verification

- `docker run --rm -v "$PWD":/src -w /src/deployer golang:1.23 sh -lc '/usr/local/go/bin/gofmt -w main.go main_test.go && /usr/local/go/bin/go test ./...'` passed in `opensamguk-docker-env-admin`.
- `git diff --check` passed in both repositories.
- Local Gradle verification in `opensamguk` was blocked by Kotlin compiler/source-path state unrelated to this diff:
  - direct runs under the Korean path rewrote sources to `/Users/apple/Desktop/uAC1C.../opensamguk/...` and failed with missing source files.
  - a clean `/tmp/opensamguk-verify` copy progressed past that path issue but hit broader existing `infra`/`logic` unresolved symbol failures outside this diff.

## Residual Risk

- Full non-`che` city-table seeding still uses `cities_1010.json`; this patch fixes map selection and engine map metadata, not a complete per-map city seed resource import.
- `cr`, `pokemon_v1`, `chess`, and `ludo_rathowm` display maps can now be selected for preview when their scenario metadata is present, but only `che` and miniche-family city constants are registered for engine world-event logic.
