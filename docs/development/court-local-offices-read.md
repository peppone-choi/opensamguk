# 지방 관직 조회(K8-03)

`GET /api/court/local-offices?generalId=<본인 장수>`는 검증된 JWT 계정의 살아 있는 본인 장수 기준으로, 그 세력의 지방 관직 재임 저장 사실을 조회한다. controller → query → reader → projection 순서이고, REPEATABLE_READ 읽기 트랜잭션을 쓴다(ADR-LITE-070). D124 분담에 따라 K3가 만들었고, 소비 조건은 계약판 「K3 → C5 · CEO: D124 소비 기술 답」(10-05)과 C5 ACK(`reports/opensamguk/tasks/2026-10-05-c5-d124-ack-and-d125-contract-replacement.md`, 메타 저장소)를 따른다.

## 응답

root는 `{status, reason, now, localOffices, appointmentOptions, pendingOffers}`이고, 모든 키를 늘 싣는다(JsonInclude ALWAYS). `now`는 실제 process world의 연월순이며, 불명이면 null이다. 목록은 원천이 없거나 셈할 수 없으면 null이다. 확인된 빈 결과만 `[]`이다.

| status | reason | 뜻 | 목록 |
| --- | --- | --- | --- |
| READY | null | `localOfficeTenures` 저장 키가 있고, 이 세력의 열린 재임이 0개다(확인된 빈 결과) | `localOffices: []` |
| NOT_SEEDED | TENURES_NOT_SEEDED | 저장 키가 없다(재임 원천이 한 번도 쓰이지 않음) | 모두 null |
| UNAVAILABLE | WORLD_UNAVAILABLE | process world가 없거나, 장수의 월드와 다르거나, 연월순이 올바르지 않다 | 모두 null, `now` null |
| UNAVAILABLE | NO_NATION | 본인 장수가 세력이 없다(재야). 저장 키를 읽지 않는다 | 모두 null |
| UNAVAILABLE | TENURES_INVALID | 저장 값이 엄격 codec(`OfficeTenureCodec`)을 통과하지 못하거나 다른 월드 행이다 | 모두 null |
| UNAVAILABLE | JURISDICTION_SNAPSHOT_UNAVAILABLE | 이 세력의 열린 재임이 있지만, 실효 판정에 필요한 관할 스냅숏이 서버에 연결되지 않았다 | 모두 null |

- 끝난 재임(`endedTurn`)은 열린 재임으로 세지 않는다.
- 다른 세력의 재임은 행으로도, 개수로도 드러내지 않는다.

## 아직 내지 않는 것

- **재임 행.** state(PENDING_ACCEPTANCE · AWAITING_ARRIVAL · EFFECTIVE · NOMINAL) · `missing`(OfficeEvidence 8종) · `actualCountyIds`는 `OfficeCapabilityResolver.actualJurisdiction`이 `OfficeJurisdictionSnapshot`으로 계산한다.
  - 이 스냅숏은 관할 현 목록 · 소유 · 앉은 사람 위치 · 창고 연결 · 현령 · 주둔 군단으로 이뤄지는데, 이를 만드는 서버 코드가 main에 없다.
  - 그래서 열린 재임이 있으면 추정한 state나 명목 행을 내지 않고 UNAVAILABLE로 닫는다.
  - 스냅숏 projection이 연결되면 행 모양(계약 문서 `court-office-vassal-api-contract.md`의 필드, `officeLabel` · `jurisdictionName` · `seatCountyName` 한글 이름 포함)을 이 응답에 더한다.
- **한글 이름.** 지방 관직 7종의 사료 이름은 한자뿐이고, 검증된 한글 원천이 없다. 지도 표시명을 `zhou:` · `hhs-group:` 관할에 짐작으로 잇지 않는다. 원천이 없으면 명시 null이다.
- **임명할 수 있는 자리(`appointmentOptions`)와 보낸 임명 제안(`pendingOffers`).** 후보 · 권한 판정 원천과 보낸 제안 reader가 연결되지 않아 늘 null이다. 빈 배열과 구분한다.
- **입력.** `court.appoint` · `court.dismiss`는 입력 원장에 행이 없다. 이 조회는 쓰기 · 사전 검사를 하지 않는다.

## 오류

D124 서버 GET 공통 경계(K8 · K3)를 따른다. 인증 판정이 먼저다.

| HTTP | code | 언제 |
| --- | --- | --- |
| 401 | AUTH_REQUIRED | 토큰이 없거나 무효 · 만료 · 갱신용 · 다른 audience다(generalId가 이상해도 401이 먼저) |
| 400 | INVALID_GENERAL_ID | generalId가 없거나, 빈 값이거나, 10진 숫자만이 아니거나, Int 범위 밖이거나, 0 이하다 |
| 403 | FORBIDDEN | 형식은 맞지만 본인 live 장수가 아니다. ADMIN이나 query `userId`로 대신 볼 수 없다 |

본문은 `{error:{code,message}}`이고, 200 · 4xx 모두 `Cache-Control: no-store`다.

## 시험

`LocalOfficesHttpTest`는 실제 JWT · security · controller · query · reader 경로로 아래를 본다.
- 고정 응답 5종: `app/game-api/src/test/resources/court/local-offices/{ready,not-seeded,unavailable,blocked,boundary}.json`
- 인증 · 400 · 403 경계
- 재야 · 월드 · 연월순 경계
- 손상 저장 값
- 열린 재임의 UNAVAILABLE

저장소와 GeneralResolver는 대역이라, 실제 JDBC · flush · cold restart는 대신하지 않는다. 클라이언트(`web/game/lib/api/court-local-offices.ts`)는 같은 고정 응답을 읽어 표류를 잡는다.
