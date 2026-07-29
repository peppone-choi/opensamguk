# OpenSamguk v2 병종 카탈로그 개정 제안

- Date: 2026-07-29
- Status: **PROPOSED**
- Scope: 병종의 추가·통합·개명과 v1 호환 경계만 설계한다. 전투 수치, 코드, DB schema, 실제 모집 조건은 구현하지 않는다.
- Base draft: `docs/superpowers/specs/2026-07-13-v2-troop-building-content-catalog.md`

## 1. 요청 해석 정정

v1의 34개 기본 병종과 `event_more_crewtype`의 47개 병종은 v2 최종 roster가 아니다. 숫자 ID와 전투 패리티를 보존해야 하는 **오리지널 호환 입력**이다.

v2에서는 함진영·단양병 같은 실명 부대와 지역 모집 전통을 추가하고, 장비·탄약·교리·공성 장비·판타지 병종이 같은 계층에 섞인 v1 분류를 해체한다.

```text
v1 legacy unit ID
  -> migration crosswalk
  -> v2 FormationTemplate / loadout / doctrine / asset / LEGACY_ONLY
```

## 2. 개정 수량

현재 초안의 **120개**는 출시 계획값이지 역사 콘텐츠의 영구 상한이 아니다. 주변 세계를 위한 기존 48개 확장 예산을 줄여 새 실명 부대를 끼워 넣지 않고, core 보강 slot 8개를 새로 열어 **128개 계획 카탈로그**로 개정하는 안을 권장한다.

| 구분 | 현재 초안 | 개정안 |
|---|---:|---:|
| 공통 편제 | 18 | 18 |
| 실명 부대·지휘기관 | 22 | 22 |
| 지역·보조군 | 12 | 12 |
| 공성·수군·군수 | 10 | 10 |
| CLASSIC 연의·게임 참고 | 10 | 10 |
| 봉기·종교·군벌 모집 전통 | 0 | **8** |
| 주변 세계 근거 대기 확장 예산 | 48 | **48** |
| 합계 | 120 | **128** |

80개 named core를 한 시나리오에서 모두 모집하게 하지 않는다. 시기, 지역, 모집원, 인물의 지휘망, 시설, 외교 관계를 적용한 뒤 한 도시의 모집 화면에는 보통 6~12개만 노출한다.

첫 공개 roster 36개와 기술 증명 4개라는 단계 구분은 유지한다. `ACTIVE`가 된 항목만 완성 병종과 에셋 수에 포함한다.

여기서 128은 **카탈로그 예산 위치**다. 플레이어가 보는 실제 모집 행과 같은 숫자가 아니다. 80개 named 부모를 persistent 무장·이동·플랫폼 선택으로 펼친 결과는 [전체 모집 병종 카탈로그](2026-07-29-v2-expanded-recruitable-unit-catalog.md)의 **105개 `RecruitableVariant`**다. 나머지 48개는 아직 부모가 아닌 `BUDGET_ONLY` `CatalogBudgetSlot`이며 `NAMED` 승격 전에는 이름·ID·자식 variant가 없다.

| 계층 | 현재 제안 수 | 의미 |
|---|---:|---|
| named `FormationTemplate` | 80 | 역사적 정체성·모집 전통·상한 계보 |
| player-facing `RecruitableVariant` | **105** | 실제 모집·개편 카드 |
| `BUDGET_ONLY` `CatalogBudgetSlot` | 48 | 미명명 미래 예산, 부모·variant·asset 없음 |

## 3. 새로 이름을 부여할 8개 전통

아래 증거 등급은 이름이나 집단의 존재를 확인하는 claim에만 적용한다. 같은 행의 무장, 정규 편제, 전장 역할은 별도의 `HistoricalClaim`으로 나누며 근거가 없으면 `SCHOLARLY_RECONSTRUCTION` 또는 `BALANCE_ONLY`다.

