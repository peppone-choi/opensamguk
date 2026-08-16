# OPENSAM-41 (레인 D) G0-C 3D 공간 증명 — 독립 적대적 비평

Date: 2026-08-16

Scope: web/game v2 3D 공간 증명(OPENSAM-41 G0-C) — `web/game/**` 신규 15파일 + `next.config.mjs`/`package.json`/`pnpm-lock.yaml` 수정 + 루프 문서. 브랜치 `op-41-g0c-3d-proof`, base `origin/main` (`dcb205d8`).

Reviewer: 독립 비평 에이전트. 코드 작성자가 아니며, 작성자가 신고한 수치는 하나도 그대로 받지 않고 이 호스트에서 전부 재실행했다.

---

## 1. 최우선 — 빌드 격리를 직접 재현했다

작성자 주장: OPENSAM-35 §7.6의 "v2-lab은 server-only라 production 클라이언트 청크가 556 B 빈
스텁"이라는 전제가 Three.js scene의 `'use client'` 때문에 깨지므로, `next.config.mjs` +
`lib/v2/buildIsolation.mjs` webpack 훅으로 `components/v2/**` import를 스텁으로 치환해 전제를
복원했다.

### 1.1 재현 — 프로덕션 빌드 (V2_ENABLED 미설정)

```
cd web/game && rm -rf .next && pnpm build     # next 15.5.20, webpack, BUILD 성공
```

| 검사 | 명령 | 결과 |
| --- | --- | --- |
| v2 페이지 청크 | `ls .next/static/chunks/app/game/v2-lab/space/` | `page-a60c3e311e865f23.js` **658 B** |
| 청크 본문 | `cat` 위 파일 | `d.d(n,{SpaceProof3D:()=>f});let f=function(){return null}` |
| three.js 클래스명 | `grep -rlE "WebGLRenderer\|InstancedMesh\|BufferGeometry\|OrthographicCamera\|RingGeometry" .next/static/` | **0 파일** |
| three.js 셰이더/내부 리터럴 | `grep -rlE "gl_FragColor\|varying vec\|THREE\.WebGL\|instanceMatrix\|morphTarget\|srgb-linear" .next/static/` | **0 파일** |
| v2 마커·fixture 문자열 | `grep -rl "v2-space-canvas\|v2-space-fallback\|합성거점\|place:luoyang" .next/static/` | **0 파일** |
| 소스맵 | `find .next/static -name "*.map"` | **0 파일** (SENTRY_AUTH_TOKEN 없으면 업로드/생성 비활성) |
| 라우트 표 | build 출력 | `/game/v2-lab/space` 395 B · First Load 187 kB = 다른 사소 페이지와 동일 공용 베이스라인 |

작성자가 신고한 청크 해시(`52c5d7a3…`)와 내 해시(`a60c3e31…`)는 다르다. 원인은 Sentry가 빌드마다
주입하는 `_sentryDebugIds` UUID이며, **크기 658 B와 본문은 바이트 단위로 동일**하다. 수치 위조가 아니다.

**클라이언트 번들에 three.js는 0바이트다. 격리 주장은 버텼다.**

### 1.2 재현 — 실제 HTTP (프로덕션 서버, V2_ENABLED 미설정)

```
node_modules/.bin/next start -p 3131      # 위에서 만든 빌드 그대로
```

| 경로 | 코드 |
| --- | --- |
| `/game/v2-lab` | **404** |
| `/game/v2-lab/space` | **404** |
| `/game/rankings` | 200 |
| `/_next/static/chunks/app/game/v2-lab/space/page-….js` | 200 — 본문은 `return null` 스텁 |

정적 자산 경로가 middleware matcher 밖이라 200으로 나가는 것은 사실이고, 나가는 내용이
빈 스텁이라는 것이 정확히 이 티켓이 복원한 전제다.

### 1.3 우회 경로 공격 — 3건 시도, 2건이 실제 구멍이었다

