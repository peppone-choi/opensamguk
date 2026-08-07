# Agent Handoff

## Current handoff (2026-08-06) — OPENSAM-33 완료 / OPENSAM-34 GCP retarget

- **이 절이 아래 두 절보다 우선한다.** 아래 RTK14 절(2026-08-04)과 OPENSAM-34 절(2026-07-31)의 미완/blocked 서술은 모두 대체됐다.
- RTK14: PR #356 **머지 완료**(2026-08-04T06:39:59Z). `origin/main`에 V37·V38 존재, 후속 #362·#363도 머지. 아래 "remediation remains uncommitted" / "None of those release steps is complete"는 이력이다.
- OPENSAM-33: Jira `완료` 전이 실행 + 증거 코멘트 기록.
- OPENSAM-34: blocker가 "러너 부재"에서 "라벨 drift + grader 결함"으로 재규정됐고 둘 다 로컬에서 닫혔다. 프로덕션 러너 `gcp-prod-opensamguk`은 **online**(labels `[self-hosted, Linux, X64, gcp-prod]`, `ec2-prod` 라벨 없음).
- 브랜치 `ops-predeploy-gcp-retarget` 커밋 `9da40167`: predeploy 워크플로 라벨 `gcp-prod`, grader glob에 Kotlin 마이그레이션 포함(`.sql`만 37 / 포함 38 → D4-35 false NO-GO 방지), Kotlin 디렉터리 부재 fail-closed(false GO 방지), 계약 테스트 스텁을 checkout 파생으로.
- **다음 담당자가 할 일:** PR 생성 → 리뷰(ADR-LITE-026: 별도 3회 리뷰 라운드 + 명시적 인간 머지 승인) → 머지. 그 다음에야 predeploy Go 체크 실제 dispatch를 논의할 수 있다. D4-31~35 production 관측·배포·dispatch는 **미실행이며 별도 명시 승인 필요**.

---

## RTK14 full-roster handoff (2026-08-04) — 머지 완료, 이력

- Source worktree: `/tmp/opensamguk-possession-five-stats`, branch `codex/fix-possession-five-stats`.
- Docker worktree: `/tmp/opensamguk-docker-scenario-five-stats`, branch `codex/v26-effective-scenario-mount`, PR #25.
- Exact prior source HEAD `725195fea29b3434cc358e3d262c6c440830dab7` was reviewed with a P1 released-V26 forward-repair finding. This branch no longer modifies V26 at all: `V26__npc_lifecycle_phase_units.kt` and `V26NpcLifecycleMigrationTest.kt` are reverted byte-for-byte to `origin/main`. All RTK14 lifecycle repair now lives in one new world-scoped migration, `V38__rtk14_npc_lifecycle_repair.kt` (test: `V38Rtk14NpcLifecycleRepairMigrationTest.kt`), which runs on every world. It uses external-over-classpath effective scenarios, `name[2]`/`nation[4]` action identity, excludes `rtk14Added`, strictly validates event shape, splits verified grouped events while preserving unrelated entries, and fails closed on ambiguity.
- Why V38 rather than an extended V26: a database that already recorded V26 in `flyway_schema_history` never re-runs it, so an extension could not reach upgraded worlds; and on a fresh database Flyway runs before `ScenarioSeedRunner` (an `ApplicationRunner`), so `world_state` is empty and V26 returns immediately, making the extension unreachable on new worlds too. Putting the whole repair in V38, which no world has recorded yet, is what makes already-migrated and freshly seeded worlds converge on the same final state.
- Migration numbering: the claim-request migration was renumbered `V36__general_owner_claim_request.sql` → `V37__general_owner_claim_request.sql`, because `origin/main` already ships `V36__diplomacy_casualties.sql` and two V36s make Flyway fail with a duplicate version.
- The CodeRabbit ambiguity finding is now closed inside V38 (duplicate future-appearance identities fail closed) rather than by extending V26. The remediation also validates importer `appearanceYear <= deathYear` and replaces possession's `takeIf` conditional-delete side effect with an explicit branch. The earlier retained-`general_ex` RNG, denied-reload reconciliation, and effective-scenario fixes remain present. The remediation remains uncommitted in this worktree.
- Private GitHub Actions secret is registered; never print or commit decoded source data. It is a secret, not an Actions input.
- Docker PR #25 changes the scenario mount contract only: `COMPOSE_HOST_DIR` supplies the daemon-visible default and the focused test renders Compose JSON instead of scanning indentation. This is candidate-branch validation, not a completed deployment.
- Next exact sequence: commit/push source fixes; give source PR #356 and Docker PR #25 three new sequential mention reviews on their new exact SHA; remediate any findings; then merge, deploy, reset/reseed only target `pep`, and verify domain health, engine clock, DB stat diversity/source metadata, lifecycle activation, and five-stat possession UI. None of those release steps is complete.
- Latest focused evidence: importer 21, possession 21, and Docker's focused contract test are green; the deep repair-migration re-review is CLEARED. The old V26 focused count no longer applies to this branch, since V26 and its test are reverted to `origin/main`; that coverage moved into `V38Rtk14NpcLifecycleRepairMigrationTest` (9 cases — external-only scenario resolution, external-over-classpath precedence, per-nation deferred identity, duplicate future-appearance fail-closed, missing-scenario fail-closed, plus a new malformed-external-override rollback case), which was not re-run in this documentation pass. Earlier broad backend evidence predates this working-tree remediation and is not a final full gate for it.
- Documentation tooling note: a broad Docker-worktree keyword search was blocked by the repository secret-protection hook because its exclusion glob named a protected secret path. No secret was read; direct inspection of the tracked Compose/test diff succeeded. This is an isolated documentation-tooling limitation, not a product-test result.

---

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
