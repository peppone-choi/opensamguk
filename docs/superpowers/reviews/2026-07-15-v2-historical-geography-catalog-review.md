# v2 역사 지리·콘텐츠 카탈로그 독립 리뷰

> 날짜: 2026-07-15
> 범위: v2 제품 spec, 역사 도시·군대·지형 spec, 병종·건축물 카탈로그, 실행 계획
> 채점기: `docs/loops/v2-planning-2026-07-12/GOLDENSET.md`
> 최종 판정: cleared

## 검토 이력

| 단계 | 점수 | 판정 | 닫은 계약 |
|---|---:|---|---|
| 기준선 | 2/10 | fix-required | 시계열·위치 오차와 v1 격리 외 계약 부족 |
| 독립 리뷰 1 | 5/10 | fix-required | production 격리, 행정 delta, 치소 정본, 위치 미상 처리, 예산, LOD |
| 독립 리뷰 2 | 5/10 | fix-required | numeric priority, 현급 전수 참여, 치소·정치망 validator, content lifecycle |
| 독립 리뷰 3 | 8/10 | fix-required | `SeasonalRange` 기간 검증, slot/entry 양방향 참조 |
| 독립 리뷰 4 | 9/10 | fix-required | 실행 계획 V2-C0의 budget-slot 원자성·실패 fixture |
| fresh 전수 재검토 | 7/10 | fix-required | dangling fixture, C1..C5 출시 일정, 2,000개 구성 예산, 1,180개 기능 전수 fixture |
| 최종 fresh runner | 10/10 | cleared | blocking finding 없음 |

점수가 중간에 낮아진 것은 시험지를 바꾼 결과가 아니다. 새 reviewer가 spec 선언뿐 아니라 주 실행 계획의 작업·Exit·실패 fixture까지 요구해 숨은 누락 네 건을 추가로 드러냈다. `GOLDENSET.md`는 모든 pass에서 변경하지 않았다.

## 최종 근거

- 『후한서』 순제기 군·국 105와 현·읍·도·후국 1,180을 140년 baseline으로 두고 deterministic delta로 189년 snapshot을 만든다.
- `TemporalAdministrativeUnit`, `PhysicalPlace`, `SeatAssignment`, `PlaceControl`을 분리하고 co-location·기간·중복 validator를 둔다.
- `CountyParticipationFixture`가 현급 1,180개 각각의 조회·점령·주둔·징병·세입·보급을 G0 in-memory와 V2-0B sandbox runtime에서 각각 검증한다.
- `PhysicalPlace` 2,000개를 상호 배타적인 `PlaceBudgetClass` 1,200/200/500/100으로 검증하고 catalog LOD 120/380/1,500과 runtime render LOD를 분리한다.
- C1..C5가 `ACTIVE` formation 120, 시설 72, 기반망 18, 자원 유형 24, 정착지 kit 24, 지형·계절 profile 32를 release 선행 조건으로 고정한다.
- `CatalogBudgetSlot` 소비와 `ContentEntry` 생성은 원자적이며 이중 소비·부분 생성·slot-side dangling·entry-side dangling 실패 fixture를 가진다.
- V2-0A가 production v2 route·bean·migration·loader 0과 v1 schema·seed·PHP golden diff 0을 먼저 증명하고, 이후 G0와 V2-0B를 연다.
- `git diff --check`: clean.
- `tools/agent-system/check.py --strict --base origin/main --format json`: findings 0, `ok: true`.

Verdict: cleared
