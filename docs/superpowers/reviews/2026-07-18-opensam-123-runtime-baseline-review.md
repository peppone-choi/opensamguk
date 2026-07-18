# OPENSAM-123 independent code and artifact review

Scope: Changed areas include `.codex/`, `app/`, and `tools/`; `.codex/config.toml` is a pre-existing user-owned diff observed and excluded from this implementation review, while the relevant `app/` and `tools/` changes were reviewed.
Verdict: cleared

## Final verdict

- **Result:** PASS
- **codeQualityStatus:** WATCH
- **recommendation:** APPROVE
- **Implementation blockers:** none
- **Remaining W0 acceptance blocker:** an approved sanitized production-shape manifest and live row-count calibration do not exist yet. The current capture is valid reproducibility evidence for a synthetic seed proxy, not a production capacity threshold.

This verdict supersedes the earlier provisional PASS and the intervening security-review BLOCK. The BLOCK was valid for the prior symlink-following analyze-only implementation; the frozen follow-up closes it with fail-closed path preflight, planned derived targets, atomic writes, and real-filesystem adversarial tests.

## Reviewed goal and scope

The goal was a reproducible, isolated JDK 21 / 2 GiB baseline for retained heap, harness setup+boot, snapshot/tick latency, and JFR evidence over three `current` plus three `cold10x` samples, without changing the production game-engine artifact or runtime path.

Reviewed implementation and evidence:

- `app/game-engine/build.gradle.kts`
- `app/game-engine/src/baseline/kotlin/opensamguk/engine/baseline/CqrsBaselineMain.kt`
- `tools/cqrs/run-runtime-baseline.py`
- `tools/cqrs/test_run_runtime_baseline.py`
- `docs/loops/cqrs-runtime-safety-2026-07-18/OPENSAM-123.md`
- `app/game-engine/build/cqrs-runtime-baseline/w0-harness-20260718d/`

## Corrections verified

- The superseded `w0-harness-20260718c` classifier inherited test inputs. The corrected build uses a dedicated `baseline` source set, dedicated jar directory, runtime-only Flyway/PostgreSQL additions, and an isolation task that rejects test/game-api libraries or any baseline classifier in production `build/libs` (`build.gradle.kts:90-100,127-129,160-182`).
- JFR pause durations are joined to `GarbageCollection.cause` by `gcId`; `System.gc()` is separately reported as `forcedRetainedHeapProbe`, while the primary operational bucket excludes it (`run-runtime-baseline.py:362-437`).
- Analyze-only mode now resolves a real run directory, requires real direct-child `raw`, `jfr`, and `logs` directories, rejects source and derived-target symlinks before invoking JFR, and allows writes only to a preflighted target set (`run-runtime-baseline.py:641-759`). Derived text is written through same-directory `mkstemp` files and `os.replace` (`run-runtime-baseline.py:762-786`).
- The prior mocked immutability test gap is closed. The suite now runs the real analyzer against a complete six-pair filesystem fixture, asserts all source hashes remain stable, verifies the exact confined derived set, and covers raw/JFR source, logs-directory, external derived-target, and in-run derived-target symlink rejection before any JFR command (`test_run_runtime_baseline.py:388-520`). The main dispatch test remains correctly scoped to proving the capture branch is unreachable.
- Percentile metadata now distinguishes three-run interpolation from pooled, event-weighted JFR pause interpolation (`run-runtime-baseline.py:931-937`). The former `bootDurationMs` ambiguity is also resolved without changing the captured timer: machine metadata and human output label it `harnessSetupAndBoot` and enumerate Flyway, seed, fixture insert, snapshot load, and world construction (`run-runtime-baseline.py:938-947,972-983`; `OPENSAM-123.md:74-76`).
- Cleanup authorization now has a direct behavior test proving foreign labels do not issue `docker rm` and owned labels do (`test_run_runtime_baseline.py:372-386`).

## Independent verification

