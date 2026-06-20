# Chief Research Command Parity Review — 2026-06-20

Verdict: cleared

## Scope

Align the reservable chief command table with legacy PHP so `event_*연구` actions are not user-reservable nation commands.

## Legacy Evidence

- `legacy/devsam-core/hwe/sammo/GameConstBase.php:378-415`: `availableChiefCommand` contains only `휴식`, `인사`, `외교`, `특수`, `전략`, and `기타`.
- `legacy/devsam-core/hwe/sammo/API/NationCommand/ReserveCommand.php:47-48`: actions outside flattened `availableChiefCommand` return `사용할 수 없는 커맨드입니다.`
- `legacy/devsam-core/hwe/v_processing.php:39-40`: chief-turn processing also rejects commands outside flattened `availableChiefCommand`.

## Root Cause

The Kotlin port mixed two separate concepts: `event_*연구` actions exist in the registry, but legacy does not expose them through the user-reservable chief command table. The divergence also persisted because `F4StateText.CHIEF_COMMAND_TABLE` duplicated the command table instead of deriving from `GameConst.availableChiefCommand`.

## Change

- Removed the `연구` category from `GameConst.availableChiefCommand`.
- Changed `F4StateText.CHIEF_COMMAND_TABLE` to derive from `GameConst.availableChiefCommand`.
- Added tests pinning the six-category command table and the nation bulk rejection of `event_극병연구`.
- Tightened `AvailableCommandsControllerTest` isolation after the related targeted suite exposed a
  leaked `SecurityContextHolder` from earlier tests.

## Debugging Audit

- H1 confirmed: `AvailableCommandsControllerTest` only cleared auth after each test, so an earlier
  test class could leave a principal in `SecurityContextHolder`; the first anonymous catalog test then
  hit the authenticated `generalId` ownership branch and returned 403.
- H2 refuted: the production controller ownership check was not newly broken; the same test passed
  when run alone before the isolation fix.
- H3 refuted: the chief-command parity change did not remove general command registry rows; only
  `availableChiefCommand` and the F4 chief table changed.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests opensamguk.common.constants.GameConstTest --rerun-tasks`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.reserve.CommandQueueTest --tests opensamguk.gameapi.read.F4StateTextChiefTest --tests opensamguk.gameapi.controller.F4ReadControllersTest --rerun-tasks`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.web.AvailableCommandsControllerTest --rerun-tasks`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests opensamguk.common.constants.GameConstTest :app:game-api:test --tests opensamguk.gameapi.reserve.CommandQueueTest --tests opensamguk.gameapi.read.F4StateTextChiefTest --tests opensamguk.gameapi.controller.F4ReadControllersTest --tests opensamguk.gameapi.web.AvailableCommandsControllerTest --rerun-tasks`
- `git diff --check`
- `tools/agent-system/check.py --strict --base origin/main --format json`
- PR #122 CI: agent-system, jvm, web-gateway, and web-game all passed.
- Main deploy run `27838423051`: shared deploy success, preserve game server pins, health + s1 turn-advance success.
- s1 promotion: `currentTag=5961295038cec09468afec2cc35c3091deb32999`.
- Production API QA after auth/proxy repair: joining QA general returned `202`, front-info returned `hasGeneral=true/generalId=1679`, and `/api/game/api/nation/chief-reserved` returned HTTP 200 with six command categories: `휴식`, `인사`, `외교`, `특수`, `전략`, `기타`; no `연구` or `event_극병연구` text.

Residual risk: the live QA general is 재야, so this confirms the rendered chief command catalog shape; the mutation-level rejection is covered by `CommandQueueTest`.
