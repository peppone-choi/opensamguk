# OPENSAM-87 — mailbox and diplomacy rich-text wiring

## Scope

- Owned implementation: `web/game/app/game/mailbox/page.tsx`,
  `web/game/app/game/diplomacy/page.tsx`, and their focused tests.
- Owned evidence: this ledger and the task review artifact.
- Excluded: `RichTextEditor`/`SafeHtml` internals, board, nation-finance, join,
  and chief-center source files.

## Oracle and trust boundary

- PHP mailbox API: `legacy/devsam-core/hwe/sammo/API/Message/SendMessage.php:24-34`
  accepts the existing `mailbox` and nonempty `text` payload; its UI predecessor
  is `hwe/ts/components/MessagePanel.vue:1-34,735-752`.
- PHP diplomacy API: `legacy/devsam-core/hwe/j_diplomacy_send_letter.php:15-58`
  reads `brief` and `detail`, trims both, and requires a nonempty brief. The
  frontend predecessor submits those fields in `hwe/ts/diplomacy.ts:68-103`.
- Legacy read shape is text/HTML-capable: mailbox `v-html` is at
  `hwe/ts/components/MessagePlate.vue:106-113`; diplomacy renders escaped
  line-preserving text at `hwe/ts/diplomacy.ts:249-273`. This ticket is the
  approved UI-level rich-text divergence, not a game-logic/RNG/log change.
- Modern intake is authoritative for untrusted rich text:
  `app/game-api/.../sanitize/HtmlSanitizer.kt:16-75` safelists the shared tags
  and color-only span style for both `sendMessage.text` and
  `diploSendLetter.{brief,detail}`. The client must render stored content with
  `SafeHtml`, never raw `dangerouslySetInnerHTML`.

## Same-surface survey

- `chief-center/page.tsx:150` has a raw HTML sink for server-owned command
  briefs, not user-authored mailbox/diplomacy document text. It is documented
  for review but excluded from this ticket's ownership.
- Board, nation-finance, and join are separate OPENSAM-83 child surfaces and
  remain untouched.

## Round 0 — baseline

| Round | Hypothesis | Score before → after | Grader | Decision | Root cause |
| --- | --- | --- | --- | --- | --- |
| 0 | The two owned pages still use plain `<textarea>` writers and React-text/raw renderers instead of the already-approved shared editor/renderer. | 13/13 focused tests → pending | `vitest` focused page tests | baseline | Existing UI wiring predates OPENSAM-84/85 shared components. |

Baseline command observed on 2026-08-13:

```text
web/game/node_modules/.bin/vitest run __tests__/MailboxPage.delete.test.tsx __tests__/DiplomacyPage.command.test.tsx
2 files passed; 13 tests passed.
```

## Planned single change hypothesis

Wire the existing `RichTextEditor` only to mailbox text and diplomacy detail,
keep diplomacy brief as a plain input, preserve existing submit/result
lifecycle, and render persisted mailbox/diplomacy document content through
`SafeHtml`. Focused regression tests must first fail because those accessible
editor/sanitized-render surfaces do not yet exist.

## Round 1 — observed RED, wiring, and recovery

| Round | Hypothesis | Score before → after | Grader | Decision | Root cause |
| --- | --- | --- | --- | --- | --- |
| 1 | Mailbox text and diplomacy detail can adopt the shared editor and `SafeHtml` without changing their intake/result lifecycle. | 0/2 new surface tests → 2/2 | `vitest` surface test | accepted | The pages bypassed the shared components, not the server sanitizer. |
| 1a | The inherited diplomacy command-result test must submit the editor's serialized HTML rather than its removed textarea value. | 5/6 → 6/6 | `vitest` diplomacy command test | accepted | Its former selector targeted `본문을 입력하세요`, which no longer exists. |
| 1b | Mailbox must forward serialized editor HTML and wait for the command result before showing success. | 0/1 → 1/1 | `vitest` mailbox command test | accepted | The previously untested send path retains `submitCommandAndAwaitResult`; a page-boundary regression now locks it. |

Observed RED before production edits:

```text
web/game/node_modules/.bin/vitest run __tests__/EditorMailDiplomacySurface.test.tsx --reporter=verbose
1 file / 2 tests failed:
- mailbox could not find role=textbox, name=서신 내용
- diplomacy could not find role=textbox, name=외교 서신 본문
```

The first combined rerun then exposed the expected inherited selector failure
in `DiplomacyPage.command.test.tsx` (14/15 assertions passed). That focused
test now stubs the shared editor at its page callback boundary and asserts the
serialized `<p>함께 합시다.</p>` command payload plus the pre-existing awaited
`applied` result lifecycle. The new surface regression deliberately uses the
real editor and renderer.

Direct synthetic ProseMirror typing in jsdom was attempted twice. The editable
surface mounted, but jsdom retained `<p></p>` rather than producing a document
transaction. This is isolated test-environment behavior, not a production
change: real shared-editor mount/sanitization is covered in the surface test,
and lifecycle forwarding is covered at the page callback boundary.

