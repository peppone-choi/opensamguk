# OPENSAM-138 Bounded Boot Gate Review

Date: 2026-07-23
Scope: `.codex/` pre-existing Agent OS concurrency WIP baseline-separated; `.ai/`, `app/game-engine/`, `docs/`, `infra/`, and `logic/` OPENSAM-138 / ARCH-S5-T2 build-only bounded boot slice.
Reviewer: `lazycodex-gate-reviewer` (`019f91f0-6fff-7bc2-813e-e21ad770cc57`) final re-review.
Verdict: cleared

## Reviewed Artifacts

- `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPreUpdateHook.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldActionContext.kt`
- `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldEventContextFactory.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HotColdWorldCatalogGuardTest.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/WorldSnapshotLoaderArchiveIT.kt`
- `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt`
- `infra/src/main/kotlin/opensamguk/infra/read/ArchiveHistoryReader.kt`
- `infra/src/main/kotlin/opensamguk/infra/read/StatisticSnapshotReader.kt`
- `infra/src/test/kotlin/opensamguk/infra/persistence/DeleteFlushNoDoubleApplyIT.kt`
- `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt`
- `docs/superpowers/research/2026-07-23-next-after-137.md`
- `docs/superpowers/plans/2026-07-23-opensam-138-bounded-boot-design.md`
- `.ai/task.md`, `.ai/current-state.md`, `.ai/ownership.md`
- `.codex/config.toml` was not changed by this worker; its pre-existing `max_threads = 1000` guard blocker is baseline-separated below.

## Result

No blocking findings remain.

The final reviewer cleared the OPENSAM-138 slice after the fourth-review remediations cataloged and source-checked `JdbcFlushExecutor.historyRows`, added non-null pending-history retry evidence, and kept the forbidden `.codex/config.toml` diff excluded. Earlier review blockers were remediated by replacing boot-retained cold history/statistic/global-log payloads with on-demand readers, stripping stale persisted cold meta, moving fallible archive SQL out of `DatabaseHooks`, and preserving pending history through retained `FlushPayload` data for retry-safe executor-side merge.

## Evidence

- Focused command: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests opensamguk.engine.boot.HotColdWorldCatalogGuardTest --tests opensamguk.engine.boot.WorldSnapshotLoaderArchiveIT --tests opensamguk.engine.turn.TombstoneEmitterTest :infra:test --tests opensamguk.infra.persistence.DeleteFlushNoDoubleApplyIT --tests opensamguk.infra.persistence.GameKvFlushIT --no-daemon --no-watch-fs --no-configuration-cache --no-build-cache --console=plain -Dkotlin.compiler.execution.strategy=in-process`
- Tail: `BUILD SUCCESSFUL in 9m 16s`
- XML: `HotColdWorldCatalogGuardTest tests=10 failures=0 errors=0 skipped=0`, `WorldSnapshotLoaderArchiveIT tests=2 failures=0 errors=0 skipped=0`, `TombstoneEmitterTest tests=4 failures=0 errors=0 skipped=0`, `DeleteFlushNoDoubleApplyIT tests=5 failures=0 errors=0 skipped=0`, `GameKvFlushIT tests=2 failures=0 errors=0 skipped=0`.
- `git diff --check` exited 0 with no output.

## Residual Caveats

- This is a build-only bounded boot slice. It does not deploy, activate runtime phase prefetch, or change golden fixtures.
- Literal 3-run JFR/full-GC retained-heap comparison from GH #284 was not run in this worker pass.
- `scripts/agent/verify-changes.sh --run` was executed after review but did not pass; it was terminated with exit 143 after the broad Gradle matrix stopped emitting new output, with the last observed tail at `:infra:compileKotlin`.
- `bash scripts/agent/test-codex-agent-os.sh` fails only on the pre-existing forbidden `.codex/config.toml` `max_threads = 1000` diff.
- `python3 tools/agent-system/check.py --strict --base origin/main` passed with `Errors: 0`, `Warnings: 0`.
