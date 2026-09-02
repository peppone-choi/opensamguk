# Administrative Spatial Hierarchy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 1,524개 공간 프로빈스, 현, 172개 군국을 서로 다른 정본 계층으로 물질화하고 군치·현치 중복 없이 소비할 수 있는 계약을 만든다.

**Architecture:** 기존 산출물을 즉시 삭제하지 않고 `jurisdictionRecords`와 역할 기반 `settlementRecords`를 병행 생성한다. 공간 프로빈스는 `jurisdictionId`로 현에 연결하고 군국 기하는 현의 합집합으로만 유도한다. Python 생성기와 계약을 먼저 고정한 뒤 JVM/API와 웹을 순차 전환한다.

**Tech Stack:** Python 3 unittest, JSON 생성기, Kotlin/Jackson/JUnit, TypeScript/React/Vitest/Canvas

**Spec:** `docs/superpowers/specs/2026-09-02-administrative-spatial-hierarchy-design.md`

## Global Constraints

- 제품 지도는 `han-world-v2`/`han`, 768×669 격자, 1,524개 공간 프로빈스, 172개 상위 군국을 보존한다.
- 군국 전용 중첩 프로빈스를 만들지 않는다.
- 현은 최소 한 공간 프로빈스를 가지며 큰 현은 여러 공간 프로빈스를 가질 수 있다.
- 정치색은 직접 공간 프로빈스 소유권만 사용하고 상위 군국에서 보간하지 않는다.
- `DIRECT_TERRITORY`는 최종 산출물에서 0개여야 하며 같은 군국의 실제 현으로만 전환한다.
- 기록된 현을 증명할 수 없는 내부 조각은 인접 실제 현에 흡수하고, 비플레이 축소는 외곽에서만 허용한다.
- 플레이 가능 육지에 둘러싸인 검은 비플레이 고립 성분은 0개여야 한다.
- 물리적 현치 하나당 아이콘·이름·깃발은 각각 하나만 렌더링한다.

---

### Task 1: 계층 감사 계약

**Files:**
- Create: `tools/map/administrative_spatial_hierarchy.py`
- Create: `tools/map/tests/test_administrative_spatial_hierarchy.py`

**Interfaces:**
- Consumes: `han-tiles.json`의 `owner`, `provinceRecords`, `parentRegions`, `cities`.
- Produces: `audit_hierarchy(document) -> HierarchyAudit`, fail-closed `validate_hierarchy(document) -> HierarchyAudit`, 현재 전환 부채를 읽는 `audit_transition_debt(document) -> TransitionDebtAudit`, 기본 감사/`--strict` CLI.

- [x] **Step 1: 실패 테스트 작성**

```python
def test_rejects_a_playable_province_without_jurisdiction(self):
    document = fixture_document()
    document["provinceRecords"][1]["jurisdictionId"] = None
    with self.assertRaisesRegex(ValueError, "unassigned playable province P2"):
        validate_hierarchy(document)

def test_rejects_a_commandery_polygon_namespace(self):
    document = fixture_document()
    document["provinceRecords"].append({**document["provinceRecords"][0], "id": "COMMANDERY-R1"})
    with self.assertRaisesRegex(ValueError, "commandery geometry"):
        validate_hierarchy(document)

def test_rejects_an_enclosed_non_playable_land_hole(self):
    document = fixture_document_with_enclosed_black_cell()
    with self.assertRaisesRegex(ValueError, "enclosed non-playable land"):
        validate_hierarchy(document)
```

- [x] **Step 2: 실패 확인**

Run: `python3 -m unittest tools.map.tests.test_administrative_spatial_hierarchy -v`

Expected: `ModuleNotFoundError: tools.map.administrative_spatial_hierarchy`.

- [x] **Step 3: 최소 감사기 구현**

`HierarchyAudit`에 `province_count`, `jurisdiction_count`, `parent_count`, `unassigned_province_ids`, `direct_territory_ids`, `duplicate_seat_place_ids`, `enclosed_non_playable_land_components`를 담고 다음을 거부한다: 알 수 없는 현/군국 ID, 프로빈스 0개 현, 현 0개 군국, 같은 현의 복수 군국, 미귀속 플레이 프로빈스, `DIRECT_TERRITORY`, 별도 군국 기하 ID, 플레이 육지에 둘러싸인 검은 고립 성분. 기본 CLI는 현 정본의 부채를 비파괴적으로 출력하고 `--strict`는 Task 2 물질화 뒤 CI 필수 경로로 승격한다.