| 공격 | 결과 |
| --- | --- |
| **npm 패키지 경로 충돌** — `V2_CLIENT_REQUEST = /(^\|[./])components\/v2\//`가 서드파티 요청을 잡아 v1 빌드를 망가뜨리는가 | `grep -rl "components/v2/" node_modules/` → **0 hit**. v1 프로덕션 빌드는 성공했고 라우트 표·공용 청크 구성에 변화 없음. **버텼다.** |
| **동적 import 우회** — `import('@/components/v2/…')` | webpack `normalModuleFactory.beforeResolve`는 정적/동적 import를 구분하지 않으므로 동일하게 치환된다. 실측 빌드에서 v2 코드가 어떤 청크로도 나오지 않음으로 간접 확인. **버텼다.** |
| **`components/v2` 밖 클라이언트 컴포넌트가 `lib/v2/**`를 직접 import** | 스텁은 `components/v2/`만 가로챈다. 오늘은 그런 importer가 0건이지만 **아무것도 막지 않고 있었다.** → 소스 스캔 테스트 추가(§3-B) |
| **`next.config.mjs`에서 훅을 떼어내기** | `shouldStubV2Client` 단위 테스트만 있었고 **배선 자체는 아무 테스트도 걸지 않았다.** 훅을 지워도 369개 테스트 전부 초록이고 three.js가 프로덕션으로 나간다. → 배선 테스트 추가(§3-A), 훅 제거 mutation으로 빨개지는 것 확인 |
| **RSC payload로 fixture 유출** | `app/game/v2-lab/space/page.tsx`는 서버 컴포넌트이고, 스텁 상태에서도 클라이언트 경계에 props를 직렬화하긴 한다. 다만 `V2LabLayout`의 `notFound()` + middleware 404가 렌더 자체를 막으므로 payload가 생성되지 않는다(§1.2 실측 404). **오늘 버텼다** — 단, 이 방어는 빌드 격리가 아니라 라우트 게이트가 담당한다는 점을 문서에 남겼다. |

---

## 2. 그 외 항목 재현

### 2.1 FPS — 직접 재측정했다 (베끼지 않았다)

```
V2_ENABLED=true next dev -p 3132
E2E_GAME_URL=http://localhost:3132 playwright test e2e/v2-space-fps.spec.ts   # 6 passed (48.4s)
```

이 호스트(macOS Darwin 25.5.0, Apple Silicon, Playwright 1.52.0 Chromium headless, `next dev`,
1024×768, `setPixelRatio(1)`, `animate=1`) 실측:

