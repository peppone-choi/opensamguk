# LEDGER — opensam-175-daemon-observability-2026-08-11

| Wheel | Hypothesis | Evidence | Decision | Notes |
|---|---|---|---|---|
| 0 | Existing daemon health is a valid baseline for a narrowed observability change. | `TurnDaemonHealthIndicatorTest`: 17 tests, 0 failures/errors, direct JDK 21 Gradle execution. | Adopt baseline | Existing behavior deliberately reports paused `UP`; recovery is only represented by the separate flush indicator. |
| 1 | Health and alerting should use successful-tick wall-clock age, derived from `tickSeconds`, rather than persisted game-clock lag. | Contract in `GOLDENSET.md`; parent selected B semantics. | Reworked pending focused rerun | An old `clock.lastTurnTime` with fresh ticks is expected `UP`; the old game clock is diagnostic only. Once at least one tick has succeeded, restart and pause/resume cannot substitute loop/resume age for that tick's wall-clock age. A never-ticked new loop alone uses loop uptime for startup grace. |
| 2 | A standalone alert route can observe bounded daemon state without invoking deploy. | Contract test was RED while the workflow was absent, then `PASS: daemon health alert workflow and script contracts`. | Adopt | Hermetic stubs proved alert POST payloads for paused, recovery-gated, stalled, unreadable, and failed-dispatch paths; catch-up with old game clock remained no-alert. Missing webhook configuration also fails closed. The workflow scans stopped as well as running named engines, and the test extracts and runs both recovery branches from `deploy.yml` against stubbed daemon status. |
| 3 | Paused/recovery health can become explicit non-UP states without changing tick age semantics. | Controlled RED: temporary old-`UP` assertion failed (19 tests, 1 failure). Restored GREEN: focused XML reports 19 tests, 0 failures/errors. | Adopt | `recovery_gated` is DOWN before paused; paused is OUT_OF_SERVICE; recovery mode is bounded to known values or UNKNOWN. |
| 4 | Independent review findings can be closed without broadening scope. | Independent `lazycodex-code-reviewer` report: two MEDIUM and two LOW findings; post-fix controlled Kotlin RED followed by focused GREEN. | Remediated pending re-review | Health now uses the exact successful-tick age after the first success; Docker inventory failure exits nonzero; alert classification respects Actuator DOWN before paused; the Spring component smoke was split into its own focused class. The hermetic workflow contract now executes an inventory-failure case. The controlled RED made only the restart case expect UP and failed 17 tests / 1 failure; after restoring it, the focused JDK 21 green completed in 3m09s with XML 17/0/0 for the decision table plus 1/0/0 for component registration. |
| 5 | The inventory-failure assertion is executable rather than merely source-shaped. | Controlled RED: temporarily changed only the contract's expected inventory error and observed `FAIL: expected output to contain 'ERROR: intentionally wrong inventory error'`. Restored GREEN: shell syntax, YAML parse, and the hermetic contract reported PASS. | Adopt | The test executes the extracted workflow run block with a stubbed failing `docker ps`; no live Docker engine or webhook is involved. |
| 6 | Recovery must dominate pause in every deploy verification branch. | Controlled RED: a stubbed `paused=true,recoveryReady=false` gate produced `FAIL: deploy workflow silently skipped a paused recovery gate`. Restored GREEN: recovery checks now precede pause in initial and polling paths, and the hermetic contract reported PASS. | Adopt | A paused recovery gate is a failed recovery condition, not a successful skip. |

## Tooling note

The initial focused Gradle command routed through the host context-mode wrapper
and produced neither usable output nor XML. The baseline was rerun directly with
JDK 21 and produced `BUILD SUCCESSFUL`; the focused XML recorded 17 passing tests.
That wrapper behavior is a harness limitation, not a product result.

The alert-contract harness initially exposed two authoring defects (an invalid
timestamp character class and doubly escaped fixture JSON). Both were fixed by
direct reproductions before the passing hermetic run. No production endpoint or
webhook was contacted.

The deploy-branch simulator initially compared a whitespace-padded `wc -l`
result, so its polling fixture selected the initial branch. It now uses an
unambiguous `awk` line count and executes both recovery-gated branches
fail-closed.

Two ad-hoc untracked-file whitespace probes also failed only because zsh reserves
`status` and `path` as shell variables. The final Bash probe with task-specific
variable names passed and made no workspace change.

The Fablize wrapper emitted generic tool-failure notices during several successful
read-only `rg`/`sed`/diff inspections. Each underlying command exited 0 and its
output was inspected; the notices are isolated wrapper telemetry and are not used
as validation evidence. Two local `apply_patch` attempts also failed before any
file mutation because JavaScript template interpolation saw a shell variable; the
escaped retry applied the intended narrow patch and is covered by the later
contract rerun.

The comment guard rejected a replacement KDoc in the flush-recovery test as
unnecessary. The contradictory legacy wording was removed rather than retained;
no executable flush behavior changed.

An orphaned focused Gradle process from an earlier context-mode run was identified
by its worktree path and stopped with SIGINT before it produced a terminal test
result. It is deliberately excluded from RED/GREEN evidence and released the
shared JVM slot.

## Review conditions

Before handoff, record focused engine test XML, hermetic alert/workflow contract
evidence, deploy recovery-gate evidence, diff scope, and independent-review
request/result. Production alert installation and deployment remain separately
gated.
