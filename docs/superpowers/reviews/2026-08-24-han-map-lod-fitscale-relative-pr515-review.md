# PR #515 독립 리뷰 — `han-map-lod-fitscale-relative`

Scope: `git diff origin/main...HEAD` (4 커밋: `fafd4b28`, `b4ddc10e`, `aac5d954`, `5ce50f23`).
변경 파일 4개 — `web/game/components/game/HanMapCanvas.tsx`, `web/game/__tests__/HanMapCanvas.test.ts`,
`docs/superpowers/reviews/2026-08-24-han-map-zoom-lod-label-threshold-reverify.md`(신규),
`reports/opensamguk/tasks/han-map-zoom-lod.md`(신규). 코드 변경은 앞 두 개뿐.
리뷰어 레인은 작성 레인 밖이며, 아래 수치는 전부 이 세션에서 `isoMap.ts` 의 실제
`fitScale`/`MAX_SCALE` 과 `data/map/han-tiles.json` 실측 격자로 직접 재계산했다 —
산문 요약이나 선행 리뷰 결론은 근거로 치지 않았다.

## 1. 실행 결과 (실측)

| 명령 | 결과 |
|---|---|
| `web/game$ npx tsc --noEmit` | exit 0, 오류 0 |
| `web/game$ npx vitest run __tests__/HanMapCanvas.test.ts` | 1 file passed, **10 passed / 0 failed** |
| `web/game$ npx vitest run` (전체) | **78 files passed, 443 passed / 0 failed** (66.0s) |
| `repo root$ python3 tools/agent-system/check.py --strict --base origin/main` | Changed files 4, **Errors 0, Warnings 0**, "No findings" |

메모: 과제 지시문의 `python3 ../../tools/agent-system/check.py` 경로는 이 워크트리에서
존재하지 않는다(`check.py` 는 워크트리 루트의 `tools/agent-system/check.py`). 리포 루트에서
실행해 통과시켰다. 지시문 오타로 판단, 결함 아님.

## 2. 스펙 준수 (Stage 1)

배경에 기술된 6개 항목이 전부 디프에 실재하고, 그 밖의 설명 없는 변경은 없다.

- 마커 문턱 상대화: `TIER2_MARKER_ZOOM = {COUNTY: 2.19, MARQUISATE: 2.19}`,
  `tierZoom(table, kind, fit) = table[kind] * fit` (`HanMapCanvas.tsx:93`). 확인.
- 라벨 문턱 절대 유지 + 클램프: `TIER2_LABEL_ZOOM` 5.5 그대로,
  `labelZoomFor(kind, fit) = min(MAX_SCALE - 0.5, max(abs, markerZoom))` (`:104-110`). 확인.
- `initialView` 상한: `min(scaleForSpan(...), 0.9 * MIN_TIER2_MARKER_ZOOM * fitScale(w,h,g))`
  (`:296-297`), `INITIAL_SCALE_MARGIN = 0.9` (`:276`). 확인.
- `initialView` export + 실데이터 vitest 케이스 (`aac5d954`). 확인.
- 문서 2건은 report/review 마크다운으로 제품 코드에 영향 없음.

K=2.19 의 유래도 재계산으로 확인된다 — 컨테이너 max-width 1440css(`app/globals.css:146`),
`h = round(w*0.53)`, dpr=1 에서 `fit = 1440/1435 = 1.0035`, 구 절대 문턱 2.2 역산 →
`2.2/1.0035 = 2.1923 ≈ 2.19`. 기준 뷰포트 체감이 0.1% 미만 오차로 보존된다.

## 3. 요구 검증 항목별 결과