| # | 이름 | 이름·집단 claim | 초기 연관 | 전장 역할과 제한 |
|---:|---|---|---|---|
| 73 | 호사(虎士) | `PRIMARY_ATTESTED` | 허저·조조 지휘망 | 지휘부 호위와 역돌격. 허저를 따르던 집단의 명칭은 확인되지만 무장과 정규 편제는 별도 복원 |
| 74 | 황중의종(湟中義從) | `PRIMARY_ATTESTED` | 동탁·금성·황중 의종호 모집망 | 변경 의종. 말·무기 inventory에 따라 기동형을 구성하며 이름만으로 전원 기병·궁기병을 확정하지 않음 |
| 75 | 동주병(東州兵) | `PRIMARY_ATTESTED` | 유언·유장·익주 이주민 | 남양·삼보 이주민을 재편한 군사 집단. 익주 토착병과 다른 결속·정치 위험을 가짐 |
| 76 | 진호병(秦胡兵) | `PRIMARY_ATTESTED` | 동탁·관중 서부 모집망 | 동탁 휘하 집단명은 확인되지만 단일 민족·단일 무장으로 평면화하지 않고 보병·기병 loadout은 별도 claim으로 둠 |
| 77 | 황건 동원군(黃巾) | `SCHOLARLY_RECONSTRUCTION` | 태평도 `方` 조직망 | 대규모 동원과 신앙 결속. 표준 제복·표준 무장·초자연 능력을 부여하지 않음 |
| 78 | 흑산 산악군(黑山) | `SCHOLARLY_RECONSTRUCTION` | 장연·태행산 네트워크 | 산악 이동·매복·분산 집결. 흑산 전체를 단일 정예 병단처럼 취급하지 않음 |
| 79 | 백파 유격군(白波) | `SCHOLARLY_RECONSTRUCTION` | 백파곡 황건 잔여 세력 | 하동·산지·하천 통로의 유격과 약탈. 세력 네트워크가 끊기면 일반 유민·향병으로 흡수 |
| 80 | 오두미도 조직대 | `SCHOLARLY_RECONSTRUCTION` | 장로·제주(祭酒)·귀졸(鬼卒) 조직망 | `鬼卒`은 입문자 호칭이고 제주가 조직·의창을 맡았다는 점만 claim한다. 호위·연락·전투 역할은 게임 복원이며 literal ghost나 마법 전투병으로 만들지 않음 |

73~80은 채택 시 새 `formation-core-addendum-2026-07-29` 예산의 8개 slot을 각각 하나씩 소비한다. 기존 주변 세계 48개 slot은 건드리지 않는다. 이름만 등록해서 ContentEntry를 `ACTIVE`로 만들 수 없으며, 각 항목에 모집·장비·보급·지휘·시기 claim과 fixture가 필요하다.

## 4. 기존 core의 개명·claim 경계 수정

`REGIONAL_TRADITION`은 출처 등급이 아니라 모집·소유 모델 type이다. 증거 등급은 기존 계약의 `PRIMARY_ATTESTED | SCHOLARLY_RECONSTRUCTION | ROMANCE_ATTESTED | GAME_REFERENCE | BALANCE_ONLY`만 사용한다.

