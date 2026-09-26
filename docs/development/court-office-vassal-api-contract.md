# 조정의 지방 관직·봉신 API 계약 초안

이 문서는 S5 L1 서버와 조정 화면 사이의 인계 계약이다. 예시는 `fixtures/court-local-offices.json`, `fixtures/court-vassals.json`에 있다. 예시의 인물·縣 ID와 이름은 응답 형태 확인용 가상값이다. **현재 두 GET과 아래 새 명령은 아직 서버에 배선되지 않았으며**, 프론트는 서버가 제공하기 전까지 사용 가능으로 표시하지 않는다.

## 읽기

- `GET /api/court/local-offices?generalId=<actor>`: 인증 사용자 소유 장수에 대한 지방 관직 재임, 실효 여부, 부족한 근거, 임명 가능한 자리와 후보, 대기 중 제안을 반환한다. `Cache-Control: no-store`를 사용한다. `縣令` 등 縣 자리는 기존 배치 투영에서 읽으며 별도 재임을 만들지 않는다.
- `GET /api/court/vassals?generalId=<actor>`: 같은 세력의 활성 계약, 봉토, 상납 이력, 설립 가능한 직속 후보를 반환한다. `Cache-Control: no-store`를 사용한다.
- 두 응답의 `status`는 `READY | UNAVAILABLE`; `UNAVAILABLE`일 때 목록은 비어 있다. 소유권이 다르면 HTTP 403, 인증되지 않았으면 HTTP 401이다. `available=false`는 선택지가 보이지만 현재 접수할 수 없다는 뜻이며 `blocked.code`에 서버의 정확한 실패 enum을 싣는다. 표시는 권한의 최종 보증이 아니다.
- 임명 `state`는 `PENDING_ACCEPTANCE | AWAITING_ARRIVAL | EFFECTIVE | NOMINAL`이다. `missing`은 `OfficeEvidence` 코드 목록이고, `EFFECTIVE`가 아닌 재임에 능력을 부여하지 않는다. `actualCountyIds`는 실효일 때만 채운다.
- `jurisdictionId`는 행정 축의 `zhou:<...>` 또는 `hhs-group:<...>` 정규 ID이며 기존 내정 `commanderyId` 문자열을 그대로 보내지 않는다. 기본 지도에 治所 근거가 없는 관할은 임명 선택지를 내지 않는다.

## 쓰기

기존 `CourtController`의 `POST /api/commands/court/{name}?generalId=<actor>`를 사용한다. 성공은 HTTP 202 `{ "status":"AVAILABLE", "requestId":"...", "inputId":"..." }`, 사전검사 거절은 HTTP 200 `{ "status":"BLOCKED", "code":"...", "reason":"..." }`이다. 접수·실행은 같은 순수 판정을 최신 상태에서 다시 실행한다. 입력 원장 행과 intake가 배선되기 전에는 이 경로를 호출하지 않는다.

| 입력 ID | JSON 본문 | 효과 |
| --- | --- | --- |
| `court.appoint` | `{ "candidateId":2, "officeId":"office.commandery-prefect", "jurisdictionId":"hhs-group:109:京兆尹", "seatCountyId":100 }` | 군주의 지방 관직 임명 제안. 사람 후보는 응답 보류·수락·거절을 거친다. 중앙 관직은 조서 전용으로 거절한다. |
| `court.dismiss` | `{ "tenureId":"tenure-1" }` | 군주가 활성 지방 재임을 종료한다. |
| `court.foundVassal` | `{ "candidateId":2, "fiefCountyIds":[100], "tributePercent":20 }` | 직속 휘하를 봉신 주공으로 세우며 사람 후보의 별도 동의를 요구한다. |
| `court.amendVassal` | `{ "contractId":"contract-1", "tributePercent":25 }` | 기존 계약 변경. 변경 가능한 필드와 승인 조건은 실제 handler 배선 시 원장에 고정한다. |
| `court.endVassal` | `{ "contractId":"contract-1" }` | 계약 종료. 독립은 별도 1층 정치 사건 경로를 탄다. |

`court.appoint`/`court.dismiss`의 거절 코드는 `OfficeAppointmentFailure`, `court.foundVassal`의 거절 코드는 `VassalFoundingFailure`를 사용한다. 예를 들어 치소 상실은 `SEAT_NOT_OWNED`, 동의 대기는 `CANDIDATE_CONSENT_PENDING`, 거절은 `CANDIDATE_REFUSED`다. 아직 정하지 않은 계약 변경·종료 상세 사유를 임의로 성공 처리하지 않는다.

## 저장·재현

관직 재임, credential, 봉신 계약은 각각 `localOfficeTenures`, `officeCredentials`, `vassalContracts` 키를 `game_kv(game_env, game_env)`에 쓴다. 월드 메모리 변경과 `ChangeRecorder.recordKv`를 한 handler에서 함께 수행해야 한다. 현재 턴의 JSON 문자열과 cold reload의 객체형 값을 codec 앞에서 `PersistedMetaJson.raw`로 통일한다. cold reload 뒤 다른 `game_env` 키가 보존되는지와 같은 입력 재생 결과가 같은지 IT로 확인한다. 상납은 월수입 직후 한 번 실행하고 계약·영수증을 같은 flush에 기록한다.
