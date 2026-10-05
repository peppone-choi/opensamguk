# 2층 런타임 연결 기술 계약

상태: **현재 결정·고정 소스 기준의 연결 계약, 미배달 기능은 별도 표시**. 이 문서는 #1151의 구형 초안을 교체한다. 문서 병합은 handler·writer·GET·화면의 구현 완료나 운영 배포를 뜻하지 않는다. 기존 순수 모델, 저장된 사실 읽기, 실제 실행을 각각 구별한다.

## 1. 기준과 소유 경계

소스 관찰 기준은 main `3041b6963507265e74a671c4ae7b2e9765771318`이다. 봉신 HTTP #1373은 이 main에 포함돼 있다. 받은 제안 읽기는 별도 #1383 head `ff05e040fc33ca1fe3670005dd1d262e68a133d0`의 원본 13파일이다. C0 통합 후보 최초 대조 head는 `c1033ef422bfa9955bf181b6f5a796f38bfb6889`이며, 그 뒤 결합 상태·CI·독립 판정은 해당 후보의 실제 head에서 다시 검증한다. 이 핀들은 현재 서버 이미지·운영 데이터의 증거가 아니다.

정책 근거는 CEO가 기록한 D32, D58–D59, D60–D68, 봉신·원군 Q6–Q14와 2026-10-05 22:58–22:59 K3·K8 소비 ACK이다. 근거 원장은 메타 저장소의 작업 보고서·계약판에 보존한다. 제품의 [지방 관직 모델](../development/local-office-model.md), [봉신 순수 모델](../development/vassal-contract.md), [조정 API 계약](../development/court-office-vassal-api-contract.md)은 각 기존 모델의 참고 자료다. 그 문서의 옛 제안·fixture가 아래 최신 결정이나 실제 producer의 증거를 대신하지 않는다.

| 범위 | 담당·변경 조건 |
| --- | --- |
| 지방 관직·봉신·원군·참모의 원천, strict codec, 원자 writer | C5. 정확 소스 슬롯을 확인한 뒤 연결한다 |
| D124 새 GET: local-offices, retinue/proposals, frontier | K3. 새 Controller·Query·Reader·Projection·DTO·시험·spec만 C0 배정으로 편집한다 |
| D124 새 GET: reinforcement-requests, world/season | K8. 같은 새 파일 경계로 구현한다 |
| canonical 입력·신원·접수/실행·CAS seam | C1와 C3. C5가 별도 parser나 턴 호출을 만들지 않는다 |
| 중앙 관직·추천·공부 부모 및 공개 office-claims DTO | C6. 지방 source를 같은 transaction export seam에서 소비한다 |
| catalog·AI/help 공유 등록 | C7. 등록을 실제 배달로 올리지 않는다 |
| 통합 PR·충돌·실행 슬롯 / 원장·계약판 | C0 / C9. D124 다섯 GET은 통합 PR #1396에서 제외한다 |

D125의 제품 Codex 변경은 C0 통합 후보에 정확 커밋·파일로 인계한다. #1383은 원본 13파일로 결합하고, #1151은 이 교체 문서 한 파일만 결합한다. 오래된 #1151 브랜치 전체를 병합하지 않는다. 새 기능별 PR을 임의로 만들거나 기존 작업·claim·WIP를 지우지 않는다.

## 2. 읽기의 공통 계약

다섯 경로는 모두 `?generalId=<본인 장수>`를 받는다. 인증→live 소유 장수→현재 process world 순서로 검증한다. 오류는 `401 AUTH_REQUIRED`, `403 FORBIDDEN`, `400 INVALID_GENERAL_ID`이며 본문은 `{error:{code,message}}`다. 인증 전 다른 장수나 원천의 존재를 노출하지 않는다. 모든 200·4xx 응답은 `Cache-Control: no-store`다.