| 현재 이름 | 개정 이름/claim 경계 | 이유 |
|---|---|---|
| 해번병 | **해번 양부(解煩兩部)**. 명칭·좌우 조직은 `PRIMARY_ATTESTED`, gameplay template은 별도 claim | 한 tradition 아래 `LEFT`·`RIGHT` cadre slot을 두고, 막연한 현대식 “특수부대” 해석을 피함 |
| 요장·장하병 | **차하호사(車下虎士)**. 일화의 표현은 `PRIMARY_ATTESTED`, 상설 군단·지속 역할은 `SCHOLARLY_RECONSTRUCTION` | 합비 철수 때 손권 곁의 차하호사라는 직접 표현은 살리되 한 전투의 표현을 영구 부대명으로 단정하지 않음 |
| 함진영 | 이름·약 700명·정돈된 장비·반복된 전과 claim은 『영웅기』 인용, gameplay template은 `SCHOLARLY_RECONSTRUCTION` | “전원 중장 창방패병” 같은 무장과 정확한 대형은 근거 없는 복원으로 명시 |
| 단양병 | `SCHOLARLY_RECONSTRUCTION` + `REGIONAL_TRADITION` | 여러 세력이 단양에서 모집한 전통이지 유비만의 영구 고유 군단이 아님 |
| 선등 | 이름 유지. UI 별칭 `국의 선등대`; `선등사`는 사용하지 않음 | 사료의 `先登`은 국의의 정병·강노 선봉 맥락이며 `先登死士`라는 정규 명칭은 과장 |
| 대극사 | 이름 유지. **원소 지휘부 근접 호위**로 축소 | 대규모 범용 대기병 군단이 아니라 원소 곁의 제한된 대극 병력으로 모델링 |
| 파인·판순 계열 | **판순병(板楯兵)** / `SCHOLARLY_RECONSTRUCTION` + `REGIONAL_TRADITION` | 기존 46번을 개명하는 것이며 새 76번을 만들지 않음. 방패·백죽노·험지 선봉은 claim별로 분리 |
| 양주 군마 전통 | **양주기병(涼州騎兵)** / `SCHOLARLY_RECONSTRUCTION` + `REGIONAL_TRADITION` | `서량철기`라는 단일 상설 군단 대신 말·갑주·무기에 따라 경기병·궁기·철기 loadout을 구성 |
| 연노 운용대 | **원융노 운용대(元戎弩)** / `SCHOLARLY_RECONSTRUCTION` | `元戎`·연노 전승은 살리되 표준화된 대규모 군단과 정확한 구조는 복원으로 표시 |
| 백이·백모병 | **백이병(白毦兵)**, UI 설명에 “백모 친병” | 음역과 뜻풀이를 두 부대처럼 병기하지 않음 |
| 감사대 | **결사대(敢死)**, 원문 배지 병기 | 한국어 `감사대`가 행정 조직으로 오해되는 문제를 없애고, `敢死`가 보통명사적 병력임을 표시 |
| 무난독 소속 | **무난독 휘하 병력**, 명칭·직무 claim과 gameplay template 분리 | 사료에 없는 `무난영`이라는 상설 부대명을 만들지 않고 관직이 곧 병종이라는 오해도 피함 |

`백마의종`, `청주병`, `호표기`, `호분`, `우림`, `서원군`, `해번 계열`은 주요 정체성을 유지한다.

## 5. 실제 모집 병종으로 펼치는 무장형

다음은 부모 `FormationTemplate` 수를 늘리지 않지만, 플레이어의 지속적인 전술 선택이므로 각각 고정 ID를 가진 `RecruitableVariant` 모집 행으로 펼친다.

| 전통 | 허용되는 대표 무장형 |
|---|---|
| 단양병 | 단양 창병 / 단양 궁병 / 단양 노병 |
| 청주병 | 청주 창병 / 청주 극병 / 청주 돌격대 |
| 양주기병 | 양주 경기병 / 양주 궁기병 / 양주 철기 |
| 황건 동원군 | 황건 도병 / 황건 창병 / 황건 궁병 |
| 흑산 산악군 | 산악 산병 / 방패 경보병 / 기동 궁병 |
| 판순병 | 방패 창병 / 백죽노병 |

위 표는 대표적인 다중 자식만 요약한다. 80개 부모와 105개 실제 모집 병종의 전행, stable ID, 이동·무장·플랫폼, gate는 [전체 모집 병종 카탈로그](2026-07-29-v2-expanded-recruitable-unit-catalog.md)를 정본 제안으로 삼는다.

같은 이름에 갑옷만 더 입힌 `철갑 단양병`, `정예 단양병`을 새 template나 variant로 만들지 않는다. 모집 등급은 `LEVY | REGULAR | CADRE`, 숙련은 `GREEN | TRAINED | VETERAN`, 장비 품질·상태·수량은 `FormationInstance`의 별도 축으로 둔다.

