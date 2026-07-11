# Scenario Event Persistence Review

Verdict: cleared

## Scope

- `ScenarioJson` decodes `events`, `initialEvents`, and `ignoreDefaultEvents` without changing war, AI, admin, or select-pool code.
- `ScenarioImporter` persists the PHP event merge order: defaults first unless ignored, then scenario rows.
- `EngineEventConfig` restores stable event ids and JSON wire rows from PostgreSQL after seed.
- Runtime `EventStore` insert/delete mutations are recorded by `ChangeRecorder` and flushed transactionally by `JdbcFlushExecutor`.

## Evidence

- PHP source: `legacy/devsam-core/hwe/sammo/Scenario.php:214-234` merges default and scenario events; `:583-597` inserts the event rows.
- `jq` validated every committed `infra/src/main/resources/scenario/scenario_*.json` event tuple and initial-event shape.
- Standalone Kotlin compilation passed for `logic/.../EventStore.kt` and `infra/.../ScenarioJson.kt`.
- `python3 tools/agent-system/check.py --format json` returned `ok: true`.

## Tests

- Added JSON coverage for scenarios 1010, 911, 912, and 910.
- Added importer row-order/count assertions and an engine boot/flush/restart Testcontainers regression.
- Added a fast `ChangeRecorder` event-channel unit test.
- Full Gradle/JUnit execution was not completed: the shared Java 21 Kotlin compiler stalled at `:common:compileKotlin` for 240 seconds before test execution.

## Residual Risk

The Testcontainers boot/flush regression still needs a clean Gradle run when the shared compiler lock/resource contention is gone.
