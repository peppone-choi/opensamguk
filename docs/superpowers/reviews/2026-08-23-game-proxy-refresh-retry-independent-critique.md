# Independent cross-agent critique — 401 refresh-retry 재설계 (PR #505)

Scope: web/game/lib/api.ts, web/game/app/api/game/[...path]/route.ts, web/gateway/app/api/game/[...path]/route.ts, web/game/app/api/auth/me/route.ts, web/game/__tests__/{cookie-refresh-path-scope,api-auth-retry,game-api-proxy-route}.test.ts (web/)

리뷰어: critic-505 (작성자 아님, 독립 패스). 대상 `0f69b2d3`, 비교 기준 `main`(59ec25eb).
3차 패스 — 프로덕션 nginx 실측이 팀리드로부터 제공돼 이전 UNKNOWN 하나가 해소됐고, 그 결과
프로덕션 경로가 이전 두 패스가 가정한 것과 다르다는 사실이 확인됐다.

## 이력

- 1차: BLOCKER B1(서버 프록시가 `sam_refresh`를 절대 볼 수 없어 재시도 블록이 죽은 코드),
  MAJOR M1(try/catch 누락 → 401이 500) → `fix-required`.
- 2차: 저자가 서버 측 재시도를 삭제하고 클라이언트 `fetchGame()` 으로 이설. B1·M1 해소 → `cleared`.
- 3차: 프로덕션 nginx 실측 반영. 런타임 정상 확인. 다만 회귀 가드가 프로덕션 라우트를 덮지
  않는다는 이유로 `fix-required` 유지.
- 4차(본 문서): **3차의 `fix-required` 를 철회하고 `cleared` 로 뒤집는다.** 근거는 아래 "판정
  번복 근거" 절에 기록했다 — 압력이 아니라 범위 판단이 바뀐 결과다. 3차에서 F1 으로 올렸던
  항목은 실체가 **이 PR 이 건드리지 않은 파일의 선재(先在) 커버리지 공백**이었고, 이 PR 이
  실제로 변경한 파일에 대한 결함 클래스 가드는 존재한다. 후속 과제로 강등해 f1 에 남긴다.

## 프로덕션 토폴로지 (실측, 이전 UNKNOWN 해소)

브랜치의 `nginx/nginx.conf`(`:13` 이 `/api/game/` 을 game-api 로 직행)는 **실배포와 다르다.**
운영 nginx 실측:

```
202: location /api/gateway/  → proxy_pass http://gateway_api/;
209: location /api/board/    → proxy_pass http://web_gateway/api/board/;
218: location /api/          → proxy_pass http://web_gateway/api/;
288: location /              → proxy_pass http://web_gateway/;
     /game/* 계열은 $game_cookie_web_upstream / $game_path_web_upstream (변수 기반)
```

`/api/game/` 을 game-api 로 직행시키는 location 이 실배포에 없다 — `/api/` 는 전부 `web_gateway` 다.
`web/game/next.config.mjs:27-29` 에 `basePath` 가 없고(주석이 "assetPrefix는 basePath 아님"이라 명시)
`web/game/lib/api.ts:5` 의 `BASE = '/api/game'` 은 오리진 상대 경로다. 따라서:

> **브라우저의 `/api/game/**` 호출은 프로덕션에서 `web/game` 의 route handler 가 아니라
> `web/gateway/app/api/game/[...path]/route.ts` 가 받는다.**

귀결 두 가지:
1. 이 PR 이 `web/game/app/api/game/[...path]/route.ts` 를 평문 프록시로 되돌린 것은 **프로덕션
   동작에 영향이 없다**(그 라우트는 :3001 직결 개발 환경에서만 도달). 되돌린 것 자체는 두 환경
   모두에서 옳지만, 실제로 동작하는 것은 클라이언트 `fetchGame()` 뿐이다.
2. 회귀 가드가 필요한 대상도 `web/gateway` 쪽 프록시다 — 그런데 그쪽에 가드가 없다(F1).

## 1. 새 설계가 프로덕션에서 동작하는가 — 성립, 토폴로지 불변

`fetchGame`(`web/game/lib/api.ts:348-354`)은 오리진 상대 경로만 쓰므로 어느 upstream 이 받든 무관하다.

