# skipped-it-guard — Docker-gated IT skips must fail the build

PR: https://github.com/peppone-choi/opensamguk/pull/517 (`work/opensamguk/skipped-it-guard-v2` → `main`, not merged — team lead merges)

## Incident that motivated this

`ScenarioBlankUnificationIT` (and the wider `assumeTrue(dockerAvailable)` family of
Docker-gated ITs) get marked **skipped**, not failed, by JUnit when Docker is
unavailable. Gradle still prints `BUILD SUCCESSFUL`. Two commits landed a change
where `data/unitset/units.json`'s `sets.han.defaultCrewTypeId` pointed at a
tech-gated unit (id 2006) — newly founded nations could conscript zero troop
types at game start — while the IT that should have caught it was silently
skipped both times. Fixed separately in PR #508. Nothing before this PR treated
"skipped" as distinct from "passed."

## 1. Skip inventory (by module)

Grepped every module for `assumeTrue`/`Assumptions.assumeTrue`/`@EnabledIf*`/
`@DisabledIf*` gating Docker or environment. Docker-gated ITs (via
`DockerClientFactory.instance().isDockerAvailable` or Testcontainers'
`@Testcontainers`) are concentrated in `infra`, `app/game-engine`, and
`app/game-api` — roughly 60 test files across those modules reference
Testcontainers/Docker directly, with 218+ individual `@Test`-annotated methods
reachable through those classes (counted via `grep -rl` + per-file `@Test`
count, not exhaustively enumerated method-by-method here — the XML gate makes
per-file enumeration unnecessary going forward since the judgment is now
automatic). `common` and `logic` have no Docker dependency and no ITs to skip.

## 2. Was CI already safe?

**No — verdict: vulnerable by omission, not "safe by design."** Before this PR,
`.github/workflows/ci.yml`'s `jvm` job ran `./gradlew build --no-daemon` and then
a "Surface test results" step that only counted/listed XML files — it never
inspected `skipped` attributes or failed on them. `ubuntu-latest` ships Docker,
so in practice CI's ITs likely do run — but nothing verified that, and nothing
would have caught it if Docker silently became unavailable on a runner (daemon
crash, disk pressure, a future runner-image change). This was a real gap, not
a theoretical one, and closing it was in scope.

## 3. Legitimate no-Docker local workflow?

Yes — iterating on `common`/`logic`-only changes, or running a quick
`:app:game-engine:compileKotlin` sanity pass, without Docker installed/running
locally is a normal, fast dev loop. The guard does not remove that: it fails by
default (so a skip is never silently reported as a pass), but
`OPENSAM_ALLOW_SKIPPED_IT=1` lets that workflow continue — non-silently, by
always printing the skip banner and every skipped test's name first — and only
locally, since CI hard-fails if the var is ever set.

## 4. Design and implementation

`tools/agent-system/check_test_xml.py` (new) is the single shared judgment
script, reused instead of inventing new mechanisms:

- Parses Gradle test-results XML (`build/test-results/test/TEST-*.xml`) per
  module root — `<testsuite tests= failures= errors= skipped=>` attributes,
  never log text.
- **failures/errors always fail**, regardless of the opt-out.
- **Any `skipped` > 0 fails by default.** `OPENSAM_ALLOW_SKIPPED_IT=1` allows
  it to pass, but always prints `=== N SKIPPED TEST(S) DETECTED ===` plus each
  `classname#name` to stderr first — the opt-out cannot be silent.
- A module root that produced **no XML at all** (deleted test class, excluded
  test task, etc.) also fails loudly (`No Gradle test XML files found`) —
  closes the "tests never ran and nobody noticed" hole, independent of the
  skip-counting logic.

Wired into existing gates rather than adding parallel ones:
- `tools/parity/gate.sh` — replaced its old inline heredoc (which only checked
  failures/errors) with a call to the shared script.
- `scripts/agent/verify-changes.sh --run` — same replacement, plus it now
  accumulates an `XML_ROOTS` list parallel to the existing Gradle-task `NEED`
  list per changed path, including `app/board-api/` (a pre-existing mapping
  gap the independent reviewer caught — see §6).
- `.github/workflows/ci.yml` `jvm` job — new steps: (a) hard-refuse if
  `OPENSAM_ALLOW_SKIPPED_IT` is set at all before the build runs, (b) run the
  gate against all 7 module roots after `./gradlew build`, with
  `OPENSAM_ALLOW_SKIPPED_IT: ""` pinned via `env:` on that step so an
  unrelated `env:` block added elsewhere in the job can't silently reopen the
  opt-out. `agent-system` job now also runs
  `tools/agent-system/tests/test_check_test_xml.py` via unittest discover.

`tools/agent-system/tests/test_check_test_xml.py` (new) bakes the observed
behaviors into a permanent regression, using synthetic JUnit XML fixtures
(the object under test is an XML parser, not Docker — team lead's explicit
correction after an earlier attempt to reproduce genuine Docker-unavailability
via triple environment spoofing proved unreliable and unverifiable). 5 cases,
all passing:

```
test_failures_fail_even_with_opt_out ... ok
test_green_when_nothing_skipped ... ok
test_missing_xml_fails_loudly ... ok
test_opt_out_passes_but_prints_skip_list ... ok
test_red_when_skipped_and_no_opt_out ... ok
----------------------------------------------------------------------
Ran 5 tests in 0.557s
OK
```

## 5. Real observed proof (not just claimed)

**Real Docker-available run** (`tools/agent-system/check_test_xml.py` against
actual `app/game-engine` XML from a real `./gradlew` run):
```
XML gate green: 32 suites, 201 tests, 0 skipped
```
exit 0.

