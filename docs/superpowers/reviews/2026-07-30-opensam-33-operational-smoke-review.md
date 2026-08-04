# OPENSAM-33 operational smoke final independent review

Date: 2026-07-31

Scope: D4-14 through D4-17 only — isolated 60-second cadence, correlated
intake/Redis/daemon/read/browser observation, false-deny/no-op/stale-refresh
assertions, seed rollback/retry evidence, and isolated-resource cleanup. This
is a local-release review; it does not authorize Jira mutation, a PR, merge,
production deployment, or EC2 access.

## Initial `fix-required` findings and remediation

1. The earlier live chain could reach terminal state through a database polling
   fallback without proving Redis wake publication and delivery. The durable
   marker was repaired after a raw `Instant` JDBC bind failure by binding
   `Timestamp.from(publishedAt)` in the one-row update. The best-effort `202`
   intake behavior remains, with a payload-free warning path on marker failure.
   The final run records one matching marker row, exactly one XRANGE entry and
   payload SHA, XINFO for group `game-engine` and consumer `world-1`, then the
   same entry's XACK and XPENDING `0`.
2. The prior cleanup left three project volumes and five run image aliases. The
   final project runs Compose down with volumes, proves absence of all three
   exact volumes and all five exact aliases, and preserves the reusable source
   image tags.
3. Reservation and execution had not explicitly asserted HTTP `200` and
   equality to the original intake request ID. The final correlation artifact
   asserts both phase statuses and the same request ID.
4. The operational worst-case timeout was too tight. Its default is now
   `E2E_PLAYWRIGHT_TIMEOUT_MS=600000`; caller override remains honored. The
   final operational Playwright result completed in `221617ms`.

## Final observed evidence

The final isolated live artifact is:

`/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`

Its correlated `che_요양` request ID is
`53dfaac0-a9f2-43ea-85ee-a19d6dc87f69`.

- Valid catalog admission was `possible=true`; intake returned `202`.
- Reservation was `200` / `reservationAccepted` at committed world version
  `0`; execution was `200` / `executionApplied` at committed world version
  `3`, both for that same request ID.
- The durable marker row is one row with non-null wake timestamp. The matching
  stream is `sammo:che:scenario_2:w1:turn-daemon:commands`, entry
  `1785479722611-0`, with payload SHA-256
  `325c881d62dad24292d85004fabfd214b1b461929e4c735def364baa574caa11`.
  XRANGE selected precisely that entry; XINFO showed the expected group and
  consumer; XACK completed it and XPENDING was `0`.
- Three post-intake snapshots are successful ticks `2`, `3`, and `4`, all at
  `60` seconds with failed and consecutive-failed counters `0`.
- Authoritative injury/experience/dedication changed from `0/0/0` to `0/10/7`
  at `minVersion=3`. After `turnCompleted`, front-info refreshed and the DOM
  rendered `명성=전무 (10)` and `계급=30품관 (7)`.
- The live gate exited `0`; the operational Playwright run reported 1 passed,
  1 intentionally skipped, and 0 unexpected results.

Focused evidence is fresh: `ScenarioImporterIT` 14/0/0/0,
`RedisCommandStreamIT` 3/0/0/0, `IntakeResultChannelTest` 4/0/0/0, and
`RealtimeRelayIT` 1/0/0/0. The marker repair separately reports focused unit
4/4 and Testcontainers IT 1/1 with skip 0. Web typecheck and the local gate's
shell contracts passed. A fresh rerun passed shell syntax+timeout contract and
web typecheck; `ScenarioMapSeedIT` was 8/0/0/0 (`BUILD SUCCESSFUL` in 2m), and
`CommandReserveServiceTest` was 4/0/0/0 plus IT 1/0/0/0 (`BUILD SUCCESSFUL` in
1m 22s).

Exact cleanup for project `v1-e2e-20260731063246-68779` removed and then proved
absent:

- `v1-e2e-20260731063246-68779_pgdata`
- `v1-e2e-20260731063246-68779_redisdata`
- `v1-e2e-20260731063246-68779_profile-icons`
- `v1-e2e-20260731063246-68779-gateway-api:latest`
- `v1-e2e-20260731063246-68779-game-api:latest`
- `v1-e2e-20260731063246-68779-game-engine:latest`
- `v1-e2e-20260731063246-68779-web-gateway:latest`
- `v1-e2e-20260731063246-68779-web-game:latest`

## Residual QUESTION

The artifact records 9 EventSource opens and 8 `turnCompleted` events.
Reconnect/remount versus duplicate subscription is UNKNOWN. This does not
invalidate the stale-refresh acceptance chain, because an event was followed by
a front-info refresh and rendered authoritative values; it does not establish a
single-subscription or operational-load property.

## Boundaries and unexecuted work

`scripts/agent/verify-changes.sh --run` executed once: five-module Gradle
reported `BUILD SUCCESSFUL in 12m 55s`, 552 suites / 4,763 tests / failures 0 /
errors 0 / skipped 1; `web/game` typecheck PASS and Vitest 46 files / 232 tests
PASS; `git diff --check` PASS. The wrapper's exit 1 derives only from the strict
checker's two existing whole-worktree baselines: user-owned `.codex/config.toml`
personal model pin and the historical 2026-07-27 review missing one anchored
Scope/Verdict under the current rule.

`tools/parity/gate.sh backend`, production deployment/EC2 access, commit, push,
PR creation, merge, and the Jira transition were not executed.
OPENSAM-34 is next in the approved order and is not started. ADR-LITE-026
requires a future PR to mention the review agent in the PR conversation for
three separate rounds, fix and reverify every round, and merge only with
explicit human approval.

Fablize emitted generic tool-failure notices while successful read-only
artifact/document commands exited normally. This is the already documented
external tooling baseline, not an opensamguk runtime or gate result; this review
uses the preserved artifact, direct command exits, and Playwright result JSON
instead. No retry converted that notice into a product outcome.

Verdict: cleared
