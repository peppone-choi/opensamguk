# 공개 황실 court 읽기

K8-10의 정확 소비 답과 사용자 D123에 따른 `GET /api/imperial/court?generalId=`다. JWT로 검증된 계정이 현재 process world에서 소유한 장수 ID만 generalId로 받는다. query userId나 ADMIN 역할로 소유를 대신하지 않는다. 이 장수의 세력과 관계없이 D123 공개 부분집합을 같은 read-only REPEATABLE_READ에서 읽는다.

## 응답과 null

- 200 `{"status":"READY","lines":[…]}`: 실제 codec를 검증한 목록. READY+lines=[]는 검증된 빈 황실이다.
- 200 `{"status":"NOT_SEEDED","lines":[]}`: 실제 imperialWorld key 부재. 공위/황실멸망을 뜻하지 않는다.
- 409 `{"status":"STATE_UNAVAILABLE","lines":[]}`: world/codec·허용된 참조/현재 artifact 결손. 부분 상세 성공을 내리지 않는다.
- 401 `{"error":{"code":"AUTH_REQUIRED","message":"로그인이 필요합니다."}}`, 403 `{"error":{"code":"FORBIDDEN","message":"본인 장수로만 조회할 수 있습니다."}}`.
- 위 응답은 `Cache-Control: no-store`다.

각 line의 키는 항상 `code/name/status/holderGeneralId/emperorName/courtCityId/courtCityName/regentGeneralId/regentName/courtNationId/courtNationName/fieldStates`다. nullable 값은 명시 null로 보낸다. fieldStates는 줄마다 `holder/courtCity/regent/courtNation`의 READY/NOT_APPLICABLE/UNAVAILABLE다.

ACTIVE는 황제·조정 城·섭정·지키는 세력을 공개한다. 명시적 미지정 optional ID는 null/READY다. 참조 행은 유효하지만 현재 이름이 blank이면 ID는 보존하고 이름은 null, 해당 fieldState는 UNAVAILABLE다. 이름/ID를 다른 장수나 소유 세력으로 추정하지 않는다. VACANT와 ENDED는 code/name/status만 공개하고 모든 상세 ID/이름을 null, 네 fieldStates를 NOT_APPLICABLE로 보낸다. non-active의 비공개 참조 행을 조회하지 않는다.

## 원천·시야 경계

ImperialWorldCodec schema1과 동일 process world의 General/Nation/City 행을 읽는다. ACTIVE의 non-null courtCityId는 현재 world artifact의 도시 binding도 확인한다. 잘못된 world/ID·허용 참조 행 누락은 409이며, 다른 세력이라는 이유로 D123 공개 자료를 가리지 않는다. source에 저장된 legitimacy·heir·candidate·관계/호의·조서·인장·방침과 원본 meta는 응답에 싣지 않는다. 실제 값의 저장 writer를 만들거나 시나리오 미시드를 채우지 않는다.

H03 황제 위치는 기존 익명 `/api/imperial/presence`가 계속 제공한다. court는 spatial/presence reader에 의존하지 않고 H03 키를 내리지 않는다. 화면은 `lines[].code`와 presence `lineCode`로 각각 그리며 두 HTTP 요청의 시점 일치를 원자성으로 주장하지 않는다. snapshot/sources/token/sourceRevision을 발급하거나 이름을 화면의 다른 API join에 맡기지 않는다.

## 검증의 한계

시험은 실제 ImperialWorldCodec, 현재 artifact bundle과 서명된 JWT security chain→Query→Reader→MVC 응답을 검증한다. GeneralResolver의 계정 소유 source와 DB repositories는 시험에서 mock이다. read-only REPEATABLE_READ annotation과 mock/HTTP 시험을 실제 PG 동시 snapshot·3190 DB seed/첫tick/운영 공개 proof로 세지 않는다. source HTTP는 D123 범위만 제공하며 PUBLIC-IMPERIAL-READY는 실제 actor/seed/공간/cold/read 증거 뒤에 확인한다.

## 트랜잭션 실패와 요청 경계

reader와 query의 read-only REPEATABLE_READ 안에서는 검증/협력자 예외를 성공 DTO로 삼키지 않는다. 트랜잭션 프록시가 rollback을 마친 뒤 controller가 검증 예외와 원래409를 STATE_UNAVAILABLE/lines[]/no-store 409로 변환한다. 원래503 등 다른 서비스 예외는409로 바꾸지 않는다.

generalId는 nullable 원문 문자열로 받아 인증을 먼저 검사한다. 익명은 누락·잘못된 숫자 여부와 관계없이 AUTH_REQUIRED 401/로그인 메시지/no-store이며, 인증 뒤 누락·잘못된 숫자·Int초과는 INVALID_GENERAL_ID 400/고정 메시지/no-store다. 0·음수·타장수는 기존403이다. 서버 admission의 VERIFYING 403·원천 unavailable503은 공통 필터가 world/actor 읽기 전에 닫는다. 공개 범위와 JSON DTO는 바꾸지 않는다.
