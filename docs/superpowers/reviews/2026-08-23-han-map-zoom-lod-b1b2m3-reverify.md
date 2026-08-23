# han-map-zoom-lod B1·B2·M3 독립 재검증 (3차, 작성 레인 외부)

Scope: 팀 리드가 필수로 확정한 세 항목만 — B1(`initialView` 초기 배율 상한), B2(縣 라벨 루프 zoom 조기 탈출 복원), M3(`MARQUISATE` 주석 사실성). 검증 대상은 `web/game/components/game/HanMapCanvas.tsx`·`web/game/lib/isoMap.ts`·`data/map/han-tiles.json`·`tools/map/build_han_places.py`. 결함 4·5·6·7 은 팀 리드가 이번 라운드 스코프에서 제외했으므로 판정에 반영하지 않는다.
Verdict: cleared

작성자의 "재검증 (2차 수정, 작성 레인)" 절은 **읽지 않은 것으로 치고** diff·코드·데이터를
직접 다시 계산해 판정했다. 아래 수치는 전부 이 세션에서 재현한 것이다.

## 판정 요약

B1·B2·M3 세 항목 모두 **실제로 고쳐졌다.** 작성자 자기보고와 코드가 일치한다.
다만 B1 의 수정 방식(고정 상한 2.1)이 줌아웃 하한 `fitScale` 과 구조적으로 충돌하는
지점이 있어, 차단은 아니지만 후속 항목으로 남긴다(아래 N1).

## B1 — 확인됨 (고쳐짐)

`HanMapCanvas.tsx` `initialView`:

```ts
const scale = Math.min(scaleForSpan(w, h, span), MIN_TIER2_MARKER_ZOOM - 0.1);
return clampView(viewAt(w, h, luo.col, luo.row, scale), w, h, g);
```

`MIN_TIER2_MARKER_ZOOM = Math.min(...Object.values(TIER2_MARKER_ZOOM))` = 2.2 이므로
상한은 2.1 이다. `clampView` 는 `v.scale` 을 그대로 보존한다(`isoMap.ts:85-92` — 반환하는
두 경로 모두 scale 을 손대지 않는다). 따라서 **초기 scale ≤ 2.1 < 2.2 는 데이터·뷰포트·dpr
과 무관하게 항등적으로 성립한다.** 작성자가 주장한 "실측 2.100" 을 실제
`data/map/han-tiles.json` 으로 재계산해 확인했다(juns 175건, `junSpanCells` 중앙값 14칸,
span=42, 격자 768×669):

| css 폭×높이(0.53배) | dpr | 백버퍼 | `scaleForSpan` | 최종 초기 scale |
|---|---|---|---|---|
| 800×424 | 1 | 800×424 | 9.524 | **2.100** |
| 800×424 | 2 | 1600×848 | 14.000 | **2.100** |
| 1280×678 | 1 | 1280×678 | 14.000 | **2.100** |
| 1280×678 | 2 | 2560×1356 | 14.000 | **2.100** |
| 1600×848 | 1 | 1600×848 | 14.000 | **2.100** |
| 1600×848 | 2 | 3200×1696 | 14.000 | **2.100** |
| 1920×1018 | 1 | 1920×1018 | 14.000 | **2.100** |
| 1920×1018 | 2 | 3840×2036 | 14.000 | **2.100** |

전부 2.2 미만이다. 즉 지도는 이제 1급(郡·國)만 켜진 상태로 열린다. 요구 충족.

## B2 — 확인됨 (고쳐짐)

라벨 루프가 다시 가드 안으로 들어갔다(`HanMapCanvas.tsx`, 縣 이름 블록):

```ts
if (s >= MIN_TIER2_LABEL_ZOOM) {
    ctx.font = …; ctx.fillStyle = …; ctx.lineWidth = 2.5;
    for (const c of data.cities) { … }
}
```

`MIN_TIER2_LABEL_ZOOM` = 5.5 이므로 이전 코드의 `if (s >= COUNTY_LABEL_ZOOM)` 와 동치이고,
`Object.values` 최솟값을 쓰므로 문턱이 늘어도 자동으로 따라간다(가드가 개별 문턱보다
느슨한 쪽이라 루프 내부 `s < labelZoom` 판정과 모순되지 않는다 — 안전 방향).
줌아웃 상태에서 `data.cities` 1144건 순회와 `ctx` 상태 세팅 3줄이 다시 통째로 스킵된다.
결함 2 의 "요구는 줄이라는 것이었는데 늘었다" 는 해소됐다.

## M3 — 확인됨 (고쳐짐)

주석이 사실과 맞다. 직접 대조한 근거:

- `data/map/han-tiles.json` 실집계: `COUNTY 958 / COMMANDERY 146 / EXTERNAL_PLACE 37 / PROVINCE 3` (합 1144), `MARQUISATE 0` · `KINGDOM 0`, `seat=true` 175건.
- `tools/map/build_han_places.py:60` — `'县': ('COUNTY', 5), '侯国': ('COUNTY', 5), '道': ('COUNTY', 5),`. 侯國이 애초에 `COUNTY` 로 emit 된다.
- PR #507 브랜치(`work/opensamguk/han-jun-guo-split`)의 같은 파일 확인 — `'国'` 만 `KINGDOM` 으로 바뀌었고 `'侯国': ('COUNTY', 5)` 줄은 그대로다.

