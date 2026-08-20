# OPENSAM-212 design-quality audit

Date: 2026-08-20
Scope: local gateway/game UI, desktop `1440x1000`, responsive `390x844`
Method: real Playwright browser flows against the local Docker stack. Screenshots and JSON/action logs are under the shared team artifact directory.

## Verdict

The reachable surfaces are usable enough to expose several independent responsive and state-hierarchy defects. The highest-risk issues are the live `/lobby` route rendering account management instead of the lobby, and the authenticated game main route remaining on a spinner after a real general was created. The clearest visual defects are account-form collisions, fixed mobile navigation covering page content, and action links wrapping into unreadable controls. Admin and direct PHP/Vue differential review are blocked and are not inferred.

This audit deliberately excludes OPENSAM-210’s broad unification findings (brand, color/token, common button/card treatment, logo, and general shell consistency). The candidates below are screen-specific behavior, responsive geometry, or state-recovery issues.

## Oracle and reachability limits

The checkout contains no `hwe/ts/` or `legacy/` source tree. Existing project docs identify those as historical references, but the files cannot be inspected here. Therefore direct PHP/Vue comparison is `BLOCKED`, not a parity pass or inferred mismatch. The local real flows also had no admin credential; a USER account was redirected from `/admin` to `/lobby`. No admin screenshot is fabricated.

## Findings and separate ticket candidates

### P0 — Game main can remain indefinitely in loading state after general creation

Observed: a real account registered a general, then `/game/pep` remained a centered spinner with `서버 갱신 중입니다.` at both breakpoints after six seconds. The page never reached the game chrome. This is a main-screen reachability failure, not a cosmetic judgment.

Evidence: `authenticated-general/game-main-general-1440x1000.png`, `authenticated-general/game-main-general-390x844.png`, `authenticated-general/general-capture.json`.

Source: `web/game/components/game/GameChrome.tsx:75-93` gates all chrome on `loading`; `web/game/hooks/useFrontInfo.ts:58-77` clears loading only when the request settles. The ticket should investigate the live request/timeout path and add a bounded, actionable failure state so a stalled request cannot present an endless blank screen.

Acceptance: with a valid general, the route renders game chrome or a visible error/retry within a bounded interval at desktop and 390px; no indefinite spinner remains when the request is stalled or rejected.

### P1 — Account settings form controls collide on mobile

Observed: at 390px, the password labels and inputs share a row; the second label wraps into the first control’s horizontal space. The shared icon filename/server controls similarly crowd the narrow viewport. The desktop form is also visually compressed because labels are inline content rather than a responsive field stack.

Evidence: `authenticated/gateway-account-auth-390x844.png`, `authenticated/gateway-account-auth-1440x1000.png`, `authenticated/authenticated-capture.json`.

Source: `web/gateway/app/account/page.tsx:114-139` emits bare `label`/input pairs; the only mobile rules near this surface are `web/gateway/app/globals.css:1541-1555`, which do not define an account-form layout.

Acceptance: each account field has a block label and full-width control at 390px; no label/control overlap or clipped file/select control; desktop keeps a readable field grouping.

### P1 — Fixed mobile bottom navigation covers game content

Observed: rankings, profile, and map screenshots show the fixed five-item nav drawn over the last visible card/table/log rows at 390px. The user cannot read or interact with covered content without guessing to scroll farther.

Evidence: `authenticated-general/game-rankings-general-390x844.png`, `authenticated-general/game-profile-general-390x844.png`, `authenticated-general/game-map-general-390x844.png`, `game/game-root-390x844.png`.

Source: `web/game/components/BottomNav.tsx:13-30`; `web/game/app/globals.css:195-206` uses fixed positioning and z-index 100, while the responsive shell padding is in `web/game/app/globals.css:413-433`. The ticket should apply the bottom-nav/safe-area inset to every scrollable game surface, not just the shell container.

Acceptance: at 390px, the final card/table/log row remains fully visible and tappable above the nav; safe-area devices retain the same guarantee; desktop nav behavior is unchanged.

### P1 — Shared board heading actions wrap on mobile (one responsive ticket)

Observed: two instances of the same `.board-heading` responsive geometry fail at 390px: the authenticated write screen renders the top-right `취소` action as vertically split `취`/`소`, while the board list renders `로그인 후 글쓰기` as a cramped two-line CTA. These are one shared responsive-heading ticket/dependency with two screen-level checks.

Evidence: `authenticated/gateway-board-write-auth-390x844.png`, `authenticated/gateway-board-write-auth-1440x1000.png`, `gateway/gateway-board-390x844.png`, `gateway/gateway-board-1440x1000.png`.

Source: both screens use the shared `.board-heading` geometry in `web/gateway/app/globals.css:665-680`; write markup is `web/gateway/app/board/write/page.tsx:32-42`, and list markup is `web/gateway/app/board/page.tsx:46-60`.