## 6. v1 병종의 v2 처리

### 6.1 유지·분해

| v1 항목 | v2 처리 |
|---|---|
| 보병·궁병·기병 | 공통 편제와 loadout으로 `SPLIT` |
| 청주병 | 청주병 전통에 `MAP` |
| 백마병 | 일반 유주 기마와 제한된 백마의종으로 `SPLIT` |
| 호표기병 | 호표기에 `MAP`, 표시명 수정 |
| 백이병 | 백이병에 `MAP` |
| 수병 | 형주 수륙병·강동 수군·전투 수군 선단으로 `SPLIT` |
| 근위병 | 호분·우림·호사·차하호사·기타 지휘부 친병으로 `SPLIT` |
| 궁기병·중장기병·돌격기병·철기병·수렵기병 | 이동·말·갑주·무기·교리 조합으로 `COMPOSED` |
| 대검병·극병·강습병 | 무기 loadout 또는 돌격 doctrine로 `COMPOSED` |
| 강궁병·석궁병·화시병 | 활·노·탄약 loadout으로 `COMPOSED` |
| 연노병·원융노병 | 원융노 운용대와 연노 장비·사격 doctrine로 `COMPOSED` |
| 정란·충차·벽력거 | 공성 운용대 + 생산된 장비 inventory로 `COMPOSED` |
| 목우 | 군량 수송대 + 목우유마 수송 upgrade로 `COMPOSED` |
| 상병 | `CLASSIC` 전상대에 `PROFILE_GATED` |
| 등갑병 | `CLASSIC` 등갑병에 `PROFILE_GATED` |

### 6.2 전투 병종에서 제외

| v1 항목 | v2 처리 |
|---|---|
| 귀병·신귀병·백귀병·흑귀병·악귀병·남귀병·황귀병·천귀병·마귀병 | 전부 `LEGACY_ONLY`. 계략·공포·기만은 전술/상태 시스템으로 이동 |
| 음귀병·향귀병 | `LEGACY_ONLY` |
| 무희 | 전투 formation에서 제외. 문화·사절·의례 콘텐츠 후보 |
| 자객병 | 대규모 전투 formation에서 제외. 인물 임무·침투·공작 시스템 후보 |
| 화랑 | 후한·삼국 core에서 `ALTERNATE_HISTORY_ONLY`. 한반도 후대 시기 팩과 혼합 금지 |
| 산저병 | `LEGACY_ONLY`. 역사 claim 없이 동물 기병으로 재해석하지 않음 |
| 맹수병 | `CLASSIC`의 맹수몰이대·전상대로 분해 |
| 화륜차 | 일반 병종에서 제외. `CLASSIC` 시나리오 제작 공성 asset 후보 |

오두미도 조직대의 `귀졸` claim은 v1 귀병의 역사화가 아니다. 종교 조직의 입문자 호칭을 설명하는 근거일 뿐이며, legacy 귀병 ID를 여기에 자동 매핑하지 않는다.

위 표는 family 정책이며 아직 numeric crosswalk 완료표가 아니다. 채택 전에는 다음 열을 가진 별도 정본을 만들어 v1의 모든 numeric ID를 한 행씩 열거한다.

```text
LegacyUnitCrosswalkRow
  legacyNumericId, legacyName
  outcome: MAP | SPLIT | COMPOSED | PROFILE_GATED
         | LEGACY_ONLY | ALTERNATE_HISTORY_ONLY
  targetFormationTemplateIds[]
  selectionPredicate
  unmappedBehavior: REJECT
```

`selectionPredicate`가 여러 target 중 선택 조건을 명시하지 못하거나 target이 비어 있는데 `LEGACY_ONLY`가 아니면 validator가 실패한다. 알 수 없는 legacy ID를 공통 보병으로 보내는 fallback은 두지 않는다.

