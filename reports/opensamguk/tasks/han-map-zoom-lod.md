# han-map-zoom-lod

## 요구
후한 맵(`HanMapCanvas.tsx`)에 행정 등급별 zoom LOD — 줌아웃 시 1급(郡·國·州)만,
줌인해야 2급(縣·侯國·道·邑)이 나타나게.

## 사전 확인 (구현 전 보고 대상이었던 두 항목)
1. **zoom 개념 존재 여부** — 이미 있다. `@/lib/isoMap`의 `IsoView.scale`이 줌 배율이고,
   휠/버튼 줌·클램프·MAX_SCALE=14가 다 구현돼 있다. 새로 만들 필요 없음.
2. **등급 구분 필드 존재 여부** — `HanTiles.cities[].kind`가 이미
   `AdministrativeContracts.kt:57`의 `AdministrativeLevel`과 같은 문자열
   (`PROVINCE`/`COMMANDERY`/`COUNTY`, 그리고 렌더러 전용 `EXTERNAL_PLACE`)을 담고 있다
   (`tools/map/build_han_places.py` TIER 테이블 확인). `KINGDOM`/`MARQUISATE`는 아직
   실제 데이터에 없다 — `국(國)`이 현재 `COMMANDERY`로 뭉개져 있는 것은 `jun-guo-split`
   레인이 살리는 중인 그 문제와 동일하다.
   단, `juns[]`(郡治 배열)에는 등급 필드가 아예 없다 — 郡·國 구분 없이 juns 배열
   하나로 합쳐져 있다. 그래서 1급 라벨(JUN_LABEL_ZOOM)은 등급 무관 균일 상수로 남겨뒀다.

이미 zoom·등급 필드 둘 다 있어서 범위가 렌더러 리팩터로 충분했다. 별도 승인 없이 진행.

## 구현
`web/game/components/game/HanMapCanvas.tsx`:
- 기존엔 `COUNTY_ZOOM`/`COUNTY_LABEL_ZOOM` 매직넘버 + `c.kind !== 'COUNTY'` 하드코딩
  조건문으로 2급 마커·라벨을 걸었다(1급은 애초에 郡治 마커/郡 라벨이 대표해서 항상
  숨김 — 이 부분은 그대로 유지, 회귀 없음).
- 이걸 "등급 → 최소 표시 zoom" 매핑 테이블 하나(`TIER2_MARKER_ZOOM`,
  `TIER2_LABEL_ZOOM`)로 승격하고, 조회 함수 `tierZoom(table, kind)`를 export.
  테이블에 없는 등급(1급 전부, 그리고 아직 데이터에 없는 `KINGDOM`)은 `undefined`를
  돌려주고 호출부가 "안 그림"으로 처리한다 — 郡治 마커·郡 라벨이 이미 그 자리를
  대표하고 있어서 안전한 기본값이다. `KINGDOM`이 실제로 들어와도 깨지지 않는다.
  `MARQUISATE`(侯國)는 요구사항에 있던 등급이라 테이블에 미리 추가해뒀다(현재 데이터엔
  없음, `build_han_places.py`가 侯国도 `COUNTY`로 매핑 중 — `tools/map/*`는 손대지 말라는
  지시라 그대로 둠).
- 새 등급이 늘면(州 등) 이 테이블에 한 줄만 추가하면 된다. 조건문 나열 없음.
- 성능: 매 프레임 전체 재계산이 아니라 원래 구조 그대로(스케일이 바뀔 때만 다시
  그림, `render()`가 줌/팬 콜백에서만 호출됨) — 이번 변경은 그 경로를 건드리지 않았다.

## 남은 갭 (범위 밖으로 판단해 손대지 않음)
- `cities[].kind === 'PROVINCE'`(州)인 항목은 애초에 `hubs`/`zhi`에 안 들어가서
  `c.seat`가 안 잡히고, 기존 로직상 비-seat 항목은 이 렌더러에서 아예 안 찍힌다
  (변경 전부터 그랬다). 즉 **州는 지금 지도에 전혀 안 나온다** — 이건 zoom LOD
  문제가 아니라 "州 마커를 아예 렌더링에 안 붙였다"는 별개 기능 갭이다. 이번
  티켓 범위(등급별 zoom 문턱)를 벗어나 손대지 않았다.