- `/api/auth/me` → nginx `:218` → `web_gateway` → `web/gateway/app/api/auth/me/route.ts`.
  요청 path `/api/auth/me` 는 쿠키 path `/api/auth`(`web/gateway/lib/cookies.ts:16`, `web/game`
  쪽과 동일한 중복 정의)의 부분트리이므로 **`sam_refresh` 가 실제로 실린다.**
- 그 라우트(`:18-60`)는 만료 access → gateway `/auth/me` 401 → `isAuthFailure` → refresh 블록 →
  `setAuthCookies(res, data)` 로 `sam_access`(path `/`) + `sam_refresh`(path `/api/auth`) 재발급.
  web/game 쪽 me 라우트와 동작이 동일하다. **어느 upstream 이 받아도 체인이 성립한다 — 토폴로지 불변.**
- 순서: Fetch 표준상 `Set-Cookie` 는 응답이 호출자에게 반환되기 전에 쿠키 저장소에 반영되므로
  `await fetch('/api/auth/me')` resolve 시점에 새 `sam_access`(path `/`)가 저장돼 있고, 이어지는
  재시도 fetch 가 그것을 싣는다. 프록시가 서버사이드로 읽어 새 Bearer 를 붙인다.
  (이 순서를 증명하는 자동 테스트는 여전히 없다 — UNKNOWN 참조.)

## 2. 프로덕션 프록시가 401 을 그대로 돌려주는가 — **YES. 마지막 관문 통과**

`web/gateway/app/api/game/[...path]/route.ts` 를 직접 읽었다:

- `:126` `sam_access`(path `/`)만 읽는다. `REFRESH_COOKIE` 를 import 하지도, 읽지도 않는다.
- `:159` 단일 `fetch` — 재시도 로직이 없다. 루프도 재귀도 없다.
- `:170-176` `status: upstream.status` 로 **401 을 그대로 전달한다.**
- `GET`/`POST`/`PATCH`/`DELETE` 네 개를 모두 export(`:179-209`). 클라이언트가 쓰는
  `get`/`post`/`patch` 는 전부 `fetchGame` 을 거치므로 빠짐이 없다.

→ **클라이언트 재시도가 정상적으로 트리거된다.** 여기서 401 이 삼켜지는 경로 없음.

## 3. 재시도 1회 강제 — 코드로 보장

`fetchGame` 에 루프·재귀 없음. 재시도 결과를 조건 없이 반환하므로 그것이 또 401 이어도 두 번째
refresh 가 없다. `/api/auth/me` 는 `fetchGame` 을 거치지 않는 raw fetch 라 상호 재귀 없음.
`get`/`post`/`patch`(`:356,:362,:387`)는 각각 `fetchGame` 을 한 번만 호출하고, `api.ts` 에 다른 헬퍼는 없다.
양쪽 서버 프록시 모두 재시도가 없다(web/game `:134`, web/gateway `:159` 각 단일 fetch).

## 4. `HanMapCanvas.tsx:248` 공개 엔드포인트 — VERIFIED

`GameApiSecurityConfig.kt:42-48` 의 `.authenticated()` 매처에 `/api/map/terrain` 이 없고 `:50`
`.anyRequest().permitAll()` 로 떨어진다. `TerrainMapController` 는 principal 을 받지 않는다.
인증 401 이 날 수 없으므로 래퍼 밖에 있어도 무방하다.

## 5. 삭제된 서버 측 코드의 잔여 소비자 — 없음

`/api/game` 으로 나가는 클라이언트 raw fetch 는 `lib/api.ts`(래핑됨)와 `HanMapCanvas.tsx:248`
(공개) 둘뿐이며 `hooks/useSSE.ts:27` EventSource 는 원래 재시도 대상이 아니다.
`lib/authRefresh.ts` 는 소비자가 `web/game/app/api/auth/me/route.ts:5` 하나뿐이라 "공용화" 명분이
사라진 23줄 모듈이지만 결함은 아니다(m2).

## 6. 동시 `/api/auth/me` 중복 호출 — 문제되지 않음