- [x] **Step 4: 단위·기존 계약 실행**

Run: `python3 -m unittest tools.map.tests.test_administrative_spatial_hierarchy tools.map.tests.test_han_tiles_contract -v`

Run: `python3 tools/map/administrative_spatial_hierarchy.py`

Expected: tests PASS; 감사 출력은 1,524 provinces, 172 parents, 526 direct territories, 52 parents without county, 0 enclosed non-playable land components를 보고한다.

- [x] **Step 5: 커밋**

```bash
git add tools/map/administrative_spatial_hierarchy.py tools/map/tests/test_administrative_spatial_hierarchy.py docs/superpowers/plans/2026-09-02-administrative-spatial-hierarchy.md
git commit -m "test(map): define administrative spatial hierarchy"
```

### Task 2: 공간 프로빈스의 현 귀속 물질화

**Files:**
- Modify: `tools/map/build_tile_grid.py`
- Modify: `tools/map/world_province_geometry.py`
- Modify: `tools/map/han_tiles_contract.py`
- Create: `tools/map/tests/test_materialize_province_jurisdictions.py`
- Modify: `tools/map/tests/test_build_tile_grid_province_records.py`
- Modify: `data/map/han-tiles.json`
- Modify: `data/map/han-world-v2-manifest.json`

**Interfaces:**
- Consumes: `assign_province_jurisdictions(owner, province_records, parent_regions, cities)`.
- Produces: `provinceRecords[].jurisdictionId`, `jurisdictionRecords[]`, 귀속의 `assignmentBasis`와 `assignmentConfidence`.

- [ ] **Step 1: 결정성 실패 테스트 작성**

```python
def test_direct_fragment_joins_longest_same_parent_boundary(self):
    result = assign_province_jurisdictions(
        owner=[[0, 2, 2, 1], [0, 2, 1, 1]],
        province_records=fixture_records_same_parent(),
        parent_regions=fixture_parents(),
        cities=fixture_county_seats(),
    )
    self.assertEqual("COUNTY-B", result.province_records[2]["jurisdictionId"])
    self.assertEqual("MAX_SHARED_BOUNDARY", result.province_records[2]["assignmentBasis"])
```

- [ ] **Step 2: 실패 확인**

Run: `python3 -m unittest tools.map.tests.test_materialize_province_jurisdictions -v`

Expected: import failure for `assign_province_jurisdictions`.

- [ ] **Step 3: 같은 군국 안의 귀속 알고리즘 구현**

공유 경계 길이, 현치 격자거리, 안정 ID 순으로 정렬한다. 다른 `parentRegionId` 후보는 입력에서 제외한다. 후보 현이 없는 22개 군국은 정본 사료 입력에서 현을 복구하기 전까지 생성 자체를 실패시킨다. 사료로 현을 증명할 수 없는 내부 조각은 인접 실제 현에 흡수하고 교차 군국 변경은 adjudication에 기록한다. 비플레이 전환은 외곽 flood-fill과 연결된 셀만 허용한다.

- [ ] **Step 4: 정본 재생성 및 수량 계약 확인**

Run: `python3 tools/map/build_tile_grid.py`

Run: `python3 tools/map/build_tile_grid.py --check`

Run: `python3 -m unittest tools.map.tests.test_materialize_province_jurisdictions tools.map.tests.test_build_tile_grid_province_records tools.map.tests.test_han_tiles_contract -v`

Expected: 172 parents, every playable province assigned, every jurisdiction has at least one province, `DIRECT_TERRITORY` count 0, enclosed non-playable land component count 0. 외곽 비플레이 전환이 있으면 제거 내역과 flood-fill 근거를 manifest에 기록한다.

- [ ] **Step 5: 커밋**

```bash
git add tools/map/build_tile_grid.py tools/map/world_province_geometry.py tools/map/tests data/map/han-tiles.json data/map/han-world-v2-manifest.json
git commit -m "feat(map): bind spatial provinces to counties"
```

### Task 3: 현치 역할과 중복 제거

**Files:**
- Modify: `tools/map/build_tile_grid.py`
- Modify: `tools/map/tests/test_build_tile_grid_province_records.py`
- Modify: `data/map/han-tiles.json`

**Interfaces:**
- Consumes: `jurisdictionRecords[]`, 기존 `cities[]`, `juns[].seat`.
- Produces: `settlementRecords[]` with `roles: string[]`; 동일 `seatPlaceId`당 정확히 한 레코드.