root의 `status`는 `READY | NOT_SEEDED | UNAVAILABLE`, `reason`은 코드 문자열 또는 명시 null, `now`는 실제 Phase `{year,month,phase}` 또는 명시 null이다. month는 1–12, phase는 1–3이다. 원천 미생성은 NOT_SEEDED·목록 null·reason, 원천 손상/필수 계산 실패는 UNAVAILABLE·목록 null·reason이다. 검증된 실제 원천에서만 READY·빈 배열을 내보낸다. 일반 장수에게 세력이 필요한 frontier는 `UNAVAILABLE / NO_NATION`을 반환한다.

선택 필드도 키를 생략하지 않고 명시 null로 직렬화한다. 모르는 계약 enum·손상·안전 정수 범위를 벗어난 Long은 fail closed다. Long은 JSON 숫자로 내보내며 문자열 변환·반올림·상한 고정으로 숨기지 않는다. `revision`, `snapshotToken`, `worldId`가 원천에 없으면 꾸미지 않는다. codec 버전·달력·현재시각은 mutation revision이 아니다.

각 읽기는 하나의 read-only REPEATABLE_READ transaction 안에서 필요한 원천을 materialize한다. 별도 HTTP 결과·별도 transaction·비동기/lazy read를 하나의 snapshot으로 합치지 않는다. 공개할 수 없는 행은 개수와 함께 제외한다. 비인가 내부 키·receipt·원문을 reason으로 노출하지 않는다. 읽기는 동의·만료·이행·효과를 새로 쓰지 않는다.

사람·장소·관직 표시명은 서버의 검증된 한글 원천을 사용한다. 이름을 확인하지 못하면 명시 null이다. `data/curated/han/local-offices.json` 7행과 `OfficeCatalog`에는 원본 한자 name만 있고 구조화된 한글명은 없다. 한글 필드 공급·loader 연결은 별도 source 작업이다. `ProvinceNamesReader`의 pinned map displayName은 실제 현 이름 근거이며 `zhou:`·`hhs-group:` 관할 ID와의 무검증 join 근거가 아니다.

## 3. 지방 관직·받은 제안

### 3.1 지방 관직 GET — K8-03

`GET /api/court/local-offices?generalId=` root는 기존 `status,now,localOffices,appointmentOptions,pendingOffers`와 `reason`을 사용한다. `localOfficeTenures` 원천 키가 없으면 NOT_SEEDED다. 목록의 필수 관할 snapshot이 없으면 UNAVAILABLE·localOffices=null이며 추정 NOMINAL이나 state=null 행을 만들지 않는다.

기존 fixture 필드는 유지하고 `officeLabel`(한글 관직명), `jurisdictionName`(한글 관할명), `seatCountyName`(한글 치소 현명)을 더한다. `officeName`은 기존 사료 표기를 유지할 수 있으며 **한글 화면 이름은 officeLabel**이다. 예시 fixture의 이름·ID·공석은 실제 저장/관할 원천이 아니다.

`state`는 `PENDING_ACCEPTANCE | AWAITING_ARRIVAL | EFFECTIVE | NOMINAL`이다. `actualCountyIds`는 EFFECTIVE일 때만 실제 목록이고 다른 상태에서는 []다. `missing`은 실제 `OfficeEvidence` 8종만 사용한다: `LIVING_CLAIM, ACCEPTED_TENURE, ASSUMED_SEAT, SEAT_OWNED, HOLDER_AT_SEAT, COUNTY_MAJORITY, WAREHOUSE_CONNECTION, LOCAL_MAGISTRATE_OR_GARRISON`.

`OfficeTenureCodec`/`OfficeCredentialCodec`의 저장 사실과 `OfficeCapabilityResolver`의 현재 관할·치소·위치·소유·창고·현령/주둔 snapshot을 함께 검증한다. accepted/assumed만으로 EFFECTIVE를 만들지 않는다. 실제 치소 상실로 실효가 사라져도 재임 이력은 유지한다. 縣令·縣長·侯國相의 현 배치와 지방 재임을 중복 생성하지 않는다.