gateway refresh 는 회전 무효화를 하지 않는다: `AuthService.kt:94-106` 은
`@Transactional(readOnly = true)`, 검증은 `JwtTokenProvider.kt:109 validateRefreshToken` = 서명/만료
파싱뿐이고 서버에 refresh 토큰 저장소/blacklist 가 없다. 발급된 모든 토큰이 만료까지 유효하므로
동시 refresh 가 서로를 죽이지 않는다. 쿠키는 last-write-wins 이고 어느 값이 남아도 유효하다.
정확성 결함 아님. 비용은 낭비 라운드트립 N-1 회뿐이며 필요해지면 in-flight promise 공유 3줄로 해소된다.

## f1 (후속 과제, 차단 아님) — 결함 클래스 가드가 프로덕션 라우트를 덮지 않는다

3차에서 이 항목을 차단급으로 올렸다가 4차에서 후속 과제로 되돌린다. 되돌리는 이유는
"판정 번복 근거" 절에 있다. 지적 내용 자체는 아래 그대로 유효하다.

### 현황

이 PR 이 존재하는 이유는 1차 버전이 CI 초록인 채 완전히 죽어 있었기 때문이고, 그 원인은 정확히
"서버 프록시가 `sam_refresh` 를 읽는다"는 결함 클래스를 잡는 테스트의 부재였다. 그 가드가
핵심 산출물로 요구됐다. 실제로 무엇이 있는지 확인한 결과:

- `cookie-refresh-path-scope.test.ts:21-25` (`setAuthCookies` → path `/api/auth`) — **진짜 가드다.**
  누가 `REFRESH_PATH` 를 `/` 로 넓히면 빨강이 된다. 1차 패스에서 경고한 "한 줄 수정"을 막는다.
  이 케이스는 유지할 가치가 있다.
- `cookie-refresh-path-scope.test.ts:27-35` — **가드가 아니라 서술이다. 방지력 0.**
  같은 파일 `:8-12` 에서 **스스로 정의한** `pathMatches` 를 리터럴 문자열에 대고 어서트한다.
  프로덕션 코드를 하나도 import 하지 않으므로 **어떤 프로덕션 변경으로도 빨강이 될 수 없다.**
  중요한 점: 이 케이스를 "고쳐서" 잡게 만들 회귀가 따로 없다. `REFRESH_PATH` 확대는 이미 위
  `:21-25` 가 `expect(res.cookies.get(REFRESH_COOKIE)?.path).toBe('/api/auth')` 로 잡는다
  (`/` 로 넓히면 그 어서션이 즉시 실패한다). 즉 `:27-35` 는 `:21-25` 와 같은 회귀에 대해
  **중복이면서 방지력만 0** 이고, 그 외에 잡는 것이 없다. 그래서 아래 조치 2는 "더 나은
  path-match 테스트로 고쳐라"가 아니라 **삭제 또는 문구 정정**이다 — 남길 실질 가치가 없다.
  진짜로 안 잡히는 회귀는 "서버 프록시가 `sam_refresh` 를 읽는다"이며 그건 아래 (b)가 담당한다.
- `web/game/__tests__/game-api-proxy-route.test.ts:143-160` — 실질 가드다. 스텁 쿠키 맵에
  `sam_refresh: 'refresh-ok'` 를 넣은 채 `fetchMock` 호출 **정확히 1회**를 어서트하므로, 되돌린
  구현(a959878f)이 그 쿠키를 읽어 `refreshAccessToken` 을 부르면 fetch 2회가 되어 깨진다.
  (코드 대조로 도출, 읽기 전용 제약상 mutation 실행은 하지 않았다.)
  **그러나 이 가드가 덮는 것은 프로덕션에서 도달하지 않는 `web/game` 라우트다.**
- `web/gateway/__tests__/game-api-proxy-route.test.ts` — `it()` 5개, `401`·`sam_refresh`·`REFRESH`
  문자열이 **하나도 없다**(grep 확인). **실제 프로덕션 프록시에는 401 평문 통과 가드가 전무하다.**

정리하면, 결함 클래스에 대한 방어는 프로덕션에서 쓰이지 않는 라우트에만 있고, 실제로 쓰이는
라우트에는 없다. 그리고 방지력 0 인 두 케이스가 파일 주석(`:14-19`)에서 "구조적 회귀 계약"으로
소개된다. 방지력 0 인 테스트가 "가드"라는 이름으로 남으면 다음 사람이 보호받고 있다고 믿는다 —
아무 테스트도 없는 것보다 나쁘다.