## 7. 실명 부대의 게임 규칙

- 함진영·호표기·백마의종·차하호사·백이병·호사·대극사는 일반 연구 한 번으로 무한 모집하지 않는다.
- 해번 양부는 하나의 tradition과 하나의 모집 병종 아래 `(formation.jiefan_liangbu, LEFT)`와 `(formation.jiefan_liangbu, RIGHT)` 두 cadre slot을 가지며, 각 slot의 상한은 1이고 소멸·승계·재건을 따로 기록한다.
- 다른 실명 부대의 기본 상한은 한 정권당 하나의 살아 있는 cadre이며, 정확한 예외는 구현 fixture에서 정한다.
- 창설에는 관련 인물 또는 승계된 지휘망, 모집원, 장비, 베테랑 seed가 필요하다.
- 원래 도시를 점령했다고 고유 부대를 즉시 얻지 않는다.
- 지휘관 사망 뒤에도 생존자·기치·교관·문서가 남으면 비싼 재건이 가능하다.
- 단양병·청주병·동주병·판순병·양주기병은 인물 전용이 아니라 지역·인구·정치 관계에 묶는다.
- 황건·흑산·백파·오두미도 계열은 세력 이름이 아니라 살아 있는 조직망과 모집원으로 판정한다.

## 8. 에셋 영향

80개 named 부모와 105개 실제 모집 병종이 각각 독립 대형 sprite sheet를 요구하지 않는다.

- 모든 `ACTIVE` `FormationTemplate`은 전통 아이콘, 기치, base art를 가지며, 모든 `ACTIVE` `RecruitableVariant`는 해소 가능한 조합형 visual recipe를 가진다.
- 보행 근접, 장병, 사격, 기마 근접, 기마 사격, 수송, 공성, 선박 등 24~32개 animation archetype을 계획 상한으로 삼는다.
- 실루엣·무장·갑주·지역 정체성이 64px에서 달라야 할 때만 별도 animated archetype을 만든다.
- 진영색은 병종 ID에 굽지 않고 띠·깃발·방패용 mask로 적용한다.
- 48개 `BUDGET_ONLY` `CatalogBudgetSlot`은 placeholder 부모가 아니다. `NAMED`에서 처음 이름·semantic ID·`FormationTemplate`을 만들고, 이후 claim·fixture·visual recipe를 준비해 `ACTIVE`로 승격한다.

## 9. 근거