ACL은 actor의 같은 세력 재임뿐이다. 임명 권한이 없거나 선택지 원천을 읽을 수 없으면 `appointmentOptions=null`이며, 실제 검증된 빈 선택지만 []다. 각 option의 `available=true`에는 `blocked=null`, false에는 `{code,reason}`를 명시하며 code는 실제 `OfficeAppointmentFailure`다. `candidateName`은 서버 한글명이다.

`pendingOffers`에는 기존 필드와 `candidateName,jurisdictionName,seatCountyId,issuedAt`을 제공한다. 실제 수신/발신 허용 범위만 읽고 `PENDING | ACCEPTED | REFUSED`를 그대로 보존한다. 읽는 시각이 기한을 지났다고 상태를 바꾸지 않는다. `vacancies`는 검증된 배치 가능 슬롯 source가 있을 때만 추가한다. 없으면 이 첫 계약에서 제외하며 가짜 공석을 생성하지 않는다. 임명·파면 단추는 실제 등록·배달된 canonical 입력을 따른다.

### 3.2 받은 제안 GET — #1383의 별도 고정 원본

`GET /api/court/offers?generalId=`는 본인 장수의 실제 `General.meta.officeAppointmentOffer`만 strict codec으로 읽는다. OFFICE의 실제 상태는 `PENDING | ACCEPTED | REFUSED`이고 원본 terms·issued/due를 보존한다. stored OFFICE 원천이 정상일 때 section은 READY/UNVERSIONED다. 봉신 체결·변경·관직 추천의 미연결 section은 UNAVAILABLE·records=null이며 전체 읽기를 완전 READY로 표시하지 않는다.

durable opaque `offerId`, `revisionToken`, `replyInputId` producer가 없으므로 해당 값은 null, 응답 상태는 UNAVAILABLE, 응답 선택지는 []다. 실제 raw OFFICE sourceRef를 reply identity로 사용하지 않는다. 추천의 별도 상태 기계와 OFFICE를 여섯 상태 공통 enum으로 합치지 않는다. 기존 OFFICE DispatchPolicy의 12순과 봉신 제안의 3순도 서로 바꾸지 않는다.

## 4. 봉신·원군의 확정 정책과 읽기

### 4.1 봉신 계약

main의 #1373 봉신 HTTP는 저장된 계약 조건 읽기다. 부분 원천 결손을 완전한 founding 선택지·변경·종료·상납/원군 실행이 있다고 해석하지 않는다. `VassalState` codec1의 계약·영수증이 actual 요청·응답·clock producer를 대신하지 않는다.

Q6–Q9의 최신 결정은 다음과 같다. 군주와 봉신 어느 쪽도 **상납률·봉토·원군 의무·자치** 변경을 제안할 수 있고 양자 명시 합의 전에는 기존 계약을 유지한다. 체결·변경 제안은 3순 뒤 자동 수락 없이 만료하며 기한 전 취소할 수 있고 무응답 벌칙은 없다. 한쪽의 명시 통지로 계약을 종료하면 미래 의무만 끝난다. 소속·봉토 소유·독립을 자동 변경하지 않고 과거 확정 위반을 보존한다.

새 proposal/revision/consent producer는 원 요청·당사자·정확 조건·현재 계약 revision·기한·응답을 결속해야 한다. 수락을 다른 조건에 재사용하지 않는다. 종료·월수입 직후 상납·부 해제/주공 지위 사건은 각 실제 실행 경로와 같은 recorder/flush로 연결한다. HTTP 접수 202나 순수 모델 판정을 정치 효과·실물 이전으로 세지 않는다.

### 4.2 받은 원군 요청 GET — K8-17

`GET /api/court/reinforcement-requests?generalId=`는 **실제 수신 봉신 본인**만 읽는다. root는 `{status,reason,now,requests}`다. 요청 writer·작전 원천이 없으면 NOT_SEEDED·requests=null이다.

