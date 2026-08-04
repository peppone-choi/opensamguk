# Agent Handoff

- Updated at: 2026-07-31
- State: OPENSAM-34 local grader/docs complete; actual D4-31~35 observation blocked

## Current handoff — OPENSAM-34

- Local-only closeout is complete: the manual-only predeploy workflow, read-only
  grader, runbook, and final independent re-review are ready/`cleared`.
- D4-31~35 are still blocked/incomplete as actual production observations. The
  `ec2-prod` runner was observed offline in both repositories, Jira remains
  `할 일`, and no production Go result exists.
- Local evidence: both `bash -n` checks and the hermetic contract PASS; YAML
  parse and scoped untracked-file whitespace PASS; fresh V29 `2/0/0/0` and V32
  `9/0/0/0`, `BUILD SUCCESSFUL in 2m 2s`.
- The initial independent `fix-required` items were remediated: pre-checkout
  immutable ref validation, strict integer/health checks, read-only SQL,
  canonical `serverId`, robust `df -Pk`, and exact V29 index shape. Final
  re-review confirmed the fixes and found no new blocker.
- `scripts/agent/verify-changes.sh` classification ran; do not claim a fresh
  `--run` for OPENSAM-34. No EC2/prod access, workflow dispatch, `.env*`/secret
  access, commit, push, PR, merge, deploy, Jira mutation, deletion, legacy/golden
  write, or test weakening occurred.
- Repeated generic Fablize tool-failure notices on successful read-only discovery
  are an isolated external tooling baseline, not a product or grader result.
- Next: only after an EC2 resume and explicit user approval, dispatch the
  manual workflow with `server`, immutable `expected_tag`,
  `expected_scenario_code`, positive `world_id`, and operator-approved
  `min_free_gib`/`min_free_percent`. ADR-LITE-026 still requires three separate
  PR-conversation review-agent rounds, remediation/reverification, then explicit
  human merge approval.

## Historical handoff — OPENSAM-33

- User order: `OPENSAM-31 → 32 → 33 → 34 → 149 → 35`; write scopes remain
  strictly sequential.
- OPENSAM-33 D4-14~17 is completed/released locally; Jira remains `할 일`.
  The final artifact is
  `/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`.
- Its one `che_요양` request has intake `202`, reservation/execution `200` with
  the original request ID, durable marker → XRANGE → XINFO → XACK/XPENDING=0,
  three 60-second ticks (`2 → 3 → 4`, zero failures), authoritative
  `0/0/0 → 0/10/7`, and `turnCompleted` → refresh → DOM (`명성=전무 (10)`,
  `계급=30품관 (7)`).
- The initial `fix-required` chain was remediated: `Timestamp.from` fixes the
  marker bind, exact isolated volumes/aliases are removed, phase status/ID
  equality is asserted, and the operational timeout defaults to `600000ms`
  while preserving an override. Final independent review is cleared.
- Residual QUESTION: 9 EventSource opens / 8 `turnCompleted`; reconnect/remount
  versus duplicate subscription is UNKNOWN. It is non-blocking only for the
  stale-refresh acceptance criterion, not a load/subscription claim.
- Focused evidence is fresh (`ScenarioImporterIT` 14/0/0/0,
  `RedisCommandStreamIT` 3/0/0/0, `IntakeResultChannelTest` 4/0/0/0,
  `RealtimeRelayIT` 1/0/0/0; marker 4/4 + 1/1 skip 0; typecheck/shell PASS).
  Fresh rerun additionally has shell syntax+timeout contract PASS, web typecheck
  PASS, `ScenarioMapSeedIT` 8/0/0/0 (`BUILD SUCCESSFUL` in 2m), and
  `CommandReserveServiceTest` 4/0/0/0 plus IT 1/0/0/0 (`BUILD SUCCESSFUL` in
  1m 22s).
- `scripts/agent/verify-changes.sh --run` executed once: five-module Gradle
  `BUILD SUCCESSFUL in 12m 55s`, 552 suites / 4,763 tests / failures 0 / errors
  0 / skipped 1; `web/game` typecheck PASS and Vitest 46 files / 232 tests PASS;
  `git diff --check` PASS. Its exit 1 is only the strict checker’s two existing
  whole-worktree baselines: user-owned `.codex/config.toml` personal model pin,
  and the historical 2026-07-27 review missing one anchored Scope/Verdict under
  the current rule.
- Do not infer unrun gates as green: `tools/parity/gate.sh backend`, production
  deploy/EC2, commit/push/PR/merge, and Jira transition were not executed.
  Fablize generic read-command notices are an isolated external tooling
  baseline; artifact and direct command evidence, not those notices, determine
  this closeout.
- Next at that time was OPENSAM-34; its local grader closeout is now recorded
  above. ADR-LITE-026 remains unchanged.

## Historical handoff — OPENSAM-32

