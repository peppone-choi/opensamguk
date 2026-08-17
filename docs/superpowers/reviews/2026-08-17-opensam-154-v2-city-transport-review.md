# OPENSAM-154 (v2 R5) — 도시 자원 수송: 자기 검토 + 선언 기록

- 대상: `V2CityTransportRules/Handler`(신규) + wire variant(신규) + 인테이크 컨트롤러(신규) + 경계 수정 2파일 + v2-lab 신규 라우트
- 브랜치: `feat-opensam-154-v2-city-transport` (base = `main`, R4 머지 완료 `7dcede3e`)
- 일자: 2026-08-17

Scope: OPENSAM-154 v2 도시 자원 수송 커맨드 — wire variant(common/) · 인테이크 컨트롤러와 매퍼 배선(app/) · 엔진 규칙·핸들러·디스패치(app/) · v2-lab 제출 라우트(web/)
Verdict: cleared

## 0. 선행 조건에서 벗어난 점 — 먼저 적는다

티켓 DoD의 "4단계(V2-1, OPENSAM-45·46·47) 이후"는 여전히 미충족이다(셋 다 `할 일`). 근거는 R4 리뷰 §0과
같다 — R5가 실제로 요구하는 결과 회신은 이미 main에 있는 v1 result-poll 규약이고 v2 SSE는 상위 개선이다.
**"R4 머지 이후에 착수"는 충족했다**(R4는 `7dcede3e`로 main 머지 완료, 공유 T2 2파일을 순차로 열었다).

## 1. 무엇을 만들었나

장수가 자기가 있는 도시의 v2 원장에서 **금·병량·도시병사**를 **인접 1홉** 자국 도시로 옮긴다.
판정은 순수 함수 `transportDecision`(draw 0, 월드 접근 0), 핸들러는 월드 조회·소속 검사·인접 판정·
원장 델타만 한다. 로그는 남기지 않는다(R4와 같은 이유 — 로그 문자열은 v1 패러티 대상).

**출발·도착 델타는 같은 `ChangeRecorder`에 실린다** = `JdbcFlushExecutor`의 같은 트랜잭션에서 커밋된다
(DoD "같은 트랜잭션"). 한쪽만 반영되는 상태는 만들어지지 않으며, 테스트가 upsert 2건이 같은 recorder에
있음을 고정한다.

## 2. 한도 수치 — 원문 그대로

| 값 | 근거 |
| --- | --- |
| 금 5만 · 병량 5만 | `help__start__intermediate__intermediatebattle.md:364` |
| 최소 병사 2000명 | 같은 줄 |
| 인접 1홉 | `:361` "인접 도시로" + `CalcCityDistance`(= `CityConst.path` BFS, 골든 잠금) |
| 장수는 이동하지 않는다 | `:366` — 핸들러가 장수 상태를 건드리지 않는 이유 |
| 주민 비대상 | `:361`이 금·병량·도시병사 셋만 든다 |

**도시병사 상한은 U6 UNKNOWN.** 원문에 없다. 지어내지 않고 같은 문장의 5만을 **임시로** 쓰고
`TRANSPORT_MAX_GARRISON` KDoc과 테스트 이름에 "묘섭 미명시"를 박았다. 값이 바뀌어도 구조는 불변이다.

## 3. 원문이 두 갈래로 읽히는 곳 — 해석을 밝힌다

> "수송에 필요한 최소병사량은 2000명 입니다."

한 문장뿐이라 (a) *수송하는 장수가 거느린 병사(호송 병력)의 하한* 과 (b) *도시병사를 수송할 때
한 번에 2000명 이상* 두 갈래로 읽힌다. **여기서는 (a)로 구현했다** — "수송에 **필요한**"이 행위의
전제 조건을 가리키기 때문이다. `TRANSPORT_MIN_ESCORT_CREW`의 KDoc에 같은 내용을 적었고, (b)가
맞다고 판명되면 판정 한 줄의 대상만 바뀐다. **모르는 것을 아는 척하지 않는다는 뜻이지, 값을 지어낸
것이 아니다** — 2000이라는 수치 자체는 원문 값이다.

## 4. 판정 순서와 fail-closed

`음수 → 총량 0 → 인접 1홉 → 호송 병사 → 자원별 상한 → 출발 도시 잔액` 순. 잔액 검사는 **규칙이**
하고 원장은 손대지 않는다 — `V2CityLedgerStore.adjust`가 `coerceAtLeast(0)`으로 음수를 막긴 하지만,
그건 원장이 새는 것을 막는 마지막 방어선이지 "잔액 부족"을 알려 주지 못하기 때문이다(조용히 0으로
깎이면 사용자는 성공으로 오해한다). 디스패처 분기는 원장 빈이 없을 때 `null`이 아니라
`V2CityTransportHandler.unavailable(...)`을 돌려준다 — `null`이면 결과 행이 없어 FE 폴링이 PENDING에
갇힌다(R4 §9와 같은 규약).

## 5. `logic/`은 한 줄도 고치지 않았다

인접 판정은 `CalcCityDistance.calcCityDistance(from, to) == 1` **호출만** 한다. `logic/`은 T1이라
게이트 ②가 이를 강제하며, 실제로 PASS다. 테스트도 인접 도시 쌍을 **하드코딩하지 않고**
`CityConst.path`에서 찾아 쓴다(골든 잠금 값이므로 테스트가 지어낼 이유가 없다).

## 6. 게이트 ③ — 티켓 사전선언과 **정확히 일치**

출력 2파일 = 티켓 T2 표 9·10행(`CommandWireMapper.kt`, `TurnDaemonCommandDispatcher.kt`). **초과 0.**
R4가 필요로 했던 `TurnRunService`/`DaemonLoopConfig`의 원장 전달 경로는 R4에서 이미 뚫려 있어
R5는 재사용만 한다.

디스패처 편집은 티켓이 못박은 제약도 지켰다: v2 핸들러 타입명에 `Repository`/`Reader` 없음,
`JdbcTemplate`/`Connection`/`DataSource` 선언 없음, v2 분기는 기존 repository seam을 **부르지 않는다**
(원장 store 하나만 쓴다).

## 7. 검증 증거

- `:common:test` + `:app:game-engine:test` + `:app:game-api:test --rerun-tasks` → XML 집계
  **tests 1658 / failures 0 / errors 0 / skipped 1**(기존 `LongSimReplayGateTest` 골든 — 무관)
  - 신규: `V2CityTransportRulesTest` 13건, `V2CityTransportWireTest` 2건, `V2CommandWireMapperTest` +2건
- `scripts/agent/v2-isolation-gate.sh` → ② PASS · ③ 정확히 2파일 · ⑤ PASS · C1 PASS
- `web/game`: lint 신규 경고 0, build 성공 — 라우트 `/game/v2-lab/transport` 생성 확인
- v1 골든/RNG/로그: **건드린 파일 0**