행의 계약 키는 `reinforcementRequestId,contractId,issuerGeneralName,issuerNationName,requestedTroops,obligatedTroops,minimumReducedTroops,responseDeadlineTurn,answeredTurn,replyKind,offeredTroops,outcome,committedTroops,decisionDueTurn,target,targetStatus,execution,calendarStatus,responseDeadlineAt,answeredAt`다. 요청 ID는 #1373 `reinforcementResponse.requestId`와 동일 실제 source identity다. 새 random ID나 계약 ID로 대체하지 않는다.

`replyKind`는 `ACCEPT | DELAY | REDUCE | REFUSE` 또는 미응답 null, `outcome`은 `PENDING | ACCEPTED | DELAYED | REDUCED | BREACH`, `targetStatus`는 `AVAILABLE | NOT_APPLICABLE | UNAVAILABLE`이다. 실제 대상·실행 source가 없는 행을 가정으로 생성하지 않는다. `execution={status,reason}`의 상태와 `target`의 실제 shape는 operation producer 연결 시 strict 타입에 맞춘다. 그 전에는 NOT_SEEDED 목록 null을 유지한다.

순 Long과 달력 Phase의 timeBasis를 실제로 검증한 경우에만 `calendarStatus=READY`와 시점을 제공한다. 검증 전에는 `calendarStatus=UNAVAILABLE`, `responseDeadlineAt=null,answeredAt=null`이다. `decisionDueTurn`은 응답 기한으로 사용하지 않는다. 미응답 null·REFUSE의 0·원군 의무 0 예외를 분리한다. 응답 단추·병력 한도는 실제 `court.reinforcementReply` 등록·배달과 서버 평가값을 따른다.

Q10–Q14: 응답 기한은 요청 후 2순이다. 정상 축소의 최소량은 `ceil(min(요청량,계약 의무량)/2)`이며 허용된 나머지를 보내지 않아도 위반이 아니다. 이행은 약속 병력의 **실제 출발**이고, 수락/축소/지연 응답 확정 후 2순 안에 출발하며 자동 추가 연장은 없다. 유효 위반은 요청당 한 번 기록하고 충성 5를 줄인다. 정상 축소·작전 취소·서버 문제는 제외한다. 자동 계약 종료·독립·봉토 회수는 없다. 단순 응답 ACCEPTED는 실제 출발 증거가 아니다.

## 5. 참모 제안·직속 명령

### 5.1 참모 제안 GET — K8-06

`GET /api/retinue/proposals?generalId=` root는 `{status,now,proposals,reason}`이며 수신 본인만 읽는다. 실제 제안 model·strict codec·발행 producer가 없으므로 첫 source 상태는 NOT_SEEDED·proposals=null이다. 옛 Retainer/Subfaction 구조를 현재 카드·부 모델에 다시 만들지 않고 legacy retainerId를 generalId로 자동 alias하지 않는다.

정상 행의 소비 요구는 `proposalId,retainerId,retainerName,proposalType,target{kind,id,name},evidence[{code,text}],biasFactors[{code,text}],createdAt,expiresAt,status,confidence,inputId,argsDraft`다. 표시명과 text는 검증된 한글이다. formula 전 confidence는 null이며 내부 score를 HTTP에 공개하지 않는다. 등록되지 않은 inputId와 대응되지 않는 canonical argsDraft는 null이고 활성 채택 단추가 없다.

**proposalType·status의 확정 enum 목록은 아직 없다.** P-5 필드 이름은 enum vocabulary나 현재 producer가 아니다. GET 담당자는 READY 예시 행·임의 ACCEPTED/APPLIED·가짜 만료 시간을 만들지 않는다. C5의 실제 typed source contract와 소비 ACK로 목록을 고정한 뒤에만 정상 행 projection/fixture를 배달한다. 원천 결손 첫 GET의 합의와 정상 제안 행 enum 미결을 구분한다.

D58에 따라 참모는 허용 시야에서 정해진 규칙·근거로 제안한다. 여러 인물의 찬반을 모으는 회의는 이 범위에 없다. 채택·고쳐서 채택은 원래 canonical 입력의 정상 인자·권한/자원/시야 재검사를 통과해야 한다. 접수 성공과 실제 실행을 분리하며 거부 입력도 실제 등록·배달 뒤에만 제공한다.

