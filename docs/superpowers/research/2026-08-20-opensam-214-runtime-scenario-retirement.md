# OPENSAM-214 런타임 시나리오 은퇴 감사

- 일자: 2026-08-20
- 판정: 15개 han 역사/IF 시나리오만 제품 카탈로그에 노출한다.
- 보존선: 은퇴는 카탈로그 지원 표면에만 적용한다. JSON, 맵, 골든,
  `scenario_1010_che.json`, `CheScenarioBootIT`, `ScenarioImporterIT`, CHE/miniche 테스트
  코드는 frozen-baseline 회귀 자산으로 그대로 둔다.

## 1. 16개 전수 감사

`infra/src/main/resources/scenario/scenario_*.json` 각각의 `map.mapName`, `map.unitSet`,
`general`, `nation`을 읽고, map 블록이 없는 `0`/`908`은 `ScenarioSeedRunner`·
`ScenarioImporter`의 `che` 기본값을 적용해 실효값을 판정했다.

| 코드 | 제목 | 맵 | 병종 세트 | 장수/세력 | 판정 |
| --- | --- | --- | --- | ---: | --- |
| 0 | 【공백지】 일반 | che | che | 0/0 | 은퇴 |
| 1 | 【공백지】 소형 | miniche | che | 0/0 | 은퇴 |
| 2 | 【공백지】 소형β | miniche_b | che | 0/0 | 은퇴 |
| 900 | 【공백지】 충차전 | che | siegetank | 0/0 | 은퇴 |
| 901 | 【공백지】 무주공산 | miniche_clean | che | 0/0 | 은퇴 |
| 902 | 【공백지】 천지비급 | miniche | che | 0/0 | 은퇴 |
| 903 | 【공백지】 영웅전설 | miniche | che | 0/0 | 은퇴 |
| 905 | 【공백지】 무작위 세계 | miniche_b | che | 0/0 | 은퇴 |
| 906 | 【공백지】 파죽지세 | miniche | che | 0/0 | 은퇴 |
| 908 | 【공백지】 이벤트 클래식 | che | che | 0/0 | 은퇴 |
| 910 | 【공백지】 거울세계 | cr | cr | 0/0 | 은퇴 |
| 911 | 【공백지】 축지 | miniche | che_except_siege | 0/0 | 은퇴 |
| 912 | 【공백지】 다병종 | miniche | event_more_crewtype | 0/0 | 은퇴 |
| 913 | 【공백지】 무한대흥 | miniche | che | 0/0 | 은퇴 |
| 914 | 【공백지】 요양 | miniche | che | 0/0 | 은퇴 |
| 9200 | 【v2】 도시 경제 시험장 | miniche_b | che | 2/2 | 은퇴(이미 비노출) |

15개 공백지는 모두 CHE 계열 또는 CR 맵이고 장수·세력이 없다. `9200`은
v2 leaf 시험용이므로 OPENSAM-151의 기존 9000번대 필터가 이미 숨기고 있었다.
OPENSAM-214는 숫자 범위 필터를 15개 제품 코드 allowlist로 바꾼 향후 테스트
리소스가 묵시적으로 제품 목록에 들어오는 것도 막는다.

## 2. 런타임과 회귀 표면의 경계

- 제품 지원 표면: `ScenarioCatalogService.list()`가 반환하는 15개 han 코드.
- 회귀 표면: 클래스패스 시나리오를 명시적으로 부트하는 통합 테스트. 은퇴 파일도
  삭제하지 않아 이 경로를 계속 증명한다.
- 향후 시나리오 추가: JSON 추가만으로는 제품 출시가 아니며 allowlist 리뷰가 필요하다.

## 3. han 상수 등재

OPENSAM-105 교차 리뷰가 문서 갭으로 남긴 세 수치를 ADR-LITE-043으로 승인했다.

| 수치 | 런타임 정본 | 경계 |
| --- | --- | --- |
| 건국 수비병 돌파 | `FOUND_ASSAULT_RATIO = 2.0`, `ceil(defence * 2.0)` | han만 적용, 다른 맵은 0 |
| 건국 가능 등급 | `level in 5..6 || level >= 10` | 영현/장현 추가, 경/대/특 제외 |
| 도적·황건 spine | `1/13/28郡治 -> level 2/3/4` | han 3축 등급 경로 |

## 4. OPENSAM-204 stale-premise 분류

OPENSAM-204 본문의 `161郡`은 더 이상 선행 결정이 아니다.

| 근거 | 관측 |
| --- | ---: |
| ADR-LITE-041 승인 세계 | 175郡治 / 780城 |
| `data/map/han-tiles.json` `_meta.counts.seats` / `(.juns | length)` | 175 / 175 |
| `infra/src/main/resources/map/han.json` `cities` | 780 |
| `data/map/han-tiles.json` `_meta.counts.cities` | 1,144(렌더·소유격자 원자료) |

따라서 `161郡`과 “세 숫자 중 택일해야 한다”는 서술을 `stale-premise`로 분류한다.
1,144는 월드 그래프 노드 수가 아니라 타일 렌더·소유 입력이다. 이동·AI·전투
설계는 780城을 노드로 사용한다.
