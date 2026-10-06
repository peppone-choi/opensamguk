# 주변 세계 조회(K8-09)

`GET /api/frontier?generalId=<본인 장수>`는 검증된 JWT 계정의 살아 있는 본인 장수 기준으로, 그 세력과 지도 밖 세력(침입 · 조공 · 내속 · 교역)의 접촉을 조회한다. controller → query → reader → projection 순서이고, REPEATABLE_READ 읽기 트랜잭션을 쓴다(ADR-LITE-070). D124 분담에 따라 K3가 만들었고, 소비 조건은 계약판 「K3 → C5 · CEO: D124 소비 기술 답」(10-05)과 C5 ACK(메타 `reports/opensamguk/tasks/2026-10-05-c5-d124-ack-and-d125-contract-replacement.md`)를 따른다.

## 응답

root는 `{status, reason, now, actors}`이고, 모든 키를 늘 싣는다(JsonInclude ALWAYS). `now`는 실제 process world의 연월순이며, 불명이면 null이다.

| status | reason | 뜻 | actors |
| --- | --- | --- | --- |
| NOT_SEEDED | CONTACTS_NOT_SEEDED | 세력이 있는 장수다. 접촉 원천(`logic/external` ExternalContact)을 저장하는 키 · codec · producer가 아직 없다 | null |
| UNAVAILABLE | NO_NATION | 본인 장수가 세력이 없다(재야). 빈 READY와 구분한다 | null |
| UNAVAILABLE | WORLD_UNAVAILABLE | process world가 없거나, 장수의 월드와 다르거나, 연월순이 올바르지 않다 | null, `now` null |

- 지금은 READY가 나오지 않는다.
- READY `[]`는 「검증된 무접촉(내륙)」에만 쓴다. 접촉 원천이 없는데 READY `[]`를 내면 내륙이라고 잘못 말하는 셈이라, NOT_SEEDED로 둔다.

## 아직 내지 않는 것

- **행위자 행.** 계약 행 모양은 `{actorId: "external:…", name, relation, borderCountyIds}`이다.
  - relation은 `ExternalRelation` 5종(HOSTILE · NEUTRAL · TRIBUTARY · SUBMITTED · TRADE)이다.
  - 사료 후보(`activation: CANDIDATE`)를 접촉으로 만들지 않는다.
  - 기간 · 시나리오에 배정된 ACTIVE 행위자와, 세력별 접촉 저장 원천이 생기면 행을 더한다. 이름은 한글 원천만 쓰고, 없으면 null이다.
- **일어난 일(침입 · 조공 등 사건).** 이 읽기에 넣지 않는다. 기록 피드 몫이다.
- **외교 입력.** 입력 원장 결정 뒤에 붙인다.

## 오류

D124 서버 GET 공통 경계(K8 · K3)를 따른다. 인증 판정이 먼저다.

| HTTP | code | 언제 |
| --- | --- | --- |
| 401 | AUTH_REQUIRED | 토큰이 없거나 무효 · 만료 · 갱신용 · 다른 audience다(generalId가 이상해도 401이 먼저) |
| 400 | INVALID_GENERAL_ID | generalId가 없거나, 빈 값이거나, 10진 숫자만이 아니거나, Int 범위 밖이거나, 0 이하다 |
| 403 | FORBIDDEN | 형식은 맞지만 본인 live 장수가 아니다. ADMIN이나 query `userId`로 대신 볼 수 없다 |

본문은 `{error:{code,message}}`이고, 200 · 4xx 모두 `Cache-Control: no-store`다.

## 시험

`FrontierHttpTest`는 실제 JWT · security · controller · query · reader 경로로 아래를 본다.
- 고정 응답 3종: `app/game-api/src/test/resources/frontier/{not-seeded,unavailable,boundary}.json`
- 인증 · 400 · 403 경계
- 월드 · 연월순 경계

READY 고정 응답은 실제 접촉 원천이 생길 때만 만든다. 저장소와 GeneralResolver는 대역이다.