D59에 따라 거부·만료된 **동일 canonical 의미**는 영속 억제한다. 다른 ID·제안자·문구·clock·score·코드 버전으로 다시 발행하지 않는다. 실제 권한·상황의 중대한 사실 변경은 새 제안 근거로 기록할 수 있으나 옛 제안을 부활시키지 않는다. 제안 개수 한도·confidence 식·일반 제안의 정확 만료 기간을 여기서 새로 정하지 않는다.

### 5.2 D60–D68 직속 명령 연결

| 결정 | 실행 계약 |
| --- | --- |
| D60 | 유효 명령 우선. 원 개인 예약은 부하만 보게 보존, 자동 재예약 없음. 명령 부적격이면 원 예약 평가 |
| D61 | 상관이 인가된 부하 소유 부곡을 명시 선택. 재편·소유 이전권을 추가하지 않음 |
| D62 | 상관 1순 발행 + 부하 1순 실행 |
| D63 | 더 높은 상관의 유효 명령이 앞 명령을 교체. 각 action/resource grant를 먼저 검증하고 실제 우위 source를 비교 |
| D64 | 대상 순 잠금 전 실제 발행자가 활성 명령 취소, 이후 불가. 부하 거부권 없음 |
| D65 | 허용되는 직속 명령 행동을 함께 설계·구현. catalog 전체에 포괄 권한 부여하지 않음 |
| D66 | 상관이 고친 건의는 명령 경로로. 정상 발행순·불변 조건·재검사를 유지하며 202 즉시 효과 아님 |
| D67 | NPC도 자기 순에 규칙으로 채택·반려. 자동/묵인 수락 없음 |
| D68 | 구조화 병력·자원 요청에 NPC의 실제 판단·지원 연결. 의향 응답만으로 실물 지원 완료 아님 |

관직명·등급·같은 세력·ADMIN·클라이언트 priority는 직접 명령 grant나 우위 producer가 아니다. 교체 CAS는 양쪽 현재 권한·자원·source revision·대상 잠금을 다시 검사하고 기존 명령 종결/새 명령 활성/receipt를 한 실행으로 기록한다. 동순위·비교 불가 결과·교체 취소 후 옛 명령 복구·비용 환급 등의 남은 정책을 옛 초안으로 확정하지 않는다. 일반 건의의 만료 기간에 봉신 3순·원군 2순·충성 -5를 복사하지 않는다.

## 6. D32 지방 속관과 중앙 seam

D32는 본직 司隸校尉·太傅·執金吾·御史中丞·侍中 다섯을 승인했다. C5는 지방 司隸, C6는 중앙 네 본직을 맡는다. 屬國都尉는 이 다섯 밖이며 자동 추가하지 않는다. 속관 부모 범위는 州·司隸·郡·公府이고 縣 속관은 제외한다. 관직자는 군주 동의 없이 자기 府 소속에게 임용하며 실제 membership·현재 부모 권한을 검증한다. 천거는 추천 흐름에 합치고 정기 孝廉/茂才 quota를 만들지 않는다. 지방 부모 tenure·credential producer는 C5, 중앙·公府 부모/추천 및 공개 office-claims는 C6다. slot·정원·임용 정책은 승인 정의의 실제 source만 사용하고 사료 숫자·legacy MAX_RETAINERS·동일 주군으로 만들어내지 않는다.

C1/C7의 등록 경계와 맞출 canonical 입력은 `court.offerReply, court.officeNominate, court.officeNominationReview, court.officeNominationReply, court.appointSubordinate, court.dismissSubordinate`다. 등록·PLANNED는 parser·handler·권한·배달의 증거가 아니다. 속관 임명 인자는 `parentReference,slotKey,candidateGeneralId,expectedParentRevision,expectedMembershipRevision`; 파면/사직은 `assignmentId,operation,expectedAssignmentRevision,expectedParentRevision`의 준비 계약이다. 최종 typed parser/schema는 C1 exact seam을 따른다.

