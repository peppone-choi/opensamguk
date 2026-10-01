# 2층 런타임 통합 설계 초안

상태: **검토 초안 — 새 결정 미확정, 런타임 미구현**. 기존 순수 모델에 입력·권한·저장·AI·읽기 경계를 잇는 설계다. 사용자 확정 결정은 근거가 있는 범위만 적으며, 아래 `C5-D01–06`은 권장안이다. 이 문서의 존재나 PR 병합으로 입력 단계를 올리지 않는다.

정본: [캠페인 spec §11·§15](../superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md), [공개 로드맵 5단계](roadmap.md), [입력 registry 계약](../superpowers/specs/2026-09-17-input-registry-contract.md). 관직 추천·자칭·중앙 관직·황실·칭제·정체성·제도는 3층이다.

## 1. 현재 근거와 공백

| 기능 | 기존 설계·모델 | 런타임에서 채울 것 |
| --- | --- | --- |
| 관직 임명·실효 지배 | [지방 관직](../development/local-office-model.md), `OfficeAppointmentRules`, `OfficeAppointmentFlow`, `OfficeCapabilityResolver`, `OfficeLifecycle`, `OfficeNpcSelector` | 월드별 관할 투영, 입력/제안 응답, 부임·실효 갱신, GET, NPC 실행 |
| 봉신 계약 | [봉신 계약](../development/vassal-contract.md), `VassalFounding`, `VassalTribute`, `VassalReinforcement` | 제안 식별·동의, 변경/종료, 월수입 직후 상납, 실제 원군, 신분 사건과 원자 저장 |
| 참모 제안·회의 | spec §11, [P-5 제안 계약](../superpowers/specs/2026-08-16-v2-contract-freeze-p1-p15.md#p-5-retainerproposal-계약-6--동결) | 현재 인물 카드로 생성, 시야·점수 근거, 채택/거부·만료, 저장·재생 |
| 역정보 | [역정보](misinformation-model.md), `Misinformation` | 카드 입력·비용, 서버 상태, 피해자 읽기 오염, 재첩보 정리, 비누출 |
| 계절·주변 세계 | [세계 사건](external-world-season-events.md), `SeasonalEvents`, `ExternalWorld` | 실행 주기, 시나리오 접촉·관계 원장, 효과 적용, 통행·NPC·기록·읽기 |

`CourtStateStore`는 지방 재임·credential·봉신 상태를 독립 KV 키에 기록하는 어댑터다. 이것만으로 실제 handler나 읽기가 존재하지는 않는다. `season`·`external`·`misinformation`의 순수 결과도 아직 운영 상태에 반영되지 않는다.

## 2. 기존 결정과 수치의 경계

- spec §11의 2층 관직은 임명과 실효 지배 두 축이다. 관직명이나 옛 수뇌부 숫자로 권한을 만들지 않는다. 능력은 현재 월드의 `OfficeCapabilityResolver` 결과에서만 얻는다.
- 縣令·縣長·侯國相은 현재 縣 배치 투영이며 별도 재임을 추가하지 않는다. 중앙 관직은 지방 임명 입력으로 처리하지 않는다.
- 봉신은 같은 세력의 별도 주공이고, 사람 부 카드에서 해제·주공 지위 사건·계약 쓰기를 같은 실행에서 적용한다. 봉신 밑에 다시 봉신을 두지 않는다.
- 지도 밖 행위자는 세력 테이블에 넣지 않는다. `external-actors.json`의 8행은 이름 근거가 있는 `CANDIDATE`다. 시기·접촉 근거 없는 활성화를 하지 않는다.
- 참모 제안은 규칙 점수와 템플릿이다(P-5). 런타임 LLM을 쓰지 않는다. 옛 Retainer/Subfaction 구조를 현재 카드·부 모델에 다시 만들지 않는다.
- `office-rules.json`, `vassal-rules.json`, `misinformation-values.json`, `seasonal-events.json`, `world-event-values.json`의 수치는 원장의 값을 읽는다. `CONFIRMED`와 `decidedBy=구현 에이전트`를 사용자 원문 확정과 구분한다. 새 문턱·확률·효과량은 이 초안에서 확정하지 않는다.

## 3. 지방 관직·봉신 입력과 읽기

기존 [조정 API 계약](../development/court-office-vassal-api-contract.md)의 다섯 입력과 두 GET 형태를 유지한다. `court.appoint`, `court.dismiss`, `court.foundVassal`, `court.amendVassal`, `court.endVassal`은 카탈로그 등록부터 한다. 모델이 없는 변경·종료를 성공 처리하지 않는다.

### 3.1 원장 등록과 접수

각 행은 `layer=2`, `kind=COURT_DECISION`, 현재 모델의 `actor`·`authorityRule`, 계약 문서의 target source, 비용 미정값, `InputResolved`, `requestId` 재생 키를 갖는다. 첫 등록은 `PLANNED`이며 `NOT_DELIVERED`로 닫힌다. `aiPolicyId=ai.<inputId>`와 `helpTopicId=commands.<inputId>`는 필요한 연결을 식별할 뿐 실제 selector·도움말 존재를 뜻하지 않는다. AI binding은 명시적인 미배달 사유를 가진다.

임명·파면 실패 목록은 `OfficeAppointmentFailure`, 봉신 세우기는 `VassalFoundingFailure`와 공통 인증·형식 거절을 사용한다. 변경·종료의 새 실패 코드 이름은 구현 계약에서 고정한다. 도움말 회복 조언도 같은 코드로 연결한다. D21에 따라 튜토리얼은 설명과 화면 바로가기이며, 새 연습 월드·진척 API·달성 판정을 만들지 않는다.

실행 직전에는 접수 때와 같은 순수 판정을 **현재 상태**로 다시 한다. requestId 재처리는 동일 결과를 반환하고 중복 재임·계약·주공 사건을 생성하지 않는다. 후보 사망·은퇴·세력 이동·치소 상실·봉토 이전·기존 계약 변경은 재검사 대상이다.

### 3.2 월드별 관할과 관직 읽기

`OfficeJurisdictionSnapshot`은 현재 월드의 행정 오버레이·R1 縣 소유·치소·창고망·재임자 위치·현령 착석/주둔에서 만든다. 城 ID→행정 단위→정규 郡國 ID를 명시 변환하며 내정의 `meta.junCh` 문자열을 관할 ID로 간주하지 않는다. 행정 축 밖 parent·시기별 州 귀속·치소 근거가 결손이면 해당 선택지를 내지 않는다.

`GET /api/court/local-offices?generalId=`는 기존 fixture의 `status`, `now`, `localOffices`, `appointmentOptions`, `pendingOffers`를 사용한다. 읽기에서 관직 상태와 실효 근거를 분리한다. `EFFECTIVE`만 실제 縣 목록·능력을 갖는다. 부임 전과 명목 재임을 실효로 표시하지 않는다. 저장 손상은 빈 READY로 바꾸지 않는다.

재임 종료는 명시 파면·사망·은퇴·세력 이동을 `OfficeLifecycle`로 처리한다. 치소 상실은 기록을 유지하며 실효만 사라진다. NPC 공석 제안도 같은 판정·공적/적성/ID 정렬로 선택하고 실제 입력 경로를 탄다.

### 3.3 제안 ID에 묶인 사람 응답 — C5-D01

권장안은 **새 `court.offerReply`** `{offerId, accepted}`다. 기존 `court.politicalConsent`의 issuerGeneralId+inputId 동의는 선양·결의용으로 유지한다. 그것에 관직·봉신 이름만 추가하면 오래된 동의가 다른 조건의 제안에 적용될 수 있다.

제안은 다음 값을 서버에서 저장한다. 클라이언트는 발신자·후보·계약 조건을 응답 본문으로 바꾸지 못한다.

```text
offerId, sourceRequestId, kind(OFFICE|VASSAL_FOUNDING|VASSAL_AMENDMENT)
issuerGeneralId, recipientGeneralId, nationId
terms, expectedRevision?, issuedAt, dueAt?
state(PENDING|ACCEPTED|REFUSED|EXPIRED|CANCELLED|APPLIED)
responseRequestId?, appliedRequestId?
```

`offerId`는 원 요청과 결합된 불투명 식별자다. 동일 offer 응답을 중복 적용하지 않는다. 다른 제안·다른 후보·다른 revision에 동의를 옮기지 않는다. 수락 뒤 적용 전 후보·발신자·관할 사실을 다시 검사하고 실패하면 기존 정치 상태를 바꾸지 않는다.

`GET /api/court/offers?generalId=`는 본인 소유 장수가 받은 제안만 내보낸다. 형태는 `{status, now, offers:[{offerId, kind, fromGeneralId, terms, issuedAt, dueAt, state, responseOptions}]}`다. 임명/봉신 GET의 pendingOffers는 관련 발신·수신 당사자에게만 보인다. 목록의 `responseOptions`는 실제 쓰기 권한의 대체가 아니다.

관직 기한 자동 수락은 기존 `OfficeAppointmentFlow`/`DispatchPolicy`를 유지한다. 봉신 체결·변경은 명시 동의 전 미체결을 권장한다. 봉신 무응답 기한과 종료 방식은 결정 전 `null`/미배달이며 관직 자동 수락을 재사용하지 않는다. NPC 응답도 제안 조건을 판정해 동일 경로로 저장하며 사람 응답을 대신하지 않는다.

### 3.4 봉신 변경·종료 — C5-D02

권장안은 체결된 계약의 **revision과 변경 제안**을 분리하는 것이다. 첫 변경 지원은 기존 계약 문서의 `tributePercent`만이다. 제안 시점 조건과 `expectedRevision`이 일치하고 군주·봉신이 동의한 뒤 새 revision을 원자 적용한다. 봉토·외교권·자치·원군 의무를 이 입력으로 몰래 변경하지 않는다.

종료 권장안은 군주나 봉신 중 한쪽의 명시 통지로 `endedTurn`을 기록하는 것이다. 계약 종료만으로 주공 지위·부 트리·소속 세력·R1 縣 소유를 바꾸지 않는다. 독립은 별도 1층 정치 입력이다. 이미 발생한 상납·미납·원군 영수증은 지우지 않는다. 종료 권한·종료가 위반으로 기록되는 조건은 사용자 결정 범위를 확인한 뒤 계약에 넣는다.

`GET /api/court/vassals?generalId=`는 기존 `status`, `now`, `contracts`, `foundingOptions`를 유지한다. 계약당 `revision`과 당사자에게만 보이는 `pendingAmendments`는 합의 후 추가한다. 같은 세력의 다른 관찰자에게 협상·미응답 조건이나 비공개 군령을 보이지 않는다.

월수입 반영→같은 월 상납 1회→월별 영수증 순서를 지킨다. 월·계약 키 재처리는 이전 영수증을 사용한다. 창고망이 끊긴 수입은 미납이고 실제 이전 전후 총량이 같다. 원군 응답은 `VassalReinforcement` 판정과 실제 부대 출발을 연결한다. 약정 병력을 응답 값만으로 생성하지 않으며 지연·축소·거절·무응답을 재생 가능하게 남긴다.

## 4. 참모 제안·회의 — C5-D03

권장안은 현재 주공의 인물 카드가 **그 주공에게 허용된 시야**로 사실을 읽고 기존 AI selector의 feature와 점수 근거를 재사용하는 것이다. 카탈로그에 handler가 없는 입력은 제안하지 않는다. 제안이 추가 명령권이나 비밀 정보 조회권을 주지 않는다.

P-5 필드 `retainerId, subjectId, proposalType, targetId, score, confidence, evidence[], biasFactors[], expiresAt, status`를 현재 인물 카드와 대응시키고, `proposalId`, `createdAt`, `inputId`, `argsDraft`, `sourceVersion`을 추가한다. confidence와 biasFactors의 수치식이 없는 경우 미정으로 남기며 지어내지 않는다. UI 문장은 feature의 한글 템플릿으로 만든다.

```text
GET /api/retinue/proposals?generalId=
{status, now, proposals:[{proposalId, ...P5, inputId, argsDraft, sourceVersion}]}

court.dismissProposal {proposalId}
```

채택·고쳐서 채택은 **원래 inputId와 정상 인자**로 같은 접수 경로를 사용한다. 사용자가 고친 인자를 다시 권한·자원·시야 검사한다. 채택 표시는 실제 requestId에 연결되고 접수 실패 때 APPLIED로 바뀌지 않는다. 거부는 제안 상태만 바꾸며 같은 제안을 다시 낼 기준을 기록한다.

매 순 생성 결과를 정렬·저장하고 같은 순 재시작에서 다시 추첨하거나 중복 생성하지 않는다. 다음 순에는 저장된 초안을 현재 상태로 재검사해 만료시키는 안을 권장한다. 제안 개수 한도·재제안 대기·점수식은 기존 selector 재사용 실험 뒤 원장에 적고 승인 근거를 붙인다. 그 전에는 게임 수치로 확정하지 않는다.

여기서 회의는 참모 제안을 비교하고 결정하는 흐름이다. 글·댓글·읽음의 `/api/council` 회의실·기밀실은 기존 권한 API를 소비한다. 관직·봉신 능력 판정만 서버 권한 정본에 넘기며 게시판을 중복 구현하지 않는다.

## 5. 역정보 — C5-D04

기존 `FalseSighting` 피해자는 **장수(victimGeneralId)**다. 세력 공유 시야를 새로 만들지 않는다. 계약판의 victimNationId 초안은 피해자 전체 공유를 뜻하지 않으며, 시전자 읽기의 표시 필드가 필요하면 실행 시 검증된 세력 정보를 별도로 투영한다.

권장 시전자 읽기는 `GET /api/stratagem/misinformation?generalId=`의 `{status, now, items:[{id, victimGeneralId, jurisdictionId, falseProvinceId, remainingPhases, state}]}`다. 본인 소유 시전자의 것만 반환한다. 피해자 API에는 이 DTO나 오염 표식을 섞지 않는다. 일반 목격 `CorpsSighting` 형태로만 보여 주고 FOG에는 숨긴다.

카드 초안에서 의병(疑兵)은 가짜 군세, **의병모집은 실제 인물·부대 생성**, 반간은 들어온 첩보·사항에 대한 대응이다. `stratagem.raiseMilitia`를 의병으로 번역하거나 `provokeRivalry`를 반간으로 임의 치환하지 않는다. 기존 `falseReport`의 의미와 카드 매핑을 고정한 뒤 중복 입력 없이 등록한다. 반간을 즉시 공격 입력으로 바꾸지 않는다.

의병 시전은 허용된 郡國·省·피해자·병력 띠를 검증하고 비용 차감과 FalseSighting 저장을 같은 실행으로 한다. 실제 군단 키와 충돌하지 않도록 하되 키 모양으로 거짓인지 식별되게 만들지 않는다. 반간은 합의한 대응 카드 설치·발동 경로를 탄다.

순 경계의 `advance`, 유효기간 제거, `action.scout`/첩보 카드의 `rescout` 정리를 실제 시야 저장소와 연결한다. 거짓 목격을 조우·군단 배치·보급·출전 저장소에 전달하지 않는다. HTTP, SSE, 지난 순 요약, 참모 feature의 모든 공개 경로에서 시전자·오염 행 ID·탐지 표식 비누출을 검사한다. 시전자에게만 자기 결과를 알려 주는 기록과 피해자가 실제 발각한 기록을 분리한다.

## 6. 계절·주변 세계 — C5-D05·06

### 6.1 사건 실행·효과·통행

권장 주기는 **월 경계 한 번**이다. 순수 함수가 phase를 인자로 받는다고 매 순 확률을 반복 적용하지 않는다. 확률 원장의 월별 근거와 맞춰 다음 실행 순서로 고정한다.

```text
현재 월드/시나리오·날짜·지형·소유/창고 스냅숏
→ 월수입 → 봉신 상납
→ 계절/주변 사건 독립 판정
→ 정렬된 효과 적용·유민 이동·통행 갱신
→ 같은 ChangeRecorder flush에 결과·중복 방지 키·공개 기록
```

추첨과 적용의 키는 worldId·phase·domain·subject·kind를 포함한다. 월 경계 복구는 저장 결과를 다시 적용하지 않고 전후 자원·효과 영수증과 상태를 함께 복원한다. 내부 자원 이동, 지도 밖 유입, 피해/유출을 서로 다른 수지로 기록한다. 창고·호구·농업이 음수가 되지 않게 실제 모델 한도로 제한하고, 제한 전 요구량과 실제 적용량을 기록한다. 한도는 게임 모델의 기존 값이며 새 문턱을 발명하지 않는다.

`CountySeasonState`의 4종 지형은 현재 지도 지형을 명시 변환하는 원장이 필요하다. 미확인 지형을 PLAIN으로 대체하지 않는다. displaced는 기존 인구 이동 규칙의 이동 계획에 넣으며 출발 호구와 목적지 합이 보존돼야 한다. 목적지·정책이 없으면 이동분 처리는 미배달이다.

통행은 edge의 ALWAYS/SEASONAL/CLOSED 근거와 개방 계절 집합을 소비한다. 비어 있는 개방 계절 집합은 닫힌다. 이동·보급·수송이 같은 통행 투영을 소비하도록 1층 담당과 합의한다. API만 닫히고 실제 이동이 열리는 상태를 허용하지 않는다.

`GET /api/world/season`의 권장 형태는 `{status, now, season, phaseOfYear, passageStatus, closedEdges}`다. 달력 값이 있어도 통행 데이터가 결손이면 passageStatus=UNAVAILABLE다. 계산하지 않은 빈 closedEdges를 전체 개방으로 해석하지 않는다. 사건은 기록 종류와 refs로 전달한다. 계절은 세계 처리이므로 카탈로그에 가짜 사용자 입력을 만들지 않는다. 대응은 기존 구휼·공사 입력과 NPC 경로를 잇는다.

### 6.2 시나리오별 주변 접촉과 외교

`external-actors.json`의 이름 근거만으로 활성 연락 상대를 만들지 않는다. 별도 시나리오 접촉 원장에 `actorId, scenarioId, subjectPeriod, borderCountyIds, initialRelation, provenance, status, decidedBy`를 넣는 안을 권장한다. 검증된 ACTIVE·기간·접경 縣만 `ExternalContact`가 된다. 역사 주장에는 책·권과 시기 검증을, 게임 대표를 만들 경우에는 게임 용어·사용자 결정 근거를 붙인다. 산월·남중을 근거 없이 하나의 대표로 합치지 않는다.

`GET /api/frontier?generalId=`는 `{status, now, actors:[{actorId, name, relation, borderCountyIds, diplomacyOptions}]}`다. principal의 장수/세력에 허용된 접촉만 읽는다. 판정 가능한 접촉 원장이 없으면 UNAVAILABLE이며 READY의 가짜 빈 관계를 만들지 않는다.

관계/효과는 현재 `HOSTILE/NEUTRAL/TRIBUTARY/SUBMITTED/TRADE`·`BORDER_RAID/TRIBUTE/SUBMISSION/TRADE`를 유지한다. 내속을 자동 縣 점령·세력 생성으로 바꾸지 않는다. 침입을 현재 모델의 縣 피해 사건으로 잇는 안과 실제 전술 침공으로 잇는 안은 효과·전투 범위가 달라 결정이 필요하다.

플레이어 외교는 일반 세력 외교의 메시지/응답 구조를 재사용하되 대상은 ExternalActorId다. 세력 숫자 ID에 접두사를 잘라 넣지 않는다. 초기 관계를 어떤 제안으로 바꾸는지, NPC 응답 근거, 원조·교역의 실제 대가/배송·조공 시작/중단 조건을 먼저 고정한다. 고정 전 등록할 inputId는 결정 대기이며 관계를 직접 설정하는 관리자 기능을 플레이어 입력으로 노출하지 않는다.

## 7. 보안·저장·공개 기록

모든 generalId는 요청자의 JWT principal→소유 장수 해석을 거친다. 다른 계정 장수 요청은 403, 인증이 없으면 401이다. generalId가 있다는 이유만으로 대리 결정하지 않는다. 같은 세력 읽기도 별도 공개 범위 판정이며 계약 당사자·기밀실 권한을 세력 전체로 넓히지 않는다. 공개 읽기와 피해자 시야에 hiddenSeed나 내부 offer/오염 조건을 넣지 않는다.

데몬 쓰기는 월드 메모리+`ChangeRecorder.created/dirty/deleted/recordKv`→JDBC flush뿐이다. API에서 DB/JPA 인라인 write를 하지 않는다. 기존 `CourtStateStore` KV를 보존하고 새 offer·proposal·세계 사건 영수증 codec은 version·필드·중복/참조를 엄격 검증한다. current JSON 문자열과 cold 객체 값은 `PersistedMetaJson.raw`로 정규화한다. codec 손상을 빈 목록으로 덮지 않는다.

새 EventKind는 별도 예약 뒤 `refs`, actor, 공개 범위를 계약에 적는다. 관직 임명/파면/실효 상실, 봉신 체결/변경/종료/상납·미납, 제안 응답, 계절·주변 사건, 역정보 시전자/피해자 기록을 현재 기록 어휘에 맞춘다. 황실 사건은 3층 담당과 겹치지 않는다.

## 8. 구현 순서와 공유 슬롯

1. C5-D01–06의 게임 결정·API 소비 합의와 시나리오 접촉 결손을 정리한다. 카탈로그 5개 입력을 PLANNED로 등록해 요구 범위를 먼저 드러낸다.
2. 관직 관할 투영·읽기·입력·응답·실효/NPC를 연결한다. 이어 봉신 읽기·체결·변경/종료·상납·원군을 연결한다.
3. 참모 생성/채택·역정보 카드/시야를 연결한다. 계절 통행·사건·주변 접촉/외교를 1층 순 경계와 연결한다.
4. 권한·AI·도움말·D21 설명/바로가기·replay 증거를 각 입력에 채운다. 단계 승격은 증거 게이트로 한다.
5. 1층의 3190 제품 월드 하니스 위에서 VERIFIED를 측정하고, 화면의 데스크톱·모바일 e2e까지 확인한다.

카탈로그와 `AiPolicyRegistry`, 기존 접수/실행 공유 파일, 순 경계·월수입 경로, EventKind, 결정 기록, Flyway는 조율 슬롯을 받는다. 이 초안은 그 파일을 변경하지 않는다. KV로 충분하면 신규 테이블/Flyway를 만들지 않으며 필요한 경우 번호·ready 순서는 조율 담당이 정한다. 프론트 코드는 화면 담당이 구현한다.

## 9. 수용 증거

| 영역 | 정상 검증 | 실패해야 하는 적색 변이 |
| --- | --- | --- |
| 관직 | 소유/부임/창고망 변화로 EFFECTIVE↔NOMINAL, county 배치 재사용, 최신 후보 사망/세력 이동 거절, NPC 동일 판정 | 능력 resolver 우회 또는 치소 소유 검사 제거 |
| 사람 응답 | 동시 다른 조건 제안, 본인/타인, 만료 경계, 중복 requestId, 수락 후 상태 변화 | offerId/recipient/revision 결합 검사 제거 |
| 봉신 | 3개 쓰기 원자성, 다른 계약 봉토 충돌, 변경 동의, 종료·독립 분리, 월수입 직후 상납 1회·총량 보존, 원군 실물 | 상납 월 키 제거 또는 부 해제/주공 사건 일부 누락 |
| 제안 | 저장된 feature/score·ID 순서 재현, 정상 입력으로 채택, 거부·만료, 비밀 시야 금지 | sourceVersion/현재 권한 재검사 제거 |
| 역정보 | FULL/INTEL/FOG, 재첩보/만료·발각, HTTP/SSE/요약 비누출, 실제 군단·조우·보급 불변 | caster/오염 표식 누출 또는 조우에 phantom 투입 |
| 계절 | 월 1회·재시작 중복 0, RNG/정렬 독립, 호구 이동 보존, 이동/수송/보급 통행 일치 | 처리 키 제거 또는 SEASONAL 결손 전체 개방 |
| 주변 | CANDIDATE/기간 밖 제외, 접촉별 관계 격리, 외부 수지 기록, 내속 뒤 세력/R1 변화 없음 | 활성/기간 검사 제거 또는 external ID를 세력에 삽입 |
| 저장/통합 | 실제 DB flush/cold reload·정규 행 덤프·입력 재생 동일 결과, 3190 무인 시즌, handler/precheck/NPC 동일 규칙 | codec 손상·고아 참조·중복 영수증 수용 |

변이는 완전한 임시 트리에서 실행한다. 파일을 없애 실패시키는 것은 증거가 아니다. 정상 hosted CI의 suite/test·skip 0·고정 head·XML과 run 링크를 보존하고, 로컬 미실행을 기록한다. API 접수 202·문서·CI 초록만으로 SERVER_DONE이나 VERIFIED라고 말하지 않는다. 화면이 필요한 기능은 서버 증거와 화면 대기를 구분하고, 화면 e2e까지 있어야 공개 로드맵 5단계 완료다.
