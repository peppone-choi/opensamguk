# 2026-06-19 SSE Nginx Buffering Review

Verdict: cleared

## Scope

- `web/game/app/api/game/[...path]/route.ts`
- `web/gateway/app/api/game/[...path]/route.ts`
- `docs/loops/page-parity/LEDGER.md`

## Baseline

- PR #115 opened the turn-SSE stream immediately in the frontend route handlers and promoted `s1` to `9e62437ba753f191a8a621a7e28ec7d578ebb806`.
- Production still returned no headers or body bytes for `/api/game/sse/turn` within 8 seconds.
- Local build artifacts contained the `proxy-connected` SSE frame, so the missing first byte was below the route-handler code path.

## Root Cause

The live docker nginx topology can route external `/api/game/sse/turn` through the generic `/api/` location into the gateway frontend route handler. That location does not disable proxy buffering. A tiny SSE comment frame can therefore be held in nginx even though the Next route handler has already enqueued it.

## Change

Both frontend SSE response helpers now include `X-Accel-Buffering: no` in addition to `Content-Type: text/event-stream`, `Cache-Control: no-cache, no-transform`, and `Connection: keep-alive`.

## Verification

- `pnpm --dir web/game typecheck` passed.
- `pnpm --dir web/gateway typecheck` passed.
- `pnpm --dir web/game build` passed with existing unrelated warnings.
- `pnpm --dir web/gateway build` passed with the existing unrelated admin hook warning.
- `tools/agent-system/check.py --strict --base origin/main --format json` passed.
- `git diff --check` passed.
- Independent reviewer (`019edf5e-c85f-70e2-89ac-677ce76dd4f5`) first flagged generated `next-env.d.ts` build output; after removing that generated diff, the final re-review returned `Verdict: cleared`.
- Pending merge, deploy, `s1` promotion, and live curl/Playwright remeasurement.

## Residual Risk

- If the live nginx config is still materially different from the repo/dockerside docs, final proof depends on production curl seeing an immediate first byte.