- `KINGDOM`/`MARQUISATE`는 `jun-guo-split` 레인이 데이터를 채우면 이 코드가 그대로
  받아 동작한다(테이블에 값 추가만 하면 됨). 지금은 값이 없어 안전한 fallback으로
  빠진다.

## 검증
- `web/game`: `npm run typecheck` — 통과(에러 없음).
- `npm run lint` — 기존 파일들의 사전 존재 경고만(HanMapCanvas.tsx 관련 경고 없음).
- `npx vitest run` — 76 test files / 433 tests 전부 통과, 그중
  `__tests__/HanMapCanvas.test.ts`에 `tierZoom` 매핑 테스트 2개 추가
  (테이블에 있는 등급 반환값 확인 + `COMMANDERY`/`KINGDOM`/`PROVINCE` 같은 미등록
  등급이 `undefined`로 안전하게 빠지는지 확인).

## 손댄 파일
- `web/game/components/game/HanMapCanvas.tsx`
- `web/game/__tests__/HanMapCanvas.test.ts`

`tools/map/*`, `data/curated/han/*`는 건드리지 않았다(jun-guo-split 레인과 충돌 방지).

## 후속 수정 (독립 리뷰 fix-required 대응)

첫 리뷰 라운드(critic-506)가 "동치성 확인 = 이미 있던 동작"이라는 사실 자체를
내가 보고서에 명시하지 않았다고 지적했다. 실측 결과, 실제 `data/map/han-tiles.json`
기준 郡治 175개의 최근접 거리 중앙값이 14칸이라 `initialView()`가 계산하는 첫 화면
배율이 거의 모든 실사용 뷰포트에서 `MAX_SCALE=14`(상한)에 붙어버렸다 — 즉 縣 문턱
로직 자체는 옳았지만 **첫 화면이 이미 그 문턱을 한참 지나서 시작**해 사용자가
차등을 체감할 수 없었다. 팀 리드가 방향 (a)(초기 배율을 낮춘다, 縣 문턱은 그대로)를
확정해 세 가지를 고쳤다:

1. **B1(a)** `initialView()`가 계산한 배율을 `MIN_TIER2_MARKER_ZOOM - 0.1`(=2.1)로
   상한을 씌운다. `scale`은 dpr 이 곱해진 백버퍼 픽셀 기준으로 렌더 전체가 이미
   일관되게 쓰고 있는 좌표계라(휠 줌 핸들러도 `* dpr`), 이 상한도 같은 좌표계에서
   걸어야 dpr 과 무관하게 항상 성립한다 — dpr 1/2 양쪽, 5개 대표 뷰포트에서 실측
   `2.100 < 2.2` 확인(아래 검증 항목).
2. **B2** 縣 라벨 루프에서 지웠던 조기 탈출 가드(`if (s >= 문턱) { ... }`)를
   `MIN_TIER2_LABEL_ZOOM` 기준으로 복원 — 그 문턱 아래서는 1144개 city 순회와
   `ctx.font`/`fillStyle` 세팅을 통째로 건너뛴다. 매직넘버 제거(구조 리팩터)와
   조기 탈출 가드(성능) 는 별개인데 하나로 묶어 지운 실수였다.
3. **M3** `MARQUISATE` 관련 주석을 "테이블에 있다"가 아니라 "현재 데이터엔 0건,
   `build_han_places.py:60`의 `'侯国': ('COUNTY', 5)`가 안 바뀌는 한(PR #507 도
   이 줄은 그대로다) 전방 호환용으로만 존재한다"로 사실과 맞춰 고쳤다.

## 검증(재확인)
- `npm run typecheck` — 통과.
- `npm run lint` — 기존 경고만, 신규 경고 없음.
- `npx vitest run` — 76 files / 433 tests 통과(1회는 `HanMapCanvas`와 무관한
  `GeneralBasicCard`류 테스트가 flake로 실패, 단독 재실행 및 풀스위트 재실행
  모두 그린으로 재현 확인).
- 초기 배율 실측(Node 스크립트로 `junSpanCells`/`scaleForSpan` 재구현, 실제
  `data/map/han-tiles.json` 사용): 800/1280/1600/1920 css 폭 × dpr 1·2 조합
  전부 최종 배율 2.100, `< 2.2` 성립.

