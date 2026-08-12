# OPENSAM-3/5 mailbox delete live E2E

## Scope

This is the dedicated Playwright acceptance scenario for the already-wired
mailbox delete flow. It exercises only a disposable self-authored message and
does not seed, mutate, or remove any pre-existing mailbox data.

The spec is:

```text
web/game/e2e/mailbox-delete-live.spec.ts
```

## Preconditions

- A local/non-production game stack is reachable at `E2E_GATEWAY_URL` and
  `E2E_GAME_URL` (defaults: `http://localhost:3000` and
  `http://localhost:3001`).
- The configured account owns an active general and is permitted to send a
  private message to itself.
- Supply credentials only through the shell environment. Do not put values in
  a command history, tracked file, artifact, or test source.

Missing credentials, an unreachable stack, or an account without a general
causes an explicit Playwright `채점대기` skip rather than a false pass.

## Required live scenario

Ensure the caller's secure environment has already exported
`E2E_MAILBOX_USERNAME` and `E2E_MAILBOX_PASSWORD`, then run only the dedicated
file so the existing `v1-core-live.spec.ts` Compose-only module setup is not
required. Do not place credential values on the command line:

```bash
E2E_GATEWAY_URL=http://localhost:3000 \
E2E_GAME_URL=http://localhost:3001 \
pnpm --dir web/game exec playwright test e2e/mailbox-delete-live.spec.ts \
  --grep "canceling then confirming"
```

Observed assertions, in order:

1. Login establishes the existing httpOnly access cookie.
2. A uniquely tagged private self-message is created through the real
   `sendMessage` intake and its terminal result.
3. Dismissing `삭제하시겠습니까?` makes no `POST /api/game/api/command/deleteMessage` request.
4. Confirming it makes that exact POST with the current `generalId` query and
   the created `msgID` body.
5. The browser itself observes the terminal `GET /api/game/api/command/result/{requestId}`
   response, then a post-success `GET /api/game/api/mailbox/recent` reload.
6. The success toast appears and the uniquely tagged row is absent after the
   reload.

The self-message is deliberately created by the test, then deleted by the
test; a failure before deletion can leave only that uniquely prefixed local
test message for the test account to clean up manually.

## Optional denial fixture

The engine denial path cannot be generated safely without a deliberately
provisioned fixture that remains frontend-deletable but resolves denied in the
engine. To exercise it, an operator may export the following local-only
fixture values (while retaining the securely exported credentials) and run:

```bash
E2E_MAILBOX_DENIAL_TEXT=<exact-visible-fixture-text> \
E2E_MAILBOX_DENIAL_REASON=<exact-terminal-reason> \
E2E_MAILBOX_DENIAL_SCOPE=private \
pnpm --dir web/game exec playwright test e2e/mailbox-delete-live.spec.ts \
  --grep "configured live denial fixture"
```

`E2E_MAILBOX_DENIAL_SCOPE` is optional and accepts `private`, `national`,
`public`, or `diplomacy`. The scenario verifies a `202` intake, browser-observed
terminal `RESOLVED !ok`, the exact engine reason, and preservation of the row.
Without every required denial fixture variable it reports `채점대기` (skip).

## Lightweight checks

```bash
pnpm --dir web/game typecheck
E2E_MAILBOX_USERNAME=placeholder E2E_MAILBOX_PASSWORD=placeholder \
  pnpm --dir web/game exec playwright test e2e/mailbox-delete-live.spec.ts --list
pnpm --dir web/game test --run __tests__/MailboxPage.delete.test.tsx
```

When the live stack is unavailable, the last command is the mockable existing
component harness. It verifies the same cancel/success/deny/pending branches
without credentials, but it does not replace the live browser scenario.

## Known baseline at authoring

Running Playwright discovery with no file selector currently loads
`e2e/v1-core-live.spec.ts`, which requires `E2E_COMPOSE_PROJECT_NAME` at module
load and therefore lists zero tests when that unrelated variable is absent.
Use the file-scoped command above for this scenario. This does not affect the
dedicated spec's discovery or runtime behavior.