- `PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s tools/cqrs -p 'test_run_runtime_baseline.py' -v` passed **20/20** tests in 0.205 s. This reviewer observed every real-layout success and adversarial symlink case pass.
- Root separately observed Python compilation, 20/20 tests, the real `--analyze-run-id w0-harness-20260718d` command exit 0, and unchanged hashes for all twelve raw/JFR inputs.
- This reviewer independently matched all twelve current raw/JFR bytes to `analysis.json.sourceArtifactSha256`; the run contains no symlink or leftover analyzer temporary file.
- The regenerated `summary.json` remains schema v2 and now contains `threeRunMetricPercentileMethod`, `jfrGcPhasePausePercentileMethod`, and `bootDurationMetric.scope=harnessSetupAndBoot`; `summary.md` uses the same setup+boot label.
- The corrected capture build log contains `BUILD SUCCESSFUL in 2m 57s` and capture-classifier SHA-256 `412ba32d5f92797952bb5278d8eb34505dbfb097c2ec598555feb8980185f8f7`. The later isolated rebuild SHA `446e9ee45734f1205124ccd37afa3ae3e7b09a63c19919a5d92e96a19ce90f46` remains correctly distinguished from capture provenance.
- The six JFR extracts each contain two forced `System.gc()` collections. Aggregate evidence remains `47 operational / 6 forced` events for `current` and `54 operational / 6 forced` for `cold10x`.
- `git diff --check` for the reviewed follow-up files was clean. Repeated generic Fablize “tool failure” notices were isolated as the known wrapper baseline because the underlying commands returned explicit exit 0 and complete output.

## Skill-perspective check

The `omo:remove-ai-slops` and `omo:programming` skills, including the Python guidance, were consulted for the final test and maintainability judgment.

- The security tests are not deletion-only, tautological, prompt-based, or constant-mirroring-only. They use real files and would fail if the preflight followed the crafted links or wrote before rejection.
- The earlier main-dispatch test is no longer used as evidence for analyzer immutability; the real-layout test owns that claim.
- The oversized multi-responsibility files and untyped JSON mappings below still violate both skill perspectives.

## Findings by severity

### CRITICAL

None.

### HIGH

None. The previously reported symlink-confinement HIGH is closed by the frozen implementation and adversarial tests above.

### MEDIUM

1. **The harness remains concentrated in oversized, multi-responsibility modules with untyped JSON plumbing.** Current pure LOC is 1,016 for `run-runtime-baseline.py`, 509 for its test module, and 413 for `CqrsBaselineMain.kt`; none carries a `SIZE_OK` rationale. The runner combines JDK/Gradle discovery, Docker lifecycle and cleanup authorization, capture, filesystem security, raw validation, JFR parsing, statistics, rendering, and CLI dispatch, while raw `Mapping[str, Any]` / `Map<String, Any>` shapes cross those layers. This is not a current correctness failure, but it raises review and schema-drift risk and violates both mandatory skill perspectives.

### LOW

1. **The classifier's direct argument parser silently defaults malformed `--base-rows` values.** `toIntOrNull() ?: DEFAULT_BASE_ROWS` turns `--base-rows=abc` into `10000`, and unknown/duplicate options are not rejected (`CqrsBaselineMain.kt:293-308`). The supported Python wrapper uses typed `argparse`, so the reviewed capture is unaffected, but direct jar invocation does not fail closed.

2. **Filesystem confinement is fail-closed for static crafted paths, not a hostile concurrent-filesystem security boundary.** The path checks are path-based rather than `dirfd`/`O_NOFOLLOW` operations, so a process able to swap directories between preflight and `mkstemp` could race them (`run-runtime-baseline.py:688-786`). This is acceptable for the documented process-owned local build-artifact workflow, but the analyzer should not be represented as safe against a concurrent malicious workspace writer.

## Approval boundary

The implementation and corrected six-sample artifact set are credible and reproducible enough to approve the OPENSAM-123 harness slice. They must not be promoted into production capacity limits until the separately owned sanitized-shape and live-calibration work is complete.
