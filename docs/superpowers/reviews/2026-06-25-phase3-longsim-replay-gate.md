# Review: Phase 3 long-sim replay gate scaffold

## Verdict: cleared

The change is acceptable as a scaffold, not as an active full parity gate. The active test now verifies that committed PHP long-sim fixtures expose the replay oracle fields, while the full 12-month structural replay remains `@Disabled` with an explicit blocker.

## Evidence

- PHP oracle fields come from `tools/php-golden/capture_longsim.php:314-340` and `:376-404`: `monthlySeedString` and `monthlyRngDraws` live in each capture file; manifest points carry only summary `rngDraws`.
- Local probe before disabling the full replay showed the first real divergence at `gameMonths=12`: PHP captured `state.nation` size `12`, Kotlin replay captured size `5`.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests opensamguk.engine.golden.LongSimReplayGateTest` passed with `tests=2`, `skipped=1`, `failures=0`, `errors=0`.

## Notes

- The disabled full replay is load-bearing documentation of the next blocker. Do not turn it on by weakening expected state or by dropping PHP capture fields.
- The next loop should narrow the `nation=12` vs `nation=5` divergence to the first AI selection, command execution, or founding/raising side effect that differs.