주석은 "현재 데이터엔 0건 / `build_han_places.py` 의 TIER 가 '侯国'을 COUNTY 로 emit 해서다
(PR #507 도 그 줄은 안 건드린다) / 전방 호환으로 미리 얹어둔다" 로, 세 진술 모두 사실이며
"live/near-term 관심사" 로 오해될 여지가 없다. 요구 충족.

## 신규 지적 (차단 아님)

### N1. [MEDIUM] 고정 상한 2.1 이 줌아웃 하한 `fitScale` 과 충돌할 수 있다

줌 하한은 `fitScale(w, h, g)` 이고(`onWheel`·`zoomBy` 모두 이 값을 `zoomAt` 의 `min` 으로
넘긴다), `zoomAt` 은 `Math.max(min, v.scale * factor)` 를 쓴다. 초기 scale 이 `fitScale`
**아래**면 첫 줌 조작 한 번에 scale 이 `fitScale` 로 **튀어 오른다** — 줌아웃 방향으로
돌려도 그렇다. `fitScale` 이 2.2 를 넘는 뷰포트에서는 그 순간 縣 마커가 켜지므로,
"줌아웃했더니 縣이 나타난다" 는 역전 LOD 가 된다.

임계 조건은 백버퍼 폭 ≈ 3013px 이다(`fitScale = dpr·W·0.000697 = 2.1` 풀이). 현재
`.shell-main > *` 의 `max-width` 가 1100px(뷰포트 ≥1600px 에서 1440px)이므로 dpr 2 에서
최대 2880px — **임계 아래이고, 팀 리드가 지정한 dpr 1·2 범위에서는 발생하지 않는다.**
다만 여유가 4% 뿐이라 dpr ≥ 2.09(2.25·2.5·3 스케일링 환경)이나 컨테이너 폭 상향에서
바로 깨진다. 지금 회귀가 아니므로 차단하지 않는다.

제안(한 줄): 줌 하한을 `Math.min(fitScale(w, h, g), v.scale)` 로 두어 현재 배율보다
하한이 높아지는 상황 자체를 없앤다.

### N2. [LOW] 초기 뷰가 사실상 전체 격자 뷰가 됐다

scale 2.1·백버퍼 2880px 기준 화면에 담기는 폭은 `w / (2·s)` ≈ 685셀로, 768열 격자의
거의 전부다. `initialView` 의 이름과 doc("郡 두세 개가 보이는 배율로 낙양에 맞춘다")이
더 이상 실제 동작을 설명하지 않는다 — 실질적으로 `centeredView` 에 가깝고, `JUN_LABEL_ZOOM`
= 1.4 이므로 175개 郡 라벨이 첫 화면에 전부 켜진다. 이는 팀 리드가 확정한 방향 (a)의
직접적 귀결이므로 구현 오류가 아니다. 낙양 중심이라는 의도를 살리려면 縣 문턱을
현재 줌 범위 상단으로 옮기는 방향 (b)를 재검토할 여지가 있다.

### N3. [LOW] B1·B2 수정 자체에 테스트가 없다

`initialView` 는 export 되지 않고, `web/game/__tests__/HanMapCanvas.test.ts` 6개 테스트는
여전히 `tierZoom` 테이블 되읽기와 `expandOwner`/`labelledRegions`/`seatLabel` 뿐이다.
`Math.min(…, MIN_TIER2_MARKER_ZOOM - 0.1)` 를 지워도, `if (s >= MIN_TIER2_LABEL_ZOOM)`
가드를 다시 지워도 6개 전부 통과한다. 이는 원 비평의 결함 6 과 같은 문제이며 팀 리드가
이번 라운드 스코프에서 제외한 항목이므로 판정에 반영하지 않는다 — 다만 이번에 고친 두
결함이 무방비로 남았다는 점은 후속 스코프에서 우선순위를 올릴 근거가 된다.

## 재현한 증거

- `npm run typecheck` (web/game) — `tsc --noEmit` 무출력 종료. 통과.
- `npx vitest run` (web/game) — `Test Files 76 passed (76) / Tests 433 passed (433)`, 36.01s. `__tests__/HanMapCanvas.test.ts (6 tests)` 포함 전부 그린. flake 없음.
- `data/map/han-tiles.json` 직접 집계 — 위 M3 절의 kind 분포·seat 175건.
- `junSpanCells` 를 실제 juns 175건으로 재계산 — 중앙값 14.0셀, span 42. 초기 scale 표는 이 값과 `scaleForSpan`·`Math.min(…, 2.1)` 로 계산.
- `git show work/opensamguk/han-jun-guo-split:tools/map/build_han_places.py` — `'侯国': ('COUNTY', 5)` 유지 확인.

## UNKNOWN

- 브라우저 실행 시각 확인은 하지 않았다. B1 판정은 코드·데이터 계산이며 스크린샷 교차검증이 없다.
- N1 의 역전 LOD 는 `fitScale`·`zoomAt` 코드로부터 유도한 것이고 고 dpr 실기기에서 재현하지 않았다.
- B2 의 성능 개선분은 프로파일러로 측정하지 않았다. 판정 근거는 이전 코드와의 구조 동치성이다.

## 남아 있는 항목 (이번 판정에 영향 없음)

결함 4(미등록 등급 fallback 설계) · 5(매직넘버 잔존, `Record<string, number>` 타입) ·
6(렌더 경로 테스트 부재) · 7(작성 레인 자기 리뷰 문서) 는 손대지 않은 상태 그대로다.
팀 리드가 명시적으로 이번 라운드 필수 조치에서 제외했으므로 `cleared` 를 막지 않는다.
특히 결함 7 은 이 문서가 작성 레인 밖에서 작성됐다는 점으로 이번 라운드에 한해 완화된다 —
작성자의 "재검증 (2차 수정, 작성 레인)" 절은 자기보고이므로 그 자체로는 게이트를
만족시키지 못하며, 이 문서가 그에 대한 독립 확인이다.
