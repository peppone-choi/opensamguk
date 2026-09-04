# Spatial Province Supply Projection Plan

> 실행 절차: 실패 테스트를 먼저 추가하고 각 단계의 집중 검증을 통과시킨 뒤 다음 단계로 진행한다.

## 목표

월간 도시 보급을 774개 `CityConst.path`만의 BFS에서 벗어나, PR #607과 동일한 1,524개 공간 프로빈스 점유 projection과 4,161개 인접 edge를 통과하는 결정적 multi-source BFS로 계산한다.

## 범위와 계약

- 수도 도시의 정본 공간 프로빈스를 보급 시작점으로 한다.
- 같은 정치 소유자의 인접 공간 프로빈스만 통과한다.
- 현치가 아닌 구성 프로빈스도 경로·차단 판정에 참여한다.
- 정본 공간 프로빈스 도달성은 기존 도시 그래프와 독립된 증거다. 두 그래프가 모두
  미도달일 때만 파괴적 감쇠를 적용하고, city-only 불일치는 검토된 `UPHOLD_*` 원장 행이
  있을 때만 공간 단절을 우선한다. 미분류 불일치는 런타임에서 보호하고 CI 감사는 실패한다.
- 정본 `provinceId`가 없는 legacy 도시는 기존 도시 BFS를 보조 판정으로 유지하되 공간 프로빈스 경로의 중계점으로 사용하지 않는다.
- 중립 프로빈스, 타 세력 점유 프로빈스, 비대칭·범위 밖 edge는 통과하지 않는다.
- API와 엔진은 15개 시나리오에 대해 동일한 직접 공간 점유 projection을 산출해야 한다.
- Han 정본·도시 매핑·시나리오는 엔진 시작 시 검증하고, 누락 시 legacy 경로로 무음 후퇴하지 않는다.
- 월별 snapshot은 캐시된 정본 topology를 외부 변경으로부터 격리한다.
- 파괴적 미보급으로 확정된 경우의 기존 후속 효과(도시 10%, 장수 5% 감소와 신뢰도 기반
  중립화)는 변경하지 않는다.

## 작업

### 1. 순수 공간 보급 BFS

- 수정: `logic/src/main/kotlin/opensamguk/logic/world/UpdateCitySupply.kt`
- 테스트: `logic/src/test/kotlin/opensamguk/logic/world/UpdateCitySupplyBfsTest.kt`
- 실패 테스트:
  - 현치 사이 도시 edge가 없어도 같은 세력의 비현치 프로빈스 corridor를 따라 보급된다.
  - corridor의 한 프로빈스가 타 세력/중립이면 차단된다.
  - 다른 세력 프로빈스를 우회하는 같은 세력 경로는 통과한다.
  - legacy 무매핑 도시는 기존 BFS 결과를 유지하되 공간 경로를 잇지 않는다.
- 구현: `SpatialSupplyNetwork`와 결정적 province BFS를 추가하고 `applyCitySupply`가 선택적으로 사용한다.

### 2. 운영 정본 loader와 parity

- 추가: `app/game-engine/src/main/kotlin/opensamguk/engine/world/HanSpatialSupplyProvider.kt`
- 테스트: `app/game-engine/src/test/kotlin/opensamguk/engine/world/HanSpatialSupplyProviderTest.kt`
- 실패 테스트:
  - `han-tiles.json`의 1,524 프로빈스와 4,161 adjacency를 정확히 읽는다.
  - self/duplicate/asymmetric/out-of-range edge를 거부한다.
  - 15개 시나리오 및 runtime city override에서 PR #607 API projection과 province owner가 일치한다.
  - 어양군 노현 7개 공간 프로빈스와 촉군 현들의 점유가 그대로 보급 입력에 들어간다.
- 구현: canonical topology/ownership을 캐시하고 월간 live city owner만 투영한다.

### 3. 월간 생산 경로 연결

- 수정:
  - `logic/src/main/kotlin/opensamguk/logic/world/UpdateCitySupply.kt`
  - `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldActionContext.kt`
  - `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldEventContextFactory.kt`
  - `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt`
  - `docker/game-engine.Dockerfile`
- 테스트: 월간 event seam에서 Han 지도는 spatial network를 받고 다른/fixture 지도는 legacy fallback을 유지한다.
- Docker 이미지에 기존 SSoT JSON을 읽기 전용으로 포함한다.

### 4. 감사와 완료

- 15개 시나리오에서 spatial network coverage, capital seed, supplied/blocked city를 자동 감사한다.
- 집중 테스트, logic/game-engine 전체 테스트, 타입/컴파일, production image build를 실행한다.
- 독립 코드 리뷰 후 수정·재검증한다.
- 별도 PR을 생성해 CI/review를 통과시키고 병합·main deploy·PEP 승격·health/public route를 확인한다.
- 운영에서 도시 `supplyState`와 지도 보급 표시를 확인하고 report를 남긴다.

## 제외

- WATERWAY/SEA_ROUTE, 계절·용량·위험·비용은 #473의 별도 수송망 작업이다.
- 비현치 공간 점유를 변경하는 전투/작전 write model은 해당 작전 PR의 범위다. 이번 PR은 현재 정본 projection을 보급 판정의 직접 입력으로 만든다.
- 개인 서신 #606은 다음 별도 PR이다.