- 기존 72/48 예산과 `FormationTemplate` 조합: [v2 병종·건축물 콘텐츠 카탈로그](../specs/2026-07-13-v2-troop-building-content-catalog.md)
- v1 34개 병종: `common/src/main/kotlin/opensamguk/common/constants/GameUnitConst.kt`
- v1 47개 확장 세트: `legacy/devsam-core/hwe/scenario/unit/event_more_crewtype.php`
- [『삼국지』 권18, 허저와 호사](https://zh.wikisource.org/zh-hant/%E4%B8%89%E5%9C%8B%E5%BF%97/%E5%8D%B718)
- [『후한서』 권72 동탁전, 황중의종과 진호병](https://zh.wikisource.org/wiki/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B772)
- [『후한서』 권75, 동주병과 오두미도 귀졸](https://zh.wikisource.org/zh/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B775)
- [『후한서』 권86, 판순 군사 전통과 단양정병](https://zh.wikisource.org/zh-hant/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B786)
- [『후한서』 권71, 흑산·백파 등 황건 이후 군사 네트워크](https://zh.wikisource.org/zh-hant/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B771)
- [『삼국지』 권55, 차하호사와 오군 병력](https://zh.wikisource.org/zh-hant/%E4%B8%89%E5%9C%8B%E5%BF%97/%E5%8D%B755)
- [『삼국지』 호종전, 해번 양부](https://ctext.org/text.pl?if=gb&node=604605)
- [『삼국지』 권7 배송지주가 인용한 『영웅기』, 함진영 전승](https://zh.wikisource.org/zh-hant/%E4%B8%89%E5%9C%8B%E5%BF%97_%28%E5%9B%9B%E5%BA%AB%E5%85%A8%E6%9B%B8%E6%9C%AC%29/%E9%AD%8F%E5%BF%97/%E5%8D%B707)
- [『후한서』 권74상, 선등과 대극사](https://zh.wikisource.org/zh-hant/%E5%BE%8C%E6%BC%A2%E6%9B%B8/%E5%8D%B774%E4%B8%8A)
- [Steam TROM guide](https://steamcommunity.com/sharedfiles/filedetails/?id=1978468851): militia·부곡·정예의 계층 문법과 세력별 가독성만 참고하며 명칭·수치의 역사 근거로 쓰지 않음

## 10. 변경·동결 정책

이 문서는 `PROPOSED`이므로 병종 추가, 삭제, 개명, 통합, 분리가 가능하다. 다만 공개 이후 저장 데이터와 replay를 깨뜨리지 않도록 단계별 규칙을 둔다.

- `BUDGET_ONLY`와 제안 단계: 이름을 붙이지 않거나 후보를 교체할 수 있다.
- `NAMED`: semantic ID가 아직 외부에 공개되지 않았다면 개명·통합할 수 있고 변경 사유를 기록한다.
- `CLAIMED` 또는 `FIXTURE_GREEN`: claim·fixture·visual recipe의 참조를 함께 갱신해야 하며 단계 역행은 새 review가 필요하다.
- `ACTIVE`: display name은 바꿀 수 있지만 semantic ID를 재사용하지 않는다. 통합은 `supersededBy`, 분리는 새 ID와 `splitInto[]`로 처리한다.
- v1 numeric ID는 어떤 단계에서도 v2 semantic ID가 되지 않으며 crosswalk에만 남는다.
- 수량을 맞추려고 이름만 다른 중복 template이나 갑옷 상·하위형을 만들지 않는다.

따라서 128도 영구 고정 숫자가 아니다. 다만 카탈로그 목표를 다시 바꿀 때는 `CatalogBudget` 새 version, 증감 사유, 에셋·fixture 비용, 공개 roster 영향까지 함께 검토한다.

## 11. 채택 조건

- 80개 named `FormationTemplate`과 48개 미래 `CatalogBudgetSlot`의 합이 128개 예산 위치이며, 기존 120개 C5 목표와 asset wave를 명시적으로 supersede한다.
- 80개 named 부모가 [전체 모집 병종 카탈로그](2026-07-29-v2-expanded-recruitable-unit-catalog.md)의 105개 고유 `RecruitableVariant`로 빠짐없이 해소되고, 48개 budget slot에는 placeholder 부모나 variant가 없다.
- 새 8개 항목마다 모집원·지휘·이동·무장·방호·보급·시기 claim이 연결된다.
- 지속적인 무장·이동·플랫폼 선택은 별도 variant로 고정하되, 훈련·숙련·품질·상태·진영색만으로 variant를 늘리지 않는다.
- `CHRONICLE`에서 `ROMANCE_ATTESTED`, `GAME_REFERENCE`, `ALTERNATE_HISTORY_ONLY`가 자동 비활성된다.
- 별도 numeric crosswalk에서 모든 v1 ID가 `MAP | SPLIT | COMPOSED | PROFILE_GATED | LEGACY_ONLY | ALTERNATE_HISTORY_ONLY` 중 하나로 명시되며 generic fallback은 없다.
- 대표 도시 fixture는 상황에 맞는 6~12개 모집 행을 기대값으로 검사한다. 유효한 행이 더 많으면 scrolling/pagination으로 모두 제공하며 13번째 이후를 잘라내지 않는다.
- 실명 cadre는 상한·승계·재건 규칙을 가진다.
- 모든 `ACTIVE` `RecruitableVariant`는 visual recipe가 있고 동시에 노출되는 형제끼리 64px 기치·무장·실루엣 검사에서 구분된다.
