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

The seed's live entity set is fixed. The fixture additionally inserts 256 fixed `SYSTEM/ACTION` hot log rows and `SYSTEM/HISTORY` cold log rows at `baseRows × 1` for `current` or `baseRows × 10` for `cold10x`. Row payloads, ordering, profile, multiplier, and base-row setting form the recorded SHA-256 fixture manifest. The wrapper rejects a completed run unless both profiles share the same base rows, fixed-hot rows, and payload length, and unless `cold10x` has exactly ten times the `current` cold-history rows. The default `baseRows=10000` is only an explicit synthetic starting point.

This fixture is deliberately labelled **synthetic production-shaped seed proxy**. It is neither production data nor a sanitized production shape. Full W0 capacity acceptance remains pending a separately approved sanitized shape and live row-count calibration, especially for the `current` profile's base history count.

## Artifacts

The classifier is separate from the production Docker input directory, and each invocation creates a new timestamp-and-PID run directory that it refuses to overwrite:

```text
app/game-engine/build/cqrs-runtime-baseline/
├── jars/game-engine-cqrs-baseline.jar
└── <run-id>/
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

The raw JSON (`cqrs-runtime-baseline.raw.v2`) contains the actual JVM version/input arguments/max heap, cgroup limit/current memory, pre/post explicit-GC heap used/committed/max, RSS, total and collector GC count/time, boot/snapshot/tick durations, due/handled count, database and snapshot row counts, fixture SHA-256, JFR filename/configuration, and both image tags and Docker image IDs for the probe and Postgres images. Tags identify the requested image name; the recorded image IDs identify the local immutable image content used by the run. The host JDK 21 runs `jfr summary` after each probe, writes that output beside the probe log, and rejects the sample unless it contains nonzero `jdk.GCPhasePause`, `jdk.GarbageCollection`, and `jdk.ObjectAllocationSample` events.

`analysis.json` (`cqrs-runtime-baseline.analysis.v1`) preserves the per-sample host-derived JFR evidence, including the raw/JFR SHA-256 manifest, event file paths, and count/total/max/p50/p95 pause durations. It uses `jdk.GarbageCollection.gcId` plus `cause` to classify each `jdk.GCPhasePause` duration: `operational` excludes `cause="System.gc()"`, while `forcedRetainedHeapProbe` contains those deliberate retained-heap probe collections.

`summary.json` and `summary.md` (`cqrs-runtime-baseline.summary.v2`) are written only after all six raw files and JFR analyses validate. For each profile (`n=3`) they show min, max, mean, run-to-run spread, and boot/snapshot/tick p50/p95. `probe.threeRunMetricPercentileMethod` records deterministic linear interpolation over the three run values, while `probe.jfrGcPhasePausePercentileMethod` explicitly records linear interpolation over pooled per-event JFR pause durations, which is event-weighted rather than a three-run metric. They also show retained-heap mean and cold10x-minus-current byte/percentage delta, the cross-profile fixture contract, stable image identities, `gcCollectionTimeProxyMillis`, and aggregate operational versus forced-probe JFR GC-pause count/total/max/p50/p95.

`durations.bootDurationMs` is explicitly a harness setup+boot measure, not JVM startup alone. Its machine-readable `probe.bootDurationMetric` scope is `harnessSetupAndBoot` and includes fresh Postgres Flyway migration, scenario seed, profile fixture insert, world snapshot load, and `InMemoryTurnWorld` construction. The existing capture values and capture timer are unchanged; summaries label this column “Harness setup+boot”.

## Measurement limitations

- `heapAfterGc.usedBytes` is a retained-heap proxy following two `MemoryMXBean.gc()` requests. It is not an object-retention proof or a heap-dump dominator analysis.
- `gcCollectionTimeProxyMillis` is the MXBean collection-time delta, not a measured stop-the-world pause. `jfrGcPhasePause.operational` is the separately derived JFR duration metric; it excludes the two explicit retained-heap `System.gc()` probes, whose metrics remain separately visible as `forcedRetainedHeapProbe`.
- JFR starts within the isolated probe before Flyway and stops after raw output preparation; it is one file per sample, not a production flight recording. Its host-side summary and JSON postprocessing are outside the measured cgroup.
- The representative tick uses the same pure in-memory `TurnDaemonLifecycle` shape as `ScenarioBootIT`; it does not start Redis, an application context, or the daemon flush loop.
- The synthetic fixture exercises current unbounded `log_entry` snapshot reads. It does not establish a production row distribution, production latency SLO, or an approved W0 capacity threshold.

## Observed verification

- Runner surface evidence: `python3 tools/cqrs/run-runtime-baseline.py --help` exited 0 and exposed `--base-rows`, `--run-id`, `--analyze-run-id`, and build-scoped `--output-dir`.
- Runner unit evidence: `python3 -m unittest discover -s tools/cqrs -p 'test_run_runtime_baseline.py' -v` passed 20 stdlib tests for Runtime/openjdk parsing, percentile interpolation, fixture/image/raw contracts, output scope/run-id, hashed Docker names, own-versus-foreign container cleanup, exact classifier freshness/location, JFR summary parsing, ISO-8601 duration parsing, malformed/empty JFR rejection, `gcId`/`System.gc()` cause mapping, separate operational/forced aggregate metrics, summary-method/boot-scope metadata, real-layout analysis with source SHA preservation, and fail-before-write rejection of raw/JFR source, logs-directory, and derived-target symlinks. Kotlin-only duplicate statistics code and its tagged test were removed so the Python runner is the sole summary implementation under test.
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

  The hardened `:app:game-engine:verifyRuntimeBaselineJarIsolation` task subsequently passed with `BUILD SUCCESSFUL in 3m 1s`. Its current forced-rebuild output is SHA-256 `446e9ee45734f1205124ccd37afa3ae3e7b09a63c19919a5d92e96a19ce90f46`, is the only `*-cqrs-baseline.jar` in the dedicated `jars/` directory, and has no classifier in `build/libs/`. `jar tf` found `flyway-core`, `flyway-database-postgresql`, and `postgresql`, with no `testcontainers`, `junit`, or `game-api` entry. The obsolete task-generated `jars/game-engine-0.0.1-SNAPSHOT-cqrs-baseline.jar` (SHA-256 `a6bb230e7e7ee9bf360e8a4a1eabe9cdc02c232b141cea63b5fd43d449a65d84`, 76379342 bytes) was removed; no run artifact or historical classifier was removed.
- Repository-level guard note: `python3 tools/agent-system/check.py --strict --base origin/main` was run after this work and reported two non-ticket blockers: a shared `.codex/config.toml` personal-model policy error and a missing PR-visible `docs/superpowers/reviews/*.md` independent-critique artifact. Neither path is owned by this ticket. The runtime baseline evidence above is green; the repository-wide strict guard is not claimed green until those shared items are resolved.
- Environment note: CodeGraph was present but its local index returned `Explore failed: unable to open database file`; direct source inspection was used for this task. The ordinary sandbox also blocked Gradle's local daemon socket and shared cache lock, so the observed Gradle validation used a build-local `GRADLE_USER_HOME` with the required local-daemon permission. Those are tooling constraints, not baseline results.

The historical six-container run is retained only for debugging comparison. A corrected six-container capture must use the isolated classifier directory and dependency assertion before it is used as current reproducibility evidence. Capacity acceptance remains pending an approved sanitized production shape and explicit W0 thresholds.