- [ ] **Step 1: 중복 치소 실패 테스트 작성**

```python
def test_commandery_seat_is_a_role_on_the_county_settlement(self):
    tiles = build(grid_document=fixture_grid(), places_document=fixture_places())
    seats = [row for row in tiles["settlementRecords"] if row["seatPlaceId"] == "100"]
    self.assertEqual(1, len(seats))
    self.assertEqual(["COUNTY_SEAT", "COMMANDERY_SEAT"], seats[0]["roles"])
```

- [ ] **Step 2: 실패 확인**

Run: `python3 -m unittest tools.map.tests.test_build_tile_grid_province_records -v`

Expected: `settlementRecords` missing.

- [ ] **Step 3: 역할 병합 구현**

현치의 물리 ID로 그룹화하고 `COUNTY_SEAT`, `COMMANDERY_SEAT`, `PROVINCIAL_SEAT`를 안정 순서로 합친다. 기존 `cities[]`는 호환 입력으로 보존하되 새 소비자는 사용하지 않도록 `_meta.legacyGameplay`에 선언한다.

- [ ] **Step 4: 생성기와 계약 실행**

Run: `python3 tools/map/build_tile_grid.py && python3 tools/map/build_tile_grid.py --check`

Run: `python3 -m unittest tools.map.tests.test_build_tile_grid_province_records tools.map.tests.test_administrative_spatial_hierarchy -v`

Expected: physical seat duplicate count 0.

- [ ] **Step 5: 커밋**

```bash
git add tools/map/build_tile_grid.py tools/map/tests/test_build_tile_grid_province_records.py data/map/han-tiles.json
git commit -m "feat(map): fold administrative seats into settlement roles"
```

### Task 4: JVM/API 계층 전달

**Files:**
- Modify: `infra/src/main/kotlin/opensamguk/infra/seed/MapJson.kt`
- Modify: `infra/src/test/kotlin/opensamguk/infra/seed/MapJsonTest.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/MapPreviewDto.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MapPreviewController.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapPreviewControllerTest.kt`
- Modify: `infra/src/main/resources/map/han.json`

**Interfaces:**
- Produces: `MapPreviewCity.jurisdictionId`, `MapPreviewCity.parentRegionId`, `MapPreviewCity.settlementRoles`.
- Preserves: existing `provinceId` for direct spatial ownership and replay compatibility.

- [ ] **Step 1: DTO 실패 테스트 작성**

Map fixture의 한 물리적 현치에 현치·군국치 역할을 함께 넣고 API 응답 도시가 한 행이며 `settlementRoles == ["COUNTY_SEAT", "COMMANDERY_SEAT"]`인지 검증한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :infra:test --tests opensamguk.infra.seed.MapJsonTest :app:game-api:test --tests opensamguk.gameapi.controller.MapPreviewControllerTest`

Expected: 새 필드 assertion failure.

- [ ] **Step 3: 로더와 DTO 구현**

MapJson에서 정본 계층 ID와 역할을 읽고 API에 전달한다. 좌표 또는 이름으로 조인하지 않고 안정 ID만 사용한다.

- [ ] **Step 4: JVM 검증**

Run: `./gradlew :infra:test :app:game-api:test`

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add infra app/game-api
git commit -m "feat(api): expose map administrative hierarchy"
```

### Task 5: 군국/현 LOD와 단일 마커

**Files:**
- Modify: `web/shared/src/provinceMap.ts`
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/shared/src/index.ts`
- Modify: `web/game/lib/types.ts`
- Modify: `web/game/__tests__/provinceMap.test.ts`
- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`
- Modify: `web/game/__tests__/MapViewer.interaction.test.tsx`

**Interfaces:**
- Consumes: `jurisdictionRecords`, `parentRegions`, `settlementRecords`, direct province ownership.
- Produces: independent `commandery` and `county` boundary/label layers; one marker/hitbox/flag per settlement.

- [ ] **Step 1: 렌더 실패 테스트 작성**