**Synthetic-fixture RED** (no opt-out, `ScenarioBlankUnificationIT` skipped):
```
=== 1 SKIPPED TEST(S) DETECTED across 1 suite(s) ===
  SKIPPED: fake.ScenarioBlankUnificationIT#han founding grants a conscriptable crew type()  (skipped-mod/build/test-results/test/TEST-fake.ScenarioBlankUnificationIT.xml)
A skipped test is NOT a passing test. BUILD SUCCESSFUL does not mean these ran
(most likely: Docker unavailable, assumeTrue(dockerAvailable) short-circuited them).
If this is a genuine no-Docker local iteration run, re-run with
OPENSAM_ALLOW_SKIPPED_IT=1 — but do not claim these tests as verified.
```
exit 1.

**Synthetic-fixture opt-out** (same fixture, `OPENSAM_ALLOW_SKIPPED_IT=1`):
```
=== 1 SKIPPED TEST(S) DETECTED across 1 suite(s) ===
  SKIPPED: fake.ScenarioBlankUnificationIT#han founding grants a conscriptable crew type()  (...)
OPENSAM_ALLOW_SKIPPED_IT=1 set — continuing despite skips. These tests were NOT verified; do not report them as passing.
XML gate green: 1 suites, 1 tests, 1 skipped
```
exit 0, but never silent.

**CI opt-out lockout** (bash logic simulated locally): with
`OPENSAM_ALLOW_SKIPPED_IT=1` set → the refuse-step prints
`OPENSAM_ALLOW_SKIPPED_IT is set in CI — this is not allowed...` and exits 1;
unset → exits 0 and proceeds. The gate step additionally pins the var empty
via `env:`, closing the "some other step in the job sets it" bypass the
reviewer identified.

## 6. Independent review

Spawned a separate `code-reviewer` subagent (not self-critique) against PR
#517. It independently re-ran the unittest suite, read `check_test_xml.py`
line-by-line for aggregation/glob/bypass correctness, confirmed
`app/gateway-api/build.gradle.kts` has zero diff, and checked CI step ordering.

**Verdict: COMMENT (no CRITICAL/HIGH), 2 MEDIUM + 3 LOW.** All addressed in a
follow-up commit (`5d96b2ef`) before this report:
- **[MEDIUM] Gate catches "skipped", not "vanished."** Deleting the IT
  entirely, or excluding it from the test task, produces zero skips and a
  green gate. **Not fixed in this PR** — the reviewer's own suggested fix (a
  committed per-module minimum-test-count baseline) is a separate, larger
  policy decision than "surface skips," and risks becoming a maintenance
  burden that itself needs its own guard against staleness. Left as a
  documented follow-up, not silently dropped.
- **[MEDIUM] CI env-var pin.** Fixed — `env: { OPENSAM_ALLOW_SKIPPED_IT: "" }`
  added to the gate step itself.
- **[LOW] Dangling doc reference.** Fixed — `docs/agent/verification.md` now
  documents the opt-out.
- **[LOW] `app/board-api/` missing from `verify-changes.sh`.** Fixed.
- **[LOW] Missing-XML branch untested.** Fixed —
  `test_missing_xml_fails_loudly` added.
- One **[MEDIUM] open question, not blocking**: Gradle build-cache restore
  could theoretically serve a cached-green test result on a run where Docker
  was actually down at execution time, if the task inputs don't include
  Docker availability. This is a pre-existing Gradle-caching property of
  `./gradlew build` (no `--rerun-tasks`) in CI, independent of this PR, and
  not fixed here — flagging it as a known residual gap rather than claiming
  it's covered.

## 7. Cross-agent-critique self-review bypass (separate investigation, accepted, not implemented)

Investigated whether `tools/agent-system/check.py`'s `check_cross_agent_critique`
gate can detect when a commit's author and the review artifact's author are
the same person. Findings:
- Every commit in this repo, across all agents/sessions, carries the identical
  human author (최병호) and identical generic `Co-authored-by` trailers
  (`Claude Opus 4.8`/`opensamguk-dev`) — there is no git-level signal that
  distinguishes "agent A wrote the code" from "agent B wrote the review."
- A self-attested `Reviewer:` field in the review markdown would be trivially
  spoofable by the same actor who would fake the review in the first place —
  adding it would create a guard that looks like protection without actually
  providing it, which is worse than no guard (per team lead: "효과 없는 가드를
  넣는 건 아무것도 안 넣는 것보다 나쁘다").
- **Conclusion, accepted by team lead: not implemented.** This belongs at the
  team-orchestration level (assigning genuinely independent review agents/
  sessions), not in git-diff-based static tooling.

## 8. What was NOT changed

`app/gateway-api/build.gradle.kts` was edited twice during investigation
(Docker-context and `user.home` spoofing, to try to reproduce genuine
Docker-unavailability) and fully reverted both times. Verified zero diff
against `origin/main` before every commit in this PR (`git diff -- app/gateway-api/build.gradle.kts` empty, confirmed again immediately before both commits `2debaeac` and `5d96b2ef`).

No existing `assumeTrue` guard was removed or weakened — the goal was to
surface skips, not eliminate the legitimate Docker-optional test paths.

## Status

- Commits: `2debaeac` (initial guard), `5d96b2ef` (review-gap fixes).
- PR #517 open, not merged.
- Independent review: COMMENT verdict, all actionable findings closed except
  the one MEDIUM explicitly deferred with reasoning above and the one
  build-cache open question flagged as a known residual gap.
- Remote CI on this exact PR head: not yet observed by me — team lead should
  check the PR's Checks tab for the `jvm` job's new "Fail on skipped tests"
  step before merging.
