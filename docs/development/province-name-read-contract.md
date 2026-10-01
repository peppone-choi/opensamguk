# 구역 이름 읽기 계약 (K4-21)

## 구현과 승인 상태

ProvinceNamesReader의 실제 Spring bean과 ProvinceNamesController의 두 GET을 등록한다. source/cache/HTTP 합성 fixture를 준비했으며 현재 head의 원격 CI와 C9 원천 공개 검토, C1/C8 공개 경계 검토는 별도 관문이다. C1 소유 GameApiSecurityConfig는 이 PR이 수정하지 않는다. 명시 exact GET permitAll → 같은 경로 다른 method denyAll patch가 main에 들어오고 실제 chain fixture로 확인되기 전 draft를 유지한다. 기존 catch-all permitAll은 새 공개 API 승인 근거가 아니다. 운영 제공 완료 표시가 아니다.

## 현재 핀 취득과 immutable URL

- `GET /api/map/provinces/names`: query 없음. `{worldId,mapRelease,topologyRevision,topologyHash,sourceSha256,representationSha256,representationPath}` metadata. `Cache-Control: public, no-cache, must-revalidate`, 정확 metadata bytes SHA256 ETag.
- `GET /api/map/provinces/names/v1`: 필수 query 정확6개 `worldId/mapRelease/topologyRevision/topologyHash/sourceSha256/representationSha256`. `{worldId,mapRelease,topologyRevision,topologyHash,sourceSha256,names:[{provinceId,displayName}]}` 이름 본문. `Cache-Control: public, max-age=31536000, immutable`, 정확 본문 bytes SHA256 ETag.
- 두 경로 GET만 공개 계약. HEAD/OPTIONS/POST/PUT/PATCH/DELETE 등 나머지 method는 C1 exact matcher가 denyAll한다. wildcard `/api/map/**` 공개 확장 없음.
- missing/blank/duplicate/extra query는400/no-store. 요청 핀과 현재 핀 불일치는409/no-store. 지원 세계/자료 부재404/no-store, 검증 실패503/no-store·빈 본문. 내부 예외/저장 경로/원문을 노출하지 않는다.

world/release/revision/topology/source뿐 아니라 정확 representation SHA까지 URL에 넣는다. 직렬화/schema가 달라지면 URL도 달라져 같은 immutable URL에 다른 bytes를 반환하지 않는다. mapRelease는 활성 bundle variant.artifactId이며 provinceId/displayName은 같은 han-tiles provinceRecords 원문이다. 배열 index/cityId/다른 release/추정 이름을 대신 쓰지 않는다.

소비자는 서버 진입·세계 reset·서버 전환 때 선택 서버의 API transport로 미버전 metadata를 재검증한 뒤 반환 representationPath만 사용한다. 기존 preview binding은 world/topology/baseTilesSHA를 제공하지만 mapRelease/representationSHA가 없으므로 preview만으로 URL을 만들 수 없다. preview에서 알고 있는 world/topology/source를 metadata와 대조하고 불일치 때 다시 현재 binding을 취득한다. 세계/서버 정체성이 바뀌면 이전 URL과 이름 선택을 폐기한다. 브라우저는 immutable 캐시를 네트워크 없이 반환할 수 있으므로 서버의 네트워크 검증만으로 소비자 reset 처리를 대체할 수 없다. frontend 구현은 이 PR 범위 밖이다.

## read 검증과 cache

