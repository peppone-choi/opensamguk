# OPENSAM-88 — Nation editor and join renderer review

Date: 2026-08-13

Scope: web/game/ nation-finance editor, join renderer, focused tests, and task-local parity/review evidence

Verdict: cleared

## Scope reviewed

- `web/game/app/game/nation-finance/page.tsx`
- `web/game/app/game/join/page.tsx`
- `web/game/__tests__/nation-finance-editor.test.tsx`
- `web/game/__tests__/join-route.test.tsx`
- Task-local loop evidence under `docs/loops/opensam-88-editor-nation-join-2026-08-13/`

No shared editor, backend, board, mail, or diplomacy file is in scope.

## Oracle and contract evidence

- `legacy/devsam-core/hwe/sammo/API/Nation/SetNotice.php:18-29,53-59` validates raw `msg` through 16,384 code points and sanitizes before persistence.
- `legacy/devsam-core/hwe/sammo/API/Nation/SetScoutMsg.php:17-28,52-54` validates raw `msg` through 1,000 code points and sanitizes before persistence.
- `legacy/devsam-core/hwe/ts/PageNationStratFinan.vue:47-82,315-380` supplies the editable rich-message lifecycle: edit gate, save/cancel, and retained draft on an unsuccessful save.
- `legacy/devsam-core/hwe/v_join.php:44-50` and `hwe/ts/PageJoin.vue:31-53` use the nation `scout_msg` or `'-'` in the public join list.
- The required existing read channel is `MapPreviewController`'s `scoutMsg`; this change neither adds a read API nor substitutes `infoText`.

## Findings and resolution

The initial independent review found that `CommandModal.onReserved` fires for both `applied` and `reserved`. That would leave an editor before an immediate setter had executed.

The owned nation-finance page now uses the existing `api.command` and `submitCommandAndAwaitResult` seam for rich-message saves and exits an editor only when the outcome is `applied`. Reserved, rejected, and pending outcomes retain the draft. The existing modal remains responsible for the unrelated finance setters.

The first rebased exact-SHA review (`eb27f314`) superseded the earlier narrow clearance and found two important defects: any refresh could overwrite an active sibling draft, and the raw-HTML limits were display-only. It also found stale plaintext/deferred-editor type comments.

The remediation separates fetched authoritative values from edit buffers, seeds a buffer only when that editor is opened, updates only the successfully applied field, and never rewrites an active/saving sibling during SSE or post-save refresh. The save path now counts serialized HTML Unicode code points, permits the exact PHP maximum, and disables submission at maximum plus one; backend validation remains authoritative. Focused regressions cover notice/scout boundaries (including astral Unicode), modified-sibling plus other-field apply/refresh, and pending plus SSE refresh followed by a reserved result. The type comments now describe sanitized rich HTML.

Independent re-review of exact product SHA `962aa4cf` cleared every product/code finding. The only reported blocker was this artifact's temporary non-enumerated `pending exact-SHA re-review` verdict, which failed the mechanical strict gate; this terminal `cleared` value and the fresh evidence below resolve that documentation-only blocker. The final doc-only amend is subject to exact-SHA confirmation.

## Verification observed

- Focused finance suite: 5/5 passed, including applied, reserved, cancel, and concurrent-save state behavior.
- Post-review focused finance suite: 9/9 passed in 18.00 seconds with a 30-second per-test ceiling, including both exact-max/max+1 boundaries and both refresh-retention regressions.
- Fresh post-amend cross-surface selector: 15 passed / 6 intentionally filtered tests skipped across finance, join, RichTextEditor, and SafeHtml in 46.43 seconds with the explicit 30-second per-test ceiling.
- Focused cross-surface suite: 11 passed / 6 intentionally filtered tests skipped across the rich-editor, safe-renderer, finance, and join-renderer cases after the final concurrent-save case was added.
- `pnpm typecheck` exited 0 after the result-state remediation.
- A resumed local focused rerun did not reproduce the earlier green total on the now-loaded host. Node 26 ended 5 failed / 6 passed / 6 skipped in `194.37s`; the final Node 24 isolation ended 2 failed / 9 passed / 6 skipped in `172.41s`, with one 5-second finance timeout and the join assertion expiring while jsdom still displayed `SafeHtml`'s escaped pre-effect state. These are recorded as current local failures, not passes; remote exact-SHA CI remains required before merge readiness.
- Live-stack browser QA remained unavailable without secret-bearing compose configuration. The bounded fallback ran the real local Next.js 15.5.20 app with Python Playwright/Chromium and explicit API/SSE fixtures: desktop `1440x900` exercised sanitized finance rendering plus the real editor/save/result UI path; mobile `390x844` exercised sanitized public join rendering. Both returned HTTP 200, retained the allowed strong/emphasis markup, removed injected image/event-handler/script content, had zero console/page errors, and had no horizontal overflow. The finance request observation was `setNotice`, `{msg:"<p>새 국가 방침</p>"}`, `generalId=10`, `turnIdx=0`, followed by fixture `executionApplied` and editor closure.
- This browser result is intentionally scoped: it proves the Next/DOM/client-request behavior against fixtures, not game-api, auth, command daemon, database, credentials, or production behavior. Full-stack browser coverage is unexecuted, not passed.

## Documentation scope

README, AGENTS, and CLAUDE remain unchanged because this changes no public API, installation/configuration, deployment, or operational contract. The parity evidence, result-state decision, review trail, and blocked runtime QA are recorded in this review and the task-local loop ledger.