### (2) `threshold > fit` 대수 보장 및 K>1 가드
`tierZoom` 은 `k * fit` 이므로 `k > 1` 이고 `fit > 0` 이면 `k*fit > fit` 이 모든 양의 `fit`
에서 성립한다. 보장은 실재한다. **런타임 가드는 없다** — 누가 `TIER2_MARKER_ZOOM.COUNTY`
를 0.9 로 바꿔도 컴파일·실행 모두 통과한다. 다만 테스트가 그 자리를 대신한다:
`tierZoom(...) > fit` 단정(K<1 이면 실패)과 신규 실데이터 케이스의
`expect(fit).toBeLessThan(MIN_MARKER_K * fit)`(역시 K<1 이면 실패) 두 겹이 걸려 있다.
런타임 불변식 검사 코드를 더 넣는 것은 이 규모에서 과잉이라 판단 — 테스트 가드로 충분.

### (3) 라벨 문턱의 MAX_SCALE 도달 불가 결함
손으로 추적: `fit = 5` → `markerZoom = 2.19*5 = 10.95` → `raw = max(5.5, 10.95) = 10.95`
→ `min(13.5, 10.95) = 10.95 < MAX_SCALE(14)`. 도달 가능.
클램프가 있으므로 **어떤 `fit` 에서도 라벨 문턱 ≤ 13.5 < 14** 이 정의상 성립 —
"라벨이 영원히 안 뜨는 화면"은 수학적으로 존재하지 않는다. 결함 해소 확인.
문제였던 1920css@2x(`fit=2.676`)는 라벨 문턱 5.8603 으로 정상 도달 가능.

독립 재계산 표(백버퍼 = css × dpr, `fit = min(W/1435, H/717.5)` — 프로덕션 종횡비에서는
항상 `W/1435`):

| 뷰포트 | fit | 마커=2.19·fit | 라벨 | 마커>fit | 라벨<14 | init 상한 |
|---|---|---|---|---|---|---|
| 800@1x | 0.5575 | 1.2209 | 5.5000 | 예 | 예 | 1.0988 |
| 1280@2x | 1.7840 | 3.9069 | 5.5000 | 예 | 예 | 3.5162 |
| 1440@1x | 1.0035 | 2.1976 | 5.5000 | 예 | 예 | 1.9779 |
| 1600@2x | 2.2300 | 4.8836 | 5.5000 | 예 | 예 | 4.3953 |
| 1920@2x | 2.6760 | 5.8603 | 5.8603 | 예 | 예 | 5.2743 |
| 3013@2x | 4.1993 | 9.1965 | 9.1965 | 예 | 예 | 8.2768 |

원래 버그도 실재했음을 확인: 1600css@2x 의 `fit = 2.2300 ≥` 구 절대 문턱 2.2 —
그 화면에선 완전 줌아웃해도 縣이 안 사라졌다. 상대화가 맞는 수정이다.

### (4) 프레임 루프 성능 / early-exit 회귀
`const fit = fitScale(w, h, { cols, rows })` 는 `draw()` 최상단
(`HanMapCanvas.tsx:189`)에서 **호출당 1회**만 계산된다 — 지형/도시/라벨 어떤 루프
안에도 `fitScale` 호출이 없다(`grep` 로 확인: 파일 내 `fitScale` 호출은 189행, 296행,
그리고 이벤트 핸들러의 줌 하한 계산뿐). 회귀 없음.
줌아웃 시 라벨 루프를 통째로 건너뛰는 early-exit 가드도 살아 있다 —
`if (s >= minLabelZoom)` (`:257`)이 구 `MIN_TIER2_LABEL_ZOOM` 가드를 그대로 대체한다.
차이는 상수였던 값이 `fit` 의존이라 draw 당 한 번 재계산된다는 것뿐(`:256`, 항목 2개).

### (6) 신규 vitest 케이스의 가치
절반은 기존 대수 테스트와 겹치고, 절반은 겹치지 않는다.

- `expect(fit).toBeLessThan(markerThreshold)` 는 `MIN_MARKER_K > 1` 의 재진술이라
  기존 `tierZoom(...) > fit` 케이스와 사실상 동어반복이다.
