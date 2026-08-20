# OPENSAM-213 후한 작전 이동 스펙 독립 리뷰

- Date: 2026-08-20
- Reviewer: independent read-only agent `/root/han_spec_213/op213_spec_review`
- Author lane: `/root/han_spec_213`
- Reviewed artifact: `docs/superpowers/specs/2026-08-20-opensam-213-han-operation-movement-spec.md`
- Exact reviewed artifact SHA-256: `2ea37fc159befa576a6897385ebab6021390474ae948e5170a0fa74fbe642b2f`
- Exact reviewed artifact length: 563 lines
- Scope: 문서 계약의 결정성, route/progress, Dijkstra, phase, 퇴각·합류,
  campaign/battle-engine/replay 경계. 제품 구현 완료 여부는 리뷰하지 않는다.

Verdict: cleared

## 1. 최종 판정

최종 exact artifact는 OPENSAM-213이 요구한 다음 계약을 모순 없이 고정한다.

- 780 city / 1,778 undirected edge topology와 integer half-unit 비용
- `ROAD=1`, `PLAIN=2`, `HILL/BASIN=3`, `PLATEAU=4`, `DESERT=6`,
  `MOUNTAIN=8`, crossing `+2`, ford `+12`, SEA impassable
- `(cost, hop count, full path city IDs)` total order의 780-node Dijkstra
- durable multi-turn route/progress와 rational mid-edge contact/retreat 위치
- unequal-budget arrival fraction, node/edge contact, general ID final tie의 무 RNG total order
- movement → FIELD → SIEGE → URBAN → AFTERMATH와 모든 terminal 분기
- 원점 방향 퇴각, full-operation atomic merge, active battle reinforcement 분리
- `CampaignBattleHandoff`와 battle-engine `battle_ticket`/result/replay DML 소유권 분리
- 기존 `processWar_NG`를 공성 kernel로만 소비하고 `che` `CalcCityDistance`를 건드리지 않는 경계

열린 fix-required finding은 없다. 아래 U1–U6은 구현 착수 전 결정을 요구하는 명시적 hard stop이며
현재 문서 내부의 모순이나 clearance 예외가 아니다.

## 2. Findings와 remediation 추적

### Round 1 — FIX-REQUIRED

| Severity | Finding | Remediation observed in exact artifact |
|---|---|---|
| MAJOR | lifetime route-total을 arrival clock으로 쓰면 긴 route를 부당하게 우선할 수 있음 | route-local progress와 exact `arrivalOffset / turnAdvance` rational 비교를 분리하고 unequal-budget fixture 추가 |
| MAJOR | mid-edge encounter에 canonical location과 total order가 없음 | `ON_EDGE(min,max,rational offset)`과 exact contact fraction, edge/pair tie tuple 고정 |
| MAJOR | mid-edge 패자의 퇴각 시작점이 정의되지 않음 | contact 위치를 보존하고 기존 edge를 직전 node까지 역주행한 뒤 origin Dijkstra tail을 연결 |
| MAJOR | friendly/no-wall/surrender/패배/철수/retreat completion phase가 비어 있음 | typed terminal matrix를 추가해 모든 경로를 AFTERMATH/RETREAT/blocked로 닫음 |
| MAJOR | secondary Operation 일부만 옮기고 terminal 처리할 수 있음 | 모든 participant/lock/reservation/deferred owner/provenance의 single-flush atomic transfer, 부분 실패 시 전부 거절 |
| MAJOR | 현재 map에서 weighted edge input을 만들 수 없고 route policy/hash pin이 빠짐 | immutable `HanRouteEdgeSnapshot`, `routePolicyRevision`, `edgeSnapshotHash`를 계약화하고 U6 hard stop 추가 |
| MINOR | game-engine 산출물을 BattleTicket handoff로 부정확하게 호칭 | game-engine `CampaignBattleHandoff`와 battle-engine `battle_ticket` 생성으로 정정 |

### Round 2 — FIX-REQUIRED

| Severity | Finding | Remediation observed in exact artifact |
|---|---|---|
| MAJOR | ROAD cost 1의 중앙 접촉 `1/2`를 integer progress로 저장할 수 없음 | canonical `RationalHalfUnits`를 progress, route location, segment/total cost, arrival offset까지 전파; coincident trajectory는 overlap-start 선택 |
| MAJOR | contact progress commit과 battle handoff 사이 crash gap | progress/phase/global ordinal/locks/`CampaignBattleHandoff`를 한 `ChangeRecorder -> JdbcFlushExecutor` flush로 묶음 |
| MAJOR | SIEGE/URBAN 퇴각 중 contact가 FIELD phase로 역전할 수 있음 | 현 phase ordinal을 유지하고 그 아래 `FIELD_BATTLE` type을 생성; operation-global encounter ordinal 사용 |

