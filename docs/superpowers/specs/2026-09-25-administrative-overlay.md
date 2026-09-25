# 시나리오별 행정 오버레이 설계 (2026-09-25)

상태: 설계 결정, 구현 전. 제품 코드 정리 동결이 풀리고 [#905](https://github.com/peppone-choi/opensamguk/pull/905)가 머지된 뒤 별도 PR로 구현한다. 결정: [ADR-LITE-067](../../../.ai/decisions.md).

## 현행 경계

- `han-world-v3.json`의 城 1,447개 중 許縣 id 130은 `level=10`, `name=허`, `meta.nameCh=许县`, `meta.displayName=영천군 허현`, `meta.isSeat=false`, `provinceId=643`, `spatialProvinceId=83011`이다. 陽翟 id 122는 `level=8`, `meta.isSeat=true`, `provinceId=575`다. 두 城의 `meta.junCh=潁川郡`이다. `han-tiles.json`의 省은 1,653개이며 인덱스 643의 안정 id·관할은 `83011`이다. 현재 값은 역사 규모의 근거가 아니다.
- `HanWorldArtifactsResolver.resolve(completeCityIds, pins)`는 城 id 명부로 지도 판을 고르고 공간 테이블의 `topologyRevision/hash`를 검사한다. 城 id 증감이나 동결 번들 제자리 덮어쓰기는 핀 사슬을 깨뜨린다. 기하·省 그래프 변경은 새 지도 판으로 낸다.
- `HwihaCountyGeographyJson.parse`는 고정 런타임 지도 `meta.junCh`와 `han-tiles provinceRecords[city.provinceId].jurisdictionId`를 읽는다. `load` 캐시는 현재 지도 판만 키로 삼으므로 월드별 오버레이 적용 후 그대로 쓸 수 없다. `provinceId`는 배열 인덱스이며 delta 참조 키가 아니다.
- [2026-07-18 시나리오 스펙](./2026-07-18-scenario-system.md)의 원천→정제→큐레이션 매니페스트→생성 구조를 따른다. 당시 `ScenarioImporter` 무변경 목표는 그 기능의 경계였다. 현재 `ScenarioJson.decode`에는 행정 delta 참조가 없다. `tools/map/build_administrative_place_overlay.py`의 CHGIS 장소 대응은 이 월드별 delta와 별개다.

## delta 데이터 계약

제안 경로: `data/curated/han/administrative-deltas/<id>.json`. 필수 필드: `schemaVersion=1`, 고정 `id`(판 접미사 포함), `baseArtifactId`, 순서 있는 `changes[]`, `evidence[]`, `designValues[]`. 시나리오 저작 매니페스트의 `administrativeDeltaIds`를 생성기가 시나리오 JSON 헤더에 순서대로 싣는다. 이벤트도 같은 id를 참조한다. 빈 출처, 미등록 id, 다른 지도 판, 중복 id는 거절한다. 역사 근거 미확인은 `UNKNOWN`, 수치 큐레이션은 `GAME_DESIGN`으로 표시한다.

| `type` | 필수 입력과 효과 | 금지·검사 |
| --- | --- | --- |
| `rename` | 대상 `cityId` 또는 `commanderyId` 중 정확히 하나, `expected`, `name`, `nameCh`, `displayName`, `aliases`; 시점 이름·검색 별칭 교체 | 城·郡 id와 안정 검색 키·`administrativeUnitId`·`physicalPlaceRef` 불변. 郡 개명은 郡 id를 유지하고 소속 城 표시를 재파생. |
| `scale` | `cityId`, `expected`, `level`, `metrics`; `metrics`는 인구·농업·상업·치안·수비·성벽 여섯 키 각각에 `{initial,max,expansionLimit}`을 둔다 | 지표마다 `0 ≤ initial ≤ max ≤ expansionLimit`, level 범위 검사. 현재 지도에 별도 증축 한도가 없으면 각 지표의 `expected.metrics.<지표>.expansionLimit=null`을 명시한다. 이벤트가 이미 변한 실제 인구를 시작값으로 재설정할 수 없다. |
| `commanderySeat` | `commanderyId`, `fromCityId`, `toCityId`; 유일한 郡 치소 변경 | 둘 다 같은 郡의 행정 縣治 城, 현 치소가 `fromCityId`. `meta.seat/isSeat`와 표시를 결과에서 함께 파생. 천자 소재와 郡 치소는 별개. |
| `reassignProvince` | 안정 `provinceRecordId`, `fromJurisdictionId`, `toJurisdictionId`; **기존 省 전체**의 관할 재배정 | 省 id·셀·간선·지형·점유·`parentRegionId` 불변. 실재 관할만 대상. 城 앵커의 자기 縣 관할 및 郡 경계 유지. 省 분할 불가. |

`nameCh`의 현행 지도값은 간체이므로 사료 원문 번체와 런타임 표기를 분리한다. 규모 숫자는 사료에서 유도하지 않고 `designValues`에 산식·단위·담당자를 둔다. id가 한 번 라이브 월드에 쓰이면 내용 수정·삭제를 금한다. 고칠 때 새 id 판을 만들고 구판을 보관한다.

郡의 면적·인구·생산 규모는 소속 縣과 그 관할 省의 결과에서 **파생**한다. 현재 郡에 독립 `level`/`max` 자원이 없으므로 v1에서 별도 郡 수치를 만들지 않는다. 郡 이름과 치소는 명시 delta로 바뀐다. 郡 소속 자체의 재편은 후속 범위다.

### 적용 순서와 충돌

1. 기본 지도 판·위상 핀을 먼저 확정한다. 매니페스트의 **기재 순서**, 이어서 각 delta의 `changes[]` 순서대로 적용한다. 연도로 암묵 정렬하지 않는다.
2. 변경하는 **모든 필드**의 이전값을 `expected`에 선언하고 검사한다. 별칭 목록도 배열 전체를 비교하며 필드 부재는 `null`로 구분한다. 동일 필드 후속 변경은 이전 결과를 `expected`로 선언한 명시적 체인만 허용한다. 같은 묶음 안 같은 대상·속성 중복, last-write-wins, 누락 참조는 거절한다.
3. 한 delta 전체를 스키마→참조→행정 관계→형상→위상 순서로 검사하고 원자 적용한다. 실패하면 이전 투영을 유지한다. 적용기는 벽시계·RNG를 읽지 않는 순수 함수 `applyAdministrativeDelta(baseProjection, delta)` 하나다. 시드와 이벤트 재생이 이를 공유한다.

## 월드 저장·시드·부팅·API

`world_state.meta.administrativeOverlay` 제안 형식(아직 구현되지 않음):

```json
{
  "schemaVersion": 1,
  "baseArtifactId": "han-world-v3-1447",
  "baseTopologyRevision": "<resolved revision>",
  "baseTopologyHash": "<resolved hash>",
  "applied": [
    {"id": "xu-196-capital-v1", "sha256": "<64 hex>", "source": "scenario", "sequence": 0}
  ],
  "projectionSha256": "<64 hex>"
}
```

JSON은 UTF-8, 키 순서 고정, 정수 정규화, 공백 제거로 canonical bytes를 만든 뒤 SHA-256을 계산한다. `applied` 배열 순서, id/내용 해시, 기본 판/위상 핀, 스키마 버전, 최종 투영 해시를 검증한다. JSON 필드 순서만 바뀌면 같은 해시다. 같은 id의 다른 내용, 삭제된 파일, 미지원 버전, 투영 해시 불일치는 부팅·API가 모두 거절한다. 이벤트 감사에는 적용 턴·요청 id를 별도 append-only 기록한다. 기존 월드의 필드 누락/마이그레이션은 기존 세계 형식 가드와 함께 구현 PR에서 명시하고, 조용한 기본값은 두지 않는다.

- **시드:** `ScenarioJson.decode`/`Scenario`가 참조를 읽고 `SeedBootstrap.ensureSeeded`가 `HanWorldArtifactsResolver.resolve`로 기본 판을 고른다. `ScenarioImporter.importAdmitted` 시작에서 공통 적용기를 실행해 `insertCities`의 이름·등급·시작/상한과 `insertWorldState` 핀을 같은 트랜잭션으로 기록한다. 현재 `insertCities`는 점령 城 시작값 일부를 `max × 70%`로 치환한다. 제안 우선순위는 명시한 delta `initial`이 우선, 미변경 지표에만 종전 규칙 적용이다. 사용자 확정 전에는 구현하지 않는다.
- **엔진 부팅:** `WorldSnapshotLoader.buildSnapshot`가 월드 id·도시 명부·공간 핀을 읽은 후 기본 번들을 선택하고 저장된 `applied`를 검증·재적용한다. `HwihaCountyGeographyJson.parse`는 투영된 행정 정보를 소비하게 바꾸고 `load` 캐시는 `(baseArtifactId, projectionSha256)` 또는 월드 키로 격리한다. DB 城 수치와 투영의 정적 상한/표시값 일치도 검사한다.
- **API:** `ActiveWorldArtifactResolver`가 같은 핀·해시 검증기를 사용한다. `WorldMapController`, `JuMapController` 등 지도 응답, `HwihaVisionReader`의 郡 묶음, 縣 검색·내정 읽기 경로는 한 월드 투영을 공유한다. 원본 `commanderyIndex`나 런타임 지도 `meta`만 읽어 옛 치소·이름을 내보내면 계약 실패. 캐시는 월드 id+투영 해시로 격리하고 이벤트 커밋 후 폐기한다. 외부 응답의 城/省 id는 불변이다.
- **이벤트 2단계:** `applyAdministrativeDelta` 액션은 등록 delta id와 예상 현행 투영 해시를 받는다. 엔진이 권한·중복 요청·현행 해시를 확인하고 턴 경계의 단일 쓰기 경로(`ChangeRecorder`→`JdbcFlushExecutor`)로 핀·영향받은 城 상태·감사 기록을 원자 갱신한다. API는 직접 저장하지 않는다. 1단계는 시드만, 2단계가 액션·재생·CAS를 연다.

## 검증 게이트와 적색 프로브

| 게이트 | 실패를 증명할 프로브 |
| --- | --- |
| id·핀·동결 | 城 id 추가/삭제, 省 안정 id 변조, 이미 사용된 delta의 내용 한 바이트 변경·파일 삭제 → 시드/부팅/API 거절. 구판은 재핀하지 않고 보존. |
| 위상 불변 | 省 id 집합, 셀 소유·지형·물길, typed 이동·보급 간선/비용, `topologyRevision/hash` 적용 전후 동일. 간선 하나 변경·省 분할 주입 시 실패. 관할별 보급 집계는 재계산해도 실제 통과 그래프는 불변. |
| 행정 완결성 | 플레이 가능 省마다 관할 하나, 郡 치소 하나·같은 郡, 城 앵커의 제 省은 자기 縣, 고아/빈 관할 금지. 앵커 省을 다른 縣에 배정하면 실패. |
| 구역 모양 | 투영 후 관할별 셀 합집합의 연결성·구멍·구역 안 구역·좁은 목/돌출부 검사. `tools/map/province_quality.py`, `tools/map/administrative_spatial_hierarchy.py`의 정책을 재사용·대조. 분리/포위 省 주입 시 실패. |
| 결정론 | 같은 기본 판+delta 순서를 시드와 이벤트 재생으로 구성할 때 투영·DB 城 값·해시 같음. 이벤트의 가변 인구는 같은 사전 상태로 비교. 순서 교환·196을 건너뛴 221 단독 적용·중복 id·낡은 CAS 해시 실패. |
| 소비자 | 190/196/220/221 표시·옛 이름 검색, 엔진/API 동일 월드 해시, 월드 간 캐시 오염 0. 기본 지도만 읽는 경로 주입 시 계약 실패. |

구역 형상 임계값은 #905 뒤의 실제 판 검사와 일치시킨다. 적색 프로브가 통과하기 전 재배정 예시를 출시 데이터로 승격하지 않는다.

## 사례 부록: 許縣·許都·許昌과 郡 치소

사료 원문은 로컬 `references/sources/shiliao/corpus`에서 확인했다. 正史와 후대 지리서 등급을 분리한다. CHGIS 형상은 사료 문장만으로 증명할 수 없다.

| 시점 | id 130 | 사료·판정 |
| --- | --- | --- |
| 190 | 정식 `許縣`, 표시 허현 | 『後漢書』 卷110 郡國志 「許」. 현재 지도 `nameCh=许县`. 규모 숫자는 `UNKNOWN`. |
| 196 | 정식 `許縣` 유지, 천자 소재 별칭 `許都` | 『後漢書』 卷9 獻帝紀 建安元年 「庚申，遷都許。」 `許都`는 공식 縣 개명이 아니다. 규모 상승·표시 괄호는 `GAME_DESIGN`. |
| 220 | 정식 `許縣` 유지 | 사용자 결정: 사료 기준 개명 221년. |
| 221 | 정식 `許昌縣`, 표시 허창현 | 『三國志』 卷2 魏書·文帝紀 黃初二年 「改許縣爲許昌縣。」 『後漢書』 卷110 교감 「改許縣為許昌縣在魏文帝黃初二年，非獻帝徙都時改名也。」 |

계약 예시(아직 유효 파서 입력 아님, 규모 수치는 미결정):

```json
{"id":"xu-196-capital-v1","baseArtifactId":"han-world-v3-1447","evidence":[{"book":"後漢書","volume":"卷9 獻帝紀","quote":"庚申，遷都許。"}],"designValues":["level/initial/max/expansionLimit: GAME_DESIGN, 미결정"],"changes":[
  {"type":"rename","cityId":130,"expected":{"name":"허","nameCh":"许县","displayName":"영천군 허현","aliases":null},"name":"허","nameCh":"许县","displayName":"영천군 허현 (허도)","aliases":["許","許縣","許都","허","허현","허도"]},
  {"type":"scale","cityId":130,"expected":{"level":10,"metrics":{"population":{"initial":50000,"max":146900,"expansionLimit":null},"agriculture":{"initial":1000,"max":2900,"expansionLimit":null},"commerce":{"initial":1000,"max":2975,"expansionLimit":null},"security":{"initial":1000,"max":2000,"expansionLimit":null},"defence":{"initial":1500,"max":3000,"expansionLimit":null},"wall":{"initial":1500,"max":2950,"expansionLimit":null}}},"level":"<미결정>","metrics":{"population":{"initial":"<미결정>","max":"<미결정>","expansionLimit":"<미결정>"},"agriculture":{"initial":"<미결정>","max":"<미결정>","expansionLimit":"<미결정>"},"commerce":{"initial":"<미결정>","max":"<미결정>","expansionLimit":"<미결정>"},"security":{"initial":"<미결정>","max":"<미결정>","expansionLimit":"<미결정>"},"defence":{"initial":"<미결정>","max":"<미결정>","expansionLimit":"<미결정>"},"wall":{"initial":"<미결정>","max":"<미결정>","expansionLimit":"<미결정>"}}}
]}
{"id":"xu-221-rename-v1","baseArtifactId":"han-world-v3-1447","evidence":[{"book":"三國志","volume":"卷2 魏書·文帝紀","quote":"改許縣爲許昌縣。"}],"designValues":[],"changes":[
  {"type":"rename","cityId":130,"expected":{"name":"허","nameCh":"许县","displayName":"영천군 허현 (허도)","aliases":["許","許縣","許都","허","허현","허도"]},"name":"허창","nameCh":"许昌县","displayName":"영천군 허창현","aliases":["許","許縣","許都","許昌","許昌縣","허현","허창현"]}
]}
```

190 시작은 delta 없음, 196·220 시작은 196 묶음, 221 이후는 196→221 묶음 순서다. `displayName`의 `허도` 괄호는 UI 설계값이다. 역사 연도만으로 이벤트를 자동 발동할지는 미결정이다.

**郡 치소 이동:** 현행 번들은 潁川郡의 陽翟 id 122를 치소로 둔다. 『晉書』 卷14 地理志 晉 시점의 「河南郡」 목록에는 「陽翟」가, 「潁川郡」 목록에는 「許昌」이 있다. 후대 『讀史方輿紀要』 卷47은 「晉屬河南郡徙潁川郡治許昌」이라고 명시한다. 晉 시점 `commanderySeat {commanderyId:"潁川郡",fromCityId:122,toCityId:130}`은 역사 후보지만 동시에 陽翟의 郡 소속도 바뀌어야 한다. v1은 郡 소속 재편을 지원하지 않으므로 **이 역사 사례는 v1 적용 불가**로 명시한다. 동일 郡 내 치소 이동은 가상 픽스처로 검증한다. 196년 천도만으로 郡 치소가 許로 옮겨졌다는 근거는 로컬 코퍼스에서 `UNKNOWN`이다.

v1 실행 예시는 **가상 IF 시나리오 전용** `{"type":"commanderySeat","commanderyId":"潁川郡","fromCityId":122,"toCityId":130}`이다. 두 城이 현재 같은 郡인 상태에서 단일 치소 검사를 통과하는지 시험하며, 196년의 역사 사실이라고 주장하지 않는다.

**省 재배정(기술 프로브, 역사 대응 UNKNOWN):** 현행 城 앵커 없는 省 `SUB-200281-c658d67f2ab8`(인덱스 1082, 현 관할 `200281`)은 관할 `45412`의 省(인덱스 1112)과 35셀 경계를 공유한다. `reassignProvince {provinceRecordId:"SUB-200281-c658d67f2ab8",fromJurisdictionId:"200281",toJurisdictionId:"45412"}`는 형상 검사 전의 **후보 입력**이다. 전체 연결성·구멍·좁은 목 합격과 미세 경계 사료는 `UNKNOWN`. 『晉書』의 陽翟 郡 소속 변경은 縣 이전 근거일 뿐 이 省 조각 재배정의 근거가 아니다.

## 이행 순서

1. 동결 해제·#905 머지 후 지도 판, 형상 정책, ADR-LITE-066 이름 대응표를 다시 확인한다. 이 문서 PR은 선행 가능하다.
2. PR A: delta 스키마·파서·canonical 해시·불변 파일 검사와 시나리오 매니페스트/`ScenarioJson` 참조. 규모 숫자는 사용자 결정 후 입력한다.
3. PR B: 단일 적용기, 시드·월드 핀·기존 월드 형식 가드, 결정론/동결 적색 프로브.
4. PR C: 엔진/API 동일 투영, 월드별 캐시, 지도·치소·검색·관할 소비자와 형상/위상 게이트.
5. PR D: 이벤트 `applyAdministrativeDelta`, 턴 경계 원자 저장·재생·CAS·감사. 운영 변경·배포는 별도 승인 대상.

## 사용자 결정 필요

1. `level`, 인구·농업·상업·치안·수비·성벽과 증축 한도의 수치 기준·단위·큐레이션 담당자. 출처에 숫자가 없으면 `GAME_DESIGN`.
2. 점령 城의 `max × 70%` 초기화와 명시 `initial`의 우선순위, 이벤트에서 상한이 현재값 아래로 내려갈 때 보존·clamp·거절 정책.
3. 역사 연도 고정, 게임 상태 조건, 둘의 조합 중 이벤트 발동 기준. IF 시나리오에서는 196 천도·221 개명이 자동으로 보장되지 않는다.
4. 천자 소재 별칭 `許都`의 UI 표시 방식과 정식 縣명 검색 결과 우선순위.
5. 郡 소속 변경과 신설·폐지 縣의 후속 범위. 城 id 증감은 지도 판 선택·핀 사슬과 기존 월드 이주 비용 때문에 v1에서 제외한다.

예시의 `<미결정>`은 파서 허용값이 아니다. 구현 전 정수와 근거를 채워야 한다.
