# Map Hierarchy Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 최신 `main`의 1,524개 공간 프로빈스·1,020개 현급 관할·172개 군국 정본 위에서 프로빈스/현/군국 표시를 분리하고, 관할 중복 없이 시나리오 직접 소유권을 계층별로 렌더링한다.

**Architecture:** game-api가 `han-scenario-province-ownership-v1.json`과 `han-tiles.json`의 안정 ID를 직접 결합해 공간 점유·현 정치 소유권·군국 통제권을 서로 다른 DTO로 제공한다. shared 캔버스는 선택한 레이어에 맞는 색과 외곽선만 파생하고, 현 표시는 `jurisdictionId`, 군국 표시는 `commanderyId`를 유일 키로 사용한다. 원자 프로빈스 기하와 시나리오 정본은 수정하지 않는다.

**Tech Stack:** Kotlin 2/Jackson/Spring MVC/JUnit 5, TypeScript/React/Canvas/Vitest, pnpm

**Spec:** `docs/superpowers/specs/2026-09-02-administrative-spatial-hierarchy-design.md`

## Global Constraints

- 기준점은 PR #605 병합 SHA `7a337facf625fa56c6e434b64dac253788a51970` 이후다.
- 제품 지도는 `han-world-v2`/`han`만 사용하고 1,524개 공간 프로빈스·1,020개 현급 관할·172개 군국을 보존한다.
- 원자 프로빈스 기하를 합치거나 새 거대 프로빈스를 만들지 않는다. 레이어 외곽선만 파생한다.
- 프로빈스 점유, 현 정치 소유권, 군국 통제권은 서로 다른 필드다.
- 공간 점유의 시작값은 15개 시나리오의 직접 프로빈스 할당이며 도시 배열 인덱스로 전체 영토를 재구성하지 않는다.
- 현 소유권은 현치 프로빈스의 정본 소유자를 시작값으로 삼고, 해당 현의 유일한 현재 런타임 도시 소유자가 있으면 그 값으로 갱신한다.
- 런타임 도시가 같은 `jurisdictionId`에 둘 이상 연결되면 임의로 숨기지 않고 계약 오류로 실패한다.
- 현 레이어 렌더 키는 `jurisdictionId`, 군국 레이어 렌더 키는 `commanderyId`다.
- `OUT_OF_SCOPE`에는 정치색·이름·아이콘·hitbox를 만들지 않는다.
- 개인 서신과 보급은 이 계획 및 PR 범위 밖이며 각각 후속 PR로 분리한다.

---

### Task 1: game-api 계층별 정치 상태 투영

**Files:**
- Create: `app/game-api/src/main/kotlin/opensamguk/gameapi/read/MapAdministrativeOwnership.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/MapPreviewDto.kt`
- Modify: `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/MapPreviewController.kt`
- Create: `app/game-api/src/test/kotlin/opensamguk/gameapi/read/MapAdministrativeOwnershipTest.kt`
- Modify: `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapPreviewControllerTest.kt`
- Modify: `docker/game-api.Dockerfile`

**Interfaces:**
- Consumes: `MapAdministrativeOwnership.project(scenarioCode: String, liveCities: List<LiveCityOwnership>): AdministrativeOwnershipSnapshot`.
- Produces: `MapPreviewResponse.provinceOccupancy[] { provinceRecordId, provinceIndex, nationId }`, `jurisdictionOwnership[] { jurisdictionId, nationId }`, `commanderyControl[] { commanderyId, nationId }`.
- Resolves: `scenario_1010` and `1010` to numeric scenario `1010`; other formats fail closed for the `han` map.
- Mutates no world state. The projection is a read model built from direct scenario assignments plus current seat-city ownership.

- [ ] **Step 1: Write the failing projection tests**

Create literal temporary map/ownership fixtures with one two-province jurisdiction, one second jurisdiction, and one commandery. Assert that direct province owners remain distinct, the jurisdiction owner follows its seat province, a unique live city overrides only the seat occupancy plus jurisdiction ownership, commandery control is derived separately, and two live cities in one jurisdiction throw `duplicate runtime cities for jurisdiction J1`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.read.MapAdministrativeOwnershipTest --rerun-tasks`

Expected: compile failure because `MapAdministrativeOwnership` and its DTOs do not exist.

- [ ] **Step 3: Implement the minimal read model**

Parse the two configured files once per `(size, mtime)` fingerprint. Validate exact province ID coverage, unique stable IDs, valid jurisdiction/commandery references, and exactly one assignment per province. Compute jurisdiction starting ownership from `seatPlaceId`; apply a live-city override only after stable `provinceIndex -> provinceRecord -> jurisdictionId` resolution. Compute commandery control by most owned jurisdictions, break a tie with the seat jurisdiction owner when present, then lowest positive nation ID; return `0` when every member is neutral.

- [ ] **Step 4: Add the response and controller contract tests**

Extend the existing `MapPreviewControllerTest` fixture so the response contains all three arrays, one runtime city updates its jurisdiction, and an unseeded/non-han world returns empty arrays. Assert on serialized response values, not repository mock calls.

- [ ] **Step 5: Run Task 1 tests and commit**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.read.MapAdministrativeOwnershipTest --tests opensamguk.gameapi.controller.MapPreviewControllerTest --rerun-tasks`