매 네트워크 요청 동일 REPEATABLE_READ read transaction에서 process world → 지원 map → 저장 spatial pin nonempty → ActiveWorldArtifactResolver 현재 세계/완전 roster/정본 선택 → 선택 world 일치 → 저장 revision/hash 전부 일치 → cache → 요청 fulltuple → If-None-Match 순서다. pin 부재 때 cold resolver의 구1447 fallback을 호출하지 않는다. cache hit과 strong/weak/list/* 조건에도 이 검증을 생략하지 않는다. reset/릴리스 변경 뒤 이전 URL은304가 될 수 없다.

메모리 cache key는 worldId/mapRelease/topologyRevision/topologyHash/sourceSHA, 최대4개 LRU. cache miss에서 검증된 bundle bytes SHA를 대조하고 streaming parser로 provinceRecords의 id/displayName만 읽는다. complete stable land ID집합·중복 JSON field/ID·문자열·빈 이름·후행JSON을 검사하고 ID정렬 후 직렬화한다. hit은 terrain bytes복사/해시/JSON재파싱0. 본문 방어적복사, 이름 목록 불변. 세계 선택/핀 검증과 WorldArtifactsResolver cold 로드/DB조회는 별도 비용이다.

## 공개 범위와 중복

공개 allowlist는 정적인 지명과 검증된 지문뿐이다. owner/fog/장수·부대/개인 위치/신원/geometry/raw 원장을 내보내지 않는다. invalid/missing Bearer는 현 정책대로 익명이며 같은 공개 DTO. query로 actor/archive/world를 선택하지 않는다.

기존 terrain=전체 source JSON, provinces=PNG, ju=parent별州번호index, preview=runtime city이름+동적소유다. authenticated counties income/commandery 읽기를 복제하지 않는다. 첫 범위province만; 郡/縣 확장은 기존 API/preview/M2 places와 중복 검토 후 별도다.

## 검증과 운영 경계

작은 합성 cache5건, reader4건, actual GameApiSecurityConfig/JwtVerifyFilter + controller HTTP5건, 실제reader/ActiveWorldArtifactResolver/controller/chain 결합4건을 준비한다. cache identity/방어복사/LRU/손상, 저장핀부재·drift·crossworld, anonymous/invalidBearer allowlist·조건헤더·reset·query오염·unrelated 보호거부(현재 chain403)을 다룬다. 실제결합4건은DB read repository와 immutable artifact catalog만작은합성fixture로대체한다. anonymous/invalid/signedUSER/ADMIN의동일본문·ETag, exact공개field와private sentinel제거, cachehit에서도저장핀2중검증/actualresolver호출, 빈핀의cold선택차단·drift503, actualworldreset409·missingworld404, query오염시world무조회를검증한다. 실PG/transaction격리/운영검증증거는아니다. C1 patch main 반영 후 같은 실제chain에 nonGET 거부·reader무호출을 보강한다. 시험 준비를 실행PASS로 표시하지 않는다.

새 로컬 JVM/대형지도 파싱·bake 없이 현재 head 원격 jvm-core와 game-engine 결과/XML skip0을 확인한다. CI 실행 중 repeated sync/취소0. 운영DB·VM/config·image승격0. cf7 actual web → PNG API 별도 승인 순서를 유지한다. 신규 이름 API의 운영 반영은 별도 승인 대상이다.

## main 보안 경계와 실제 reader 결합

#1115의 exact 두 경로 GET 허용·그 외 method 거부가 main에 병합된 뒤 같은 main을 반영했다. 실제 reader/resolver/controller/JWT chain 시험에서 HEAD·OPTIONS·POST·PUT·PATCH·DELETE를 두 실제 경로(메타데이터와 전체 pin query가 있는 불변 URL)에 요청한다. 익명·잘못된 Bearer·refresh JWT·access USER·access ADMIN 모두 정확한 403이며, world/city/pin/catalog 및 artifact bytes 접근 0을 요구한다. GET은 이 다섯 신원에 같은 공개 DTO·ETag를 준다. 저장소 read와 immutable catalog는 fixture이며 실제 PostgreSQL 트랜잭션 실행 증거는 아니다.

현재 main의 보안 설정은 인증 필수 `/api/events`의 익명 거절을 403으로 반환한다. 공통 401 entrypoint가 main에 들어오면 해당 인증 거절 시험은 그 정확한 401/오류 JSON에 맞춘다. 인증된 USER/ADMIN의 비GET 권한 거절 403 계약은 유지한다.