## 3차 수정 — (b) fitScale 상대 문턱 + (a) 재조정 (팀 리드 지시 정정)

팀 리드가 B1 지시를 정정했다: 절대 문턱(2.2/5.5)은 `fitScale(w,h,g)`(화면·DPR 에 따라
달라지는, "완전히 줌아웃"했을 때의 배율) 과 무관한 고정값이라, 화면이 넓고 고DPI일수록
`fitScale` 자체가 문턱을 넘어버릴 수 있다 — 실측: 1600css@2x 백버퍼 기준 `fitScale ≈ 2.23
≥ 2.2` 라 **그 화면에선 완전히 줌아웃해도 縣이 절대 안 사라진다.** (a)만으로는 이 화면
크기 의존 결함을 못 고친다. 그래서 (b) 를 먼저, (a) 를 그 위에 얹는다.

**(b)** `TIER2_MARKER_ZOOM`/`TIER2_LABEL_ZOOM` 을 절대 scale 이 아니라 `fitScale` 배수
K 로 바꿨다(2.2→2.19, 5.5→5.48 — 컨테이너 max-width 1440×800 대표 뷰포트에서
`fitScale≈1.0035` 로 역산해 기존 절대값 체감을 보존). `tierZoom(table, kind, fit)`
이 이제 `K * fit` 을 돌려준다. K>1 이므로 문턱 = K·fitScale > fitScale 이 **어떤
화면·DPR 에서도 수학적으로 성립** — "완전 줌아웃 = 반드시 1급만" 이 보장된다.
draw() 는 fitScale 을 프레임당 한 번만 계산해 재사용한다(추가 재계산 없음).

**(a)(재조정)** `initialView` 의 상한을 `MIN_TIER2_MARKER_ZOOM - 0.1`(고정 뺄셈) 대신
`INITIAL_SCALE_MARGIN(0.9) * (MIN_TIER2_MARKER_ZOOM * fitScale(w,h,g))` 비율로
바꿨다 — 문턱 자체가 화면마다 다른 값이라 고정 뺄셈보다 비율이 (b) 와 일관된다.

### 검증 — 대표 4+1 뷰포트, 실제 데이터 기준

코드 상수(K_MARKER=2.19, MARGIN=0.9)를 그대로 써서 재계산(`data/map/han-tiles.json`,
juns 175개, `junSpanCells` 중앙값 14):

| css×dpr | fit | markerThresh(K·fit) | 초기 scale(상한 적용) | 초기<문턱 | 문턱>fit |
|---|---|---|---|---|---|
| 800×424@1 | 0.5575 | 1.2209 | 1.0988 | ✅ | ✅ |
| 1280×678@1 | 0.8920 | 1.9534 | 1.7581 | ✅ | ✅ |
| 1280×678@2 | 1.7840 | 3.9069 | 3.5162 | ✅ | ✅ |
| 1920×1018@2 | 2.6760 | 5.8603 | 5.2743 | ✅ | ✅ |
| 1600×848@2 | 2.2300 | 4.8836 | 4.3953 | ✅ | ✅ |

1600×848@2 행이 이전 절대-문턱(2.2) 결함이 재현되던 지점이다 — `fit=2.2300` 이
구 문턱 2.2 를 이미 넘겨 縣이 절대 안 숨었었는데, 새 문턱은 `4.8836`(fit 의 2.19배)이라
여전히 성립한다.

### 검증(공통)
- `npm run typecheck` — 통과.
- `npm run lint` — 신규 경고 없음.
- `npx vitest run` — 76 files / 434 tests 통과(신규 3번째 테스트 추가: 임의 fit 값에서
  문턱이 항상 fit 자체보다 크다는 (b) 의 핵심 보장을 직접 검증).

## #507 상황 — METROPOLITAN 철회 반영
critic-507 가 근거를 뒤집었다: 河南尹·京兆尹·左馮翊·右扶風 4개 전부 `COMMANDERY` 로
되돌아간다(`METROPOLITAN` 신설 자체가 철회). 이 파일이 다루는 `tools/map/*` 는 여전히
손대지 않았고, `SEAT_KINDS` 최종형이 `{'COMMANDERY','KINGDOM'}` 이 되더라도 렌더러
쪽 결론(KINGDOM 20건이 `seat`/`juns[]` 대표 경로를 타 `tierZoom` 미등록이어도 회귀
없음)은 그대로 유효하다 — 이 렌더러는 `METROPOLITAN` 문자열을 아예 참조하지 않는다.

