# Independent cross-agent critique — game proxy 401 refresh-retry (PR #505)

Scope: web/game/app/api/game/[...path]/route.ts, web/game/app/api/auth/me/route.ts, web/game/lib/authRefresh.ts, web/game/__tests__/game-api-proxy-route.test.ts (web/)

리뷰어: critic-505 (작성자 아님, 독립 패스). 대상 브랜치 `work/opensamguk/game-proxy-refresh-retry`,
비교 기준 `main`(59ec25eb). 작성자가 스스로 작성했다 철회한 리뷰 문서(commit 9e573e05로 삭제됨)는
근거로 사용하지 않았고, 아래 항목은 모두 원본 소스를 직접 읽어 확인했다.

## Verdict 요약

브라우저 쿠키 path 스코프 때문에 새 refresh-retry 경로가 **실제 요청에서 절대 실행되지 않는다.**
로직 자체(1회 재시도, body 재전송, SSE 제외, 401 한정)는 코드상 정확하지만, 진입 조건이 항상 거짓이라
보고된 "장수 생성 401"이 고쳐지지 않는다. 신규 테스트 2개는 `next/headers`의 `cookies()`를 통째로
스텁해 쿠키 path 규칙을 우회하므로 이 결함을 구조적으로 잡을 수 없다.

## BLOCKER

### B1. `sam_refresh`는 `/api/game/*` 요청에 실려오지 않는다 — 재시도 분기가 죽은 코드

- `web/game/lib/cookies.ts:7` `const REFRESH_PATH = '/api/auth';`
- `web/game/lib/cookies.ts:29` `res.cookies.set(REFRESH_COOKIE, tokens.refreshToken, opts(SESSION_MAX_AGE, REFRESH_PATH));`
- `web/gateway/lib/cookies.ts:14-16` 에 의도가 명시돼 있다: "refresh 쿠키는 `/api/auth/{me,refresh}`에서만
  읽힌다 → 경로를 `/api/auth`로 좁혀 7일짜리 장기 토큰이 모든 동일출처 요청(/api/proxy, 정적 등)에
  실려나가지 않게 한다." `sam_refresh`를 심는 코드 경로는 두 앱의 `setAuthCookies` 뿐이며(전역 grep 확인),
  둘 다 path=`/api/auth`를 쓴다. path=`/`로 심는 경로는 존재하지 않는다.
- RFC 6265 §5.1.4 path-match: 쿠키 path `/api/auth`는 요청 path `/api/game/select-pool/claim`의
  접두어가 아니므로 브라우저는 이 쿠키를 보내지 않는다. 포트가 달라도(3000/3001) 쿠키는 host 단위로
  공유되지만 path 스코프는 그대로 적용된다.
- 결과: `web/game/app/api/game/[...path]/route.ts:101` 의 `store.get(REFRESH_COOKIE)?.value` 는 실사용에서
  항상 `undefined` → `route.ts:138` 의 `if (upstream.status === 401 && refresh)` 가 항상 false →
  401이 변경 전과 동일하게 그대로 전달된다. 즉 이 PR은 리포트된 증상을 고치지 못한다.
- 신규 테스트가 통과하는 이유는 `__tests__/game-api-proxy-route.test.ts:11-18` 이 `cookies()`를 임의
  맵으로 대체하고 `:144`, `:183` 에서 `sam_refresh`를 직접 주입하기 때문이다. path 스코프가 재현되지
  않으므로 초록불이 이 결함에 대해 아무 정보도 주지 않는다.
- 주의: `REFRESH_PATH`를 `/`로 넓히는 "한 줄 수정"은 위에 인용된 보안 결정(장기 토큰을 모든
  동일출처 요청에 노출하지 않는다)을 뒤집는 것이므로 그대로 받아들여선 안 된다. 서버사이드 프록시가
  refresh 토큰을 쥘 수 없다는 제약을 전제로 한 설계 재검토가 필요하다(예: 클라이언트가 401을 받으면
  `sam_refresh`가 실제로 도달하는 `/api/auth/me`를 먼저 호출해 재발급받고 원 요청을 재시도).

## MAJOR

### M1. `refreshAccessToken` 예외가 프록시에서 처리되지 않아 401이 500으로 바뀐다

`route.ts:139` 의 `await refreshAccessToken(refresh)` 는 try/catch 없이 호출된다.
`lib/authRefresh.ts:11-17` 의 `fetch`가 gateway 장애/DNS 실패로 reject하면 `forward()`가 throw하고
Next는 500을 반환한다. 변경 전에는 같은 상황에서 upstream의 401이 그대로 전달됐다.
공유 헬퍼를 쓰는 다른 호출자 `app/api/auth/me/route.ts:44-56` 은 동일 호출을 try/catch로 감싸
502로 정상 변환한다 — 프록시만 가드가 빠져 있다. B1을 고쳐 분기가 살아나면 즉시 노출되는 결함이다.

## MINOR

### m1. event-stream 분기의 `setAuthCookies`는 사실상 도달 불가

`route.ts:129-131` 에서 `sse/turn` GET은 첫 fetch 이전에 `streamEventSource`로 early return하므로,
`route.ts:150-157` 의 event-stream 분기에 `refreshed != null` 로 도달하려면 `sse/turn` 이 아닌 경로가
`text/event-stream`을 돌려주고 그 요청이 401→refresh를 거쳐야 한다. 실질적으로 죽은 줄이다. 차단 사유는 아님.

### m2. SSE 자체는 만료 복구 대상이 아니다(범위 밖 한계)

`sse/turn`은 재시도 대상에서 제외되므로 access 만료 시 `route.ts:69-71` 경로로 `event: error`만 나가고
복구되지 않는다. 기존 동작과 동일하며 이 PR이 악화시키지는 않았다. 문서화된 한계로 남길 것을 권한다.