Expected: `BUILD SUCCESSFUL` and no failing XML suites.

Commit: `feat(map): expose administrative ownership layers`

---

### Task 2: 레이어별 색과 외곽선 파생

**Files:**
- Modify: `web/shared/src/provinceMap.ts`
- Modify: `web/shared/src/index.ts`
- Modify: `web/game/__tests__/provinceMap.test.ts`

**Interfaces:**
- Produces: `AdministrativeLayer = 'PROVINCE' | 'COUNTY' | 'COMMANDERY'`.
- Produces: `buildAdministrativeEdges(map, provinceRecords): { provinceEdges, jurisdictionEdges, commanderyEdges }`.
- Produces: `bindAdministrativeOwnership(map, provinceRecords, state, layer): ProvinceOwnershipBinding`.
- Preserves: `ProvinceIdentityMap.provinces` and `commanderies` as the immutable raster identity source.

- [ ] **Step 1: Write failing geometry and color tests**

Use a hand-written `3×2` raster where province 0 and 1 share `jurisdictionId=J1`, province 2 is `J2`, and all are in `C1`. Assert that the province layer keeps the 0/1 internal edge, the county layer removes it but keeps the J1/J2 edge, and the commandery layer removes both. With direct owners `province0=1`, `province1=2`, `province2=3`, assert that each layer consumes only its matching DTO field instead of choosing a representative city color.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `corepack pnpm --filter @opensamguk/game test -- provinceMap.test.ts`

Expected: named exports are missing.

- [ ] **Step 3: Implement deterministic boundary and binding functions**

Scan right/down raster transitions once. For the county layer compare mapped `jurisdictionId`; for the commandery layer compare the raster `commanderies` value. At map/out-of-scope edges close the selected polygon boundary. Validate every ownership entry references the exact stable ID/index pair and never color a negative raster identity.

- [ ] **Step 4: Verify mutation cases**

Add malformed fixtures for duplicate province entries, mismatched stable ID/index, missing jurisdiction owner, and unknown commandery. Each must throw a distinct contract error instead of producing neutral or inferred color.

- [ ] **Step 5: Run Task 2 tests and commit**

Run: `corepack pnpm --filter @opensamguk/game test -- provinceMap.test.ts`

Expected: PASS.

Commit: `feat(map): derive administrative layer geometry`

---

### Task 3: 관할 키 기반 단일 표시와 세 토글

**Files:**
- Modify: `web/shared/src/HanMapCanvas.tsx`
- Modify: `web/game/__tests__/HanMapCanvas.interaction.test.tsx`
- Modify: `web/game/__tests__/HanMapCanvas.test.ts`

**Interfaces:**
- Consumes: `HanMapCanvasProps.administrativeOwnership` and Task 2의 `AdministrativeLayer`.
- Produces: buttons `프로빈스(지역)`, `현`, `군국` with mutually exclusive `aria-pressed` state.
- Produces: one county overlay per `jurisdictionId`, one commandery overlay per `commanderyId`, no administrative marker in province mode.
- Produces: `IsoCountyHover`의 안정 `provinceRecordId`, 공간 프로빈스 표시명, `jurisdictionId`/현명, `commanderyId`/군국명으로 구성된 전체 계층 경로.

- [ ] **Step 1: Write failing renderer tests**

Build a fixture for 어양군 노현 equivalent: one jurisdiction with seven province IDs and one runtime city. Assert county mode emits one scene city, one label, one flag, and one hitbox; commandery mode emits one representative; province mode emits no administrative marker. Assert a second runtime city mapped to the same jurisdiction throws instead of being deduplicated.

같은 fixture의 각 공간 프로빈스를 hover했을 때 프로빈스 안정 ID와 표시명은 서로 다르지만 `jurisdictionId=J-LU`와 군국 ID는 동일하게 유지되는지 검증한다. `OUT_OF_SCOPE` 픽셀은 계층 경로와 hitbox를 반환하지 않아야 한다.

- [ ] **Step 2: Run focused renderer tests and verify RED**

Run: `corepack pnpm --filter @opensamguk/game test -- HanMapCanvas.interaction.test.tsx HanMapCanvas.test.ts`

