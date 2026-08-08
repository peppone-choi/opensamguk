# S3-b — 0A-a `/game/v2-lab/` 라우트 네임스페이스 (실측)

- 티켓: OPENSAM-35 / 0A-a, 계획 `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S3
- 브랜치: `op-35-v2-0a`, 작성 2026-08-08
- 범위: `web/game/**` 전용. 백엔드(`app/`·`infra/`·`logic/`·`common/`) 무수정.

---

## 1. `V2_ENABLED`가 Next.js에 도달하는 경로 — 기존 관례 확인 결과

새 관례를 발명하지 않고 리포에 이미 있는 것을 썼다.

| 확인한 기존 코드 | 관례 |
|---|---|
| `web/game/lib/server-api.ts:12-16` | `GAME_API_URL`·`GATEWAY_API_URL`을 **서버 전용 `process.env`**로 읽는다. 파일 상단 주석이 "no `NEXT_PUBLIC_` prefix. The browser never sees them"을 명시 |
| `web/game/middleware.ts:57` | `SERVER_ID`를 미들웨어에서 `process.env`로 직접 읽는다 |
| `web/game/lib/constants.ts:7` | 브라우저가 봐야 하는 값(`NEXT_PUBLIC_IMAGE_CDN`)만 `NEXT_PUBLIC_` 접두사 |
| `web/game/.env.example` | 서버 전용/브라우저 노출을 접두사로 구분한다고 문서화 |

→ **결정: 서버 컴포넌트/미들웨어에서 `process.env.V2_ENABLED`를 읽는다. game-api에 묻지 않는다.**

- game-api 왕복은 불필요하다. 게이트는 프론트 렌더 시점의 정적 판정이고, 물어볼 대상 API가 아직 없다(0A는 seam 개설 0건 — 계획 §5).
- `NEXT_PUBLIC_V2_ENABLED`는 **쓰지 않는다.** 그 값은 클라이언트 번들에 인라인되어 브라우저에 노출되고, 게이트가 서버 판정이 아니라 공개 설정이 된다.

**값 규약은 frontend와 backend가 의도적으로 다르다.** frontend middleware/layout는 raw
`V2_ENABLED === 'true'` **strict equality**만 허용하므로 `'1'`·`'TRUE'`·`'True'`·`'tRuE'`·`'false'`·`''`를
모두 닫는다. backend Spring `@ConditionalOnProperty(..., havingValue = "true")`는
case-insensitive 비교라 profile이 함께 있으면 `TRUE`/`True`/`tRuE`를 연다. backend에는 추가
`@Profile("v2-sandbox")` 조건도 있지만 frontend에는 없으므로, frontend가 느슨해지면 exposure가 된다.
Current source tests cover both sides, and later direct-pnpm typecheck is green with Vitest JSON 132 suites /
288 tests / 0 failures. The current Java 21 `--rerun-tasks` backend gate then completed one run/no retry with
601 suites / 5,050 tests / failures·errors 0; the remaining PR Round 1 closure is independent dirty-tree re-review.

---

## 2. 404 메커니즘 — 최초 설계가 실측에서 깨졌다

### 2.1 1차 시도: `app/game/v2-lab/layout.tsx`에서 `notFound()`

App Router 정석이고, layout이므로 `/game/v2-lab/**` **모든 하위 라우트**가 자동으로 같은 게이트를 통과한다
(페이지마다 게이트를 복사하지 않는다). 라우트 테스트는 이 형태로 통과했다.

### 2.2 실스택 측정 — **soft 404 (status 200)**

`pnpm build` 후 `pnpm exec next start -p 3987`, `V2_ENABLED` 미설정 상태에서 curl:

| 경로 | 게이트 위치 | HTTP status |
|---|---|---|
| `/game/v2-lab` | `app/game/v2-lab/layout.tsx`의 `notFound()` | **200** |
| `/game/v2-lab` | `app/game/v2-lab/page.tsx`의 `notFound()` (프로브) | **200** |
| `/probe-root` — `/game` 밖, 동일 코드 (프로브) | `page.tsx`의 `notFound()` | **404** |
| `/game/v2-lab/anything` — 라우트 미존재 | — | 404 |
| `/game/rankings` — 대조 정상 라우트 | — | 200 |

프로브 2종(`app/probe-root/`, `v2-lab/page.tsx` 임시 변형)은 측정 후 삭제했고 워킹트리에 남지 않았다.

200 응답 바디에는 `5:E{"digest":"NEXT_HTTP_ERROR_FALLBACK;404"}`가 들어 있어 브라우저는 404 화면을 그린다.
그러나 **전송 상태 코드는 200이고, v2 페이지 콘텐츠(`main > h1 "v2-lab"`)가 RSC 페이로드에 그대로 실려 나간다.**

### 2.3 원인

`app/game/layout.tsx`가 `AuthGate`(client component)를 렌더한다. `/game/**` 서브트리는 그 클라이언트 경계
안에서 스트리밍되므로 **HTML 셸(=status 200)이 flush된 뒤에** 하위 `notFound()`가 해소된다. 스트리밍이
시작된 뒤에는 상태 코드를 바꿀 수 없다.

`/game` 밖 동일 코드가 404를 내는 것이 대조군이다. 라우트 그룹(`(v2)`)이나 중첩 위치를 바꿔도
`app/game/layout.tsx`는 `app/game/**` 전체에 적용되므로 **컴포넌트 레벨로는 이 경계를 벗어날 수 없다.**
이는 계획이 금지한 "리다이렉트·빈 페이지·준비 중 안내"와 같은 부류의 결함이다 — 겉모습만 404다.

### 2.4 채택 메커니즘 — middleware (팀 리드 승인 2026-08-08)

렌더 이전에 도는 유일한 층은 **middleware**다. `web/game/middleware.ts`는 이미
`matcher: ['/game', '/game/:path*']`이므로 v2-lab 경로가 그 안에 들어온다.
`layout.tsx`의 `notFound()`는 **심층방어로 유지**한다 — middleware가 우회돼도 v2 콘텐츠는 렌더되지 않는다.

### 2.5 우회 구멍 — serverId rewrite 경유 (팀 리드 지적, 실측으로 폐쇄)

같은 파일에 경로 기반 serverId rewrite 분기가 있다(`segments[2] === configuredServerId()` →
`/game/<rest>`로 rewrite). 따라서 **원시 pathname으로 판정하면 게이트가 샌다**:

- `SERVER_ID=pep`인 인스턴스에 `/game/pep/v2-lab`이 오면 pathname은 `/game/pep/v2-lab`이라
  `startsWith('/game/v2-lab')`에 걸리지 않고, rewrite를 거쳐 `/game/v2-lab`으로 렌더된다.
- early-return을 함수 맨 앞에 두면 이 경로가 새고, rewrite 뒤에 두면 쿼리 기반 분기(`?server=`)가
  먼저 `return`해 또 샌다.

**해결: 원시 pathname이 아니라 "렌더에 쓰일 실효 경로"로 판정한다.** `isV2LabPath()`가
`segments[2] === configuredServerId()`면 그 세그먼트를 접어낸 뒤 첫 세그먼트가 `v2-lab`인지 본다.
쿼리 기반 진입(`?server=pep`)은 pathname이 이미 실효 경로라 같은 판정에 걸린다.
`RESERVED_PATH_SERVER_IDS`에는 **`'v2-lab'`을 실제로 추가했다.** `PATH_SERVER_ID`
(`^[a-z0-9]{1,48}$`)가 하이픈을 제외하므로 현재 `isPublicServerId()` behavior에는 도달하지 않는
defensive/list-consistency entry다. 그래도 reserved namespace를 명시해 future regex 변경에서
`v2-lab`이 server ID로 해석되지 않게 한다.

세그먼트 단위 비교라 `v2-lab-x` 같은 접두 일치는 게이트 대상이 아니다(단위 테스트가 고정).

---

## 3. 산출물

| 파일 | 분류 | 내용 |
|---|---|---|
| `web/game/app/game/v2-lab/layout.tsx` | 신규 | `V2_ENABLED !== 'true'` → `notFound()`. 하위 전 라우트 공통 게이트 |
| `web/game/app/game/v2-lab/page.tsx` | 신규 | 최소 플레이스홀더. **v2 기능 페이지는 만들지 않았다**(계획 §5 — 0A는 확장점 개설 0건) |
| `web/game/__tests__/v2-lab-route.test.tsx` | 신규 | initial stage 13종; final historical A4 log/source has 17 tests. CodeRabbit의 14라는 수치는 XML/source로 corroborate되지 않는다. |
| `web/game/middleware.ts` | **수정(승인됨)** | `isV2LabPath()` + early-return 404 + `RESERVED_PATH_SERVER_IDS`에 `'v2-lab'` 추가. §5 참조 |

테스트는 리포 기존 관례를 따랐다 — `__tests__/` 디렉터리(`vitest.config.ts`가 이 경로만 스캔),
`@/` 별칭 import, `vi.mock`으로 외부 의존 격리(`admin1-route.test.tsx` 등과 동일 형태).
`next/navigation`을 모킹한 이유는 `NEXT_HTTP_ERROR_FALLBACK` digest 문자열이 Next 내부 규약이라
테스트를 그 형태에 결합시키지 않기 위해서다 — "`notFound()`가 호출됐는가"만 판정한다.

---

## 4. initial stage 검증 출력 tail (historical; exit code 아님)

`corepack`이 이 호스트에 없어 `pnpm`을 직접 호출했다(`/usr/local/bin/pnpm`).

```text
$ pnpm typecheck
> @opensamguk/web-game@0.0.1 typecheck /Users/apple/Desktop/개인프로젝트/opensamguk/web/game
> tsc --noEmit
```
(출력 없음 = 타입 오류 0)

```text
$ pnpm test
 ✓ __tests__/v2-lab-route.test.tsx (13 tests) 362ms
 ✓ __tests__/middleware.test.ts (8 tests) 118ms
 Test Files  54 passed (54)
      Tests  284 passed (284)
```

위 284는 initial stage transcript다. final historical A4 XML/log는 `v2-lab-route.test.tsx` **17**,
`middleware.test.ts` **8**, 전체 **54 files / 288 tests**다. The dependency absence was the earlier verifier's
historical failure; later direct-pnpm typecheck is green and Vitest JSON reports 132 suites / 288 tests / 0 failures.
That resolves frontend evidence without replacing independent dirty-tree re-review.

```text
$ pnpm build   (발췌)
├ ƒ /game/v2-lab                           331 B         187 kB
ƒ Middleware                              101 kB
BUILD_ID=V75C2XFKp2JmjIY6b1Qt2
```

### 4.1 실스택 404 실측 — 요구사항 1

**빌드 1회**(위 `BUILD_ID`, `V2_ENABLED` 미설정 상태에서 빌드) 후 env만 바꿔 `next start`로 2회 관측.

| 경로 | RUN A `V2_ENABLED` 미설정 | RUN B `V2_ENABLED=true` |
|---|---|---|
| `/game/v2-lab` | **404** | 200 (`v2 실험 네임스페이스` 렌더 확인) |
| `/game/v2-lab/anything` | **404** | 404 (라우트 미존재) |
| **`/game/pep/v2-lab`** (우회 경로) | **404** | 200 |
| **`/game/pep/v2-lab/anything`** | **404** | 404 (라우트 미존재) |
| `/game/pep/rankings` (rewrite 대조군) | 200 | 200 |
| `/game/rankings` (정상 라우트 대조군) | 200 | 200 |
| `/game/v2-lab-x` | 404 | 404 |

두 run 모두 `SERVER_ID=pep`. **`/game/pep/rankings` 200이 rewrite 분기가 실제로 살아있다는 대조군**이다
(`configuredServerId() === 'pep'`) — 즉 `/game/pep/v2-lab`의 404는 "serverId가 안 맞아서 라우트가 없어서"가
아니라 게이트가 잡은 것이다.

RUN A에서 v2 콘텐츠 유출 0건: `/game/v2-lab`·`/game/pep/v2-lab` 응답 바디에 `v2 실험 네임스페이스` **0회**
(§2.2의 soft 404에서는 이 문자열이 페이로드에 실려 나갔다).

`/game/v2-lab-x`는 두 run 다 404지만 이는 **라우트가 없어서**이지 게이트 때문이 아니다 —
HTTP 층에서는 구별되지 않으므로, 게이트가 이 경로를 통과시킨다는 사실은 단위 테스트
("접두사만 같은 경로는 게이트 대상이 아니다", `NextResponse.next` 호출 확인)로만 고정돼 있다.

### 4.2 `V2_ENABLED`는 런타임에 읽힌다 — 요구사항 2

**판정: 런타임 읽기. 빌드 타임 인라인 아님.**

RUN A·RUN B는 **동일 빌드 산출물**(`BUILD_ID=V75C2XFKp2JmjIY6b1Qt2`, `V2_ENABLED` 미설정으로 빌드)을
쓴다. 인라인이었다면 빌드 시점 값(`undefined`)이 박혀 RUN B도 404여야 하는데 200 + 실제 렌더가 나왔다.

→ **같은 이미지로 env만 바꿔 v1/v2 스택을 나눌 수 있다.** 게이트가 배포 구조를 제약하지 않는다.
(`SERVER_ID`가 같은 파일에서 이미 같은 방식으로 읽히는 것과 동일한 성질이다.)

### 4.3 404 응답 본문 — 요구사항 3

`new NextResponse(null, { status: 404 })`이므로 **바디 0바이트**다(`curl ... | wc -c` = 0).
브라우저에는 **흰 화면**이 나온다. M2의 판정 기준은 status이고 팀 리드 지시대로 범위를 넓히지 않았다.
사용자 대면 404 페이지가 필요하면 별도 티켓이다.

---

## 5. 수정한 기존 파일

| 파일 | 사유 | 범위 |
|---|---|---|
| `web/game/middleware.ts` | §2.3 — 렌더 레이어에서는 진짜 404가 구조적으로 불가능. 렌더 이전 층은 여기뿐 | `isV2LabPath()` 헬퍼 + `middleware()` 최상단 early-return. 기존 서버선택 쿠키·rewrite 로직은 무수정, `RESERVED_PATH_SERVER_IDS`에는 defensive `'v2-lab'`이 추가됨 |

팀 리드 승인 2026-08-08 (범위: 이 파일 1개). `web/game/` 밖 변경 0건. 커밋하지 않았다.

---

## 6. UNKNOWN (정직하게 남김)

1. **compose 전체 스택(nginx + game-api + engine)에서의 404 — 미관측.** §4.1은 Next 프론트 단독
   (`next start`) 측정이다. nginx 경유 응답은 **S5(v2 스택 env 분리) 이후에만 잴 수 있다.**
   M2의 "production-shape 스택" 층은 이 범위에서 부분 충족(프론트 층만)이다.
2. **컨테이너에서 `V2_ENABLED` env가 Next 프로세스에 실제로 주입되는지 — 미측정.** §4.2는 런타임
   읽기임을 증명했지만, compose가 그 값을 넣어주는지는 S5 소관이다(S2 문서 `:169`의 백엔드 미지와 동일 성질).
3. **`next start` 경고** — `"next start" does not work with "output: standalone"`. 배포는
   `node .next/standalone/server.js`로 뜬다. middleware는 두 서버 모두에서 라우팅 앞단에 돌지만
   standalone 서버에서 §4.1을 재현하지는 않았다.
4. **`layout.tsx` 심층방어 게이트의 실효성** — middleware가 먼저 404를 내므로 실스택에서는
   이 경로가 실행되지 않는다. 단위 테스트로만 검증돼 있다.

---

## 7. 정적 자산 누출 실측 (Q7) — 2026-08-08

§6이 남긴 미지 중 하나를 실측으로 닫는다. **미들웨어 matcher는 `/game`·`/game/:path*`뿐이라
`/_next/**`는 게이트 밖이다.** 그렇다면 프로덕션 빌드가 v2-lab 청크를 만드는지, 만든다면 그 안에
무엇이 있고 게이트 없이 받아지는지가 문제다.

### 7.1 측정 조건

```text
$ cd web/game && rm -rf .next && env -u V2_ENABLED pnpm run build
$ env -u V2_ENABLED npx next start -p 3399
```

`BUILD_ID=Pt2iXYMSd8-ggZ5LlvyeE`. `V2_ENABLED` 미설정 = v1 프로덕션 형상. Next 15.5.20,
`ASSET_PREFIX` 미설정이므로 에셋 경로는 `/_next`(prod compose는 `/game/_next`; 경로 접두사만
다르고 **matcher가 둘 다 안 잡는 성질은 동일**하다).

빌드 라우트 표에 `ƒ /game/v2-lab   331 B   187 kB`가 v1 빌드에서도 그대로 나온다.

### 7.2 청크는 생성된다 — 답 1: **YES**

```text
$ find .next/static -path '*v2-lab*'
.next/static/chunks/app/game/v2-lab
.next/static/chunks/app/game/v2-lab/layout-de5db85afb63f890.js
.next/static/chunks/app/game/v2-lab/page-b528149853812e2a.js
```

`app-build-manifest.json`에도 `/game/v2-lab/layout`·`/game/v2-lab/page` 엔트리가 있다.

### 7.3 게이트 없이 받아진다 — 답 2: **YES**

```text
$ curl -s -o /dev/null -w '%{http_code} %{size_download}B' http://localhost:3399<path>
/game/v2-lab                                                    -> 404 0B
/_next/static/chunks/app/game/v2-lab/page-b528149853812e2a.js   -> 200 556B
/_next/static/chunks/app/game/v2-lab/layout-de5db85afb63f890.js -> 200 556B
/_next/static/chunks/main-app-298ed5ed150ca8f1.js               -> 200 3071B
```

라우트는 404인데 그 라우트의 청크는 200이다. **matcher가 정적 자산을 안 잡는다는 것은 실측으로 사실이다.**

### 7.4 그런데 청크 안에 v2 코드는 없다 — 판정: **코드 누출 없음**

두 파일(각 556바이트)의 전문은 Sentry debug id + **빈 모듈 스텁**뿐이다:

```text
...(self.webpackChunk_N_E=self.webpackChunk_N_E||[]).push([[2772,3699,5063,5578,5783,8673,9860],
{64941:()=>{}},e=>{e.O(0,[4004,3007,3089,7358],()=>e(e.s=64941)),_N_E=e.O()}]);
```

`{64941:()=>{}}` — 모듈 본문이 비어 있다. v2-lab이 순수 서버 컴포넌트라서 클라이언트 번들에 실릴
모듈이 0개이기 때문이다. 대조군:

| 라우트 | 클라이언트 컴포넌트 | page 청크 크기 |
|---|---|---|
| `/game/v2-lab` | 없음(서버 전용) | **556 B**(빈 스텁) |
| `/game/coming-soon` | 없음(서버 전용) | 556 B(동일 스텁) |
| `/game/join` | 있음 | 20,417 B |
| `/game/inherit` | 있음 | 21,129 B |

페이지 본문 문자열도 클라이언트 번들에 없다:

```text
$ grep -rl "v2 실험 네임스페이스" .next/static
(hits 없음)
```

또한 청크 **파일명 자체가 클라이언트에서 발견 불가능**하다. `page-b528149853812e2a` 문자열은
`.next/app-build-manifest.json`(서버 전용)에만 있고 `.next/static` 어디에도 없다. 그 매니페스트는
서빙되지 않는다(`/_next/app-build-manifest.json` → 404, `/_next/static/app-build-manifest.json` → 404).

### 7.5 실제로 새는 것: 라우트 **이름** 하나 (name-only)

`grep -rl v2-lab .next/static`은 3개 파일을 잡는다 — `main-app-*.js`, `3089-*.js`, `main-*.js`.
내용은 코드가 아니라 **Sentry가 클라이언트 init에 인라인하는 라우트 매니페스트**다:

```text
$ curl -s .../main-app-298ed5ed150ca8f1.js | grep -o '.\{80\}v2-lab.\{80\}'
...{"path":"/game/troop"},{"path":"/game/v2-lab"},{"path":"/game/vote"},{"path":"/game/world-log"}],
"isrRoutes":[]}',n.TsN({dsn
```

즉 **`/game/v2-lab`라는 경로가 존재한다는 사실**은 v1 프로덕션 번들에서 누구나 읽을 수 있다.
`V2_ENABLED` 값과 무관하며(빌드 산출물), 셰어드 청크라 어차피 모든 페이지가 받는다.
**노출되는 것은 이름뿐 — 구현·데이터·마크업은 0바이트다.** 이름 노출은 이 티켓의 위협모델
("v2 코드가 v1에 새지 않는다")을 위반하지 않으므로 수용한다. 숨기려면 Sentry 라우트 매니페스트
주입을 끄거나 v2를 별도 Next 앱으로 분리해야 하는데, 둘 다 0A 범위 밖이고 이득 대비 비용이 크다.

### 7.6 0A-a "404" 판정 범위 (정본)

> **0A-a의 "404" 판정은 라우트 응답에만 적용된다 — 정적 자산은 포함되지 않는다.**
> 근거: 미들웨어 matcher가 `['/game', '/game/:path*']`이라 `/_next/**`는 구조적으로 게이트 밖이고
> (§7.3 실측), 그 대신 v2-lab이 서버 전용 컴포넌트여서 게이트 밖 청크에 **v2 코드가 0바이트**다(§7.4).
> 즉 격리는 "정적 경로도 404를 낸다"가 아니라 **"게이트 밖 경로에 보호할 내용이 애초에 없다"**로
> 성립한다. 이 전제는 v2-lab이 서버 전용인 동안에만 참이다.

### 7.7 회귀 위험과 최소 감지 장치 (implemented source guard; final rerun pending)

전제가 깨지는 조건은 하나다: **`/game/v2-lab/**`에 `'use client'` 컴포넌트가 들어오는 순간**.
그때부터 §7.4의 빈 스텁 자리에 실제 v2 코드가 들어가고(대조군 join/inherit = 20 KB),
그 청크는 §7.3대로 게이트 없이 200으로 서빙된다 — **404 뒤에 숨겼다고 믿은 코드가 정적 경로로 샌다.**

최소 장치는 현재 `web/game/__tests__/v2-lab-route.test.tsx`에 구현됐다: `app/game/v2-lab/**`의 어느
파일에도 `'use client'`가 없음을 source-level assertion으로 검사하고, 실패 메시지가 §7.6 판정을
다시 하라고 안내한다. The later direct-pnpm frontend run observed typecheck green and Vitest JSON 132 suites /
288 tests / 0 failures; the guard therefore has current frontend evidence. The current backend gate is also
green (601 suites / 5,050 tests / failures·errors 0); independent dirty-tree re-review remains pending.

**이 장치의 천장(정직하게):** 소스 레벨 grep이라 *직접* 붙은 `'use client'`만 잡는다. v2-lab이
바깥의 클라이언트 컴포넌트를 import하면(예: `components/`의 기존 클라 컴포넌트) 통과한다.
그것까지 잡으려면 CI에서 프로덕션 빌드 후 `.next/static/chunks/app/game/v2-lab/*.js` 크기가
서버 전용 기준선(556 B)을 넘는지 보는 편이 정확하지만, CI에 prod 빌드 스텝이 필요하다.

### 7.8 안 한 것 / UNKNOWN

- **`'use client'` 전이를 실제로 빌드해 재현하지 않았다.** §7.7의 "코드가 실린다"는 대조군
  (join 20 KB / inherit 21 KB)에서의 **추론**이지 v2-lab에서의 측정이 아니다. 재현하려면
  `app/game/v2-lab/page.tsx`를 임시 수정해야 하는데, 그 파일은 병렬 에이전트 소유라 건드리지 않았다.
- **standalone 서버(`node .next/standalone/server.js`)에서 재측정하지 않았다.** §7.3은 `next start`
  기준이다. 정적 청크는 두 서버 모두 같은 `.next/static`을 서빙하지만 확인은 안 했다(§6-3과 동일한 성질).
- **nginx 경유 + `ASSET_PREFIX=/game` 형상에서 재측정하지 않았다.** §6-1과 같은 이유(S5 이후).
- 이 절의 빌드는 `.next/`(gitignored)만 만들었고 커밋 대상 변경은 0건이다(`git status --short` 확인).
