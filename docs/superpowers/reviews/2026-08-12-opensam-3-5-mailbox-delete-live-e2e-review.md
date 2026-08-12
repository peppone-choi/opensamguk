# OPENSAM-3/5 mailbox delete live E2E independent review

- Date: 2026-08-12
Scope: web/game/e2e/mailbox-delete-live.spec.ts and its dedicated runbook only.
- Independent reviewer: `/root/implement_opensam3_5_mailbox_e2e/mailbox_e2e_review_final`
  (`lazycodex-code-reviewer`), separate from the implementation lane.
Verdict: cleared

## Verified acceptance path

The reviewer confirmed that the dedicated spec covers login with the existing
httpOnly cookie, navigation to mailbox, canceling the confirmation without a
delete request, confirming the exact `deleteMessage` intake with `generalId`
and `msgID`, browser-observed terminal result polling, mailbox reload, success
toast, and removal of the uniquely created row. The optional, explicitly
provisioned denial fixture verifies the exact engine reason and retained row.

Credentials are environment-only. Missing credentials, a missing active
general, an unreachable runtime, or a missing denial fixture is an explicit
`채점대기` skip; it is not a passing live result.

## Findings and disposition

- No critical, high, or remaining low findings.
- Nonblocking watch: the dedicated harness is 324 LOC. It remains a cohesive
  single-scenario harness today; split it if its responsibilities grow.
- The initial wording ambiguity for the denial fixture was corrected before
  final clearance: it is frontend-deletable but resolves denied in the engine.

## Independent validation observed

- `pnpm --dir web/game typecheck` passed.
- Focused Playwright discovery found 2 tests.
- `pnpm --dir web/game test --run __tests__/MailboxPage.delete.test.tsx`
  passed: 7 tests.
- A placeholder-credential Playwright run skipped both tests because no live
  runtime/authorized credentials or denial fixture were available. Live
  execution therefore remains `채점대기`.

## Broader-suite and tooling quarantine

The unchanged `web/game/__tests__/live-noop-closures.test.tsx` currently fails
one unrelated select-pool mock case because its `@/lib/api` mock lacks
`pollCommandResultResponse`. Its working-tree SHA-256 matches `origin/main`
(`5c2af8c5933612d232dfc4de8844862d5da5796734295e31c9a6b90744e96785`), and
neither it nor the implicated select-pool/API files is part of this diff. The
focused mailbox harness above is green.

Generic Fablize wrapper tool-failure notices occurred while retrieving command
output. They were isolated with direct reruns: the persistent-terminal
Playwright probe exited 0 with two intentional skips, and typecheck, focused
harness, strict agent-system check, and diff checks all returned their recorded
results. The wrapper notices are external tooling telemetry, not an
opensamguk product or gate failure.
