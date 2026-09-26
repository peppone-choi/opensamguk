# 실명 부대와 정체성 사료 대조

`data/curated/han/named-unit-traditions.json`은 S6-7a의 부대 카드 후보 10개,
`data/curated/han/identity-presets.json`은 S6-9a의 프리셋 15개에 관한 사료
원장이다. 이 파일만으로 모집·정체성 선택이 활성화되지는 않는다. 원문의
책·권·편·짧은 원문은 각 행의 `sources[]`에 있고, `grade`가 다른 주장은
같은 인용으로 합치지 않는다. 출처가 없는 프리셋은 `GAME_TERM`과 화면 배지
`게임 용어`를 함께 기록한다. 정사와 연의가 충돌하면 연의를 기본으로 고르되,
각 인용과 등급은 분리한다. 이번 원장의 역사 행은 모두 `PRIMARY`다.

## 카드 모델 대응

| 원장 필드 | 1층 C1 카드 계약 | 의미 |
| --- | --- | --- |
| `id`, `name` | `CardHeader.id`, `name` | 새 부대 카드 식별자. `units.json`의 숫자 ID를 런타임 키로 재사용하지 않는다. |
| `availability` | `CardAvailability` | `UNIQUE`는 서버 한 장, `COMMON`은 반복 가능이라는 게임 규칙이다. |
| `sources[]` | `CardProvenance.Citation` | 책·권 배지는 옮길 수 있다. 편·원문·등급은 원장에 보존해야 한다. |
| `renownCost.value` | `CardHeader.renownCost`, `RetinueNamedUnit.renownCost` | 숫자는 구현 에이전트가 정한 확정 게임 값이다. |
| `costColors` | `CardHeader.costColors` | `MONEY`·`GRAIN`·`IRON`·`TIMBER`·`HORSES` 중 고른 비용 색이다. |
| `bondRecruitmentKinds` | `UnitBondKind` | `VOLUNTEER`·`CAPTIVE`는 게임 내 모집 경로다. 해당 인물이 실제로 자원했다는 사료 주장이 아니다. |
| `requires.generalName` | 후속 카드 편성 조건 | 시나리오 인물 ID는 1층 C1·역사 시나리오의 확정 ID와 후속 PR에서 연결한다. |
| `requires.regionName`, `mapParentRegionId` | 후속 카드 편성 조건 | 지역명은 사료 문맥이고 지도 ID는 게임 지도에 대한 연결 결정이다. 이름만으로 군·현을 판정하지 않는다. |
| `attestedPeriod` | 후속 편성 가용 시기 | 원문 문맥이 허용하는 넓은 시기만 쓴다. `UNKNOWN`은 임의 연도로 보충하지 않는다. |

아래 번호는 옛 `units.json`의 han 후보를 대조한 조사 키일 뿐 로더나
게임플레이 의존이 아니다. `units.json`의 인용을 그대로 복사하지 않고
`references/sources/shiliao` 색인에서 원문과 권을 다시 확인했다.

| 새 카드 | 옛 후보 ID | 색인으로 확인한 명칭과 범위 | 미확정 경계 |
| --- | ---: | --- | --- |
| 유주돌기 | 2100 | 三國志 卷08의 幽州突騎와 公孫瓚 파견 | 반복 모집 가능 여부는 게임 결정 |
| 단양병 | 2103 | 三國志 卷32의 丹楊兵과 陶謙의 劉備 지원 | 반복 모집 가능 여부는 게임 결정 |
| 백마의종 | 2117 | 後漢書 卷073의 白馬義從과 公孫瓚 | 이후 다른 지휘관 양도 규칙 미정 |
| 호표기 | 2101 | 三國志 卷09 魏書·諸夏侯曹傳 曹純 본문의 虎豹騎 | 본문 인용 범위를 주석 인용과 혼동하지 않음 |
| 함진영 | 2102 | 三國志 卷07 裴注引英雄記의 陷陣營과 高順 | 본문과 주석 출전을 구분; 피해·장비 수치는 사료에서 가져오지 않음 |
| 청주병 | 2127 | 三國志 卷01의 항졸 선발과 青州兵 명명 | `CAPTIVE` 결속은 이 인용만으로 주장할 수 없음 |
| 대극사 | 2133 | 三國志 卷06 魏書·董二袁劉傳 裴注引英雄記의 袁紹 장막 大戟士 | 전국 공용 병종으로 확대하지 않음 |
| 해번병 | 2118 | 三國志 卷55 韓當傳의 解煩兵 | 손오 전체의 단일 상설 부대로 단정하지 않음 |
| 적갑군 | 2120 | 華陽國志 卷一 巴志의 涪陵郡 절에 있는 赤甲軍 | `漢時` 이상으로 세부 연대 미확인 |
| 연노사 | 2121 | 華陽國志 卷一 巴志의 涪陵郡 절에 있는 連弩士 | 다른 시기의 반복 모집 가능 여부 미확인 |