## 3차 독립 재검증에서 발견된 반대쪽 결함 — 라벨 문턱도 fitScale 배수로 하면 안 됐다

3차 수정을 별도 code-reviewer 서브에이전트로 재검증(자기승인 아님)했더니, 라벨 문턱까지
`K·fit` 로 바꾼 것 자체가 새 결함이었다: `MAX_SCALE=14`(isoMap.ts)는 여전히 절대값인데
라벨 K(5.48)를 넓고 고DPI인 화면(예: 1920css@2x, fit≈2.68)에 곱하면 `K·fit`이 14를
넘어버려 — 그 화면에서는 **아무리 줌인해도 縣 이름이 영원히 안 뜬다.** 마커 쪽 결함(줌아웃
해도 안 사라짐)을 고치다가 반대쪽(줌인해도 안 뜸)을 새로 낸 것이다.

원인: 마커 문턱과 라벨 문턱은 목적이 다르다. 마커는 "완전 줌아웃에서 사라지는가"라 fitScale
배수(K>1)가 정답이지만, 라벨은 "縣 970개 이름이 화면에서 겹치지 않는가"라는 **절대 밀도**
문제라 fitScale 과 무관해야 한다.

**수정**: `TIER2_LABEL_ZOOM` 은 절대값(5.5)으로 되돌리고, 실제 라벨 문턱은
`labelZoomFor(kind, fit) = min(MAX_SCALE - 0.5, max(TIER2_LABEL_ZOOM[kind], 마커 문턱))`
로 계산한다 — "라벨은 마커보다 먼저 뜰 수 없다"는 구조적 제약만 마커 문턱에서 가져오고,
절대 밀도 하한은 유지하며, `MAX_SCALE` 아래 여유(0.5)로 도달 불가능을 막는다.

### 검증
- `npm run typecheck` — 통과.
- `npm run lint` — 신규 경고 없음.
- `npx vitest run` — 76 files / 436 tests 통과. 신규 테스트: 좁은 화면에서 라벨 문턱이
  절대값을 그대로 쓰는지, 넓고 고DPI인 화면(fit 최대 5, 1440 컨테이너 기준 dpr≈5 —
  실사용 하드웨어를 훌쩍 넘는 여유)에서도 `labelZoomFor < MAX_SCALE` 이 항상 성립하고
  라벨 문턱이 마커 문턱보다 낮아지지 않는지 검증.
- 3차 독립 재검토: `docs/superpowers/reviews/2026-08-24-han-map-zoom-lod-fitscale-relative-reverify.md`
  (라벨 결함 fix-required로 잡아냄) → 위 수정 반영 후 재확인 필요.

## 4차 — PR #506 머지 경합 발견 + 후속 PR #512

3차 수정(fitScale 상대 마커 문턱 + 절대·클램프 라벨 문턱, 커밋 `18849f15`) 을 독립
재검토(`2026-08-24-han-map-zoom-lod-label-threshold-reverify.md`, Verdict: cleared)까지
마치고 나서 `gh run list` 로 확인해 보니, **저장소 소유자(peppone-choi)가 이미 PR #506 을
직접 머지했다**(`2026-08-23T15:03:51Z`, 머지 커밋 `fdc297fd`). 머지 시점이 이 3차 작업
완료 시점보다 앞서서, `fdc297fd` 에는 2차까지의 절대 문턱 버전(`90eb5347`)만 들어가고
3차 fitScale-상대화 + 라벨 수정(`18849f15`)은 빠졌다. **이 머지는 내가 한 게 아니다** —
"머지 금지" 지시는 계속 지켰다.

`main` 이 이미 #506 을 흡수해 닫혀서, 3차 수정을 올릴 새 PR **#512**
(`work/opensamguk/han-map-zoom-lod` → `main`) 를 열었다. `git diff origin/main...HEAD` 로
확인한 diff 는 6개 파일(HanMapCanvas.tsx, 3·4차 리뷰 문서 2개, 리포트, 테스트) 588
추가/14 삭제로 깨끗하고 충돌 없음. PR 설명에 머지 경합 경위와 (b)+(a)+라벨 수정 전체
요약·검증 계획을 적었다.

