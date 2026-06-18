# 2026-06-18 Immediate Intake Drain Review

Verdict: cleared

Scope:
- User-facing registration must become enterable immediately after `POST /api/join`.
- Existing turn cadence must remain unchanged: no monthly/general turn advancement and no `turnCompleted` for intake-only work.

Legacy evidence:
- `legacy/devsam-core/hwe/sammo/API/General/Join.php:177-182` checks and writes ownership in the same request path.
- `legacy/devsam-core/hwe/ts/PageJoin.vue:420-428` treats a successful join as immediately navigable.

Baseline:
- Production UI accepted `MakeGeneral` but stayed on `/game/s1/join` with “장수 생성은 접수됐지만 아직 게임에 반영되지 않았습니다.”
- Production DB had no `general.user_id='3'`, no `general_owner` row, and Redis still held `makeGeneral` in `sammo:che:scenario_2:turn-daemon:commands`.

Root cause:
- `TurnDaemonRunner` called `TurnRunService.runTick` only when the scheduled turn was due.
- Immediate Model-B commands were therefore queued until the next turn instead of being flushed independently.

Change:
- Added `TurnRunService.runIntakeCommands()` to drain, dispatch, publish command results, and flush dirty rows without advancing world clock.
- `TurnDaemonRunner` now invokes intake drain while waiting for the next scheduled turn.
- Added a runner regression proving intake drains while `nextRunTime` is still in the future and scheduled ticks stay idle.

Verification:
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests opensamguk.engine.run.TurnDaemonRunnerTest --tests opensamguk.engine.intake.MakeGeneralHandlerTest`