부모·slot·offer·assignment·故吏/이력·physical assessment export는 C6 read-only REPEATABLE_READ의 **동일 callback** 안에서 materialize한다. 별도 HTTP join이나 REQUIRES_NEW를 추가하지 않는다. 실제 부모 revision·府 membership binding·slot reservation producer 없이 accepted 재임·Retainer master·故吏를 임용 권한으로 바꾸지 않는다. snapshot stamp는 CAS revision이 아니다.

부모 상실로 해당 속관 권한은 종료하고 후임 자동 승계는 없다. 부모 nominal과 SEAT_LOST에 의한 물리 능력 상실을 구별하며 치소 상실만으로 모든 속관 이력을 종료하도록 확대하지 않는다. 故吏/이전 재직 이력은 현재 소속이나 명령권을 부활시키지 않는다. admission 뒤 execution에서도 현재 부모·소속·slot·revision을 재검사하고 원자 receipt/append history/종료/관계 근거를 recorder에 기록한다.

## 7. 계절·통행·주변 세계

### 7.1 계절 GET — K8-08

`GET /api/world/season?generalId=` root는 `{status,reason,now,season,phaseOfYear,passageStatus,closedEdges,eventsStatus,events}`다. 계절·통행은 세계 공개 사실, 사건은 actor 세력이 현재 소유하는 현만 반환한다. source가 없으면 season/phaseOfYear는 null/NOT_SEEDED다. season enum은 `SPRING | SUMMER | AUTUMN | WINTER`; `phaseOfYear=(month-1)*3+(phase-1)`로 0–35이며 실제 Phase와 calendar definition을 검증한다. front-info와 불일치하면 확인 중으로 두고 계산으로 덮지 않는다.

`passageStatus=READY | UNAVAILABLE`이며 UNAVAILABLE이면 closedEdges=null이다. 정상 edge만 `{edgeId,endpoints,label}`로 내보내고 endpoints는 실제 traversal edge의 from/to 문자열 두 개다. 새로운 지도 ID·방향·별칭을 만들지 않는다. K2의 지도 소비 확인 전 새 geometry를 약속하지 않는다. label은 검증된 표시명 또는 null이다. 실제 계산 전 []로 전체 개방을 선언하지 않는다.

`eventsStatus=READY | NOT_SEEDED | UNAVAILABLE`, `events`는 `[{countyId,kind,effect:{trust,population,agriculture,displaced,passageClosed}}]` 또는 null이다. kind는 `DROUGHT | FLOOD | PLAGUE | LOCUST | FREEZE | RAINY_PASSAGE`다. 순수 `SeasonalOccurrence`는 발생/저장의 증거가 아니다. 현재 계절에 결속된 event producer·현 소유 snapshot이 없으면 NOT_SEEDED/null이다. 지난 기록을 현재 사건으로 재생하지 않는다. 과거 사건 feed는 기록 담당의 별도 범위다.

실제 writer는 C3의 확정 호출 주기·지형/현 소유·resource 한도·seed·정렬·중복방지 키에 연결한다. 이 문서는 월 1회나 매 순 추첨을 새 정책으로 확정하지 않는다. 추첨·효과·유민 이동·통행·영수증을 같은 authoritative recorder/flush에 남기고 restart에서 중복 적용하지 않는다. 이동·수송·보급은 같은 통행 source를 소비해야 하며 미확인 지형을 PLAIN으로 대체하지 않는다.

### 7.2 주변 세계 GET — K8-09

`GET /api/frontier?generalId=` root는 `{status,now,actors,reason}`다. rows는 `{actorId,name,relation,borderCountyIds}`이며 actorId는 실제 문자열 ID, name은 검증된 한글 또는 null, borderCountyIds는 실제 현 Int[]다. relation은 `HOSTILE | NEUTRAL | TRIBUTARY | SUBMITTED | TRADE`다. 외부 actor를 일반 세력 Int ID로 바꾸지 않는다.

