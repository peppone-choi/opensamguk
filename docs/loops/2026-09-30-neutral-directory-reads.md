# 중립 인물·세력 읽기 계약

## 제공 경로

| 경로 | 인증·스코프 | 응답 |
|---|---|---|
| GET /api/people | 로그인 본인 장수, scope=ALL/NATION/RETINUE | status, people, nextCursor |
| GET /api/nation/summary?generalId= | 본인 user_id와 장수 일치, 소속 세력 | status, nation, lord, capitalCityId, countyCount, retinueCount, stockTotal, population, troops |
| GET /api/admin/nations | 검증된 ADMIN 토큰, 처리 world 전체 | status, nations |
| GET /api/admin/people | 검증된 ADMIN 토큰, 처리 world 전체 | status, people, nextCursor |

인물 목록 인자: q(최대 100자), sort=ID, cursor, limit(1..100, 기본 50). 이름은 부분 검색하며 ID 오름차순으로 limit+1 후보를 가져와 다음 cursor를 만든다. cursor는 세계·조회 장수·scope·정규화한 검색어에 묶인다. 다른 scope·세계·사용자의 cursor는 400이다. cursor는 권한을 주지 않는다. 관리자 페이지에서는 viewer=0이며 ADMIN 검증을 매 요청 반복한다.

각 인물: generalId, name, portrait{picture,imageServer}, affiliation{nationId,name,color}|null, role, lordGeneralId, stats{leadership,strength,intel,politics,charm}, aptitudes{command,administration,strategy,envoy}, locationCityId, bonds[{kind,targetId}].

개인 읽기의 상세 값(role, lordGeneralId, stats, aptitudes, locationCityId, bonds)은 **본인과 본인의 직접 가신 카드에 연결된 장수만** 받는다. 같은 세력이어도 다른 주공의 가신·일반 동료는 공개 이름·초상·소속만 받는다. source-validated personPolicy가 없으면 능력치는 null이다. 본인 관계 원장이 없거나 잘못되면 bonds=null이지 관계가 없다는 뜻의 빈 목록이 아니다. 적성은 엔진과 같은 Aptitude 정본 가중을 사용한다. 관리자는 상세 투영을 받을 수 있다. 개인 가신 관계·주공은 general_retainers와 LordStatus로 읽는다. 세력 요약의 대표는 엔진과 동일한 DomesticRules.rulerOf(해당 세력에서 officerLevel=12인 한 명이며 LordStatus=true)를 공유한다. 같은 세력에 주공이 여럿이어도 이 대표 판정은 변하지 않는다.

세력 요약: countyCount=ActiveWorldArtifactResolver의 administrativeCountyIds에 속한 소유 縣 수, retinueCount=그 국가 장수를 주공으로 하는 실제 가신 카드 수. population=동일 소유 縣의 실제 호구 합. stockTotal은 CountyWarehouse 정본의 다섯 자원 합. troops.city는 CityMilitaryState, troops.bugok은 같은 국가 장수를 주공으로 하는 부곡 원장 합이며 개인 crew를 더하지 않는다. 창고가 없는 縣은 재고 합에 0을 기여한다. 관·수·진 등 행정 縣이 아닌 행은 수·호구·성 병력·재고에서 제외한다. 창고 또는 군사 메타가 손상되면 해당 합은 null이고 status=PARTIAL이다. 소유 縣이 없으면 실제 빈 합은 0이다. 세력 없는 장수는 NO_NATION이다. 세계 행이나 지도 artifact 식별이 없음은 UNAVAILABLE이고 다른 세계 행이 섞이면 409로 닫는다.

## 응답·보호

개인 신원은 JWT principal이다. people의 임의 generalId query는 신원 선택에 쓰지 않는다. nation/summary의 generalId는 GeneralResolver가 확정한 현행 플레이 가능 장수와 일치하고 실제 user_id도 본인인지 먼저 검사한다. 과거 장수 행에 user_id가 남았다는 이유로 읽기 권한을 주지 않는다. 인증 없음/무효 401, 타인 장수 또는 일반 계정의 관리자 읽기 403, 성공 no-store. 플레이어와 관리자 응답 DTO에는 ruleProfile, 경험/계급/삭턴/개인 병력 등 기존 규칙 필드를 싣지 않는다. 읽기만 하며 운영 write 또는 migration이 없다.

## 후속

- /api/session world 시각은 C8 TurnLoopHealth와 동일 관측 시각·catchUp 스냅샷을 공유해야 한다. C8 #1073이 main에 착지한 후 기존 shared projection으로 결합하며 복사한 별도 정본을 만들지 않는다. pausedReason/maintenance를 status로 추정하지 않는다.
- tutorial/unread/buName는 담당 원장과 권한 계약으로 연결한다. 이번 네 경로에는 미구현 칸을 0·빈 목록으로 넣지 않는다.
- county 상세·목록은 FULL/INTEL/FOG 정본 투영을 결합한 후 추가한다. 새 외교 읽기 계약도 후속이므로 이 요약에서 legacy state code를 중립 필드명으로 재포장하지 않는다.
- 기존 API는 화면이 새 경로로 옮겨 실제 검증한 후 은퇴한다. join GET/POST의 현 MakeGeneral 생성 의미는 출사(enlistment)나 생성 T3와 동일하지 않다.

## 검증

CampaignDirectoryReaderTest는 타국·같은 국가 타인 비공개 필드, 타인/세계 스코프, cursor 재사용, 행정 縣만의 자원·호구·성/부곡 병력 집계, 창고 없음과 손상 구분, 군주 정본 공유, artifact 미상/세계 불일치을 검사한다. CampaignDirectorySecurityChainTest는 실제 JWT 필터와 SecurityFilterChain을 통해 무인증/무효 토큰/USER 관리자 401·403 및 ADMIN 허용을 검사한다. 필터 모킹으로 인증을 건너뛰지 않는다. 로컬 Gradle 슬롯을 사용하지 않고 PR CI의 실행·skip 증거를 따르며 운영/화면 완료를 뜻하지 않는다.
