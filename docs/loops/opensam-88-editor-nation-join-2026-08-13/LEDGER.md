# OPENSAM-88 — Nation editor and join renderer

## Task-local contract

- Worktree: `/private/tmp/opensam-op88-editor-nation-join`
- Branch: `codex/opensam88-editor-nation-join` from `origin/main` `fe7127fd`
- Scope: `web/game` nation-finance editor, join recruitment-message renderer, focused tests, this ledger, and one review artifact.
- Shared `.ai/` is deliberately untouched: its active OPENSAM-35 contract is stale for this isolated task.

## Oracle and read-channel inventory

- `legacy/devsam-core/hwe/sammo/API/Nation/SetNotice.php:18-29` accepts required raw `msg` through `lengthMax(..., 16384)`; `:53-59` sanitizes before persistence.
- `legacy/devsam-core/hwe/sammo/API/Nation/SetScoutMsg.php:17-28` accepts required raw `msg` through `lengthMax(..., 1000)`; `:52-54` sanitizes before persistence.
- `legacy/devsam-core/hwe/ts/PageNationStratFinan.vue:47-82,315-380` renders rich nation/scout messages, gates editing, and retains the draft on save failure.
- `legacy/devsam-core/hwe/v_join.php:44-50` attaches each nation namespace's `scout_msg`; `hwe/ts/PageJoin.vue:31-53` renders that message in the public join list.
- The required rendering channel is present rather than fabricated: `app/game-api/.../MapPreviewController.kt:116-127` emits `scoutMsg`, and `web/game/lib/types.ts:326-332` consumes it.

## Early checkpoint

- 2026-08-13: oracle and read-channel evidence complete before source editing. The next action is an observed RED focused Vitest run.
- Fablize baseline: the command wrapper repeatedly emitted generic Fablize warnings while successful read-only commands completed. No project gate has failed because of it; final evidence must use direct command output and test/XML results, not those warnings.

## Loop ledger

| Round | Hypothesis | Score before → after | Grader | Decision | Cause / next action |
|---|---|---|---|---|---|
| 0 | Plain text editor and raw join injection diverge from the PHP/hwe rich-HTML behavior. | 0/3 → 3/3 | Focused Vitest plus browser QA | adopted | RED: the finance test found raw HTML shown in a textarea and absent edit controls; the join test found a surviving injected image. GREEN: RichTextEditor exact counters are 9/16384 and 9/1000, only an `applied` result exits the editor, reserved/rejected/pending preserve the draft, cancel restores the fetched draft, and SafeHtml removes the injected image. |
| 1 | Fetched values can also serve as live edit buffers, and visible counters sufficiently express the PHP limits. | 3/3 → 3/3 | Exact-SHA independent review plus focused Vitest/browser QA | rejected/remediated | Review of `eb27f314` showed refresh/post-save could overwrite a modified sibling draft and over-limit HTML could still be submitted. Fetched values and edit buffers are now separate; only an applied field updates its authoritative value. Save is disabled above the serialized-HTML code-point limit. Regressions cover both max/max+1 boundaries, astral Unicode, sibling apply/refresh, and pending/SSE/reserved retention. |

## Test-baseline isolation

- Command: `pnpm exec vitest run __tests__/join-route.test.tsx --reporter=verbose --no-file-parallelism`.
- Observed on 2026-08-13: the new renderer case failed at `join-route.test.tsx:179` because `container.querySelector('img')` returned `<img onerror="alert(1)" src="x">`; this is the task RED signal.
- The same run also timed out in pre-existing, out-of-scope cases at `join-route.test.tsx:98` (stat submission) and `:183` (inheritance/avatar submission). They do not exercise the recruited-message renderer and are recorded as baseline/채점대기 for this task, not retried or weakened.
- The first wrapped run appeared to hang because output was piped. Interrupting it showed the complete 54.37-second Vitest result above; no source, runtime, or temporary-debug artifact was changed. The Node 26 localStorage experimental warning was also emitted.

## Green evidence

- Post-review remediation command `pnpm exec vitest run __tests__/nation-finance-editor.test.tsx --reporter=verbose --no-file-parallelism --testTimeout=30000` exited 0: 9/9 passed in 18.00 seconds. It includes notice/scout exact-max and max+1, astral Unicode, modified-sibling plus other-field apply/refresh, and pending plus SSE refresh followed by reserved retention.
- Post-review `pnpm typecheck` exited 0, and the real Next/Playwright fixture scenario was rerun successfully with the same asserted DOM/request metrics and the updated desktop screenshot hash below.
- `pnpm exec vitest run __tests__/nation-finance-editor.test.tsx --reporter=verbose --no-file-parallelism` exited 0 after the final save-state fix: 5/5 tests passed.
- Fresh final command `pnpm exec vitest run __tests__/nation-finance-editor.test.tsx __tests__/join-route.test.tsx __tests__/RichTextEditor.test.tsx __tests__/SafeHtml.test.tsx --reporter=verbose --no-file-parallelism -t 'NationFinancePage rich-text messages|공개 map preview의 임관 권유문은 안전한 서식만 렌더한다|RichTextEditor|SafeHtml'` exited 0: 11 passed / 6 intentionally filtered tests skipped across four files.
- Fresh final `pnpm typecheck` exited 0.
- Fresh final `python3 tools/agent-system/check.py --strict --base origin/main` exited 0 with 0 errors and 0 warnings; `git diff --check` exited 0.
- Vitest is not configured to emit JUnit/XML in `web/game/package.json`; the exact commands, exit statuses, test totals, and scoped files above are the durable task-local evidence rather than an invented XML artifact.
- Resumed verification caveat (2026-08-13 afternoon): fresh `pnpm typecheck` still exited 0, but the same focused Vitest selector no longer completed inside the configured 5-second async windows on this loaded host. A concurrent Node 26 attempt ended 5 failed / 6 passed / 6 skipped in `194.37s`; a subsequent isolated Node 24 attempt improved to 2 failed / 9 passed / 6 skipped in `172.41s`. The remaining failures were the first finance test timing out at 5 seconds and jsdom still showing `SafeHtml`'s escaped pre-effect state when the join assertion expired. This was not retried again or called green. The earlier 11/11 focused pass remains historical pre-QA evidence; the real Chromium fixture run above independently observed the affected client behavior.

