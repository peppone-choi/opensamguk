# OPENSAM-123 production-shape validation re-review

Scope: [`.codex/config.toml`](../../../.codex/config.toml) (local review-runtime configuration only; no game behavior is asserted there), [`app/game-engine` baseline CLI and inventory](../../../app/game-engine/src/baseline/kotlin/opensamguk/engine/baseline/CqrsBaselineMain.kt), and [`tools/cqrs` runner and tests](../../../tools/cqrs/run-runtime-baseline.py). This review is limited to the validation-only production-shape contract; it neither authorizes nor assesses a production-shaped capture, capacity measurement, or production data access.

Verdict: cleared

The validation-only contract currently implemented is cleared. The capture path intentionally remains unavailable. A future capture remains blocked until both a complete approved live-evidence manifest exists and there is either a deterministic sanitized materializer or an approved sanitized restore.

## Observed evidence

- `run-runtime-baseline.py` rejects `--production-shape-manifest` in `main()` before host-JDK resolution, baseline-JAR build, Docker/image setup, or probe execution. Its validation-only `--validate-production-shape-manifest` branch returns before any measurement work. `test_main_blocks_production_shape_capture_before_resolve_build_or_docker` replaces those later operations with fail-if-called stubs and observes the expected `RunnerFailure`.
- `CqrsBaselineMain` handles `ngGames` through its dedicated `aggregateNgGames` observer rather than the generic aggregate. Its executable inventory source is the canonical composition of `countQuery=SELECT count(*) AS source_rows, octet_length((count(*))::text) AS payload_bytes FROM ng_games` and `activeQuery=SELECT id, server_id, season, scenario, scenario_name, map, CAST(env AS VARCHAR) AS env FROM ng_games WHERE server_id = ?`. `requireInventoryBinding` recomposes and compares both query branches with the checked-in `loader-input-inventory.json`, so source drift fails closed.
- The same observer preserves the intended two-part metric: full `ng_games` cardinality and count-text payload bytes, plus exactly one active-server row's selected-field bytes. It reports one retained server-count item plus an optional active-game item; inactive rows affect the count but not the active-row payload.
- The opt-in packaged-JAR regression recorded for this remediation completed in **142.145 seconds**. It uses two `ng_games` rows, gives the inactive row a 4096-byte `env`, proves that only the active row contributes selected-field bytes, and mutates each of the count-query and active-query branches inside the packaged inventory. Both mutations fail with the executable-inventory divergence error; the same test also observes the actual CLI reject capture after fixture validation.
- I observed `PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s tools/cqrs -p 'test_*.py' -v` finish with **40 tests**, `OK (skipped=3)`. The three skips are opt-in packaged-CLI/Docker validations, not failures.
- The checked-in source inventory and the inventory embedded in `game-engine-cqrs-baseline.jar` have the same SHA-256: `a6635414f04c347435f3352c67d394bee18c5dfccd6e25653df29ad5fec75d12`.
- `git diff --numstat` for production `WorldSnapshotLoader.kt` was empty. The remediation changes the baseline/validation path without a production-loader diff.

## Review limits

- The previous fixed-seed Docker result is historical and superseded for this question: it constructed discovery rows and then re-used the manifest-bound pair, which is circular rather than evidence for the present validation-only production-shape contract. No Docker capture was treated as evidence here.
- Generic Fablize wrapper notices recurred around successful read-only calls. The conclusions above rely on direct source, test output, inventory/JAR-hash comparison, and the recorded packaged-JAR execution rather than that wrapper telemetry.
