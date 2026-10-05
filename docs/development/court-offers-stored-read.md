# 받은 제안의 저장 사실 조회

`GET /api/court/offers?generalId=<본인 장수>`는 검증된 JWT 계정의 살아 있는 본인 장수에 대해 저장 사실을 조회한다. controller → query → reader와 REPEATABLE_READ 읽기 트랜잭션을 사용한다. source 읽기는 제안 발행·응답 접수·재임 적용의 증거가 아니다.

## 응답

root의 `status`는 UNAVAILABLE이다. 네 원천 전체가 연결되지 않았기 때문이다. `now`는 실제 process world의 연월순이다. 월드 부재·유효하지 않은 월드 형식/시계는 503 WORLD_UNAVAILABLE이며 정상 조회 응답으로 바꾸지 않는다. `offers`에는 확인한 개인 저장 임명만 들어간다. `sources`는 OFFICE/VASSAL_FOUNDING/VASSAL_AMENDMENT/OFFICE_NOMINATION을 각각 보고하며 읽지 못한 source는 readStatus=UNAVAILABLE/records=null/revisionStatus=null/revisionToken=null이다. 미생산을 READY+[]나 NOT_SEEDED로 표시하지 않는다.

OFFICE 원천은 같은 현재 월드·본인 계정의 `General.meta[officeAppointmentOffer]`와 기존 OfficeAppointmentOffer.read 형식이다. key가 실제 존재하고 엄격 decode와 candidate 본인 검사가 성공한 경우만 해당 source의 readStatus=READY/records=[원본]이 된다. 이는 저장 사실을 읽었다는 뜻이며 app의 발행 writer가 배달됐다는 뜻이 아니다. absent/null/손상/다른 actor·world/다른 후보는 UNAVAILABLE이다. nation 전체나 다른 장수 meta를 수신함으로 조인하지 않는다.

행은 원본 state PENDING/ACCEPTED/REFUSED와 issuer·officeId/jurisdictionId/seatCountyId·issuedAt/dueAt의 실제 Phase를 보존한다. 기한이 지났다고 reader에서 ACCEPTED로 바꾸지 않는다. 원본 ID는 sourceRef.kind=OFFICE/sourceRef.id에만 보존한다. durable opaque lookup과 reply parser가 없으므로 offerId/replyInputId는 null, responseStatus=UNAVAILABLE/responseOptions=[]이다. 저장 형식 version1을 CAS revision으로 바꾸지 않으며 건강한 OFFICE만 source revisionStatus=UNVERSIONED/revisionToken=null이다.

기존 계약의 planned 응답 라우팅은 앞 세 kind의 court.offerReply, 추천의 court.officeNominationReply다. 현재 실제 입력 등록·opaque lookup·CAS가 없어 이 조회는 활성 응답과 argsDraft를 공급하지 않는다. 향후 실제 parser/producer가 연결돼야 nullable replyInputId/offerId를 채운다.

VASSAL_FOUNDING/AMENDMENT에는 제안 저장 key/codec/writer가 없다. 이미 체결된 vassalContracts를 제안으로 바꾸지 않는다. 사용자 Q9의 봉신 체결·변경 제안3순/기한 뒤 자동수락 없음/이전 계약 유지/무응답 벌칙 없음은 그대로 유지하되 없는 발행·기한을 만들어내지 않는다. OFFICE의 저장 Phase에 이 봉신 정책을 자동 적용하거나 DispatchPolicy 기본12순을 봉신 정책으로 복사하지 않는다.

OFFICE_NOMINATION은 C6 소유의 9state 모델이지만 저장 key/codec/명시 audience/producer가 아직 연결되지 않았다. officeClaimHistory/imperialEdicts·같은 세력·courtId·reviewTurnLimit을 추천 source/응답 기한으로 바꾸지 않는다. 실제 source가 연결될 때 requestedOfficeId와 review.offeredOfficeId, 요청/확정 관할, null 시각, 원본9state를 구분하는 기존 계약을 따른다.

## 오류와 검증 범위

익명·무효 토큰은401 AUTH_REQUIRED, 본인 actor가 아니면403 FORBIDDEN이다. 인증 후 generalId 누락·숫자 형식 오류는400 INVALID_GENERAL_ID다. process world가 actor의 월드와 다르면 개인 source 조회 전에 403 FORBIDDEN이다. 공개 상태 VERIFYING은 기존 admission 필터의 403 SERVER_NOT_PUBLIC, 공개 상태 source 불명은 503 SERVER_ADMISSION_UNAVAILABLE이다. 200/400/401/403/503은 모두 Cache-Control:no-store이다. ADMIN과 query userId는 본인 확인을 대신하지 않는다.

HTTP 시험은 실제 JWT/security/controller/query/reader 경로와 고정 응답을 검증한다. 저장소/GeneralResolver는 대역이며 actual JDBC snapshot·flush·cold restart·운영 검증을 대신하지 않는다. shared CourtStateStore·입력 원장·domain schemas·nomination writer는 변경하지 않는다.
