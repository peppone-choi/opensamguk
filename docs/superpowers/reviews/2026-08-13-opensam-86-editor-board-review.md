# Review: OPENSAM-86 board rich-text editor

Scope: `web/game/` meeting/secret-room board article input and persisted article/comment rendering, direct focused regressions, and task-local loop/browser evidence. Shared `RichTextEditor`/`SafeHtml`, intake/daemon contracts, deployment, and shared `.ai` state are excluded.

Stage: corrective implementation and independent re-review complete

Verdict: cleared

## Contract checked

- HWE `PageBoard.vue:5-31, 129-169` establishes the board form shape, title `maxlength=250`, room-derived `isSecret`, empty-title-and-body guard, reset, and reload-after-success lifecycle. `BoardArticle.vue:18-40` and `BoardComment.vue:8-15` establish the article/comment positions and the 250-character comment input.
- HWE `j_board_article_add.php:33-81` and `j_board_comment_add.php:32-86` establish the server-owned trim, jointly empty article rejection, comment validation, permissions, persistence, and Korean result semantics. The board's command names, arguments, and `CommandModal` terminal-result path remain unchanged.
- HWE authoring/display itself is plain textarea plus escaped Vue interpolation. [Epic #225](https://github.com/peppone-choi/opensamguk/issues/225) explicitly authorizes a minimal shared Tiptap profile, HTML in existing text columns, game-api Jsoup sanitation, and a plain-text fallback; [OPENSAM-86 / #228](https://github.com/peppone-choi/opensamguk/issues/228) narrows that approved UI divergence to the board article editor and article/comment `SafeHtml` sinks. PHP/HWE remains the oracle for lifecycle, limits, permissions, and result semantics—not the approved formatting divergence.
- The scoped code uses the existing shared `RichTextEditor` only for article entry and existing `SafeHtml` only at persisted article/comment sinks. It does not change backend limits, sanitizer ownership, result strings, permissions, or command lifecycle.

## Review finding disposition

- The first independent code/context review found that `<p> </p>` was treated as nonblank, which could bypass the legacy trim-equivalent UI guard. The page-local predicate is now pure and SSR-safe: it strips markup, normalizes non-breaking-space entities, and trims. Regressions cover literal whitespace, `&nbsp;`, decimal/hex non-breaking-space entities, and the TipTap trailing-break paragraph.
- The same review found that the initial DOM-template predicate would fail during Next server pre-rendering. The final predicate has no `document` dependency; a focused test stubs `document` to prove that it still evaluates correctly.
- The initial focused display test mocked `CommandModal`. A direct board lifecycle regression now verifies that formatted HTML reaches the unchanged `{ isSecret, title, text }` payload and that clearing/refetch happens only through `onReserved`, the existing terminal-success callback.
- A temporary board-room class rename was removed after source inspection showed its inline wrapping-flex styles already override `.control-bar`'s grid display. No responsive source expansion remains in the diff. Superseded mobile previews are excluded from the final evidence set.
- Evidence now references the immutable `final-secret-mobile-verified-20260813-1300.png` path, not any superseded same-path capture. The branch was rebased to current `origin/main` `4a3dedf9` before final validation.

## Observed evidence

- TDD: the persisted article formatting, persisted comment formatting, and writable-rich-editor checks each observed RED against the prior plain rendering/textarea. The whitespace-only rich body check then observed RED against the first tag-strip predicate before the pure trim-compatible helper was adopted.
- Focused regression: `./node_modules/.bin/vitest run __tests__/articleBody.test.ts __tests__/board-rich-text.test.tsx __tests__/board-rich-text-lifecycle.test.tsx` completed with 3 files and 6 tests passed. It covers server-safe semantic-empty HTML, both `SafeHtml` sinks, shared-editor presence, legacy title/comment limits, and payload/reset/refetch wiring.
- Browser QA used a local Next page with Playwright API fixtures at desktop 1440×900 and mobile 390×844 for meeting and secret rooms. It observed editable rich bodies, persisted `<strong>` article / `<em>` comment formatting, title/comment limits, formatted input, trailing-break behavior, blocked-secret rendering, safe HTML/plain-text behavior, zero browser-console errors, and no mobile horizontal overflow.
- Final task-local images are `docs/loops/opensam-86-editor-board-2026-08-13/final-meeting-desktop.png`, `final-secret-desktop.png`, `final-meeting-mobile.png`, and `final-secret-mobile-verified-20260813-1300.png`. The verified secret-mobile image is 390×844, SHA-256 `ea49a60cdb1294592c23153847ce7d3d7ddad38d493c927523adaec04f7bda83`; its matching Playwright DOM metrics are `final-browser-metrics-20260813-1300.json`, SHA-256 `5daa88f813164879d01bd344e34f618133dc5abf723728694f237726d0b475da`.
- Final independent re-review cleared the repaired diff: code review confirmed the pure semantic-empty guard and terminal-lifecycle regression; context review confirmed the approved #225/#228 divergence and current-main rebase; three independent visual checks directly opened the verified 390×844 secret capture, matched both SHA-256 values, saw all four controls and the complete editor, and confirmed `scrollWidth=clientWidth=390` with no console errors.

## Known limits and isolated tool baselines

- `scripts/agent/verify-changes.sh --run` runs the full `web/game` Vitest suite, which is baseline-red in `__tests__/live-noop-closures.test.tsx`: its select-pool mock omits `pollCommandResultResponse`. The same focused test fails 7 passed / 1 failed in a detached clean `fe7127fd` worktree, so it is not attributed to OPENSAM-86.
- An early direct `pnpm test` / `pnpm vitest` lookup could not resolve Vitest although the local executable exists; the verifier later invoked the full suite normally. Direct `./node_modules/.bin/vitest` is the reproducible focused runner used above.
- The external no-excuse TypeScript checker requires TypeScript 7 `typescript/unstable/*` APIs, while this repository carries TypeScript 5.7.2. Its exit 2 precedes code analysis; repository `tsc --noEmit` is the applicable type gate.
- Fablize emitted generic tool-failure notices during otherwise successful direct work, and one attempted Node-environment test could not load the repository's jsdom-only `MouseEvent` setup. Both are isolated as tooling constraints: direct command exits are the evidence, and the SSR assertion now runs in the normal test environment with `document` deliberately stubbed.

## Documentation boundary

No README, AGENTS, CLAUDE, or deployment documentation change is required. This artifact and the task-local loop ledger record the approved divergence, PHP/HWE evidence, result-preserving behavior, validation, browser evidence, independent critique, and known non-blocking baselines.
