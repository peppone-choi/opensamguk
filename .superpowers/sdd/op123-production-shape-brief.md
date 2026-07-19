# OPENSAM-123 production-shape baseline completion brief

## Goal

Close the remaining OPENSAM-123 acceptance gap without promoting synthetic or inferred values into live calibration.

## Existing accepted evidence

- Branch: `codex/op-123-cqrs-runtime-baseline`.
- Existing corrected run: `w0-harness-20260718d`, 3 current + 3 cold10x samples, exact 2 GiB cgroup, JDK 21, G1, 60% max heap.
- Existing runner and analyzer: `tools/cqrs/run-runtime-baseline.py`; Kotlin probe: `app/game-engine/src/baseline/kotlin/opensamguk/engine/baseline/CqrsBaselineMain.kt`.
- The existing result is explicitly a synthetic scenario_1010 seed proxy and must remain labelled as such.

## Live calibration evidence available now

- GitHub Actions run `29151979765` (`Backup and Reset Game Server`, 2026-07-11) recorded pre-reset counts: `world_state=1`, `city=94`, `nation=19`, `general=598`.
- The same workflow did not record `log_entry`, history, rank, diplomacy, or payload-size distributions.
- EC2 SSH at `3.37.232.176:22` timed out and `https://sam.peppone.dev/health` timed out on 2026-07-18. Local AWS CLI auth is unavailable.
- The retained pg_dump is on the stopped host EBS, not a GitHub Actions artifact.
- Therefore no agent may infer the missing live history count or call the existing `baseRows=10000` live-calibrated.

## Required implementation outcome

1. Define a versioned, sanitized production-shape manifest contract that records source/provenance, observation time, table/snapshot cardinalities used by the fixture, payload-size assumptions, and SHA-256. It must reject missing required dimensions and must not contain PII, row contents, credentials, host paths, or hidden seeds.
2. Extend the baseline runner/probe only as necessary to consume the approved complete manifest and prove the observed fixture matches it. Preserve the existing synthetic mode and labels; no backward claim upgrade.
3. Add fail-closed tests for incomplete/tampered manifests and cross-profile shape equivalence with cold/history exactly 10x.
4. If a complete live calibration can be obtained safely from existing non-sensitive evidence, run 3 current + 3 cold10x under the existing exact JVM/container contract and record the resulting raw/JFR/SHA evidence.
5. If the missing live `log_entry`/history shape cannot be obtained without starting or accessing production, stop at the executable fail-closed manifest/tooling boundary and report the exact human authorization required. Do not fabricate or mark OPENSAM-123 complete.

## Ownership

The implementer owns only OPENSAM-123 files: `tools/cqrs/**`, `app/game-engine/src/baseline/**`, the baseline-specific block in `app/game-engine/build.gradle.kts`, `docs/loops/cqrs-runtime-safety-2026-07-18/OPENSAM-123.md`, and a new sanitized manifest schema/example or review artifact needed by this ticket. Do not edit `.ai/**`, `.codex/**`, OPENSAM-124 files, legacy, or golden fixtures.

## Verification evidence

- Python unit suite output.
- Baseline classifier isolation build tail with `BUILD SUCCESSFUL` if build inputs change.
- Manifest validation on a complete test fixture and rejection of incomplete/tampered fixtures.
- Real 3x2 measurement only if all required live dimensions are sourced; otherwise explicitly unrun/blocked.
- No commit, push, PR, deploy, production mutation, instance start, dependency addition, or golden edit.
