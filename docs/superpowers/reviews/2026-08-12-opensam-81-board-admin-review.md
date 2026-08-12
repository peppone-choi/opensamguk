# OPENSAM-81 Admin Board Control Review

Scope: web/

## Reviewed change

The gateway admin page gains a `게시판 관리` tab under the existing
`AuthGate admin`. `BoardControl` owns proxy requests, response validation,
mutation state, stale-response suppression, and final-page recovery.
`BoardControlTable` is presentation-only. The change is deliberately limited to
the gateway admin control and dedicated tests; it does not change public board
pages, CSS, package metadata, backend policy, or shared agent state.

## Contract evidence

- List requests use `GET /api/proxy/board/posts` with exactly one of
  `NOTICE`, `FREE`, or `SUGGESTION`, `page`, and `size=20`.
- Pin requests use `PATCH /api/proxy/board/posts/{id}/pin` with
  `{ "pinned": boolean }`.
- Post deletion requires the existing confirmation modal and accepts success
  only for `204 No Content`.
- The browser stays on the same-origin proxy. It never reads the bearer token;
  the existing server proxy reads the httpOnly cookie and forwards credentials.
- Titles, authors, modal text, and response errors are React text. A hostile
  `contentHtml` fixture is explicitly proved absent from the rendered list.

## Independent critique

Three fresh post-extraction reviewers inspected the final UI boundary:

1. Code quality: **CLEAR / APPROVE**. It confirmed the split preserves list,
   pagination, mutation, modal, stale-response, and safe-render behavior.
2. Goal and constraint review: **UI PASS / APPROVE**. It reproduced the focused
   tests, TypeScript check, and diff hygiene; it also inspected the OP79 and
   OP80 parallel artifacts.
3. Security review: **in-scope security cleared**. It found no client token,
   authorization bypass, raw-HTML sink, or CSRF regression. Backend
   authorization remains the required source of truth.

Earlier adversarial review found a stale list-response race and invalid
final-page state after deletion. Both were repaired and locked by focused
regressions before the final critique. The initial oversized component was
split into a 194-line state/API module and a 179-line presentation module.

## Verification

- `pnpm --dir web/gateway exec vitest run __tests__/admin-board-control.test.tsx __tests__/admin-board-access.test.tsx --pool=forks --poolOptions.forks.singleFork=true --reporter=verbose --testTimeout=10000`
  — 2 files, 8 tests passed.
- `pnpm --dir web/gateway typecheck` — `tsc --noEmit` passed.
- `git diff --check` — clean.
- Playwright drove the rendered admin route with the locked proxy contract
  mocked at the browser boundary: ADMIN tab, NOTICE/FREE/SUGGESTION selection,
  pin state change, confirmation, and delete success were observed. This proves
  the frontend surface only; it is not a substitute for the combined API stack.

## Required stacked verification

OPENSAM-81 consumes two parallel changes that are intentionally outside this
worktree:

1. OPENSAM-79 provides gateway-api `/board/posts` list/pin/delete routes and
   enforces admin-only pinning plus owner-or-admin deletion.
2. OPENSAM-80 forwards empty upstream `204` responses through the generic
   gateway proxy without constructing a response body.

After those branches are integrated, run an authenticated combined-stack
scenario through `/api/proxy`: non-admin pin `403`, owner delete `204`,
non-owner delete `403`, and admin pin/delete success. Until that composition is
observed, the combined live route is deliberately quarantined rather than
claimed as complete.

Proof: OP79 and OP80 were independently inspected in their isolated worktrees;
their focused route/proxy tests passed, and this review records the exact
post-integration matrix still required.

Verdict: quarantined-with-proof