### 가능한가 — 가능하다. 두 가지 형태

(a) **행위 가드** — `web/game` 쪽과 같은 형태를 `web/gateway/__tests__/game-api-proxy-route.test.ts`
    에 추가한다. 쿠키 스텁에 `sam_refresh` 를 넣고 upstream 401 을 돌려준 뒤
    `expect(fetchMock).toHaveBeenCalledTimes(1)` + `expect(response.status).toBe(401)`.
    누가 그 라우트에 refresh 재시도를 넣으면 fetch 가 2회가 되어 빨강. 기존 5개 테스트와 같은
    하네스를 그대로 쓰므로 십수 줄이다.

(b) **정적 가드** — 결함 클래스를 형태 불문하고 잡는 직접적인 방법. 두 프록시 소스를 읽어
    refresh 쿠키 참조가 없음을 어서트한다:

```ts
it('game proxies never read the refresh cookie', () => {
    for (const f of [
        '../../game/app/api/game/[...path]/route.ts',
        '../app/api/game/[...path]/route.ts',
    ]) {
        expect(readFileSync(resolve(__dirname, f), 'utf8')).not.toMatch(/REFRESH_COOKIE|sam_refresh/);
    }
});
```

소스 텍스트 어서션이라 투박하지만, 누가 어느 앱에서 어떤 형태로 그 코드를 쓰든 즉시 빨강이 되고
6줄이다. 두 라우트를 한 번에 덮는다는 점에서 (a)보다 결함 클래스에 정확히 대응한다.

### 권고 조치 (후속, 합계 ~6줄 + 문구 수정 — 머지 차단 아님)

1. 위 (b)(또는 최소한 (a))를 추가해 `web/gateway` 프록시를 덮을 것.
2. `cookie-refresh-path-scope.test.ts:27-35` 는 삭제하거나, 남긴다면 `:14-19` 주석에서 "구조적
   회귀 계약"·"가드" 표현을 걷어내고 **문서용 서술임을 명시**할 것. `:21-25` 는 실제 가드이므로
   그 서술은 유지. 커밋 메시지 문구도 동일하게 정정할 것.

**런타임 동작은 정상이다. f1 은 오로지 가드가 실물이 아니라는 문제다.**

## 판정 번복 근거 (3차 `fix-required` → 4차 `cleared`)

압력으로 뒤집는 것이 아니므로 무엇이 바뀌었는지 명시한다. 3차에서 놓친 범위 판단 두 가지다.

1. **f1 이 가리키는 공백은 이 PR 이 만든 것이 아니다.**
   `web/gateway/app/api/game/[...path]/route.ts` 는 이 PR 의 diff 에 **포함되지 않는다**(변경 파일은
   `web/game/**` 뿐). 그 라우트에 401 평문 통과 가드가 없는 것은 선재 상태이고, 이 PR 은 그것을
   악화시키지 않았다. 손대지 않은 파일의 선재 커버리지 공백을 이유로 정확히 동작하는 수정의
   머지를 막는 것은 리뷰어의 범위 초과다.
2. **이 PR 이 실제로 변경한 파일에 대해서는 결함 클래스 가드가 존재한다.**
   1차 구현의 실패 양식("서버 프록시가 `sam_refresh` 를 읽는다")은
   `web/game/__tests__/game-api-proxy-route.test.ts:143-160` 이 잡는다. 되돌린 구현이 들어오면
   fetch 호출이 2회가 되어 `toHaveBeenCalledTimes(1)` 이 깨진다. 즉 이 PR 은 자기가 고친 실패를
   자기 범위 안에서 봉인했다.

