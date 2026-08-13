# OPENSAM-123 local runtime baseline

This runbook produces the GitHub #269 local surrogate measurement. It never
reads production data, does not contact a production service, and must not be
used as a production capacity threshold.

## Preconditions

- Docker daemon available locally.
- JDK 21 available on the host for the Gradle build and JFR analysis.
- Enough local disk for six small raw/JFR artifacts. Output is created only
  below `app/game-engine/build/cqrs-runtime-baseline/` and remains untracked.

## Run

From the repository root, choose a unique run id and execute:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B tools/cqrs/run-runtime-baseline.py \
  --local-sanitized-aggregate-policy \
  app/game-engine/src/baseline/resources/opensamguk/engine/baseline/local-sanitized-aggregate-policy.json \
  --run-id op123-local-YYYYMMDDTHHMMSSZ
```

The runner builds a dedicated baseline classifier, starts one fresh PostgreSQL
container per sample, and removes only containers/networks labelled with its
run id. It runs `current-1..3` and `cold10x-1..3` with a 2 GiB cgroup, G1, and
`-XX:MaxRAMPercentage=60`.

## Inspect

The run directory contains:

- `raw/*.json`: one stable raw record per sample with cgroup/JVM/image identity,
  fixture hash, boot/snapshot/tick durations, RSS, heap used/committed,
  after-GC retained heap, loaded database/snapshot rows, and GC counters.
- `jfr/*.jfr`: JFR profile recordings.
- `analysis.json`: source hashes plus derived JFR GC-pause evidence.
- `summary.json`: p50/p95 and run-to-run spread for latency, RSS, heap
  used/committed, retained heap, GC pause, and each loaded row count.
- `summary.md`: a compact human-readable latency/GC table plus memory and
  loaded-row totals; detailed per-row statistics remain in `summary.json`.

To regenerate only derived artifacts from an existing completed run, use:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B tools/cqrs/run-runtime-baseline.py \
  --analyze-run-id op123-local-YYYYMMDDTHHMMSSZ
```

The analyzer rejects unexpected files and symlinks before writing any derived
output. Do not commit raw JSON or JFR files; record only concise result values
and source hashes in review evidence.

## Acceptance check

1. `summary.json` reports `n=3` for both profiles.
2. Fixture hashes are constant within each profile, hot cardinality is equal,
   and cold history is exactly 10x.
3. Every raw record reports the required cgroup and JVM flags.
4. All twelve raw/JFR source hashes appear in `analysis.json`.
5. `summary.json` exposes p50/p95/spread for boot/tick and for the additional
   memory/loaded-row metrics; `summary.md` provides a human-readable table.

If Docker or JDK 21 is unavailable, record the exact failure as local
measurement blocked. Do not substitute a fake capture or claim a pass.
