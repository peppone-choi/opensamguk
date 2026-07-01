# Turn daemon runner CI flake review

Verdict: cleared

Scope: `TurnDaemonRunnerTest.enabled runner drives runTick when a tick is due`.

Observed failure:
- GitHub Actions Build + Deploy run `28514539825`, job `build-jvm`, failed at `TurnDaemonRunnerTest.kt:68`.
- The failing assertion read `successfulTicks >= 1` immediately after the stub `runTick` latch fired.

Root cause:
- The test latch was released inside the stub `runTick` before `TurnDaemonRunner` updated `lastTickCompletedAt`, `successfulTicks`, and `consecutiveFailures`.
- On a slower or differently scheduled CI worker, the test could read diagnostics in that gap.
- Production behavior was not implicated; the runner already records success after `runTick` returns.

Fix reviewed:
- Keep the existing cadence latch so the test still proves `runTick` was called.
- Add a bounded wait for `successfulTicks >= 1` and `lastTickCompletedAt != null` before asserting the exposed diagnostics.
- No daemon production code, tick ordering, DB flush behavior, or PHP parity behavior changed.

Verification:
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests '*TurnDaemonRunnerTest*' --rerun-tasks`
- XML result: `TEST-opensamguk.engine.run.TurnDaemonRunnerTest.xml` had `tests=7 failures=0 errors=0 skipped=0`.

Residual risk:
- Kotlin LSP is unavailable in this local environment (`kotlin-lsp` not installed), so compile/test output is the source-level check for this slice.
