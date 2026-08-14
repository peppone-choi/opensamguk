# OPENSAM-123 local baseline evaluation contract

This is a deterministic local-surrogate contract for GitHub issue #269. It is
not PHP parity evidence and it is not a production-capacity threshold.

| Criterion | Required evidence |
| --- | --- |
| Fixture relationship | `current` and `cold10x` use the same hot cardinality; cold history is exactly 10x. |
| Repetition | Three fresh samples per profile; six raw JSON and six non-empty JFR files. |
| Process envelope | Probe reports a 2 GiB cgroup, JDK 21, G1, and `MaxRAMPercentage=60`. |
| Representative work | Each raw record reports boot, snapshot, and handled representative-tick durations. |
| Memory and GC | Raw records include RSS, heap used/committed before and after GC, retained after-GC heap, MXBean GC time, and JFR pause evidence. |
| Loaded rows | Raw records include database and snapshot counts; `summary.json` aggregates each count with p50, p95, and run-to-run spread. |
| Reproducibility | Fixture hash, image identity, raw/JFR source hashes, and stable JSON/Markdown summaries are retained under the run directory. |
| Scope | The run output remains under `app/game-engine/build/cqrs-runtime-baseline/` and is never committed as a large artifact. |

The GitHub issue acceptance criteria freeze this contract for this loop. Any
future change to the measurement shape needs a new RED/GREEN round rather than
relaxing the checks above.