### CI 트리거 이상 — 미해결, 보고만 함
PR #512 를 열고 상당 시간이 지나도 `.github/workflows/ci.yml` (jvm / web (game) /
web (gateway) / agent-system) 이 **한 번도 트리거되지 않았다** — `CodeRabbit` 만 붙었다
(`state: success`, `description: "Review rate limited"`). 확인한 것:
- `gh api .../actions/permissions` → Actions 활성화 상태(`enabled: true`).
- `ci.yml` 의 `on:` 은 `pull_request:` 필터 없음 — #506 에서는 동일 트리거로 정상 작동했다
  (동일 브랜치, 직전 커밋들에서 CI 4개 잡 전부 성공/실패 기록 있음).
- PR #512 를 close → reopen 해 재트리거를 시도했으나 그래도 새 run 이 안 생겼다
  (`gh api .../actions/runs` 로 커밋 `18849f15` 관련 run 0건 확인).

원인 미상 — 내 쪽에서 diff/구성을 건드린 결과가 아니라 GitHub 플랫폼/CI 트리거 쪽
문제로 보인다. **CI 미확인 상태이므로 머지 여부 판단은 팀 리드/저장소 소유자 몫으로
넘긴다.** 억지로 CI green 이라 보고하지 않는다.

## 5차 — 새 브랜치 `han-map-lod-fitscale-relative` (#512 대체)

