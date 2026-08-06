# Current Task

## 2026-08-06 — OPENSAM-33 완료 전이 + OPENSAM-34 EC2→GCP 대체

- OPENSAM-33 (D4-14~17 B2 운영 스모크): Jira `할 일` → **`완료`** 전이 완료. 2026-07-31 로컬 완료/released 상태에서 전이만 미실행이었고, D4-14~17 실측 증거와 5모듈 게이트(552 suites / 4,763 tests / 0 failures)를 증거 코멘트로 남겼다. 미해결 non-blocking 1건은 EventSource open 9 vs `turnCompleted` 8 (reconnect/remount 대 중복 구독 UNKNOWN) — D4-16 판정에는 영향 없으나 중복 구독이면 별건이다.
- OPENSAM-34 (D4-31~35 배포 전 Go 조건): 종전 blocker였던 "`ec2-prod` 러너 offline"은 **오진**이었다. 레포 등록 프로덕션 러너 `gcp-prod-opensamguk`은 online이며 라벨은 `[self-hosted, Linux, X64, gcp-prod]`, `ec2-prod` 라벨은 존재하지 않는다. 실제 원인은 predeploy 워크플로만 없어진 라벨을 계속 선택한 drift였다(promote/reset/deploy는 이미 이관 완료).
- 함께 발견한 별건 결함: grader의 `highest_checkout_migration()`이 `*.sql`만 스캔해 Kotlin 마이그레이션 `V38__rtk14_npc_lifecycle_repair.kt`를 놓친다. `.sql`만 37 / Kotlin 포함 38이라, 실제 러너에서 DB가 보고하는 38과 어긋나 **D4-35가 정상 상태를 NO-GO로 오판**했을 것이다. 라벨만 고쳤으면 첫 실행이 그대로 실패한다.
- 브랜치 `ops-predeploy-gcp-retarget`, 커밋 `9da40167` (3파일). 검증: 양쪽 스크립트 `bash -n` PASS, 워크플로 YAML 파싱 PASS, hermetic 계약 테스트 `PASS` EXIT=0, 변경 전 baseline에서는 동일 테스트가 `NO-GO: D4-35 ...`로 실패함을 `git stash`로 확인.
- Jira OPENSAM-34는 **`할 일` 유지**. 실제 D4-31~35 production 관측은 미실행이며 워크플로 dispatch에는 여전히 별도 명시 승인이 필요하다.
- 다음: PR 생성/리뷰 → 머지 후에야 predeploy Go 체크 실행을 논의할 수 있다.

---

## 2026-08-03 — RTK14 full-roster scenario and five-stat surfaces (완료 — 2026-08-04 머지)

- Status (2026-08-06 갱신): **PR #356이 2026-08-04T06:39:59Z에 머지됐다.** 아래 "working tree에만 존재" / "release step 미완" 서술은 당시 기록이며 더 이상 유효하지 않다. `origin/main`에 `V37__general_owner_claim_request.sql`과 `V38__rtk14_npc_lifecycle_repair.kt`가 모두 올라와 있고, 후속 수정 #362·#363까지 머지됐다. 이 절은 이력으로 보존한다.
- 당시 기록: the exact prior source HEAD `725195fea29b3434cc358e3d262c6c440830dab7` review found a P1 released-V26 forward-repair gap. The remediation no longer touches V26: `V26__npc_lifecycle_phase_units.kt` and `V26NpcLifecycleMigrationTest.kt` are reverted byte-for-byte to `origin/main`, and all RTK14 lifecycle repair now lives in one new world-scoped migration, `V38__rtk14_npc_lifecycle_repair.kt` (test: `V38Rtk14NpcLifecycleRepairMigrationTest.kt`), which runs on every world. It is not yet a final full gate, merged change, deployment, reseed, or live verification.
- Migration numbering: the claim-request migration was renumbered `V36__general_owner_claim_request.sql` → `V37__general_owner_claim_request.sql`, because `origin/main` already ships `V36__diplomacy_casualties.sql` and two V36s make Flyway fail with a duplicate version.
- Why V38 instead of extending V26: a database that already recorded V26 in `flyway_schema_history` never re-runs it, so the extension could not reach upgraded worlds; and on a fresh database Flyway runs before `ScenarioSeedRunner` (an `ApplicationRunner`), so `world_state` is empty and V26 returns immediately, making the extension unreachable on new worlds too. Putting the whole repair in V38, which no world has recorded yet, is what makes already-migrated and freshly seeded worlds converge on the same final state.
- Current remediation: V38 resolves the effective scenario external-over-classpath, matches actual deferred action identity at `name[2]`/`nation[4]`, excludes `rtk14Added`, applies universal strict-shape checks, splits valid grouped events while preserving unrelated ones, and fails closed on ambiguity — including the ambiguous future-row case that an earlier draft had tried to close inside V26. The importer rejects `appearanceYear > deathYear`; possession uses an explicit conditional-delete branch rather than a `takeIf` side effect.
- Docker PR #25 remediation replaces the indentation-only Compose scan with a rendered Compose JSON contract and gives the daemon-visible scenario mount a `COMPOSE_HOST_DIR` default. This is candidate-branch validation only.
- Focused evidence: importer 21, possession 21, and the Docker focused contract test are green; the deep repair-migration re-review is CLEARED. The former V26 focused count no longer applies, since V26 and its test are reverted to `origin/main`; that coverage moved into `V38Rtk14NpcLifecycleRepairMigrationTest` (9 cases — external-only scenario resolution, external-over-classpath precedence, per-nation deferred identity, duplicate future-appearance fail-closed, missing-scenario fail-closed, plus a new malformed-external-override rollback case), which was not re-run in this documentation pass. These focused results do not replace a final full gate for the current working tree.
- Remaining release gates: both source PR #356 and Docker PR #25 need three new sequential mention reviews on their new exact SHA, with any findings remediated; only then may they be merged, deployed, followed by `pep` reseed and live DB/API/UI/clock verification.
- Goal: use every officer row from `/Users/apple/Desktop/삼국지14 무장정보.xlsx` in every populated runtime scenario, preserving one-to-one duplicate identities, replacing the five stats, and applying birth/appearance/death lifecycle dates. Existing runtime-only officers remain with reviewed politics/charm overrides.
- In scope:
  - the local RTK14 source-data builder, validation/reporting, and private generated scenario artifacts;
  - runtime scenario tuple decoding and active/deferred appearance scheduling;
  - deferred NPC creation carrying politics/charm;
  - possession routing plus all affected API/UI five-stat surfaces;
  - source and orchestration deployment wiring for private generated scenarios;
  - tests, three sequential mention-triggered PR reviews with fixes, merge, deployment, and live verification.
