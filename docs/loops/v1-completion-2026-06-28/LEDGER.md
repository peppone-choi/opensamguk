# v1 completion loop ledger

| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 |
|---|---|---|---|---|---|
| 0 | v1 기준서의 핵심 게이트를 베이스라인 측정한다 | pending -> `LongSimReplayGateTest` 구조 리플레이 disabled 1건 확인 | focused logic/game-api/game-engine/infra tests | baseline | command/battle/founding 단위 게이트는 대체로 통과하지만 장기 시뮬레이션 게이트가 아직 꺼져 있어 v1 완성 불가 |
| 1 | 건국이 실패하는 원인은 daemon handler가 예약 arg와 방랑국 메타/무작위건국 후보를 logic seam에 충분히 전달하지 않는 것이다 | `FoundingHandlerSeamTest` 5 tests/1 failure -> 7 tests/0 failure; backend gate 3410 tests/0 failure/0 error/1 skipped | `:app:game-engine:test --tests opensamguk.engine.turn.FoundingHandlerSeamTest`, `tools/parity/gate.sh backend` | 채택 | `che_건국`은 `nationName` arg와 `gennum`이 constraint view에 보이지 않아 fallback했고, `che_무작위건국`은 follower/chosen-city drain이 비어 있었다 |

## 승인 대기 항목

없음. 기존 gate 완화나 골든 기대값 변경은 하지 않았다.

## 남은 차단 항목

- `LongSimReplayGateTest.12 month structural replay matches PHP golden()`이 `@Disabled` 상태다. 문구상 PHP 12개월 snapshot은 12 nations, Kotlin replay는 5 nations였던 장기 AI/founding 수렴 갭이다.

