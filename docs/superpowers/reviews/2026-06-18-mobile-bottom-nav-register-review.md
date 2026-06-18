# Mobile Bottom Nav Register Review

Date: 2026-06-18
Verdict: cleared

## Scope

- Fix mobile `/game/join` so the 장수 생성 submit row is never hidden behind the fixed bottom navigation.
- Remove the standalone global 명령 button from header and mobile bottom navigation.
- Keep page-specific command reservation surfaces intact.

## Legacy Evidence

- `legacy/devsam-core/hwe/select_general_from_pool.php:110-183` renders 장수 생성 as a normal form section; the submit control is inside the form row at `:181`, followed by reset at `:182`.
- `legacy/devsam-core/hwe/ts/PartialReservedCommand.vue:101-126` scopes 명령 선택 to the reserved-command surface, not to a separate always-present chrome button.

## Root Cause

- `web/game/app/globals.css` had mobile `.shell-main { padding-bottom: 72px; }`, but later `@media (max-width: 767px)` reset `.shell-main` with shorthand `padding: var(--space-sm)`, removing the bottom clearance.
- `web/game/components/BottomNav.tsx` added an extra 명령 button after the five navigation links, creating a sixth mobile bottom item.
- `web/game/components/Header.tsx` added a second global 명령 button in the sticky header.

## Fix

- Introduced `--bottom-nav-height` and safe-area-aware bottom navigation sizing.
- Restored bottom content clearance after every mobile padding shorthand.
- Removed the global command modal launcher from `Shell`, `Header`, and `BottomNav`.
- Left page-local `CommandModal` flows untouched.

## Verification

- `pnpm build` in `web/game`: passed.
- `pnpm typecheck` in `web/game`: passed after `next build` regenerated `.next/types`.
- `git diff --check`: passed.
- Mobile Playwright QA at 390x844 on local `/game/join?server=s1` with API/auth mocks:
  - submit button bottom: `755.765625`
  - bottom nav top: `780`
  - overlap: `false`
  - bottom nav labels: `내 정보`, `장수`, `도시`, `외교`, `경매`
  - global 명령 buttons: `0`
  - screenshot: `/tmp/opensamguk-mobile-join-bottom-nav-local.png`

## Known Existing Warnings

- `web/game/app/game/generals/page.tsx`: `aria-sort` warning on button role.
- `web/game/app/game/tournament/page.tsx`: pre-existing `useMemo` dependency warnings.
