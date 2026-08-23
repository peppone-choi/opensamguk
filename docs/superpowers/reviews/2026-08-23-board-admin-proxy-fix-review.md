# Admin BoardControl proxy-path regression — independent critique

PR: https://github.com/peppone-choi/opensamguk/pull/504

Scope: web/

Verdict: cleared

Independent review of commit `a8b835da` on `work/opensamguk/board-admin-proxy-fix`.
The reviewer is not the author and read the target route, the retired generic proxy,
the board-api authorization path, and the tests directly rather than relying on the
author's summary.

## The regression is real

`web/gateway/app/api/proxy/[...path]/route.ts` forwards to `GATEWAY_API_URL`. After
`c8507bfa` (OPENSAM-208) the board endpoints live in `board-api`; `app/gateway-api/src/main/kotlin`
contains no board controller (grep for `board` there hits only `DeployService.kt` and
`ServerRegistry.kt`). So every admin call under `/api/proxy/board/...` was being forwarded to a
service that no longer serves that path. The admin board tab was dead, not merely misrouted.

## The target route does support all three calls

Read verbatim from `web/gateway/app/api/board/[...path]/route.ts`:

- `GET /api/board/posts?...` → `isPublicReadPath(['posts'])` is true → `forward(..., 'GET', 'optional')`.
- `PATCH /api/board/posts/7/pin` → `isPinPath` is true → `forward(..., 'PATCH', 'required')`.
- `DELETE /api/board/posts/7` → `isPostMutationPath` is true → `forward(..., 'DELETE', 'required')`.

`isPostId` accepts `/^[1-9]\d*$/`, which the numeric `post.id` from the DTO satisfies.
The query string is forwarded verbatim via `request.nextUrl.search`, so `includeDeleted=true`,
`category`, `page`, and `size` all reach `board-api` unchanged. `Content-Type` is forwarded for
`PATCH` (the pin call sets it) and the body is read for non-GET/DELETE methods, so the
`{"pinned":...}` payload survives. `DELETE` intentionally carries no body and the component sends
none. No mismatch found between what the three call sites send and what the route accepts.

`BOARD_API_URL` is wired in both `docker-compose.yml:305` and `docker-compose.production.yml:242`
(`http://board-api:8083`), with a `localhost:8083` fallback in `web/gateway/lib/server-api.ts`, so
the corrected path is not dead in the deployed topology.

## No residual dead callers

`grep -rn "api/proxy" web/` (excluding `node_modules`, `.next`, build output, and
`tsconfig.tsbuildinfo`) returns only `/api/proxy/admin/...` and `/api/proxy/${path}` call sites in
`app/admin/page.tsx`, `components/admin/MemberControl.tsx`, and the corresponding tests, plus the
generic proxy route and one comment in `lib/cookies.ts`. Those targets are gateway-api admin
endpoints, which still exist. Zero `/api/proxy/board` references remain. The author's grep
reproduces.

## Authorization boundary: not weakened

The two proxies differ. The generic proxy 401s whenever the access cookie is absent, for every
method. The board proxy uses `accessMode: 'optional'` for GET, so an anonymous admin-list request is
forwarded without an `Authorization` header instead of being rejected at the edge. That is a real
behavioral difference and it was checked rather than assumed.

It does not weaken the admin gate, because the gate was never in the proxy. Neither proxy checks the
`ADMIN` role; both only bridge the cookie. The actual authorization lives in
`app/board-api/.../GatewayBoardService.kt:36`, which throws `GatewayBoardForbiddenException` when
`includeDeleted` is requested by anything other than an admin principal, and
`GatewayBoardPostMutationSecurityTest.kt:265` pins that contract for admin (200, unmasked deleted
rows), non-admin user (403), and anonymous (403). Since the admin list always sends
`includeDeleted=true`, the anonymous case is rejected by board-api rather than the proxy — a 403
where there used to be a 401, but never an open door. The mutation paths keep
`accessMode: 'required'`, so pin and delete still 401 at the edge without a cookie.

`ACCESS_COOKIE` is set with `path: '/'` (`web/gateway/lib/cookies.ts`), so it is sent to
`/api/board/*` exactly as it was to `/api/proxy/*`. No cookie-scoping gap.

One incidental improvement: the generic proxy emits `{error: ...}` while the board proxy emits
`{message, status}`, and `responseMessage` in `BoardControl.tsx` reads only `body.message`.
Proxy-level errors that previously degraded to the generic Korean fallback now surface the real
message.

## Defect: the test cannot catch this class of regression

The URL literals in `web/gateway/__tests__/admin-board-control.test.tsx` were updated in lockstep
with the component, and `global.fetch` is stubbed on both sides. The assertions therefore compare the
component's URL against a string in the same file — nothing binds either to a route handler that
actually exists. The bug this PR fixes was not a wrong string; it was a route that stopped existing
underneath a correct-looking string, and a fetch-mocked component test is structurally blind to that.
The test does have some value: the mock's fallback returns 404 for unknown paths, so an unintended
URL change breaks rendering assertions too. But it would not have caught OPENSAM-208's breakage and
will not catch the next one.

`__tests__/board-proxy-route.test.ts` covers the route handlers properly (anonymous public read,
optional Bearer, unauthenticated write rejection, delete and admin pin bridging, invalid paths, 502,
504) — but with hardcoded path arrays of its own. Component and route are each tested against their
own copy of the contract, and the two copies can drift apart silently exactly as they did here.

This is a pre-existing property of the test architecture, not something this PR introduced, and the
fix under review is itself correct and verified. It is recorded as residual risk rather than a
blocker. The cheapest closure is a single assertion that the paths `BoardControl` builds are accepted
by the `/api/board/[...path]` handlers instead of by a hand-written mock.

## Residual, out of scope but user-visible

An expired access JWT (cookie present, token stale) yields 401 on pin/delete and 403 on the list
from board-api. `BoardControl` uses raw `fetch` with no refresh-and-retry, so the admin sees an error
banner and must reload to let `AuthProvider` recover the session via `/api/auth/me`. The PR does not
make this worse; it was equally true through the generic proxy. Flagged because "the board tab shows
an error" will keep being reported until it is addressed.

## UNKNOWN

Not verified: the exact JSON body of `GatewayBoardForbiddenException` as rendered by board-api's
exception handler, i.e. whether a 403 on the admin list produces a `message` field that
`responseMessage` can surface, or falls back to the generic Korean string. The security test asserts
only the status code.

## Evidence

`npx vitest run __tests__/admin-board-control.test.tsx __tests__/board-proxy-route.test.ts` in
`web/gateway` — 2 files, 15 tests, all passing, run by this reviewer.
