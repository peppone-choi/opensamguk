# OPENSAM-123 — reproducible CQRS runtime baseline

## Status

The harness is implemented. The earlier six-sample capture is retained as superseded evidence because its classifier inherited test-only dependencies. The corrected `w0-harness-20260718d` capture is the current reproducibility evidence and used the fixed dedicated classifier path. It is not by itself a W0 capacity-acceptance claim because the fixture is synthetic.

## Reproduction command

Run from the repository root:

```bash
python3 tools/cqrs/run-runtime-baseline.py --base-rows 10000
```

The wrapper validates a host JDK 21 and Docker daemon, builds `verifyRuntimeBaselineJarIsolation` outside the measurement cgroup, then validates that `eclipse-temurin:21-jre` itself is JDK 21. The Gradle assertion requires the fixed `game-engine-cqrs-baseline.jar` to be the only classifier under `app/game-engine/build/cqrs-runtime-baseline/jars/`, rejects any classifier in `app/game-engine/build/libs/`, excludes test-only/game-api libraries, and requires Flyway/Postgres runtime libraries. It launches one new PostgreSQL container and one new probe container for each of three `current` and three `cold10x` samples.

Only the probe container is constrained by the measurement cgroup:

```text
--memory=2g --memory-swap=2g
java -XX:+UseG1GC -XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=40 -jar ...
```

The classifier probe is the only JVM in that 2 GiB container. Gradle and its test workers do not enter the measured cgroup. PostgreSQL is a fresh sibling container on a dedicated throwaway Docker network; its memory is not part of the JVM RSS/heap figures. Container names include a hash of the full run ID, and each container/network carries the full run-ID label. Cleanup inspects that label and removes only a container owned by the current run, including when Postgres readiness fails. Host artifacts stay below `app/game-engine/build/cqrs-runtime-baseline/`.

After a completed run, the safe analysis-only command validates the exact six existing raw/JFR pairs under the same build-scoped output root, performs host JDK 21 discovery (including `/usr/libexec/java_home -v 21` when available and `java -version`), then invokes the host JDK 21 `jfr` tool:

```bash
python3 tools/cqrs/run-runtime-baseline.py --analyze-run-id w0-harness-20260718d
```

It neither rebuilds the classifier nor invokes Docker. It preserves raw JSON and JFR files, records their SHA-256 values in `analysis.json`, rejects a source-artifact change during analysis, and rewrites only JFR summary/JSON extracts, `analysis.json`, and the two summaries.

Before it invokes `jfr` or writes any derived artifact, analysis performs a fail-closed filesystem preflight. The run directory and its `raw/`, `jfr/`, and `logs/` children must be real non-symlink directories resolved inside the run directory. The exact six raw JSON files and six JFR files must be regular non-symlink files in their expected directories. Every derived target must be confined to the run directory or `logs/`; an existing derived target symlink, including one that points back inside the run directory, rejects analysis before any JFR command or write. Approved derived files are written through a same-directory temporary file and atomic `os.replace`; cleanup removes only that writer's own temporary file. This prevents analysis from following crafted source, directory, or output links outside the selected run.

## Runtime path and fixture

The classifier-only source set executes the same representative path used by `ScenarioBootIT`:

```text
Flyway → SeedBootstrap(scenario_1010) → deterministic fixture insert
→ WorldSnapshotLoader.buildSnapshot → InMemoryTurnWorld
→ TurnDaemonLifecycle one all-due representative tick
```

The seed's live entity set is fixed. Before measuring it, the baseline-only fixture normalizes the importer-derived clock and server identifier to fixed values while preserving each general's relative scheduled-turn offset. The fixture then inserts 256 fixed `SYSTEM/ACTION` hot log rows and `SYSTEM/HISTORY` cold log rows at `baseRows × 1` for `current` or `baseRows × 10` for `cold10x`. Each inserted hot/cold log payload is exactly `repeat('H', declaredBytes)` or `repeat('C', declaredBytes)`, respectively, with no row identifier prefix. Row payloads, ordering, profile, multiplier, and base-row setting form the recorded SHA-256 fixture manifest. The wrapper rejects a completed run unless both profiles share the same base rows, fixed-hot rows, and payload length, and unless `cold10x` has exactly ten times the `current` cold-history rows. The default `baseRows=10000` is only an explicit synthetic starting point.

