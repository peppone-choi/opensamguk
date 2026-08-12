# Review: OPENSAM-80 gateway board frontend

Scope: `web/gateway` community-board frontend, its same-origin board proxy, the lobby entry point, focused tests, and the generic proxy empty-204 regression. This review excludes the OPENSAM-79 backend implementation, infrastructure, deployment, and shared Agent OS state.

Stage: fresh independent remediation re-review completed

Verdict: cleared

## Locked OPENSAM-79 contract

- Gateway API, not the retired game API, owns this surface: `/board/posts`, `/board/posts/{id}`, `/board/posts/{postId}/comments/{commentId}`, and `/board/posts/{id}/pin`.
- `GET /board/posts` and `GET /board/posts/{id}` remain public. The proxy forwards `sam_access` opportunistically when it is present so OPENSAM-79 can calculate permissions; an absent, stale, or invalid bearer must still receive the public anonymous representation rather than a frontend-generated 401.
- `PostDto` requires `canDelete` alongside `id`, `category`, `authorName`, `title`, `contentHtml`, `pinned`, `deleted`, `createdAt`, and `updatedAt`. `CommentDto` likewise requires `canDelete`. The client rejects malformed successful responses that omit either permission field and renders deletion controls only from that server decision.
- OPENSAM-79 varies public representations by authorization. The board proxy adds `Vary: Authorization, Cookie` and `Cache-Control: private, no-store` to avoid sharing a personalized `canDelete` value.
- Only `contentHtml` is an HTML sink. OPENSAM-79's `GatewayBoardContentSanitizer.plainTextToSafeHtml` escapes `<`, `>`, `&`, `\"`, and `'`, then converts normalized newlines to `<br>`; `GatewayBoardService` uses it before persistence. Title, author, and comment values render as React text. The browser-shaped contract test uses escaped image-looking text and asserts no executable element is produced.

## CodeRabbit disposition

CodeRabbit left 12 commented findings; it did not grant approval.

| Comment | Disposition | Resolution / evidence |
| --- | --- | --- |
| Review verdict and missing evidence reference | Fixed | This document now scopes the independent verdict separately from landing, removes the absent `.omo/evidence/OPENSAM-80-code-review.md` reference, and records unverified boundaries. |
| Delete failure leaves the page unusable | Fixed | `actionError` is separate from initial-load failure; a 403 delete test proves the loaded title remains visible with an inline alert. |
| Leaked `GATEWAY_API_ORIGIN` test mutation | Fixed | The unused process-environment assignment was removed; test cleanup restores mocks and globals. |
| Missing invalid-path and upstream-failure tests | Fixed | Board proxy tests cover non-numeric/incomplete/unsupported paths without `fetch`, plus connection failure. |
| Route should target old game API instead of `/board/posts` | Invalid against the locked contract | OPENSAM-79's gateway controller owns `/board/**`; this proxy deliberately forwards there. No redirect to the old game API is correct. |
| No upstream timeout | Fixed | Both board and generic proxies use the shared 10-second `AbortSignal.timeout` and map a `TimeoutError` to 504; focused tests cover it. |
| Mutation failure replaces loaded detail | Fixed | Post delete, comment delete, and pin failures retain detail and use the inline action alert path. |
| Login implies delete permission | Fixed with OPENSAM-79 | Required `canDelete` is parsed for posts and comments; controls no longer infer authority from `user !== null`. Per-resource positive and negative UI tests cover it. |
| Client/server HTML sanitization | Invalid in this frontend scope after contract verification | The backend owns a deliberately text-only sanitizer; the client receives its safe `contentHtml` contract rather than accepting arbitrary HTML. The escaped-HTML component regression verifies the integration boundary. |
| Lobby TSX uses four-space indentation | Fixed | The owned `LobbyView` JSX hierarchy, including the account-action links, now follows two-space nesting without a whole-file quote/wrapping rewrite. |
| Incomplete tab ARIA pattern | Fixed | Category controls are a labeled button group with `aria-pressed`, not tabs, because they reload a filtered list and do not control a tab panel. |
| Duplicate post response validation | Fixed | `parsePost` centralizes the shared runtime guard for post creation and pin responses. |

## Observed remediation evidence

- TDD red runs captured the missing `canDelete`, optional-read bearer, timeout, action-error, and button-group behavior before implementation.
- The final focused Vitest run covered board list/detail/write/mutations, DTO parsing, board proxy, generic proxy, and the lobby portrait surface: 8 files, 31 tests, 0 failures. The independent re-review separately reran the seven board/proxy suites: 24 tests, 0 failures.
- `pnpm typecheck` completed with `tsc --noEmit` after the remediation changes.
- `git diff --check` completed without output.
- Independent re-review verdict: cleared for the remediation scope, with `WATCH` only for the pre-existing unrelated full-suite contention. This is not landing approval.

## Unverified or contended checks

- A complete gateway Vitest run previously retained unrelated asynchronous loading-state failures in `admin-server-id.test.tsx` and `lobby-possession.test.tsx`; focused runs pass. This review does not call the complete suite green until a fresh run proves it.
- Local `pnpm build` previously reached Next static analysis but did not terminate within the bounded host run. No product build error was emitted, but local build success remains unverified; PR CI is the deciding build gate.
- Playwright against a local contract-shaped mock remains `채점대기` because the Next development server did not serve bytes while host-bound compilation was active. Browser-shaped React Testing Library coverage is the available runtime evidence.
- PHP golden draw-for-draw replay is not applicable to this gateway UI/proxy work: it contains no RNG, rounding, Korean game-log, or PHP-command-parity claim.
- Generic Fablize `tool failure` notifications have recurred around otherwise successful command/output-session recovery. This is the pre-existing wrapper baseline recorded in `.ai/known-issues.md`; direct exit codes and test artifacts are the evidence used above.

## Conditional landing boundary

PR #379 remains open. It must not merge or deploy until the OPENSAM-79 backend dependency lands, a fresh independent re-review clears this remediation, and required PR CI checks are green.
