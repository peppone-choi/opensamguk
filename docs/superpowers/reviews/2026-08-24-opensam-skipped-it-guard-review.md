# skipped-it-guard (#517) independent review

Scope: `.github/workflows/ci.yml`, `scripts/agent/verify-changes.sh`, `tools/parity/gate.sh`, `tools/agent-system/check_test_xml.py`, `tools/agent-system/tests/test_check_test_xml.py`

Verdict: cleared

An independent `code-reviewer` subagent (not the implementer) reviewed PR #517 against the actual diff and re-ran verification itself rather than trusting the PR description.

- Independently re-ran `python3 -m unittest discover -s tools/agent-system/tests -p 'test_*.py' -v`: 4/4 `ok` at review time (now 8/8 after the quarantine addition below).
- Confirmed `app/gateway-api/build.gradle.kts` has zero diff against `origin/main`.
- Confirmed multi-file XML aggregation, the missing-XML-fails-loudly branch, and that the opt-out cannot exit 0 silently (the skip banner always prints before the env-var branch).
- Confirmed the glob `build/test-results/test/TEST-*.xml` covers every module (single `tasks.test` per module, no custom output location).
- No CRITICAL/HIGH findings. 2 MEDIUM + 3 LOW findings, all addressed before merge:
  1. **[MEDIUM]** CI's skip-check step only *refused* the opt-out env var in a prior step; an `env:` block added elsewhere in the job could still reach it. Fixed: `env: { OPENSAM_ALLOW_SKIPPED_IT: "" }` pinned directly on the gate step (`ci.yml`).
  2. **[MEDIUM]** `scripts/agent/verify-changes.sh` had no `app/board-api/` mapping, so CI gated 7 modules while local verification silently covered 6. Fixed: mapping added.
  3. **[LOW]** `ci.yml`'s comment cited `docs/agent/verification.md` for the opt-out, but that doc never mentioned it. Fixed: doc updated.
  4. **[LOW]** The missing-XML branch (a deleted test class / excluded test task reading as green) had no regression test. Fixed: `test_missing_xml_fails_loudly` added.
  5. One MEDIUM **open question, not blocking, not fixed**: Gradle build-cache restore could in principle serve a cached-green result from a run where Docker was actually down at execution time; this is a pre-existing property of `./gradlew build` without `--rerun-tasks`, independent of this PR, and is recorded as a known residual gap rather than claimed as covered.
  - One MEDIUM finding explicitly **deferred by design, not fixed**: deleting a Docker-gated test class entirely (or excluding it from the test task) produces zero skips and a green gate — the guard judges XML `skipped` attributes, not "did every expected test class run." A committed per-module minimum-test-count baseline was suggested as the fix but is a separate, larger policy decision (staleness maintenance burden) than "surface silent skips," and was left for a follow-up rather than folded into this PR's scope.

## Real-world confirmation (post-review addendum, 2026-08-24)

The guard caught a real, unanticipated skip on PR #517's first remote CI run — Docker was available on the `ubuntu-latest` runner, and the gate still fired:

```
=== 1 SKIPPED TEST(S) DETECTED across 675 suite(s) ===
  SKIPPED: opensamguk.engine.golden.LongSimReplayGateTest#12 month structural replay matches PHP golden()
           (app/game-engine/build/test-results/test/TEST-opensamguk.engine.golden.LongSimReplayGateTest.xml)
```

Root cause traced to source, not guessed: `LongSimReplayGateTest.kt:942-945` gates on
`assumeTrue(candidateDir != null, ...)`, and `candidateDir` (`LongSimReplayGateTest.kt:616-624`)
resolves from `LONGSIM_SCHEMA4_CANDIDATE_DIR`/`LONGSIM_CANDIDATE_DIR` (system property or env),
an external PHP golden candidate directory never wired into `ci.yml` — matching `CLAUDE.md`'s
documented P5 backlog item "long-sim multi-turn (gate dim c)". Filed as
[opensamguk#521](https://github.com/peppone-choi/opensamguk/issues/521); the test itself is out
of scope for #517 and was not touched.

Registered a name-scoped, ticket-required quarantine (`tools/agent-system/skipped_it_quarantine.json`)
so this one known skip passes without weakening the guard for anything else — a quarantine entry
with an empty ticket is rejected by the loader (`load_quarantine`) rather than silently accepted,
and an unrelated unquarantined skip in the same run still fails regardless. Both behaviors are
locked in by `test_quarantine_entry_without_ticket_is_rejected` and
`test_unquarantined_skip_alongside_quarantined_still_fails`.
