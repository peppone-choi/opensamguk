# OPENSAM-123 runtime baseline independent review

Date: 2026-08-13
Scope: OPENSAM-123 `app/game-engine`, `tools/cqrs`, manifest/policy contracts, and local v5 artifacts
Verdict: cleared
Reviewer: independent `fable-deep-reasoner` agent (read-only)

## Initial verdict: fix-required

The first pass found two major issues:

1. Production-shape manifest validation still required the superseded unbounded
   snapshot-log model even though `WorldSnapshotLoader` strips cold-boot
   `globalLogs` and history metadata.
2. The branch was two commits behind `origin/main`, which made the prospective
   diff delete two unrelated OPENSAM-31 evidence documents.

## Remediation reviewed

- Both production and local manifest paths now select the bounded snapshot-log
  contract explicitly. `globalLogs`, SYSTEM action retained items, and SYSTEM
  history retained items must be zero, while table/source-row/payload growth
  remains exact 10x.
- A resealed, internally coherent unbounded production manifest is rejected by
  an adversarial test. The standard complete production manifest is the
  positive bounded case.
- The branch-behind condition is a pre-PR gate: rebase onto `origin/main` must
  preserve both OPENSAM-31 documents before push.

## Evidence

- Python discovery: 47 tests passed; 3 Docker/JAR opt-in tests skipped.
- Actual run `op123-local-20260813-final-v5`: build successful in 9m20s;
  six raw JSON and six non-empty JFR files; all 15 analysis source hashes
  matched; both profiles report `n=3`.
- Summary includes p50, p95, and run-to-run spread for RSS, heap
  used/committed, and every database/snapshot row count.
- `git diff --check` passed.
- Repeated generic Fablize failure notices were isolated as wrapper noise:
  the underlying commands returned explicit normal output and exit status.

## Final verdict: cleared

No remaining blocker, major, minor, or question finding in the OPENSAM-123
source/artifact scope. Clearance is conditional only on preserving the two
behind-main OPENSAM-31 documents during the pre-PR rebase.

## PR #401 CodeRabbit remediation re-review

The subsequent CodeRabbit review correctly found that retained-heap summary
metrics lacked p50/p95, that row-count fixtures did not exercise percentile
or spread behavior, and that new `summary_metric` boolean arguments were
positional. The minimal remediation:

- passes `percentiles=True` by name for all affected summary metrics,
- emits retained-heap p50/p95 alongside its existing run-to-run spread, and
- makes the three-sample row and retained-heap fixtures vary, asserting each
  p50, p95, and spread.

The first remediation critique found one additional test-adequacy gap (the
retained-heap spread assertion); it was added before the final independent
re-review. The final exact-diff independent verdict is `cleared`.

Fresh evidence: 47 Python tests passed with 3 documented opt-in skips;
`uvx ruff@0.16.1 check --select FBT003` passed; a derived-only reanalysis of
`op123-local-20260813-final-v5` succeeded and recorded 15 raw/JFR/fixture/policy
source hashes. The detailed, sanitized evidence record is
`.omo/evidence/2026-08-13-pr401-coderabbit-remediation.md`.

An additional `:app:game-engine:test --rerun-tasks` regression attempt produced
no fresh XML after more than 20 minutes and was terminated. It is an incomplete
environment/tool result, not a JVM pass claim; the remediation changed no JVM
source and retains its direct Python, lint, artifact, strict, and independent
review evidence above.
