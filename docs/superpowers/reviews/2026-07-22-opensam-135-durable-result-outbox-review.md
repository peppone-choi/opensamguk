# OPENSAM-133/134/135 S4 Durable Inbox/Outbox Review

Date: 2026-07-22
Scope: uncommitted WIP on `peppone-choi/status-check-current-work` for S4 durable inbox/outbox, Redis wake ACK/PEL, outbox relay, reserved/queue lifecycle terminalization, reserved execution correlation, and the review-fix patch covering `.codex/`, `app/`, `common/`, `infra/`, and `docs/`.
Reviewer: Codex gate reviewer (`019f8a5c-26c2-7450-86ec-631812f3d98d`)
Verdict: cleared

## Summary

The prior fix-required blocker is closed. Reserved turn consumption is no longer committed by production daemon callbacks through `ReservedTurnRepository.pullGeneralTurn` / `pullNationTurn`; production wiring records pull intents on the shared `ChangeRecorder`, those intents are mapped into `FlushPayload`, and `JdbcFlushExecutor` executes the ring rotation in the same JDBC transaction as `world_state`, state deltas, `command_result`, `command_outbox`, and the world-version fence.

## Evidence Reviewed

- `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt:414` records nation pull intents through `recorder.recordNationTurnPull(...)`, and `:417` records general pull intents before the retained AI delta drain.
- `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt:218` stores append-only reserved pull intents, `:244` includes them in `isDirty`, `:763-773` exposes and records them, and `:829-830` clears them only with the rest of the committed recorder generation.
- `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt:691-696` snapshots recorder pull intents into `FlushPayload`.
- `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt:288-292` executes reserved nation/general pulls from the payload inside the existing flush transaction.
- `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt:2144-2232` processes each pull intent sequentially with the repository-equivalent two-statement rotation SQL, preserving duplicate catch-up semantics.
- `infra/src/test/kotlin/opensamguk/infra/persistence/CommandResultOutboxFlushIT.kt` covers rollback of state, ring pull, result, and outbox when execution result flush fails, then retries the same valid payload and asserts a single rotation plus exactly one `command_result` / `command_outbox` row.
- `app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnRunServiceFlushRecoveryTest.kt` covers retained flush retry payloads carrying reserved pull intents and wake ACK failure after committed flush/outbox.
- `.codex/config.toml` now keeps Codex Agent OS concurrency within the project guard (`max_threads = 16`).

## Findings

No blocking findings.

## Residual Risks

- `scripts/agent/verify-changes.sh --run` did not complete. It entered the known broad `--rerun-tasks` Gradle matrix stall pattern and was interrupted; do not count it as green.
- The design-level `reservationRevision` coordinator from `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` remains outside this build-only fix. The current patch closes the reviewed daemon execution crash window but does not claim that broader deployment contract.

## Verification Notes

- Focused command:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests opensamguk.infra.persistence.CommandResultOutboxFlushIT :app:game-engine:test --tests opensamguk.engine.run.TurnRunServiceFlushRecoveryTest --tests opensamguk.engine.run.TurnRunServiceIT --no-daemon --no-configuration-cache --no-build-cache --console=plain -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process -Dkotlin.compiler.execution.strategy=in-process`
  passed with `BUILD SUCCESSFUL in 9m 7s`.
- XML evidence:
  - `CommandResultOutboxFlushIT`: `tests=7 failures=0 errors=0 skipped=0`
  - `TurnRunServiceFlushRecoveryTest`: `tests=7 failures=0 errors=0 skipped=0`
  - `TurnRunServiceIT`: `tests=1 failures=0 errors=0 skipped=0`
- `git diff --check` exited 0 with no output.
- `bash scripts/agent/test-codex-agent-os.sh` passed: `PASS: Codex Agent OS contract`.
- `python3 tools/agent-system/check.py --strict --base origin/main` initially failed only because this review artifact was stale and did not cover `.codex/`, `app/`, `common/`, `infra/`, and `docs/`.

## Completion Status

Cleared for the reviewed S4 build-only reserved execution/result/outbox crash window. Broad full-matrix verification remains a tooling/gate risk until the Gradle `--rerun-tasks` stall is resolved or completed successfully in CI.
