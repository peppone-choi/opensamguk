# 2026-06-19 Gateway SSE Streaming Review

Verdict: cleared

## Scope

- `web/gateway/app/api/game/[...path]/route.ts`
- `docs/loops/page-parity/LEDGER.md`

## Baseline

- Prod Playwright on `https://sam.peppone.dev/game/s1` showed the main map itself working after PR #113 promotion:
  - map canvas: `698x499`
  - live map request: `/api/game/api/map?neutralView=0&showMe=1`
  - city count: `94`
  - my-city marker count: `1`
  - first city click: `/game/s1/city?id=1`
- The same run showed repeated browser errors:
  - `504 https://sam.peppone.dev/api/game/sse/turn` x3
  - `GET https://sam.peppone.dev/api/game/sse/turn :: net::ERR_ABORTED`

## Root Cause

Prod nginx routes `/api/game/*` through `web-gateway`, even when the browser is on the game surface. The `web-game` proxy already streams `text/event-stream`, but the matching `web-gateway` proxy buffered every upstream response with `upstream.text()`. The SSE response never completes, so the proxy chain eventually times out and the browser sees Cloudflare 504s.

## Change

`web-gateway` now mirrors `web-game` for SSE responses:

- read upstream `content-type`
- if it includes `text/event-stream`, return `NextResponse(upstream.body)` with stream-friendly headers
- keep the existing buffered path for all non-SSE responses

## Verification

- `pnpm --dir web/gateway typecheck`
- `pnpm --dir web/gateway build`
- `git diff --check`
- independent reviewer `019edf16-6254-7721-8a5e-46c5bbc01318`: `Verdict: cleared`

## Residual Risk

- Final proof requires merge, deploy, `s1` promotion, and live Playwright remeasurement that `/api/game/sse/turn` no longer reports 504.
