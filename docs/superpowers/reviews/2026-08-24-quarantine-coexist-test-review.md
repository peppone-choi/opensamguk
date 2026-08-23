# Quarantine coexistence regression test — independent review (2026-08-24)

Reviewer: separate reviewer lane (code-reviewer), not the authoring pass.
Commit under review: 96a81bd4 on `work/opensamguk/quarantine-coexist-test`, one commit on top of `origin/main` (0bb5f522).

Scope: tools/agent-system/tests/test_check_test_xml.py

## What the diff adds

- `OTHER_SKIPPED_XML` fixture: a second single-testcase Gradle suite, `fake.LongSimReplayGateTest#12 month structural replay matches PHP golden()`, skipped for a non-Docker reason (`LONGSIM_SCHEMA4_CANDIDATE_DIR not set`).
- `test_quarantined_and_unquarantined_skip_coexist_in_one_run`: writes both `SKIPPED_XML` and `OTHER_SKIPPED_XML` into the same module, registers only the LongSim key in a per-test quarantine JSON, and asserts exit 1 plus both a `QUARANTINED:` line and a `SKIPPED:` line on stderr.

## Verification performed

1. Read `tools/agent-system/check_test_xml.py` in full rather than trusting the test's assertions. The key the test registers (`classname#name`) is exactly the key the checker builds at `check_test_xml.py:113-116`; the partition into `quarantined` / `unquarantined` (`:127-128`), the `QUARANTINED:` print (`:135-137`), and the `if unquarantined:` red path (`:141-156`) all line up with what the test asserts.
2. Ran the full suite: `python3 -m unittest discover -s tools/agent-system/tests -p 'test_*.py' -v` — 9 tests, OK, ~1.4s. The new test passes.
3. Mutation-tested the checker against a scratch copy of `tools/` to confirm the test is not trivially satisfied:
   - quarantine loading neutered (`quarantine = {}`) → 2 failures, including the new test (its `QUARANTINED:` assertion).
   - `QUARANTINED:` print replaced with `pass` → 2 failures, including the new test.
   - blanket-silence bug injected (`if unquarantined and not quarantined:`) → exactly 1 failure, and it is the new test. No other test in the file catches this mutation.

Mutation 3 is the load-bearing result: the new test is the only regression guard in the repo for "one quarantined skip must not silence an unrelated skip in the same run". That alone justifies the diff.

## Assessment against the stated properties

- Transparency of a quarantined skip when it is the *only* skip is already covered by the pre-existing `test_quarantined_skip_passes_without_opt_out` (exit 0 + `QUARANTINED` + ticket URL). The new test extends transparency to the mixed case rather than duplicating the solo case; combined coverage is complete.
- Partial coverage (exit 1 driven by the unquarantined skip) is genuinely exercised: `failures`/`errors` are 0 in both fixtures, so the only path to returncode 1 is the unquarantined-skip branch.

## Fixture realism

Two separate `TEST-<class>.xml` files under one module's `build/test-results/test/` matches Gradle's one-file-per-test-class output. Suite-level `skipped="1"` is consistent with the single `<skipped>` testcase in each file, so the `total_skipped` accounting and the per-testcase scan agree. Real Gradle output also carries `<system-out>`/`<system-err>`, which the parser ignores; omitting them is fine.

## Isolation / determinism

Clean. `setUp` allocates a fresh `tempfile.TemporaryDirectory` per test and `tearDown` removes it; `--repo-root` is absolute so cwd does not matter; `_run` pops `OPENSAM_ALLOW_SKIPPED_IT` from the inherited env; the quarantine file is written inside the tmpdir and passed via `--quarantine`, so the repo's real `tools/agent-system/skipped_it_quarantine.json` is never consulted. No shared state, no ordering dependence, no network, no clock dependence.

## Findings

- [LOW, confidence HIGH] `tools/agent-system/tests/test_check_test_xml.py:157-158` — the test asserts the presence of both lines but never asserts that `SKIPPED: fake.LongSimReplayGateTest` is *absent*. A regression that printed a quarantined test on both lists would still pass. Suggested (non-blocking) addition: `self.assertNotIn("SKIPPED: fake.LongSimReplayGateTest", result.stderr)`.
- [LOW, confidence HIGH] `tools/agent-system/tests/test_check_test_xml.py:126-137` — pre-existing, not introduced by this diff: `test_unquarantined_skip_alongside_quarantined_still_fails` has no actually-quarantined skip in its run (the registered key matches no testcase), so its name overstates what it checks. The new test is the real version of that scenario; consider renaming the older one later.

No CRITICAL/HIGH findings. No security surface (test-only change, no network, no secrets, no shell interpolation).

## Positive observations

- Choosing a non-Docker skip reason for the second fixture mirrors the actual #517 incident, so the fixture documents *why* quarantine exists rather than just exercising a code path.
- The quarantine key is written out in full in the test rather than computed, which makes an accidental key-format change in the checker fail loudly.
- Per-test quarantine JSON in the tmpdir keeps the test independent of the repo's live quarantine registry.

Verdict: cleared