- `expect(initialView(w, h, grid, hanTiles).scale).toBeLessThan(markerThreshold)` 는 다르다.
  실제 `data/map/han-tiles.json`(768×669, 郡治 175개)로 `junSpanCells` → `scaleForSpan`
  → `MAX_SCALE` 상호작용과 `clampView` 가 scale 을 건드리지 않는다는 사실까지 함께
  통과시켜야 성립한다. B1(초기 뷰가 문턱 위에서 시작) 회귀를 실제로 잡는 유일한 테스트다.

`Math.min(..., 0.9*threshold)` 구조상 이 단정도 대수적으로 강제되긴 하지만, 테스트가
지키는 것은 "그 상한이 코드에 존재하고 같은 `fit` 좌표계로 걸린다"는 사실이다 — B1 은
정확히 그 상한 부재였다. **유지할 가치 있음.** 남는 중복은 한 줄뿐이라 제거 권고는 안 한다.

## 4. 발견 사항

CRITICAL 0 / HIGH 0 / MEDIUM 0 / LOW 5. 차단 사항 없음.

### [LOW] L1 — K>1 런타임 가드 없음 (Confidence: HIGH)
`web/game/components/game/HanMapCanvas.tsx:87,93`
`TIER2_MARKER_ZOOM` 값이 1 이하로 편집되면 "완전 줌아웃 = 1급만" 보장이 조용히 깨진다.
Fix: 조치 불필요. 테스트 2건이 K<1 에서 실패하므로 회귀는 CI 에서 잡힌다.
런타임 assert 추가는 이 규모에서 과잉.

### [LOW] L2 — 극단 fit 에서 "라벨 ≥ 마커" 구조 제약 역전 (Confidence: HIGH)
`web/game/components/game/HanMapCanvas.tsx:109`
`min(MAX_SCALE-0.5, ...)` 클램프가 값을 깎기 시작하는 `fit > 13.5/2.19 = 6.1644`
구간에서는 라벨 문턱(13.5)이 마커 문턱(2.19·fit)보다 낮아져, 점 없이 이름만 뜨는
구간이 이론적으로 생긴다. 다만 컨테이너가 1440css 로 캡되어 있어(`app/globals.css:146`)
`fit ≈ dpr × 1.0035` 이고, 이 구간에 들어가려면 **dpr > 6.1** 이 필요하다 — 현존 하드웨어
범위 밖이다. 테스트 주석(`HanMapCanvas.test.ts:59-64`)이 이 극단을 별개 케이스로 명시해
인지 상태다. Fix: 조치 불필요.

### [LOW] L3 — 마커 문턱에는 MAX_SCALE 클램프가 없다 (비대칭) (Confidence: HIGH)
`web/game/components/game/HanMapCanvas.tsx:93`
라벨은 `MAX_SCALE-0.5` 로 클램프하지만 마커는 안 한다. `fit > 14/2.19 = 6.3927`
(dpr > 6.4 상당)이면 마커 문턱이 `MAX_SCALE` 을 넘어 縣 마커가 그 화면에서 영원히 안
뜬다 — L2 가 지적한 도달 불가 결함의 마커판이다. 같은 이유로 실사용 도달 불가.
Fix: 지금은 조치 불필요. 컨테이너 max-width 캡이 사라지거나 초고 DPR 이 현실화되면
`tierZoom` 에도 같은 클램프를 넣는 것이 일관적이다.

### [LOW] L4 — 리사이즈가 초기 scale 상한을 재적용하지 않는다 (Confidence: MEDIUM)
`web/game/components/game/HanMapCanvas.tsx:369-372` (`fit()` 콜백 / ResizeObserver)
리사이즈 시 `clampView` 만 호출하는데 `clampView` 는 오프셋만 가두고 `scale` 은 보존한다
(`isoMap.ts:clampView`). 문턱이 `fit` 의존이 된 뒤로, 창을 줄이면 `fit` 이 작아져 문턱도
같이 내려가므로 사용자가 줌하지 않았는데 縣 마커가 나타날 수 있다. 절대 문턱 시절엔
없던 새 동작이다. 다만 이는 LOD 를 `fit` 상대로 두겠다는 이번 수정의 **의도된 의미론**
그대로다 — 화면이 작아지면 같은 scale 이 상대적으로 더 확대된 상태이므로 縣이 나오는
것이 오히려 일관적이다. 반대 방향(창 확대 → scale < 새 fit)은 `main` 에도 있던 기존 동작.
Fix: 조치 불필요. 의도라면 그대로 두고, 아니라면 `fit()` 에서 `scale` 도
`max(fitScale(...), ...)` 로 재클램프.