This fixture is deliberately labelled **synthetic production-shaped seed proxy**. It is neither production data nor a sanitized production shape. Full W0 capacity acceptance remains pending a separately approved sanitized shape and live row-count calibration, especially for the `current` profile's base history count.

## Sanitized production-shape contract (validation-only; capture blocked)

OPENSAM-123 has a v3 sanitized production-shape **contract**, not a materializer. Its schema is [`tools/cqrs/production-shape-manifest.schema.json`](../../../tools/cqrs/production-shape-manifest.schema.json); the Python validator in `tools/cqrs/production_shape_manifest.py` is normative for canonical JSON SHA-256, exact required fields, checked-in loader-inventory binding, the explicit `selected-loader-fields-postgres-text-bytes.v1` payload semantic, provenance coverage, and the 1×/10× relation. The schema and validator admit only aggregate table/snapshot and loader-input metrics, UTC observation times, approved aggregate-evidence source classes, evidence digests, and payload-size assumptions. They reject paths, host names, identifiers, raw rows, payload contents, and any unknown fields.

An approved manifest is a necessary future prerequisite and must contain all of the following:

- a canonical SHA-256 over the full manifest (excluding its own `sha256` field);
- the SHA-256 of the checked-in v2 [`loader-input-inventory.json`](../../../app/game-engine/src/baseline/resources/opensamguk/engine/baseline/loader-input-inventory.json), which is the single inventory source of truth for each input's loader and payload columns;
- source class, UTC observation time, and an evidence digest for every required table, snapshot, loader-input, fixture-row, and payload-size dimension;
- current-profile cardinalities for `worldState`, `city`, `nation`, `general`, `diplomacy`, `rankData`, `logEntry`, all eight snapshot collection metrics, and all 19 persisted loader inputs; and
- fixed hot-action rows, current cold-history rows, hot/cold payload bytes, plus a `cold10x` profile that differs only by exactly nine additional current cold-history sets (an exact 10× cold-history total).

The 19 loader inputs are `worldState`, `ngGames`, `archivedNationIds` (`ng_old_nations` scoped to the active server), `statistics`, four independently measured log categories (`nationHistoryLogs`, `generalHistoryLogs`, `systemActionLogs`, `systemHistoryLogs`), `activeUniqueAuctionItems`, `storedUniqueItemNamespaces`, `gameEnv`, `nationEnv`, `inheritancePoints`, `generalRankValues`, `nations`, `cities`, `generals`, `diplomacy`, and `generalAccessLogs`. Each records `sourceRows`, `retainedItems`, and aggregate `payloadBytes`; the checked-in inventory binds every input to explicit loader columns and payload columns. Under `selected-loader-fields-postgres-text-bytes.v1`, ordinary inputs sum `COALESCE(octet_length((selectedField)::text), 0)` only over their listed payload columns, never JSON row keys, separators, or a serialized row envelope. `ngGames` is intentionally special: `sourceRows` is full `ng_games` cardinality; retained items are one `state.meta.serverCount` scalar plus the optional active game; and payload bytes are the Postgres text bytes of that full count scalar plus only the selected active-server row fields (`WHERE server_id = ?`). Its inventory source is a canonical composite of the exact count query and exact active-server query; the Kotlin observer reconstructs and checks that composite before executing either branch. All four log inputs deliberately list only `text` as payload, so fixture log bytes and `systemHistoryLogs` growth share the same unit. Cross-profile validation keeps nation/general history and system actions fixed, and permits the exact 10× growth only in `systemHistoryLogs`; its source/retained/payload deltas must all equal nine current cold-history sets. All cardinalities use the shared closed range `0..2_147_483_647`: Python, JSON schema, and Kotlin fixture parsing reject `Int.MAX_VALUE + 1` before a probe can start.

The safe validation-only surface does not start Docker, Gradle, or a probe:

```bash
python3 tools/cqrs/run-runtime-baseline.py \
  --validate-production-shape-manifest /approved/sanitized-shape.json
```

