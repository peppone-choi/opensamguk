# 정체성 첫 세 프리셋의 순수 모델

S6-8a는 `FactionIdentityProfile`, `NetworkPresence`, `FactionIdentityState`와
유가·태평도·도적 시작 템플릿만 정의한다. 템플릿 원장은
`data/curated/han/identity-core-templates.json`이다. 각 `id`는 S6-9a의
`identity-presets.json` 출처 행을 가리킨다. 템플릿의 단계·범주·능력 ID는
역사 사실이 아니라 `designDecision`의 게임 결정이다. 출처 등급과 배지는
S6-9a 원장 그대로 보존한다.

| 프리셋 | 시작 단계 | 조직망 자리 | 검증할 성질 |
| --- | --- | --- | --- |
| 유가 | `TERRITORIAL_REGIME` | 학교 | 관료·추천 전통 |
| 태평도 | `MOVEMENT` | 方 | 타국 도시의 은밀 조직망 |
| 도적 | `CONFEDERATION` | 산채 | 도시 없는 거점의 존속 |

`seedProfile`은 정통성 청중 여섯 값과 콘텐츠 프로필을 호출자로부터
명시적으로 받는다. `seedState`는 호출자가 제공한 조직망의 종류·장소 종류가
템플릿의 `startingNetworks`와 일치하는지 확인한다. 시나리오 장소 ID,
정통성 수치, 조직망 수치의 기본값을 임의로 만들지 않는다.
`FactionIdentityState.adopt`는 이미 판정된 전환을
적용하는 순수 함수다. 기존 조직망을 유지하고 대체된 통치형태·전통을
`institutionalTensions`에 남긴다. 이 함수는 사령권·비용·지지·시간 경과를
판정하지 않으며, 콘텐츠 프로필별 자동 활성화도 하지 않는다.

템플릿의 `facilityCapabilities`는 S6-6의 일반 시설 자리와 나중에 연결할
불활성 ID다. S6-6b의 일반 공사 완료나 S6-8b/9b의 프리셋 통합 완료를
이 파일로 주장하지 않는다. 저장·시드·입력·AI·화면·월 경계·시설 효과는
후속 b 슬라이스에 남겨 둔다. 정체성 명칭을 기존 `nation.type_code`와
같은 저장 키로 취급하지 않는다.

정적 검증: `python3 -m tools.content.validate_identity_core_templates` 및
`python3 -m unittest tools.content.tests.test_validate_identity_core_templates -v`.
Kotlin 대상 테스트는 단독 Gradle 슬롯이 재개된 뒤 실행한다.