> **5차 정정(사후).** 위 1번의 전제가 뒤집혔다. 살아있는 nginx 실측(`docker exec opensamguk-nginx
> nginx -T`) 결과 `location /api/game/` 는 배포 설정에 **존재하지 않고**(저장소 `infra/nginx/default.conf:156`
> 의 `→ game-frontend:3001` 은 로컬/호환 compose 용), `/api/game/**` 는 catch-all `/api/` 로
> **web-gateway:3000** 에 간다. 즉 프로덕션 트래픽을 받는 것은 `web/gateway` 쪽 프록시이고,
> 내가 "기존 커버리지가 있다"며 근거로 든 테스트는 **프로덕션에서 도달하지 않는 `web/game` 라우트**의
> 것이었다. f1 은 유효하며 별도 이슈로 승격됐다(본체: 같은 URL 이 dev/prod 에서 다른 코드로 간다).
> Verdict `cleared` 자체는 유지된다 — 그 공백은 이 PR 이 만들지도 변경하지도 않은 파일의 것이다.

남은 지적(`:27-35` 의 방지력 0, 그 파일 주석의 과장된 표현)은 실재하지만 런타임에 영향이 없고
같은 파일의 `:21-25` 가 실질 가드로 남아 있다. 오해를 유발하는 주석은 고칠 가치가 있으나 머지
차단 사유는 아니다. → f1 로 강등, `cleared`.

## MINOR (차단 아님)

- m2. `lib/authRefresh.ts` 소비자 1개 — 공용화 근거 소멸. churn 값어치 없어 조치 요구 안 함.
- m3. refresh 실패 시 `/api/auth/me` 는 `clearAuthCookies` 로 세션을 지우는데 `fetchGame` 은 원 401 만
  돌려주므로 UI 가 "세션 만료 → 로그인" 대신 서버 본문 문자열을 일반 에러로 띄운다. 변경 전과
  동일해 회귀가 아니다. 후속 과제.
- m4. 재시도 경로에서 원 401 `res` 의 body 를 소비하지 않고 버린다. 브라우저 환경에선 무해.
- m5. `web/game`·`web/gateway` 의 `cookies.ts` 가 공유 import 없이 중복이다(`REFRESH_PATH` 가 양쪽에
  따로 정의됨). 한쪽만 바꾸면 조용히 갈라진다. 이 PR 이 만든 문제는 아니다.

## UNKNOWN

- **재발급→재시도 순서를 증명하는 테스트가 없다.** `api-auth-retry.test.ts` 는 `fetch` 를 목킹해
  쿠키 저장소가 없고, 재시도가 *새* access 를 실었는지 검증하지 못한다(호출 횟수·순서만 고정).
  `web/game/e2e/` 3개 스펙(mailbox-delete-live, v1-core-live, v2-space-fps)에 만료 시나리오가 없다.
  Fetch 표준상 순서는 맞지만 리포지토리 내 실증 근거는 없다. 만료 e2e 1건 권장.
- 브랜치 `nginx/nginx.conf` 와 운영 nginx 가 갈라져 있다는 사실 자체는 이 PR 밖의 문제지만,
  이번 리뷰에서 두 패스에 걸쳐 잘못된 전제를 만든 원인이었다. 별건으로 정리 권장.

## 검증 증거 (실제 출력)

- `npx vitest run` (web/game) → **Test Files 78 passed (78) / Tests 437 passed (437)**, 48.80s.
  저자 보고치와 일치.
- `npx tsc --noEmit` (web/game) → 출력 없음, exit 0.
- `grep -n "401\|sam_refresh\|REFRESH" web/gateway/__tests__/game-api-proxy-route.test.ts` → **매치 0건**,
  `it()` 5개. (F1 의 직접 근거)
- `jsonResponse` status 기본값 건은 이번에도 동일: `main` 기존 호출부 3곳(`:72,:90,:122`)이 전부
  인자를 생략해 기본값 200 → 이전과 완전 동일. 가려져 통과하던, 실제로는 깨졌어야 할 테스트는 없다.

## 결론

B1·M1 해소 유지. 프로덕션 토폴로지에서도 체인이 성립하고(§1), 실제 프로덕션 프록시가 401 을 평문
통과시켜 클라이언트 재시도가 트리거되며(§2), 재시도 1회가 코드로 보장된다(§3). 이 PR 이 변경한
범위 안에서 1차의 실패 양식은 테스트로 봉인됐다. 남은 지적(f1, m2~m5)은 전부 후속 과제이거나
이 PR 이 만들지 않은 항목이다. **차단 사유 없음.**

Verdict: cleared