- OPENSAM-32 D4-08~13 completed:
  - `che_불가침제의` shortcut consumes the server catalog
    `destNationID/year/month` form and fails closed when lookup, row, or form
    is missing.
  - D4-08/10/12 proposals serialize the preloaded destination nation color.
  - A Testcontainers regression connects lifecycle proposal → DB message →
    accept → bilateral flush → same-world declaration.
- Fresh focused verification: logic 72/72, engine 34/34, infra 2/2, frontend
  16/16, frontend typecheck PASS; backend failure/error/skip 0.
- Independent review ended `cleared`.
- Live browser remains `채점대기` because the required compose runtime setting
  is absent. Full PHP accept logs/events are an explicit follow-up outside this
  Jira state-transition scope.
- Strict checker remains red only on two out-of-scope baselines: user-owned
  `.codex/config.toml` model pin and historical uppercase
  `Verdict: CLEARED` review anchor.
- Jira remains `할 일`; no external transition, commit, push, merge, deploy,
  production access, data deletion, secret access, legacy/golden write, or
  test weakening occurred.
- OPENSAM-33 was opened through a fresh `$os-start-task` contract only after
  this closure.

## Historical handoff — OPENSAM-31

- OPENSAM-31 active-plan checklist records exact D4-01~07 commands and
  objective verdicts. Independent review ended `cleared`.
- Verification: fresh backend XML 552/4,758 with failure·error 0 and skip 1;
  `web/game` typecheck + 46/227; Agent OS contract and diff check PASS.
- The seven checklist runtime commands were documented, not individually
  rerun. Browser-facing SseEmitter delivery remains explicitly `채점대기`.

## Historical handoff — v1 비운영 종결

- Report: `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`
  (2026-07-29 final addendum)
- Frozen input manifest: `docs/loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md`
  — 388 docs at audit freeze; newer evidence/docs are not retroactively counted.
- Final ledger/review: `docs/loops/v1-nonoperational-completion-2026-07-27/LEDGER.md`,
  `docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`
- Evidence: schema 4 PHP A/B 12-month/36-phase exact replay; backend 550/4,753
  failure·error 0; affected backend 185/1,172 failure·error 0; web/game 46/227 +
  typecheck; local Docker runtime9 Playwright 1 passed/0 unexpected; independent
  review CLEAR/APPROVE.
- Closed: command form/terminal path, monthly/event, battle/conquest, AI,
  side systems, world-scoped read/restart, core-live frontend and stored-log
  evidence. Engine `MessageRepository` boot wiring and local E2E synchronization
  were remediated and observed.
- Still not done: production/S6 rollout, canary/expand/backfill/capacity policy,
  live EC2 cutover. Do not turn this closure into a deploy approval.
- Final Agent OS whole-worktree verification: checker의 cleared/quarantined
  disjoint Scope union 수정 뒤 `scripts/agent/verify-changes.sh --run`을 정확히
  한 번 실행했다. Gradle 5개 모듈 `BUILD SUCCESSFUL in 13m 27s` / 29 tasks,
  `web/game` typecheck + 46/227, Agent OS contract와 diff/whitespace는 PASS다.
  strict checker는 error 1 / warning 0이며 exit 1은 untouched user-owned
  `.codex/config.toml` 최상위 personal model pin 하나뿐이다. cross-agent
  finding은 독립 scope-union review `cleared`로 제거됐다. strict green 또는
  ship/merge ready가 아니며, 증거는
  `.omo/evidence/v1-final/verify-changes-final2/{verify-changes.log,exit-code.txt}`다.
- Agent-harness note: 문서 점검 중 Fablize가 반복적인 generic tool-failure
  경고를 냈다. 재실행으로 덮지 않았고, 제품/게이트 판정은 위의 보존된
  verify artifact와 exit code만 사용한다.
- Safety: no commit/push/merge/deploy/data deletion/secret access.

## Historical handoff — 2026-07-26 v1 audit snapshot

## Historical handoff — CQRS B1

## Landed

| Ticket | PR | Notes |
|--------|-----|-------|
| OPENSAM-127 | #302 + residual-reads | all GWT-named read/Redis cohorts world-scoped |
| OPENSAM-128 | #303 | flush contract + handoff |
| OPENSAM-129 | #304 | two-world isolation IT |

## Next

- B2 S3 generation/fence/CAS (OPENSAM-130+)
- Optional OPENSAM-10 tournament fight()
- Activation/cutover still blocked (prod EC2 + live capacity)

## Do not

- prod deploy/cutover/W3 activation without gates
- co-widen JdbcFlushExecutor without sequential handoff

## B2 handoff (2026-07-21)
- B2 S3-T1..T3 landed or landing on main (130–132).
- Next CQRS priority: S4 inbox authority / outbox (plan ARCH-S4), not activation.

## B2 complete evidence close (2026-07-21)
- Reviews 131/132 cleared; TurnRunServiceFlushRecoveryTest on real runTick/intake; SCRATCH op131/op132/sweep archived in implementer session.