### Round 3 — FIX-REQUIRED

| Severity | Finding | Remediation observed in exact artifact |
|---|---|---|
| MAJOR | rational mid-edge retreat route가 city-only `nodeIds.size-1` schema로 표현 불가 | immutable operation origin/attack target과 별도로 `movementMode`, `routeStart`, `movementDestinationCityId`, `futureNodeIds`, equal-cardinality rational `segmentCosts`를 도입 |

### Final rereview — CLEARED

독립 reviewer는 current disk에서 다음을 재확인하고 `CLEARED`를 반환했다.

- `routeStart -> futureNodeIds[0]`가 segment 0이며 `futureNodeIds.size == segmentCosts.size`다.
- rational contact에서 시작한 retreat route가 prior node와 origin destination을 정확히 표현한다.
- `movementMode=RETREAT` route가 보존된 `attackTargetCityId`를 SIEGE target으로 재사용하지 않는다.
- replay와 acceptance fixture가 rational arrival, route cardinality, destination, SIEGE exclusion,
  contact atomic flush, non-decreasing phase ordinal과 hash stability를 포함한다.
- stale old route schema 또는 unresolved review finding이 없다.

## 3. U1–U6 implementation hard stops

| ID | Hard stop | Required decision/evidence before implementation |
|---|---|---|
| U1 | turn별 movement budget 공식과 cadence 미결 | version owner가 `advanceHalfUnits` 산식·revision을 승인해야 movement resolver 착수 가능 |
| U2 | street battle의 공식 battle type/adapter·수식 미결 | BATTLE-F2/adapter 계약 전 enum·수식 하드코딩 금지 |
| U3 | retreat 통행 허용 node와 origin 상실 fallback 미결 | 정책 승인 전 nearest-friendly 순간이동 또는 임의 fallback 금지 |
| U4 | Operation merge compatibility와 supply cap 미결 | explicit compatibility verdict 없이 서로 다른 Operation 자동 merge 금지 |
| U5 | isolated city 523/550/759/770/780의 의도 여부 미결 | `ROUTE_UNREACHABLE`은 유지하고 근거 없는 edge 생성 금지 |
| U6 | edge terrain/road/crossing/ford offline source와 generator 미결 | immutable edge artifact/hash 없이는 weighted route 구현 금지 |

RC1도 유지된다. Jira는 수치만 주고 대수식을 주지 않았으므로 `ROAD`를 absolute base cost로,
crossing/ford 중 하나를 additive surcharge로 해석한 §2.2 공식은 구현 계획에서 승인받아야 한다.
RC2도 유지된다. 서로 다른 budget의 같은-turn arrival는 raw offset이나 lifetime progress가 아니라
exact rational fraction으로 비교해야 한다.

## 4. 독립 검토 근거

- `.ai/decisions.md`: ADR-LITE-025/032/037/041/042
- `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md`: Operation/BattleReplay와 phase 축
- `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`:
  campaign handoff, battle ticket, stable ordering, replay, result apply, reinforcement
- `infra/src/main/resources/map/han.json`: current 780 city / 3,556 directed adjacency entries
- `data/map/han-tiles.json`: cell terrain은 있으나 edge road/crossing/ford artifact는 없음
- `tools/scenario/build_han_world.py`: topology generation과 sorted connection output
- `logic/src/main/kotlin/opensamguk/logic/world/CalcCityDistance.kt`: 기존 `che` insertion-order BFS

독립 측정 결과는 780 nodes, 1,778 symmetric undirected edges, duplicate/self/asymmetric edge 0,
6 connected components(그중 isolated node 5개)였다. 작성 lane의 별도 `jq` 재측정도 780 nodes,
3,556 directed entries, 1,778 symmetric pairs와 isolated IDs 523/550/759/770/780을 확인했다.

## 5. 검증과 비범위

- Exact spec SHA-256: `2ea37fc159befa576a6897385ebab6021390474ae948e5170a0fa74fbe642b2f`
- 최종 문서 게이트: `scripts/agent/verify-changes.sh --run` green,
  agent-system Errors 0 / Warnings 0.
- 제품 코드·테스트·공유 `.ai` 파일은 변경하지 않았다.
- Gradle/브라우저/runtime 검증은 docs-only scope에 적용되지 않아 실행하지 않았다.
- 이 verdict는 구현 완료, merge-ready, deploy-ready 또는 Jira 완료를 뜻하지 않는다.
