# OPENSAM-123 local materializer brief

Goal: replace the unavailable stopped-host measurement with an explicitly local-only deterministic sanitized aggregate fixture and run exactly three `current` plus three `cold10x` fresh Docker probes under 2 GiB, JDK 21, and `MaxRAMPercentage=60`.

Binding decisions:

- No EC2, production, credentials, `.env`, or live-data access.
- Evidence label is `local sanitized aggregate surrogate`; never production/live capacity.
- A manifest/shape policy is the target input. The materializer must not observe a seeded DB to choose its target.
- In sanitized mode, Flyway runs, then a baseline-only materializer creates deterministic rows in one transaction. Production loader/source code and production migrations are untouched.
- Existing independent observer exact-checks all 7 table, 8 snapshot, and 19 loader-input metrics.
- Add feasibility validation before output directory, JDK/build, or Docker work. Impossible FK/cardinality/payload/tick shapes fail with zero side effects.
- Every probe uses a fresh PostgreSQL database. Six runs are mandatory and cannot be reused/padded.
- Preserve v2 synthetic evidence; use an explicit sanitized/local schema version and provenance.

Owned files:

- `tools/cqrs/production_shape_manifest.py`
- `tools/cqrs/production-shape-manifest.schema.json`
- `tools/cqrs/test_production_shape_manifest.py`
- `tools/cqrs/run-runtime-baseline.py`
- `tools/cqrs/test_run_runtime_baseline.py`
- `app/game-engine/src/baseline/kotlin/opensamguk/engine/baseline/CqrsBaselineMain.kt`
- new baseline-only materializer/resources/tests under `app/game-engine/src/baseline/**`
- `docs/loops/cqrs-runtime-safety-2026-07-18/OPENSAM-123.md`

Stop condition: Python/Kotlin focused tests pass and a real local Docker run produces six raw JSON + six JFR + logs + canonical manifest/configs + analysis JSON/MD with exact cgroup/JVM identities. Report actual artifact path and numbers. Do not commit/push.