- Non-goals: tracked generated Koei data, `legacy/**` or golden writes, unrelated archive universes under `data/extracted/scenario`, and secret disclosure.
- Allowed files: `tools/rtk14/**`, relevant scenario seed/import and deferred-NPC logic/tests in `infra/**`, `logic/**`, `app/game-engine/**`, affected `app/game-api/**` DTO/controller/tests, affected `web/game/**` and `web/gateway/**`, `.github/workflows/deploy.yml`, `tools/agent-system/check.py`, `.ai/{task,current-state,ownership,handoff}.md`, and one review artifact. The sibling Docker worktree may change only scenario mount Compose/deployer contract files.
- Acceptance evidence:
  - exactly 1,000 workbook rows are represented once per populated runtime scenario, including all duplicate-name rows; settings-only scenarios remain byte-identical;
  - five stats and birth/appearance/death lifecycle values round-trip through source JSON and generated tuples; no unresolved workbook row remains;
  - future appearances retain politics/charm and occur on the validated effective appearance year;
  - focused Python, logic, infra, engine, API, frontend, typecheck, static workflow, and diff gates pass with XML/tail evidence;
  - three fresh PR review rounds per PR have no unresolved `fix-required`; deployment and live DB/UI observations confirm the result.
- Approved external actions: create/update the private RTK14 GitHub Actions secret without printing it, commit, push, PR, three mention reviews, merge, deploy, and overwrite/reseed the target scenario as requested. Any broader data deletion remains prohibited.

---

## 2026-07-31 OPENSAM-34 — 배포 전 Go 조건 5종

- Status: the local grader and closeout documentation are complete, and the
  final independent re-review is `cleared`. Jira remains `할 일`. D4-31~35
  actual production observation is blocked/incomplete because the `ec2-prod`
  runner was observed offline in both repositories; it requires an EC2 resume
  signal and separate explicit user approval.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
  - "계속."
- Jira acceptance:
  - D4-31 immutable image tag.
  - D4-32 authoritative seed code.
  - D4-33 self-hosted runner health.
  - D4-34 disk headroom.
  - D4-35 DB/Flyway migration state, including V29's concurrent index.
- Repository truth:
  - The current production control plane is the sibling
    `opensamguk-docker` repository; this repository's production compose and
    deploy script are compatibility-only.
  - Scenario seed truth is persisted in `ng_games.env.scenario_code`.
  - V29 is non-transactional, uses `DROP/CREATE INDEX CONCURRENTLY`, and
    requires the configured PostgreSQL session lock.
  - The local manual-only executable grader now covers all five Jira checks;
    no disk Go threshold is approved until an operator supplies the two
    explicit values for an authorized run.
