# OPENSAM-87 Mailbox and Diplomacy Rich-Text Review

Date: 2026-08-13
Scope: `web/game` mailbox/diplomacy rich-text writer/renderer surfaces, their focused tests, and task evidence.
Reviewer: independent Codex code reviewer
Verdict: cleared

## Review outcome

The first review correctly found that replacing mailbox's native
`textarea maxLength={500}` with `RichTextEditor` had removed an effective
input boundary: the shared editor's existing `maxTextLength` is presentation
only. The correction stays within this lane's page ownership. Both send paths
now use the shared raw-Unicode-code-point function to disable oversize sends
and to reject them again immediately before command reservation.

The reviewer re-audited the corrected diff and returned `CLEARED`.

## Evidence reviewed

- PHP mailbox input contract:
  `legacy/devsam-core/hwe/sammo/API/Message/SendMessage.php:24-37`.
- PHP diplomacy input contract:
  `legacy/devsam-core/hwe/j_diplomacy_send_letter.php:16-61`.
- HWE read/UI context:
  `hwe/ts/components/MessagePanel.vue:735-752`,
  `hwe/ts/components/MessagePlate.vue:106-113`, and
  `hwe/ts/diplomacy.ts:68-103,249-273`.
- Shared rich-text rule:
  `web/game/components/RichTextEditor.tsx:17-18` counts submitted HTML with
  `Array.from`, the existing raw-Unicode-code-point convention.
- Client trust boundary:
  mailbox and diplomacy stored document content is rendered through `SafeHtml`;
  neither owned page introduces a raw HTML sink.
- Result lifecycle:
  mailbox and diplomacy retain `submitCommandAndAwaitResult` and show success
  only after an applied terminal result.
- Length correction:
  the owned pages disable 501-code-point serialized bodies and independently
  reject them before calling the command intake/reservation helper.

## Verification reviewed

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

git diff --check
exit=0
```

The reviewer independently ran the applicable focused tests and `git diff
--check`; it found no residual safety, lifecycle, parity, or scope blocker.

## Residual risks

- Live authenticated browser QA is `채점대기`: the supplied Playwright helper
  observed local port 3001 ready twice, but `/game/mailbox` never reached DOM
  ready after 30 seconds and then 120 seconds. No credentials or `.env` files
  were read. A turnkey stack with an authenticated session is needed for that
  final visual observation.
- The full `web/game` suite has one unrelated failure in
  `live-noop-closures.test.tsx`: its `@/lib/api` mock lacks
  `pollCommandResultResponse` for the select-pool path. This lane did not
  modify that surface; the focused OPENSAM-87 suite is clean.