## Independent review follow-up

- Initial independent review was `NOT CLEAR`: the shared `CommandModal.onReserved` callback fires for both `applied` and `reserved`, so it could close a rich editor before the setter executed.
- Remediation is confined to the owned nation-finance page: rich-message saves use the existing `submitCommandAndAwaitResult` with the existing `api.command` intake seam and exit only for `status === 'applied'`. Other finance setters remain on their existing modal path.
- The focused test asserts the exact `api.command('setNotice'|'setScoutMsg', { msg }, generalId)` seam, `applied` closure, `reserved` retention, cancel restoration, and independent notice/scout pending-save state.
- Second review: `CLEAR`, no Critical or Important findings. Its small concurrent-save observation was closed with independent `{ notice, scout }` flags and a RED→GREEN regression; the final narrow review is also `CLEAR` with no actionable findings. PR-visible record: `docs/superpowers/reviews/2026-08-13-opensam-88-editor-nation-join-review.md`.
- After rebasing onto `origin/main`, an independent review of exact SHA `eb27f314` superseded those earlier narrow clearances with `FIX-REQUIRED`: active-draft refresh overwrite, unenforced raw-HTML maximums, and stale type comments. The implementation and focused regressions were remediated as described in round 1; a new exact-SHA re-review is required before push/PR.
- Independent review of remediated product SHA `962aa4cf` cleared all product/code findings after freshly observing finance 9/9, cross-surface 15 passed/6 skipped, typecheck, diff, and artifact hashes. Its sole blocker was the review artifact's temporary non-enumerated pending verdict, now changed to `cleared`; the resulting doc-only SHA requires final exact confirmation.

## Browser-QA status

- Fixture-scoped PASS after the live-stack attempt above remained blocked: a real local Next.js 15.5.20 dev server ran at `:3108`, and Python Playwright drove Chromium `149.0.7827.55` with API/SSE request interception. This is explicitly frontend integration evidence only: it did not run game-api, authentication, the daemon, a database, credentials, or production data.
- Desktop `1440x900` nation-finance: HTTP 200, sanitized `<strong>`/`<em>` content retained while fixture `<img onerror>` and `<script>` were absent from both message subtrees; the real editor reported `29 / 16384`; save posted `setNotice` with `{msg:"<p>새 국가 방침</p>"}`, `generalId=10`, and `turnIdx=0`; the fixture returned an `executionApplied` result and the editor closed with the success message. DOM metrics were body/document width `1440/1440`, horizontal overflow false, browser-console errors 0, and page errors 0.
- Mobile `390x844` join: HTTP 200; the public scout message rendered exactly `<p><strong>천하</strong>의 <em>인재</em></p>` after the injected image/event handler/script were removed. DOM metrics were body/document width `390/390`, horizontal overflow false, browser-console errors 0, and page errors 0.
- Task-local untracked QA captures (not repository evidence) after the remediation rerun were `/tmp/op88-browser-qa/nation-finance-desktop.png` (`1440x1287`, SHA-256 `08c75ea7944b4a716c8c3f346206a35f3eea1d322ea416220e02093368c911a7`), `/tmp/op88-browser-qa/join-mobile.png` (`390x1251`, SHA-256 `4eac9c71ff5d58c8a27c13c3d72ea6adc332eed83bb792cfb134993f31b3e8fe`), and `metrics.json` (SHA-256 `aa3f0373af4064e7824ff990d886412c4ff0148cb3b3cb553095414676048501`). The metrics hash remained stable because all asserted DOM/request metrics were identical; the desktop pixels changed with the remediated source build. The observed metrics above are copied here so completion does not depend on ephemeral files.
- Tooling history: the prescribed `with_server.py` helper orphaned a Node 26 Next process that listened but never returned HTTP while compiling a 751 MB stale `.next` cache. The task-owned process was terminated, the ignored cache was moved recoverably to `/tmp/op88-next-stalled-cache`, and the successful run used the installed Node 24 plus Turbopack. Two initial browser attempts failed only brittle harness assertions (global Next `<script>` counting, then a hardcoded pre-normalization counter); both were corrected before the single successful run. No product assertion was weakened.

## Build attempt

- `pnpm build` compiled, completed type/lint checking, and generated all six static pages, then continued in final trace collection without exit for more than twelve minutes. It was interrupted and returned exit 130, so this ledger does not count the build as passing. The output contained existing lint/Sentry warnings outside the owned files.

## Remaining gates

- Human approval was received before the branch push and PR creation; PR #397 is open and its review remediations are being pushed under that approval. Before merge, rerun strict/diff verification, require remote exact-SHA CI and the mandated PR-conversation review rounds, and disclose the earlier default-five-second resumed local timing failures alongside the later explicit-30-second green result. Full-stack/API/daemon/production browser coverage remains unexecuted and is not implied by the fixture-scoped PASS.
