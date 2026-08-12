# Review: OPENSAM-80 gateway board frontend

Scope: `web/gateway` community-board frontend, its same-origin board proxy, the lobby entry point, focused tests, the generic proxy empty-204 regression, and an authorized test-only global-mock cleanup. This review excludes the OPENSAM-79 backend implementation, infrastructure, deployment, and shared Agent OS state.

Stage: independent re-review completed

Verdict: cleared

## Contract checked

- Anonymous visitors can list and read board posts through `/api/board/posts` without a cookie read or Bearer header.
- Authenticated board writes bridge only the httpOnly access cookie: post/comment create, post delete, nested comment delete, and pin updates.
- The paired OPENSAM-79 controller exposes comment deletion only at `DELETE /board/posts/{postId}/comments/{commentId}`. The client helper, proxy allowlist, detail page, and regression tests all use that nested path.
- Title, author, and comment strings render as React text. `contentHtml` is the only HTML sink and is limited to the server-owned escaped-and-line-broken field in the agreed contract.
- UI input limits match the paired backend constraints: title 120, post content 10,000, comment 2,000 characters.
- The generic authenticated proxy now represents an empty upstream `204` as a null response body, avoiding the Next `Response constructor: Invalid response status code 204` exception.

## Independent critique

An independent read-only reviewer first found a HIGH nested-comment-delete mismatch: the client and proxy used a nonexistent top-level comment route. It was corrected, then re-reviewed against the paired controller. The reviewer also requested the client length alignment, which was corrected and re-reviewed.

Final independent result: cleared, with no blockers. The detailed reviewer evidence is retained at `.omo/evidence/OPENSAM-80-code-review.md` in the working environment.

Nonblocking follow-ups noted by the reviewer:

- `board-components.test.tsx` is fixture-heavy and can be split by page surface in a later cleanup.
- `lib/board.ts` uses handwritten response guards; a later shared validation-schema decision can simplify that boundary without changing this contract.

## Observed verification

- `pnpm typecheck` completed with `tsc --noEmit` and no errors after the final changes.
- Focused Vitest JSON output recorded 6 suites, 14 tests, 0 failures, and `success: true` for board components, board proxy, and generic 204 proxy regression coverage.
- `git diff --check` completed without output.
- The direct lobby test passed after the authorized `afterEach` mock cleanup.

## Unverified or contended checks

- The complete gateway Vitest suite was attempted serially and under parallel workers. It retained unrelated async loading-state failures in `admin-server-id.test.tsx` and `lobby-possession.test.tsx`; their focused runs pass. This review does not call the full suite green.
- `pnpm build` compiled and minified the board route chunks but did not complete the host's existing Next static-analysis phase before the bounded self-started process was stopped. No product build error was emitted, but local build success is unverified; PR CI is the deciding build gate.
- A Playwright run against a local contract-shaped mock could not receive bytes from a Next dev server while the server remained in host-bound compilation. Browser validation is `채점대기`; the focused component tests are the available browser-shaped evidence.
- Generic Fablize `tool failure` notices recurred around successful commands and output-session recovery. This is the pre-existing wrapper baseline recorded in `.ai/known-issues.md`; command output and JSON test artifacts, rather than that telemetry, are used above.

## Conditional landing boundary

This frontend PR depends on the OPENSAM-79 gateway-board backend landing first. Do not merge until that dependency is present and required PR CI checks are green. No deployment or production action is authorized by this review.