An approved manifest cannot currently authorize a capture. The fixed `scenario_1010` seed proxy can only adjust its clock, server identifier, and synthetic log rows; it cannot materialize arbitrary sanitized entity, rank/KV, auction, access-log, or selected-field shapes. Any actual packaged Kotlin CLI invocation with `--fixture-config` therefore fails closed before cgroup validation, database initialization, Flyway, seeding, or observation with `sanitized production-shape capture is blocked`. `--validate-fixture-config` remains available because it only checks the canonical contract.

The future capture command would use the same existing 3×`current` + 3×`cold10x` topology, but it remains blocked until a deterministic sanitized materializer or an approved sanitized restore can prove each requested shape rather than reusing scenario-seed discovery rows. The command is shown only as the future interface; the Python runner now rejects it before manifest loading, host-JDK resolution, Gradle, Docker, or PostgreSQL work:

```bash
python3 tools/cqrs/run-runtime-baseline.py \
  --production-shape-manifest /approved/sanitized-shape.json \
  --run-id production-shape-<approved-id>
```

The dormant projection/immutable-contract code remains useful validation/hardening work, but no `--production-shape-manifest` run can currently create capture artifacts: the runner rejects it before creating a run directory. A future materializer/restore must retain the existing canonical-SHA, projection byte-comparison, and immutable-contract checks before a raw sample is accepted.

No approved live manifest is committed with this ticket. The only currently retained aggregate pre-reset CI observation is `world_state=1`, `city=94`, `nation=19`, and `general=598` from GitHub Actions run `29151979765`. It lacks sourced `diplomacy`, `rank_data`, `log_entry`, all four log categories, every one of the 19 loader-input metrics, all snapshot cardinalities, payload-size assumptions, and a complete per-dimension provenance/time record. Therefore it is intentionally rejected as incomplete: no production-shape 3×2 capture or W0 capacity conclusion is claimed. The existing `w0-harness-20260718d` artifacts remain **synthetic** and are not upgraded or relabelled by this mode.

## Local deterministic aggregate surrogate

The separate checked-in policy [`local-sanitized-aggregate-policy.json`](../../../app/game-engine/src/baseline/resources/opensamguk/engine/baseline/local-sanitized-aggregate-policy.json) makes one deliberately small, deterministic local aggregate shape executable. It is not an approved production-shape manifest, not a sanitized production restore, and not evidence about production/live capacity. Its explicit provenance is `local-deterministic-policy`: it is authored in the repository and has no production, live, scenario-seed, or database-observed input.

`LocalSanitizedAggregateMaterializer` is baseline-only code. After fresh Flyway migration it inserts the exact policy rows directly through JDBC: one fixed `world_state`, one fixed `general`, 256 `SYSTEM/ACTION` rows, and 10,000 or 100,000 `SYSTEM/HISTORY` rows with fixed 192-byte text payloads. It pads only the newly constructed local world/general JSON values to the policy's fixed 4,096-byte selected-field targets. It does not query a seeded database to derive a target. The already-present world makes `SeedBootstrap` take its idempotent skip branch; the measured load remains the real `WorldSnapshotLoader` → `InMemoryTurnWorld` → representative tick path.

The runner accepts a supplied local-policy path only when its canonical document and SHA-256 exactly equal the checked-in policy; an exact copy at another path is allowed, but a changed-and-resealed policy is rejected. It then verifies the complete loader-input inventory, fixed profile arithmetic, and the exact 1×/10× cold-history relation before it creates a run directory, resolves a host JDK, builds, or invokes Docker. The local-only command is:

```bash
python3 tools/cqrs/run-runtime-baseline.py \
  --local-sanitized-aggregate-policy app/game-engine/src/baseline/resources/opensamguk/engine/baseline/local-sanitized-aggregate-policy.json \
  --run-id op123-local-<run-id>
```

Its raw artifacts use `cqrs-runtime-baseline.raw.local-sanitized-aggregate.v1` and carry the local fixture kind, policy ID, policy SHA-256, and the explicit non-observation label. The production `--production-shape-manifest` path remains fail-closed and cannot be relabelled or inferred from this local surrogate.

