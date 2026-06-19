# 2026-06-19 SSE Open And Dedupe Review

Verdict: cleared

## Scope

- `web/gateway/app/api/game/[...path]/route.ts`
- `web/game/app/api/game/[...path]/route.ts`
- `web/game/hooks/useSSE.ts`
- `web/game/__tests__/useSSE.test.tsx`
- `docs/loops/page-parity/LEDGER.md`

## Baseline

- After PR #114, the previous Cloudflare 504 responses disappeared on `https://sam.peppone.dev/game/s1`.
- The main map still rendered in the expected detail mode:
  - map canvas: `698x499`
  - city count: `94`
  - my-city marker count: `1`
  - live map request: `/api/game/api/map?neutralView=0&showMe=1`
- The next live measurement found a navigation hang:
  - city button click reached the DOM handler
  - RSC request `/game/s1/city?id=1&_rsc=...` returned `200`
  - city page chunk request was issued but did not complete in the browser run
  - direct SSE request to `/api/game/sse/turn` produced no bytes before timeout

## Root Cause

The live docker nginx route sends `/api/game/*` to `game-frontend`, whose Next route handler only returned the `text/event-stream` response after upstream `fetch()` resolved. The gateway frontend had the same proxy shape for other deployment paths. If the game API SSE endpoint kept the upstream request pending before headers or first bytes, the browser connection saw no response body and held a per-origin connection slot. On the client side, `useSSE` only treated `EventSource.OPEN` as an existing connection, so a connecting stream was not protected against re-entry.

## Change

- Both frontend proxies now open a `ReadableStream` immediately for turn SSE and send an SSE comment frame before awaiting upstream.
- They keep the stream alive with a periodic comment heartbeat and pipe upstream chunks when they arrive.
- Non-OK upstream responses are translated into an SSE error event instead of buffering a non-SSE body.
- Client `useSSE` now treats `CONNECTING` as an active EventSource and stores the latest callback in a ref so rerenders do not reconnect the stream.
- `useSSE.test.tsx` locks the connecting-state dedupe and latest-callback behavior.

## Verification

- `pnpm --dir web/game test -- useSSE.test.tsx` passed as part of the full suite: 14 files, 73 tests.
- `pnpm --dir web/game typecheck` passed.
- `pnpm --dir web/gateway typecheck` passed.
- `pnpm --dir web/game build` passed with existing unrelated warnings.
- `pnpm --dir web/gateway build` passed with the existing unrelated admin hook warning.
- First independent review returned `fix-required` because only the gateway proxy had the immediate-open wrapper while the live nginx route uses `game-frontend`; the game proxy was then patched with the same behavior.
- Final independent review (`019edf3c-e9a4-7423-a361-6b95cc9d8584`) returned `Verdict: cleared`.
- Pending merge, deploy, `s1` promotion, and live Playwright remeasurement.

## Residual Risk

- Final proof requires production measurement that `/api/game/sse/turn` receives an immediate event-stream response and that a main-map city click reaches `/game/s1/city?id=1` without static chunk starvation.