## 검증되어 통과한 항목 (직접 확인)

- **재시도 정확히 1회 / 재귀·루프 없음 — VERIFIED.** `route.ts:138-145` 는 단일 `if` 블록이고 루프가
  없다. 재시도 응답 `upstream`은 다시 401 검사를 받지 않으며 `refreshAccessToken`은 자기 자신을
  호출하지 않는다(`lib/authRefresh.ts` 전체 23줄).
- **body 재전송 — VERIFIED.** `route.ts:126` `init.body = await req.text()` 로 문자열이 버퍼링되고,
  재시도는 동일한 `init` 객체를 그대로 넘긴다(`route.ts:143`). `route.ts:112-117` 에서 `init.headers`가
  `headers` 객체를 참조하므로 `route.ts:142` 의 `headers.Authorization` 변경이 재시도 요청에 반영된다.
  테스트가 `Bearer new-access` + 동일 body를 실제로 어서트한다. 다만 이 라우트는 `GET`/`POST`만
  export하므로(`route.ts:168-176`) PATCH/DELETE 경로는 애초에 존재하지 않는다 — 해당 질문은 무의미.
- **SSE 스트리밍 분기 제외 — VERIFIED.** `route.ts:129-131` 의 early return이 첫 fetch보다 앞에 있어
  스트림 시작 후 재시도가 발생할 수 없다.
- **refresh 토큰 회전으로 인한 동시성 결함 — 해당 없음(VERIFIED).**
  `app/gateway-api/src/main/kotlin/opensamguk/gateway/service/AuthService.kt:94-106` 의 `refresh()`는
  `@Transactional(readOnly = true)`이고, 검증은
  `security/JwtTokenProvider.kt:109 validateRefreshToken` = 서명/만료 파싱뿐이다. 서버에 refresh 토큰
  저장소나 무효화(blacklist/rotation) 기록이 없으므로 동시 refresh가 서로를 무효화하지 않는다.
  부작용은 쿠키 last-write-wins 뿐이며 모든 발급 토큰이 만료까지 유효하다.
- **인증 경계 — VERIFIED.** 재시도 조건은 `upstream.status === 401` 뿐이라 403은 refresh를 타지 않는다.
  refresh 실패 시 `refreshed`는 null로 남고 원 401 응답 객체가 그대로 status/본문 전달된다
  (`route.ts:159-165`). 테스트 2번이 fetch 2회 + status 401을 어서트한다.
  game-api의 401은 `@AuthenticationPrincipal userId == null` 계열(예: `SelectPoolController.kt:37-39`,
  `web/CommandController.kt:97-99`)로 부작용 이전에 반환되므로 재시도로 인한 중복 실행 위험은 없다.
- **쿠키 세팅 충돌 — VERIFIED.** 응답은 `Content-Type`만 복사해 새로 만들고(`route.ts:160-163`)
  그 위에 `res.cookies.set`이 Set-Cookie를 추가하므로 헤더 충돌이 없다. upstream의 Set-Cookie를
  버리는 동작은 변경 전과 동일하다.
- **`/api/auth/me` 회귀 — 없음(VERIFIED).** 헬퍼 추출 전후 제어 흐름이 동등하다. 유일한 차이는
  `lib/authRefresh.ts:16` 이 추가한 `cache: 'no-store'` 이며 POST 요청에는 무의미하다. throw는 여전히
  같은 try/catch에 잡힌다. `__tests__/auth-me-route.test.ts` 3개 통과.
- **`jsonResponse` status 기본값 수정이 기존 테스트 의미를 바꾸지 않음 — VERIFIED.**
  `git show main:web/game/__tests__/game-api-proxy-route.test.ts` 확인 결과 기존 호출부는 `:72`, `:90`,
  `:122` 세 곳뿐이고 모두 인자를 생략한다 → 기본값 200으로 이전과 완전히 동일하다.
  200 하드코딩에 가려져 통과하던 테스트는 없다.
- **테스트 실행 — 확인.** `npx vitest run __tests__/game-api-proxy-route.test.ts __tests__/auth-me-route.test.ts`
  → 2 files / 13 tests passed. 초록불이지만 B1·M1 어느 것도 잡지 못한다.

## UNKNOWN (확인 못 함)

- `nginx/nginx.conf:13` 은 `location /api/game/ { proxy_pass http://game_api/; }` 로 `/api/game/*`을
  Next 라우트가 아니라 game-api로 직행시킨다. 이 토폴로지에서는 본 프록시 라우트 자체가 우회된다.
  보고된 "장수 생성 401"이 어느 토폴로지(nginx :80 / web-game :3001 직결 / 배포 환경)에서 발생했는지
  브랜치 내 근거로는 확정할 수 없었다. 재현 경로가 특정되지 않은 채 수정이 들어갔다.
  (이 불확실성과 무관하게 B1은 성립한다 — path 스코프는 어느 오리진에서도 동일하게 적용된다.)

## 요구 조치

1. B1: `/api/game/*` 서버 핸들러가 refresh 토큰에 접근할 수 없다는 제약을 인정하고 설계를 다시 잡을 것.
   `REFRESH_PATH` 확대는 문서화된 보안 결정을 되돌리므로 단독 해법으로 채택 금지.
2. M1: 프록시의 `refreshAccessToken` 호출을 try/catch로 감싸 실패 시 원 401을 그대로 반환할 것.
3. 테스트: `cookies()` 스텁이 실제 쿠키 path 스코프를 반영하도록 하거나(요청 path 기반 필터),
   최소한 `/api/game/*` 요청에 `sam_refresh`가 없을 때의 동작을 명시적으로 고정하는 테스트를 추가할 것.

Verdict: fix-required