Expected: missing province toggle and duplicate-jurisdiction assertion failures.

- [ ] **Step 3: Replace province-key deduplication with explicit hierarchy collections**

Build county overlays by iterating `jurisdictionRecords` and anchoring at the province named by `seatPlaceId`. Build commandery overlays by iterating `commanderyRecords` and anchoring at the seat jurisdiction's seat province. Validate the runtime city grouping before rendering. Remove the commandery-mode extra current-city marker; preserve initial focus through the canonical marker position already computed before layer projection.

- [ ] **Step 4: Select fill and outline by active layer**

Bake three ownership canvases or deterministically rebuild the selected one when the layer changes. Draw only the active layer's outline, so internal province edges disappear in county mode and both province/county edges disappear in commandery mode. Preserve the existing containment gate, dark outer/light inner flag strokes, and capital badge without creating a second flag.

- [ ] **Step 5: Run Task 3 tests and commit**

Run: `corepack pnpm --filter @opensamguk/game test -- HanMapCanvas.interaction.test.tsx HanMapCanvas.test.ts provinceMap.test.ts`

Expected: PASS.

Commit: `fix(map): render each jurisdiction once`

---

### Task 4: 프론트 소비자 연결과 15개 시나리오 감사

**Files:**
- Modify: `web/game/lib/types.ts`
- Modify: `web/game/components/game/MapViewer.tsx`
- Modify: `web/game/__tests__/MapViewer.props.test.tsx`
- Modify: `web/game/__tests__/MapViewer.interaction.test.tsx`
- Modify: `web/gateway/lib/types.ts`
- Modify: `web/gateway/components/MapPreview.tsx`
- Modify: `web/gateway/__tests__/MapPreview.iso.test.tsx`
- Create: `tools/scenario/tests/test_administrative_render_projection.py`
- Modify: `docs/superpowers/specs/2026-09-02-administrative-spatial-hierarchy-design.md`

**Interfaces:**
- Consumes: the three new `MapPreviewResponse` arrays in both game and gateway clients.
- Audits: all 15 scenario assignments against the current hierarchy and derives the same county/commandery state as the API algorithm.
- Documents: the live projection rule and the intentional 上庸縣 1100/1110 split between direct spatial occupancy and seat-based county ownership.
- Renders: MapViewer hover/selection status에 `공간 프로빈스 → 현 → 군국` 경로와 각 계층의 정치 상태 차이를 함께 표시한다.

- [ ] **Step 1: Write failing consumer tests**

Assert both frontends pass all three ownership arrays into `HanMapCanvas` with nation colors attached. Assert `mergeLive` changes city ownership without deleting the direct province ownership arrays.

- [ ] **Step 2: Run the consumer tests and verify RED**

Run: `corepack pnpm --filter @opensamguk/game test -- MapViewer.props.test.tsx MapViewer.interaction.test.tsx`

Run: `corepack pnpm --filter @opensamguk/gateway test -- MapPreview.iso.test.tsx`

Expected: `administrativeOwnership` is absent from captured canvas props.

- [ ] **Step 3: Connect API state and add the scenario audit**

Map `nationId=0` to neutral without synthesizing a color. The Python audit must check 1,524 assignments per scenario, stable ID coverage, one county owner derived from the seat assignment, deterministic commandery control, no out-of-scope IDs, and explicitly report the two 上庸縣 mixed-occupancy scenarios as intentional direct-vs-seat separation rather than a silent representative color.

- [ ] **Step 4: Run focused and full validation**

Run: `python3 -m unittest tools.scenario.tests.test_administrative_render_projection -v`

Run: `corepack pnpm --filter @opensamguk/game test`

Run: `corepack pnpm --filter @opensamguk/gateway test`

Run: `corepack pnpm --filter @opensamguk/game typecheck && corepack pnpm --filter @opensamguk/gateway typecheck`

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test`

Expected: all commands pass with no skipped new contract test.

- [ ] **Step 5: Build, smoke, commit, and prepare PR**

Run: `corepack pnpm --filter @opensamguk/game build && corepack pnpm --filter @opensamguk/gateway build`

Run: `python3 tools/map/administrative_spatial_hierarchy.py --strict`

Run: `python3 tools/scenario/runtime_province_fill_audit.py --summary`

Expected: production builds pass; hierarchy counts remain 1,524/1,020/172; the renderer projection audit covers all 15 scenarios.

Commit: `test(map): audit administrative rendering projection`

After the exact PR SHA passes CI and review, verify production `/game/pep/map` at DPR 1/1.5/2/3 for one 어양군 노현 label/marker/flag, filled 촉군 counties, merged county/commandery boundaries, contained labels/icons, and one capital flag. Record merge SHA, image, PEP, health, route, allowlist, and remaining risk in the metarepo task report.
