# Server Path Navigation Review

Verdict: cleared

## Scope

- Change: use document navigation for server-scoped game links in the main control bar, mobile control dropdown, bottom nav, and character-claim entry links.
- Files: `web/game/components/game/MainControlBar.tsx`, `web/game/components/game/MainControlDropdown.tsx`, `web/game/components/BottomNav.tsx`, `web/game/components/game/CharacterClaim.tsx`.
- Reason: `/game/s1/...` paths are intentionally handled by `web/game/middleware.ts`, which rewrites them to canonical app routes while setting `sam_server`. Client-side `next/link` navigation can fetch the RSC payload for `/game/s1/inherit` without committing the visible route transition.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/components/MainControlBar.vue:1-64` renders the 20-button game menu as direct `<a href="...">` links, with new-window targets only for the legacy windows.
- `legacy/devsam-core/hwe/ts/components/GlobalMenu.vue:142-158` prevents default navigation only for `funcCall`, missing URL, or `newTab`; ordinary menu items use the browser's default handler.

## Production Baseline

- After PR #110 was promoted to s1, rendered hrefs were correct (`/game/s1/inherit`, `/game/s1/generals`, `/game/s1/simulator`) and direct HTTP GETs returned 200.
- Playwright mobile click on `a[href="/game/s1/inherit"]` sent `GET /game/s1/inherit?_rsc=...` and loaded the page chunk, but the browser URL stayed on `/game/s1`.

## Review

- Root cause: `next/link` was the wrong navigation primitive for URLs whose route identity is rewritten by middleware from a public server-scoped path to an internal canonical app path.
- Fix shape: keep server-scoped `href` values but render normal anchors for these game navigation surfaces, letting the browser perform a document navigation and letting middleware set `sam_server` on the request.
- Scope control: disabled menu items remain non-navigating `<span>` elements, and `_blank` links now include `rel="noopener noreferrer"`.

## Verification

- `pnpm typecheck`
- `pnpm test -- serverGameUrl`
- `pnpm build`

## Risk

- Scope-risk: narrow. The change only swaps the navigation primitive for already-rendered href values.
- Remaining risk: production click verification must rerun after the new image is built and promoted.
