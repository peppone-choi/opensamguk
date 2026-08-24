# PR #516 — game-api 프록시 커버리지 구멍 메우기, 독립 리뷰

Scope: 브랜치 work/opensamguk/proxy-topo-516 커밋 c0cbbc6b (base origin/main) — web/gateway `/api/game/[...path]` 프록시의 401 회귀 테스트 추가와 route.ts 판정 주석이, 이슈 #516 §6(프로덕션 경로 커버리지 구멍 메우기)를 실제로 닫는지 독립 재검증

Verdict: cleared

**최신 상태는 6dd238b4(#516 §5 프록시 통합)이고 판정은 cleared다.** 2차 재심에서 올린 R1 blocker 는 2026-08-24 라이브 프로덕션 실측으로 **반증**되었고(맨 끝 "R1 종결" 절), 그 결과 남은 결함은 없다. f0ac1a5e 재심(cleared)은 그 커밋이 되돌려져 **폐기**되었다.

초심(c0cbbc6b) 판정 근거 한 줄: 발산 지점 열거는 **완전**하고 duplex·sam_refresh·#514/#530 주장은 **전부 사실로 확인**되었으나, route.ts 주석의 PATCH 관련 문장이 **사실이 아니며**(살아 있는 호출자가 있다) 그 문장이 실재하는 dev 405 발산을 "결함 아님"으로 기록했다. #528과 동일한 실패 모드였다.

---

## 검증 환경

- 워크트리: `/Users/apple/Desktop/개인프로젝트/opensamguk-meta/worktrees/opensamguk/proxy-topo-516`
- Node v26.5.1, vitest 3.2.7
- 변경 파일 2개 확인:

```
$ git diff origin/main...HEAD --stat
 web/gateway/__tests__/game-api-proxy-route.test.ts | 31 +++++++++++++++++++++-
 web/gateway/app/api/game/[...path]/route.ts        | 24 +++++++++++++++++
 2 files changed, 54 insertions(+), 1 deletion(-)
```

route.ts 변경은 주석 24줄 추가뿐 — 로직 무변경 주장은 사실이다(diff 전체가 블록 주석).

베이스라인 green 재현:

```
$ cd web/gateway && npx vitest run
 Test Files  24 passed (24)
      Tests  175 passed (175)
```

---

## F1 (High) — route.ts 주석의 PATCH 문장이 거짓이고, 실재하는 dev/prod 발산을 은폐한다

### 주석이 주장하는 것

`web/gateway/app/api/game/[...path]/route.ts` 상단:

> - HTTP 메서드: 이쪽은 GET/POST/PATCH/DELETE, `web/game` 쪽은 GET/POST뿐 —
>   `web/game/lib/api.ts`의 PATCH 헬퍼는 현재 무호출(dead code)이라 실사용 격차는 없다.
>   의도된 차이는 아니고 단지 아직 아무도 필요로 하지 않았을 뿐 — 결함 아님, 관찰만.

### 반증 — 호출자는 0건이 아니라 살아 있는 어드민 화면이다

```
$ grep -rn --include='*.ts' --include='*.tsx' "\bpatch<" web/game | grep -v node_modules
web/game/lib/api.ts:803:            patch<AdminGameSettingsPatchResponse>('/api/admin/game-settings', { values }),

$ grep -rn --include='*.ts' --include='*.tsx' "patchGameSettings" web | grep -v node_modules
web/game/app/game/admin1/page.tsx:59:            const result = await api.admin.patchGameSettings({ [key]: value });
web/game/__tests__/admin1-route.test.tsx:61:        await waitFor(() => expect(apiMocks.patchGameSettings).toHaveBeenCalledWith({ maxgeneral: 650 }));
web/game/lib/api.ts:802:        patchGameSettings: (values: Record<string, string | number>) =>
```

`web/game/app/game/admin1/page.tsx:59` 는 게임 설정 저장 버튼의 실제 핸들러(`save`)다. dead code 가 아니다. 전용 테스트(`admin1-route.test.tsx:61`)까지 있다.

### 실제로 나가는 요청 경로

```
$ sed -n '348,354p' web/game/lib/api.ts
async function fetchGame(path: string, init?: RequestInit): Promise<Response> {
    const res = await fetch(`${BASE}${path}`, init);
...
$ grep -n "^const BASE" web/game/lib/api.ts
5:const BASE = '/api/game';
```

따라서 브라우저가 실제로 보내는 요청은:

`PATCH /api/game/api/admin/game-settings`

### 그 요청이 dev 에서 어디로 가고 무엇을 받는가

```
$ grep -n "location /api" infra/nginx/default.conf
...
103:    location /api/admin/ {
104:        proxy_pass http://game-api:18080/api/admin/;
156:    location /api/game/ {
160:        proxy_pass http://game-frontend:3001;
170:    location /api/ {
171:        proxy_pass http://game-api:18080/api/;
```

nginx prefix 매칭에서 `/api/game/` 가 `/api/` 보다 길어 우선한다 → `game-frontend:3001` = `web/game` 의 프록시 route. 그 route 가 export 하는 메서드:

```
$ grep -n "^export async function" "web/game/app/api/game/[...path]/route.ts"
159:export async function GET(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
164:export async function POST(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {

$ grep -n "^export async function" "web/gateway/app/api/game/[...path]/route.ts"
215:export async function GET(
223:export async function POST(
231:export async function PATCH(
239:export async function DELETE(
```

`web/game` route 에 `PATCH` export 가 없다. Next.js App Router 는 정의되지 않은 메서드에 **405 Method Not Allowed** 를 돌려준다. 즉 admin1 의 설정 저장은 **dev 토폴로지에서 405, 프로덕션(gateway 경유)에서 정상** — 이 이슈가 다루는 dev/prod 발산 그 자체다.

또한 405 는 401 이 아니므로 `fetchGame` 의 401 재시도 경로에도 걸리지 않고(`api.ts:350` `if (res.status !== 401) return res;`), `patch` 는 `405: Method Not Allowed` 를 그대로 던져 화면에 뜬다.

### 왜 이것이 High 인가

- 주석이 "호출자 0건" 이라는 **검증 가능한 거짓 사실**을 코드에 박아 넣었다. 지시받은 대로 문장 단위 대조에서 걸린 항목이고, #528 에서 정확히 이 방식으로 오판 근거가 만들어졌다.
- "호출자 0건이므로 결함 아님" 이라는 추론은 전제가 무너지면 결론도 무너진다. 전제가 거짓이므로 이 발산은 **관찰이 아니라 결함**이다.
- 이슈 #516 의 주제가 dev/prod 발산인데, 이 커밋은 살아 있는 발산 하나를 "결함 아님" 으로 기록하면서 닫는다.

### 요구 조치

1. 주석의 해당 문단을 사실로 교정(호출자 있음, dev 405 발생).
2. `web/game` route 에 `PATCH`(및 대칭성을 위해 `DELETE`) export 추가 — `forward` 를 그대로 재사용하므로 각각 4줄. 이것이 발산을 없애는 가장 짧은 수정이다.
3. dev route 의 PATCH 통과 회귀 테스트 1건.

---

## F2 (Medium) — 살아남는 뮤테이션: gateway 프록시에 POST 본문 전달 단언이 없다

§6 의 목표가 "프로덕션 경로(web/gateway)에 dev 쌍둥이만큼의 커버리지를 채운다" 인데, dev 쪽이 가진 단언 하나가 여전히 비어 있다.

뮤테이션: `init.body = await req.text();` → 본문을 버리도록 변경.

```
### M2: drop request body forwarding
 Test Files  24 passed (24)       Tests  175 passed (175)
```

전체 175 테스트 중 **한 건도 실패하지 않는다**. 프록시 테스트 파일 단독으로도 동일:

```
### M2b: drop body forwarding
 Test Files  1 passed (1)       Tests  14 passed (14)
```

대조 — dev 쌍둥이는 이 단언을 가지고 있다(`web/game/__tests__/game-api-proxy-route.test.ts:148`):

```
    expect(fetchMock).toHaveBeenCalledWith('http://pep-game-api/api/command/test?turnIdx=0', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store',
      duplex: 'half',
      body: JSON.stringify({ value: 1 }),
    });
```

새로 추가된 gateway 401 테스트는 `POST` 를 쓰지만 **본문 없는 요청**이고 `fetch` 인자를 검사하지 않는다(호출 횟수만 검사). 프로덕션 프록시에서 커맨드 POST 본문이 유실돼도 테스트가 잡지 못한다.

요구 조치: gateway 테스트에 본문 포함 POST 의 `toHaveBeenCalledWith` 단언 1건 추가.

---

## F3 (Low) — 403 을 200 으로 뭉개는 뮤테이션이 살아남는다 / 주석의 "검증됨" 이 과장

뮤테이션: JSON 경로에서 `status: upstream.status` → `upstream.status === 403 ? 200 : upstream.status`.

```
### M4: coerce 403 to 200 on JSON path
 Test Files  1 passed (1)       Tests  14 passed (14)
```

살아남는다. 두 프록시 테스트 어느 쪽에도 403 케이스가 없다:

```
$ grep -rn "403" web/game/__tests__/game-api-proxy-route.test.ts web/gateway/__tests__/game-api-proxy-route.test.ts
(출력 없음)
```

이는 양쪽 대칭인 선존 구멍이라 이 커밋이 만든 회귀는 아니다. 다만 route.ts 주석의

> - 401/에러 등급 그대로 전파, SSE 401-우선-확인(#514) 동작 동일 — 검증됨(이 파일 테스트).

에서 "에러 등급" 은 401 만 검증됐다. `isAuthFailure` 계약이 401|403 두 값을 다루는 만큼(아래 §확인된 주장) 403 도 검증 대상에 속한다. 문장을 "401 검증됨" 으로 좁히거나 403 테스트를 추가할 것.

---

## 검증 결과 — 구현자 주장 중 사실로 확인된 것

### (a) 발산 지점 5개 열거는 완전하다

주석/코드를 정규화(따옴표·들여쓰기·주석 제거)해 두 파일을 나란히 diff 했다:

```
$ norm "web/game/app/api/game/[...path]/route.ts" > gm.txt
$ norm "web/gateway/app/api/game/[...path]/route.ts" > gw.txt
$ diff -u gm.txt gw.txt
```

diff 에 나타난 의미 있는 차이는 registry 해석 함수군, HTTP 메서드 export, `duplex`, `isEventStream` 헬퍼 추출(순수 리팩터)뿐이다. 지시서가 지목한 미열거 후보들은 **양쪽 모두 존재하지 않아 차이가 아니다**:

| 항목 | web/game | web/gateway | 발산 |
|---|---|---|---|
| 타임아웃 | 없음 | 없음 | 없음 |
| `redirect` 옵션 | 없음 | 없음 | 없음 |
| `cache` | `'no-store'` | `'no-store'` | 없음 |
| `next` 옵션 | 없음 | 없음 | 없음 |
| 요청 헤더 전달 | Authorization + Content-Type 만 | 동일 | 없음 |
| 응답 헤더 전달 | Content-Type 만 | 동일 | 없음 |
| `Set-Cookie` 전파 | 안 함 | 안 함 | 없음 |
| SSE 경로 | 동일 로직 | 동일 로직 | 없음 |
| 에러 시 body 소비 | `upstream.text()` | 동일 | 없음 |
| AbortSignal | SSE 에만 | SSE 에만 | 없음 |
| fetch 예외 try/catch | 없음 | 없음 | 없음 |

`Set-Cookie` 미전파는 양쪽 공통이라 발산은 아니다(게임 API 가 쿠키를 심지 않는 설계 전제). 열거 누락은 **없다**.

### (b) `duplex` 무관 주장 — 실측으로 사실

Node v26.5.1 에서 gateway route 의 init 형태(문자열 body, duplex 없음)를 그대로 태웠다:

```
$ node -v
v26.5.1
$ node -e "...(실제 http 서버 띄우고 fetch)..."
NO-duplex string body => 200 {"method":"POST","body":"{\"v\":1}"}
stream NO-duplex FAILED: RequestInit: duplex option is required when sending a body.
```

`duplex` 는 **ReadableStream body 에만** 요구된다. 두 route 모두 `req.text()` 로 문자열을 만들어 넣으므로 gateway 에 `duplex` 가 없는 것은 무해하다. 구현자 주장 (4) 는 사실이다.

### (c) `sam_refresh` 서버사이드 재시도 없음 — 의도된 설계, 사실

```
$ cat web/gateway/lib/cookies.ts   # REFRESH_PATH = '/api/auth'
$ cat web/game/lib/cookies.ts      # REFRESH_PATH = '/api/auth'
```

양쪽 동일하게 `path=/api/auth` 로 좁혀 심는다. RFC 6265 path-match 상 브라우저가 `/api/game/**` 요청에 실어 보내지 않으므로 서버 코드로 얻을 수 없다. 주석 문장은 사실이다.

### (d) #514/#530 회귀 방지 유지 — 사실

이 커밋의 diff 는 `web/gateway/app/api/game/**` 와 그 테스트 2개 파일뿐이며 `app/api/auth/me/route.ts` 는 포함되지 않는다. 계약 자체도 그대로다:

```
$ grep -n "401\|403\|502\|isAuthFailure" web/gateway/app/api/auth/me/route.ts
14:function isAuthFailure(status: number): boolean {
15:    return status === 401 || status === 403;
33:            if (!isAuthFailure(r.status)) {
35:                return NextResponse.json({ error: '일시적 오류가 발생했습니다.' }, { status: 502 });
...
```

`isAuthFailure` = 401|403, 일시적 오류는 502 + 쿠키 미변경. 훼손 없음.

#530 회귀(연결 실패인데 event-stream 을 열어버림)에 대한 뮤테이션도 잡힌다:

```
### M5: #530 regression — open stream regardless of upstream.ok
 Test Files  1 failed (1)       Tests  1 failed | 13 passed (14)
```

### (e) 구현자가 제시한 뮤테이션은 실제로 핀포인트다

```
### M6: hardcode 200 on JSON path
 Test Files  1 failed (1)       Tests  1 failed | 13 passed (14)
```

주장한 1 failed / 13 passed 와 정확히 일치한다. 쿼리스트링 전달 뮤테이션도 잡힌다:

```
### M7: drop query-string forwarding
 Test Files  1 failed (1)       Tests  3 failed | 11 passed (14)
```

Authorization 헤더 제거 뮤테이션도 전체 스위트에서 잡힌다:

```
### M1: drop Authorization header
 Test Files  1 failed | 23 passed (24)       Tests  1 failed | 174 passed (175)
```

모든 뮤테이션은 실행 직후 `git checkout --` 로 원복했고, 최종 워킹트리는 깨끗하다:

```
$ git status --short
(출력 없음)
$ git diff origin/main...HEAD --stat
 web/gateway/__tests__/game-api-proxy-route.test.ts | 31 +++++++++++++++++++++-
 web/gateway/app/api/game/[...path]/route.ts        | 24 +++++++++++++++++
```

---

## 범위 한정(§6만 닫고 §5 남김)에 대한 판정

**§6 만 닫는 것 자체는 정당하다** — §5(프로덕션 nginx 에 `/api/game` location 추가 vs 두 프록시 통합)는 인프라 저장소(`opensamguk-docker`)를 건드리는 별개 결정이고, 그 결정 없이도 프로덕션 경로에 회귀 테스트를 다는 것은 독립적인 이득이다. 이슈를 열어 두는 것도 맞다.

**다만 현재 실행된 §6 은 착시를 만든다.** §6 의 목적이 "프로덕션 프록시의 커버리지 구멍 메우기" 인데,

- F1: 살아 있는 발산 하나(PATCH → dev 405)를 메우기는커녕 "결함 아님" 으로 코드에 기록했다.
- F2: dev 쌍둥이가 가진 본문 전달 단언이 프로덕션 쪽에 여전히 없다.

즉 §6 의 산출물이 "발산 5개 전수 조사, 결함 0건" 이라는 강한 문장을 남기는데 그 문장이 부정확하다. F1·F2 를 처리하면 범위 한정은 그대로 유효하다. 범위를 넓힐 필요는 없다.

---

## UNKNOWN — 근거를 확보하지 못해 findings 로 올리지 않은 것

1. **프로덕션 nginx 의 `/api/game` 부재를 이 저장소에서 확인할 수 없다.** 이 저장소의 `nginx/nginx.conf:13` 과 `infra/nginx/nginx.conf:137` 에는 `/api/game/` location 이 **있고** game-api 로 직결한다. 프로덕션 제어면은 `opensamguk-docker` 에 있어 접근 범위 밖이다. 다만 F1 의 405 는 **dev 쪽(`infra/nginx/default.conf:156`)에서 발생**하는 것이고 그 파일은 직접 확인했으므로, 프로덕션 측 전제와 무관하게 성립한다.
2. **admin1 화면의 실 사용 빈도.** 코드상 살아 있는 핸들러임은 확인했으나 실제로 운영자가 dev 에서 이 버튼을 누르는지는 확인하지 못했다. 발산의 존재 여부와는 무관하다.
3. **Next.js 405 응답을 런타임으로 재현하지 못했다.** dev 서버를 띄우지 않고 export 부재(`grep`)로 판정했다. App Router 의 문서화된 동작이지만 실측은 아니다.

---

# 재심 — 커밋 f0ac1a5e (2026-08-24)

초심 c0cbbc6b 위에 올라온 수정 커밋을 독립 재검증했다. 결론: **F1/F2/F3 전부 닫힘, cleared.**

변경 범위는 의도한 4개 파일뿐이고 전부 추가/정정이다:

```
$ git diff c0cbbc6b..f0ac1a5e --stat
 web/game/__tests__/game-api-proxy-route.test.ts    | 27 +++++++++++++++++-
 web/game/app/api/game/[...path]/route.ts           | 13 +++++++++
 web/gateway/__tests__/game-api-proxy-route.test.ts | 33 ++++++++++++++++++++--
 web/gateway/app/api/game/[...path]/route.ts        | 14 ++++++---
 4 files changed, 80 insertions(+), 7 deletions(-)
```

## F1 — 닫힘 (High → 해소)

발산이 코드로 제거되었다:

```
$ grep -n "^export async function" "web/game/app/api/game/[...path]/route.ts"
159:export async function GET(...)
164:export async function POST(...)
172:export async function PATCH(...)
177:export async function DELETE(...)
```

주석의 거짓 문장은 사실로 교체되었고, 문장 단위로 재대조해 전부 확인했다: `api.ts:803` PATCH 헬퍼, `admin1/page.tsx:59` 호출자, `BASE='/api/game'` 경유 `/api/game/api/admin/game-settings`, `infra/nginx/default.conf:156` prefix 우선 — 모두 초심에서 내가 직접 확인한 사실과 일치한다.

회귀 테스트가 실제로 잡는지 뮤테이션으로 검증(단순 export 유무가 아니라 **포워딩 동작**을 잡는지):

```
### baseline (web/game proxy test file)
 Test Files  1 passed (1)       Tests  12 passed (12)

### G1: PATCH가 포워딩 대신 405를 반환하도록 변경
 Test Files  1 failed (1)       Tests  1 failed | 11 passed (12)
```

새 테스트가 method/headers/body/duplex 를 전부 단언하므로 관련 뮤테이션도 함께 잡힌다:

```
### G2: web/game에서 본문 전달 제거
 Test Files  1 failed (1)       Tests  2 failed | 10 passed (12)
### G3: web/game init에서 duplex 제거
 Test Files  1 failed (1)       Tests  4 failed | 8 passed (12)
```

## F2 — 닫힘 (Medium → 해소)

초심에서 **175 테스트 전부를 통과하며 살아남던** 본문 전달 뮤테이션이 이제 잡힌다:

```
### baseline gateway full
 Test Files  24 passed (24)       Tests  176 passed (176)

### M2 재실행: init.body = await req.text() 제거
 Test Files  1 failed | 23 passed (24)       Tests  1 failed | 175 passed (176)
```

구현자가 보고한 RED 1건 / GREEN 176 과 정확히 일치한다. 새 테스트는 dev 쌍둥이와 동형으로 `toHaveBeenCalledWith` 에 `body`, `Authorization`, `Content-Type`, `cache` 를 모두 단언한다.

## F3 — 닫힘 (Low → 해소)

주석이 "401 그대로 전파 — 검증됨(403 등 다른 에러 등급은 양쪽 다 테스트가 없다 — 기존 구멍)" 으로 좁혀졌다. 그 문장 자체가 사실인지 확인:

```
$ grep -rn "403" web/game/__tests__/game-api-proxy-route.test.ts web/gateway/__tests__/game-api-proxy-route.test.ts
none (comment's claim holds)
```

403 미검증은 여전히 열린 구멍이지만, 이제 주석이 그것을 **정확히 그렇게** 기술한다. 과장이 제거된 것이 F3 의 요구사항이었고 충족되었다.

## 스위트·타입체크 재현

```
$ cd web/gateway && npx vitest run
 Test Files  24 passed (24)
      Tests  176 passed (176)

$ cd web/game && npx vitest run
 Test Files  78 passed (78)
      Tests  449 passed (449)

$ cd web/game && npx tsc --noEmit     → exit 0, 출력 없음
$ cd web/gateway && npx tsc --noEmit  → exit 0, 출력 없음
```

가짜 완료 가드:

```
$ grep -rn "it.skip\|describe.skip\|\.only(\|TODO\|FIXME" (4개 변경 파일)
none
```

모든 뮤테이션은 실행 직후 `git checkout --` 로 원복했고 워킹트리는 이 리뷰 문서(untracked)만 남는다.

## OBSERVATION — 차단 사유 아님, 다음 손댈 때 정리 권장

1. **주석 한 문장이 중의적이다.** `web/gateway` route.ts 의 "대칭을 위해 DELETE도 추가 — 현재 호출자는 없지만 이 route에도 없다" 에서 주어가 생략돼 "이 route(gateway)에 DELETE 가 없다" 로도 읽힌다. 실제로는 gateway route 에 DELETE 가 있다:

   ```
   $ grep -n "^export async function DELETE" "web/gateway/app/api/game/[...path]/route.ts"
   245:export async function DELETE(
   ```

   의도한 뜻("DELETE 호출자가 양쪽 다 없다")은 사실이다 — 실제로 저장소 전체에 `/api/game` 으로 가는 DELETE 호출자는 없다(모든 `method: 'DELETE'` 는 `/api/board`, `/api/account`, `/api/proxy` 행). 거짓 진술은 아니지만 F1 이 정확히 "주석 오독" 사고였던 만큼 "DELETE 호출자는 양쪽 다 없다" 로 명확히 쓰는 편이 낫다.

2. **테스트 카운트 보고 오차.** 구현자는 web/game 을 "기존 449 그대로 유지" 라고 했으나 `it()` 은 7 → 8 로 하나 늘었다(따라서 이전 총계는 448). 실측 449 green 은 사실이고 스킵도 없으므로 결과에는 영향이 없다.

3. **403 등급 테스트는 여전히 없다.** 양쪽 대칭인 선존 구멍이고 주석에 명시되었으므로 이 PR 의 차단 사유는 아니다. #516 §5 를 처리할 때 두 프록시를 합치면서 한 번에 덮는 것이 가장 싸다.

## 범위 판정 (변경 없음)

§6 만 닫고 §5(프로덕션 nginx location vs 프록시 통합)를 이슈에 남기는 것은 여전히 정당하다. 초심에서 지적한 "착시" 는 F1 이 실제 코드 수정으로 닫히고 주석이 사실로 교정되면서 해소되었다 — 이제 §6 산출물이 남기는 문장이 전부 검증 가능한 사실이다.

---

# 2차 재심 — 커밋 6dd238b4 (#516 §5 프록시 통합)

f0ac1a5e(F1을 web/game route에 PATCH/DELETE export 추가로 처리)는 되돌려졌다. 사용자가 §5 방향을 (B) 프록시 통합으로 결정해, web/game의 `/api/game` route와 전용 `lib/serverRegistry.ts`를 삭제하고 web/gateway route가 dev/prod 유일 프록시가 되었다. **위 "재심(f0ac1a5e)" 절은 폐기된 상태에 대한 기록이다.**

```
$ git diff origin/main...6dd238b4 --stat
 docker-compose.production.yml                      |   6 +-
 docker-compose.yml                                 |  11 +-
 web/game/.env.example                              |  13 +-
 web/game/__tests__/game-api-proxy-route.test.ts    | 224 ---------------------
 web/game/__tests__/serverRegistry.test.ts          | 112 -----------
 web/game/app/api/game/[...path]/route.ts           | 167 ---------------
 web/game/lib/server-api.ts                         |  10 +-
 web/game/lib/serverRegistry.ts                     | 112 -----------
 web/game/middleware.ts                             |   4 +-
 web/game/next.config.mjs                           |  12 ++
 web/gateway/.env.example                           |   8 +-
 web/gateway/__tests__/game-api-proxy-route.test.ts | 139 ++++++++++++-
 web/gateway/app/api/game/[...path]/route.ts        |  23 +++
 13 files changed, 206 insertions(+), 635 deletions(-)
```

**통합 자체는 옳은 방향이다** — 중복 구현을 지우는 것이 발산 재발을 막는 유일한 구조적 수정이고, 삭제 635줄/추가 206줄로 순감이다. 아래 검증 항목은 R1 하나를 빼고 전부 통과했다.

---

## R1 (Blocker → **반증됨, 종결**) — 라이브 prod nginx 전제

> **2026-08-24 라이브 실측으로 이 findings 는 기각되었다. 아래는 제기 당시의 기록이고, 결론은 이 절 뒤의 "R1 종결" 절에 있다.** 제기 근거였던 `infra/nginx/default.conf` 헤더가 거짓임이 확인됐다.

구현자의 §4 근거 체인은 "이 저장소 `infra/nginx/default.conf`는 안 쓰임 → 진짜 발산은 코드 레벨 dev 전용" 이다. 그런데 **그 파일 자신의 헤더가 정반대를 주장한다**:

```
$ sed -n '1,9p' infra/nginx/default.conf
# 라이브 prod nginx 설정 (EC2 박스 ~/opensamguk/docker/nginx/default.conf 의 버전관리 정본).
#
# 박스 compose(docker-compose.production.yml)가 `./docker/nginx/default.conf` →
# `/etc/nginx/conf.d/default.conf`(ro)로 마운트한다. deploy.yml이 이 파일을 박스로 scp 동기화 후
# nginx를 force-recreate(업스트림 IP 재해석 + 새 설정 적용)하므로, 라이브 라우팅 변경은 반드시 여기서 한다.
#
# 토폴로지: 서비스명 gateway-frontend(:3000)/game-frontend(:3001)/gateway-api(:18081)/game-api(:18080).
# (주의: 레포 `infra/nginx/nginx.conf`는 로컬/레포 compose 전용의 다른 토폴로지 — web-gateway/8081.
#  박스/레포 compose 수렴은 별도 후속.)
```

그리고 그 파일의 라우팅은 게이트웨이가 아니라 **game-frontend** 로 간다:

```
$ sed -n '156,161p' infra/nginx/default.conf
    location /api/game/ {
        ...
        proxy_pass http://game-frontend:3001;
```

즉 헤더를 믿으면 라이브 박스에서 `/api/game/**` 는 **web/game 컨테이너로 가고**, 이 커밋은 바로 그 컨테이너의 프록시 route를 삭제했다.

### 왜 이것이 Blocker 인가 — 폴백이 안전하지 않다

삭제 후 그 경로는 전적으로 `next.config.mjs` 의 rewrite에 의존한다:

```
destination: `${process.env.GATEWAY_WEB_URL || 'http://localhost:3000'}/api/game/:path*`,
```

`GATEWAY_WEB_URL` 이 없으면 **game-frontend 컨테이너 자기 자신의 localhost:3000** 으로 간다 — 거기엔 아무것도 없다(그 컨테이너는 3001을 듣는다). 결과는 ECONNREFUSED, 즉 인게임 data fetch 전면 중단이다. 조용히 degrade 하지 않고 전부 깨진다.

이 커밋은 `docker-compose.yml` 과 `docker-compose.production.yml` 에 `GATEWAY_WEB_URL` 을 넣었지만, **박스가 그 파일들을 쓴다는 증거가 없다.** deploy.yml 이 실제로 쓰는 compose 는 이 저장소에 존재하지 않는다:

```
$ grep -n "COMPOSE=" .github/workflows/deploy.yml
270:          COMPOSE="docker compose -p opensamguk-shared -f docker-compose.shared.yml --env-file .env"

$ ls docker-compose*.yml
docker-compose.production.yml
docker-compose.v2-sandbox.yml
docker-compose.yml          ← docker-compose.shared.yml 없음

$ grep -n "game-frontend" .github/workflows/deploy.yml
338:          if grep -Fxq 'game-frontend' <<<"$shared_services"; then
339:            SHARED_SERVICES="$SHARED_SERVICES game-frontend"
417:            $COMPOSE up -d --force-recreate --no-deps game-frontend
```

deploy.yml 은 박스 쪽 `docker-compose.shared.yml` 의 `game-frontend` 를 재기동한다. 그 파일은 이 저장소에 없으므로 **이 커밋이 그 컨테이너에 `GATEWAY_WEB_URL` 을 넣어줄 방법이 없다.** 동시에 `GAME_API_URL` 은 코드에서 삭제되어(`web/game/lib/server-api.ts`) 예전 경로도 남지 않는다.

`docker/nginx/default.conf` 를 마운트하는 compose 도 이 저장소엔 없다 — 헤더가 지목한 마운트가 이 저장소의 `docker-compose.production.yml` 에는 존재하지 않는다(그건 `infra/nginx/nginx.conf` 를 마운트한다):

```
$ grep -rn "docker/nginx" docker-compose*.yml .github/workflows/*.yml
(출력 없음)
$ grep -n "nginx.conf" docker-compose.production.yml
288:      - ${COMPOSE_HOST_DIR:-...}/infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
```

### 판정

이 저장소만으로는 라이브 라우팅을 확정할 수 없다 — 그리고 그것이 정확히 이슈 #516 의 주제이자 "테스트만으로 닫지 마라" 의 이유다. 현재 상태는 두 갈래다:

- 라이브 nginx 가 실제로 게이트웨이로 보낸다면 → 안전하고, 다만 §4 근거 체인의 "default.conf 는 안 쓰임" 문장이 그 파일 헤더와 모순되므로 **어느 쪽이 stale 인지 밝혀 기록해야 한다.**
- 라이브 nginx 가 헤더 말대로 `game-frontend:3001` 로 보낸다면 → **머지·배포 시 인게임 전면 장애.**

**요구 조치(택1, 배포 전):**
1. 박스의 실제 nginx `location /api/game/` 업스트림과 `game-frontend` 컨테이너의 `GATEWAY_WEB_URL` 유무를 확인해 증거를 이슈에 붙인다. (나는 프로덕션 박스 접근 금지라 확인할 수 없다.)
2. 또는 라이브 nginx 가 game-frontend 로 보내는 것이 맞다면, `infra/nginx/default.conf` 의 `/api/game/` 를 게이트웨이로 바꾸고 그 변경이 박스에 반영된 뒤에 이 커밋을 배포한다.
3. 어느 쪽이든 `infra/nginx/default.conf` 헤더와 §4 주장 중 틀린 쪽을 고쳐, 다음 사람이 같은 모순을 다시 밟지 않게 한다.

`GATEWAY_WEB_URL` 폴백을 `localhost:3000` 대신 **명시적 실패**(미설정 시 throw)로 바꾸는 것도 고려할 만하다 — 지금 폴백은 오설정을 조용한 ECONNREFUSED 로 바꾸기만 한다.

---

## R2 (통과) — 삭제는 안전하다, 죽은 코드 캐스케이드 없음

```
$ grep -rn "serverRegistry\|GAME_API_URL\|resolveGameApiUrl" web/game --include='*.ts' --include='*.tsx' | grep -v node_modules
web/game/lib/server-api.ts:9:// ... GAME_API_URL was only ever read   ← 주석 한 줄뿐

$ ls web/game/lib/serverRegistry.ts web/game/app/api/game
ls: web/game/app/api/game: No such file or directory
ls: web/game/lib/serverRegistry.ts: No such file or directory
```

남은 임포터 0건. `middleware.ts` 의 `sam_server` 쿠키는 유지되고(게이트웨이 프록시가 읽는다) 주석도 그에 맞게 갱신됐다. 타입체크로도 확인:

```
$ cd web/game && npx tsc --noEmit   → exit 0, 출력 없음
$ cd web/gateway && npx tsc --noEmit → exit 0, 출력 없음
```

## R3 (통과) — `rewrites()` 는 실제로 동작한다 (런타임 실측)

주장을 문서로 믿지 않고 실제로 띄워서 확인했다. `GATEWAY_WEB_URL` 을 헤더를 되비추는 echo 서버로 향하게 하고 `next dev` 를 올렸다:

```
$ GATEWAY_WEB_URL=http://127.0.0.1:9999 npx next dev -p 3101
   ▲ Next.js 15.5.20   ✓ Starting...

$ curl -X PATCH 'http://localhost:3101/api/game/api/admin/game-settings?server=pep' \
    -H 'Content-Type: application/json' -H 'Cookie: sam_access=THETOKEN; sam_server=pep' \
    -d '{"values":{"maxgeneral":650}}'
HTTP 200
{"url":"/api/game/api/admin/game-settings?server=pep","method":"PATCH",
 "cookie":"sam_access=THETOKEN; sam_server=pep","ct":"application/json",
 "body":"{\"values\":{\"maxgeneral\":650}}"}

$ curl -X DELETE http://localhost:3101/api/game/api/thing/1 -H 'Cookie: sam_access=THETOKEN'
HTTP 200  {"url":"/api/game/api/thing/1","method":"DELETE","cookie":"sam_access=THETOKEN",...}

$ curl 'http://localhost:3101/api/game/api/front-info?minVersion=3' -H 'Cookie: sam_access=THETOKEN'
HTTP 200  {"url":"/api/game/api/front-info?minVersion=3","method":"GET","cookie":"sam_access=THETOKEN",...}
```

확인된 것: rewrite 발화, **`Cookie` 헤더 원문 그대로 전달**(`sam_access`·`sam_server` 둘 다), 메서드 보존(PATCH/DELETE 포함), 본문 보존, 쿼리스트링 보존. 주석의 쿠키 근거(RFC 6265 포트 무시)는 브라우저→3001 홉에 대해 맞고, 3001→3000 홉은 Next 가 헤더를 그대로 프록시해서 성립한다 — 두 홉이 다른 메커니즘인데 주석이 한 문장으로 뭉뚱그린 점은 사실 오류는 아니다.

**SSE 도 버퍼링되지 않고 흐른다** — 700ms 간격으로 tick 을 보내는 upstream 을 태워 도착 시각을 찍었다:

```
04:41:00  : open
04:41:00  data: tick1
04:41:01  data: tick2
04:41:02  data: tick3
```

간격이 보존된다(버퍼링이면 셋이 한꺼번에 도착한다). `/api/game/sse/turn` 이 이 홉을 타도 실시간성이 죽지 않는다.

## R4 (통과) — 새 테스트 3종이 실제로 회귀를 잡는다

베이스라인 `18 passed (18)`. 뮤테이션 4종 전부 RED:

```
### A: SSE 게이트 !upstream.ok → upstream.status === 401 로 좁힘
 Test Files  1 failed (1)       Tests  1 failed | 17 passed (18)
### B: JSON 경로에서 403 → 200 뭉개기
 Test Files  1 failed (1)       Tests  1 failed | 17 passed (18)
### C: PATCH 가 forward 대신 405 반환
 Test Files  1 failed (1)       Tests  1 failed | 17 passed (18)
### D: init.body = await req.text() 제거
 Test Files  1 failed (1)       Tests  2 failed | 16 passed (18)
```

A 가 특히 중요하다 — 초심 F3 에서 살아남던 뮤테이션이고, `!upstream.ok` 가 401 전용 체크가 아님을 증명하는 유일한 테스트다. C 는 F1 이 잡았던 admin1 게임설정 저장 405 를 실제 시나리오(`/api/admin/game-settings`, PATCH, values 본문)로 고정한다. D 는 F2.

## R5 (통과) — 스위트·타입체크

```
$ cd web/gateway && npx vitest run   → 24 files / 179 tests passed
$ cd web/game    && npx vitest run   → 430 tests passed
```

web/game 이 449 → 430 인 것은 삭제한 두 테스트 파일(proxy 8건 + serverRegistry 11건)의 감소분과 일치한다. 삭제된 테스트는 삭제된 코드만 덮고 있었으므로 커버리지 손실이 아니다. skip/only/TODO 없음.

## R6 (통과) — route.ts 주석 문장 단위 대조

새 주석에서 **거짓 문장을 찾지 못했다.** 문장별:

| 주석 문장 | 확인 |
|---|---|
| dev/prod 모두 이 route 하나로 온다 | 코드상 참(web/game route 삭제됨) — 단 prod 은 R1 미해결 |
| web/gateway는 원래 GET/POST/PATCH/DELETE, web/game은 GET/POST뿐 | origin/main 대조로 참 |
| `patchGameSettings` 가 admin1 저장 버튼의 살아 있는 호출자 | 참(초심에서 직접 확인) |
| `pnpm dev` 단독 실행 흐름에서 405 | 참 |
| 근본 수정은 증상이 아니라 통합 | 참 |
| `rewrites()` 가 이 route로 넘긴다 | R3 에서 런타임 실측 |
| nginx `location /api/game/` 가 이미 여기로 보낸다(`infra/nginx/nginx.conf`) | 참 — `nginx.conf:138 proxy_pass http://web_gateway` |
| `!upstream.ok` 는 상태코드 특별취급 안 함 | R4 뮤테이션 A 로 증명 |
| sam_refresh 재시도 없음 | 참 |

이전의 거짓 "PATCH 헬퍼 무호출" 문장은 완전히 제거됐고, f0ac1a5e 에서 지적한 중의적 DELETE 문장도 사라졌다.

**초심 기록 정정**: 초심 F1 에서 나는 dev 405 의 경로 근거로 `infra/nginx/default.conf:156` 을 들었다. 그 파일은 어떤 저장소 compose 도 마운트하지 않으므로(R1) 그 경로 설명은 틀렸다 — 실제 발생 경로는 구현자가 새 주석에 쓴 대로 nginx 없는 `pnpm dev` 단독 실행이다. 버그 자체와 수정 필요성은 그대로다.

---

## UNKNOWN — 근거를 확보하지 못한 것

1. **라이브 박스의 실제 nginx 업스트림과 `game-frontend` 의 `GATEWAY_WEB_URL`.** R1 의 핵심이며 프로덕션 박스 접근 금지라 확인 불가.
2. **`.env.example` 2개(web/game, web/gateway) 미검토.** 하드 룰(`.env*` 읽기 금지)에 따라 열지 않았다. `GATEWAY_WEB_URL` 문서화가 그 안에 있을 텐데 검증하지 못했다 — 다른 리뷰어가 봐야 한다.
3. **`docker-compose.v2-sandbox.yml` 은 이번 커밋에서 갱신되지 않았다** — `GAME_API_URL: http://game-api:8081`(이제 아무도 읽지 않음)이 남아 있고 `GATEWAY_WEB_URL` 이 없다. 그 스택의 nginx 는 `infra/nginx/nginx.conf` 를 마운트해 `/api/game/` 를 `web_gateway` 로 보내므로 rewrite 가 발화할 일이 없어 지금은 무해하지만, 누군가 그 컨테이너의 :3001 을 직접 때리면 localhost:3000 폴백에 걸린다. 정리 권장, 차단 사유 아님.

---

# R1 종결 — 라이브 실측으로 반증 (2026-08-24)

team-lead 가 GCP 박스에서 읽기 전용으로 재측정했다. `docker exec opensamguk-nginx nginx -T` 가 로드한 설정은 `/etc/nginx/nginx.conf` 하나뿐이고 `conf.d` 마운트가 없다:

```
upstream web_gateway { server web-gateway:3000; keepalive 32; }
location /api/gateway/ { proxy_pass http://gateway_api/; }
location /api/board/   { proxy_pass http://web_gateway/api/board/; }
location /api/         { proxy_pass http://web_gateway/api/; client_max_body_size 2m; }
```

`location /api/game/` 가 **없다** — `/api/game/**` 는 `/api/` catch-all 로 **web-gateway:3000** 에 간다. `game-frontend` 로 가는 건 페이지 라우트(`^/game/[a-z0-9]{1,48}`)뿐이다.

내가 근거로 삼은 `infra/nginx/default.conf` 헤더는 거짓이었다: 박스에 `~/opensamguk/docker/nginx/default.conf` 는 존재하지 않고, 실제 마운트 원본은 **다른 저장소**(`opensamguk-docker/infra/nginx/nginx.conf`)이며 수동 `scripts/deploy.sh:21` 이 그것만 scp 한다. `deploy.yml` 은 nginx 설정을 동기화하지 않는다 — 내가 grep 으로 못 찾은 게 맞았다. 즉 그 파일은 **배포되지도 마운트되지도 로드되지도 않으면서 자기 헤더에 "라이브 prod 정본" 이라 적어둔 파일**이다(PR #505 리뷰 두 패스를 망친 전력 있음, 헤더 정정은 별도 이슈).

**R1 기각.** `web/game/app/api/game/[...path]/route.ts` 를 지워도 라이브 트래픽은 그 라우트를 타지 않는다.

## R7 (통과) — `GATEWAY_WEB_URL` 미설정은 프로덕션에서 도달 불가

실측: `spep-web-game` 컨테이너의 환경변수 키는 `GAME_API_URL GATEWAY_API_URL NODE_ENV NODE_VERSION PATH PORT SERVER_ID YARN_VERSION` — `GATEWAY_WEB_URL` 없음. 따라서 rewrite 가 발화하면 `http://localhost:3000` 폴백에 걸리고 그 컨테이너는 3001 을 들으므로 ECONNREFUSED 다. **문제는 rewrite 가 발화할 수 있느냐**이고, 그러려면 `/api/game/*` 요청이 web-game 의 Next 서버에 도달해야 한다. 도달 경로를 코드로 전수 확인했다 — 셋 다 막혀 있다:

**(1) 브라우저 → nginx**: 위 실측대로 `/api/` catch-all 이 web-gateway 로 보낸다. web-game 은 페이지 라우트만 받는다.

**(2) middleware**: `/api/game` 을 아예 매칭하지 않는다.

```
$ grep -n "matcher" -A 2 web/game/middleware.ts
129:  matcher: ['/game', '/game/:path*'],
```

**(3) 서버사이드 fetch / SSR / RSC**: `lib/api.ts`(`BASE='/api/game'`)의 임포터가 전부 클라이언트 컴포넌트다 — 서버에서 그 상대 URL을 fetch 하는 호출자가 0건이다.

```
$ (web/game/app, web/game/components 의 lib/api 임포터 전수 확인 — 'use client' 여부)
  → 모든 임포터가 'use client' (서버 사이드 호출자 0건)
```

(애초에 Server Component 가 상대 URL `/api/game/...` 을 fetch 하면 base URL 이 없어 그 자리에서 throw 한다 — 조용히 rewrite 를 타는 시나리오 자체가 성립하지 않는다.)

**(4) 컨테이너 자기 자신을 때리는 절대 URL**: 없다. `localhost:3001`/`localhost:3000` 참조는 rewrite 폴백 자신, playwright/e2e 설정, `NEXT_PUBLIC_GATEWAY_URL`(브라우저용, 게이트웨이 대상)뿐이다.

```
$ grep -rn "localhost:3001\|localhost:3000" web/game --include='*.ts' --include='*.tsx' --include='*.mjs'
web/game/next.config.mjs:38   ← rewrite 폴백 자신
web/game/playwright.config.ts:14, web/game/e2e/*.spec.ts   ← e2e 도구
web/game/lib/server-api.ts:22  ← NEXT_PUBLIC_GATEWAY_URL, 브라우저용
```

**판정: 도달 불가. blocker 아님.** rewrite 는 nginx 없이 `pnpm dev` 단독 실행하는 프론트 dev 흐름 전용이고, 그 흐름에서는 `localhost:3000` 폴백이 **정확히 맞는 기본값**이다(게이트웨이 dev 서버가 그 포트를 쓴다). 프로덕션 컨테이너에 `GATEWAY_WEB_URL` 이 없는 것은 결함이 아니라 그 변수가 프로덕션에서 쓰이지 않기 때문이다.

다만 R1 이 반증된 지금도 남는 잔여 리스크 하나는 기록해 둔다: **라이브 nginx 가 `/api/game/` 를 `/api/` catch-all 에 의존해 잡고 있다.** 다른 저장소의 nginx.conf 에 `/api/game/` 나 더 긴 매칭 location 이 추가되면 이 경로가 조용히 바뀐다. 차단 사유는 아니고, #516 을 닫을 때 그 저장소 쪽에 명시적 `location /api/game/ { proxy_pass http://web_gateway; }` 를 두는 편이 catch-all 의존보다 안전하다는 제안이다.

## 최종 판정

6dd238b4 에 대한 결함 0건. **cleared.**

R1 은 기각됐지만 제기 자체는 유효했다 — 저장소 안의 거짓 헤더가 실측을 부른 것이고, 그 헤더가 남아 있는 한 다음 리뷰어도 같은 함정을 밟는다(별도 이슈로 정정 예정).
