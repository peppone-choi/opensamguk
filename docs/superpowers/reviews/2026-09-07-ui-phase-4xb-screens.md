# Phase 4X-B 작전 — 실화면 보고 (2026-09-07)

- 대상: spec `docs/superpowers/specs/2026-09-06-operation-vertical-slice.md` v4.1(cleared) 구현(`5cdb3301` 이후). 로컬 스택(도커 game-engine·game-api V57 이미지, dev web :3000/:3001), 계정 `uitestpi3tu7`, 실측 장수 1496 「실측14245」(국가 27 ㉿이유, 수뇌부 officer_level 5, SQL 로 배치 — `general.meta.killturn` 999).
- 캡처: `reports/ui-redesign/phase4xb/` (Playwright 1440×1000 fullPage, 스크립트 `scratchpad/shots-operation.mjs` — 저장소 밖).

| 순서 | 화면 | 실측 결과 |
|---|---|---|
| 01 | `/game/pep/my-nation#operations` 빈 상태 | 「작전 진행 0 / 3 · 잠정」 + 빈 상태 「작전이 없습니다. 수뇌부가 선언하면 나옵니다.」, 선언 폼(종류 6종 중 예약 3종 disabled + 「아직 선언할 수 없는 작전 종류입니다.」, 목표 도시 95개 select, 기한 1~12개월) |
| 02 | 선언(도시 점령 · 업 · 2개월) | 인테이크 202 → RESOLVED 「처리되었습니다.」 → 「실측 작전 · 진행 중 · 도시 점령 · 업 · 기한 192年 2月 상순 · 남은 3개월」, 이정표 0/4 |
| 03 | 참여(본대) | 「처리되었습니다.」 → 참여 부대 「실측14245 본대 1,000 · 보병 · 수춘」, 이탈/종료 버튼 |
| 04 | 회의실 글쓰기 종류 `operation` | 「연결 작전」 select 에 실측 작전이 뜬다(선언 직후 같은 틱 연결 허용 — DEFERRABLE FK) |
| 05 | 작전실 조작 대상 바 | `OperationBadge` 「작전 1 · 실측 작전 · 3개월 남음」 |

## 실측에서 잡은 결함(둘 다 커밋 `04f24289`)
1. **대체 목표가 「null」 로 저장** — 선언 폼이 빈 대체 목표를 JSON `null` 로 보내고, game-api `CommandWireMapper` 의 접근자가 `JsonNull`(도 `JsonPrimitive`)의 `.content` = `"null"` 을 문자열로 읽었다. 고침: 네 접근자(`int/long/str/bool`)가 `JsonNull` 을 부재(null)로 + `CommandWireMapperTest` 1건; 폼은 빈 값이면 키를 생략. 로컬 DB 의 기존 행은 SQL 로 NULL.
2. **국가 운영 grid 안에서 작전 패널이 한 셀(320px)에 눌려** 이정표 라벨(출발·도달·보급·목표)이 글자 단위로 세로 배치, 선언 폼 입력이 잘림. 고침: `.record-grid > .ops { grid-column: 1 / -1 }` + 이정표 `nowrap`/wrap.

## 관리자 개입 0
- 엔진 `OperationAdminZeroSimTest`(선언 → 참여 → `ReservedTurnHandler.handle(che_출병)` 실경로 점령·멸망 캐스케이드 → 월 정산 「달성」, 세계 상태 직접 쓰기 0, 적색 프로브 rc=1). 실화면의 작전은 60분 턴 서버라 정산(월 경계)까지 기다리지 않았다 — 이정표 재계산은 시뮬로 증명, 실서버 관측은 UNKNOWN.

## 밖(spec §10 그대로)
통제권 %·예약 3종(도로 확보·통과·봉쇄) 생산자·리플레이 첨부 카드·표결 결과 메모.
