# 받은 원군 요청 HTTP 조회

`GET /api/court/reinforcement-requests?generalId=<actor>`는 actor가 **실제 수신 봉신으로 받은** 원군 요청만 낸다. D124에 따라 CEO가 K8에 배정했고, 모양은 C5가 받아들인 K8 소비 답(계약판 「K8 · K3 → C5 소비 답」 K8-17)과 C5 초안 `2026-10-01-c5-k8-17-reinforcement-contract.md`를 따른다. 구현은 `ReinforcementRequestsController → ReinforcementRequestsQuery → ReinforcementRequestsReader → ReinforcementRequestsProjection`이다.

## 지금 원천

- 요청을 저장하는 writer가 없다. 그래서 읽을 수 있는 월드면 늘 `NOT_SEEDED · REQUEST_SOURCE_ABSENT · requests:null`이다.
  - `VassalState`는 계약과 상납 영수증만 저장한다.
  - logic `ReinforcementRequest`에는 저장소와 작전 원천이 없다.
- 계약(`vassalContracts`)에서 요청을 지어내지 않는다. 작전이 없는 행도 만들지 않는다.
- 빈 배열은 READY일 때만 「받은 요청 없음」이다. NOT_SEEDED · UNAVAILABLE에서는 목록이 null이다.

## 인증과 오류

- 인증을 먼저 본다. 검증된 access JWT가 없으면 HTTP401 `AUTH_REQUIRED`다. generalId가 이상해도 401이 먼저다.
- generalId가 없거나, 빈 값이거나, 10진 숫자만이 아니거나, Int 범위 밖이거나, 0 이하면 HTTP400 `INVALID_GENERAL_ID`다.
- `GeneralResolver`가 해석한 살아 있는 본인 장수가 아니면 HTTP403 `FORBIDDEN`이다. 군주 · 같은 세력 · ADMIN이 대신 보지 못한다.
- 오류 본문은 `{error:{code,message}}`이고, 모든 응답이 `Cache-Control: no-store`다. D124의 다른 GET(K8-08 · K3 셋)과 같은 규칙이다.

## 응답 계약

| 필드 | 원천과 의미 |
| --- | --- |
| `status` | `READY / NOT_SEEDED / UNAVAILABLE`. 지금은 NOT_SEEDED(원천 없음)이나 UNAVAILABLE(월드 · 날짜를 확인하지 못함) |
| `reason` | `REQUEST_SOURCE_ABSENT · WORLD_UNAVAILABLE · WORLD_DATE_INVALID` |
| `now` | 실제 world_state 연 · 월 · 순 `{year,month,phase}`. 확인하지 못하면 명시 null |
| `requests` | READY에서만 배열이다. 그 밖은 null이다 |

writer가 생긴 뒤의 행 모양은 `ReinforcementRequestDto`다(C5 수용). 키는 늘 다 싣는다(`JsonInclude.ALWAYS`).

- 식별
  - `reinforcementRequestId`는 #1373 봉신 조회 `reinforcementResponse.requestId`와 같은 값이다.
  - `contractId`가 같이 온다.
- 발행자: `issuerGeneralName · issuerNationName`. 확인하지 못하면 명시 null이다.
- 병력: `requestedTroops · obligatedTroops · minimumReducedTroops`
- 기한
  - `responseDeadlineTurn`은 응답 기한이다. `answeredTurn | null`이다.
  - 순은 JSON 안전 정수다.
  - `calendarStatus`가 READY일 때만 `responseDeadlineAt · answeredAt`(Phase)가 값이다. 달력이 없으면 UNAVAILABLE · null이다.
- 응답
  - `replyKind`(ACCEPT · DELAY · REDUCE · REFUSE) `| null`과 `offeredTroops | null`이다.
  - 미응답은 null이다. REFUSE의 0, 의무 0과 섞지 않는다.
- 판정
  - `outcome`(PENDING · ACCEPTED · DELAYED · REDUCED · BREACH) · `committedTroops` · `decisionDueTurn`이다. logic `VassalReinforcement.assess` 그대로다.
  - `decisionDueTurn`은 출발 · 결정 기한이다. 응답 기한으로 쓰지 않는다.
- 목표 · 실행
  - `target`은 `{kind,cityId,name|null} | null`이다. `targetStatus`는 AVAILABLE · NOT_APPLICABLE · UNAVAILABLE이다.
  - `execution`은 `{status,reason}`이다. 실제 작전 원천이 붙기 전에는 행을 만들지 않는다.

## 검증 범위

- 고정 응답 `app/game-api/src/test/resources/court/reinforcement-requests/`
  - not-seeded, unavailable, blocked(403 본문), boundary(400 본문).
  - `ready.json`은 두지 않는다. 실제 원천 없이 READY 행을 지어내지 않는다(C5 범위표 단서).
- `ReinforcementRequestsHttpTest`가 실제 Spring · JWT · controller · query · reader 경로를 탄다. 다음을 본다.
  - 한 해 36순 전부에서 NOT_SEEDED · null(READY [] 아님).
  - 401 · 400 · 403과 no-store.
  - 다른 월드와 불가능한 날짜.
  - 투영이 원천 없는 READY를 거절하는지.
- 저장소는 대역이라 실제 JDBC 읽기 · 재기동 검증을 대신하지 않는다.