### [LOW] L5 — draw 당 소량 할당 / 라벨 루프 내 중복 계산 (Confidence: HIGH)
`web/game/components/game/HanMapCanvas.tsx:256,263`
`minLabelZoom` 계산이 draw 마다 `Object.keys().map()` 배열 2개를 할당하고,
`labelZoomFor(c.kind, fit)` 가 도시 루프에서 셀당(縣 ~970개) 재호출된다.
등급이 2종뿐이라 실측 영향은 무시 가능하고, 종전 코드도 셀당 `tierZoom` 을 호출했으므로
회귀도 아니다. Fix: 조치 불필요.

### 부수 관찰 (결함 아님)
- 테스트 뷰포트 `[3013, 1200]` 은 `2h < w` 라 `fitScale` 이 높이 기준으로 결정되는데,
  프로덕션은 `h = round(w*0.53)` 이라 항상 폭 기준이다. 또 3013css 는 컨테이너
  max-width 1440 을 넘는다. 즉 실제로는 도달하지 않는 조합이지만, 보수적으로 더 넓은
  영역을 덮는 쪽이라 문제되지 않는다.
- `MIN_MARKER_K` 를 프로덕션 테이블에서 파생시켜 쓴 것은 좋은 선택이다 — 하드코딩된
  기대값이었다면 상수 편집 시 테스트가 함께 틀어져 가드 역할을 못 했을 것이다.

## 5. 잘한 점

- `fitScale` 을 `draw()` 최상단에서 한 번만 계산하고 루프에 전파 — 이전 라운드의 성능
  회귀를 되풀이하지 않았고, early-exit 가드도 의미를 그대로 보존한 채 이관됐다.
- 라벨과 마커의 문턱 **목적이 다르다**(도달 가능성 하한 vs 밀도/겹침)는 점을 코드
  주석(`:79-85`, `:99-102`)에 명시하고, 구조 제약만 `labelZoomFor` 로 좁게 강제했다 —
  한 번 실패한 "둘 다 상대화" 접근을 되돌린 판단이 정확하다.
- `MAX_SCALE - 0.5` 클램프로 "영원히 도달 불가" 를 대수적으로 봉쇄했다.
- `INITIAL_SCALE_MARGIN` 을 고정 뺄셈(-0.1)이 아닌 비율로 둔 것이 옳다 — 문턱 자체가
  화면마다 달라진 뒤로 고정 뺄셈은 작은 `fit` 에서 음수가 될 수 있었다.
- 신규 테스트가 상수를 다시 베끼지 않고 실제 데이터·실제 export 함수를 호출한다.
- `tierZoom` 이 `undefined` 를 그대로 전파해 호출부의 "안 그림" 처리와 계약이 맞는다.

## 6. Open Questions

없음. 저신뢰 CRITICAL/HIGH 후보 없음.

## 7. 권고

APPROVE. 스펙 전 항목이 디프에 실재하고, 핵심 불변식 3개(완전 줌아웃 < 마커 문턱 /
라벨 문턱 < MAX_SCALE / 초기 뷰 < 마커 문턱)를 독립 재계산으로 확인했으며,
tsc·vitest 443건·check.py --strict 전부 통과했다. LOW 5건은 전부 실사용 도달 불가
극단이거나 의도된 의미론이라 차단하지 않는다.

Verdict: cleared