같은 `seatPlaceId`에 `COUNTY_SEAT`와 `COMMANDERY_SEAT` 역할을 준 fixture를 렌더해 `drawImage` 1회, 깃발 path 1회, 현명 1회인지 검증한다. 기본 fit에서는 군국 경계만, 확대 임계값 뒤에는 현 경계와 현명이 추가되는지도 검증한다.

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @opensamguk/game test -- HanMapCanvas.interaction.test.tsx provinceMap.test.ts MapViewer.interaction.test.tsx`

Expected: duplicated marker/layer assertions fail.

- [ ] **Step 3: 계층별 scene과 LOD 구현**

`seatPlaceId`로 마커를 한 번만 만들고 역할 배지를 합성한다. `paths.commandery`와 `paths.province`의 표시 조건을 분리하고 군국명과 현명을 서로 다른 라벨 컬렉션에서 그린다.

- [ ] **Step 4: 웹 검증**

Run: `pnpm --filter @opensamguk/game test`

Run: `pnpm --filter @opensamguk/game typecheck && pnpm --filter @opensamguk/gateway typecheck`

Run: `pnpm --filter @opensamguk/game build && pnpm --filter @opensamguk/gateway build`

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add web/shared web/game web/gateway
git commit -m "feat(map): separate commandery and county presentation"
```

### Task 6: 명령 집계와 공간 이동 계약

**Files:**
- Create: `common/src/main/kotlin/opensamguk/logic/map/AdministrativeControl.kt`
- Create: `common/src/test/kotlin/opensamguk/logic/map/AdministrativeControlTest.kt`
- Modify: `web/game/lib/command-arg-types.ts`
- Modify: `web/game/lib/menu-types.ts`
- Create: `web/game/__tests__/administrativeCommandTargets.test.ts`

**Interfaces:**
- Produces: `deriveCountyControl(provinceClaims, seatProvinceId)`와 `deriveCommanderyControl(countyControls)`; command args with `commanderyId`, optional `jurisdictionId`, and deterministic `destinationProvinceId`.

- [ ] **Step 1: 부분 점령 실패 테스트 작성**

군국의 세 현 가운데 두 현을 공격자가, 한 현과 현치를 방어자가 보유한 fixture에서 공간 프로빈스 소유권은 바뀌지 않은 채 군국 통제와 현 통제가 별도로 계산되는지 검증한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :common:test --tests opensamguk.logic.map.AdministrativeControlTest`

Expected: missing class failure.

- [ ] **Step 3: 결정적 집계 구현**

직접 프로빈스 claim을 입력으로만 사용하고, 동률은 현치 점유 뒤 안정 nation ID로 결정한다. 집계 결과를 직접 claim으로 다시 쓰지 않는다.

- [ ] **Step 4: 도메인·웹 계약 검증**

Run: `./gradlew :common:test`

Run: `pnpm --filter @opensamguk/game test -- administrativeCommandTargets.test.ts`

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add common web/game
git commit -m "feat(domain): separate commandery control from province movement"
```

### Task 7: 전체 계약·시각·배포 검증

**Files:**
- Create: `reports`는 메타리포의 `reports/opensamguk/tasks/`에 별도 작성

**Interfaces:**
- Consumes: Tasks 1-6 전체.
- Produces: PR별 검증 증거, 운영 이미지 SHA, PEP 상태, 브라우저 스크린샷.

- [ ] **Step 1: 생성·시나리오 계약 실행**

Run: `python3 tools/map/build_tile_grid.py --check && python3 tools/map/han_tiles_protected_orchestrator.py --check && python3 tools/scenario/province_ownership_contract.py`

- [ ] **Step 2: 전체 타입·테스트·생산 빌드 실행**

Run: `./gradlew test`

Run: `pnpm -r typecheck && pnpm -r test && pnpm --filter @opensamguk/game build && pnpm --filter @opensamguk/gateway build`

- [ ] **Step 3: 실제 브라우저 확인**

운영 `/game/pep/map`에서 기본 군국 LOD, 확대 현 LOD, 단일 군국치 마커·깃발, 프로빈스 내부 라벨·hitbox를 조작하고 DPR 1/1.5/2/3 스크린샷을 남긴다.

- [ ] **Step 4: PR·배포·PEP 확인**

각 독립 PR의 CI와 리뷰를 통과시켜 main 병합 뒤 운영 이미지가 병합 SHA를 사용하고 game-api/engine health가 `UP`, 공개 route가 200인지 확인한다.

- [ ] **Step 5: 종료 report 작성**

결과, 파일, 커밋, PR, 병합 SHA, 검증, 운영 이미지, PEP, 헬스, 공개 route, 비플레이 전환 근거, 남은 위험과 다음 최소 작업을 기록한다.