시나리오·기간·현재 actor 세력에 허용된 접촉 원장이 없으면 NOT_SEEDED/null이다. 실제 건강한 무접촉만 READY/[]다. `external-actors.json`의 8 CANDIDATE는 접촉 8행이 아니다. 사료 이름만으로 ACTIVE를 만들거나 다른 세력 접촉·개수를 공개하지 않는다. 세력이 없는 장수는 UNAVAILABLE/NO_NATION이다. 사건은 이 GET에 넣지 않고 외교 선택지는 실제 입력 계약 이후다.

침입·교역·조공·내속의 실제 대가/효과·recipient·배송·관계 변화는 별도 확정 source와 실행이 필요하다. 내속을 자동 세력 생성·현 점령으로 바꾸지 않는다. 순수 ExternalWorld의 enum을 외교 handler 완료로 세지 않는다.

## 8. 역정보·저장·수용 증거

[역정보 모델](misinformation-model.md)의 FalseSighting 피해자는 장수 `victimGeneralId`다. 피해자에게 시전자·오염 ID·진위 표식을 노출하지 않고 실제 시야·목격 형태만 소비한다. 의병(가짜 군세)과 의병모집(실제 인물/부대 생성), 반간의 기존 카드 의미를 구별하며 다른 canonical 입력의 이름만 바꿔 배달하지 않는다. 실제 군단·조우·보급 source에 phantom을 삽입하지 않는다. 저장/만료/재첩보와 HTTP·SSE·지난 순·참모 feature의 각 observer 경계를 실제 source에서 검증한다.

쓰기 정본은 TurnWorld 메모리와 `ChangeRecorder.created/dirty/deleted/recordKv` 및 JDBC flush다. API 읽기에서 DB/JPA 인라인 write를 하지 않는다. strict codec은 버전·필드·enum·중복·참조를 검증하고 hot JSON/cold 객체를 동일 정규화 규칙으로 읽는다. 새 durable producer는 world·identity·terms·revision·timeBasis·request digest·terminal receipt·append history를 실제 transaction에 결속한다. codec 손상을 빈 READY로 덮지 않는다.

| 검증 범위 | 필요한 실제 증거 |
| --- | --- |
| 다섯 새 GET | 실제 principal·본인/타인·401/403/400/no-store, NOT_SEEDED/null, 손상 UNAVAILABLE, 정상 빈/행, enum·safe Long·표시명·ACL 고정 응답 |
| 관직·속관 | 현재 관할8근거·부모/소속/slot, 부임/치소 상실, stale revision·경쟁 reply·부모 종료·후임/故吏 권한 부활 거절 |
| 봉신·원군 | 양자 조건 CAS·3순 proposal·2순 응답/출발 구분·실제 병력/상납 보존·요청당 위반1회·정상 축소/서버 문제 제외 |
| 참모·직속 명령 | 동일 의미 거부/만료 영속 억제, 실제 action grant·우위·잠금·비공개 원예약·NPC 자기 순·실제 지원 |
| 계절·주변·역정보 | 현재 사건/접촉 pin·시야 격리·resource 수지·공유 통행·중복0·피해자 비누출 |
| 공통 저장/통합 | 실제 flush/rollback/cold reload·race·retry·receipt 재현, 정확 combined head CI와 영역 독립 리뷰 |

고정 HTTP JSON은 wire shape를 검증하는 자료다. double·예시·미생성 source의 fixture를 실제 producer 양성 증거로 세지 않는다. 정상 CI·XML·run/head·skip과 미실행 범위를 그대로 기록한다. 문서·등록·HTTP202·개별 CI 초록만으로 SERVER_DONE/VERIFIED·전체 2층 완료를 선언하지 않는다. 화면 소비가 필요한 기능은 실제 서버·화면 증거를 별도로 갖춘 뒤 해당 단계 완료로 기록한다.