- Approved local implementation:
  - Add a manual-only self-hosted workflow and a read-only grader that accept
    the expected immutable tag, scenario code, and operator-approved free-space
    thresholds as explicit inputs.
  - Compare safe server pins and running image references without printing
    unrelated environment values; prove runner execution, disk thresholds,
    `ng_games.env.scenario_code`, Flyway success/current version, and V29 index
    validity/readiness.
  - Add a hermetic shell contract test and a runbook with exact PASS/FAIL and
    redaction rules.
- Allowed files:
  - `.github/workflows/predeploy-go-check.yml`
  - `tools/ops/predeploy_go_check.sh`
  - `tools/ops/predeploy_go_check_contract_test.sh`
  - `docs/superpowers/runbooks/2026-07-31-opensam-34-predeploy-go-checklist.md`
  - `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`
  - `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/02-plans-micro.md`
  - `docs/superpowers/reviews/2026-07-31-opensam-34-predeploy-go-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/known-issues.md`,
    `.ai/ownership.md`, `.ai/handoff.md`, `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Shell syntax and hermetic contract tests cover all five Go/No-Go branches,
    redaction, invalid inputs, and fail-closed behavior.
  - The V29 migration IT is fresh with zero failures/errors/skips.
  - Independent critique ends `cleared` for the local grader.
  - The actual production observation remains `blocked`, never promoted to
    PASS from local simulation.
- Local completion record:
  - Both predeploy scripts passed `bash -n`; the hermetic contract, workflow
    YAML parse, and scoped untracked-file whitespace check passed.
  - Fresh migration Gradle evidence is V29 `2/0/0/0` and V32 `9/0/0/0`, with
    `BUILD SUCCESSFUL in 2m 2s`.
  - `scripts/agent/verify-changes.sh` classification ran, but `--run` was not
    rerun for OPENSAM-34. This ticket therefore makes no fresh whole-worktree
    execution claim.
  - The runbook and final independent review record the exact manual inputs,
    redaction/fail-closed contract, initial `fix-required` remediations, and
    final `cleared` review result.
- Human approval checkpoints:
  - EC2/prod access or workflow dispatch, `.env*` content access, production
    DB reads, commit, push, PR, merge, deploy, Jira mutation, data deletion,
    secrets, legacy/golden writes, and test weakening remain prohibited.
- Non-goals:
  - Resuming EC2, choosing disk capacity policy for the operator, changing a
    server pin/seed, applying a migration, pruning disk, or starting
    OPENSAM-149 before OPENSAM-34's production observation is authorized and
    complete.

---

## 2026-07-30 OPENSAM-33 — B2 운영 스모크

- Status: completed/released locally. Jira remains `할 일` because no separately
  authorized external transition was performed. OPENSAM-34 now has a local
  grader closeout; its actual production observation remains separately blocked.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
- Jira acceptance:
  - D4-14 s1/QA 60초 cadence 축소 루프.
  - D4-15 Redis intake → daemon → authoritative read → browser-facing SSE
    관측 배선.
  - D4-16 no-op / false-deny / stale frontend 탐지 기준을 구체화하고
    executable smoke assertion으로 만든다.
  - D4-17 seed 중간 실패 뒤 부분 `world_state`가 남지 않고 재시도가
    skip되지 않는지 검증한다.
- Initial repository truth (before execution):
  - D4-17은 `ScenarioSeedCoordinator`의 단일 transaction과
    `ScenarioImporterIT`의 mid-import rollback→successful retry 회귀로 이미
    구현돼 있다. fresh rerun 전에는 완료로 승격하지 않는다.
  - Redis intake/daemon/flush/SSE 각 seam은 존재하지만 60초 local stack에서
    request→terminal→read→browser refresh를 한 실행으로 잇는 grader는 없다.
  - 기존 live E2E는 generic available command의 terminal success를 확인하나
    authoritative state delta나 browser SSE refresh를 요구하지 않아 silent
    no-op/stale UI를 놓칠 수 있다.
- Execution contract:
  - 0바퀴에서 기존 focused backend와 live-E2E contract를 먼저 측정한다.
  - 60초 cadence는 isolated local Compose에서만 활성화하며 production
    compose/default cadence를 바꾸지 않는다.
  - D4-16 기준은 최소한 (a) valid intake가 deterministic reject되지 않음,
    (b) terminal success 뒤 기대한 authoritative read delta 존재,
    (c) 실제 `turnCompleted` 뒤 브라우저 document refresh와 갱신된 read가
    함께 관측됨으로 고정한다.
  - TDD/contract RED를 먼저 관측한 뒤 가장 작은 smoke-harness 변경만 한다.
- Provisional allowed files:
  - `tools/e2e/local_v1_gate.sh`
  - `tools/e2e/local_v1_gate_timeout_contract_test.sh`
  - `web/game/e2e/v1-core-live.spec.ts`
  - `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt`
  - `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/AdminWriteControllerTest.kt`
  - `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`
  - `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/02-plans-micro.md`
  - `docs/loops/opensam-33-operational-smoke-2026-07-30/**`
  - `docs/superpowers/reviews/2026-07-30-opensam-33-operational-smoke-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/known-issues.md`,
    `.ai/ownership.md`, `.ai/handoff.md`, `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Focused backend integration gates are fresh and Testcontainers skips are 0.
  - Isolated local Compose smoke proves 60-second cadence without reading local
    secrets or touching an existing project.
  - One correlated artifact chain shows accepted request ID, terminal success,
    authoritative read delta, and browser refresh after `turnCompleted`.
  - Seed mid-import rollback/retry is fresh green with zero skipped tests.
  - Independent critique ends `cleared` or gaps are explicitly quarantined with
    proof; no `fix-required` remains.
- Human approval checkpoints:
  - Commit, push, merge, deploy, Jira mutation, production/EC2 access, data
    deletion, secret access, legacy/golden writes, and test weakening remain
    prohibited.
- Non-goals:
  - Production cadence changes, production smoke/cutover, generalized
    observability platform work, or opening OPENSAM-34 before OPENSAM-33 is
    verified and released.
- Completion record:
  - Fresh focused XML evidence: `ScenarioImporterIT` 14/0/0/0,
    `RedisCommandStreamIT` 3/0/0/0, `IntakeResultChannelTest` 4/0/0/0, and
    `RealtimeRelayIT` 1/0/0/0. The marker remediation additionally reported
    focused unit 4/4 and Testcontainers IT 1/1 with skip 0; web typecheck and
    the local-gate shell contracts passed. A fresh focused rerun also passed
    shell syntax and timeout contract, web typecheck, `ScenarioMapSeedIT`
    8/0/0/0 (`BUILD SUCCESSFUL` in 2m), and `CommandReserveServiceTest`
    4/0/0/0 plus IT 1/0/0/0 (`BUILD SUCCESSFUL` in 1m 22s).
  - The final isolated Compose artifact is
    `/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`.
    It records `che_요양` intake `202`, same-request reservation/execution
    `200`, durable Redis marker/XRANGE/XINFO/XACK/XPENDING evidence, three
    60-second ticks, authoritative `0/10/7`, and SSE → refresh → rendered DOM.
  - The initial independent `fix-required` findings were remediated: the raw
    `Instant` marker bind now uses `Timestamp.from`, the gate proves one
    matching wake/ACK chain, cleanup removes the exact project resources, and
    phase correlation plus the operational timeout are explicitly asserted.
  - The final independent review is cleared. The remaining QUESTION is nine
    EventSource opens versus eight `turnCompleted` events: reconnect/remount
    versus duplicate subscription is UNKNOWN and non-blocking for the
    stale-refresh acceptance criterion.
  - `scripts/agent/verify-changes.sh --run` executed once: five-module Gradle
    `BUILD SUCCESSFUL in 12m 55s`, 552 suites / 4,763 tests / failures 0 /
    errors 0 / skipped 1; `web/game` typecheck PASS and Vitest 46 files / 232
    tests PASS; `git diff --check` PASS. The wrapper exited 1 only because its
    strict checker found the two existing whole-worktree baselines: the
    user-owned `.codex/config.toml` personal model pin and the historical
    2026-07-27 review missing one anchored Scope/Verdict under the current rule.
  - Explicitly unexecuted: `tools/parity/gate.sh backend`, production deploy or
    EC2 access, commit/push/PR/merge, and Jira transition.
  - ADR-LITE-026 applies to a future PR: after creation, mention the review
    agent in the PR conversation for three separate rounds, fix and reverify
    each round, and merge only with explicit human approval.

---

## 2026-07-30 OPENSAM-32 — 외교 상태 전이 6종

- Status: completed/released locally. Jira remains `할 일` because no
  separately authorized external transition was performed.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
- Jira acceptance:
  - D4-08 `che_종전제의`
  - D4-09 `che_종전수락` (`war → trade`)
  - D4-10 `che_불가침제의`
  - D4-11 `che_불가침수락` (non-aggression state + term)
  - D4-12 `che_불가침파기제의`
  - D4-13 `che_불가침파기수락` (`trade` 복귀 후 선전포고 가능)
- Execution contract:
  - Process each command as a separate parity-close slice; never batch an
    unverified production edit across all six.
  - Freeze PHP path+line, RNG/log/order/state evidence before any behavior edit.
  - Reproduce the current Kotlin reserved-command/message-accept/flush/UI path
    and record a zero-round baseline before forming one root-cause hypothesis.
  - Use TDD red→green for any missing behavior; if the Jira criteria are already
    met, make no speculative source change and close with fresh evidence.
- Initial allowed files (narrow further after reproduction):
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheJongjeonjeui.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheJongjeonSuak.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimJeui.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimSuak.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimPagiJeui.kt`
  - `logic/src/main/kotlin/opensamguk/logic/actions/nation/CheBulgachimPagiSuak.kt`
  - directly corresponding logic tests/goldens (existing fixtures read-only)
  - `app/game-engine/**/DiplomaticMessageHandler*`
  - `app/game-engine/**/ReservedTurnHandler*`
  - `app/game-engine/src/test/kotlin/opensamguk/engine/intake/DiplomaticMessageWorldScopeIT.kt`
  - `infra/**/Diplomacy*` and the diplomacy-only `JdbcFlushExecutor` seam
  - `web/game/app/game/diplomacy/page.tsx`
  - `web/game/components/CommandModal.tsx`
  - `web/game/__tests__/CommandModal.form-spec.test.tsx`
  - `web/game/__tests__/DiplomacyPage.command.test.tsx`
  - `docs/loops/opensam-32-diplomacy-transitions-2026-07-30/**`
  - `docs/superpowers/reviews/2026-07-30-opensam-32-diplomacy-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/ownership.md`, `.ai/handoff.md`,
    `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Six PHP command sources have exact path+line evidence and parity dimensions.
  - Proposal commands create the PHP-shaped diplomatic message action/payload.
  - Accept commands mutate both diplomacy directions with the exact target
    state/term and preserve the next-command constraint (`선전포고` after
    break-accept).
  - The actual reserved-ring → message response → daemon delta → JDBC flush
    path is covered by fresh tests, and the existing diplomacy UI submit path is
    observed or explicitly marked `채점대기`.
  - Independent critique ends `cleared` or a gap is
    `quarantined-with-proof`; no `fix-required` remains.
- Human approval checkpoints:
  - Commit, push, merge, deploy, Jira mutation, production/EC2 access, data
    deletion, secret access, legacy/golden writes, and test weakening remain
    prohibited.
- Non-goals:
  - General diplomacy redesign, invented status semantics, production rollout,
    or opening OPENSAM-33 before this ticket is verified and released.
- Completion record:
  - D4-10의 `che_불가침제의` shortcut이 backend catalog의 authoritative
    `destNationID/year/month` form을 소비한다. 조회 실패, row 누락, form
    누락은 모두 fail-closed하며 기존 scalar shortcut은 유지한다.
  - D4-08/10/12 proposal destination target color는 preload된 상대국
    color를 직렬화한다. destination 없는 격리 테스트는 기존 fallback을
    유지한다.
  - 실제 lifecycle proposal → message JDBC → accept → 양방향 diplomacy
    flush → 같은 월드 declaration을 Testcontainers 회귀로 연결했다.
  - Fresh focused gates: logic 72/72, game-engine 34/34, infra 2/2,
    frontend 16/16, frontend typecheck PASS; backend failure/error/skip 0.
  - Live browser는 필수 compose runtime 설정 부재로 `채점대기`이며,
    accept의 PHP 다중 로그/event 전체 패러티는 Jira 상태 전이 밖의 명시적
    후속 항목이다.
  - 독립 검토의 코드 finding은 모두 해소됐다. 최종 문서 정합화와 Agent OS
    verification 뒤 `Verdict: cleared`로 종결한다.
  - No commit, push, merge, deploy, Jira mutation, production access, data
    deletion, secret access, legacy/golden write, or test weakening occurred.

---

## 2026-07-30 Jira 권장 순서 직렬 실행 — OPENSAM-31 완료

- Status: completed/released locally; Jira remains `할 일`
  because no external status mutation was authorized or performed.
- User request (verbatim):
  - "권장 처리 순서대로 차례대로."
- Goal:
  - Process the agreed Jira order strictly sequentially:
    `OPENSAM-31 → 32 → 33 → 34 → 149 → 35`.
  - Finish and verify one ticket before opening the next ticket's write scope.
  - For OPENSAM-31, document one existing executable command and an objective
    PASS/FAIL criterion for each of seed, load/restart, intake, flush, read,
    SSE, and deploy.
- Approved plan:
  - Reconcile each Jira ticket with repository truth before changing files.
  - Prefer existing tests and scripts; do not invent evidence or weaken gates.
  - Record implementation, verification, and independent review evidence before
    moving to the next ticket.
- Allowed files for OPENSAM-31:
  - `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`
  - `docs/superpowers/reviews/2026-07-30-opensam-31-stabilization-checklist-review.md`
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/ownership.md`, `.ai/handoff.md`
  - `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - D4-01 through D4-07 each name an exact repo-root command.
  - Each item defines observable success and failure, including Gradle XML
    requirements and Docker/Testcontainers skip handling.
  - Every referenced test class/script exists in the repository.
  - Documentation checks and independent review are recorded.
- Human approval checkpoints:
  - Commit, push, merge, deploy, production/EC2 access, data deletion, secret
    access, legacy/golden writes, and test weakening remain prohibited.
  - OPENSAM-34 production checks remain paused until the existing EC2 resume
    condition and separate operational approval are satisfied.
- Non-goals for OPENSAM-31:
  - Runtime behavior changes, executing a production deployment, Jira status
    mutation, or opening the write scope of OPENSAM-32 and later tickets.
- Completion record:
  - Active plan now contains exact D4-01~07 repo-root commands, objective
    PASS/FAIL rules, Testcontainers skip handling, and the production-approval
    boundary.
  - Independent review moved from `fix-required` to `cleared` after removing
    four assertion overclaims.
  - `scripts/agent/verify-changes.sh --run` was executed once against the whole
    dirty worktree. Fresh backend XML: 552 suites / 4,758 tests /
    failures 0 / errors 0 / skipped 1. The wrapper deleted its temporary
    Gradle log, so the literal `BUILD SUCCESSFUL` line was not retained.
  - `web/game` typecheck and 46 files / 227 tests passed; Codex Agent OS
    contract and `git diff --check` passed.
  - Strict checker remains non-green because of two pre-existing branch/worktree
    baselines: user-owned `.codex/config.toml` personal model pin and the
    historical 2026-07-27 review's uppercase `Verdict: CLEARED`, which no longer
    matches the current lowercase anchored-verdict rule.
  - No commit, push, merge, deploy, Jira mutation, production access, data
    deletion, secret access, legacy/golden write, or test weakening occurred.

---

## 2026-07-30 V2 실시간 전투 공통 기반 계획·티켓화

- Status: approved design; implementation planning in progress.
- User request (verbatim):
  - "커밋 및 푸시. 그리고 다음 단계들을 [$superpowers:brainstorming] 이용해서 물어봐서 설계후 지라 타켓과 깃허브 이슈로 넣어줘."
  - "전체 승인."
  - "좋아. 승인."
  - "승인."
- Goal:
  - Convert the approved realtime battle session/authority/order/replay spec into one executable common-foundation implementation plan.
  - Keep land, siege, naval, and 2.5D client as separately specified consumers of that foundation while retaining all three as V2 launch requirements.
  - After human plan approval, create the Jira OPENSAM target/Epic hierarchy and linked GitHub issues with Given-When-Then acceptance criteria.
- Approved design:
  - `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`
  - Dedicated single-world `battle-engine`; authoritative 200ms session actor; WebSocket; durable events/snapshots; campaign handoff/result boundaries.
  - Commander + delegated officers, one human per formation, delayed authority/orders, limited pause, reconnect/AI/deputy succession.
  - Launch baseline 16 formations per side, 12–15 minutes; land/siege/naval required; deterministic headless fallback only for failed realtime gates.
- Allowed files:
  - `docs/superpowers/specs/**`, `docs/superpowers/plans/**`, `docs/superpowers/SESSION_HANDOFF.md`
  - `.ai/task.md`, `.ai/decisions.md`, `.ai/current-state.md`, `.ai/handoff.md`
  - Jira OPENSAM and the connected GitHub repository after plan approval
- Acceptance evidence:
  - Plan maps every common-foundation spec requirement to an exact file/interface/test task with no placeholders.
  - Independent adversarial plan review has no remaining `fix-required`.
  - Jira and GitHub items cross-link to the approved spec/plan and preserve foundation-first dependencies.
- Human approval checkpoints:
  - Implementation plan adoption before external tracker writes.
  - Any implementation, merge, deploy, production change, data deletion, secret access, legacy/golden write, or test weakening needs separate authority.
- Non-goals:
  - Runtime implementation, PR creation, merge/deploy, production/S6 activation, or changing v1 parity behavior.

---

## Previous tracked task contract

- Status: completed — 2026-07-26 감사의 모든 비운영 v1 blocker를 구현·관측했다. checker 수정 뒤 최종 `scripts/agent/verify-changes.sh --run`도 정확히 한 번 기록됐으며, whole-worktree strict 기준선의 유일한 error는 수정하지 않은 사용자 소유 `.codex/config.toml` personal model pin이다.
- Updated: 2026-07-29
- User request (verbatim):
  - "미완성 중 운영전환 제외하고 나머지를 완성시켜. 검증은 로컬 도커로 해."
- Goal:
  - Close every release blocker in audit §6 and every gate in §8 except production/S6 cutover.
  - Preserve PHP `legacy/devsam-core` as grand truth and use `hwe/ts/` only for frontend shape.
  - Prove command, monthly/event, battle/conquest, AI, side-system, world-scope/restart, frontend, and stored-log behavior through real PHP evidence and local Docker.
  - Update the Korean audit and loop ledger from `채점대기` to observed pass/fail evidence.
- Approved plan:
  - Freeze the new loop contract and measure Docker/PHP/browser baselines before implementation.
  - Build shared foundations sequentially, then implement disjoint behavior families with one hypothesis per loop.
  - Require RED reproduction or an explicit missing-path contract before each fix; never weaken goldens/tests.
  - Run targeted gates, full backend/frontend gates, local Docker smoke/restart/two-world checks, PHP capture, and authenticated browser E2E.
  - Obtain an independent adversarial review and remediate every `fix-required` finding.
- Allowed files:
  - `.ai/task.md`, `.ai/current-state.md`, `.ai/ownership.md`, `.ai/handoff.md`
  - `common/**`, `logic/**`, `infra/**`
  - `app/game-api/**`, `app/game-engine/**`
  - `web/game/**`
  - `tools/php-golden/**` capture scripts only; legacy and existing golden fixtures remain read-only
  - `tools/smoke.sh` and local test tooling only if a reproduced verification defect requires it
  - `docs/loops/v1-nonoperational-completion-2026-07-27/**`
  - `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`
  - `docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`
  - `docs/superpowers/SESSION_HANDOFF.md`
- Acceptance evidence:
  - Audit §6.1–§6.8 has no unresolved non-operational `정적대조`, `추론`, or `채점대기` blocker.
  - PHP 92-command cross-layer matrix reaches form/intake/daemon/flush/terminal/UI or records a PHP-proven non-user-facing exception.
  - Battle/conquest, monthly/event, AI 12-month, side-system, and stored-log parity use real PHP captures and deterministic replay gates.
  - Every world-owned read is process-world scoped; same-local-ID two-world and restart-rehydrate Docker tests pass.
  - All PARTIAL/DEAD core-live routes are exercised through authenticated local browser flows and terminal results.
  - `tools/parity/gate.sh backend`, both frontend gates, `./tools/smoke.sh`, PHP golden capture, and `scripts/agent/verify-changes.sh --run` have fresh evidence.
  - Independent review verdict is `cleared`.
- Human approval checkpoints:
  - Commit, push, merge, production deploy/cutover, data deletion, secret access, golden/test weakening, and legacy writes remain prohibited.
- Non-goals:
  - CQRS S6/production rollout, production data or infrastructure changes, v2 implementation, external tracker writes, and speculative divergence from PHP.

## 2026-07-29 완료 기록

- §6.1–§6.8의 비운영 항목은 PHP schema 4 12개월·36순 exact replay,
  92-command matrix, battle/event/AI/side-system capture, scoped read/restart,
  authenticated local Docker E2E로 종결했다.
- 표준 backend gate는 550 suites / 4,753 tests / failure·error 0,
  영향 backend 재검증은 185 suites / 1,172 tests / failure·error 0이다.
  `web/game`은 46 files / 227 tests + typecheck, runtime9 Playwright는
  1 passed / 0 unexpected다.
- independent review는 CLEAR / APPROVE / blockers none이다.
- production/S6, commit, push, merge, deploy, data delete, secret access는
  수행하지 않았다.
- checker의 cleared/quarantined disjoint Scope union 수정 뒤 최종 Agent OS
  verify를 정확히 한 번 실행했다. Gradle 5개 모듈은 `BUILD SUCCESSFUL in
  13m 27s` / 29 tasks, `web/game` typecheck + 46 files / 227 tests, Agent OS
  contract와 diff/whitespace는 PASS다.
- strict checker는 error 1 / warning 0이며 exit 1은 사용자 소유·untouched
  `.codex/config.toml` 최상위 personal model pin 하나뿐이다. 이는 비운영
  기능 완료와 다른 whole-worktree strict baseline이므로 strict green 또는
  ship/merge ready를 뜻하지 않는다. 증거:
  `.omo/evidence/v1-final/verify-changes-final2/{verify-changes.log,exit-code.txt}`.

---

## 2026-08-02 — canonical alphanumeric game-server ID release closeout

- PR: #354 — `https://github.com/peppone-choi/opensamguk/pull/354`.
- Status: the first Codex review found two valid issues against
  `8d1a64fee0651b2977f13af27eb6b91b43577342`. They were remediated in exact
  code SHA `1683100447be91abc6eb2629969c9ee3c16bac5e`; an independent re-review
  of that exact fixed code SHA is **CLEARED**. This source verdict does not
  satisfy the separate fresh three-round PR review requirement.
- Canonical public contract: accept raw `[A-Za-z0-9]+`, canonicalize to
  lowercase `[a-z0-9]+`, and derive the internal Compose/container identity as
  `s` + public ID. Examples: `pep` → `pep` / `spep`; `s1` → `s1` / `ss1`;
  `A1` → `a1` / `sa1`.
- `all`, `main`, and current top-level game route names are reserved to avoid
  control and URL collisions. `current` remains a valid public ID.
- First-review remediation:
  - compatibility Nginx now accepts canonical public paths and derives the
    internal `s<public>` upstream/container name only at that boundary;
  - deploy, promote, and reset workflows now apply matching reserved-public-ID
    guards instead of a one-off `all` check.
- Independent re-review evidence for the fixed SHA: gateway 64 tests; game 220
  tests; FrontInfo XML `27/0/0`; Admin XML `26/0/0`; and the fixed-SHA
  workflow/Nginx/Compose contract review.
- Approved external actions remain those of the governing OPENSAM-34 task:
  GCP changes/configuration, commit, push, PR creation, three mention-triggered
  reviews, merge, deployment, and live verification. They are authorized scope,
  not completed actions; secrets stay redacted and data deletion is out of
  scope.
- Remaining release gates: Docker repository review/fix; a new three-round
  `@codex` PR review sequence after the next documentation commit/current HEAD;
  then merge, deploy, and live-production verification. No final PR review,
  merge, deployment, or live result is claimed.

## 2026-08-02 OPENSAM-34 — GCP production migration and launch

- Status: in progress; GCP VM/runtime/network/DNS are provisioned, and PR
  review findings are being remediated before the approved merge and deploy.
- User-approved scope:
  - migrate active production GitHub Actions runner selectors and operator
    guidance from the retired EC2 target to GCP Compute Engine
    `e2-standard-2` / `gcp-prod`;
  - configure the GCP production control repository and generated runtime
    secrets without printing their values;
  - request three PR mention-triggered reviews, fix valid findings, then merge
    and deploy;
  - verify `sam.peppone.dev` through Cloudflare and live health routes.
- Approved external actions: GCP resource changes, production configuration,
  commit, push, PR creation, merge, and deployment. Secret values remain
  redacted and data deletion remains out of scope.
- Allowed repository files: production workflows, active deployment/operator
  documentation, the compatibility deploy script/compose header, and this task
  contract. No game logic, golden fixture, legacy source, image pin, or runtime
  secret may enter the diff.
- Completion evidence: matching online `gcp-prod` runners, three successful PR
  review rounds with no unresolved `fix-required`, green targeted syntax/diff
  checks, an observed deployment run, and live domain health.

---

## Previous completed task — documentation and agent harness refresh

- Status: completed — independent review cleared; docs-only verification passed except for the pre-existing `.codex/config.toml` personal-model baseline.
- Updated: 2026-07-25
- Goal: refresh the current documentation and agent harness, with `README.md` as the primary onboarding surface.
- Approved plan:
  - Update `README.md` to match the current July 2026 implementation, local quick start, CQRS consistency/runtime state, and shared/server production model.
  - Update `.claude/HARNESS.md` from its obsolete parity-only/deploy narrative to the current Agent OS, parity, verification, and deployment entry points.
  - Correct the comment-language and deployment wording in the tracked parity skills.
  - Refresh `docs/agent/project-overview.md` so README can link to a current bounded status source.
- Allowed files:
  - `README.md`
  - `.claude/HARNESS.md`
  - `.claude/skills/parity-close/SKILL.md`
  - `.claude/skills/parity-ship/SKILL.md`
  - `docs/agent/project-overview.md`
  - `docs/agent/lifecycle-ops.md`
  - `.ai/task.md`
  - `.ai/current-state.md`
  - `.ai/ownership.md`
  - `docs/superpowers/reviews/2026-07-25-docs-harness-refresh-review.md`
- Acceptance evidence:
  - No obsolete `V1..V10`, `appleboy/ssh-action`, unresolved `commandBlockMs` wiring-gap, or Korean-code-comment claim remains in the refreshed surfaces.
  - README quick start names required environment variables without exposing values or secrets.
  - Production guidance points to `opensamguk-docker` shared/server orchestration and clearly labels repository production compose/manual deploy as compatibility-only.
  - Parity guidance distinguishes immediate intake from turn-reserved ring admission and never authorizes commit/push/merge/deploy without separate human approval.
  - `git diff --check`, path/link checks, and `tools/agent-system/check.py` results are recorded.
- Human approval checkpoints:
  - Commit, push, merge, deploy, data deletion, or secret access remain prohibited without separate approval.
- Non-goals:
  - Runtime/code/schema changes, legacy or golden edits, production operations, and external tracker mutations.