## Artifacts

The classifier is separate from the production Docker input directory, and each invocation creates a new timestamp-and-PID run directory that it refuses to overwrite:

```text
app/game-engine/build/cqrs-runtime-baseline/
├── jars/game-engine-cqrs-baseline.jar
└── <run-id>/
    ├── manifest/approved-production-shape-manifest.json  (future production-shape capture only; currently blocked)
    ├── manifest/local-sanitized-aggregate-policy.json  (local-policy capture only)
    ├── fixture/current.json … fixture/cold10x.json  (canonical configs for the selected mode)
    ├── raw/current-1.json … raw/current-3.json
    ├── raw/cold10x-1.json … raw/cold10x-3.json
    ├── jfr/current-1.jfr … jfr/cold10x-3.jfr
    ├── logs/build.log
    ├── logs/current-1.log … logs/cold10x-3.log
    ├── logs/current-1.jfr-summary.txt … logs/cold10x-3.jfr-summary.txt
    ├── logs/current-1.jfr-gc-phase-pause.json … logs/cold10x-3.jfr-gc-phase-pause.json
    ├── analysis.json
    ├── summary.json
    └── summary.md
```

The retained W0 raw JSON artifacts use `cqrs-runtime-baseline.raw.v2`. They contain the actual JVM version/input arguments/max heap, cgroup limit/current memory, pre/post explicit-GC heap used/committed/max, RSS, total and collector GC count/time, boot/snapshot/tick durations, due/handled count, database/snapshot row metrics, synthetic fixture details (including that fixture's SHA), JFR filename/configuration, and both image tags and Docker image IDs for the probe and Postgres images. They do **not** contain loader-input aggregates, a loader-inventory SHA-256, payload semantics, or an inventory-bound observation mapping. Those fields belong only to a future v3 sanitized-mode raw result, which cannot currently be captured. Tags identify the requested image name; the recorded image IDs identify the local immutable image content used by the run. The host JDK 21 runs `jfr summary` after each probe, writes that output beside the probe log, and rejects the sample unless it contains nonzero `jdk.GCPhasePause`, `jdk.GarbageCollection`, and `jdk.ObjectAllocationSample` events.

Local-policy raw JSON is intentionally a different schema (`cqrs-runtime-baseline.raw.local-sanitized-aggregate.v1`), rather than a v3 production-shape result. It records the same operational/JFR evidence plus the checked-in policy identity and locally materialized aggregate rows/loader inputs; its summary label states that there was no production, live, or seeded-database observation.

`analysis.json` (`cqrs-runtime-baseline.analysis.v1`) preserves the per-sample host-derived JFR evidence, including the raw/JFR SHA-256 manifest, event file paths, and count/total/max/p50/p95 pause durations. It uses `jdk.GarbageCollection.gcId` plus `cause` to classify each `jdk.GCPhasePause` duration: `operational` excludes `cause="System.gc()"`, while `forcedRetainedHeapProbe` contains those deliberate retained-heap probe collections.

`summary.json` and `summary.md` (`cqrs-runtime-baseline.summary.v2`) are written only after all six raw files and JFR analyses validate. For each profile (`n=3`) they show min, max, mean, run-to-run spread, and boot/snapshot/tick p50/p95. `probe.threeRunMetricPercentileMethod` records deterministic linear interpolation over the three run values, while `probe.jfrGcPhasePausePercentileMethod` explicitly records linear interpolation over pooled per-event JFR pause durations, which is event-weighted rather than a three-run metric. They also show retained-heap mean and cold10x-minus-current byte/percentage delta, the cross-profile fixture contract, stable image identities, `gcCollectionTimeProxyMillis`, and aggregate operational versus forced-probe JFR GC-pause count/total/max/p50/p95.

`durations.bootDurationMs` is explicitly a harness setup+boot measure, not JVM startup alone. Its machine-readable `probe.bootDurationMetric` scope is `harnessSetupAndBoot`. Synthetic runs include fresh Postgres Flyway migration, scenario seed, profile fixture insert, world snapshot load, and `InMemoryTurnWorld` construction; local-policy runs instead record fresh Flyway migration, local sanitized aggregate materialization, world snapshot load, and `InMemoryTurnWorld` construction. The existing capture values and capture timer are unchanged; summaries label this column “Harness setup+boot”.

## Measurement limitations

- `heapAfterGc.usedBytes` is a retained-heap proxy following two `MemoryMXBean.gc()` requests. It is not an object-retention proof or a heap-dump dominator analysis.
- `gcCollectionTimeProxyMillis` is the MXBean collection-time delta, not a measured stop-the-world pause. `jfrGcPhasePause.operational` is the separately derived JFR duration metric; it excludes the two explicit retained-heap `System.gc()` probes, whose metrics remain separately visible as `forcedRetainedHeapProbe`.
- JFR starts within the isolated probe before Flyway and stops after raw output preparation; it is one file per sample, not a production flight recording. Its host-side summary and JSON postprocessing are outside the measured cgroup.
- The representative tick uses the same pure in-memory `TurnDaemonLifecycle` shape as `ScenarioBootIT`; it does not start Redis, an application context, or the daemon flush loop.
- The synthetic fixture exercises current unbounded `log_entry` snapshot reads. It does not establish a production row distribution, production latency SLO, or an approved W0 capacity threshold.
- The local deterministic aggregate surrogate is a feasibility and reproducibility check only. Its intentionally small one-world/one-general policy does not establish a production distribution, sanitized production parity, live capacity, or a W0 acceptance threshold.

## Observed verification

- Earlier validation-only contract checkpoint on 2026-07-19: `PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s tools/cqrs -p 'test_*.py' -v` ran 40 tests with `OK (skipped=3)`. The three opt-in skips are the two packaged CLI regressions and the disposable synthetic scenario-seed Docker validation; no production-shape capture is enabled. The suite covers v3 payload semantics/inventory binding, all 19 inputs, raw/config projection agreement, canonical SHA replay, range boundaries, exact 10× enforcement, the fail-before-build/Docker production-shape runner guard, and filesystem/contract failure cases. After the local materializer and checked-in-policy binding regressions landed, the consolidated suite ran 45 tests with the same three opt-in skips and passed.
- Behavior-backed `ngGames` and capture-block evidence on 2026-07-19: `RUN_CQRS_PACKAGED_CLI_REGRESSION=1 ...ProductionShapePackagedCliRegressionTest.test_ng_games_multi_row_composition_and_validation_only_capture_block -v` passed one test in `142.145s`. It rebuilt the isolated classifier and ran the packaged Kotlin CLI with a two-row model whose inactive `env` payload was 4096 bytes: observed metrics retained full `sourceRows=2`, `retainedItems=2`, and only count-scalar-plus-active-row payload bytes. The test then rewrote the packed inventory resource once with count-query drift and once with active `WHERE server_id = ?` drift; both direct CLI calls failed the executable-source binding. Finally, a deliberately differing sanitized config passed `--validate-fixture-config` while actual `--fixture-config` capture failed before cgroup/database work. No Docker or probe ran.
- Historical, superseded contract-probe evidence: `RUN_CQRS_DOCKER_PROBE_REGRESSION=1 ...ProductionShapeDockerProbeRegressionTest.test_real_postgres_probe_preserves_selected_field_payload_contract -v` previously passed in `230.052s` after using current/cold discovery rows to construct and then recapture a manifest-bound pair. That discovery-to-target loop was circular for production shape and is not production-shape evidence. The replacement opt-in test is `RUN_CQRS_DOCKER_SCENARIO_PROXY_VALIDATION=1 ...ScenarioSeedProxyDockerValidationTest...`; it validates only the fixed synthetic scenario-seed proxy and does not invoke fixture-config capture. It was intentionally not rerun for this contract-only fix.
- Fixture-config arithmetic evidence on 2026-07-19: the focused non-mocked `RUN_CQRS_PACKAGED_CLI_REGRESSION=1 ...ProductionShapePackagedCliRegressionTest.test_fixture_config_log_entry_containment_rejects_int_overflow -v` passed one test in `131.910s` with durable exit marker `0`. It rebuilt the isolated classifier and invoked the packaged Kotlin CLI directly: the canonical `current` config printed `Production-shape fixture config valid: profile=current`, while an otherwise valid config with `fixedHotActionRows=2_147_483_647`, `coldHistoryRows=1`, and `logEntry=2_147_483_647` exited nonzero with `ArithmeticException: integer overflow` from the fail-closed `Math.addExact` containment calculation. No Docker or probe was run for this arithmetic-only regression.
- Tool-wrapper baseline: the local Fablize wrapper emitted generic `gate observed a tool failure` / repeated-class notices and intermittently dropped/truncated long Gradle/read output around successful read/test commands and intentional red TDD checks. This session also had one rejected compound `apply_patch` hunk before any mutation; the equivalent granular patches applied and all named tests reached terminal exit codes. The notices are a documented wrapper baseline, not measurement evidence or an unresolved product-test failure.
- Runner surface evidence: `python3 tools/cqrs/run-runtime-baseline.py --help` exposes `--base-rows`, `--run-id`, `--analyze-run-id`, validation-only production-shape manifest validation, and build-scoped `--output-dir`; its `--production-shape-manifest` help explicitly says capture is blocked. A direct invocation with a nonexistent manifest path exited `1` immediately with `sanitized production-shape capture is blocked`, proving the guard does not first read the path. Against an ephemeral non-live v3 manifest, `--validate-production-shape-manifest` printed `Production-shape manifest valid: sha256=5050ca2a9a41a584791c95af723c39941d8c1dad478e5496f7f8dbb5e32f2369`; the packaged classifier printed `Production-shape fixture config valid: profile=current` for its projected v3 config.
- Runner unit evidence: `python3 -m unittest discover -s tools/cqrs -p 'test_run_runtime_baseline.py' -v` now includes a mocked `main()` assertion that `--production-shape-manifest` fails before manifest loading, host-JDK resolution, build, Docker, or probe execution, alongside the existing Runtime/openjdk parsing, percentile interpolation, fixture/image/raw contracts, output scope/run-id, hashed Docker names, cleanup, classifier freshness, JFR parsing, summary, and fail-before-write filesystem coverage.
- Local-policy evidence on 2026-07-19: focused Python suites passed: `test_production_shape_manifest.py` ran 22 tests with `OK (skipped=3)` and `test_run_runtime_baseline.py` ran 23 tests with `OK`. `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon :app:game-engine:baselineClasses` ended `BUILD SUCCESSFUL`. The runner command above completed `op123-local-20260719b` with three fresh `current` and three fresh `cold10x` Docker probes, then `python3 tools/cqrs/run-runtime-baseline.py --analyze-run-id op123-local-20260719b` exited `0`. All six raw/JFR pairs use the local schema and policy `op123-local-sanitized-aggregate-v1` SHA-256 `2e9f7a74adf0ea322c8e0fbf9feee4c1f8d8f1ba95ecade3c24703d797cf9a9b`, JDK `21.0.11+10-LTS`, `-XX:MaxRAMPercentage=60`, and the exact `2147483648`-byte cgroup. Each `current` raw has 256 action rows and 10,000 history rows; each `cold10x` raw has the same 256 action rows and 100,000 history rows. All six logs record `World already exists ... scenario seed skipped` before the real loader reports one general and zero cities/nations. Its summary reports current boot p50/p95 `14122.00 / 17175.70` ms, tick p50/p95 `407.00 / 668.90` ms, and retained heap mean `14908445.33` bytes; cold10x reports `22693.00 / 26343.40` ms, `306.00 / 468.00` ms, and `66375930.67` bytes, respectively. The `51467485.33`-byte (`345.22%`) retained-heap delta is local feasibility/reproducibility evidence only, not a production-shape, live-capacity, or W0 threshold conclusion.
- Preserved non-measurement build-race artifact on 2026-07-19: `op123-local-20260719a` stopped before Docker/raw/JFR creation when the isolated `verifyRuntimeBaselineJarIsolation` build encountered Kotlin's `unsafe memory access` while reading `logic/build/libs/logic-0.0.1-SNAPSHOT.jar!/…/LastTurn.class`. At the same time an unrelated shared `:app:game-engine:test --rerun-tasks` Gradle invocation was rebuilding that shared JAR. The failed run directory is retained for diagnosis and is not a measurement artifact. After that external Gradle process exited, fresh `op123-local-20260719b` is the successful authoritative local-surrogate run described above.
- A prior one-probe attempt at `w0-harness-20260718b` produced a raw file and non-empty JFR under the intended 2 GiB cgroup, but the runner rejected its direct `Runtime.version()` form after completion. That parser defect is fixed; this partial attempt is explicitly excluded from six-sample acceptance.
- Superseded historical capture evidence on 2026-07-18:

  ```bash
  python3 tools/cqrs/run-runtime-baseline.py \
    --run-id w0-harness-20260718c \
    --base-rows 10000
  ```

  The wrapper completed with exit `0`, writing six raw JSON files, six non-empty JFR files, and both summaries under `app/game-engine/build/cqrs-runtime-baseline/w0-harness-20260718c/`. Its build log ended in `BUILD SUCCESSFUL in 3m 40s`; the subsequent artifact check confirmed raw schema `v2`, three samples per profile, JDK `21.0.11+10-LTS`, the required three JVM flags, `maxHeapBytes=1289748480`, the exact cgroup `2147483648` bytes, JFR `profile`, stable probe/Postgres image IDs, fixed `baseRows=10000`/`fixedHotLogRows=256`, and exact cold-history counts of `10000` and `100000`. It is superseded because the classifier then inherited `testImplementation`/`testRuntimeOnly`, including test-only boot classpath inputs. Its exact `112714001`-byte classifier is preserved outside the Docker glob at `app/game-engine/build/cqrs-runtime-baseline/historical-jars/game-engine-0.0.1-SNAPSHOT-cqrs-baseline.jar`.

  Fixture hashes were `current=4d33d21608441e53344765a2bb6b5eb2e6f822278828fc497a98e15fee6a96ce` and `cold10x=209fe7137392eafb459c842ca35f2f435b8046c91e0deeb74d84cc5637726c0f`. The immutable Docker image IDs were `eclipse-temurin:21-jre=sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3` and `postgres:16-alpine=sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777`.

  | Profile | Harness setup+boot p50 / p95 ms | Snapshot p50 / p95 ms | Tick p50 / p95 ms | Retained heap mean / spread bytes |
  | --- | ---: | ---: | ---: | ---: |
  | current | 14847.00 / 14974.80 | 449.00 / 565.10 | 3651.00 / 3794.10 | 19326013.33 / 98240.00 |
  | cold10x | 17703.00 / 17718.30 | 961.00 / 1230.10 | 3552.00 / 3633.90 | 74455168.00 / 97368.00 |

  The retained-heap delta was `55,129,154.67` bytes / `285.26%`, and the MXBean collection-time-proxy means were `86.33` ms (`current`) and `362.33` ms (`cold10x`). These are synthetic proxies, not capacity thresholds. `jfr summary` for `current-1.jfr` observed `jdk.GCPhasePause` (20), `jdk.GarbageCollection` (18), and `jdk.ObjectAllocationSample` (930) events, confirming profile-level JFR data was captured.

  A post-run `docker ps -a` and `docker network ls` filter for `cqrs-baseline-w0-harness-20260718c` returned no scoped container or network, confirming normal cleanup.
- Corrected capture evidence on 2026-07-18:

  ```bash
  python3 tools/cqrs/run-runtime-baseline.py \
    --run-id w0-harness-20260718d \
    --base-rows 10000
  ```

  `app/game-engine/build/cqrs-runtime-baseline/w0-harness-20260718d/` contains all six raw JSON files, six non-empty JFR files, six JFR summary files, six GC-pause JSON extracts, `analysis.json`, and summary v2. The capture build log ended in `BUILD SUCCESSFUL in 2m 57s` and records classifier SHA-256 `412ba32d5f92797952bb5278d8eb34505dbfb097c2ec598555feb8980185f8f7`. Immediately before a later isolation-only rebuild, that exact SHA was observed on the fixed `jars/game-engine-cqrs-baseline.jar`; the later forced rebuild is recorded separately below and must not be substituted for the capture provenance.

  `python3 tools/cqrs/run-runtime-baseline.py --analyze-run-id w0-harness-20260718d` exited `0`. It validated the exact three samples per profile, schema `cqrs-runtime-baseline.raw.v2`, JDK `21.0.11+10-LTS`, required JVM flags, `2147483648`-byte cgroup, JFR `profile`, stable image identities, fixed `baseRows=10000`/`fixedHotLogRows=256`, and exact cold-history counts of `10000` and `100000`. A SHA-256 manifest taken immediately before and after this analysis covered all 12 raw/JFR source artifacts and had an empty diff. The same 12 hashes are persisted in `analysis.json` under `sourceArtifactSha256`; raw JSON and JFR files remain immutable analysis inputs.

  | Profile | Harness setup+boot p50 / p95 ms | Snapshot p50 / p95 ms | Tick p50 / p95 ms | Retained heap mean / spread bytes | Operational JFR pause count / p50 / p95 / max ms | Forced-probe JFR pause count / p50 / p95 / max ms |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: |
  | current | 15740.00 / 17388.80 | 422.00 / 504.80 | 3216.00 / 3920.70 | 18631298.67 / 72088.00 | 47 / 6.20 / 17.48 / 20.77 | 6 / 38.34 / 79.87 / 91.65 |
  | cold10x | 16751.00 / 19758.80 | 1037.00 / 1111.70 | 3302.00 / 3694.40 | 73771205.33 / 14872.00 | 54 / 6.88 / 37.94 / 86.61 | 6 / 74.70 / 110.45 / 115.00 |

  The primary operational pause metric derives from all `jdk.GCPhasePause` durations whose `gcId` maps to a `jdk.GarbageCollection` cause other than `System.gc()`. The separately reported forced-probe bucket captures the two explicit retained-heap collections per sample; it is intentionally excluded from the latency baseline. Every sample's `jfr summary` retained nonzero `jdk.GCPhasePause`, `jdk.GarbageCollection`, and `jdk.ObjectAllocationSample` evidence.

  The hardened `:app:game-engine:verifyRuntimeBaselineJarIsolation` task subsequently passed with `BUILD SUCCESSFUL in 3m 1s`. Its historical forced-rebuild output for that regression was SHA-256 `446e9ee45734f1205124ccd37afa3ae3e7b09a63c19919a5d92e96a19ce90f46`; it is not a claim about the currently rebuilt classifier after later source changes. At that time it was the only `*-cqrs-baseline.jar` in the dedicated `jars/` directory and no classifier was in `build/libs/`. `jar tf` found `flyway-core`, `flyway-database-postgresql`, and `postgresql`, with no `testcontainers`, `junit`, or `game-api` entry. The obsolete task-generated `jars/game-engine-0.0.1-SNAPSHOT-cqrs-baseline.jar` (SHA-256 `a6bb230e7e7ee9bf360e8a4a1eabe9cdc02c232b141cea63b5fd43d449a65d84`, 76379342 bytes) was removed; no run artifact or historical classifier was removed.
- Repository-level guard note: `python3 tools/agent-system/check.py --strict --base origin/main` was run after this work and reported two non-ticket blockers: a shared `.codex/config.toml` personal-model policy error and a missing PR-visible `docs/superpowers/reviews/*.md` independent-critique artifact. Neither path is owned by this ticket. The runtime baseline evidence above is green; the repository-wide strict guard is not claimed green until those shared items are resolved.
- Environment note: CodeGraph was present but its local index returned `Explore failed: unable to open database file`; direct source inspection was used for this task. The ordinary sandbox also blocked Gradle's local daemon socket and shared cache lock, so the observed Gradle validation used a build-local `GRADLE_USER_HOME` with the required local-daemon permission. Those are tooling constraints, not baseline results.

The historical six-container run is retained only for debugging comparison. A corrected six-container capture must use the isolated classifier directory and dependency assertion before it is used as current reproducibility evidence. Capacity acceptance remains pending an approved sanitized production shape and explicit W0 thresholds.
