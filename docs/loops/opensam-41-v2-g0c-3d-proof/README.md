# OPENSAM-41 [G0-C] 3D 공간 증명 — 실측 기록

레인 D. 브랜치 `op-41-g0c-3d-proof`, base `d63f6fec`.
파일 범위: `web/game/**` 만. 백엔드(`app/**`, `infra/**`, `logic/**`, `common/**`) 무접점.

## 1. 산출물 대응표

| 항목 | 구현 | 검증 |
| --- | --- | --- |
| G0C-a 정사영 scene (도시3·route2·terrain1) | `components/v2/SpaceProof3D.tsx`, `lib/v2/terrain/fixtures.ts` `demoScene()` | `__tests__/v2-space-proof.test.ts` + e2e 캔버스 스크린샷 |
| G0C-b 공유 picking 왕복 | `lib/v2/terrain/projection.ts` `roundTripPlace` | 데모 3 + synthetic 2,000 전량 오류 0 · 브라우저 클릭 왕복 |
| G0C-c 작전 경로 왕복 | `roundTripOperationPath` | corridor 시퀀스 → 집기 → 같은 작전 복귀 |
| G0C-d 전장 anchor 왕복 | `roundTripBattlefieldAnchor` | 역투영 오차 < 1e-6 · projectionVersion 불일치 거부 |
| G0C-e replay camera 왕복 | `serializeCamera`/`deserializeCamera`/`projectionFingerprint` | 복원 후 화면 배치 지문 동일 |
| G0C-f WebGL 불가 fallback | `components/v2/SpaceFallbackTable.tsx` | `__tests__/v2-space-fallback.test.tsx` (jsdom 실제 감지 경로) + e2e |
| G0C-g 2,000 거점 4-class count | `lib/v2/terrain/lod.ts` `auditBudget` | 1,200/200/500/100 · 합계 2,000 · 다중class/무분류/비장소/키복제 0 |
| G0C-h catalog LOD Tier A/B/C | `auditCatalogTiers`, `syntheticCatalog` | 120/380/1,500 |
| G0C-i runtime LOD 4종 독립축 | `selectRuntimeLod`, `planRender`, `auditLodIndependence` | 4 LOD × 3 tier 전부 identity 유실·중복 0 |
| G0C-j 실제 2,000 catalog 동일 검사 | `auditCatalog` (synthetic과 동일 함수) | **BLOCKED** — §4 참조 |
| G0C-k 60/30 FPS | `lib/v2/terrain/telemetry.ts` + `e2e/v2-space-fps.spec.ts` | §3 실측 |

지형 모델 계약 T1-G01~G12는 `lib/v2/terrain/contracts.ts`에 타입으로 고정했다
(RouteCorridor / 강 수송로 분리 / TerrainRegion / catalog·runtime LOD / BattlefieldSeed /
TerrainPatch / 좌표·projectionVersion / 렌더·fallback / uncertainty 구분).

## 2. 측정 방법

- 렌더러가 `requestAnimationFrame` 간격을 직접 모아(`FrameCollector`) `window.__v2Space.telemetry`로
  노출하고, Playwright가 그 값을 읽는다. 브라우저 바깥에서 추정한 수치는 없다.
- 워밍업 30프레임은 버린다(셰이더 컴파일·첫 업로드 히치가 기동 비용을 렌더 성능으로 오염시킨다).
  이후 구간의 스파이크는 그대로 남으며 `worstFrameMs`에 드러난다.
- 판정은 **프레임 시간 백분위**로 한다. rAF는 디스플레이 주사율에 묶여 60을 넘을 수 없으므로
  평균 FPS는 상한이 곧 목표가 되고, 단발 히치 하나에 평균이 무너져 게이트가 무의미해진다.
- 실행:
  ```bash
  cd web/game
  V2_ENABLED=true pnpm exec next dev -p 3131
  E2E_GAME_URL=http://localhost:3131 pnpm exec playwright test e2e/v2-space-fps.spec.ts
  ```

### 측정 조건 (원시 수치의 유효 범위)

| 항목 | 값 |
| --- | --- |
| 일시 | 2026-08-16 |
| 호스트 | macOS (Darwin 25.5.0), Apple Silicon |
| 브라우저 | Playwright 1.52.0 Chromium, **headless** |
| 서버 | `next dev` (webpack, 프로덕션 빌드 아님) |
| 캔버스 | 1024×768, `setPixelRatio(1)` 고정 |
| 부하 | `animate=1` — 매 프레임 azimuth 회전 → 전 인스턴스 행렬 재계산 |

한계: headless Chromium은 GPU 구성에 따라 SwiftShader 소프트웨어 래스터라이저를 쓸 수 있고,
`next dev` 빌드는 프로덕션보다 무겁다. 아래 수치는 **이 조건에서의 실측**이며 실기기 성능의
상한/하한 보증이 아니다.

## 3. FPS 실측 원시 수치

`web/game/test-results/v2-space-fps.json` 원본 그대로:

| scene | LOD | 거점 | 프레임 | 경과(ms) | 평균 FPS | p50(ms) | p95(ms) | 최악(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| demo | FULL_SCENE | 3 | 209 | 3483.3 | **60.0006** | 16.700 | 16.700 | 16.800 |
| synthetic | SYMBOL | 2,000 | 209 | 3549.9 | **58.875** | 16.700 | 16.800 | 83.200 |
| synthetic | CLUSTER | 2,000 (825 클러스터) | 134 | 2233.2 | **60.0036** | 16.700 | 16.700 | 16.800 |

- 60 FPS 게이트(demo): p50 ≤ 16.9ms, p95 ≤ 17.5ms → **통과** (둘 다 16.700ms).
- 30 FPS 게이트(synthetic 2,000): p95 ≤ 33.3ms → **통과** (16.800ms). 여유 약 2배.
- 캔버스 픽셀: 스크린샷 1024×768 = 786,432px, 고유 색 **144**, 배경 점유 **99.15%**
  (단색 = 빈 화면 아님, 전면 도형 = 오염 아님). 스크린샷: `web/game/test-results/v2-space-demo.png`.

## 4. 미해결 / blocker

1. **G0C-j 실제 2,000 source catalog — BLOCKED.**
   리포에 있는 유일한 v2 카탈로그는 v1 지도 94 도시(`infra/src/main/resources/content/v2/cities_1010.json`,
   `cityCount: 94`)다. 2,000 거점 카탈로그는 G0A-h/i(군·국 105 + 현·읍·도·후국 1,180)와
   G0B-n/o(주변 240 / 주변 물리 장소 500)의 산출물이며 아직 없다.
   `__tests__/v2-space-source-catalog.test.ts`가 이 사실을 단언으로 고정하고, 카탈로그가
   나타나면 synthetic과 **같은 함수**(`auditCatalog`)로 자동 검사한다. 수치는 지어내지 않았다.
2. **연대기 모드** 판정도 같은 이유로 미측정(UNKNOWN) — 실제 catalog에 `chronicleOnly` 항목이 없다.
3. **프로덕션 빌드 FPS 미측정.** 위 수치는 `next dev` 기준이다.
4. **기존 실패 1건은 이 작업과 무관.** `web/game/__tests__/live-noop-closures.test.tsx`의
   `선택 풀 즉시 반영` 테스트는 base(`d63f6fec`)에서도 동일하게 실패한다(stash 후 재현 확인).

## 5. 격리(OPENSAM-35) 관련 결정 — 리뷰 필요

OPENSAM-35의 §7.6 격리 판정은 **"v2-lab은 server-only라 production 클라이언트 청크가
556B 빈 스텁"** 이라는 전제 위에 서 있었다(`__tests__/v2-lab-route.test.tsx` 말미 주석이
"v2-lab이 외부 클라이언트 컴포넌트를 import하면 이 스캔은 통과해버린다"고 스스로 한계를 명시).

G0-C의 Three.js scene은 필연적으로 `'use client'`다. 그대로 두면 middleware matcher 밖인
`/_next/static/**`로 v2 코드가 HTTP 200으로 나가 그 전제가 깨진다. **문서화된 한계를 조용히
이용하지 않고** 전제를 복원했다:

- `web/game/lib/v2/buildIsolation.mjs` + `next.config.mjs` webpack 훅이
  `V2_ENABLED !== 'true'` 빌드에서 `components/v2/**` import를 빈 스텁(`lib/v2/clientDisabled.tsx`)으로
  치환한다 → v2 클라이언트 코드가 번들 그래프에 아예 들어가지 않는다.
- `middleware.ts`는 **수정하지 않았다**(다른 레인 소유). 이건 빌드 측 보강이다.
- 한계: webpack 빌드에만 적용된다. `next build --turbopack`으로 옮기면 동등한 turbopack 규칙이
  필요하다. 현재 `package.json`의 build는 plain `next build`(webpack)다.

프로덕션 빌드 실측 (`pnpm build`, V2_ENABLED 미설정, 2026-08-16):

```
.next/static/chunks/app/game/v2-lab/space/page-52c5d7a392bba058.js  = 658 B
  49275: d.d(n,{SpaceProof3D:()=>s}); let s=function(){return null}
grep -rl "WebGLRenderer|BufferGeometry|InstancedMesh" .next/static/  → 0 파일
grep -rl "v2-space-canvas|v2-space-fallback"          .next/static/  → 0 파일
```

three.js도 scene 코드도 클라이언트 번들에 없다. 청크에 남는 `SpaceProof3D`는 export 이름뿐이고
본문은 `return null` 스텁이다. OPENSAM-35 §7.6의 "556 B 빈 스텁" 전제가 그대로 유지된다(658 B).

대조군 — 같은 명령을 `V2_ENABLED=true`로 빌드하면 실제 코드가 들어온다(플러그인이 항상
스텁으로 만드는 게 아님을 증명):

```
page-fd6302c2a3a718a1.js = 8,586 B   (스텁 658 B 대비 13배)
grep -rl "InstancedMesh"   .next/static/ → chunks/81570add-…, chunks/53d2aca8-…  (three.js)
grep -rl "v2-space-canvas" .next/static/ → app/game/v2-lab/space/page-…
```

라이브 확인 (`next dev`, 2026-08-16):

| 경로 | V2_ENABLED 미설정 | V2_ENABLED=true |
| --- | --- | --- |
| `/game/v2-lab` | 404 | 200 |
| `/game/v2-lab/space` | 404 | 200 |
| `/game/rankings` | 200 | 200 |
