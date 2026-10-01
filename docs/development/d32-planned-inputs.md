# D32 관직·속관 입력 등록 경계

이 문서는 입력 원장의 `PLANNED` 행이 가리키는 기술 범위를 기록한다. 행 등록과 DRAFT 도움말은 접수·실행·부임·회신·재생 기능의 배달 증거가 아니다. 실제 핸들러와 저장·권한 검증이 준비되기 전에는 `NOT_DELIVERED`로 거절한다.

D32 범위는 司隸校尉·太傅·執金吾·御史中丞·侍中 다섯 본직, 州·司隸·郡·公府 속관, 기존 `OfficeNomination`을 통한 조정 심의다. 縣 속관은 포함하지 않으며 기존 縣 본직 令·長·侯國相은 유지한다. 孝廉·茂才 정기 정원은 두지 않는다. 자기 부(府)의 실제 소속을 속관으로 임명할 때 군주 동의는 요구하지 않고, 부모 관직이 끝나면 연결 속관도 끝난다. 후보표의 자리 수·비용·응답 기한·NPC 자동수락·효과는 이 등록에서 확정하지 않는다.

## 선행 응답·천거 경로

<a id="court-offer-reply"></a>
### 공통 제안 응답 (`court.offerReply`)

정본 공통 응답 ID 한 개를 등록한다. `SUBORDINATE_APPOINTMENT`는 서버가 조회한 제안의 종류이며 별도 입력 ID나 클라이언트가 선언하는 권한이 아니다. 실제 수신자·제안 상태·부모 임기·source revision을 서버에서 확인해야 한다. 기본 행의 다른 제안 종류, wire의 수락 표현, revision과 원자 영수증은 생산자·접수 계약이 확정될 때 연결한다.

<a id="court-office-nominate"></a>
### 관직 천거 (`court.officeNominate`)

기존 `OfficeNomination`의 제출 단계 입력이다. 추천 자격·대상·관할은 authoritative source에서 검증한다. 추천은 관직 임용이 아니며 정기 quota 소비도 아니다.

<a id="court-office-nomination-review"></a>
### 관직 천거 심의 (`court.officeNominationReview`)

제출된 천거를 현재 명시적으로 인가된 조정 심의자가 검토하는 별도 입력이다. 순수 `OfficeNominationFlow.decide()` 함수가 있다는 사실은 접수 경로·심의 권한·저장 이력의 배달 근거가 아니다. 요청 관직·관할과 심의로 확정한 제안 조건은 구분한다.

<a id="court-office-nomination-reply"></a>
### 관직 천거 후보 응답 (`court.officeNominationReply`)

실제 후보에게 제공된 천거 제안의 응답 경로다. 공통 속관 제안 `court.offerReply`의 별명으로 합치지 않는다. 후보의 수락만으로 관직 임기나 부임이 자동 생성되지 않는다.

## 속관 신규 입력

<a id="court-appoint-subordinate"></a>
### 속관 임명 제안 (`court.appointSubordinate`)

유효한 부모 관직의 실제 보유자가 자기 부(府)의 실제 소속 후보에게 한 속관 임명을 제안하는 입력이다. 부모 범위는 州·司隸·郡·公府다. 公府의 구체 부모 본직은 정의·임기 source로 제한하며 모든 중앙 관직이나 太傅府·將軍府를 자동 허용하지 않는다. 같은 세력 소속이나 과거 故吏 결속만으로 현재 부(府) 소속을 인정하지 않는다. 제안·자리 예약·응답·임용의 정확 key/revision과 비용은 아직 미확정이다.

<a id="court-dismiss-subordinate"></a>
### 속관 해임·사임 (`court.dismissSubordinate`)

`DISMISS`는 해당 임용의 현재 부모 관직자, `RESIGN`은 실제 속관 당사자만 요청한다. `actor:GENERAL`이라는 원장 분류만으로 타인의 임용을 종료할 수 없다. 부모 임기 상실은 연결 속관의 권한을 즉시 막고 임용·대기 제안을 같은 writer에서 종료해야 한다. 후임 부모에게 자동 승계하지 않고, 검증된 과거 임용 이력과 기존 사람 결속은 보존한다.

## 단계·도움말·실패 코드

여섯 행은 `PLANNED`, `evidence:{}`, AI selector 없음, 첫걸음 설명 `UNMAPPED`로 시작한다. `DECISION_TURN`은 등록 schema에 맞춘 계획 단계 표기이며 새 처리 기회나 기한의 승인 근거가 아니다. 비용의 `null`은 무료라는 뜻이 아니다. 각 도움말 주제는 `DRAFT`이며 사람이 설명·예시를 검수하기 전 `HELP_READY` 증거가 아니다.

현재 등록 실패 사유는 미배달 상태에서 실제 가능한 `WRONG_RULE_PROFILE`, `UNKNOWN_INPUT`, `NOT_DELIVERED`만 선언한다. 부모 임기·권한·부(府) 소속·revision 등 도메인 거절의 제안 이름은 생산자와 접수 계약, 공유 이름 슬롯이 확정된 후 원장과 도움말을 함께 갱신한다. HTTP 401 인증 실패와 인증된 사용자의 타인 장수 403 경계도 실제 접수 구현에서 별도로 검증한다.
