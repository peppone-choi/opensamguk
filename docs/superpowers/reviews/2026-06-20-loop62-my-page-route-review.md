# Loop 62 — 내정보 페이지 라우트 복원

## Legacy evidence

- `legacy/devsam-core/hwe/ts/components/MainControlBar.vue:37-38` links `내 정보&설정` to `b_myPage.php`.
- `legacy/devsam-core/hwe/ts/components/MainControlDropdown.vue:35-36` uses the same `b_myPage.php` target.
- `legacy/devsam-core/hwe/b_myPage.php:25` increments the refresh bucket as `내정보`.
- `legacy/devsam-core/hwe/b_myPage.php:76,97-105` renders the `내정보` page title and the `generalInfo()`/`generalInfo2()` panels.
- `legacy/devsam-core/hwe/b_myPage.php:215-260` renders the four log panels: 개인 기록, 전투 기록, 장수 열전, 전투 결과.

## Baseline

- Live route probe before the change: `/game/s1/my` returned 404 while `/game/s1`, `/game/s1/city?id=1`, `/game/s1/chief-center`, `/game/s1/troop`, `/game/s1/auction`, `/game/s1/inherit`, and `/game/s1/nation-finance` returned 200.
- Next route inventory had `my-boss`, `my-cities`, `my-generals`, and `my-nation`, but no `web/game/app/game/my/page.tsx`.
- `web/game/lib/api.ts` already exposed `api.myPage('/api/my-page')`, and `web/game/components/game/MyInfoLogPanel.tsx` already encoded the `b_myPage.php` four-log-panel structure.

## Root Cause

The legacy page was split into API and partial UI pieces, but the App Router destination was never created. The main control entry also pointed back to `/game`, so the page spine could not reach the `b_myPage.php` equivalent route.

## Hypothesis

Adding `/game/my` as a read-only `b_myPage.php` equivalent, mapping legacy `b_myPage.php` to it, and pointing the control button there closes the 404 gap without inventing write behavior for the separate settings-save wave.

## Verification

- `pnpm --dir web/game typecheck`
- `pnpm --dir web/game test` — 16 files, 82 tests passed.
- `pnpm --dir web/game build` — passed; `/game/my` appears as a dynamic app route. Existing warnings only.
- `pnpm --dir web/gateway typecheck`

## Live Check

- PR #124 was merged to main, then PR #125 deployed via Actions run `27855366232`.
- Promoted `s1` to `c925a8a712f7e4775453b7b2220c41f808968417` through the admin deployer path.
- Live browser QA with QA general `코덱스3bpr` confirmed `/game/s1/my` renders h1 `내 정보&설정`, current general info, and the four log panels: `개인 기록`, `전투 기록`, `장수 열전`, `전투 결과`.
- The main control surface exposes `내 정보&설정` as `/game/s1/my`.
- Follow-up response-status capture across `/game/s1` and `/game/s1/my` found no 4xx/5xx responses.

Verdict: cleared
