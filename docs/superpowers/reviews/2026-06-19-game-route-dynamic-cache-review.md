# Game Route Dynamic Cache Review — 2026-06-19

## Scope

- Task: keep the main game page and game subpages from serving stale static HTML after a server image promotion.
- Changed files:
  - `web/game/app/game/layout.tsx`
  - `docs/loops/page-parity/LEDGER.md`

## Baseline Evidence

- `s1` deploy status reported `currentTag=f1d8cc4e4b44641243a7ebe4cfb5c5bb3f66584c`.
- `/health` returned OK and `/api/game/sse/turn` returned the immediate SSE prelude.
- Live `/game/s1` still rendered `button.city-base=94` and `a.city-base=0`, so the PR #117 `MapViewer` anchor change was not in the browser DOM.
- Fetching live `/game/s1?fresh=f1d8cc4e4b44641243a7ebe4cfb5c5bb3f66584c` showed:
  - `cache-control=s-maxage=31536000`
  - page chunk reference `/_next/static/chunks/app/game/page-4b0b67ec68b163f1.js`
  - no `cityHref` / anchor-city logic in the HTML payload

## Root Cause

`web/game/app/game/page.tsx` is a Client Component under `app/game`. Without dynamic segment config on the server segment, Next can prerender the route shell and emit a long-lived HTML response for `/game`. Because production path-server URLs such as `/game/s1` rewrite into that same route, a promoted game server can still receive an old HTML payload that points at the prior client chunk.

## Change

`web/game/app/game/layout.tsx` now exports:

```ts
export const dynamic = 'force-dynamic';
export const revalidate = 0;
```

This applies to the authenticated game route segment instead of only the client page, so `/game/**` no longer depends on build-time HTML for route shell delivery.

## Why This Scope

- The issue is not isolated to the map component. Any authenticated game page under `/game/**` can be stale if the route shell is served from static HTML.
- The layout is the server component that owns the `/game` segment and already wraps all game pages in `AuthGate`.
- No game API contract or legacy gameplay behavior changes.

## Local Verification

- `pnpm --dir web/game test -- MapViewer.interaction.test.tsx MapViewer.props.test.tsx`: 14 files / 73 tests passed.
- `pnpm --dir web/game typecheck`: passed.
- `pnpm --dir web/game build`: passed. Build route table shows `/game` and all `/game/**` routes as `ƒ (Dynamic) server-rendered on demand`; only `/` and `/_not-found` remain static.
- `tools/agent-system/check.py --strict --base origin/main --format json`: passed.

## Production Acceptance

After merge, deploy, and `s1` promotion:

- `/game/s1` HTML must not return the previous long-lived `s-maxage=31536000` static shell.
- `/game/s1` DOM must render city markers as anchors (`a.city-base=94`).
- Clicking city 1 on the main map must commit to `/game/s1/city?id=1`.
- Existing map baseline must hold: approximately 700 x 500 map, 94 cities, `neutralView=0`, `showMe=1`, SSE first byte succeeds.

## Fresh Review

Verdict: cleared

Fresh correctness reviewer Lagrange the 2nd returned no findings. Evidence reviewed:

- `web/game/app/game/layout.tsx:3` exports `dynamic = 'force-dynamic'`.
- `web/game/app/game/layout.tsx:4` exports `revalidate = 0`.
- `web/game/middleware.ts:39` rewrites path-server URLs back into the `/game` App Router segment.
- `infra/nginx/default.conf:52` production nginx also rewrites `/game/s*` requests into `/game` with server selection.
- `docs/loops/page-parity/LEDGER.md:93` records the stale static HTML symptom, route-segment fix, and post-deploy acceptance checks.

Residual risk: production proof remains pending until merge, deploy, and `s1` promotion.
