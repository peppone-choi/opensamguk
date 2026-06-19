# Server Scoped Internal Links Review

Verdict: cleared

## Scope

- Change: preserve path server ids for in-game internal city and rankings navigation.
- Files: `web/game/components/game/MapViewer.tsx`, `web/game/app/game/city/page.tsx`, `web/game/app/game/rankings/page.tsx`, `web/game/app/game/rankings/emperor/page.tsx`, `web/game/app/game/rankings/emperor/[id]/page.tsx`, `web/game/__tests__/MapViewer.interaction.test.tsx`, `web/game/__tests__/rankings-lobby-route.test.tsx`.
- Reason: some internal client navigation still generated `/game/...` after entering through `/game/s1/...`, so the visible URL and middleware-selected server could drift from the user's active server.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/PageFront.vue:37-49` passes `genHref` and `city-click` into the main map viewer.
- `legacy/devsam-core/hwe/ts/PageFront.vue:501-502` maps city ids to `b_currentCity.php?citylist=<id>`, a same-server relative city-detail route.
- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:226-228` accepts a route generator, and `legacy/devsam-core/hwe/ts/components/MapViewer.vue:439-455` emits city click after the touch gate.
- `legacy/devsam-core/hwe/ts/legacy/main.ts:44-46` uses the same `b_currentCity.php?citylist={0}` template for cached world-map city navigation.

## Production Baseline

- Browser repro on `https://sam.peppone.dev/game/s1`: triggering a city marker with DOM click navigated to `https://sam.peppone.dev/game/city?id=1`.
- Baseline regression test failed as expected: `MapViewer.interaction` expected `/game/s1/city?id=11` but received `/game/city?id=11`.
- Static scan found hardcoded in-game navigation in `MapViewer.tsx`, `city/page.tsx`, `rankings/page.tsx`, and `rankings/emperor/page.tsx`.

## Root Cause

- Server selection is encoded in the public path for path server ids such as `/game/s1`.
- The affected components bypassed `serverGameUrl` helpers and built absolute canonical app paths directly, dropping `s1`.
- Rankings lobby/detail used client `Link` for server-scoped in-game hrefs, which is the same navigation primitive avoided in the previous server path fix.
- Independent reviewer flagged that rankings lobby cards render before `useServerId()` can read the cookie, so those initial hrefs must be relative instead of effect-derived absolute paths.

## Verification

- `pnpm --dir web/game test -- MapViewer.interaction` red before implementation, then 69/69 green after the first implementation.
- `pnpm --dir web/game test -- MapViewer.interaction rankings-lobby-route` 70/70 green after the rankings initial-href reviewer fix.
- `pnpm --dir web/game typecheck`
- `rg -n 'router\.(push|replace)\(`/?game|href=\{`/?game|href="/?game|window\.location.*?/game|\.assign\(`/?game' web/game/app/game web/game/components web/game/lib -S`
- `git diff --check`
- `pnpm --dir web/game build`

## Risk

- Scope-risk: narrow. The change only replaces hardcoded in-game internal href generation with the existing server URL helper and normal anchors for rankings links.
- Remaining risk: production image must be promoted and rechecked on `/game/s1` before closing the loop.
