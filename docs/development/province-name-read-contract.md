# 구역 이름 읽기 준비 계약 (K4-21)

## 구현 상태

ProvinceNamesDto/ProvinceNamesCache와 작은 합성 fixture만 준비했다. HTTP controller·Spring bean·익명 security matcher는 아직 등록하지 않았고 운영·화면 제공 기능이 아니다. C9 원천/릴리스 검토와 공개 DTO·명시 GET 보안 규칙 검토 후 연결한다.

## 제안 경로와 cache

`GET /api/map/provinces/names` → `{worldId,mapRelease,topologyRevision,topologyHash,sourceSha256,names:[{provinceId,displayName}]}`. mapRelease는 활성 ResolvedWorldArtifacts.variant.artifactId, stable provinceId/displayName은 같은 bundle의 han-tiles provinceRecords 원문이다. 숫자 배열 인덱스·cityId나 다른 release 이름을 대체로 쓰지 않는다. 지도 정본의 이름 자료를 읽으며 새 번역·역사 이름을 만들지 않는다.

미버전 경로는 `Cache-Control: public, no-cache, must-revalidate`, 강한 ETag는 정확 응답 JSON bytes의 SHA256을 제안한다. 매 요청 ActiveWorldArtifactResolver가 current world·완전 city roster·저장 spatial pin을 검증한 **다음** 캐시/조건 헤더를 검사한다. reset/release/source가 바뀌면 old ETag를304로 처리하지 않는다. K4-21 연결 시 저장 spatial pin 부재는 no-store 실패 응답으로 거부한다. 기존 WorldArtifactsResolver는 빈 pin의 구1447 release 선택을 허용하므로 그것만으로 strict pin을 증명하지 않는다. 같은 transaction의 WorldArtifactIdentityReadRepository 결과에 nonempty·revision/hash 일치를 추가 검증한 뒤 cache에 전달해야 한다. invalid/missing identity는 no-store 실패 응답이고 이름 추정/fallback은 없다. immutable은 사용하지 않는다. 이 문서는 공개 접근 승인 자체가 아니다.

cache key는 worldId/mapRelease/topologyRevision/topologyHash/sourceSHA, 최대4개 LRU. miss에서 검증된 bundle bytes를 한 번 가져와 SHA대조 후 streaming parser로 id/displayName만 추출한다. 완전한 stable land ID집합·중복·문자열·빈 이름·후행JSON을 검사하고 정렬·직렬화한다. hit에서 source bytes복사/해시/JSON재파싱이 없다. 반환byte는 방어적복사, 이름목록은 불변. 캐시는 세계 선택/핀 검증을 대체하지 않는다. 기존 WorldArtifactsResolver cold bundle 로드와 DB roster/pin 조회 비용은 별도다.

## 공개 범위와 기존 API

DTO allowlist는 static 이름과 검증된 map 지문뿐이다. live owner/fog/부대/장수·사용자 신원/개인 위치/geometry/raw 원장을 싣지 않는다. anyRequest().permitAll()로 익명 승인 여부를 판단하지 않는다. 명시 GET matcher와 실제 SecurityFilterChain 테스트는 C1 소유와 합의해 연결한다. 익명 preview가 주는 동적 소유 payload를 그대로 재사용하지 않는다.

기존 terrain=전체 source JSON, provinces=PNG, ju=parent별州번호index, preview=runtime city이름+동적소유다. 권한있는 counties income/commandery 읽기를 새 목록으로 복제하지 않는다. 첫범위province만이고 郡/縣 확장은 기존 API/preview·M2 places와 중복 검토 후 별도다.

## 검증 경로

로컬 JVM·대형지도 파싱/전수bake 금지 상황에서 작은 합성 fixture5건을 준비한다. 필드allowlist·exactbyte ETag·cachehit byte읽기1회/외부변경불가·world/release/source 변경tag·결손/손상/중복/핀불일치 거부·LRUeviction을 확인한다. Kotlin 시험을 실행한 것으로 표시하지 않는다.

후속 draft PR의 현재 head 원격 CI `jvm-core`로 컴파일/reader tests, 실제 XML skip0 gate를 확인한다. HTTP 연결 이후에는 actual anonymous/JWT chain·GET외메서드·strong/weak/list/* 조건헤더·reset후old ETag304 거부·failclosed no-store 시험을 더한다. CI 대기 중 repeated sync/취소를 하지 않는다. 운영DB·VM/config·image승격0. cf7 actualweb→PNG API 별도 승인 순서와 신규API의 별도운영반영 경계를 유지한다.
