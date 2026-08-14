# OPENSAM-123 runtime baseline ledger

| Round | Hypothesis | Score before -> after | Grader | Verdict | Cause / evidence |
| --- | --- | --- | --- | --- | --- |
| 0 | The inherited summary omits aggregate RSS, heap used/committed, and loaded-row measurements even though raw JSON contains them. | Required summary fields absent -> RED | `RuntimeBaselineRunnerTest.test_summary_aggregates_required_memory_and_loaded_row_evidence` | baseline | Observed `KeyError: 'loadedRows'` on the inherited runner. |
| 1 | Aggregating the existing raw memory and row fields into the stable summary preserves the six-run contract without changing the probe or game runtime. | RED -> GREEN | Focused test, then full `tools/cqrs` unittest suite | adopted | Focused test passed; full runner suite passed 24/24. A duplicate `rssAfterGcBytes` assignment was also exposed by a new p95 assertion (`KeyError: p95`) and removed. |
| 2 | An optional absent production jar directory can be modeled as input files without weakening classifier isolation. | Gradle validation failure -> build GREEN | `:app:game-engine:verifyRuntimeBaselineJarIsolation` | adopted | Initial real run failed before sampling because optional `@InputDirectory` did not exist. Corrected task completed `BUILD SUCCESSFUL in 1m 24s`. |
| 3 | The deterministic materializer and loader inventory must include the current world-scope schema fields. | current-1 failed twice -> progressed through snapshot load | Real probe logs and sealed inventory/policy checks | adopted | Added `world_id` to log inserts and `world_version`/`writer_epoch` to the world-state loader observation; rebound the checked-in policy to inventory SHA `419cb248...`. |
| 4 | Local surrogate snapshot expectations must follow bounded cold boot while DB source-row pressure remains 10x. | current-1 snapshot mismatch -> six-run GREEN | Python manifest/runner suites and real 3x2 capture | adopted | Local policy now expects zero retained global logs while preserving 10,256 vs 100,256 DB log rows. Related suites passed 46/46. Run `op123-local-20260813-final-v5` emitted six raw JSON and six non-empty JFR files plus analysis and summaries. |
| 5 | Production-shape validation must encode the same bounded loader contract instead of accepting impossible retained log counts. | Independent review `fix-required` -> 47-test GREEN | Fresh independent review plus positive/adversarial manifest tests | adopted; re-review cleared | Production and local manifests now both require zero cold-boot `globalLogs` and SYSTEM-log retained items while preserving DB/source/payload growth. The adversarial test rejects a resealed unbounded production manifest. |

Final observed p50/p95: harness setup+boot `current` 14,420/19,242.2 ms,
`cold10x` 30,604/45,250.6 ms; representative tick `current` 427/484.6 ms,
`cold10x` 248/326.3 ms. Mean after-GC RSS was 190,956,885 bytes and
196,057,771 bytes respectively; mean retained-heap proxy was 9,209,909 bytes
and 9,184,549 bytes. The result is local surrogate evidence only.

Approval pending: none for local-surrogate measurement. Production, live data,
capacity thresholds, deployment, and activation remain outside this loop.