Acceptance: (a) `/board/write` at 390px keeps `취소` on one readable line or moves it to a deliberate full-width row and never splits Korean characters; (b) unauthenticated `/board` at 390px keeps `로그인 후 글쓰기` as one readable control or intentionally moves it below the heading. Desktop heading alignment remains intact for both.

### P0 — Live `/lobby` renders account management instead of the lobby

Observed: after real signup, a browser request to `http://localhost:3000/lobby` returned a page headed `계 정 관 리` with account-management links. No lobby server table, server tabs, or lobby heading was present. This is a route/surface identity failure in the live local stack, not an inferred missing empty state.

Evidence: `authenticated/gateway-lobby-auth-1440x1000.png`, `authenticated/gateway-lobby-auth-390x844.png`, `authenticated/authenticated-capture.json` (both records report heading `계 정 관 리`).

Source: the current checkout’s lobby component renders `Topbar`, `ServerBoard`, server selection, and account section at `web/gateway/app/lobby/page.tsx:240-289`; account management is a separate surface at `web/gateway/app/account/page.tsx:109-145`. The live output therefore needs a deployment/runtime route check before any visual redesign. The intended lobby’s empty-registry behavior remains unverified.

Acceptance: authenticated `/lobby` renders the lobby component at both breakpoints, with either the populated server board/table or an explicit server-empty/error state; account management remains reachable via its own link and `/account` does not replace `/lobby`.

### P2 — Profile data loading/recovery remains blocked on desktop

Observed: the authenticated general capture’s desktop `/game/pep/my` body remains `로딩 중...`, so desktop profile coverage is `FAIL/blocked`, not a successful visual review. A separate account-state capture produced the explicit desktop error `내 정보를 불러올 수 없습니다.` with retry, while the general mobile capture reached the profile data table; that mobile success remains useful evidence for the fixed-nav overlap finding only.

Evidence: `authenticated-general/game-profile-general-1440x1000.png`, `authenticated-general/game-profile-general-390x844.png`, `authenticated-general/general-capture.json`; supplemental error-state evidence is `authenticated/game-profile-auth-1440x1000.png` and `authenticated/authenticated-capture.json`.

Source: `web/game/app/game/my/page.tsx:83-105` renders the loading/error branches. This should be triaged with the game-main request behavior; if API latency is the root cause, retain a bounded retry/error contract rather than redesigning the successful profile table.

Acceptance: at 1440px and 390px, loading transitions to profile data or a bounded, contextual retry state; desktop must not remain on `로딩 중...` for the capture window. Preserve the mobile profile table once loaded and apply the separate fixed-nav inset ticket.

## Surfaces captured

- Entrance/auth: gateway `/login`, `/join`; protected-route redirects for `/lobby`, `/admin`, `/account`.
- Lobby: authenticated `/lobby` request captured, but live output rendered account management (`계 정 관 리`) instead of the lobby; server-empty behavior is therefore unverified.
- Board: empty default `공지` list, invalid detail, authenticated write form, and a real populated detail at `/board/posts/2` on both breakpoints. The created post is `자유`, so the default `공지` tab correctly remains empty in the list capture.
- Admin: not reached; exact blocker is USER role redirect to `/lobby`.
- Game main: `/game/pep` captured as the actual stuck loading state after general creation.
- Ranking: `/game/pep/rankings` loaded real ranking cards at both breakpoints.
- Profile: `/game/pep/my` desktop general capture remained `로딩 중...` and is `FAIL/blocked`; mobile general capture reached profile data, while a separate account state showed the explicit error branch.
- Map: `/game/pep/map` loaded the real map and `중원 정세` log in the general-auth capture at both breakpoints.

## Manual-QA matrix

The manual-QA matrix is an artifact-only deliverable at `.omo/teams/team-67fb6e8b/artifacts/design-audit-212/OPENSAM-212-manual-qa.md`; this durable report intentionally does not link to an ephemeral absolute filesystem path.

## Artifact index

All paths below are non-empty files under `.omo/teams/team-67fb6e8b/artifacts/design-audit-212/`:

- `gateway/gateway-capture.json` plus login, signup, board, invalid-detail, board-write, lobby/admin/account redirect screenshots.
- `authenticated/authenticated-capture.json` plus authenticated gateway and game screenshots.
- `authenticated-general/general-capture.json` plus real game ranking/profile/map and game-main loading screenshots.
- `board-detail/board-detail-capture.json` plus real populated board-detail screenshots at `1440x1000` and `390x844`.
- `qa-harness/` contains the archived Playwright harnesses used for the captures; it is artifact-only and is not a product deliverable.
- `status-update.md` records the current reachability/blocker status.
