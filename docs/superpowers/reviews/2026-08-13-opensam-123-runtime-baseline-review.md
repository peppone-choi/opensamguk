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
source hashes. Its sanitized derived artifacts are `analysis.json` SHA-256
`231b7d266e67ddb9b3b2b582e19600f965a4028a6567b68fbeaca034f21eab12`,
`summary.json` SHA-256
`fca6312f0654e0ba7a0b6bd4a8e9a1e8332a9c70faa77a3a235c27cd437f2422`,
and `summary.md` SHA-256
`6ff165ccf882140acef16341f55a9d74a36cb4f5509a3842389e9e169cbc8c86`.
The regenerated summary reports retained heap p50/p95/spread as
`9206912.0`/`9224501.6`/`30096.0` for `current` and
`9183120.0`/`9189621.6`/`10160.0` for `cold10x`.

### Sanitized reanalysis source-hash manifest

The following is the complete 15-entry `analysis.json` source-hash manifest
from that derived-only run. It identifies sanitized local artifacts only; none
is production data or a committed raw/JFR capture.

| Artifact | SHA-256 |
| --- | --- |
| `raw/current-1.json` | `59857eef3b583c17cdebb6caffbfa17b1601339189bbd45153ce11479a69bea7` |
| `jfr/current-1.jfr` | `735284417abb4f585f758c2186e03726be4474f2bdb2cf995698f7e51c49b145` |
| `raw/current-2.json` | `ff50655cee21676cd6af43fffc0f3d25eea6167015b03087098d139e20114119` |
| `jfr/current-2.jfr` | `65fbd8a8b6a56ff5b42ee93a95e1602be2b50dee05d3431f6bd751962effdfd7` |
| `raw/current-3.json` | `dfa7f59519c46c4b440c48e990ca1395ff50298717dd4ca15d332ccc67dfef55` |
| `jfr/current-3.jfr` | `82d47a5a6e11e445784d9c4ecb4ec2f1394363c891baa8950712289e188efdd1` |
| `raw/cold10x-1.json` | `26fea4307ee1aafa99b47460f0c1f4557e53a3b5a4fe444b1f20e3ec08c80bf2` |
| `jfr/cold10x-1.jfr` | `50261a735a940e1cdde981d45f6c71a1e0da799556e3d1ea8e95a0c27c73d64b` |
| `raw/cold10x-2.json` | `2b81eb3a955f8259c6b4818b05bc0b134e6bf2ffad00b3ddc72442ac2e87d554` |
| `jfr/cold10x-2.jfr` | `5c1dec42a1552807868aefe3f3bcc4331ae80413dbefb666bfffe49af57f1371` |
| `raw/cold10x-3.json` | `ace043dff6f4951d7cb8444e9623700382ccfd50133d451104cac44025ed7dc2` |
| `jfr/cold10x-3.jfr` | `3303183eebde82794cb4aa7318139dc795cae52250a3856d000ff981a054d928` |
| `manifest/local-sanitized-aggregate-policy.json` | `9728a7cc18273c398249415aec81b21f37873d1cccbd622d1b00c05aa0f7d3b3` |
| `fixture/current.json` | `0f3e1d7fed4fd32be620c3f198cdbcf69383617ab6dc486e3f17dbbd37a065ce` |
| `fixture/cold10x.json` | `7145d822fc761dd8e60f9cabb010eb9c8919a67c6c71f8336f424ce13caddf36` |

An additional `:app:game-engine:test --rerun-tasks` regression attempt produced
no fresh XML after more than 20 minutes and was terminated. It is an incomplete
environment/tool result, not a JVM pass claim; the remediation changed no JVM
source and retains its direct Python, lint, artifact, strict, and independent
review evidence above.
