# GOLDENSET — turn-date-36-per-year

Status: draft
Date: 2026-06-27

## 고정 채점자

- `tools/parity/gate.sh backend`
- `:logic:test --tests opensamguk.logic.golden.MonthTickReplayGateTest`
- `:logic:test --tests opensamguk.logic.tick.ServerClockTest`
- `:logic:test --tests opensamguk.logic.tick.MonthlyPipelineOrderTest`
- `:app:game-api:test --tests opensamguk.gameapi.controller.FrontInfoControllerTest --tests opensamguk.gameapi.web.ReservedCommandsControllerTest`
- `:infra:test --tests opensamguk.infra.persistence.JdbcFlushExecutorIT`
- `:app:game-engine:test --tests opensamguk.engine.run.TurnRunServiceIT`

## 불변식

- 삼모 달력은 1개월 3순(상순/중순/하순), 1년 36턴이다.
- world state의 `current_year/current_month/current_phase`가 read API, reserved command, engine world, flush, realtime event에서 같은 값을 표현해야 한다.
- `checkStatistic`은 새 날짜가 1월 상순일 때만 실행한다.
- golden fixture와 gate 기대값을 약화하지 않는다.
