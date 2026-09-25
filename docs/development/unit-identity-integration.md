# 부대·정체성 원장 통합

이 문서는 S6 원장을 현재 카드 계약에 읽어 들이는 경계를 기록한다.
원장은 `data/curated/han/named-unit-traditions.json`과
`data/curated/han/identity-presets.json`이다. 아직 공용 입력 원장·턴
처리·시나리오 적재와 연결되지 않았다.

`NamedUnitTraditions.parse(payload)`는 부대 사료 행을 1층의
`CardHeader(kind=UNIT)`와 전용 `NamedUnitTradition`으로 읽는다. 원문의
편명·인용·등급은 `UnitSourceCitation`에 남긴다. `NamedUnitFormation.assess`
는 인물·지역 요구, 서버 단위 유일성, 현재 명망 여유를 순서대로 판정하는
순수 함수다. 호출자는 문자열 이름을 비교하는 대신 현재 월드에서 인물과
지역의 존재 여부를 해석해 `UnitFormationContext`에 넣어야 한다. 이것은
예약·실행 공통 판정 함수의 재료이며, 이 파일 자체는 입력 핸들러가 아니다.
MAP4 이후 적갑군·연노사는 원장과 파서에 `涪陵郡` 및
`requiredMapParentRegionId=PARENT-0150`을 함께 보존한다. 이 두 카드의
편성 판정은 `UnitFormationContext.eligibleMapParentRegionIds`에 그 ID가
있을 때만 지역 조건을 통과한다. 다른 지역 카드는 아직 호출자의 지역
존재 판정을 사용한다. `涪陵縣`이나 군 좌석의 진단 문자열을 같은 지역으로
간주하지 않는다.

`IdentityPresetSources.parse(payload)`는 사료 등급과 출처 배지를 읽는다.
출처가 없는 행의 배지는 반드시 `게임 용어`다. 정체성의 국가 상태,
조직망·전환·AI 가중치는 S6-8/S6-9 통합 범위로 남아 있다.
현재 시나리오 importer의 `ideology` → `nation.type_code`는 기존 이념
시드이며 프리셋 ID의 시드 계약이 아니다.

두 파서는 받은 JSON 문자열만 해석한다. 리소스 패키징과 클래스패스 로딩은
1층 C1 카드 모델 및 공용 C0 validator가 확정된 뒤 연결한다. 그 전에는
`logic/build.gradle.kts`, `ScenarioImporter`, `HotColdCatalog`, 입력 원장,
공용 웹 화면을 건드리지 않는다. 이는 다른 레인의 같은 파일 변경을 덮지
않기 위한 경계다.
