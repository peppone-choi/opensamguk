# Web front-info identity critique — 2026-06-09

Verdict: cleared

## Scope

- `web/game/app/game/mailbox/page.tsx`
- `web/game/app/game/tournament-admin/page.tsx`
- `web/game/lib/mailbox.ts`
- `docs/superpowers/gap/HARDCODE_INVENTORY.md`

## Claim Under Review

Remove remaining web/game `id=1` identity hardcoding for mailbox and tournament-admin without changing backend contracts or fabricating identity.

## Adversarial Checks

1. **Does the mailbox still route through a fabricated default?**
   - No. Private mailbox uses `frontInfo.general.generalId`; national mailbox uses `9000 + frontInfo.general.nationId`; public mailbox uses `9999`.
   - Missing general/nation identity returns `null` and disables action instead of falling back to `1`.

2. **Do accept/decline mutations still act as general `1`?**
   - No. They now pass the resolved caller `generalId`; when unresolved they show an error and do not submit.

3. **Did the tournament gate introduce a new wrong constant?**
   - Initial review caught a bad `permission >= 5` gate. `front-info.general.permission` is derived from legacy `getNationPermission` as `0/1/2`, so the page now uses the existing 수뇌부 gate `permission >= 2`.

4. **Did this PR overclaim nation-page work?**
   - The current nation page no longer contains `nationId/generalId=1` inputs, so the inventory was corrected as a current-state audit item. The separate `INHERIT_COSTS` API-source gap remains open as `D3-04(web)`.

## Residual Risk

- `tournament_start/advance/reset` remain backend-unimplemented and still show the existing "not implemented" toast. This slice only removes the identity hardcoding around that UI.
- The mailbox page still uses the older direct list endpoint for the selected mailbox. The richer legacy `recent/old` envelope remains a later parity/read-shape task.

## Verification

- `pnpm test -- mailbox.test.ts` passed; current vitest config ran all 5 test files, 39 tests.
- `pnpm typecheck` and production build must pass before merge.