| scene | LOD | 거점 | 프레임 | 평균 FPS | p50(ms) | p95(ms) | 최악(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| demo | FULL_SCENE | 3 | 209 | 60.0023 | 16.700 | 16.700 | 16.800 |
| synthetic | SYMBOL | 2,000 (마커 2,000) | 209 | 60.0023 | 16.700 | 16.700 | 16.800 |
| synthetic | CLUSTER | 2,000 (클러스터 825) | 134 | 60.0009 | 16.700 | 16.700 | 16.800 |
| demo 픽셀 히스토그램 | — | — | — | 고유색 **144** | 배경 점유 **0.99151** | | |

작성자 수치와의 차이: synthetic SYMBOL이 작성자 실행에서는 58.875 FPS / 최악 83.2ms였고 내
실행에서는 60.0023 / 16.8ms다. 단발 히치의 유무 차이이며 게이트 판정(p95 ≤ 33.3ms)은 양쪽 모두 통과다.
**수치는 하드코딩이 아니다** — `test-results/v2-space-fps.json`은 gitignore 대상이라 리포에 없었고,
내 실행이 브라우저 rAF 실측으로 새로 생성했다.

게이트 설계 검증:
- 판정은 **프레임타임 백분위**(p50/p95)이고 평균 FPS는 보조 하한이다. 평균만 보는 게이트가 아니다.
- 워밍업 제외는 `FrameCollector(windowSize=240, warmupFrames=30)`으로 **앞 30프레임만** 버린다.
  버린 뒤 구간의 스파이크는 `worstFrameMs`에 그대로 남는다(단위 테스트
  `워밍업 프레임만 버리고 이후 스파이크는 남긴다`가 90ms 스파이크 보존을 확인). 유리하게 조작하는 창이 아니다.
- **정직한 한계 (내가 추가로 지적):** 세 시나리오의 p50/p95가 모두 정확히 16.700ms다. rAF가 vsync에
  묶여 있다는 뜻이고, 이 측정은 "vsync 아래로 떨어지지 않는다"는 것만 증명하며 **헤드룸을 보여주지 못한다.**
  2,000 거점이 3 거점보다 얼마나 무거운지 이 게이트로는 알 수 없다. 30 FPS 통과가 "2배 여유"라는
  루프 문서 표현은 과장이며 §4에서 수정했다. e2e가 `worstFrameMs`를 단언하지 않는 것도 남은 느슨함이다
  (원시값은 JSON에 기록되므로 회귀 추적은 가능).

### 2.2 G0C-j BLOCKED 처리 — 허수가 아니다

`__tests__/v2-space-source-catalog.test.ts`를 읽고 판정:
- `loadSourceCatalog()`가 4개 후보 경로를 실제 `existsSync`로 확인한다. 있으면
  `expect(source.places).toHaveLength(2000)` + `auditCatalog(...)` = **synthetic과 동일 함수**로 실검사.
- 없으면 무조건 통과가 아니라 **부재를 사실로 단언한다**:
  `infra/src/main/resources/content/v2/cities_1010.json`이 존재하고 `cityCount === 94`임을 검증한다.
  나는 그 파일을 직접 파싱해 `cityCount = 94`를 확인했다. 이 파일이 사라지거나 값이 바뀌면 테스트가 빨개진다.
- 기준선 테스트(`synthetic 2,000은 공용 검사 함수를 통과한다`)가 별도로 있어 검사기 자체가 공허하지 않다.
- `auditBudget`의 자기검증 테스트(`감사기가 실제로 위반을 잡는다`)가 위반 주입 시 실제로 검출됨을 보인다.

**판정: 진짜 BLOCKED 처리다.** 다만 두 가지를 지적하고 고쳤다:
1. 주석이 "카탈로그가 착지하는 순간 BLOCKED 단언이 빨개진다"고 썼는데 사실이 아니다 —
   분기가 조용히 교체될 뿐이다. 주석을 정정했다.
2. `CANDIDATES`가 하드코딩 4경로다. 카탈로그가 그 밖에 놓이면 이 테스트는 영원히 BLOCKED로 남는다.
   그 사실을 주석에 명시해 카탈로그를 만드는 레인이 경로를 추가하도록 했다.

### 2.3 `selectRuntimeLod` 독립축 · picking 왕복 · 2,000 카운트

- `selectRuntimeLod(cameraDistance, perf)` 시그니처에 `PhysicalPlace`/`catalogTier`가 **없다.**
  타입 수준에서 catalog 축을 읽는 것이 불가능하므로 "독립축"은 주장이 아니라 구조다.
  더해 `auditLodIndependence`가 4 LOD × 3 tier 12조합 전부에서 identity 유실·중복 0을 실제로 돈다.
- picking: `SpaceProof3D`는 `three.Raycaster`를 쓰지 않고 렌더 배치와 picking이 **같은
  `lib/v2/terrain/projection.ts` `project()`**를 탄다(`layout()`도 `pickPlace()`도 동일 함수).
  `animate` 회전 시 `liveCamera.current`를 갱신하고 picking이 그 값을 읽으므로 회전 중에도 드리프트가 없다.
  브라우저 e2e가 페이지 밖에서 같은 수식을 손으로 재계산해 그 좌표를 클릭하고 `place:luoyang`이
  돌아오는 것을 확인한다(내 실행에서 통과).
- `projectionVersion` 불일치는 조용히 통과하지 않는다: `assertSameProjection`/`assertSupportedProjection`이
  `ProjectionVersionMismatchError`를 **throw**하고, `roundTripPlace`/`roundTripOperationPath`/
  `roundTripBattlefieldAnchor`/`deserializeCamera` 네 진입점 모두 앞단에서 호출한다.
  테스트 4건이 `toThrow(ProjectionVersionMismatchError)`로 이를 건다.
- 2,000 카운트: `PLACE_BUDGET` 1,200/200/500/100(합계 2,000) + `CATALOG_TIER_BUDGET` A/B/C 120/380/1,500.
  방금 main에 머지된 `docs/superpowers/specs/2026-08-16-v2-contract-freeze-p1-p15.md` **P-12 동결값과 완전 일치**
  (해당 문서 `:313` 이하 및 P-15h 행 확인). **일치.**

### 2.4 스텁·TODO·skip 0건 / 기존 실패 1건

- `web/game/**` 변경 파일에서 `TODO`/`FIXME`/`test.skip`/`.only`/`placeholder` **0건**.
  `lib/v2/clientDisabled.tsx`는 의도적 빌드 스텁이며 그 자체가 산출물이다(미구현 자리표시가 아님).
- 작성자가 신고한 기존 실패 `__tests__/live-noop-closures.test.tsx` › `선택 풀 즉시 반영`을
  **직접 재현했다**: base `d63f6fec`에서 1 failed / 7 passed, `origin/main`(`dcb205d8`)에서도
  1 failed / 7 passed. **이 브랜치와 무관한 선행 실패가 맞다.**

---

## 3. 발견한 결함과 수정 (이 리뷰 커밋에 포함)

- **A. MAJOR — 격리 배선이 무방비였다.** `shouldStubV2Client`는 단위 테스트가 있었지만
  `next.config.mjs`가 그 플러그인을 실제로 배선했는지는 아무 테스트도 걸지 않았다. webpack 훅을
  지워도 전 스위트가 초록이고 three.js가 프로덕션 번들로 나간다.
  → `__tests__/v2-space-proof.test.ts`에 `next.config.mjs` 배선(`createV2ClientStubPlugin(`,
  `process.env.V2_ENABLED === 'true'`, `webpack(config)`) 단언을 추가했다.
  **mutation 확인:** `createV2ClientStubPlugin(` → `noopPlugin(`으로 바꾸면 이 테스트가 빨개진다(실행 확인 후 원복).
- **B. MAJOR — turbopack 한계가 주석뿐이었다.** `buildIsolation.mjs`가 "webpack 전용"임을 스스로
  신고했지만, `package.json`의 `build`가 `--turbopack`으로 바뀌면 훅이 조용히 죽고 아무도 모른다.
  → `pkg.scripts.build`가 `turbo`를 포함하면 실패하는 테스트를 추가했다. 한계가 주석에서 게이트로 승격됐다.
- **C. MINOR — `lib/v2/**` 직접 import 경로가 무방비였다.** 스텁은 `components/v2/`만 가로챈다.
  → `components/v2` 밖의 `'use client'` 파일이 `@/lib/v2/`를 import하면 실패하는 소스 스캔 테스트를 추가했다
  (빈 디렉터리 공허 통과 방지 단언 포함).
- **D. MINOR — G0C-j 주석이 사실과 달랐다.** §2.2-1/2 참조. 주석 정정 + `CANDIDATES` 한계 명시.
- **E. MINOR — 루프 문서의 "여유 약 2배" 표현.** vsync 고정 측정에서는 헤드룸을 알 수 없다. §4에서 수정.

수정 범위는 `web/game/__tests__/**` 와 `docs/**` 뿐이다. `tools/agent-system/check.py`,
`middleware.ts`, 백엔드 모듈은 건드리지 않았다.

## 4. 문서 갱신 (docs-drift)

`docs/loops/opensam-41-v2-g0c-3d-proof/README.md`에 다음을 반영했다:
독립 재현 결과(청크 658 B·three 0건·404 실측·FPS 재측정치), 빌드 격리 메커니즘의 **webpack 전용
한계와 그것을 잠그는 테스트**, `lib/v2` 직접 import 잔여 경로, vsync 고정으로 헤드룸을 알 수 없다는
측정 한계, G0C-j `CANDIDATES` 한계.

## 5. 게이트

- `python3 tools/agent-system/check.py --strict --base origin/main` → Errors 0
- `cd web/game && pnpm typecheck` → 오류 0
- `cd web/game && pnpm test` → 신규 v2 테스트 전부 통과, 실패는 선행 1건(`live-noop-closures`,
  base/main에서도 동일 실패)뿐
- `playwright test e2e/v2-space-fps.spec.ts` → 6 passed

남은 UNKNOWN(지어내지 않음): 프로덕션 빌드 기준 FPS 미측정(`next dev`만 측정), 실기기·비headless
GPU 성능 미측정, G0C-j 실제 2,000 카탈로그 BLOCKED, 연대기 모드 미측정.

Verdict: cleared