The shared editor intentionally serializes an empty document as `<p></p>` and
counts raw submitted HTML in Unicode code points. Its existing unit test locks
that contract (`<p>한😀</p>` is 9 code points). Therefore the mailbox surface
test verifies the configured `/ 500` cap rather than incorrectly expecting a
visible-text `0 / 500`; `RichTextEditor` itself is explicitly out of scope.

## Runtime/manual QA

The supplied Playwright helper was used after its required `--help` preflight.
It started `pnpm dev` in the isolated `web/game` worktree and reported port
3001 ready. Two browser navigation attempts to `/game/mailbox` then failed
before any page DOM became observable:

1. default load navigation timed out after 30 seconds;
2. a materially different retry used a 120-second budget and
   `wait_until='domcontentloaded'`, which also timed out.

No credentials, `.env` files, or external services were read. Therefore live
authenticated visual QA is `채점대기` in this checkout, rather than inferred
from the unit surface. The concrete fallback evidence is the real shared
editor/`SafeHtml` focused rendering test plus page-boundary command lifecycle
tests. A running turnkey stack with an authenticated session is required to
close this manual-QA gap.

## Validation evidence

Observed after the implementation and test adjustments:

```text
./node_modules/.bin/vitest run \
  __tests__/MailboxPage.delete.test.tsx \
  __tests__/MailboxPage.command.test.tsx \
  __tests__/DiplomacyPage.command.test.tsx \
  __tests__/EditorMailDiplomacySurface.test.tsx \
  --reporter=json --outputFile=/tmp/opensam-op87-editor-mail-diplomacy-focused.json
success=true; 9 suites passed; 16 tests passed; 0 failed

pnpm typecheck
exit=0

git diff --check
exit=0
```

The required full `pnpm test` was also run once. It completed with 57 passing
files / 298 passing tests and one pre-existing, out-of-scope failure:
`__tests__/live-noop-closures.test.tsx` mocks `@/lib/api` without the now-used
`pollCommandResultResponse` export, so its select-pool assertion sees zero
poll calls. This lane does not modify that test, select-pool, or `lib/api`; the
focused OPENSAM-87 suite above is clean. The full suite also reports unrelated
React warnings in auction/admin tests; none originate in the owned diff.

## Independent-review correction

The first independent review correctly returned `FIX-REQUIRED`: replacing the
native mailbox textarea removed its browser-enforced 500-character ceiling,
while `RichTextEditor.maxTextLength` only renders a counter and the current
server raw-length map has no mailbox/diplomacy entry. The issue is within this
lane's behavior but extending shared editor or server-sanitizer ownership is
not. The narrow correction therefore reuses the already-exported
`countHtmlCodePoints` rule in both owned submit handlers and disables each
owned send button when its serialized body exceeds 500 raw code points.

New page-boundary regressions supply 501 Korean code points (therefore more
than 500 after the editor's `<p>` serialization), assert the visible send
button is disabled, and assert no command reservation occurs. The corrected
focused command was observed as:

```text
./node_modules/.bin/vitest run \
  __tests__/MailboxPage.delete.test.tsx \
  __tests__/MailboxPage.command.test.tsx \
  __tests__/DiplomacyPage.command.test.tsx \
  __tests__/EditorMailDiplomacySurface.test.tsx \
  --reporter=json --outputFile=/tmp/opensam-op87-editor-mail-diplomacy-focused.json
success=true; 9 suites passed; 18 tests passed; 0 failed

pnpm typecheck
exit=0
```

The independent reviewer has been asked to re-audit this correction before the
review artifact, commit, and PR gates proceed.

The re-audit returned `CLEARED`. Its review artifact is
`docs/superpowers/reviews/2026-08-13-opensam-87-editor-mail-diplomacy-review.md`.
After the artifact's anchored metadata was aligned with the repository checker,
the final strict gate was observed as `Errors: 0; Warnings: 0`:

```text
python3 tools/agent-system/check.py --strict --base origin/main
exit=0
```

## Environment notes

- The requested isolated worktree was absent at task start; it was created from
  `origin/main` at `fe7127fdd240632b448409b7f7c04e3ef7c1e966` before edits.
- The worktree intentionally omits git-ignored PHP and downloaded skill bodies;
  they were read read-only from the source checkout.
- `corepack` is not installed, and `pnpm --dir ... exec` did not locate Vitest.
  `pnpm 10.33.0` installed the lockfile-consistent local dependencies, then the
  local Vitest binary ran the baseline. These are tooling baselines, not product
  failures.
- Vitest emits Node's non-fatal `localStorage is not available because
  --localstorage-file was not provided` experimental warning in this shell;
  focused JSON reports still completed and are used for the pass/fail evidence.

## Approval gates

No additional approval is needed for the scoped implementation, commit, push,
and ready PR: the delegated task explicitly authorizes them. Merge and deploy
remain out of scope.