팀 리드가 #506 을 머지했다(15:03:51Z, `gh pr merge --squash` — mergedBy 가 항상 계정
공유로 `peppone-choi` 로 찍히는 건 저장소 구조상 정상이고 사람이 직접 눌렀다는 뜻이
아니다, 팀 리드 확인). #512 는 그 뒤 `origin/main` 과 `mergeStateStatus: DIRTY /
mergeable: CONFLICTING` 상태가 됐다 — squash 커밋(`fdc297fd`)과 #512 브랜치에 남아있던
squash-전 개별 커밋들이 겹쳐서다. 지시대로 새 브랜치
`work/opensamguk/han-map-lod-fitscale-relative` 를 `origin/main` 에서 새로 파고, #512
전용 델타 커밋 2개(`18849f15` fitScale 상대화+라벨 수정, `443fdbf0` 리포트)만 깨끗하게
cherry-pick 했다 — 충돌 0.

### K 값 실측 근거 (1440×800 css, dpr=1 — 대표 뷰포트)
```
fitScale(1440,800,grid) = 1.0034843205574913
marker: K=2.19 * fit = 2.197631  vs 구 절대값 2.2   diff = -0.002369 (-0.108%)
label : K=5.48 * fit = 5.499094  vs 구 절대값 5.5   diff = -0.000906 (-0.016%)
```
주석의 "역산" 주장은 실측으로 확인됐다(오차 0.1%/0.02% 이내) — K 값을 고칠 필요 없음.

### 문턱 불변식 실측표 — 5 뷰포트 × 2 dpr (실제 data/map/han-tiles.json, 768×669 격자)
| css | dpr | fit | markerThresh(K·fit) | initScale | init<thresh | fit<thresh |
|---|---|---|---|---|---|---|
| 800×600 | 1 | 0.5575 | 1.2209 | 1.0988 | ✅ | ✅ |
| 800×600 | 2 | 1.1150 | 2.4418 | 2.1976 | ✅ | ✅ |
| 1280×800 | 1 | 0.8920 | 1.9534 | 1.7581 | ✅ | ✅ |
| 1280×800 | 2 | 1.7840 | 3.9069 | 3.5162 | ✅ | ✅ |
| 1600×900 | 1 | 1.1150 | 2.4418 | 2.1976 | ✅ | ✅ |
| 1600×900 | 2 | 2.2300 | 4.8836 | 4.3953 | ✅ | ✅ |
| 1920×1080 | 1 | 1.3380 | 2.9302 | 2.6372 | ✅ | ✅ |
| 1920×1080 | 2 | 2.6760 | 5.8603 | 5.2743 | ✅ | ✅ |
| 3013×1200 | 1 | 1.6725 | 3.6627 | 3.2964 | ✅ | ✅ |
| 3013×1200 | 2 | 3.3449 | 7.3254 | 6.5929 | ✅ | ✅ |

10/10 조합 모두 (a) `initScale < markerThresh`, (b) `fit < markerThresh` 성립. (b) 는
`markerThresh = K·fit`, `K=2.19>1` 이라 대수적으로 항상 성립하지만, 팀 리드 요청대로 실제
데이터·실제 함수로 재확인했다. 이 표는 임시 스크립트(비커밋)로 먼저 뽑고, 그 다음
`export function initialView`(HanMapCanvas.tsx) 를 노출해 **실제 프로덕션 함수를 그대로
호출하는 vitest 케이스**로 옮겨 `__tests__/HanMapCanvas.test.ts` 에 박았다 — 재구현이
아니라 실제 함수 호출로 증명한다.

### 검증(숫자)
- `npm run typecheck` — 통과, 0 errors.
- `npm run lint` — 경고 6건, 전부 이번 변경과 무관한 기존 파일(generals/select-pool/tournament
  페이지, SelectRecruitField, GeneralBasicCard) — HanMapCanvas.tsx/테스트 파일 신규 경고 0.
- `npx vitest run` — **78 files / 443 tests 통과** (신규 뷰포트×dpr 증명 테스트 1개 추가,
  `HanMapCanvas.test.ts` 자체는 10/10).
- `tools/agent-system/check.py --strict --base origin/main` — **Errors: 0, Warnings: 0**
  (changed files 4).
- 독립 리뷰: 아직 새 브랜치 기준으로 다시 안 띄웠다 — 코드 diff 자체는 #512 때 이미 두 번
  독립 재검토(cleared)를 거친 것과 동일하고, 이번에 추가된 건 순수 검증 강화(테스트 1개 +
  `initialView` export)뿐이다. 그래도 팀 리드 지시대로 별도 독립 리뷰어를 새로 띄운다.

## 6차 — PR #515 독립 재검토 + CI

- `work/opensamguk/han-map-lod-fitscale-relative` → `main`, PR #515:
  https://github.com/peppone-choi/opensamguk/pull/515 (#512 는 닫고 이 PR 을 가리키게 코멘트).
- 별도 code-reviewer 서브에이전트를 독립 검토자로 새로 띄웠다(직접 critique 안 씀). diff 를
  스스로 읽고 tsc/vitest/check.py 를 직접 재실행해 다음을 재확인:
  - 1600css@2x 옛 결함 실측 재현(`fitScale=2.2300 ≥` 구 절대값 2.2) — 프로즈 신뢰 없이 직접 계산.
  - `K·fit > fit` (K=2.19>1) 대수 보장 — 런타임 가드는 없지만 테스트 2개가 K≤1 이면 fail.
  - 라벨 문턱 손계산(fit=5): `min(13.5, max(5.5, 10.95)) = 10.95 < MAX_SCALE(14)` — 도달 가능.
  - `fitScale` 는 `draw()` 당 1회만 호출(HanMapCanvas.tsx:189, 루프 밖) — perf 가드 회귀 없음.
  - K=2.19 역산 근거: `2.2 / (1440/1435) = 2.1923` — 오차 0.1% 이내.
  - 새 뷰포트×dpr 테스트가 절반은 기존 대수 테스트와 중복이지만, `initialView(...).scale` 쪽은
    실제 175개 郡治 데이터·`junSpanCells`·`scaleForSpan`·`clampView` 를 다 타는 유일한 회귀
    가드라 유지할 가치 있음 — 판단.
  - 자체 실행 결과: `tsc --noEmit` 0 errors / `vitest run __tests__/HanMapCanvas.test.ts` 10/10 /
    `vitest run`(전체) 443/443 / `check.py --strict` Errors 0, Warnings 0.
  - **Verdict: cleared.** LOW 5건(런타임 K>1 가드 없음, dpr>6.16/6.39 극단에서만 발생하는
    라벨/마커 역전·도달불가, resize 시 scale 재클램프 안 함(의도된 상대-LOD 의미론), draw 당
    사소한 할당) — 전부 비차단.
  - 리뷰 파일: `docs/superpowers/reviews/2026-08-24-han-map-lod-fitscale-relative-pr515-review.md`.
- `tools/agent-system/check.py --strict --base origin/main` 재확인 — Errors 0, Warnings 0
  (changed files 5, 리뷰 파일 포함).
