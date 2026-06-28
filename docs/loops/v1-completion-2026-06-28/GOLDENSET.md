# v1 completion loop goldenset

> 상태: draft
> 생성일: 2026-06-28

이 루프는 새 골든 기대값을 만들지 않는다. 채점자는 이미 저장소에 있는 deterministic gate와 테스트 XML이다.

## 채점자

- `tools/parity/gate.sh backend`
- `:logic:test` focused battle/founding/month/unification tests
- `:app:game-api:test` command lifecycle focused tests
- `:app:game-engine:test` daemon command/founding/battle/tombstone/long-sim focused tests
- `:infra:test --tests opensamguk.infra.persistence.JdbcFlushExecutorIT --rerun-tasks`
- `web/gateway` and `web/game` TypeScript check/build through existing local `node_modules/.bin`

## 불합격 조건

- failure/error가 1개라도 있는 XML
- `LongSimReplayGateTest`의 구조 리플레이가 disabled/pending인 상태를 v1 완성으로 판정하는 것
- 명령이 API 예약까지는 통과하지만 `ReservedTurnHandler`에서 resolver까지 도달하지 못하는 것
- 건국 계열(`che_거병`, `che_건국`, `cr_건국`, `che_무작위건국`) 중 하나라도 데몬 seam에서 fallback 또는 write-set 누락을 일으키는 것

