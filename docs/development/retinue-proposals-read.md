# 참모 제안 조회(K8-06)

`GET /api/retinue/proposals?generalId=<본인 장수>`는 검증된 JWT 계정의 살아 있는 본인 장수에게, 그 부의 인물이 이번 순에 올린 제안을 조회한다. 제안은 받는 본인에게만 보인다. controller → query → reader → projection 순서이고, REPEATABLE_READ 읽기 트랜잭션을 쓴다(ADR-LITE-070). D124 분담에 따라 K3가 만들었고, 소비 조건은 계약판 「K3 → C5 · CEO: D124 소비 기술 답」(10-05)과 C5 ACK(메타 `reports/opensamguk/tasks/2026-10-05-c5-d124-ack-and-d125-contract-replacement.md`)를 따른다. 사용자 결정 D58 · D59를 지킨다.

## 응답

root는 `{status, reason, now, proposals}`이고, 모든 키를 늘 싣는다(JsonInclude ALWAYS). `now`는 실제 process world의 연월순이며, 불명이면 null이다.

| status | reason | 뜻 | proposals |
| --- | --- | --- | --- |
| NOT_SEEDED | PROPOSALS_NOT_SEEDED | 참모 제안을 만들어 저장하는 typed producer가 아직 없다. 세력 소속과 관계없다 | null |
| UNAVAILABLE | WORLD_UNAVAILABLE | process world가 없거나, 장수의 월드와 다르거나, 연월순이 올바르지 않다 | null, `now` null |

- 지금은 READY가 나오지 않는다.
- READY `[]`(「이번 순 제안 없음」)는 실제 producer가 그 순을 셈한 뒤에만 쓴다.

## 아직 내지 않는 것

- **제안 행.**
  - `proposalType` · `status`의 확정 enum 목록이 아직 없다. P-5 필드 이름은 enum 원천이 아니다.
  - 내부 `score`는 공개하지 않는다.
  - `confidence`는 서버 식이 정해지기 전에는 null이다(D58).
  - `inputId` · `argsDraft`는 입력 원장 canonical 등록 전에는 null이다.
  - 거부 · 만료는 같은 의미로 오래 막는다(D59).
  - C5의 실제 producer와 enum 소비 합의 뒤에 행과 READY 고정 응답을 더한다.
  - 옛 `retainerId` · `generalId`를 자동 별칭으로 쓰지 않는다.
- **「회의」(여러 인물 찬반).** 없다(D58).
- **채택 · 거부 입력.** 원장 등록 뒤에 화면이 단추를 붙인다.

## 오류

D124 서버 GET 공통 경계(K8 · K3)를 따른다. 인증 판정이 먼저다.

| HTTP | code | 언제 |
| --- | --- | --- |
| 401 | AUTH_REQUIRED | 토큰이 없거나 무효 · 만료 · 갱신용 · 다른 audience다(generalId가 이상해도 401이 먼저) |
| 400 | INVALID_GENERAL_ID | generalId가 없거나, 빈 값이거나, 10진 숫자만이 아니거나, Int 범위 밖이거나, 0 이하다 |
| 403 | FORBIDDEN | 형식은 맞지만 본인 live 장수가 아니다. ADMIN이나 query `userId`로 대신 볼 수 없다 |

본문은 `{error:{code,message}}`이고, 200 · 4xx 모두 `Cache-Control: no-store`다.

## 시험

`AdviserProposalsHttpTest`는 실제 JWT · security · controller · query · reader 경로로 아래를 본다.
- 고정 응답 2종: `app/game-api/src/test/resources/retinue/proposals/{not-seeded,unavailable}.json`
- 인증 · 400 · 403 경계
- 월드 · 연월순 경계
- 재야도 NOT_SEEDED라는 점
- 점수 칸이 없다는 점

READY 고정 응답은 실제 producer가 생길 때만 만든다.
