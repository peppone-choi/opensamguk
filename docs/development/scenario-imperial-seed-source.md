# 명시적 황실 seed 선언·실제 ID 반환부

현재 황실 모델은 활성 장수의 실제 general ID와 `world_state.meta.imperialWorld` schema1을 사용한다. `imperial-succession.md`의 S6-2a·일관성 규칙을 재사용한다. 이 내부 source는 시나리오를 채우거나 황실 인물을 만들지 않는다.

## C6 source

`ScenarioImperialSeedCodec.read(root)`는 기존 설계가 지정한 시나리오 `imperialWorld` 키를 읽는다. 키 부재는 null, explicit null·다른 타입·지원하지 않는 schema·누락/추가 필드는 예외다. 선언은 다음 정확 필드를 가진다. nullable 필드도 키가 있어야 하고, 모든 수는 Int로 읽으며 변환하거나 기본값을 채우지 않는다.

- root: `schemaVersion:1`, `houses:[]`, `allegiances:[]`.
- house: `code`, `name`, `status`, `holderName`, `designatedHeirName`, `dynasticCandidateNames`, `regentName`, `courtNationId`, `courtCityId`, `legitimacy`. status는 기존 ImperialLineStatus다. person은 ScenarioImperialHouse의 이름 참조이며 holder/heir/regent·court IDs는 nullable다.
- allegiance: `lineCode`, `nationId`, `relation`, `recognition`, `favor`. 기존 ImperialAllegiance 타입과 범위를 사용한다.

`ScenarioImperialSeedMaterializer.materialize(seed, activeGeneralIdsByName, seededNationIds, seededCityIds)`는 기존 resolver로 실제 생성 ID를 연결하고 조정·관계 참조가 target world의 실제 생성 행에 속하는지 supplied universe에서 검증한다. 누락/중복 인물·같은 ID 별칭·미존재 참조·기존 황통 불변식 위반은 예외다. 성공하면 기존 ImperialWorldCodec의 schema1 payload **값**을 반환한다. null seed는 payload null이며 key를 쓰지 않는다. 명시적으로 유효한 empty seed는 empty payload로 보존한다. 원본 컨테이너를 수정하지 않고 payload는 입력과 분리된다. 새로운 WorldState entity나 두 번째 저장 정본은 만들지 않는다.

## C4 정확 consumer seam

이 PR에는 아래 호출이 아직 없다. C4 소유 파일을 수정하지 않는다. 선언 shape는 내부 decoder의 기술 계약이며 3190 제품 데이터/사료 승인이나 consumer ACK를 대신하지 않는다.

1. `ScenarioJson.loadScenario`의 MetaJson root에서 `ScenarioImperialSeedCodec.read(root)`를 호출하고 typed ScenarioImperialSeed?를 Scenario에 보관한다. imperialGenerals NPC7 표지와 별개다.
2. `ScenarioImporter.importAdmitted`의 `buildGenerals(startYear)` 결과 `(src.name,id)`와 **동일 target world에서 실제 삽입/검증한** nation/city IDs를 materializer에 전달한다. RTK officer 번호나 수동1001+N은 전달하지 않는다. 현재 대상 universe라는 사실은 호출자가 보장해야 하며 이 순수 함수 자체가 DB world를 조회하지 않는다.
3. non-null payload만 같은 fresh seed transaction의 `world_state.meta[ImperialWorldCodec.META_KEY]`에 기록한다. 현재 insertWorldState는 buildGenerals보다 먼저 실행되므로 동일 transaction에서 해당 key를 연결해야 한다. 기존 meta의 다른 키를 보존하고 flush·평행 KV를 다시 만들지 않는다.
4. `ScenarioSeedRunner`는 선택된 실제 원천/옵션으로 importer를 호출한다. decoder·materializer 예외를 공개 READY로 바꾸지 않는다. 새 world seed→cold load→presence와 첫 실제 turn/flush/restart 증거는 C4 통합 시험에 남긴다.

## 아직 완료되지 않은 것

3190 원천에는 유굉/유변/유협 행과 황실 선언이 없다. 이 PR은 기존1,000명/활동384 계약을 늘리거나 능력·생몰·등장·위치·초기 정통성·후계/섭정/관계를 만들지 않는다. 값은 명시적 승인 원천에서 와야 한다. 별도 인물 모델 선택은 현재 general-ID 계약을 바꾸는 후속 제품 변경이며 decoder의 기술 선행으로 묶지 않는다.

단위 시험은 실제 MetaJson·resolver·ImperialWorldCodec의 선언/참조/round-trip/입력 분리를 확인한다. 실제 DB seed/cold load·공간 artifact·현재 HTTP·첫 tick·PUBLIC-IMPERIAL-READY·운영 초기화 proof가 아니다. HTTP/public audience·writer·C4 consumer·선택 원천 JSON·운영 변경은 없다.