적갑군·연노사의 `requires.regionName`은 원문 절의 `涪陵郡`이다. MAP4의
`data/map/han-tiles.json`에는 같은 지명의 군 `parentRegions.id=PARENT-0150`과
현 `涪陵縣`이 별도로 있다. 두 카드만 `mapParentRegionId=PARENT-0150`으로
게임 지도 연결을 고정한다. 이 연결은 사료의 행정 연대 주장과 구분하며,
후속 편성 단계에서 지도 ID로 판정해야 한다. MAP4 대조표의 군 좌석 진단은
일부 현에 `REVIEW_REQUIRED_DIAGNOSTIC_ONLY` 상태를 남기므로, 좌석 문자열이나
진단 행을 모집 판정으로 사용하지 않는다.

`COMMON`과 비용 색, 명망 값, 결속 모집 목록은 `designDecision` 및
`renownCost`의 `CONFIRMED`/`decidedBy`로 게임 결정을 분리했다. 사료가
확인해 주는 것은 이름과 해당 문맥의 인물·지역·넓은 시기뿐이다. 정확한
활동 연대가 원문에 없는 항목은 후속 편성 통합 전에 연대 인용을 보강해야 한다.

## 정체성 프리셋

15개 이름은 설계 문서 §5의 목록을 따른다. 태평도와 오두미도는 명칭과
조직 기록을, 도적은 黑山賊 張燕의 한 사례를 인용한다. 이 세 항목의
`historicalClaimScope`는 해당 문장만 주장하며, 게임의 완성된 국가 제도나
능력치를 사료가 보증하지 않는다. 나머지 12개는 `게임 용어`다. 특히
漢書 卷030 藝文志에 儒家·道家·陰陽家·法家·名家·墨家·縱橫家·兵家가
문헌 갈래로 분류돼 있어도, 그것만으로 한말의 독립 국가 정체성이
확인되지는 않는다. 중립과 없음은 게임 상태다.

최신 `ScenarioImporter`는 국가의 `ideology`를 기존 `nation.type_code`로
옮기지만 이 프리셋 원장의 ID를 받는 시드 필드는 없다. 따라서 이 원장을
기존 이념 코드에 암묵적으로 결합하지 않는다.

## 제외·보류

`units.json`의 산월병·선등·백모병 등은 이번 사료 원장에
넣지 않았다. 山越 언급만으로 `山越兵`의 직접 명칭은 확인할 수 없고,
先登校尉는 袁紹의 `先登` 부대명 인용을 대신하지 않는다. 白毦兵/白毛兵
표기도 이 색인의 시대 사서에서 확인되지 않았다. 이는 이번 색인의
미검출 또는 인용 범위 불일치이며, 역사 전체에 없었다는 결론이 아니다.

검증: `python3 tools/content/validate_unit_identity_sources.py`와
`python3 -m unittest tools.content.tests.test_validate_unit_identity_sources -v`.
두 명령은 CI `contracts` 작업의 `Verify unit identity source ledgers` 단계에서도 실행된다.
사료 재검색: 메타 `references/sources/shiliao`에서
`python3 -m shiliao.index <원문구절> --book <책> --limit 3`.
